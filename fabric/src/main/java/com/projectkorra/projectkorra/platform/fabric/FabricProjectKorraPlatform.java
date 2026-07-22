package com.projectkorra.projectkorra.platform.fabric;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.boss.BarColor;
import com.projectkorra.projectkorra.platform.mc.boss.BarStyle;
import com.projectkorra.projectkorra.platform.mc.boss.BossBar;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.event.Cancellable;
import com.projectkorra.projectkorra.platform.mc.event.Event;
import com.projectkorra.projectkorra.platform.mc.event.EventHandler;
import com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.platform.PKBossBars;
import com.projectkorra.projectkorra.platform.PKChunks;
import com.projectkorra.projectkorra.platform.PKEventBus;
import com.projectkorra.projectkorra.platform.PKPermissions;
import com.projectkorra.projectkorra.platform.PKPlayers;
import com.projectkorra.projectkorra.platform.PKPlugins;
import com.projectkorra.projectkorra.platform.PKScheduler;
import com.projectkorra.projectkorra.platform.PKScoreboards;
import com.projectkorra.projectkorra.platform.PKServer;
import com.projectkorra.projectkorra.platform.PKTags;
import com.projectkorra.projectkorra.platform.PKTask;
import com.projectkorra.projectkorra.platform.PKWorlds;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.model.PKAdapter;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/** Fabric implementation of ProjectKorra's platform services. */
public final class FabricProjectKorraPlatform implements ProjectKorraPlatform {
    private final MinecraftServer server;
    private final Logger logger = Logger.getLogger("ProjectKorra/Fabric");
    private final FabricScheduler scheduler = new FabricScheduler();
    private final PKEventBus events = new FabricEventBus();
    private final PKPlayers players = new FabricPlayers();
    private final PKWorlds worlds = new FabricWorlds();
    private final PKPlugins plugins = new FabricPlugins();
    private final PKTags tags = new FabricTags();
    private final PKPermissions permissions = new FabricPermissions();
    private final PKServer serverFacade = new FabricServerFacade();
    private final PKScoreboards scoreboards = new FabricScoreboards();
    private final PKBossBars bossBars = new FabricBossBars();
    private final PKChunks chunks = new FabricChunks();
    private final PKAdapter adapter = new FabricAdapter();

    public FabricProjectKorraPlatform(final MinecraftServer server) {
        this.server = server;
    }

    public void enable() {
        this.logger.info("ProjectKorra Fabric platform installed. Common runtime migration can now run without Bukkit/Paper statics.");
    }

    public void disable() {
        this.scheduler.cancelAll();
    }

    public void tick() {
        this.scheduler.tick();
    }

    @Override public String id() { return "fabric"; }
    @Override public Object pluginHandle() { return this; }
    @Override public Path dataFolder() { return FabricLoader.getInstance().getConfigDir().resolve("projectkorra"); }
    @Override public Logger logger() { return this.logger; }
    @Override public PKScheduler scheduler() { return this.scheduler; }
    @Override public PKEventBus events() { return this.events; }
    @Override public PKPlayers players() { return this.players; }
    @Override public PKWorlds worlds() { return this.worlds; }
    @Override public PKPlugins plugins() { return this.plugins; }
    @Override public PKTags tags() { return this.tags; }
    @Override public PKPermissions permissions() { return this.permissions; }
    @Override public PKServer server() { return this.serverFacade; }
    @Override public PKScoreboards scoreboards() { return this.scoreboards; }
    @Override public PKBossBars bossBars() { return this.bossBars; }
    @Override public PKChunks chunks() { return this.chunks; }
    @Override public PKAdapter adapter() { return this.adapter; }

    private static final class FabricBossBars implements PKBossBars {
        @Override
        public BossBar.Delegate create(String title,
                                                                                     BarColor color,
                                                                                     BarStyle style) {
            ServerBossBar nativeBar = new ServerBossBar(
                    FabricMC.legacyText(title == null ? "" : title),
                    net.minecraft.entity.boss.BossBar.Color.valueOf((color == null ? BarColor.WHITE : color).name()),
                    nativeStyle(style));
            return new Delegate(nativeBar);
        }

