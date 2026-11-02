package com.phishlens.scanapi.scoring;

import com.phishlens.scanapi.url.UrlParts;
import java.util.regex.Pattern;

/** Java port of risk-model/app/features.py::extract_features. Keep the two in step. */
public final class UrlFeatureExtractor {

    private static final Pattern SUSPICIOUS_TLD = Pattern.compile(
            "\\.(?:xyz|top|tk|ml|ga|cf|gq|click|work|zip|country|support)(?=[/:?#]|$)",
            Pattern.CASE_INSENSITIVE);

    private UrlFeatureExtractor() {
    }

    public static UrlFeatures extract(String url) {
        // length, digits, '@' and TLD are measured on the raw trimmed string, as in Python.
        String raw = url.strip();
        String normalized = UrlParts.normalize(raw);
        String host = UrlParts.hostname(normalized);
        boolean ipHost = UrlParts.isIpv4(host);
        return new UrlFeatures(
                raw.codePointCount(0, raw.length()),
                (int) raw.codePoints().filter(Character::isDigit).count(),
                ipHost,
                raw.indexOf('@') >= 0,
                ipHost ? 0 : Math.max(countLabels(host) - 2, 0),
                SUSPICIOUS_TLD.matcher(raw).find(),
                UrlParts.scheme(normalized).equals("https"));
    }

    private static int countLabels(String host) {
        int labels = 0;
        for (String label : host.split("\\.")) {
            if (!label.isEmpty()) {
                labels++;
            }
        }
        return labels;
    }
}
