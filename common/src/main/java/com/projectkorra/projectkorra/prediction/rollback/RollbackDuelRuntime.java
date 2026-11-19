package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.listener.CommonAbilityLifecycleListener;
import com.projectkorra.projectkorra.platform.PKPermissions;
import com.projectkorra.projectkorra.platform.PKScoreboards;
import com.projectkorra.projectkorra.platform.mc.permissions.Permission;
import com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard;
import com.projectkorra.projectkorra.prediction.action.AbilityRemovalSync;
import com.projectkorra.projectkorra.prediction.authority.PredictionServices;
import com.projectkorra.projectkorra.prediction.block.*;
import com.projectkorra.projectkorra.prediction.hit.PredictedContactSync;
import com.projectkorra.projectkorra.prediction.movement.VelocitySync;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeItems;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainCodec;
import com.projectkorra.projectkorra.prediction.state.*;
import com.projectkorra.projectkorra.hooks.RegionProtectionHook;
import java.util.*;

/** Shared setup for the authoritative Paper match and its Fabric replicas. */
public final class RollbackDuelRuntime {
    public static final RollbackGraphCodec.Limits GRAPH = new RollbackGraphCodec.Limits(120_000, 1_000_000, 32 << 20, 1 << 20);
    public static final RollbackTerrainCodec.Limits TERRAIN = new RollbackTerrainCodec.Limits(16_777_216, 65536, 128 << 20, 256L << 20, 1 << 20);
    public static final RollbackEngine.Limits ENGINE = new RollbackEngine.Limits(8, 4, 16384, 50_000_000L);
    public static final RollbackStartNegotiation.Limits CLOCK = new RollbackStartNegotiation.Limits(200, 30, 4);
    public static final RollbackBootstrapServerEndpoint.Limits TRANSFER = new RollbackBootstrapServerEndpoint.Limits(1200, 16);

    private final List<Class<?>> installed;
    private final String definitions;

    public RollbackDuelRuntime(ClassLoader loader) {
        installed = RollbackGameplayCatalog.installed(loader);
        definitions = RollbackDefinitionSchema.fingerprint(RollbackGameplayCatalog.implementationFingerprint(loader, installed),
                RollbackGameplayCatalog.create(installed, List.of()).fingerprint(),
                RollbackBootstrapData.VERSION, RollbackInputPacket.VERSION, RollbackTerrainCodec.VERSION, RollbackGraphCodec.VERSION,
                ENGINE.rollbackTicks(), ENGINE.futureTicks(), ENGINE.effectsPerTick(), ENGINE.stepNanos());
    }

    public String definitions() { return definitions; }
    public List<Class<?>> installed() { return installed; }

    public record Match(RollbackGameplayGraph.Imported graph, RollbackCombatRuntime<RollbackPlayerInput, Object> combat) { }

