package com.projectkorra.projectkorra.prediction.rollback;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeMethods;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.scoreboard.CraftScoreboardManager;
import org.bukkit.craftbukkit.scoreboard.CraftScoreboard;
import org.objenesis.ObjenesisStd;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** Native scoreboard gameplay state and detached provisional updates; no live manager or packet sends. */
public final class PaperRollbackScoreboards implements RollbackStateCell<PaperRollbackScoreboards.Checkpoint> {
    public enum Kind { OBJECTIVE, REMOVE_OBJECTIVE, SCORE, REMOVE_SCORE, REMOVE_SCORES, TEAM, REMOVE_TEAM, DISPLAY }
    public record Change(long board, Kind kind, String name, String holder, String data) implements PaperRollbackCombatAccess.Output { }
    public record Assignment(java.util.UUID player, long board) implements PaperRollbackCombatAccess.Output { }

    private final Thread thread = Thread.currentThread();
    private final PaperRollbackWorldAccess world;
    private final Consumer<PaperRollbackCombatAccess.Output> output;
    private final DynamicOps<JsonElement> ops;
    private final State state = new State();
    private final RollbackStateGraph graph;
    private final CraftScoreboardManager manager = new ObjenesisStd(false).newInstance(CraftScoreboardManager.class);
    private final MinecraftServer server = RollbackNativeQueryShell.create(MinecraftServer.class).instance();
    private final Board main;

    private static final class State {
        long nextId;
        final Map<Long, NativeBoard> boards = new LinkedHashMap<>();
        final Set<NativeBoard> tracked = new LinkedHashSet<>();
        final Map<java.util.UUID, NativeBoard> assigned = new LinkedHashMap<>();
    }

    PaperRollbackScoreboards(PaperRollbackWorldAccess world, RegistryAccess registries, Consumer<PaperRollbackCombatAccess.Output> output) {
        this.world = Objects.requireNonNull(world, "world");
        this.output = Objects.requireNonNull(output, "output");
        ops = registries.createSerializationContext(JsonOps.INSTANCE);
        graph = new RollbackStateGraph(value -> value == this || value == world || value == server || metadata(value), field ->
                !(field.getDeclaringClass().getName().startsWith("it.unimi.dsi.fastutil.")
                        && Map.class.isAssignableFrom(field.getDeclaringClass()) && Collection.class.isAssignableFrom(field.getType())
                        && Set.of("entries", "keys", "values").contains(field.getName())), 100_000);
        main = create(true);
    }

    public Board main() { check(); return main; }
    public Board create(boolean tracked) {
        check();
        if (state.boards.size() >= 128) throw new IllegalStateException("Private scoreboard budget exceeded");
        var board = new NativeBoard(state.nextId++);
        state.boards.put(board.id, board);
        if (tracked) state.tracked.add(board);
        return new Board(board);
    }

    /** Tracks an owned board for native statistic/death criteria, preserving registration order. */
    public void track(Board board) { require(board.nativeBoard); state.tracked.add(board.nativeBoard); }

    /** Native handles remain inside adapter operations; only detached results may escape. */
    public final class Board implements RollbackStateCell<Void> {
        private final NativeBoard nativeBoard;
        private Board(NativeBoard nativeBoard) { this.nativeBoard = nativeBoard; }
        public long id() { require(nativeBoard); return nativeBoard.id; }
        public <R> R use(Function<Scoreboard, R> operation) { require(nativeBoard); return operation.apply(nativeBoard); }
        @Override public Void captureRollbackState() { require(nativeBoard); return null; }
        @Override public void restoreRollbackState(Void saved) { check(); }
        @Override public List<?> rollbackReferences() { check(); return List.of(PaperRollbackScoreboards.this); }
    }

    ServerScoreboard nativeMain() { check(); return main.nativeBoard; }

    public void assign(Object nativePlayer, Board board) {
        var player = requirePlayer(nativePlayer);
        require(board.nativeBoard);
        var previous = state.assigned.getOrDefault(player.getUUID(), main.nativeBoard);
        if (previous == board.nativeBoard) return;
        state.assigned.put(player.getUUID(), board.nativeBoard);
        output.accept(new Assignment(player.getUUID(), board.nativeBoard.id));
    }

    /** Read routes used by Paper's actual canHarmPlayer team policy. */
    CraftScoreboard bukkitBoard(ServerPlayer owner) {
        requirePlayer(owner);
        var board = state.assigned.getOrDefault(owner.getUUID(), main.nativeBoard);
        require(board);
        return board.bukkit;
    }

