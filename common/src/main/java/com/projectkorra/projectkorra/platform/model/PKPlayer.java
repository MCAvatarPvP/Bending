package com.projectkorra.projectkorra.platform.model;

import java.util.UUID;

public interface PKPlayer extends PKLivingEntity {
    String name();

    UUID uuid();

    void sendMessage(String message);

    boolean hasPermission(String permission);

    boolean sneaking();

    boolean sprinting();

    PKLocation eyeLocation();

    PKBlock targetBlock(int range);
}
