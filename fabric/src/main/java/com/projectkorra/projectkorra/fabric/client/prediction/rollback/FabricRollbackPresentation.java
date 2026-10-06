package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.WorldRenderer;
import java.util.*;

/** Bridges completed replica updates to detached player motion/pose, then to the session's separate effect sink. */
public final class FabricRollbackPresentation<S, E> implements RollbackClientRuntime.Output<S, E> {
    private final Map<UUID, FabricRollbackNativePlayerState> players;
    private final UUID viewer;
    private final RollbackReplicaTimeline<S, RollbackPlayerInput, E> timeline;
    private final RollbackClientRuntime.Output<S, E> effects;
    private final FabricRollbackPlayerRenderer renderer;
    private boolean stopped;
    public static <S, E> FabricRollbackPresentation<S, E> prepare(MinecraftClient client, UUID session, FabricRollbackRoster roster,
            RollbackReplicaTimeline<S, RollbackPlayerInput, E> timeline, RollbackClientRuntime.Output<S, E> effects) {
        if (!client.isOnThread() || client.world == null || client.player == null || client.getNetworkHandler() == null) throw new IllegalStateException("No render-thread client session");
        var world = client.world; var player = client.player; var connection = client.getNetworkHandler();
        var renderer = new FabricRollbackPlayerRenderer(session, world, roster.players().keySet(),
                () -> client.world == world && client.player == player && client.getNetworkHandler() == connection,
                () -> client.gameRenderer.getCamera().getCameraPos(), position -> WorldRenderer.getLightmapCoordinates(world, position));
        renderer.bindCamera(player.getUuid(), client.gameRenderer.getCamera());
        return new FabricRollbackPresentation<>(roster, player.getUuid(), timeline, effects, renderer);
    }
    FabricRollbackPresentation(FabricRollbackRoster roster, UUID viewer, RollbackReplicaTimeline<S, RollbackPlayerInput, E> timeline,
                               RollbackClientRuntime.Output<S, E> effects, FabricRollbackPlayerRenderer renderer) {
        this(roster.players(), viewer, timeline, effects, renderer);
    }
    FabricRollbackPresentation(Map<UUID, FabricRollbackNativePlayerState> players, UUID viewer, RollbackReplicaTimeline<S, RollbackPlayerInput, E> timeline,
                               RollbackClientRuntime.Output<S, E> effects, FabricRollbackPlayerRenderer renderer) {
        this.players = Map.copyOf(players); this.viewer = Objects.requireNonNull(viewer); this.timeline = Objects.requireNonNull(timeline);
        this.effects = Objects.requireNonNull(effects); this.renderer = Objects.requireNonNull(renderer);
        if (!timeline.replica() || !players.containsKey(viewer) || !players.keySet().equals(Set.copyOf(timeline.participants()))) throw new IllegalArgumentException("Presentation roster differs from replica");
        var world = players.get(viewer).ownedPlayer().getEntityWorld();
        players.forEach((id, player) -> { if (!id.equals(player.identity().uuid()) || player.ownedPlayer().getEntityWorld() != world) throw new IllegalArgumentException("Foreign presentation player"); });
    }
    @Override public void update(RollbackEngine.Update<S, RollbackPlayerInput, E> update) {
        if (stopped) throw new IllegalStateException("Presentation already stopped");
        try {
            var head = timeline.diagnostics();
            if (head.failed() || head.tick() != update.head().tick() || head.revision() != update.revision()) throw new IllegalStateException("Cannot present a stale replica head");
            var views = new TreeMap<UUID, FabricRollbackPlayerView>(); var local = players.get(viewer).ownedPlayer();
            players.forEach((id, state) -> views.put(id, state.use(player -> FabricRollbackPlayerView.capture(player, local))));
            renderer.publish(update.head().tick(), update.revision(), views);
            effects.update(update);
        } catch (RuntimeException | Error failure) { renderer.close(); throw failure; }
    }
    @Override public void stop(RollbackStartServerEndpoint.Failure failure) {
        if (stopped) return; stopped = true;
        try { renderer.close(); } finally { effects.stop(failure); }
    }
}
