package org.offeringprotocol.odp.core;

import java.util.Locale;

/** HTTP media-type syntax validation without interpreting parameters. */
public final class OdpMediaType {
    private static final char SEMICOLON = ';';
    private static final char QUOTE = '"';
    private static final char BACKSLASH = '\\';

    private OdpMediaType() {}

    /** Returns the lowercase type/subtype, or an empty string for invalid syntax. */
    public static String essence(String value) {
        if (value == null) return "";
        int start = whitespace(value, 0);
        int slash = tokenEnd(value, start);
        if (slash == start || slash == value.length() || value.charAt(slash) != '/') return "";
        int end = tokenEnd(value, slash + 1);
        if (end == slash + 1) return "";
        int position = whitespace(value, end);
        while (position < value.length()) {
            if (value.charAt(position) != SEMICOLON) return "";
            position++;
            position = whitespace(value, position);
            if (position == value.length() || value.charAt(position) == ';') continue;
            int nameEnd = tokenEnd(value, position);
            if (nameEnd == position || nameEnd == value.length() || value.charAt(nameEnd) != '=') return "";
            position = nameEnd + 1;
            if (position < value.length() && value.charAt(position) == '"') {
                position = quotedEnd(value, position + 1);
                if (position < 0) return "";
            } else {
                int valueEnd = tokenEnd(value, position);
                if (valueEnd == position) return "";
                position = valueEnd;
            }
            position = whitespace(value, position);
        }
        return value.substring(start, end).toLowerCase(Locale.ROOT);
    }

    private static int quotedEnd(String value, int start) {
        int position = start;
        while (position < value.length()) {
            char current = value.charAt(position);
            position++;
            if (current == QUOTE) return position;
            if (current == BACKSLASH) {
                if (position == value.length()) return -1;
                current = value.charAt(position);
                position++;
            }
            if (current != '\t' && (current < 32 || current == 127 || current > 255)) return -1;
        }
        return -1;
    }

    private static int whitespace(String value, int start) {
        int position = start;
        while (position < value.length() && (value.charAt(position) == ' ' || value.charAt(position) == '\t')) {
            position++;
        }
        return position;
    }

    private static int tokenEnd(String value, int start) {
        int position = start;
        while (position < value.length()) {
            char current = value.charAt(position);
            if (!(current >= 'a' && current <= 'z')
                    && !(current >= 'A' && current <= 'Z')
                    && !(current >= '0' && current <= '9')
                    && "!#$%&'*+-.^_`|~".indexOf(current) < 0) {
                break;
            }
            position++;
        }
        return position;
    }
}