        private static net.minecraft.entity.boss.BossBar.Style nativeStyle(BarStyle style) {
            return switch (style == null ? BarStyle.SOLID : style) {
                case SEGMENTED_6 -> net.minecraft.entity.boss.BossBar.Style.NOTCHED_6;
                case SEGMENTED_10 -> net.minecraft.entity.boss.BossBar.Style.NOTCHED_10;
                case SEGMENTED_12 -> net.minecraft.entity.boss.BossBar.Style.NOTCHED_12;
                case SEGMENTED_20 -> net.minecraft.entity.boss.BossBar.Style.NOTCHED_20;
                default -> net.minecraft.entity.boss.BossBar.Style.PROGRESS;
            };
        }

        private record Delegate(ServerBossBar value) implements BossBar.Delegate {
            @Override public void addPlayer(Player player) {
                if (player != null && player.handle() instanceof ServerPlayerEntity nativePlayer) value.addPlayer(nativePlayer);
            }
            @Override public void removePlayer(Player player) {
                if (player != null && player.handle() instanceof ServerPlayerEntity nativePlayer) value.removePlayer(nativePlayer);
            }
            @Override public void removeAll() { value.clearPlayers(); }
            @Override public void setProgress(double progress) { value.setPercent((float) Math.max(0.0, Math.min(1.0, progress))); }
            @Override public void setTitle(String title) { value.setName(FabricMC.legacyText(title == null ? "" : title)); }
            @Override public void setColor(BarColor color) {
                value.setColor(net.minecraft.entity.boss.BossBar.Color.valueOf((color == null ? BarColor.WHITE : color).name()));
            }
            @Override public void setVisible(boolean visible) { value.setVisible(visible); }
        }
    }

    private final class FabricScheduler implements PKScheduler {
        private final AtomicInteger ids = new AtomicInteger(1);
        private final PriorityQueue<Scheduled> queue = new PriorityQueue<>(
                Comparator.comparingLong((Scheduled scheduled) -> scheduled.nextTick)
                        .thenComparingInt(scheduled -> scheduled.id));
        private final Map<Integer, Scheduled> tasks = new ConcurrentHashMap<>();
        private final Object queueLock = new Object();
        private long tick;

        @Override public PKTask runNow(final Runnable task) { return runLater(task, 0); }
        @Override public PKTask runAsync(final Runnable task) { CompletableFuture.runAsync(contextual(task)); return new FabricTask(-1); }
        @Override public PKTask runLater(final Runnable task, final long delayTicks) { return schedule(task, delayTicks, -1); }
        @Override public PKTask runAsyncLater(final Runnable task, final long delayTicks) { Runnable contextual = contextual(task); return schedule(() -> CompletableFuture.runAsync(contextual), delayTicks, -1); }
        @Override public PKTask runTimer(final Runnable task, final long delayTicks, final long periodTicks) { return schedule(task, delayTicks, periodTicks); }
        @Override public PKTask runTimerAsync(final Runnable task, final long delayTicks, final long periodTicks) { Runnable contextual = contextual(task); return schedule(() -> CompletableFuture.runAsync(contextual), delayTicks, periodTicks); }
        @Override public int scheduleRepeating(final Runnable task, final long delayTicks, final long periodTicks) { return runTimer(task, delayTicks, periodTicks).legacyId(); }
        @Override public void cancelTask(final int taskId) {
            Scheduled scheduled = tasks.remove(taskId);
            if (scheduled == null) return;
            scheduled.cancelled = true;
            synchronized (queueLock) { queue.remove(scheduled); }
        }
        @Override public void cancelAll() {
            tasks.values().forEach(scheduled -> scheduled.cancelled = true);
            tasks.clear();
            synchronized (queueLock) { queue.clear(); }
        }
        @Override public <T> Future<T> callSync(final Callable<T> task) { CompletableFuture<T> future = new CompletableFuture<>(); runNow(() -> { try { future.complete(task.call()); } catch (Exception e) { future.completeExceptionally(e); } }); return future; }
        @Override public boolean isPrimaryThread() { return server.isOnThread(); }

        private PKTask schedule(final Runnable task, final long delay, final long period) {
            int id = ids.getAndIncrement();
            // Match Bukkit: equal-heartbeat tasks run by scheduler id and a
            // task scheduled with delay zero cannot re-enter this heartbeat.
            Scheduled scheduled = new Scheduled(id, task, tick + Math.max(1, delay), period);
            tasks.put(id, scheduled);
            synchronized (queueLock) { queue.add(scheduled); }
            return new FabricTask(id);
        }

