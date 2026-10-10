package com.udcf.modules.rmi;

import java.util.Locale;
import java.util.Objects;

/**
 * Makes text safe to show on one line: the summaries and failure messages of
 * {@link RmiCallResult}.
 *
 * <p>{@link #singleLine} escapes {@code \} as {@code \\}, line feed as {@code \n}, carriage return
 * as {@code \r}, tab as {@code \t}, and every other control character (and U+2028, U+2029) as
 * {@code \}{@code uXXXX}, so the result never contains a line break. Other characters, including
 * {@code |}, are kept. {@link #cut} shortens text to a maximum length, ending it with
 * {@value #CUT_MARKER}.</p>
 */
final class DisplayText {

    /** Ends text that {@link #cut} shortened. */
    static final String CUT_MARKER = "\u2026";

    private DisplayText() {
    }

    /** {@code value} with every line break and control character escaped; null stays null. */
    static String singleLine(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (needsEscape(c)) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** Whether {@code value} has no control character, U+2028 or U+2029 (so no line break). */
    static boolean isSingleLine(String value) {
        return value.chars().noneMatch(DisplayText::needsEscape);
    }

    private static boolean needsEscape(int c) {
        return Character.isISOControl(c) || c == 0x2028 || c == 0x2029;
    }

    /**
     * {@code value} unchanged if it has at most {@code maxLength} characters; otherwise its
     * beginning followed by {@value #CUT_MARKER}, {@code maxLength} characters in all. A
     * surrogate pair is never split. Null stays null.
     */
    static String cut(String value, int maxLength) {
        if (maxLength < 1) {
            throw new IllegalArgumentException("maxLength must be >= 1, was " + maxLength);
        }
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        int keep = maxLength - CUT_MARKER.length();
        if (keep > 0 && Character.isHighSurrogate(value.charAt(keep - 1))) {
            keep--;
        }
        return value.substring(0, keep) + CUT_MARKER;
    }

    /** {@link #singleLine} then {@link #cut}. */
    static String singleLine(String value, int maxLength) {
        return cut(singleLine(value), maxLength);
    }

    /** A quoted, single-line rendering of {@code text} with its length, for summaries. */
    static String quoted(String text) {
        Objects.requireNonNull(text, "text must not be null");
        return "\"" + singleLine(text) + "\" (" + text.length() + " characters)";
    }
}
