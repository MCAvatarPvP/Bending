package com.projectkorra.projectkorra.platform.mc.scheduler;

import com.projectkorra.projectkorra.platform.Platform;

public abstract class BukkitRunnable implements Runnable {
    private BukkitTask task;
    private boolean cancelled;

    private BukkitTask remember(BukkitTask task) {
        this.task = task;
        return task;
    }

    public void cancel() {
        cancelled = true;
        if (task != null) task.cancel();
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public BukkitTask runTaskLater(Object plugin, long delay) {
        return remember(new BukkitTask(Platform.scheduler().runLater(this, delay)));
    }

    public BukkitTask runTaskTimer(Object plugin, long delay, long period) {
        return remember(new BukkitTask(Platform.scheduler().runTimer(this, delay, period)));
    }

    public BukkitTask runTaskTimerAsynchronously(Object plugin, long delay, long period) {
        return remember(new BukkitTask(Platform.scheduler().runTimerAsync(this, delay, period)));
    }

    public BukkitTask runTaskAsynchronously(Object plugin) {
        return remember(new BukkitTask(Platform.scheduler().runAsync(this)));
    }
}