        private Runnable contextual(final Runnable task) {
            return task;
        }

        private void tick() {
            this.tick++;
            while (true) {
                Scheduled scheduled;
                synchronized (queueLock) {
                    scheduled = queue.peek();
                    if (scheduled == null || scheduled.nextTick > this.tick) return;
                    queue.poll();
                }
                if (scheduled.cancelled) continue;
                try {
                    scheduled.task.run();
                } catch (Throwable throwable) {
                    scheduled.cancelled = true;
                    tasks.remove(scheduled.id);
                    ProjectKorra.log.log(Level.SEVERE, "Scheduled task " + scheduled.id + " failed", throwable);
                }
                if (scheduled.period > 0 && !scheduled.cancelled) {
                    scheduled.nextTick = this.tick + scheduled.period;
                    synchronized (queueLock) { queue.add(scheduled); }
                } else {
                    tasks.remove(scheduled.id);
                }
            }
        }

        private final class FabricTask implements PKTask {
            private final int id;
            private FabricTask(final int id) { this.id = id; }
            @Override public void cancel() { cancelTask(this.id); }
            @Override public boolean cancelled() { Scheduled scheduled = tasks.get(this.id); return scheduled == null || scheduled.cancelled; }
            @Override public int legacyId() { return this.id; }
        }

        private final class Scheduled {
            private final int id;
            private final Runnable task;
            private long nextTick;
            private final long period;
            private volatile boolean cancelled;
            private Scheduled(final int id, final Runnable task, final long nextTick, final long period) { this.id = id; this.task = task; this.nextTick = nextTick; this.period = period; }
        }
    }

    private static final class FabricEventBus implements PKEventBus {
        private final List<Handler> handlers = new CopyOnWriteArrayList<>();
        @Override public void call(final Object event) {
            if (!(event instanceof Event commonEvent)) throw new IllegalArgumentException("Unsupported Fabric event: "+event);
            this.handlers.stream().filter(handler->handler.type().isInstance(commonEvent)).sorted(Comparator.comparingInt(Handler::priority)).forEach(handler->handler.invoke(commonEvent));
        }
        @Override public void registerListener(final Object listener) {
            for (Method method:listener.getClass().getMethods()) {
                if(method.getParameterCount()!=1)continue;
                var annotation=method.getAnnotation(EventHandler.class);
                if(annotation==null||!Event.class.isAssignableFrom(method.getParameterTypes()[0]))continue;
                method.trySetAccessible();
                this.handlers.add(new Handler(listener,method,method.getParameterTypes()[0],annotation.priority().ordinal(),annotation.ignoreCancelled()));
            }
        }
        @Override public void unregisterAll(final Object listener) { this.handlers.removeIf(handler->handler.listener()==listener); }
        private record Handler(Object listener,Method method,Class<?> type,int priority,boolean ignoreCancelled){
            void invoke(Event event){
                if(ignoreCancelled&&event instanceof Cancellable cancellable&&cancellable.isCancelled())return;
                try{method.invoke(listener,event);}catch(ReflectiveOperationException exception){throw new RuntimeException("Failed to dispatch "+event.getClass().getName(),exception);}
            }
        }
    }

    private final class FabricPlayers implements PKPlayers {
        @SuppressWarnings("unchecked") @Override public <P> Collection<P> onlinePlayers() { return (Collection<P>) server.getPlayerManager().getPlayerList().stream().map(FabricMC::player).toList(); }
        @SuppressWarnings("unchecked") @Override public <P> P getPlayer(final UUID uuid) { return (P) FabricMC.player(server.getPlayerManager().getPlayer(uuid)); }
        @SuppressWarnings("unchecked") @Override public <P> P getPlayer(final String name) { return (P) FabricMC.player(server.getPlayerManager().getPlayer(name)); }
        @Override public <P> P getOfflinePlayer(final UUID uuid) { return getPlayer(uuid); }
        @Override public <P> P getOfflinePlayer(final String name) { return getPlayer(name); }
    }

    private final class FabricWorlds implements PKWorlds {
        @SuppressWarnings("unchecked") @Override public <W> Collection<W> worlds() { List<com.projectkorra.projectkorra.platform.mc.World> list = new ArrayList<>(); server.getWorlds().forEach(world -> list.add(FabricMC.world(world))); return (Collection<W>) list; }
    }

