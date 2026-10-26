package com.phishlens.scanapi.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScanUrlValidatorTest {

    private final ScanUrlValidator validator = new ScanUrlValidator();

    @ParameterizedTest
    @ValueSource(strings = {
            "http://192.168.10.5/secure-login/verify@account-update.xyz",
            "https://www.google.com",
            "example.com/login",
            "HTTPS://Sub.Example.ZIP/x",
            "http://user@login.secure-bank.com.verify.top:8080/a?b=1",
            "1.2.3.4",
            "  https://example.com  "})
    void accepts(String url) {
        assertThat(validator.isValid(url, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not a url",
            "ftp://example.com",
            "localhost",
            "http://",
            "http:///path",
            "javascript:alert(1)",
            "http://[::1]/",
            "exa mple.com",
            "https://example.com/a b"})
    void rejects(String url) {
        assertThat(validator.isValid(url, null)).isFalse();
    }
}
