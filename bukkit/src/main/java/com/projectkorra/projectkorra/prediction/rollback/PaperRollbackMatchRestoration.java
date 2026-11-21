package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.util.TempBlock.RollbackLayers;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Stages a single confirmed frame for return to normal gameplay. Retain the plan until release succeeds. */
public final class PaperRollbackMatchRestoration {
    private record Frame(RollbackRosterData players, List<RollbackBlockStore.Change> changes, byte[] bending, PaperRollbackActorRestoration.Captured actors, RollbackScheduledTicks scheduledTicks, long confirmedMillis, long confirmedNanos) { }

    /** Original live identities and the ID reservation held during the match. */
    public record LiveRoster(Map<UUID, ServerPlayer> nativePlayers, Map<UUID, BendingPlayer> bendingPlayers,
            CoreAbility.RollbackIdReservation reservation, CoreAbility.RollbackRegistry originalAbilities,
            RollbackGraphCodec liveCodec, RollbackLayers.Lease layers) {
        public LiveRoster(Map<UUID, ServerPlayer> nativePlayers, Map<UUID, BendingPlayer> bendingPlayers,
                CoreAbility.RollbackIdReservation reservation, CoreAbility.RollbackRegistry originalAbilities, RollbackGraphCodec liveCodec) {
            this(nativePlayers, bendingPlayers, reservation, originalAbilities, liveCodec, null);
        }
        public LiveRoster {
            nativePlayers = Map.copyOf(nativePlayers);
            bendingPlayers = Map.copyOf(bendingPlayers);
            Objects.requireNonNull(reservation);
            Objects.requireNonNull(originalAbilities);
            Objects.requireNonNull(liveCodec);
            if (nativePlayers.isEmpty() || !nativePlayers.keySet().equals(bendingPlayers.keySet()))
                throw new IllegalArgumentException("Live bending and native rosters differ");
        }
        private Map<UUID, Player> views(ServerLevel world) {
            var views = new TreeMap<UUID, Player>();
            for (var entry : nativePlayers.entrySet()) {
                var player = entry.getValue();
                var view = bendingPlayers.get(entry.getKey()).getPlayer();
                if (player.level() != world || !entry.getKey().equals(player.getUUID()) || view == null
                        || view.handle() != player.getBukkitEntity())
                    throw new IllegalStateException("Live duel player changed before restoration");
                views.put(entry.getKey(), view);
            }
            return views;
        }
    }

    private final PaperRollbackActorRestoration actors;
    private final PaperRollbackTerrainWriteback terrain;
    private final PaperRollbackRosterComponents.Prepared players;
    private final RollbackBendingState.Restoration.LiveCommit gameplay;
    private final RollbackTaskBindings tasks;
    private final RollbackScheduledTicks scheduledTicks;
    private final long frontierTime, confirmedMillis, confirmedNanos;
    private RollbackLayers.Lease layerLease;
    private RollbackLayers settledLayers;
    private boolean released;

    private PaperRollbackMatchRestoration(PaperRollbackTerrainWriteback terrain, PaperRollbackRosterComponents.Prepared players,
            RollbackBendingState.Restoration.LiveCommit gameplay, RollbackTaskBindings tasks, PaperRollbackActorRestoration actors, RollbackScheduledTicks scheduledTicks, long frontierTime, long confirmedMillis, long confirmedNanos) {
        this.actors = actors;
        this.scheduledTicks = scheduledTicks; this.frontierTime = frontierTime; this.confirmedMillis = confirmedMillis; this.confirmedNanos = confirmedNanos;
        this.terrain = terrain;
        this.players = players;
        this.gameplay = gameplay;
        this.tasks = tasks;
    }

    public static PaperRollbackMatchRestoration prepare(RollbackCombatRuntime<?, ?> authority,
            RollbackBootstrapData initial, PaperRollbackNativeScene scene, RollbackGameplayGraph.Imported graph,
            ServerLevel world, LiveRoster live, long liveNanos) {
        if (!new java.util.HashSet<>(authority.participants()).equals(initial.sides().keySet())
                || !live.nativePlayers().keySet().equals(initial.sides().keySet())
                || !world.getWorld().getUID().equals(initial.world().world()))
            throw new IllegalArgumentException("Handoff does not belong to this match");
        var views = live.views(world);
        var frame = authority.readForHandoff(() -> {
            long tick = authority.diagnostics().confirmedTick();
            long elapsed = Math.multiplyExact(tick, authority.limits().stepNanos());
            var roster = scene.captureRoster(initial.roster(), initial.epochNanos() + elapsed);
            if (roster.worldTime() != Math.addExact(initial.roster().worldTime(), tick))
                throw new IllegalStateException("Private roster and confirmed clock differ");
            var actors = PaperRollbackActorRestoration.Captured.capture(initial.session(), scene, roster.combatActors());
            var registry = scene.world().actors();
            var codec = registry == null ? graph.codec() : graph.codec().withBindings(registry.bindings());
            return new Frame(roster, scene.logical().terrain().changesSince(initial.world().terrain()),
                    graph.bending().exportState(codec, registry), actors, scene.ticks().captureRollbackState(), RollbackClock.millis(), RollbackClock.nanos());
        });
        var terrain = PaperRollbackTerrainWriteback.prepare(world, initial.world().terrain(), frame.changes());
        var actors = new PaperRollbackActorRestoration(world, live.nativePlayers(), frame.actors());
        var players = PaperRollbackRosterComponents.prepare(live.nativePlayers(), frame.players(), liveNanos,
                actors.combatHistory(frame.players().combatActors(), frame.actors().data()));
        var restored = RollbackBendingState.decodeRestoration(views, frame.bending(), live.liveCodec().withBindings(actors.bindings()));
        var tasks = restored.services().stream().filter(RollbackTaskBindings.class::isInstance)
                .map(RollbackTaskBindings.class::cast).toList();
        if (tasks.size() != 1) throw new IllegalStateException("Confirmed gameplay requires one scheduler");
        var gameplay = restored.prepareCommit(live.bendingPlayers(), live.reservation(), live.originalAbilities());
        var plan = new PaperRollbackMatchRestoration(terrain, players, gameplay, tasks.getFirst(), actors, frame.scheduledTicks(), frame.players().worldTime(), frame.confirmedMillis(), frame.confirmedNanos());
        var globals = restored.services().stream().filter(RollbackAbilityGlobals.class::isInstance)
                .map(RollbackAbilityGlobals.class::cast).findFirst();
        globals.ifPresent(state -> state.validateTemporaryActors(frame.actors().data()));
        if (live.layers() != null) {
            plan.layerLease = live.layers();
            plan.settledLayers = globals.orElseThrow().capturedLayers();
            plan.layerLease.validate(plan.settledLayers);
        }
        return plan;
    }

    public void release(PaperRollbackLiveOwnership ownership) {
        if (released) return;
        gameplay.validate();
        if (layerLease != null) layerLease.validate(settledLayers);
        actors.validate();
        terrain.validate();
        players.validate();
        ownership.stageScheduledTerrain(scheduledTicks, frontierTime);
        gameplay.rebaseAt(confirmedMillis, RollbackClock.millis(), confirmedNanos, RollbackClock.nanos());
        ownership.restoreAndRelease(tasks, terrain, players, () -> {
            actors.commit();
            if (layerLease != null) layerLease.merge(settledLayers);
            gameplay.commit();
        });
        released = true;
    }
}