    private static final class FabricPlugins implements PKPlugins {
        @SuppressWarnings("unchecked") @Override public <P> P getPlugin(final String name) { return (P) FabricLoader.getInstance().getModContainer(name).orElse(null); }
        @Override public boolean isPluginPresent(final String name) { return FabricLoader.getInstance().isModLoaded(name); }
        @Override public boolean isPluginEnabled(final String name) { return isPluginPresent(name); }
        @Override public String pluginName(final Object plugin) {
            if (plugin instanceof ModContainer mod) return mod.getMetadata().getId();
            return PKPlugins.super.pluginName(plugin);
        }
    }

    private static final class FabricPermissions implements PKPermissions {
        private final Map<String, Object> permissions = new ConcurrentHashMap<>();
        @SuppressWarnings("unchecked") @Override public <P> P getPermission(final String name) { return (P) permissions.get(name); }
        @Override public void addPermission(final Object permission) {
            if (permission != null) permissions.putIfAbsent(permission.toString(), permission);
        }
    }

    private final class FabricServerFacade implements PKServer {
        @SuppressWarnings("unchecked") @Override public <S> S handle() { return (S) server; }
        @Override public String version() { return server.getVersion(); }
        @Override public String minecraftVersion() { return server.getVersion(); }
        @Override public String name() { return server.getServerModName(); }
        @Override public boolean onlineMode() { return server.isOnlineMode(); }
        @Override public int viewDistance() { return server.getPlayerManager().getViewDistance(); }
        @SuppressWarnings("unchecked") @Override public <P> Collection<P> plugins() { return (Collection<P>) FabricLoader.getInstance().getAllMods(); }
        @SuppressWarnings("unchecked") @Override public <D> D createBlockData(final Object material) {
            if (material instanceof Material commonMaterial) return (D) commonMaterial.createBlockData();
            if (material instanceof Block block) return (D) block.getDefaultState();
            throw new IllegalArgumentException("Unsupported material: " + material);
        }
    }

    private static final class FabricScoreboards implements PKScoreboards {
        private final Scoreboard main = new Scoreboard();
        @SuppressWarnings("unchecked") @Override public <S> S newScoreboard() { return (S) new Scoreboard(); }
        @SuppressWarnings("unchecked") @Override public <S> S mainScoreboard() { return (S) main; }
    }

    private final class FabricChunks implements PKChunks {
        @Override public CompletableFuture<?> getChunkAtAsync(final Object location) {
            if (server.isOnThread()) {
                try {
                    return CompletableFuture.completedFuture(chunkAt(location));
                } catch (Throwable throwable) {
                    CompletableFuture<Object> failed = new CompletableFuture<>();
                    failed.completeExceptionally(throwable);
                    return failed;
                }
            }
            CompletableFuture<Object> future = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    future.complete(chunkAt(location));
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
            return future;
        }

        private Object chunkAt(final Object location) {
            if (location instanceof Location commonLocation
                    && commonLocation.handle() instanceof FabricMC.LocationRef ref) {
                return ref.world().getChunk((int) Math.floor(ref.pos().x) >> 4, (int) Math.floor(ref.pos().z) >> 4);
            }
            throw new IllegalArgumentException("Expected a Fabric-backed location");
        }
    }

    private static final class FabricTags implements PKTags {
        @SuppressWarnings("unchecked")
        @Override public <T> Collection<T> values(final String registry, final String key, final Class<T> type) {
            if (!"blocks".equalsIgnoreCase(registry)) return List.of();
            Identifier id = Identifier.of(key);
            TagKey<Block> tag = TagKey.of(RegistryKeys.BLOCK, id);
            if (type == Material.class) {
                List<Material> materials = new ArrayList<>();
                Registries.BLOCK.iterateEntries(tag).forEach(entry -> {
                    Material material = FabricMC.material(entry.value().getDefaultState());
                    if (material != Material.AIR && !materials.contains(material)) materials.add(material);
                });
                return (Collection<T>) materials;
            }
            if (type == BlockState.class) {
                List<BlockState> states = new ArrayList<>();
                Registries.BLOCK.iterateEntries(tag).forEach(entry -> states.add(entry.value().getDefaultState()));
                return (Collection<T>) states;
            }
            return List.of();
        }
    }
}
