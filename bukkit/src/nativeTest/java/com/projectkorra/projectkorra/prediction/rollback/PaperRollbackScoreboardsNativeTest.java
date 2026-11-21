package com.projectkorra.projectkorra.prediction.rollback;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackScoreboardsNativeTest {
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }

    @Test void nativePlayerAwardsUpdateEveryTrackedBoardAndReplayCountersScoresAndOutputTogether() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var main = scene.boards.main();
            var tracked = scene.boards.create(true);
            var untracked = scene.boards.create(false);
            var first = objective(main, "jumps");
            var second = objective(tracked, "plugin_jumps");
            var third = objective(untracked, "untracked_jumps");
            scene.combat.outputs.clear();
            var saved = scene.snapshot(scene.state);
            var stat = Stats.CUSTOM.get(Stats.JUMP);
            scene.state.awardStatistic(stat, 3);
            assertEquals(3, scene.player.getStats().getValue(stat));
            assertEquals(3, score(main, first, scene.player));
            assertEquals(3, score(tracked, second, scene.player));
            assertEquals(-1, score(untracked, third, scene.player));
            var outputs = List.copyOf(scene.combat.outputs);
            assertEquals(2, outputs.size());
            assertTrue(outputs.stream().allMatch(value -> value instanceof PaperRollbackScoreboards.Change change && change.kind() == PaperRollbackScoreboards.Kind.SCORE));
            assertTrue(((PaperRollbackScoreboards.Change) outputs.getFirst()).data().contains("rollback_scores"));
            var scored = scene.snapshot(scene.state);
            scene.state.awardStatistic(stat, 7);
            scored.restore();
            assertEquals(3, scene.player.getStats().getValue(stat));
            assertEquals(3, score(main, first, scene.player));
            assertEquals(outputs, scene.combat.outputs);
            scene.player.setHealth(8);
            saved.restore();
            assertEquals(0, scene.player.getStats().getValue(stat));
            assertEquals(-1, score(main, first, scene.player));
            assertEquals(20, scene.player.getHealth());
            assertTrue(scene.combat.outputs.isEmpty());
            assertTrue(scene.combat.events.isEmpty());
            scene.state.awardStatistic(stat, 3);
            assertEquals(outputs, scene.combat.outputs);
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void statisticCancellationStillUpdatesObjectivesAsPaperDoesAndResetUsesNativeOrdering() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var board = scene.boards.main();
            var objective = objective(board, "jumps");
            var stat = Stats.CUSTOM.get(Stats.JUMP);
            scene.combat.cancelStatistics = true;
            scene.state.awardStatistic(stat, 2);
            assertEquals(0, scene.player.getStats().getValue(stat));
            assertEquals(2, score(board, objective, scene.player));
            scene.combat.cancelStatistics = false;
            scene.state.awardStatistic(stat, 3);
            assertEquals(3, scene.player.getStats().getValue(stat));
            assertEquals(5, score(board, objective, scene.player));
            int events = scene.combat.events.size();
            scene.state.resetStatistic(stat);
            assertEquals(events, scene.combat.events.size());
            assertEquals(0, scene.player.getStats().getValue(stat));
            assertEquals(0, score(board, objective, scene.player));
            return null;
        });
    }

    @Test void teamsDisplaySlotsRemovalAndNewBoardMembershipRestoreWithRetainedBoardAsRoot() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var board = scene.boards.main();
            var objective = objective(board, "jumps");
            var team = board.use(value -> value.addPlayerTeam("duel"));
            team.setAllowFriendlyFire(false);
            team.setPlayerPrefix(Component.literal("[duel] "));
            board.use(value -> {
                value.addPlayerToTeam(scene.player.getScoreboardName(), team);
                value.addPlayerToTeam("zeta", team);
                value.addPlayerToTeam("alpha", team);
                value.setDisplayObjective(DisplaySlot.SIDEBAR, objective);
                return null;
            });
            assertSame(team, scene.player.getTeam());
            scene.combat.outputs.clear();
            var saved = scene.snapshot(board);
            var branch = scene.boards.create(true);
            long discardedId = branch.id();
            objective(branch, "discarded");
            scene.state.awardStatistic(Stats.CUSTOM.get(Stats.JUMP), 4);
            team.setAllowFriendlyFire(true);
            board.use(value -> { value.removePlayerTeam(team); value.removeObjective(objective); return null; });
            assertNull(scene.player.getTeam());
            assertNull(board.use(value -> value.getDisplayObjective(DisplaySlot.SIDEBAR)));
            saved.restore();
            assertSame(team, scene.player.getTeam());
            assertFalse(team.isAllowFriendlyFire());
            assertEquals(List.of("alpha", "rollback_scores", "zeta"), team.getPlayers().stream().sorted().toList());
            assertSame(objective, board.use(value -> value.getDisplayObjective(DisplaySlot.SIDEBAR)));
            assertEquals(0, scene.player.getStats().getValue(Stats.CUSTOM, Stats.JUMP));
            assertTrue(scene.combat.outputs.isEmpty());
            assertThrows(IllegalArgumentException.class, () -> branch.use(value -> value.getObjective("discarded")));
            assertEquals(discardedId, scene.boards.create(true).id());
            assertThrows(IllegalArgumentException.class, branch::id);
            return null;
        });
    }

    @Test void trackingRestoresAndForeignObjectsOrThreadsCannotMutatePrivateBoards() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var other = new Scene();
            var board = scene.boards.create(false);
            var ownObjective = objective(board, "own");
            var foreignObjective = objective(other.boards.main(), "foreign");
            var foreignTeam = other.boards.main().use(value -> value.addPlayerTeam("foreign"));
            var saved = scene.snapshot(scene.state);
            scene.boards.track(board);
            scene.state.awardStatistic(Stats.CUSTOM.get(Stats.JUMP), 1);
            assertEquals(1, score(board, ownObjective, scene.player));
            saved.restore();
            scene.state.awardStatistic(Stats.CUSTOM.get(Stats.JUMP), 1);
            assertEquals(-1, score(board, ownObjective, scene.player));
            assertThrows(IllegalArgumentException.class, () -> scene.boards.track(other.boards.main()));
            assertThrows(IllegalArgumentException.class, () -> board.use(value -> { value.setDisplayObjective(DisplaySlot.SIDEBAR, foreignObjective); return null; }));
            assertThrows(IllegalArgumentException.class, () -> board.use(value -> value.addPlayerToTeam("x", foreignTeam)));
            assertTrue(foreignTeam.getPlayers().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> board.use(value -> value.getOrCreatePlayerScore(other.player, ownObjective)));
            assertThrows(IllegalArgumentException.class, () -> scene.boards.restoreRollbackState(other.boards.captureRollbackState()));
            assertThrows(IllegalStateException.class, () -> board.use(value -> { ((net.minecraft.server.ServerScoreboard) value).storeToSaveDataIfDirty(null); return null; }));
            var failure = assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(() -> board.use(value -> value.addPlayerTeam("bad"))).join());
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            return null;
        });
    }

    private static Objective objective(PaperRollbackScoreboards.Board board, String name) {
        return board.use(value -> value.addObjective(name, Stats.CUSTOM.get(Stats.JUMP), Component.literal(name), ObjectiveCriteria.RenderType.INTEGER, true, null));
    }
    private static int score(PaperRollbackScoreboards.Board board, Objective objective, ServerPlayer player) {
        return board.use(value -> { var result = value.getPlayerScoreInfo(player, objective); return result == null ? -1 : result.value(); });
    }
    private static final class Scene {
        final PaperRollbackDamageNativeTest.Combat combat = new PaperRollbackDamageNativeTest.Combat();
        final PaperRollbackWorldAccess world = new PaperRollbackWorldAccess(new PaperRollbackWorldAccessNativeTest.Queries(), combat, 4);
        final PaperRollbackScoreboards boards = world.scoreboards();
        final PaperRollbackNativePlayerState state = PaperRollbackNativePlayerState.serverPlayer(world,
                new GameProfile(new UUID(0, 96), "rollback_scores"), ClientInformation.createDefault(), GameType.SURVIVAL, 92, 100_000);
        final ServerPlayer player = state.use(value -> (ServerPlayer) value);
        RollbackStateGraph.Snapshot snapshot(Object root) { return new RollbackStateGraph(value -> false, field -> true, 100_000).capture(List.of(root), List.of()); }
    }
}
