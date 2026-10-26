package com.phishlens.scanapi.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class UrlFeatureExtractorTest {

    // Expected values are risk-model's extract_features(url), computed in Python.
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            // url                                                  | len | digits | ip    | at    | subs | tld   | https
            "http://192.168.10.5/secure-login/verify@account-update.xyz | 58 | 9 | true  | true  | 0 | true  | false",
            "https://www.google.com                                     | 22 | 0 | false | false | 1 | false | true",
            "example.com/login                                          | 17 | 0 | false | false | 0 | false | false",
            "https://a.b.c.d.example.tk/login?id=12345678               | 44 | 8 | false | false | 4 | true  | true",
            "http://user@login.secure-bank.com.verify.top:8080/a?b=1    | 55 | 5 | false | true  | 3 | true  | false",
            "HTTPS://Sub.Example.ZIP/x                                  | 25 | 0 | false | false | 1 | true  | true",
            "http://example.com/redirect?to=evil.xyz#frag               | 44 | 0 | false | false | 0 | true  | false"})
    void matchesPythonFeatures(String url, int length, int digits, boolean ip, boolean at,
            int subdomains, boolean tld, boolean https) {
        assertThat(UrlFeatureExtractor.extract(url))
                .isEqualTo(new UrlFeatures(length, digits, ip, at, subdomains, tld, https));
    }
}
