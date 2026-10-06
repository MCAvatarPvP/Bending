package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.prediction.rollback.RollbackClock;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayer;
import com.projectkorra.projectkorra.prediction.rollback.RollbackScheduler;
import com.projectkorra.projectkorra.platform.Platform;

import com.projectkorra.projectkorra.Manager;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.platform.mc.GameMode;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.Set;
import java.util.Comparator;
import java.util.function.BiConsumer;

public class FlightHandler extends Manager {

    /**
     * A Map containing all Flight instances.
     */
    private final Map<UUID, Flight> INSTANCES = new HashMap<>();
    /**
     * A PriorityQueue containing all Flight instances which have a specified
     * duration. This is used to reduce the number of iterations when cleaning
     * up dead instances.
     */
    private final PriorityQueue<FlightAbility> CLEANUP = new PriorityQueue<>(100, new FlightDurationOrder());

    // Preserve the existing queue order; a named comparator can be checkpointed.
    private static final class FlightDurationOrder implements Comparator<FlightAbility> {
        @Override public int compare(FlightAbility first, FlightAbility second) {
            return (int) (first.duration - second.duration);
        }
    }

    private FlightHandler() {
    }

    @Override
    protected void projectRollbackState(Set<UUID> participants, BiConsumer<Object, Object> project) {
        if (getClass() != FlightHandler.class) throw new UnsupportedOperationException("Flight manager subclass requires its own rollback import");
        var instances = new HashMap<UUID, Flight>();
        this.INSTANCES.forEach((id, flight) -> { if (participants.contains(id)) instances.put(id, flight); });
        // Clone retains heap ordering, including equal-duration entries, before filtering.
        var cleanup = new PriorityQueue<>(this.CLEANUP);
        cleanup.removeIf(ability -> !participants.contains(ability.player.getUniqueId()));
        project.accept(this.INSTANCES, instances);
        project.accept(this.CLEANUP, cleanup);
    }

    @Override
    protected List<?> projectRollbackRestoration(Set<UUID> participants, Manager live, BiConsumer<Object, Object> bind) {
        if (getClass() != FlightHandler.class || live.getClass() != FlightHandler.class) throw new UnsupportedOperationException("Flight manager restoration subclass");
        var target = (FlightHandler) live;
        bind.accept(INSTANCES, target.INSTANCES); bind.accept(CLEANUP, target.CLEANUP);
        return List.of(new HashMap<>(INSTANCES), new ArrayList<>(CLEANUP));
    }

    @Override @SuppressWarnings("unchecked")
    protected RestorationStep prepareRollbackRestoration(Set<UUID> participants, List<?> roots) {
        if (roots.size() != 2) throw new IllegalArgumentException("Flight restoration roots");
        var restored = (Map<UUID, Flight>) roots.get(0);
        var cleanup = (List<FlightAbility>) roots.get(1);
        for (var entry : restored.entrySet()) {
            if (!participants.contains(entry.getKey()) || !entry.getKey().equals(entry.getValue().player.getUniqueId())
                    || entry.getValue().player instanceof RollbackPlayer)
                throw new IllegalArgumentException("Flight restoration participant binding");
            var flight = entry.getValue();
            if (flight.source != null && (!participants.contains(flight.source.getUniqueId()) || flight.source instanceof RollbackPlayer))
                throw new IllegalArgumentException("Flight restoration source binding");
            for (var ability : flight.abilities.entrySet()) {
                if (ability.getValue().player.handle() != flight.player.handle() || !ability.getKey().equals(ability.getValue().identifier))
                    throw new IllegalArgumentException("Flight restoration grant binding");
            }
        }
        for (var ability : cleanup) if (!participants.contains(ability.player.getUniqueId())
                || ability.player instanceof RollbackPlayer)
            throw new IllegalArgumentException("Flight expiry outside restored roster");
        return new RestorationStep() {
            @Override public void validate() { }
            @Override public void commit() {
                participants.forEach(INSTANCES::remove); INSTANCES.putAll(restored);
                CLEANUP.removeIf(ability -> participants.contains(ability.player.getUniqueId())); CLEANUP.addAll(cleanup);
            }
        };
    }

    @Override
    protected void onRollbackInstall() {
        if (!(Platform.scheduler() instanceof RollbackScheduler scheduler)) throw new IllegalStateException("Flight cleanup requires a private scheduler");
        scheduler.runServiceTimer(this::cleanupExpired, 0, 1);
    }

    @Override
    public void onActivate() {
        this.startCleanup();
    }

    /**
     * Create a new Flight instance for the provided player with an unlimited
     * duration. Call {@link FlightHandler#removeInstance(Player, String)} to remove
     * this instance when necessary.
     *
     * @param player     The flying player
     * @param identifier The ability using Flight
     */
    public void createInstance(final Player player, final String identifier) {
        this.createInstance(player, Flight.PERMANENT, identifier);
    }

    /**
     * Create a new Flight instance for the provided player with an unlimited
     * duration. This method will set the source for this Flight instance to the
     * second provided player argument. Call
     * {@link FlightHandler#removeInstance(Player, String)} to remove this instance when
     * necessary.
     *
     * @param player     The flying player
     * @param source     The source player
     * @param identifier The ability using Flight
     */
    public void createInstance(final Player player, final Player source, final String identifier) {
        this.createInstance(player, source, Flight.PERMANENT, identifier);
    }

    /**
     * Create a new Flight instance with the specified duration. This instance
     * will automatically be removed with the set delay.
     *
     * @param player     The flying player
     * @param duration   Flight duration
     * @param identifier The ability using Flight
     */
    public void createInstance(final Player player, final long duration, final String identifier) {
        this.createInstance(player, null, duration, identifier);
    }

