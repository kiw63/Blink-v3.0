package com.blink.browser;

/** Pure Java filename safety helper for download destinations. */
public final class SafeFilename {
    private SafeFilename() {}
    public static String sanitize(String input) {
        String name = input == null ? "download.bin" : input;
        name = name.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").trim();
        while (name.startsWith(".")) name = name.substring(1);
        if (name.isEmpty()) name = "download.bin";
        if (name.length() > 110) name = name.substring(0, 110);
        return name;
    }
}
