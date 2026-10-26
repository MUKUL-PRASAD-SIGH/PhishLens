package com.phishlens.scanapi.url;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * URL splitting that mirrors risk-model/app/features.py, which uses Python's
 * urllib.parse.urlparse. java.net.URI is deliberately not used: it is stricter than
 * urlparse and parses some inputs differently, which would make the validator and the
 * fallback score disagree with risk-model on the same URL.
 */
public final class UrlParts {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final String SCHEME_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+-.";

    private UrlParts() {
    }

    /** features.normalize: trim, and prepend http:// when the string has no "://". */
    public static String normalize(String url) {
        String trimmed = url.strip();
        return trimmed.contains("://") ? trimmed : "http://" + trimmed;
    }

    /** urlsplit's scheme rule, lower-cased; empty when there is no scheme. */
    public static String scheme(String url) {
        int colon = url.indexOf(':');
        if (colon <= 0 || !isAsciiLetter(url.charAt(0))) {
            return "";
        }
        String candidate = url.substring(0, colon);
        for (int i = 0; i < candidate.length(); i++) {
            if (SCHEME_CHARS.indexOf(candidate.charAt(i)) < 0) {
                return "";
            }
        }
        return candidate.toLowerCase(Locale.ROOT);
    }

    /**
     * urlparse(url).hostname, lower-cased; empty when there is none. The netloc runs from
     * "//" to the first '/', '?' or '#'; userinfo before the last '@' and any port are dropped.
     */
    public static String hostname(String url) {
        String scheme = scheme(url);
        String rest = scheme.isEmpty() ? url : url.substring(scheme.length() + 1);
        if (!rest.startsWith("//")) {
            return "";
        }
        int end = rest.length();
        for (char delimiter : new char[] {'/', '?', '#'}) {
            int index = rest.indexOf(delimiter, 2);
            if (index >= 0) {
                end = Math.min(end, index);
            }
        }
        String netloc = rest.substring(2, end);
        String hostInfo = netloc.substring(netloc.lastIndexOf('@') + 1);
        String host;
        int openBracket = hostInfo.indexOf('[');
        if (openBracket >= 0) {
            String bracketed = hostInfo.substring(openBracket + 1);
            int closeBracket = bracketed.indexOf(']');
            host = closeBracket >= 0 ? bracketed.substring(0, closeBracket) : bracketed;
        } else {
            int colon = hostInfo.indexOf(':');
            host = colon >= 0 ? hostInfo.substring(0, colon) : hostInfo;
        }
        return host.toLowerCase(Locale.ROOT);
    }

    /** features._IPV4_RE: four dot-separated groups of 1-3 digits. */
    public static boolean isIpv4(String host) {
        return IPV4.matcher(host).matches();
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