    /**
     * Create a new Flight instance with the specified duration. This method
     * will set the source for this Flight instance to the second provided
     * player argument. This instance will automatically be removed with the set
     * delay.
     *
     * @param player     The flying player
     * @param source     The source player
     * @param duration   Flight duration
     * @param identifier The ability using Flight
     */
    public void createInstance(final Player player, final Player source, final long duration, final String identifier) {
        if (this.INSTANCES.containsKey(player.getUniqueId())) {
            final Flight flight = this.INSTANCES.get(player.getUniqueId());
            final FlightAbility ability = new FlightAbility(player, identifier, duration);
            if (duration != Flight.PERMANENT) {
                this.CLEANUP.add(ability);
            }
            flight.abilities.put(identifier, ability);
        } else {
            final Flight flight = new Flight(player, source);
            final FlightAbility ability = new FlightAbility(player, identifier, duration);
            if (duration != Flight.PERMANENT) {
                this.CLEANUP.add(ability);
            }
            flight.abilities.put(identifier, ability);
            this.INSTANCES.put(player.getUniqueId(), flight);
        }
    }

    /**
     * Remove a player's Flight status with the provided identifier. If this is
     * the last ability using Flight, then their Flight instance shall be
     * reverted to its initial state. This method does not need to be called for
     * instances with a defined duration, however can be used in this case if
     * necessary.
     *
     * @param player     The flying player
     * @param identifier The ability using Flight
     */
    public void removeInstance(final Player player, final String identifier) {
        if (this.INSTANCES.containsKey(player.getUniqueId())) {
            final Flight flight = this.INSTANCES.get(player.getUniqueId());
            if (flight.abilities.containsKey(identifier)) {
                flight.abilities.remove(identifier);
            }
            if (flight.abilities.isEmpty()) {
                this.wipeInstance(player);
            }
        }
    }

    /**
     * Completely wipe all Flight data for the player. Should only be used if it
     * is guaranteed they have a Flight instance.
     *
     * @param player
     */
    private void wipeInstance(final Player player) {
        final Flight flight = this.INSTANCES.get(player.getUniqueId());

        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setAllowFlight(true);
            player.setFlying(true);
        } else if (player.getGameMode() == GameMode.CREATIVE) {
            player.setAllowFlight(true);
            player.setFlying(flight.wasFlying);
        } else {
            player.setAllowFlight(flight.couldFly);
            player.setFlying(flight.wasFlying);
        }

        flight.abilities.values().forEach(ability -> this.CLEANUP.remove(ability));
        this.INSTANCES.remove(player.getUniqueId());
    }

    /**
     * Get the provided player's Flight instance.
     *
     * @param player The flying player
     * @return Flight instance
     */
    public Flight getInstance(final Player player) {
        if (this.INSTANCES.containsKey(player.getUniqueId())) {
            return this.INSTANCES.get(player.getUniqueId());
        }
        return null;
    }

    /**
     * Returns whether another bending ability still owns the player's shared
     * flight lease. Individual abilities must consult this before temporarily
     * clearing the vanilla flying bits; otherwise one spout can disable a
     * simultaneously active spout, scooter, jet, or addon flight grant.
     *
     * @param player the player whose leases should be inspected
     * @param identifier the calling ability's lease identifier
     * @return true when a different identifier still owns flight
     */
    public boolean hasOtherInstance(final Player player, final String identifier) {
        if (player == null) {
            return false;
        }
        final Flight flight = this.INSTANCES.get(player.getUniqueId());
        if (flight == null) {
            return false;
        }
        return flight.abilities.keySet().stream()
                .anyMatch(current -> !current.equals(identifier));
    }

    /**
     * Removes every bending-owned flight grant for a player and restores the
     * state captured before the first grant was created.
     */
    public void removeAll(final Player player) {
        if (player != null && this.INSTANCES.containsKey(player.getUniqueId())) {
            this.wipeInstance(player);
        }
    }

    public void startCleanup() {
        Platform.scheduler().runTimer(this::cleanupExpired, 0, 1);
    }

    private void cleanupExpired() {
        final long currentTime = RollbackClock.millis();
        while (!this.CLEANUP.isEmpty()) {
            final FlightAbility ability = this.CLEANUP.peek();
            if (currentTime >= ability.startTime + ability.duration) {
                this.CLEANUP.poll();
                this.removeInstance(ability.player, ability.identifier);
            } else {
                break;
            }
        }
    }

    public static class Flight {

        public static final int PERMANENT = -1;

        private final Player player;
        private final Player source;
        private final boolean couldFly;
        private final boolean wasFlying;
        private final Map<String, FlightAbility> abilities;

        public Flight(final Player player, final Player source) {
            this.player = player;
            this.source = source;
            this.couldFly = player.getAllowFlight();
            this.wasFlying = player.isFlying();
            this.abilities = new HashMap<>();
        }

        public Player getPlayer() {
            return this.player;
        }

        public Player getSource() {
            return this.source;
        }

        @Override
        public String toString() {
            return "Flight{player=" + this.player.getName() + ",source=" + (this.source != null ? this.source.getName() : "null") + ",couldFly=" + this.couldFly + ",wasFlying=" + this.wasFlying + ",abilities=" + this.abilities + "}";
        }
    }

    public static class FlightAbility {

        private final Player player;
        private final String identifier;
        private final long duration;
        private final long startTime;

        public FlightAbility(final Player player, final String identifier, final long duration) {
            this.player = player;
            this.identifier = identifier;
            this.duration = duration;
            this.startTime = RollbackClock.millis();
        }

        @Override
        public String toString() {
            return "FlightAbility{player=" + this.player.getName() + ",identifier=" + this.identifier + ",duration=" + this.duration + ",startTime=" + this.startTime + "}";
        }
    }
}
