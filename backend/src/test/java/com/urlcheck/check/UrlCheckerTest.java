package com.urlcheck.check;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlCheckerTest {

    private final UrlChecker checker = new UrlChecker();

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not a url",
            "ftp://example.com",
            "file:///etc/passwd",
            "example.com/no-scheme"
    })
    void rejectsUrlsThatAreNotHttp(String url) {
        CheckResult result = checker.check(1L, url);
        assertThat(result.status()).isEqualTo(CheckStatus.DOWN);
        assertThat(result.errorType()).isEqualTo(CheckErrorType.INVALID_URL);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/",
            "http://127.0.0.1:80/",
            "http://localhost/",
            "http://169.254.169.254/latest/meta-data/",
            "http://10.1.2.3/",
            "http://192.168.1.1/",
            "http://[::1]/"
    })
    void refusesToContactInternalTargets(String url) {
        CheckResult result = checker.check(1L, url);
        assertThat(result.status()).isEqualTo(CheckStatus.DOWN);
        assertThat(result.errorType()).isEqualTo(CheckErrorType.BLOCKED_TARGET);
    }

    @Test
    void refusesNonWebPorts() {
        CheckResult result = checker.check(1L, "http://example.com:8080/");
        assertThat(result.errorType()).isEqualTo(CheckErrorType.BLOCKED_TARGET);
    }

    @Test
    void rejectsNullUrl() {
        CheckResult result = checker.check(1L, null);
        assertThat(result.errorType()).isEqualTo(CheckErrorType.INVALID_URL);
    }
}
