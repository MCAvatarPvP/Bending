package com.projectkorra.projectkorra.platform.mc.scheduler;

import com.projectkorra.projectkorra.platform.PKTask;

public class BukkitTask implements PKTask {
    private final PKTask task;

    public BukkitTask() {
        this.task = null;
    }

    public BukkitTask(PKTask task) {
        this.task = task;
    }

    public void cancel() {
        if (task != null) task.cancel();
    }

    public boolean cancelled() {
        return task != null && task.cancelled();
    }

    public int legacyId() {
        return task == null ? -1 : task.legacyId();
    }
}
