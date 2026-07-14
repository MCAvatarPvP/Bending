package com.projectkorra.projectkorra.platform.model;

import java.util.UUID;

public interface PKEntity extends PKHandle {
    UUID uuid();

    PKWorld world();

    PKLocation location();

    PKVec3 velocity();

    void velocity(PKVec3 velocity);

    boolean valid();

    void remove();
}
