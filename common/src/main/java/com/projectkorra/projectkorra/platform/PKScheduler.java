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

    // Prefer the describable target for lambdas/method references. Existing Runnable
    // objects and platform implementations retain the original scheduling API.
    default PKTask runNow(PKRunnable task) { return runNow((Runnable) task); }
    default PKTask runAsync(PKRunnable task) { return runAsync((Runnable) task); }
    default PKTask runLater(PKRunnable task, long delayTicks) { return runLater((Runnable) task, delayTicks); }
    default PKTask runAsyncLater(PKRunnable task, long delayTicks) { return runAsyncLater((Runnable) task, delayTicks); }
    default PKTask runTimer(PKRunnable task, long delayTicks, long periodTicks) { return runTimer((Runnable) task, delayTicks, periodTicks); }
    default PKTask runTimerAsync(PKRunnable task, long delayTicks, long periodTicks) { return runTimerAsync((Runnable) task, delayTicks, periodTicks); }
    default int scheduleRepeating(PKRunnable task, long delayTicks, long periodTicks) { return scheduleRepeating((Runnable) task, delayTicks, periodTicks); }

    void cancelTask(int taskId);

    void cancelAll();

    <T> Future<T> callSync(Callable<T> task);

    boolean isPrimaryThread();
}
