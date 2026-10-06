package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import java.util.*;
import java.util.function.Function;

/** Local canonical handles for one roster; UUID equality alone never grants access to a binding. */
public final class RollbackRosterBindings implements Function<Object, Object> {
    private record Participant(Player view, Object handle) { }
    private final Thread thread = Thread.currentThread();
    private final World world;
    private final Object worldHandle;
    private final Map<UUID, Participant> players;
    private final List<RollbackGraphCodec.Binding> bindings;

    public RollbackRosterBindings(World world, Collection<? extends Player> players) {
        if (RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Bind roster before replay");
        this.world = Objects.requireNonNull(world);
        worldHandle = Objects.requireNonNull(world.handle());
        var roster = new TreeMap<UUID, Participant>();
        Set<Object> handles = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Player player : players) {
            Objects.requireNonNull(player);
            Object handle = Objects.requireNonNull(player.handle());
            requireWorld(player.getWorld());
            if (!player.isOnline() || !handles.add(handle)
                    || roster.putIfAbsent(Objects.requireNonNull(player.getUniqueId()), new Participant(player, handle)) != null)
                throw new IllegalArgumentException("Invalid canonical roster identity");
        }
        if (roster.size() < 2 || roster.size() > 128) throw new IllegalArgumentException("Duel roster size");
        this.players = Collections.unmodifiableMap(roster);
        var entries = new ArrayList<RollbackGraphCodec.Binding>();
        entries.add(new RollbackGraphCodec.Binding("world", World.class, world));
        roster.forEach((id, participant) -> entries.add(new RollbackGraphCodec.Binding("player/" + id, Player.class, participant.view)));
        bindings = List.copyOf(entries);
    }

    public List<RollbackGraphCodec.Binding> bindings() { checkThread(); return bindings; }

    /** Returns the local canonical binding, or null for objects outside this adapter's contracts. */
    @Override public Object apply(Object value) {
        checkThread();
        if (value instanceof Player player) {
            Participant participant = players.get(player.getUniqueId());
            if (participant == null || player.handle() != participant.handle || participant.view.handle() != participant.handle
                    || !player.isOnline() || !participant.view.isOnline()
                    || !participant.view.getUniqueId().equals(player.getUniqueId()))
                throw new IllegalArgumentException("Player is not the enrolled native identity");
            requireWorld(player.getWorld()); requireWorld(participant.view.getWorld());
            return participant.view;
        }
        if (value instanceof World view) { requireWorld(view); return world; }
        return null;
    }
    private void requireWorld(World view) {
        if (view == null || view.handle() != worldHandle || world.handle() != worldHandle)
            throw new IllegalArgumentException("World is not the enrolled native identity");
    }
    private void checkThread() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Roster bindings crossed threads");
    }
}
