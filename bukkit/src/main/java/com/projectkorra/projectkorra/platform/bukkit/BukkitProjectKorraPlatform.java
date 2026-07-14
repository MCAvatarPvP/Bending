package com.projectkorra.projectkorra.platform.bukkit;

import com.projectkorra.projectkorra.platform.*;
import com.projectkorra.projectkorra.platform.bukkit.model.BukkitAdapter;
import com.projectkorra.projectkorra.platform.mc.boss.BarColor;
import com.projectkorra.projectkorra.platform.mc.boss.BarStyle;
import com.projectkorra.projectkorra.platform.mc.boss.BossBar;
import com.projectkorra.projectkorra.platform.mc.event.Cancellable;
import com.projectkorra.projectkorra.platform.mc.event.EventHandler;
import com.projectkorra.projectkorra.platform.model.PKAdapter;
import com.projectkorra.projectkorra.prediction.PaperPredictionServer;
import io.papermc.lib.PaperLib;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Bukkit/Paper implementation of the ProjectKorra platform abstraction.
 */
public final class BukkitProjectKorraPlatform implements ProjectKorraPlatform {
    private final JavaPlugin plugin;
    private final PKScheduler scheduler = new BukkitSchedulerFacade();
    private final PKEventBus events = new BukkitEventBusFacade();
    private final PKPlayers players = new BukkitPlayersFacade();
    private final PKWorlds worlds = new BukkitWorldsFacade();
    private final PKPlugins plugins = new BukkitPluginsFacade();
    private final PKTags tags = new BukkitTagsFacade();
    private final PKPermissions permissions = new BukkitPermissionsFacade();
    private final PKServer server = new BukkitServerFacade();
    private final PKScoreboards scoreboards = new BukkitScoreboardsFacade();
    private final PKBossBars bossBars = new BukkitBossBarsFacade();
    private final PKChunks chunks = new BukkitChunksFacade();
    private final PKAdapter adapter = new BukkitAdapter();

    public BukkitProjectKorraPlatform(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "bukkit";
    }

    @Override
    public Object pluginHandle() {
        return this.plugin;
    }

    @Override
    public Path dataFolder() {
        return this.plugin.getDataFolder().toPath();
    }

    @Override
    public Logger logger() {
        return this.plugin.getLogger();
    }

    @Override
    public PKScheduler scheduler() {
        return this.scheduler;
    }

    @Override
    public PKEventBus events() {
        return this.events;
    }

    @Override
    public PKPlayers players() {
        return this.players;
    }

    @Override
    public PKWorlds worlds() {
        return this.worlds;
    }

    @Override
    public PKPlugins plugins() {
        return this.plugins;
    }

    @Override
    public PKTags tags() {
        return this.tags;
    }

    @Override
    public PKPermissions permissions() {
        return this.permissions;
    }

    @Override
    public PKServer server() {
        return this.server;
    }

    @Override
    public PKScoreboards scoreboards() {
        return this.scoreboards;
    }

    @Override
    public PKBossBars bossBars() {
        return this.bossBars;
    }

    @Override
    public PKChunks chunks() {
        return this.chunks;
    }

    @Override
    public PKAdapter adapter() {
        return this.adapter;
    }

    private static final class BukkitBossBarsFacade implements PKBossBars {
        @Override
        public BossBar.Delegate create(String title,
                                       BarColor color,
                                       BarStyle style) {
            org.bukkit.boss.BossBar nativeBar = Bukkit.createBossBar(
                    title == null ? "" : title,
                    org.bukkit.boss.BarColor.valueOf((color == null ? BarColor.WHITE : color).name()),
                    org.bukkit.boss.BarStyle.valueOf((style == null ? BarStyle.SOLID : style).name()));
            return new Delegate(nativeBar);
        }

        private record Delegate(org.bukkit.boss.BossBar value) implements BossBar.Delegate {
            @Override
            public void addPlayer(com.projectkorra.projectkorra.platform.mc.entity.Player player) {
                if (player != null && player.handle() instanceof Player nativePlayer) value.addPlayer(nativePlayer);
            }

            @Override
            public void removePlayer(com.projectkorra.projectkorra.platform.mc.entity.Player player) {
                if (player != null && player.handle() instanceof Player nativePlayer) value.removePlayer(nativePlayer);
            }

            @Override
            public void removeAll() {
                value.removeAll();
            }

            @Override
            public void setProgress(double progress) {
                value.setProgress(Math.max(0.0, Math.min(1.0, progress)));
            }

            @Override
            public void setTitle(String title) {
                value.setTitle(title == null ? "" : title);
            }

            @Override
            public void setColor(BarColor color) {
                value.setColor(org.bukkit.boss.BarColor.valueOf((color == null ? BarColor.WHITE : color).name()));
            }