    /** Import the captured gameplay graph onto the already instantiated private native scene. */
    public Match open(RollbackBootstrapData data, RollbackRound round, RollbackRosterViews views, RollbackPlatform.Identity identity,
            RollbackServer.Blocks<?> blocks, RollbackNativeItems<?> items, RollbackPlayerExecution<Object> execution,
            List<?> sceneRoots, boolean replica) {
        if (!definitions.equals(data.definitions())) throw new IllegalArgumentException("Installed gameplay schema differs from the duel");
        var handles = new TreeMap<String, Object>();
        var bindings = new ArrayList<RollbackGraphCodec.Binding>();
        for (var entry : data.plugins().entries()) {
            handles.put(entry.name(), entry);
            bindings.add(new RollbackGraphCodec.Binding("plugin/" + entry.name(), Object.class, entry));
        }
        var plugins = new RollbackPlugins(data.plugins(), handles);
        var services = new RollbackPlatform.Services(Objects.requireNonNull(plugins.getPlugin("ProjectKorra")),
                plugins, data.tags(), data.materials(), new Permissions(data.access()),
                new RollbackServer(data.server(), plugins.entries(), blocks), new Scoreboards(),
                new RollbackBossBars(views.players().values(), 2048), new RollbackModelAdapter(views.world()));
        var platform = new RollbackPlatform(identity, services, new RollbackScheduler(65536, 16384), new RollbackEventBus(16384, 64), views);
        var lifecycle = new CommonAbilityLifecycleListener(execution.output()::accept);
        var imported = RollbackGameplayGraph.decode(installed, GRAPH, data.sides().keySet(), data.bending(), data.configuration(),
                PredictionConfigSync.sources(), views.graphBindings(), lifecycle, bindings);
        var policies = imported.bending().services().stream().filter(RollbackRegionPolicy.class::isInstance).map(RollbackRegionPolicy.class::cast).toList();
        if (policies.size() != 1) throw new IllegalArgumentException("Duel requires one captured region policy");
        RegionProtectionHook regions = policies.getFirst().bind(views);
        var external = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        external.addAll(imported.configuration().references());
        external.addAll(handles.values());
        external.add(items);
        external.addAll(RollbackGameplayGraph.definitionBindings().stream()
                .map(RollbackGraphCodec.Binding::value).toList());
        var state = new RollbackStateGraph(external::contains, field -> true, GRAPH.maximumObjects());
        var environment = new RollbackCombatRuntime.Environment(state, List.of(), sceneRoots, platform, items,
                prediction(regions), imported.configuration());
        var combat = RollbackCombatRuntime.createImportedMatch(environment, imported, execution, () -> { }, inputs(views),
                ENGINE, data.epochMillis(), data.epochNanos(), round, replica);
        return new Match(imported, combat);
    }

    /** Legacy packet publishers are replaced by the match authority stream. Region decisions remain match-specific. */
    private static PredictionServices.Bindings prediction(RegionProtectionHook regions) {
        return PredictionServices.builder().bind(RegionProtectionHook.class, Objects.requireNonNull(regions))
                .disable(AbilityRemovalSync.Listener.class).disable(DirectBlockSync.Listener.class)
                .disable(TempBlockSync.Listener.class).disable(TempFallingBlockSync.Listener.class)
                .disable(PredictedContactSync.Listener.class).disable(VelocitySync.Listener.class)
                .disable(AbilityCheckpointSync.Listener.class).disable(AbilityStateSync.Listener.class)
                .disable(CooldownSync.Listener.class).disable(GlidingStateSync.Listener.class)
                .disable(PlayerStatusSync.Listener.class).build();
    }

    private static final class Scoreboards implements PKScoreboards {
        private final Scoreboard main = new Scoreboard();
        @Override @SuppressWarnings("unchecked") public <S> S newScoreboard() { return (S) new Scoreboard(); }
        @Override @SuppressWarnings("unchecked") public <S> S mainScoreboard() { return (S) main; }
    }
    private static final class Permissions implements PKPermissions {
        private final Map<String, Permission> nodes = new TreeMap<>();
        private Permissions(RollbackPlayerAccess access) {
            access.players().values().forEach(player -> player.permissions().keySet().forEach(key -> nodes.computeIfAbsent(key, Permission::new)));
        }
        @Override @SuppressWarnings("unchecked") public <P> P getPermission(String name) {
            return (P) nodes.get(RollbackPlayerAccess.permissionName(name));
        }
        @Override public void addPermission(Object value) {
            if (!(value instanceof Permission permission)) throw new IllegalArgumentException("Foreign permission");
            String key = RollbackPlayerAccess.permissionName(permission.getName());
            if (nodes.putIfAbsent(key, permission) != null) throw new IllegalArgumentException("Permission already registered: " + key);
        }
    }

    public static long seed(UUID session) {
        return session.getMostSignificantBits() ^ Long.rotateLeft(session.getLeastSignificantBits(), 23);
    }

    /** Seed the first predicted frame with the pose captured into each private native player. */
    public static Map<UUID, RollbackPlayerInput> inputs(RollbackRosterViews roster) {
        var inputs = new TreeMap<UUID, RollbackPlayerInput>();
        roster.players().forEach((id, player) -> {
            var position = player.getLocation();
            var movement = new RollbackMovementInput(0, 0, false, position.getYaw(), position.getPitch());
            inputs.put(id, new RollbackPlayerInput(movement, player.isSprinting(), List.of()));
        });
        return Collections.unmodifiableMap(inputs);
    }
}
