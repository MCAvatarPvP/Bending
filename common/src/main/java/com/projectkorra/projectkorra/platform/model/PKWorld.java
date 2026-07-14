package com.projectkorra.projectkorra.platform.model;

public interface PKWorld extends PKHandle {
    String name();

    PKBlock blockAt(int x, int y, int z);

    void playSound(PKLocation location, String sound, float volume, float pitch);

    void spawnParticle(String particle, PKLocation location, int count, double offsetX, double offsetY, double offsetZ, double extra);
}
