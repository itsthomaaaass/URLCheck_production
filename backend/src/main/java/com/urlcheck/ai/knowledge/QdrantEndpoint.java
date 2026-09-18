package com.urlcheck.ai.knowledge;

/**
 * Where Qdrant listens, parsed from the single URL the environment provides.
 *
 * <p>Qdrant Cloud hands out one HTTPS endpoint, while the gRPC client wants a
 * host, a port and a flag. Splitting the URL here keeps the parsing in one
 * testable place instead of spreading it through the client setup, and it means
 * the deployment only has to know one value.
 *
 * @param host   hostname, without scheme and without port
 * @param port   gRPC port; Qdrant's default is 6334
 * @param useTls true for an https endpoint, which is what Qdrant Cloud requires
 */
public record QdrantEndpoint(String host, int port, boolean useTls) {

    private static final int DEFAULT_GRPC_PORT = 6334;

    /**
     * @throws IllegalArgumentException when the value is blank or has no host,
     *         because starting with a URL that cannot work would only move the
     *         failure to the first question a user asks
     */
    public static QdrantEndpoint parse(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(
                    "app.ai.knowledge.qdrant.url is empty: set QDRANT_URL, or turn the knowledge base"
                            + " off with AI_KNOWLEDGE_ENABLED=false");
        }

        String value = url.trim();
        // Only a plain http:// endpoint is unencrypted; anything else (https, or
        // a bare host) is treated as TLS, which is the safe assumption and the
        // only one Qdrant Cloud accepts.
        boolean useTls = !value.startsWith("http://");
        String withoutScheme = value.replaceFirst("^[A-Za-z][A-Za-z0-9+.-]*://", "");

        int path = withoutScheme.indexOf('/');
        if (path >= 0) {
            withoutScheme = withoutScheme.substring(0, path);
        }

        int colon = withoutScheme.lastIndexOf(':');
        if (colon < 0) {
            return new QdrantEndpoint(requireHost(withoutScheme, url), DEFAULT_GRPC_PORT, useTls);
        }

        String host = requireHost(withoutScheme.substring(0, colon), url);
        try {
            return new QdrantEndpoint(host, Integer.parseInt(withoutScheme.substring(colon + 1)), useTls);
        }
        catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Qdrant URL has a port that is not a number: " + url, ex);
        }
    }

    private static String requireHost(String host, String url) {
        if (host.isBlank()) {
            throw new IllegalArgumentException("Qdrant URL has no host: " + url);
        }
        return host;
    }
}
