package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.BendingPlayer;
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
    private record Frame(RollbackRosterData players, List<RollbackBlockStore.Change> changes, byte[] bending) { }

    /** Original live identities and the ID reservation held during the match. */
    public record LiveRoster(Map<UUID, ServerPlayer> nativePlayers, Map<UUID, BendingPlayer> bendingPlayers,
            CoreAbility.RollbackIdReservation reservation, CoreAbility.RollbackRegistry originalAbilities,
            RollbackGraphCodec liveCodec) {
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

    private final PaperRollbackTerrainWriteback terrain;
    private final PaperRollbackRosterComponents.Prepared players;
    private final RollbackBendingState.Restoration.LiveCommit gameplay;
    private final RollbackTaskBindings tasks;
    private boolean released;

    private PaperRollbackMatchRestoration(PaperRollbackTerrainWriteback terrain, PaperRollbackRosterComponents.Prepared players,
            RollbackBendingState.Restoration.LiveCommit gameplay, RollbackTaskBindings tasks) {
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
            return new Frame(roster, scene.logical().terrain().changesSince(initial.world().terrain()),
                    graph.bending().exportState(graph.codec()));
        });
        var terrain = PaperRollbackTerrainWriteback.prepare(world, initial.world().terrain(), frame.changes());
        var players = PaperRollbackRosterComponents.prepare(live.nativePlayers(), frame.players(), liveNanos);
        var restored = RollbackBendingState.decodeRestoration(views, frame.bending(), live.liveCodec());
        var tasks = restored.services().stream().filter(RollbackTaskBindings.class::isInstance)
                .map(RollbackTaskBindings.class::cast).toList();
        if (tasks.size() != 1) throw new IllegalStateException("Confirmed gameplay requires one scheduler");
        var gameplay = restored.prepareCommit(live.bendingPlayers(), live.reservation(), live.originalAbilities());
        return new PaperRollbackMatchRestoration(terrain, players, gameplay, tasks.getFirst());
    }

    public void release(PaperRollbackLiveOwnership ownership) {
        if (released) return;
        gameplay.validate();
        ownership.restoreAndRelease(tasks, terrain, players, gameplay::commit);
        released = true;
    }
}
