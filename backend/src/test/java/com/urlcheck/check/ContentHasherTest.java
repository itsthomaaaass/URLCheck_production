package com.urlcheck.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ContentHasherTest {

    @Test
    void hashesAnEmptyBodyToTheKnownDigest() throws Exception {
        assertThat(ContentHasher.sha256(bytes(""), 1024))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void hashesUtf8Bytes() throws Exception {
        assertThat(ContentHasher.sha256(bytes("hello"), 1024))
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    void stopsReadingAtTheByteCap() throws Exception {
        // "hello world" capped at five bytes must hash exactly like "hello".
        assertThat(ContentHasher.sha256(bytes("hello world"), 5))
                .isEqualTo(ContentHasher.sha256(bytes("hello"), 1024));
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}