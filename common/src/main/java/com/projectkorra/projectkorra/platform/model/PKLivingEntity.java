package com.projectkorra.projectkorra.platform.model;

public interface PKLivingEntity extends PKEntity {
    double health();

    void damage(double amount, PKEntity source);
}
