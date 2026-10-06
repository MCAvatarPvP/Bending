package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.PKBossBars;
import com.projectkorra.projectkorra.platform.mc.boss.*;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayer;
import java.util.*;

/** Private ability-owned bars. Presentation reads detached views; mutations never send live UI updates. */
public final class RollbackBossBars implements PKBossBars, RollbackStateCell<RollbackBossBars.State> {
    public record View(long id, String title, BarColor color, BarStyle style, double progress,
                       boolean visible, Set<UUID> players) {
        public View { players = Collections.unmodifiableSet(new TreeSet<>(players)); }
    }
    public static final class State {
        private final RollbackBossBars owner;
        private final long next;
        private final Map<Long, Bar> bars;
        private State(RollbackBossBars owner) { this.owner = owner; next = owner.next; bars = Map.copyOf(owner.bars); }
    }
    private final Thread thread = Thread.currentThread();
    private final Map<UUID, RollbackPlayer> roster;
    private final int maximumBars;
    private final Map<Long, Bar> bars = new TreeMap<>();
    private long next = 1;
    public RollbackBossBars(Collection<RollbackPlayer> players, int maximumBars) {
        if (maximumBars < 1 || maximumBars > 65536) throw new IllegalArgumentException("Boss bar budget");
        this.maximumBars = maximumBars;
        var roster = new TreeMap<UUID, RollbackPlayer>();
        Object world = null;
        for (var player : players) {
            if (world == null) world = player.getWorld();
            if (world != player.getWorld() || roster.putIfAbsent(player.getUniqueId(), player) != null)
                throw new IllegalArgumentException("Boss bar roster");
        }
        if (roster.isEmpty() || roster.size() > 128) throw new IllegalArgumentException("Boss bar roster size");
        this.roster = Collections.unmodifiableMap(roster);
    }
    @Override public BossBar.Delegate create(String title, BarColor color, BarStyle style) {
        checkThread(); Objects.requireNonNull(title); Objects.requireNonNull(color); Objects.requireNonNull(style);
        if (bars.size() >= maximumBars || next == Long.MAX_VALUE) throw new IllegalStateException("Boss bar budget exhausted");
        var bar = new Bar(new View(next++, title, color, style, 1, true, Set.of()));
        bars.put(bar.value.id(), bar); return bar;
    }
    public List<View> views() { checkThread(); return bars.values().stream().map(bar -> bar.value).toList(); }
    private final class Bar implements BossBar.Delegate, RollbackStateCell<View> {
        private View value;
        private Bar(View value) { this.value = value; }
        private void active() {
            checkThread(); if (bars.get(value.id()) != this) throw new IllegalStateException("Discarded boss bar branch");
        }
        @SuppressWarnings("WrapperReferenceEquality") // Equal UUIDs from another replica must not join this bar.
        private UUID player(Player player) {
            active(); Objects.requireNonNull(player);
            if (roster.get(player.getUniqueId()) != player) throw new IllegalArgumentException("Foreign boss bar player");
            return player.getUniqueId();
        }
        private void members(Set<UUID> players) { value = new View(value.id(), value.title(), value.color(), value.style(), value.progress(), value.visible(), players); }
        @Override public void addPlayer(Player player) { var id = player(player); var members = new TreeSet<>(value.players()); members.add(id); members(members); }
        @Override public void removePlayer(Player player) { var id = player(player); var members = new TreeSet<>(value.players()); members.remove(id); members(members); }
        @Override public void removeAll() { active(); members(Set.of()); }
        @Override public void setProgress(double progress) {
            active(); if (!Double.isFinite(progress) || progress < 0 || progress > 1) throw new IllegalArgumentException("Boss bar progress");
            value = new View(value.id(), value.title(), value.color(), value.style(), progress, value.visible(), value.players());
        }
        @Override public void setTitle(String title) { active(); value = new View(value.id(), Objects.requireNonNull(title), value.color(), value.style(), value.progress(), value.visible(), value.players()); }
        @Override public void setColor(BarColor color) { active(); value = new View(value.id(), value.title(), Objects.requireNonNull(color), value.style(), value.progress(), value.visible(), value.players()); }
        @Override public void setVisible(boolean visible) { active(); value = new View(value.id(), value.title(), value.color(), value.style(), value.progress(), visible, value.players()); }
        @Override public View captureRollbackState() { active(); return value; }
        @Override public void restoreRollbackState(View state) { checkThread(); value = state; }
    }
    @Override public State captureRollbackState() { checkThread(); return new State(this); }
    @Override public void restoreRollbackState(State state) {
        checkThread(); if (state.owner != this) throw new IllegalArgumentException("Foreign boss bar checkpoint");
        next = state.next; bars.clear(); bars.putAll(state.bars);
    }
    @Override public Collection<?> rollbackReferences() { checkThread(); var roots = new ArrayList<Object>(roster.values()); roots.addAll(bars.values()); return List.copyOf(roots); }
    private void checkThread() { if (thread != Thread.currentThread()) throw new IllegalStateException("Boss bars crossed threads"); }
}
