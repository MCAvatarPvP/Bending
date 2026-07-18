package com.projectkorra.projectkorra.platform.fabric;

import com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime;
import com.projectkorra.projectkorra.platform.*;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.boss.BarColor;
import com.projectkorra.projectkorra.platform.mc.boss.BossBar;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.event.Cancellable;
import com.projectkorra.projectkorra.platform.mc.event.Event;
import com.projectkorra.projectkorra.platform.mc.event.EventHandler;
import com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard;
import com.projectkorra.projectkorra.platform.model.PKAdapter;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

/**
 * Isolated logical-client platform used only while exact prediction code runs.
 */
public final class FabricClientPredictionPlatform implements ProjectKorraPlatform {
    private final MinecraftClient client;
    private final Logger logger = Logger.getLogger("ProjectKorra/Prediction");
    private final ClientScheduler scheduler = new ClientScheduler();
    private final ClientEventBus events = new ClientEventBus();
    private final PKAdapter adapter = new FabricPredictionMC.Adapter();

    public FabricClientPredictionPlatform(MinecraftClient client) {
        this.client = client;
    }

    public void tick() {
        scheduler.tick();
    }

    public void close() {
        scheduler.cancelAll();
        FabricPredictionMC.clear();
    }

    private static String minecraftVersion() {
        return SharedConstants.getGameVersion().name();
    }

    @Override
    public String id() {
        return "fabric-client-prediction";
    }

    @Override
    public Object pluginHandle() {
        return this;
    }

