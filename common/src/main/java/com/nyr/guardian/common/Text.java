package com.nyr.guardian.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Colour codes and placeholders for chat text, as plain section-sign strings every server type accepts. */
public final class Text {

    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
    private static final String CODES = "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx";

    private Text() {
    }

    /** Turns {@code &a} codes and {@code &#12abef} hex colours into section-sign codes. */
    public static String color(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        Matcher hex = HEX.matcher(raw);
        StringBuilder expanded = new StringBuilder(raw.length() + 16);
        while (hex.find()) {
            StringBuilder code = new StringBuilder("§x");
            for (char c : hex.group(1).toLowerCase(java.util.Locale.ROOT).toCharArray()) {
                code.append('§').append(c);
            }
            hex.appendReplacement(expanded, Matcher.quoteReplacement(code.toString()));
        }
        hex.appendTail(expanded);
        char[] chars = expanded.toString().toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (chars[i] == '&' && CODES.indexOf(chars[i + 1]) >= 0) {
                chars[i] = '§';
                chars[i + 1] = Character.toLowerCase(chars[i + 1]);
            }
        }
        return new String(chars);
    }

    /** Replaces {@code {key}} with its value for each key/value pair given. */
    public static String fill(String template, Object... pairs) {
        if (template == null) {
            return "";
        }
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("placeholders come in key/value pairs");
        }
        String out = template;
        for (int i = 0; i < pairs.length; i += 2) {
            out = out.replace("{" + pairs[i] + "}", String.valueOf(pairs[i + 1]));
        }
        return out;
    }

    /** Removes section-sign codes, for console and log files. */
    public static String plain(String colored) {
        if (colored == null) {
            return "";
        }
        return colored.replaceAll("§[0-9a-fk-orx]", "");
    }
}
