package com.projectkorra.projectkorra.platform;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * Scheduler facade. All delays and periods are in Minecraft ticks.
 */
public interface PKScheduler {
    PKTask runNow(Runnable task);

    PKTask runAsync(Runnable task);

    PKTask runLater(Runnable task, long delayTicks);

    PKTask runAsyncLater(Runnable task, long delayTicks);

    PKTask runTimer(Runnable task, long delayTicks, long periodTicks);

    PKTask runTimerAsync(Runnable task, long delayTicks, long periodTicks);

    /**
     * Compatibility bridge for legacy code that still stores integer task ids.
     */
    int scheduleRepeating(Runnable task, long delayTicks, long periodTicks);

    void cancelTask(int taskId);

    void cancelAll();

    <T> Future<T> callSync(Callable<T> task);

    boolean isPrimaryThread();
}
