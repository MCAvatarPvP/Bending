package com.projectkorra.projectkorra.platform.mc.event;

public interface Cancellable {
    boolean isCancelled();

    void setCancelled(boolean cancelled);
}
