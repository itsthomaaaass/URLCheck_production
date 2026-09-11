package com.urlcheck.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Screens outbound targets so a user-supplied URL cannot make the backend
 * reach services that are only reachable from the server itself.
 *
 * <p>This is the classic SSRF (server-side request forgery) risk: the monitor
 * fetches whatever URL a user stores, so {@code http://127.0.0.1},
 * {@code http://169.254.169.254} (cloud metadata) or a name that resolves to
 * an RFC 1918 address would otherwise turn the backend into a proxy for the
 * internal network.
 *
 * <p>The policy is deliberately conservative: anything that is not a globally
 * routable public address is blocked. Checks run on resolved
 * {@link InetAddress} values, so the JDK has already normalised decimal, octal,
 * hex and IPv4-mapped forms before they are judged.
 */
public final class SsrfGuard {

    private static final byte[] IPV4_MULTICAST = ipv4(224, 0, 0, 0);
    private static final byte[] ULA = ipv6(0xfc);
    private static final byte[] LINK_LOCAL_V6 = ipv6(0xfe, 0x80);
    private static final byte[] MULTICAST_V6 = ipv6(0xff);
    private static final byte[] DOCUMENTATION_V6 = ipv6(0x20, 0x01, 0x0d, 0xb8);
    private static final byte[] DISCARD_V6 = ipv6(0x01, 0x00);
    private static final byte[] NAT64_V6 = ipv6(0x00, 0x64, 0xff, 0x9b);

    private SsrfGuard() {
    }

    /**
     * @return true when the address must not be contacted
     */
    public static boolean isBlocked(InetAddress address) {
        InetAddress candidate = unwrapIpv4Mapped(address);

        if (candidate.isAnyLocalAddress()
                || candidate.isLoopbackAddress()
                || candidate.isLinkLocalAddress()
                || candidate.isSiteLocalAddress()
                || candidate.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = candidate.getAddress();
        if (bytes.length == 4) {
            return isBlockedIpv4(bytes);
        }
        if (bytes.length == 16) {
            return isBlockedIpv6(bytes);
        }
        return true;
    }

    /**
     * Write-time convenience for a host taken straight from a URL. It never
     * performs a DNS lookup: only the reserved local names and IP literals are
     * judged, so saving stays fast. A name that resolves to an internal address
     * is still caught at request time by {@link #isBlocked}.
     *
     * @return true when the host is clearly internal
     */
    public static boolean isBlockedHost(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String name = stripBrackets(host).toLowerCase(Locale.ROOT);
        if (name.equals("localhost")
                || name.endsWith(".localhost")
                || name.endsWith(".local")
                || name.endsWith(".internal")) {
            return true;
        }
        if (!looksLikeIpLiteral(name)) {
            return false;
        }
        try {
            return isBlocked(InetAddress.getByName(name));
        } catch (UnknownHostException notALiteral) {
            return false;
        }
    }

    private static boolean isBlockedIpv4(byte[] address) {
        return inRange(address, ipv4(0, 0, 0, 0), 8)
                || inRange(address, ipv4(10, 0, 0, 0), 8)
                || inRange(address, ipv4(100, 64, 0, 0), 10)
                || inRange(address, ipv4(127, 0, 0, 0), 8)
                || inRange(address, ipv4(169, 254, 0, 0), 16)
                || inRange(address, ipv4(172, 16, 0, 0), 12)
                || inRange(address, ipv4(192, 0, 0, 0), 24)
                || inRange(address, ipv4(192, 0, 2, 0), 24)
                || inRange(address, ipv4(192, 168, 0, 0), 16)
                || inRange(address, ipv4(198, 18, 0, 0), 15)
                || inRange(address, ipv4(198, 51, 100, 0), 24)
                || inRange(address, ipv4(203, 0, 113, 0), 24)
                || inRange(address, IPV4_MULTICAST, 4)
                || inRange(address, ipv4(240, 0, 0, 0), 4);
    }

    private static boolean isBlockedIpv6(byte[] address) {
        if (inRange(address, ULA, 7)
                || inRange(address, LINK_LOCAL_V6, 10)
                || inRange(address, MULTICAST_V6, 8)
                || inRange(address, DOCUMENTATION_V6, 32)
                || inRange(address, DISCARD_V6, 64)) {
            return true;
        }
        if (inRange(address, NAT64_V6, 96)) {
            byte[] embedded = {address[12], address[13], address[14], address[15]};
            return isBlockedIpv4(embedded);
        }
        return false;
    }

    private static boolean inRange(byte[] address, byte[] network, int prefixBits) {
        if (address.length != network.length) {
            return false;
        }
        int fullBytes = prefixBits / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (address[i] != network[i]) {
                return false;
            }
        }
        int remainingBits = prefixBits % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = (0xFF << (8 - remainingBits)) & 0xFF;
        return (address[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    /**
     * The JDK can report {@code ::ffff:a.b.c.d} as an IPv4-mapped IPv6 address,
     * which would hide the real IPv4 address from the checks above.
     */
    private static InetAddress unwrapIpv4Mapped(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != 16 || !isIpv4Mapped(bytes)) {
            return address;
        }
        try {
            return InetAddress.getByAddress(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]});
        } catch (UnknownHostException impossible) {
            return address;
        }
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
    }

    private static boolean looksLikeIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}")
                || host.matches("\\d+")
                || host.matches("0x[0-9a-f]+");
    }

    private static String stripBrackets(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static byte[] ipv4(int a, int b, int c, int d) {
        return new byte[]{(byte) a, (byte) b, (byte) c, (byte) d};
    }

    private static byte[] ipv6(int... leading) {
        byte[] bytes = new byte[16];
        for (int i = 0; i < leading.length && i < bytes.length; i++) {
            bytes[i] = (byte) leading[i];
        }
        return bytes;
    }
}
