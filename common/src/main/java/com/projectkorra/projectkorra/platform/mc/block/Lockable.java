package com.projectkorra.projectkorra.platform.mc.block;

public interface Lockable {
    boolean isLocked();

    String getLock();

    void setLock(String key);
}
