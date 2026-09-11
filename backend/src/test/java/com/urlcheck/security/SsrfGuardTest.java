package com.urlcheck.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SsrfGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1",
            "127.255.255.254",
            "0.0.0.0",
            "10.0.0.1",
            "172.16.5.4",
            "172.31.255.255",
            "192.168.1.1",
            "169.254.169.254",
            "100.64.0.1",
            "192.0.2.10",
            "198.18.0.1",
            "240.0.0.1",
            "224.0.0.1",
            "::1",
            "::",
            "fc00::1",
            "fd12:3456::1",
            "fe80::1",
            "ff02::1",
            "::ffff:127.0.0.1",
            "64:ff9b::7f00:1"
    })
    void blocksAddressesThatAreNotPublic(String literal) throws Exception {
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName(literal))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "8.8.8.8",
            "1.1.1.1",
            "93.184.216.34",
            "100.63.0.1",
            "172.32.0.1",
            "2606:4700:4700::1111",
            "2001:4860:4860::8888"
    })
    void allowsPublicAddresses(String literal) throws Exception {
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName(literal))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "localhost",
            "api.localhost",
            "printer.local",
            "jenkins.internal",
            "127.0.0.1",
            "10.1.2.3",
            "::1"
    })
    void blocksInternalHostNames(String host) {
        assertThat(SsrfGuard.isBlockedHost(host)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.com", "www.google.com", "sub.example.org"})
    void allowsPublicHostNames(String host) {
        assertThat(SsrfGuard.isBlockedHost(host)).isFalse();
    }

    @Test
    void blocksBlankHost() {
        assertThat(SsrfGuard.isBlockedHost(null)).isTrue();
        assertThat(SsrfGuard.isBlockedHost("  ")).isTrue();
    }
}