    private org.bukkit.scoreboard.Team playerTeam(CraftScoreboard token, org.bukkit.OfflinePlayer player) {
        check();
        var board = state.boards.values().stream().filter(value -> value.bukkit == token).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Foreign or discarded scoreboard token"));
        return teamView(board, name(player));
    }

    private org.bukkit.scoreboard.Team teamView(NativeBoard board, String entry) {
        require(board);
        var team = board.getPlayersTeam(entry);
        if (team == null) return null;
        Runnable validate = () -> {
            require(board);
            if (board.getPlayerTeam(team.getName()) != team) throw new IllegalStateException("Private team is no longer registered");
        };
        return RollbackNativeQueryShell.create(org.bukkit.scoreboard.Team.class)
                .query(org.bukkit.scoreboard.Team::allowFriendlyFire, false, args -> { validate.run(); return team.isAllowFriendlyFire(); })
                .query(value -> value.hasPlayer(null), false, args -> { validate.run(); return team.getPlayers().contains(name(args[0])); })
                .query(value -> value.hasEntry(""), false, args -> { validate.run(); return team.getPlayers().contains(Objects.requireNonNull((String) args[0])); })
                .instance();
    }

    private String name(Object wrapper) {
        check();
        if (!world.ownsWrapper(wrapper)) throw new IllegalArgumentException("Scoreboard player belongs to another simulation");
        return ((org.bukkit.entity.Player) wrapper).getName();
    }

    private ServerPlayer requirePlayer(Object value) {
        check();
        if (!(value instanceof ServerPlayer player) || !world.ownsPlayer(player) || player.level() != world.world()) {
            throw new IllegalArgumentException("Scoreboard player belongs to another simulation");
        }
        return player;
    }

