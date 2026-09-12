package com.urlcheck.check;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 of a response body, read in constant memory. */
public final class ContentHasher {

    private static final int BUFFER_SIZE = 8192;

    /**
     * Hashes at most {@code maxBytes} of the stream, then stops reading, so an
     * oversized file costs no more than a capped page. A digest of a truncated
     * body is still deterministic, which is all change detection needs.
     */
    public static String sha256(InputStream body, long maxBytes) throws IOException {        MessageDigest digest = newDigest();
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = maxBytes;
        while (remaining > 0) {
            int read = body.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read == -1) {
                break;
            }
            digest.update(buffer, 0, read);
            remaining -= read;
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private ContentHasher() {
    }
}