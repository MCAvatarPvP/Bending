package com.projectkorra.projectkorra.platform.mc;

import java.util.Locale;
import java.util.Objects;

public class GameMode {
    public static final GameMode SURVIVAL = new GameMode("SURVIVAL");
    public static final GameMode CREATIVE = new GameMode("CREATIVE");
    public static final GameMode ADVENTURE = new GameMode("ADVENTURE");
    public static final GameMode SPECTATOR = new GameMode("SPECTATOR");
    private final String name;
    public GameMode() {
        this.name = getClass().getSimpleName();
    }
    private GameMode(String name) {
        this.name = name;
    }

    public static GameMode valueOf(String name) {
        final String normalized = Objects.requireNonNull(name, "name").toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "SURVIVAL" -> SURVIVAL;
            case "CREATIVE" -> CREATIVE;
            case "ADVENTURE" -> ADVENTURE;
            case "SPECTATOR" -> SPECTATOR;
            default -> new GameMode(normalized);
        };
    }

    public String name() {
        return this.name;
    }

    @Override
    public String toString() {
        return this.name;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof GameMode mode && this.name.equals(mode.name);
    }

    @Override
    public int hashCode() {
        return this.name.hashCode();
    }

    public Object handle() {
        return this;
    }
}