    @Override
    public Path dataFolder() {
        return FabricLoader.getInstance().getConfigDir().resolve("projectkorra/prediction-cache");
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public PKScheduler scheduler() {
        return scheduler;
    }

    @Override
    public PKEventBus events() {
        return events;
    }

    @Override
    public PKPlayers players() {
        return new PKPlayers() {
            @SuppressWarnings("unchecked")
            @Override
            public <P> Collection<P> onlinePlayers() {
                return client.world == null ? List.of() : (Collection<P>) client.world.getPlayers().stream().map(FabricPredictionMC::player).toList();
            }

            @SuppressWarnings("unchecked")
            @Override
            public <P> P getPlayer(UUID uuid) {
                return (P) (client.world == null ? null : FabricPredictionMC.player(client.world.getPlayerByUuid(uuid)));
            }

            @SuppressWarnings("unchecked")
            @Override
            public <P> P getPlayer(String name) {
                return (P) (client.world == null ? null : client.world.getPlayers().stream().filter(player -> player.getName().getString().equalsIgnoreCase(name)).findFirst().map(FabricPredictionMC::player).orElse(null));
            }

            @Override
            public <P> P getOfflinePlayer(UUID uuid) {
                return getPlayer(uuid);
            }

            @Override
            public <P> P getOfflinePlayer(String name) {
                return getPlayer(name);
            }
        };
    }

    @Override
    public PKWorlds worlds() {
        return new PKWorlds() {
            @SuppressWarnings("unchecked")
            @Override
            public <W> Collection<W> worlds() {
                return client.world == null ? List.of() : (Collection<W>) List.of(FabricPredictionMC.world(client.world));
            }
        };
    }

    @Override
    public PKPlugins plugins() {
        return new PKPlugins() {
            @SuppressWarnings("unchecked")
            @Override
            public <P> P getPlugin(String name) {
                return (P) FabricLoader.getInstance().getModContainer(name).orElse(null);
            }

            @Override
            public boolean isPluginPresent(String name) {
                return FabricLoader.getInstance().isModLoaded(name);
            }

            @Override
            public boolean isPluginEnabled(String name) {
                return isPluginPresent(name);
            }
        };
    }

    @Override
    public PKTags tags() {
        return new PKTags() {
            @SuppressWarnings("unchecked")
            @Override
            public <T> Collection<T> values(String registry, String key, Class<T> type) {
                if (!"blocks".equalsIgnoreCase(registry) || type != Material.class) return List.of();
                TagKey<Block> tag = TagKey.of(
                        RegistryKeys.BLOCK, Identifier.of(key));
                List<Material> result = new ArrayList<>();
                Registries.BLOCK.iterateEntries(tag).forEach(entry -> {
                    Material material = FabricMC.material(entry.value().getDefaultState());
                    if (material != Material.AIR && !result.contains(material)) result.add(material);
                });
                return (Collection<T>) result;
            }
        };
    }

    @Override
    public PKPermissions permissions() {
        return new PKPermissions() {
            @Override
            public <P> P getPermission(String name) {
                return null;
            }

            @Override
            public void addPermission(Object permission) {
            }
        };
    }

    @Override
    public PKServer server() {
        return new PKServer() {
            @SuppressWarnings("unchecked")
            @Override
            public <S> S handle() {
                return (S) client;
            }

            @Override
            public String version() {
                return FabricClientPredictionPlatform.minecraftVersion();
            }

            @Override
            public String minecraftVersion() {
                return FabricClientPredictionPlatform.minecraftVersion();
            }

            @Override
            public String name() {
                return "Fabric prediction client";
            }

            @Override
            public boolean onlineMode() {
                return true;
            }

            @Override
            public int viewDistance() {
                return client.options.getClampedViewDistance();
            }

            @SuppressWarnings("unchecked")
            @Override
            public <P> Collection<P> plugins() {
                return (Collection<P>) FabricLoader.getInstance().getAllMods();
            }

            @SuppressWarnings("unchecked")
            @Override
            public <D> D createBlockData(Object material) {
                return (D) (material instanceof Material common ? common.createBlockData() : null);
            }
        };
    }

    @Override
    public PKScoreboards scoreboards() {
        return new PKScoreboards() {
            @SuppressWarnings("unchecked")
            @Override
            public <S> S newScoreboard() {
                return (S) new Scoreboard();
            }

            @Override
            public <S> S mainScoreboard() {
                return newScoreboard();
            }
        };
    }

    @Override
    public PKBossBars bossBars() {
        return (title, color, style) -> new BossBar.Delegate() {
            @Override
            public void addPlayer(Player player) {
            }

            @Override
            public void removePlayer(Player player) {
            }

            @Override
            public void removeAll() {
            }

            @Override
            public void setProgress(double progress) {
            }

            @Override
            public void setTitle(String title) {
            }

            @Override
            public void setColor(BarColor color) {
            }

            @Override
            public void setVisible(boolean visible) {
            }
        };
    }

    @Override
    public PKChunks chunks() {
        return location -> CompletableFuture.completedFuture(location);
    }

    @Override
    public PKAdapter adapter() {
        return adapter;
    }

    private final class ClientScheduler implements PKScheduler {
        private final AtomicInteger ids = new AtomicInteger(1);
        private final PriorityQueue<Scheduled> queue = new PriorityQueue<>(
                Comparator.comparingLong((Scheduled task) -> task.nextTick)
                        .thenComparingInt(task -> task.id));
        private final Map<Integer, Scheduled> tasks = new ConcurrentHashMap<>();
        private long tick;

        @Override
        public PKTask runNow(Runnable task) {
            return runLater(task, 0);
        }

        // Prediction tasks touch ClientWorld and must stay on its render/game
        // thread even when legacy addon code labels them "async".
        @Override
        public PKTask runAsync(Runnable task) {
            return runLater(task, 0);
        }

        @Override
        public PKTask runLater(Runnable task, long delayTicks) {
            return schedule(task, delayTicks, -1);
        }

        @Override
        public PKTask runAsyncLater(Runnable task, long delayTicks) {
            return runLater(task, delayTicks);
        }

        @Override
        public PKTask runTimer(Runnable task, long delayTicks, long periodTicks) {
            return schedule(task, delayTicks, periodTicks);
        }

        @Override
        public PKTask runTimerAsync(Runnable task, long delayTicks, long periodTicks) {
            return runTimer(task, delayTicks, periodTicks);
        }

        @Override
        public int scheduleRepeating(Runnable task, long delayTicks, long periodTicks) {
            return runTimer(task, delayTicks, periodTicks).legacyId();
        }

        @Override
        public void cancelTask(int taskId) {
            Scheduled scheduled = tasks.remove(taskId);
            if (scheduled != null) scheduled.cancelled = true;
        }

        @Override
        public void cancelAll() {
            tasks.values().forEach(task -> task.cancelled = true);
            tasks.clear();
            queue.clear();
        }

        @Override
        public <T> Future<T> callSync(Callable<T> task) {
            try {
                return CompletableFuture.completedFuture(task.call());
            } catch (Exception exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }

        @Override
        public boolean isPrimaryThread() {
            return client.isOnThread();
        }

        private PKTask schedule(Runnable task, long delay, long period) {
            int id = ids.getAndIncrement();
            long predictionAction = ExactPredictionRuntime.captureAction();
            Runnable contextualTask = predictionAction <= 0 ? task
                    : () -> ExactPredictionRuntime.runWithAction(predictionAction, task);
            // Bukkit never re-enters a task in the scheduler heartbeat that
            // scheduled it. A zero-delay runTask/runTaskLater is eligible on
            // the next heartbeat, just like delay=1; executing it immediately
            // changes child-ability and TempBlock ordering inside progressAll.
            Scheduled scheduled = new Scheduled(id, contextualTask, tick + Math.max(1, delay), period);
            tasks.put(id, scheduled);
            queue.add(scheduled);
            return new Task(id);
        }

        private void tick() {
            tick++;
            while (!queue.isEmpty() && queue.peek().nextTick <= tick) {
                Scheduled scheduled = queue.poll();
                if (scheduled.cancelled) continue;
                try {
                    scheduled.task.run();
                } catch (Throwable throwable) {
                    logger.warning("Prediction task failed: " + throwable.getMessage());
                    scheduled.cancelled = true;
                }
                if (scheduled.period > 0 && !scheduled.cancelled) {
                    scheduled.nextTick = tick + scheduled.period;
                    queue.add(scheduled);
                } else tasks.remove(scheduled.id);
            }
        }

        private final class Task implements PKTask {
            private final int id;

            private Task(int id) {
                this.id = id;
            }

            @Override
            public void cancel() {
                cancelTask(id);
            }

            @Override
            public boolean cancelled() {
                return !tasks.containsKey(id);
            }

            @Override
            public int legacyId() {
                return id;
            }
        }

        private final class Scheduled {
            final int id;
            final Runnable task;
            long nextTick;
            final long period;
            boolean cancelled;

            private Scheduled(int id, Runnable task, long nextTick, long period) {
                this.id = id;
                this.task = task;
                this.nextTick = nextTick;
                this.period = period;
            }
        }
    }

    private static final class ClientEventBus implements PKEventBus {
        private final List<Handler> handlers = new CopyOnWriteArrayList<>();

        @Override
        public void call(Object event) {
            if (!(event instanceof Event common)) return;
            handlers.stream().filter(handler -> handler.type.isInstance(common)).sorted(Comparator.comparingInt(handler -> handler.priority)).forEach(handler -> handler.invoke(common));
        }

        @Override
        public void registerListener(Object listener) {
            for (Method method : listener.getClass().getMethods()) {
                if (method.getParameterCount() != 1) continue;
                EventHandler annotation = method.getAnnotation(EventHandler.class);
                if (annotation == null || !Event.class.isAssignableFrom(method.getParameterTypes()[0]))
                    continue;
                method.trySetAccessible();
                handlers.add(new Handler(listener, method, method.getParameterTypes()[0], annotation.priority().ordinal(), annotation.ignoreCancelled()));
            }
        }

        @Override
        public void unregisterAll(Object listener) {
            handlers.removeIf(handler -> handler.listener == listener);
        }

        private record Handler(Object listener, Method method, Class<?> type, int priority,
                               boolean ignoreCancelled) {
            void invoke(Event event) {
                if (ignoreCancelled && event instanceof Cancellable cancellable && cancellable.isCancelled())
                    return;
                try {
                    method.invoke(listener, event);
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
    }
}
