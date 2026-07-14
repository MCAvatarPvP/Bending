package com.projectkorra.projectkorra.storage;

public interface StorageAdapter {

    void initialize();

    void shutdown();

    StorageType getType();

    PlayerRepository players();

    PresetRepository presets();

    CooldownRepository cooldowns();

    TempElementRepository tempElements();

    BoardRepository boards();

    StatisticsRepository statistics();
}