    void bind(RollbackNativeMethods methods, CraftServer server) {
        check();
        try {
            var lookup = MethodHandles.lookup();
            var getter = lookup.findVirtual(PaperRollbackScoreboards.class, "manager",
                    MethodType.methodType(CraftScoreboardManager.class, CraftServer.class, CraftServer.class)).bindTo(this);
            methods.replace(CraftServer.class.getMethod("getScoreboardManager"), MethodHandles.insertArguments(getter, 0, server));
            methods.replace(CraftScoreboardManager.class.getMethod("forAllObjectives", ObjectiveCriteria.class, ScoreHolder.class, Consumer.class),
                    lookup.findVirtual(PaperRollbackScoreboards.class, "forAllObjectives",
                            MethodType.methodType(void.class, CraftScoreboardManager.class, ObjectiveCriteria.class, ScoreHolder.class, Consumer.class)).bindTo(this));
            methods.replace(CraftScoreboard.class.getMethod("getPlayerTeam", org.bukkit.OfflinePlayer.class),
                    lookup.findVirtual(PaperRollbackScoreboards.class, "playerTeam",
                            MethodType.methodType(org.bukkit.scoreboard.Team.class, CraftScoreboard.class, org.bukkit.OfflinePlayer.class)).bindTo(this));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native scoreboard methods changed", failure); }
    }

    private CraftScoreboardManager manager(CraftServer expected, CraftServer receiver) {
        check();
        if (expected != receiver) throw new IllegalArgumentException("Foreign scoreboard server");
        return manager;
    }

    private void forAllObjectives(CraftScoreboardManager receiver, ObjectiveCriteria criteria, ScoreHolder holder, Consumer<ScoreAccess> action) {
        check(); requireCriteria(criteria);
        if (receiver != manager || !(holder instanceof ServerPlayer player) || !world.ownsPlayer(player) || player.level() != world.world()) {
            throw new IllegalArgumentException("Scoreboard update belongs to another simulation");
        }
        for (var board : state.tracked) board.forAllObjectives(criteria, holder, action);
    }

    private boolean metadata(Object value) {
        if (value instanceof CraftScoreboard board && state.boards.values().stream().anyMatch(owned -> owned.bukkit == board)) return true;
        if (value instanceof ObjectiveCriteria criteria) { requireCriteria(criteria); return true; }
        return PaperRollbackMetadata.frozen(value);
    }

    private static void requireCriteria(ObjectiveCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria");
        if (criteria.getClass() == Stat.class) {
            PaperRollbackStatistics.requireStat((Stat<?>) criteria);
        } else if (criteria.getClass() != ObjectiveCriteria.class) {
            throw new IllegalArgumentException("Unowned or mutable native score criterion");
        }
    }

    private <T> String encode(Codec<T> codec, T value) {
        String encoded = codec.encodeStart(ops, value).getOrThrow(IllegalArgumentException::new).toString();
        if (encoded.length() > 1_048_576) throw new IllegalStateException("Native scoreboard output exceeds budget");
        return encoded;
    }

    private void require(NativeBoard board) {
        check();
        if (state.boards.get(board.id) != board) throw new IllegalArgumentException("Scoreboard is foreign or belongs to a discarded branch");
    }
    private void check() { if (thread != Thread.currentThread()) throw new IllegalStateException("Native scoreboard crossed threads"); }

    private static final class Bodies {
        static final MethodHandle DISPLAY, ADD_MEMBER, REMOVE_MEMBER;
        static {
            try {
                var display = Scoreboard.class.getDeclaredMethod("setDisplayObjective", DisplaySlot.class, Objective.class);
                var add = Scoreboard.class.getDeclaredMethod("addPlayerToTeam", String.class, PlayerTeam.class);
                var remove = Scoreboard.class.getDeclaredMethod("removePlayerFromTeam", String.class, PlayerTeam.class);
                var methods = new RollbackNativeMethods().copy(display).copy(add).copy(remove).build();
                DISPLAY = methods.get(display); ADD_MEMBER = methods.get(add); REMOVE_MEMBER = methods.get(remove);
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native scoreboard storage methods changed", failure); }
        }
    }

    private final class NativeBoard extends ServerScoreboard {
        final long id;
        // Final native class: an identity token for audited read replacements,
        // never an actual Bukkit board with access to global services.
        final CraftScoreboard bukkit = new ObjenesisStd(false).newInstance(CraftScoreboard.class);
        NativeBoard(long id) { super(server); this.id = id; }
        private void emit(Kind kind, String name, String holder, String data) {
            require(this); super.setDirty(); output.accept(new Change(id, kind, name, holder, data));
        }
        private void own(Objective objective) {
            require(this);
            if (objective == null || objective.getScoreboard() != this || getObjective(objective.getName()) != objective) {
                throw new IllegalArgumentException("Objective is foreign or removed");
            }
        }
        private void own(PlayerTeam team) {
            require(this);
            if (team == null || team.getScoreboard() != this || getPlayerTeam(team.getName()) != team) {
                throw new IllegalArgumentException("Team is foreign or removed");
            }
        }
        @Override public Objective addObjective(String name, ObjectiveCriteria criteria, net.minecraft.network.chat.Component displayName,
                ObjectiveCriteria.RenderType renderType, boolean autoUpdate, net.minecraft.network.chat.numbers.NumberFormat format) {
            require(this); requireCriteria(criteria);
            return super.addObjective(name, criteria, displayName, renderType, autoUpdate, format);
        }
        @Override public ScoreAccess getOrCreatePlayerScore(ScoreHolder holder, Objective objective, boolean readOnly) {
            own(objective);
            if (holder instanceof net.minecraft.world.entity.Entity entity && !world.ownsPlayer(entity)) {
                throw new IllegalArgumentException("Score holder belongs to another simulation");
            }
            return super.getOrCreatePlayerScore(holder, objective, readOnly);
        }
        @Override public void removeObjective(Objective objective) { own(objective); super.removeObjective(objective); }
        @Override public PlayerTeam addPlayerTeam(String name) { require(this); return super.addPlayerTeam(name); }
        @Override public void removePlayerTeam(PlayerTeam team) { own(team); super.removePlayerTeam(team); }
        @Override public void onObjectiveAdded(Objective objective) { objective(objective); }
        @Override public void onObjectiveChanged(Objective objective) { objective(objective); }
        private void objective(Objective objective) {
            own(objective);
            requireCriteria(objective.getCriteria());
            emit(Kind.OBJECTIVE, objective.getName(), null, encode(Objective.Packed.CODEC, objective.pack()));
        }
        @Override public void onObjectiveRemoved(Objective objective) { emit(Kind.REMOVE_OBJECTIVE, objective.getName(), null, null); }
        @Override protected void onScoreChanged(ScoreHolder holder, Objective objective, Score score) {
            own(objective);
            emit(Kind.SCORE, objective.getName(), holder.getScoreboardName(), encode(Scoreboard.PackedScore.CODEC,
                    new Scoreboard.PackedScore(holder.getScoreboardName(), objective.getName(), score.pack())));
        }
        @Override protected void onScoreLockChanged(ScoreHolder holder, Objective objective) {
            var score = getPlayerScoreInfo(holder, objective);
            if (score instanceof Score nativeScore) onScoreChanged(holder, objective, nativeScore);
            else throw new IllegalStateException("Native score lock has no owned score");
        }
        @Override public void onPlayerRemoved(ScoreHolder holder) { emit(Kind.REMOVE_SCORES, null, holder.getScoreboardName(), null); }
        @Override public void onPlayerScoreRemoved(ScoreHolder holder, Objective objective) { emit(Kind.REMOVE_SCORE, objective.getName(), holder.getScoreboardName(), null); }
        @Override public void onTeamAdded(PlayerTeam team) { team(team); }
        @Override public void onTeamChanged(PlayerTeam team) { team(team); }
        @Override public void onTeamRemoved(PlayerTeam team) { emit(Kind.REMOVE_TEAM, team.getName(), null, null); }
        private void team(PlayerTeam team) {
            own(team);
            var value = team.pack();
            var sorted = new PlayerTeam.Packed(value.name(), value.displayName(), value.color(), value.allowFriendlyFire(),
                    value.seeFriendlyInvisibles(), value.memberNamePrefix(), value.memberNameSuffix(), value.nameTagVisibility(),
                    value.deathMessageVisibility(), value.collisionRule(), value.players().stream().sorted().toList());
            emit(Kind.TEAM, team.getName(), null, encode(PlayerTeam.Packed.CODEC, sorted));
        }
        @Override public void setDisplayObjective(DisplaySlot slot, Objective objective) {
            require(this); if (objective != null) own(objective);
            try { Bodies.DISPLAY.invokeExact((Scoreboard) this, slot, objective); }
            catch (Throwable failure) { throw failed(failure); }
            emit(Kind.DISPLAY, slot.getSerializedName(), objective == null ? null : objective.getName(), null);
        }
        @Override public boolean addPlayerToTeam(String name, PlayerTeam team) {
            own(team);
            try { boolean added = (boolean) Bodies.ADD_MEMBER.invokeExact((Scoreboard) this, name, team); if (added) team(team); return added; }
            catch (Throwable failure) { throw failed(failure); }
        }
        @Override public void removePlayerFromTeam(String name, PlayerTeam team) {
            own(team);
            try { Bodies.REMOVE_MEMBER.invokeExact((Scoreboard) this, name, team); team(team); }
            catch (Throwable failure) { throw failed(failure); }
        }
        @Override public boolean addPlayersToTeam(Collection<String> names, PlayerTeam team) {
            own(team); boolean changed = false; for (var name : names) changed |= addPlayerToTeam(name, team); return changed;
        }
        @Override public void removePlayersFromTeam(Collection<String> names, PlayerTeam team) { own(team); for (var name : names) removePlayerFromTeam(name, team); }
        @Override public void startTrackingObjective(Objective objective) { throw new IllegalStateException("Scoreboard recipient synchronization requires the connection adapter"); }
        @Override public void stopTrackingObjective(Objective objective) { throw new IllegalStateException("Scoreboard recipient synchronization requires the connection adapter"); }
        @Override public void storeToSaveDataIfDirty(net.minecraft.world.scores.ScoreboardSaveData data) {
            throw new IllegalStateException("Scoreboard persistence requires finalized delivery");
        }
    }

    @Override public Checkpoint captureRollbackState() { check(); return new Checkpoint(this, graph.capture(List.of(state), List.of())); }
    @Override public List<?> rollbackReferences() { check(); return List.of(world); }
    @Override public void restoreRollbackState(Checkpoint saved) {
        check();
        if (Objects.requireNonNull(saved, "checkpoint").owner != this) throw new IllegalArgumentException("Scoreboards checkpoint belongs to another world");
        saved.state.restore();
    }
    public static final class Checkpoint {
        private final PaperRollbackScoreboards owner;
        private final RollbackStateGraph.Snapshot state;
        private Checkpoint(PaperRollbackScoreboards owner, RollbackStateGraph.Snapshot state) { this.owner = owner; this.state = state; }
    }
    private static RuntimeException failed(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException("Private native scoreboard operation failed", failure);
    }
}
