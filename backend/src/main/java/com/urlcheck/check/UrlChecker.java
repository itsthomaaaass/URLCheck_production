package com.urlcheck.check;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;

import javax.net.ssl.SSLException;

import org.springframework.stereotype.Component;

import com.urlcheck.security.SsrfGuard;

/**
 * Performs the actual accessibility probe.
 *
 * <p>Free of persistence and of any knowledge about users: it turns a URL into
 * a {@link CheckResult} and nothing else, so a later scheduled checker can
 * reuse it as is.
 *
 * <p>URLs come from users, so this class is the SSRF boundary: every hop is
 * screened by {@link SsrfGuard} before a request is built. Redirects are
 * followed by hand ({@link HttpClient.Redirect#NEVER}) so each new target is
 * screened too; letting the client follow them would let a public host bounce
 * the backend into a private one.
 */
@Component
public class UrlChecker {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String USER_AGENT = "UrlCheckBot/0.1";
    private static final String ACCEPT = "*/*";
    private static final int MAX_REDIRECTS = 5;
    private static final Set<Integer> ALLOWED_PORTS = Set.of(80, 443);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * Issues one GET request, following redirects by hand. This method does not
     * throw for network problems: an unreachable URL is reported as a
     * {@link CheckStatus#DOWN} result carrying the reason in
     * {@link CheckErrorType}. A target that fails the SSRF screen is never
     * contacted and comes back as {@link CheckErrorType#BLOCKED_TARGET}.
     *
     * <p>The response body is discarded; only the status, timing and final URI
     * are used.
     */
    public CheckResult check(Long urlId, String url) {
        URI uri = parse(url);
        if (uri == null) {
            return failure(urlId, CheckErrorType.INVALID_URL, 0L);
        }

        long startedAt = System.nanoTime();
        try {
            for (int redirects = 0; ; redirects++) {
                CheckErrorType rejected = inspect(uri);
                if (rejected != null) {
                    return failure(urlId, rejected, elapsedMillis(startedAt));
                }

                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(REQUEST_TIMEOUT)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", ACCEPT)
                        .GET()
                        .build();

                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                int httpStatus = response.statusCode();
                long responseTimeMs = elapsedMillis(startedAt);

                Optional<URI> redirect = nextHop(uri, response);
                if (redirect.isPresent()) {
                    if (redirects >= MAX_REDIRECTS) {
                        return failure(urlId, CheckErrorType.TOO_MANY_REDIRECTS, responseTimeMs);
                    }
                    uri = redirect.get();
                    continue;
                }

                boolean up = httpStatus >= 200 && httpStatus < 400;
                return new CheckResult(
                        urlId,
                        now(),
                        up ? CheckStatus.UP : CheckStatus.DOWN,
                        httpStatus,
                        responseTimeMs,
                        uri.toString(),
                        up ? null : CheckErrorType.HTTP_ERROR);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return failure(urlId, CheckErrorType.INTERRUPTED, elapsedMillis(startedAt));
        } catch (IOException ex) {
            return failure(urlId, classify(ex), elapsedMillis(startedAt));
        }
    }

    private static URI parse(String url) {
        if (url == null) {
            return null;
        }
        try {
            return URI.create(url);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Screens one hop before it is requested.
     *
     * @return the reason the target is refused, or {@code null} when it may be
     *         contacted
     */
    private static CheckErrorType inspect(URI uri) {
        if (!isHttpUrl(uri)) {
            return CheckErrorType.INVALID_URL;
        }
        if (!ALLOWED_PORTS.contains(effectivePort(uri))) {
            return CheckErrorType.BLOCKED_TARGET;
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(stripBrackets(uri.getHost()));
        } catch (UnknownHostException ex) {
            return CheckErrorType.DNS_ERROR;
        }
        for (InetAddress address : addresses) {
            if (SsrfGuard.isBlocked(address)) {
                return CheckErrorType.BLOCKED_TARGET;
            }
        }
        return null;
    }

    /**
     * The next hop of a redirect, resolved against the current URL. Empty when
     * the response is not a redirect the checker can follow.
     */
    private static Optional<URI> nextHop(URI current, HttpResponse<?> response) {
        int status = response.statusCode();
        boolean isRedirect = status == 301 || status == 302 || status == 303
                || status == 307 || status == 308;
        if (!isRedirect) {
            return Optional.empty();
        }
        return response.headers().firstValue("Location")
                .flatMap(location -> {
                    try {
                        return Optional.of(current.resolve(location));
                    } catch (IllegalArgumentException malformed) {
                        return Optional.empty();
                    }
                });
    }

    private static int effectivePort(URI uri) {
        int port = uri.getPort();
        if (port != -1) {
            return port;
        }
        return uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
    }

    private static String stripBrackets(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static boolean isHttpUrl(URI uri) {
        String scheme = uri.getScheme();
        return uri.getHost() != null
                && scheme != null
                && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
    }

    private static CheckErrorType classify(IOException failure) {
        if (hasCause(failure, HttpTimeoutException.class)) {
            return CheckErrorType.TIMEOUT;
        }
        if (hasCause(failure, UnknownHostException.class)) {
            return CheckErrorType.DNS_ERROR;
        }
        if (hasCause(failure, SSLException.class)) {
            return CheckErrorType.SSL_ERROR;
        }
        if (hasCause(failure, ConnectException.class)) {
            return CheckErrorType.CONNECTION_REFUSED;
        }
        return CheckErrorType.IO_ERROR;
    }

    /**
     * The JDK client wraps the interesting failure inside the IOException it
     * throws, so look at the whole cause chain rather than the top exception.
     */
    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static CheckResult failure(Long urlId, CheckErrorType errorType, long responseTimeMs) {
        return new CheckResult(urlId, now(), CheckStatus.DOWN, null, responseTimeMs, null, errorType);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
