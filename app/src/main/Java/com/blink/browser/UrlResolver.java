package com.blink.browser;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.regex.Pattern;

/** Only web addresses/searches are sent into the WebView by the location bar. */
public final class UrlResolver {
    private static final Pattern DOMAIN = Pattern.compile(
        "(?i)^(?:localhost|(?:[a-z0-9-]+\\.)+[a-z0-9-]{2,}|(?:\\d{1,3}\\.){3}\\d{1,3})(?::\\d{1,5})?(?:[/?#][^\\s]*)?$"
    );

    private UrlResolver() {}

    public static String resolve(String userInput) {
        String input = userInput == null ? "" : userInput.trim();
        if (input.isEmpty() || input.equalsIgnoreCase("about:blank")) return "about:blank";

        if (input.regionMatches(true, 0, "https://", 0, 8)
                || input.regionMatches(true, 0, "http://", 0, 7)) {
            try {
                URI uri = new URI(input);
                String scheme = uri.getScheme();
                if (uri.getHost() != null && scheme != null
                        && (scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
                    return input;
                }
            } catch (Exception ignored) { }
            return search(input);
        }
        if (DOMAIN.matcher(input).matches()) {
            String prefix = input.toLowerCase().startsWith("localhost") || input.matches("^\\d.*")
                    ? "http://" : "https://";
            return prefix + input;
        }
        return search(input);
    }

    public static boolean isWebUrl(String value) {
        if (value == null) return false;
        try {
            URI uri = new URI(value);
            return uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (Exception ignored) { return false; }
    }

    private static String search(String query) {
        try {
            return "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            return "https://www.google.com";
        }
    }
}