            @Override
            public void setVisible(boolean visible) {
                value.setVisible(visible);
            }
        }
    }

    private static final class BukkitTaskHandle implements PKTask {
        private final BukkitTask task;

        private BukkitTaskHandle(final BukkitTask task) {
            this.task = task;
        }

        @Override
        public void cancel() {
            this.task.cancel();
        }

        @Override
        public boolean cancelled() {
            return this.task.isCancelled();
        }

        @Override
        public int legacyId() {
            return this.task.getTaskId();
        }
    }

    private static final class BukkitPlayersFacade implements PKPlayers {
        @SuppressWarnings("unchecked")
        @Override
        public <P> Collection<P> onlinePlayers() {
            return (Collection<P>) Bukkit.getOnlinePlayers().stream().map(BukkitMC::player).toList();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <P> P getPlayer(final UUID uuid) {
            return (P) BukkitMC.player(Bukkit.getPlayer(uuid));
        }

        @SuppressWarnings("unchecked")
        @Override
        public <P> P getPlayer(final String name) {
            return (P) BukkitMC.player(Bukkit.getPlayer(name));
        }

        @SuppressWarnings("unchecked")
        @Override
        public <P> P getOfflinePlayer(final UUID uuid) {
            return (P) BukkitMC.offline(Bukkit.getOfflinePlayer(uuid));
        }

        @SuppressWarnings("unchecked")
        @Override
        public <P> P getOfflinePlayer(final String name) {
            return (P) BukkitMC.offline(Bukkit.getOfflinePlayer(name));
        }

        @Override
        public boolean isBedrockPlayer(final UUID uuid) {
            try {
                final Class<?> floodgate = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                final Object api = floodgate.getMethod("getInstance").invoke(null);
                return Boolean.TRUE.equals(floodgate.getMethod("isFloodgatePlayer", UUID.class).invoke(api, uuid));
            } catch (final Throwable ignored) {
                // Floodgate is optional.
            }
            try {
                final Class<?> geyserUtil = Class.forName("io.github.retrooper.packetevents.util.GeyserUtil");
                return Boolean.TRUE.equals(geyserUtil.getMethod("isGeyserPlayer", UUID.class).invoke(null, uuid));
            } catch (final Throwable ignored) {
                return false;
            }
        }

        @Override
        public boolean isExternalSpectator(final Object player) {
            if (!(player instanceof Player bukkitPlayer) || !Bukkit.getPluginManager().isPluginEnabled("StrikePractice")) {
                return false;
            }
            try {
                final Class<?> strikePractice = Class.forName("ga.strikepractice.StrikePractice");
                final Object api = strikePractice.getMethod("getAPI").invoke(null);
                return Boolean.TRUE.equals(api.getClass().getMethod("isSpectator", Player.class).invoke(api, bukkitPlayer));
            } catch (final Throwable ignored) {
                return false;
            }
        }
    }

    private static final class BukkitWorldsFacade implements PKWorlds {
        @SuppressWarnings("unchecked")
        @Override
        public <W> Collection<W> worlds() {
            return (Collection<W>) Bukkit.getWorlds().stream().map(BukkitMC::world).toList();
        }
    }

    private static final class BukkitPluginsFacade implements PKPlugins {
        @SuppressWarnings("unchecked")
        @Override
        public <P> P getPlugin(final String name) {
            return (P) Bukkit.getPluginManager().getPlugin(name);
        }

        @Override
        public boolean isPluginPresent(final String name) {
            return Bukkit.getPluginManager().getPlugin(name) != null;
        }

        @Override
        public boolean isPluginEnabled(final String name) {
            final Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
            return plugin != null && plugin.isEnabled();
        }

        @Override
        public String pluginName(final Object plugin) {
            return plugin instanceof Plugin p ? p.getName() : PKPlugins.super.pluginName(plugin);
        }
    }

    private static final class BukkitPermissionsFacade implements PKPermissions {
        private final Map<String, BukkitPermissionView> permissions = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        @Override
        public <P> P getPermission(final String name) {
            final Permission permission = Bukkit.getPluginManager().getPermission(name);
            return permission == null ? null : (P) this.permissions.computeIfAbsent(name, ignored -> new BukkitPermissionView(permission));
        }

