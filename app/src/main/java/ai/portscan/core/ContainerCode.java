package ai.portscan.core;

import java.util.Locale;
import java.util.regex.Pattern;

/** ISO 6346 checksum. This validates transcription, not registration or ownership. */
public final class ContainerCode {
    private static final Pattern BODY = Pattern.compile("[A-Z]{3}[UJZ][0-9]{6}");
    private ContainerCode() {}

    public static int checkDigit(String body) {
        if (!BODY.matcher(body).matches()) throw new IllegalArgumentException("Expected 4 letters and 6 digits");
        int total = 0;
        for (int i = 0; i < 10; i++) {
            char c = body.charAt(i);
            int value;
            if (c >= '0' && c <= '9') value = c - '0';
            else {
                int ordinal = c - 'A' + 10;
                value = ordinal + (ordinal - 1) / 10; // Skip 11, 22 and 33.
            }
            total += value * (1 << i);
        }
        return (total % 11) % 10;
    }

    public static boolean valid(String number) {
        return number.matches("[A-Z]{3}[UJZ][0-9]{7}")
            && checkDigit(number.substring(0, 10)) == number.charAt(10) - '0';
    }

    public static String normalize(String text) {
        // Deliberately do not guess O/0, I/1, B/8, or a missing check digit.
        return text.toUpperCase(Locale.ROOT).replaceAll("[\\s\\p{Z}()\\[\\]-]+", "");
    }

    public static String format(String text) {
        return text.length() == 11 ? text.substring(0, 4) + " " + text.substring(4, 10) + " " + text.substring(10) : text;
    }
}
