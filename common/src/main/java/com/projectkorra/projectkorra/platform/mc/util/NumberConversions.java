package com.projectkorra.projectkorra.platform.mc.util;

public class NumberConversions {
    public static boolean isFinite(double value) {
        return Double.isFinite(value);
    }

    public static int round(double value) {
        return (int) Math.round(value);
    }

    public static int round(float value) {
        return Math.round(value);
    }

    public static int ceil(double value) {
        return (int) Math.ceil(value);
    }

    public static int floor(double value) {
        return (int) Math.floor(value);
    }
}