        @Override
        public void addPermission(final Object permission) {
            if (permission instanceof BukkitPermissionView view) {
                if (Bukkit.getPluginManager().getPermission(view.getName()) == null) {
                    Bukkit.getPluginManager().addPermission(view.value);
                }
                this.permissions.putIfAbsent(view.getName(), view);
                return;
            }

            if (permission instanceof com.projectkorra.projectkorra.platform.mc.permissions.Permission platformPermission) {
                final String name = platformPermission.getName();
                Permission nativePermission = Bukkit.getPluginManager().getPermission(name);
                if (nativePermission == null) {
                    nativePermission = new Permission(name);
                    Bukkit.getPluginManager().addPermission(nativePermission);
                }
                for (var entry : platformPermission.getParents().entrySet()) {
                    final Permission nativeParent = Bukkit.getPluginManager().getPermission(entry.getKey().getName());
                    if (nativeParent != null) nativePermission.addParent(nativeParent, entry.getValue());
                }
                this.permissions.put(name, new BukkitPermissionView(nativePermission));
            }
        }
    }

    private static final class BukkitPermissionView extends com.projectkorra.projectkorra.platform.mc.permissions.Permission {
        private final Permission value;

        private BukkitPermissionView(final Permission value) {
            super(value.getName());
            this.value = value;
        }

        @Override
        public void addParent(final com.projectkorra.projectkorra.platform.mc.permissions.Permission parent, final boolean value) {
            if (parent instanceof BukkitPermissionView view) {
                this.value.addParent(view.value, value);
            } else if (parent != null) {
                final Permission nativeParent = Bukkit.getPluginManager().getPermission(parent.getName());
                if (nativeParent != null) {
                    this.value.addParent(nativeParent, value);
                }
            }
        }
    }

    private static final class BukkitServerFacade implements PKServer {
        @SuppressWarnings("unchecked")
        @Override
        public <S> S handle() {
            return (S) Bukkit.getServer();
        }

        @Override
        public String version() {
            return Bukkit.getVersion();
        }

        @Override
        public String minecraftVersion() {
            return Bukkit.getBukkitVersion().split("-", 2)[0];
        }

        @Override
        public String name() {
            return Bukkit.getName();
        }

        @Override
        public boolean onlineMode() {
            return Bukkit.getOnlineMode();
        }

        @Override
        public int viewDistance() {
            return Bukkit.getServer().getViewDistance();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <P> Collection<P> plugins() {
            return (Collection<P>) List.of(Bukkit.getPluginManager().getPlugins());
        }

        @SuppressWarnings("unchecked")
        @Override
        public <D> D createBlockData(final Object material) {
            if (material instanceof com.projectkorra.projectkorra.platform.mc.Material commonMaterial) {
                return (D) BukkitMC.blockData(Bukkit.createBlockData(BukkitMC.material(commonMaterial)));
            }
            return (D) BukkitMC.blockData(Bukkit.createBlockData((Material) material));
        }
    }

    private static final class BukkitScoreboardsFacade implements PKScoreboards {
        private final com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard main =
                new com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard();

        @SuppressWarnings("unchecked")
        @Override
        public <S> S newScoreboard() {
            return (S) new com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <S> S mainScoreboard() {
            return (S) main;
        }
    }

    private static final class BukkitChunksFacade implements PKChunks {
        @Override
        public CompletableFuture<?> getChunkAtAsync(final Object location) {
            return PaperLib.getChunkAtAsync(BukkitMC.locationHandle((com.projectkorra.projectkorra.platform.mc.Location) location));
        }
    }

    private static final class BukkitTagsFacade implements PKTags {
        @SuppressWarnings("unchecked")
        @Override
        public <T> Collection<T> values(final String registry, final String key, final Class<T> type) {
            if (com.projectkorra.projectkorra.platform.mc.Material.class.equals(type) && "blocks".equalsIgnoreCase(registry)) {
                final Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, NamespacedKey.minecraft(key), Material.class);
                if (tag == null) {
                    return Collections.emptySet();
                }
                return (Collection<T>) tag.getValues().stream()
                        .map(BukkitMC::material)
                        .filter(material -> material != null && material != com.projectkorra.projectkorra.platform.mc.Material.AIR)
                        .toList();
            }
            return Collections.emptySet();
        }
    }

    private final class BukkitSchedulerFacade implements PKScheduler {
        @Override
        public PKTask runNow(final Runnable task) {
            return new BukkitTaskHandle(Bukkit.getScheduler().runTask(plugin, predictionContext(task)));
        }

        @Override
        public PKTask runAsync(final Runnable task) {
            return new BukkitTaskHandle(Bukkit.getScheduler().runTaskAsynchronously(plugin, predictionContext(task)));
        }

        @Override
        public PKTask runLater(final Runnable task, final long delayTicks) {
            return new BukkitTaskHandle(Bukkit.getScheduler().runTaskLater(plugin, predictionContext(task), delayTicks));
        }

        @Override
        public PKTask runAsyncLater(final Runnable task, final long delayTicks) {
            return new BukkitTaskHandle(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, predictionContext(task), delayTicks));
        }

