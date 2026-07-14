package com.projectkorra.projectkorra.platform.mc;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Platform-neutral legacy color token.
 */
public final class ChatColor {
    public static final char COLOR_CHAR = '\u00A7';
    private static final String COLOR_CHAR_PATTERN = Pattern.quote(String.valueOf(COLOR_CHAR));
    private static final Pattern STRIP = Pattern.compile("(?i)" + COLOR_CHAR_PATTERN + "x(?:"
            + COLOR_CHAR_PATTERN + "[0-9a-f]){6}|" + COLOR_CHAR_PATTERN + "[0-9a-fk-or]");
    private static final Map<String, ChatColor> VALUES = new ConcurrentHashMap<>();

    public static final ChatColor BLACK = register("BLACK", '0');
    public static final ChatColor DARK_BLUE = register("DARK_BLUE", '1');
    public static final ChatColor DARK_GREEN = register("DARK_GREEN", '2');
    public static final ChatColor DARK_AQUA = register("DARK_AQUA", '3');
    public static final ChatColor DARK_RED = register("DARK_RED", '4');
    public static final ChatColor DARK_PURPLE = register("DARK_PURPLE", '5');
    public static final ChatColor GOLD = register("GOLD", '6');
    public static final ChatColor GRAY = register("GRAY", '7');
    public static final ChatColor DARK_GRAY = register("DARK_GRAY", '8');
    public static final ChatColor BLUE = register("BLUE", '9');
    public static final ChatColor GREEN = register("GREEN", 'a');
    public static final ChatColor AQUA = register("AQUA", 'b');
    public static final ChatColor RED = register("RED", 'c');
    public static final ChatColor LIGHT_PURPLE = register("LIGHT_PURPLE", 'd');
    public static final ChatColor YELLOW = register("YELLOW", 'e');
    public static final ChatColor WHITE = register("WHITE", 'f');
    public static final ChatColor BOLD = register("BOLD", 'l');
    public static final ChatColor STRIKETHROUGH = register("STRIKETHROUGH", 'm');
    public static final ChatColor UNDERLINE = register("UNDERLINE", 'n');
    public static final ChatColor ITALIC = register("ITALIC", 'o');
    public static final ChatColor RESET = register("RESET", 'r');

    private final String name;
    private final String legacy;

    private ChatColor(final String name, final String legacy) {
        this.name = name;
        this.legacy = legacy;
    }

    private static ChatColor register(final String name, final char code) {
        ChatColor color = new ChatColor(name, String.valueOf(COLOR_CHAR) + code);
        VALUES.put(name, color);
        return color;
    }

    public static ChatColor valueOf(final String name) {
        return of(name);
    }

    public static ChatColor[] values() {
        return VALUES.values().toArray(new ChatColor[0]);
    }

    public static ChatColor getByChar(final char code) {
        String legacy = String.valueOf(COLOR_CHAR) + Character.toLowerCase(code);
        return VALUES.values().stream().filter(color -> color.legacy.equals(legacy)).findFirst().orElse(WHITE);
    }

    public static String getLastColors(final String input) {
        if (input == null || input.isEmpty()) return "";
        final StringBuilder result = new StringBuilder();
        for (int i = input.length() - 2; i >= 0; i--) {
            if (input.charAt(i) != COLOR_CHAR) {
                continue;
            }

            final char code = Character.toLowerCase(input.charAt(i + 1));
            if (isHexLegacyEnd(input, i)) {
                return input.substring(i - 12, i + 2) + result;
            }
            if ("klmno".indexOf(code) >= 0) {
                result.insert(0, input.substring(i, i + 2));
                continue;
            }
            if ("0123456789abcdefr".indexOf(code) >= 0) {
                return input.substring(i, i + 2) + result;
            }
        }
        return result.toString();
    }

    public static ChatColor of(final String value) {
        if (value == null) return WHITE;
        final String normalized = value.trim().replace(' ', '_').replace('-', '_').toUpperCase(Locale.ROOT);
        if (normalized.startsWith("#") && normalized.length() == 7)
            return new ChatColor(normalized, toHexLegacy(normalized));
        final ChatColor known = VALUES.get(normalized);
        if (known != null) return known;
        throw new IllegalArgumentException("Unknown chat color: " + value);
    }

    private static String toHexLegacy(final String hex) {
        StringBuilder out = new StringBuilder().append(COLOR_CHAR).append('x');
        for (int i = 1; i < hex.length(); i++) out.append(COLOR_CHAR).append(hex.charAt(i));
        return out.toString();
    }

    private static boolean isHexLegacyEnd(final String input, final int sectionIndex) {
        if (sectionIndex < 12 || sectionIndex + 1 >= input.length()) {
            return false;
        }
        final int start = sectionIndex - 12;
        if (input.charAt(start) != COLOR_CHAR || Character.toLowerCase(input.charAt(start + 1)) != 'x') {
            return false;
        }
        for (int i = start + 2; i <= sectionIndex; i += 2) {
            if (input.charAt(i) != COLOR_CHAR || !isHexChar(input.charAt(i + 1))) {
                return false;
            }
        }
        return true;
    }

    public static String stripColor(final String input) {
        return input == null ? null : STRIP.matcher(input).replaceAll("");
    }

    public static String translateAlternateColorCodes(final char altColorChar, final String textToTranslate) {
        if (textToTranslate == null) return null;
        StringBuilder out = new StringBuilder(textToTranslate.length());
        for (int i = 0; i < textToTranslate.length(); i++) {
            char current = textToTranslate.charAt(i);
            if (current == altColorChar && i + 1 < textToTranslate.length()) {
                char next = textToTranslate.charAt(i + 1);
                if (next == '#' && i + 7 < textToTranslate.length()) {
                    String hex = textToTranslate.substring(i + 2, i + 8);
                    if (isHex(hex)) {
                        out.append(COLOR_CHAR).append('x');
                        for (int j = 0; j < hex.length(); j++)
                            out.append(COLOR_CHAR).append(Character.toLowerCase(hex.charAt(j)));
                        i += 7;
                        continue;
                    }
                }
                if ("0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx".indexOf(next) > -1) {
                    out.append(COLOR_CHAR).append(Character.toLowerCase(next));
                    i++;
                    continue;
                }
            }
            out.append(current);
        }
        return out.toString();
    }

    private static boolean isHex(final String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!isHexChar(value.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isHexChar(final char c) {
        boolean digit = c >= '0' && c <= '9';
        boolean lower = c >= 'a' && c <= 'f';
        boolean upper = c >= 'A' && c <= 'F';
        return digit || lower || upper;
    }

    public String name() {
        return this.name;
    }

    public String getName() {
        return this.name;
    }

    public boolean isColor() {
        return this.legacy.length() == 2 && "0123456789abcdef".indexOf(Character.toLowerCase(this.legacy.charAt(1))) >= 0;
    }

    @Override
    public String toString() {
        return this.legacy;
    }

    public Object handle() {
        return this;
    }
}
