package com.projectkorra.projectkorra.platform.model;

public interface PKLocation extends PKHandle {
    PKWorld world();

    double x();

    double y();

    double z();

    float yaw();

    float pitch();

    PKVec3 direction();
}
