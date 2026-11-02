package com.phishlens.scanapi.validation;

import com.phishlens.scanapi.url.UrlParts;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Normalises like features.py (prepends http:// when there is no "://"), then requires an
 * http or https scheme, no whitespace, and a host that contains a dot or is an IPv4 literal.
 */
public class ScanUrlValidator implements ConstraintValidator<ScanUrl, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String trimmed = value.strip();
        if (trimmed.codePoints().anyMatch(Character::isWhitespace)) {
            return false;
        }
        String normalized = UrlParts.normalize(trimmed);
        String scheme = UrlParts.scheme(normalized);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return false;
        }
        String host = UrlParts.hostname(normalized);
        return !host.isEmpty() && (host.contains(".") || UrlParts.isIpv4(host));
    }
}
