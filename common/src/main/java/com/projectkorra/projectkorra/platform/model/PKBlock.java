package com.projectkorra.projectkorra.platform.model;

public interface PKBlock extends PKHandle {
    PKWorld world();

    int x();

    int y();

    int z();

    String materialKey();

    boolean liquid();

    boolean passable();

    PKLocation location();
}
