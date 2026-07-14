package com.projectkorra.projectkorra.platform.mc;

import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.plugin.Plugin;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitTask;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

public class Server {
    private final Scheduler scheduler = new Scheduler();
    private final PluginManager pluginManager = new PluginManager();

    public Scheduler getScheduler() {
        return scheduler;
    }

    public PluginManager getPluginManager() {
        return pluginManager;
    }

    public Player getPlayer(String name) {
        return Platform.players().getPlayer(name);
    }

    public Player getPlayer(UUID uuid) {
        return Platform.players().getPlayer(uuid);
    }

    public OfflinePlayer getOfflinePlayer(UUID uuid) {
        return Platform.players().getOfflinePlayer(uuid);
    }

    public Collection<Player> getOnlinePlayers() {
        return Platform.players().onlinePlayers();
    }

    public Object handle() {
        return Platform.server().handle();
    }

    public static final class PluginManager {
        public void registerEvents(Object listener, Object plugin) {
            Platform.events().registerListener(listener, plugin);
        }

        public void callEvent(Object event) {
            Platform.events().call(event);
        }

        public void disablePlugin(Plugin plugin) {
        }
    }

    public static final class Scheduler {
        public BukkitTask runTaskLater(Object plugin, Runnable task, long delay) {
            return new BukkitTask(Platform.scheduler().runLater(task, delay));
        }

        public BukkitTask runTaskTimer(Object plugin, Runnable task, long delay, long period) {
            return new BukkitTask(Platform.scheduler().runTimer(task, delay, period));
        }

        public BukkitTask runTaskTimerAsynchronously(Object plugin, Runnable task, long delay, long period) {
            return new BukkitTask(Platform.scheduler().runTimerAsync(task, delay, period));
        }

        public BukkitTask runTaskAsynchronously(Object plugin, Runnable task) {
            return new BukkitTask(Platform.scheduler().runAsync(task));
        }

        public int scheduleSyncRepeatingTask(Object plugin, Runnable task, long delay, long period) {
            return Platform.scheduler().scheduleRepeating(task, delay, period);
        }

        public <T> Future<T> callSyncMethod(Object plugin, Callable<T> task) {
            return Platform.scheduler().callSync(task);
        }
    }
}