        @Override
        public PKTask runTimer(final Runnable task, final long delayTicks, final long periodTicks) {
            return new BukkitTaskHandle(Bukkit.getScheduler().runTaskTimer(plugin, predictionContext(task), delayTicks, periodTicks));
        }

        @Override
        public PKTask runTimerAsync(final Runnable task, final long delayTicks, final long periodTicks) {
            return new BukkitTaskHandle(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, predictionContext(task), delayTicks, periodTicks));
        }

        @Override
        public int scheduleRepeating(final Runnable task, final long delayTicks, final long periodTicks) {
            return Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, predictionContext(task), delayTicks, periodTicks);
        }

        @Override
        public void cancelTask(final int taskId) {
            Bukkit.getScheduler().cancelTask(taskId);
        }

        @Override
        public void cancelAll() {
            Bukkit.getScheduler().cancelTasks(plugin);
            PaperPredictionServer.schedulerReset();
        }

        @Override
        public <T> Future<T> callSync(final Callable<T> task) {
            return Bukkit.getScheduler().callSyncMethod(plugin,
                    PaperPredictionServer.contextual(task));
        }

        @Override
        public boolean isPrimaryThread() {
            return Bukkit.isPrimaryThread();
        }

        private Runnable predictionContext(final Runnable task) {
            return PaperPredictionServer.contextual(task);
        }
    }

    private final class BukkitEventBusFacade implements PKEventBus {
        private final CopyOnWriteArrayList<NeutralHandler> neutralHandlers = new CopyOnWriteArrayList<>();

        @Override
        public void call(final Object event) {
            if (event instanceof Event bukkitEvent) {
                Bukkit.getPluginManager().callEvent(bukkitEvent);
                return;
            }
            if (!(event instanceof com.projectkorra.projectkorra.platform.mc.event.Event commonEvent)) {
                throw new IllegalArgumentException("Unsupported event type: " + event);
            }
            this.neutralHandlers.stream()
                    .filter(handler -> handler.eventType().isInstance(commonEvent))
                    .sorted(Comparator.comparingInt(NeutralHandler::priority))
                    .forEach(handler -> handler.invoke(commonEvent));
        }

        @Override
        public void registerListener(final Object listener) {
            for (final Method method : listener.getClass().getMethods()) {
                if (method.getParameterCount() != 1) continue;
                final Class<?> eventType = method.getParameterTypes()[0];
                final org.bukkit.event.EventHandler bukkitAnnotation = method.getAnnotation(org.bukkit.event.EventHandler.class);
                final EventHandler commonAnnotation = method.getAnnotation(EventHandler.class);
                if (Event.class.isAssignableFrom(eventType) && bukkitAnnotation != null && listener instanceof Listener bukkitListener) {
                    Bukkit.getPluginManager().registerEvent(
                            eventType.asSubclass(Event.class), bukkitListener, bukkitAnnotation.priority(),
                            (ignored, event) -> {
                                if (method.getParameterTypes()[0].isInstance(event)) {
                                    invoke(method, listener, event);
                                }
                            }, plugin, bukkitAnnotation.ignoreCancelled());
                } else if (com.projectkorra.projectkorra.platform.mc.event.Event.class.isAssignableFrom(eventType) && (bukkitAnnotation != null || commonAnnotation != null)) {
                    final int priority = commonAnnotation != null ? commonAnnotation.priority().ordinal() : bukkitAnnotation.priority().ordinal();
                    final boolean ignoreCancelled = commonAnnotation != null ? commonAnnotation.ignoreCancelled() : bukkitAnnotation.ignoreCancelled();
                    this.neutralHandlers.add(new NeutralHandler(listener, method, eventType, priority, ignoreCancelled));
                }
            }
        }

        @Override
        public void unregisterAll(final Object listener) {
            if (listener instanceof Listener bukkitListener) {
                HandlerList.unregisterAll(bukkitListener);
            }
            this.neutralHandlers.removeIf(handler -> handler.listener() == listener);
        }

        private void invoke(final Method method, final Object listener, final Object event) {
            try {
                method.invoke(listener, event);
            } catch (ReflectiveOperationException exception) {
                throw new RuntimeException("Failed to dispatch " + event.getClass().getName() + " to " + listener.getClass().getName(), exception);
            }
        }

        private record NeutralHandler(Object listener, Method method, Class<?> eventType, int priority,
                                      boolean ignoreCancelled) {
            private void invoke(final com.projectkorra.projectkorra.platform.mc.event.Event event) {
                if (ignoreCancelled && event instanceof Cancellable cancellable && cancellable.isCancelled()) return;
                try {
                    method.invoke(listener, event);
                } catch (ReflectiveOperationException exception) {
                    throw new RuntimeException("Failed to dispatch " + event.getClass().getName() + " to " + listener.getClass().getName(), exception);
                }
            }
        }
    }
}
