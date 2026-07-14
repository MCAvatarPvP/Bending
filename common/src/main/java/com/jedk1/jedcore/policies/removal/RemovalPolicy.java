package com.jedk1.jedcore.policies.removal;

import com.projectkorra.projectkorra.configuration.PKConfigurationSection;

public interface RemovalPolicy {
    boolean shouldRemove();

    default void load(PKConfigurationSection config) {
    }

    String getName();
}

