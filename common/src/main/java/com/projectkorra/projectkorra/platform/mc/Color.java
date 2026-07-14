package com.projectkorra.projectkorra.platform.mc;

import java.util.Locale;

public class Color {
    public static final Color DEEP_PURPLE = new Color("DEEP_PURPLE", 103, 58, 183);
    public static final Color GREY = new Color("GREY", 128, 128, 128);
    public static final Color BLACK = new Color("BLACK", 0, 0, 0);
    public static final Color NONE = new Color("NONE", 0, 0, 0);
    public static final Color ORANGE = new Color("ORANGE", 255, 165, 0);
    public static final Color RED = new Color("RED", 255, 0, 0);
    public static final Color WHITE = new Color("WHITE", 255, 255, 255);
    private final String name;
    private final int red;
    private final int green;
    private final int blue;
    public Color() {
        this(getClassNameSafe(Color.class), 255, 255, 255);
    }
    private Color(String name, int red, int green, int blue) {
        this.name = name;
        this.red = clamp(red);
        this.green = clamp(green);
        this.blue = clamp(blue);
    }

    private static String getClassNameSafe(Class<?> clazz) {
        return clazz.getSimpleName();
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    public static Color valueOf(String name) {
        if (name == null) return WHITE;
        String normalized = name.trim().replace(' ', '_').replace('-', '_').toUpperCase(Locale.ROOT);
        if (normalized.startsWith("#") && normalized.length() == 7) {
            return fromRGB(Integer.parseInt(normalized.substring(1), 16));
        }
        return switch (normalized) {
            case "DEEP_PURPLE" -> DEEP_PURPLE;
            case "GREY", "GRAY" -> GREY;
            case "NONE" -> NONE;
            case "ORANGE" -> ORANGE;
            case "RED" -> RED;
            case "WHITE" -> WHITE;
            default -> new Color(normalized, 255, 255, 255);
        };
    }

    public static Color fromRGB(int red, int green, int blue) {
        return new Color(String.format("#%02X%02X%02X", clamp(red), clamp(green), clamp(blue)), red, green, blue);
    }

    public static Color fromRGB(int rgb) {
        return fromRGB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    public String name() {
        return this.name;
    }

    public int getRed() {
        return red;
    }

    public int getGreen() {
        return green;
    }

    public int getBlue() {
        return blue;
    }

    public int asRGB() {
        return (red << 16) | (green << 8) | blue;
    }

    @Override
    public String toString() {
        return this.name;
    }

    public Object handle() {
        return this;
    }
}
