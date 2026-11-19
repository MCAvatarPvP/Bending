package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import java.util.*;

/** Staged native actors and mount edges belonging to the confirmed frame. */
final class PaperRollbackActorRestoration {
    record Ride(UUID passenger, UUID vehicle) { }
    record Captured(RollbackActorData data, Map<String, Class<?>> contracts, List<Ride> rides) {
        static Captured capture(UUID session, PaperRollbackNativeScene scene) {
            var registry = scene.world().actors();
            var contracts = new TreeMap<String, Class<?>>();
            var rides = new ArrayList<Ride>();
            var entities = new ArrayList<Entity>();
            scene.roster().values().forEach(p -> entities.add(p.ownedPlayer()));
            if (registry != null) {
                registry.bindings().forEach(b -> contracts.put(b.id(), b.type()));
                registry.active().forEach(a -> entities.add(((PaperRollbackActor) a).entity()));
            }
            for (var vehicle : entities) for (var passenger : vehicle.getPassengers()) {
                if (!scene.world().ownsEntity(passenger) || passenger.getVehicle() != vehicle)
                    throw new IllegalStateException("Mount links cross private actor ownership");
                rides.add(new Ride(passenger.getUUID(), vehicle.getUUID()));
            }
            return new Captured(registry == null ? new RollbackActorData(session, 0, List.of()) : registry.exportState(),
                    Map.copyOf(contracts), List.copyOf(rides));
        }
    }

    private final ServerLevel world;
    private final List<Entity> actors = new ArrayList<>();
    private final Map<UUID, Entity> bodies = new HashMap<>();
    private final List<RollbackGraphCodec.Binding> bindings = new ArrayList<>();
    private final List<Ride> rides;

    PaperRollbackActorRestoration(ServerLevel world, Map<UUID, ServerPlayer> players, Captured captured) {
        this.world = world;
        bodies.putAll(players);
        rides = captured.rides();
        for (var row : captured.data().actors()) {
            var id = row.identity();
            var type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.withDefaultNamespace(
                    id.type().name().toLowerCase(Locale.ROOT))).orElseThrow();
            var actor = Objects.requireNonNull(type.create(world, EntitySpawnReason.LOAD));
            if (actor instanceof ServerPlayer || actor.isRemoved() || actor.valid || actor.inWorld)
                throw new IllegalStateException("Invalid staged actor");
            var craft = actor.getBukkitEntity();
            PaperRollbackPrivateAccess.initializePlayer(actor, craft, new LegacyRandomSource(0));
            PaperRollbackActor.restore(actor, id, row.state(), actor.getId());
            actors.add(actor);
            if (bodies.putIfAbsent(actor.getUUID(), actor) != null) throw new IllegalArgumentException("Duplicate actor UUID");
            String binding = "actor/" + id.uuid();
            var contract = Objects.requireNonNull(captured.contracts().get(binding));
            var view = PaperRollbackActorView.wrap(contract, craft);
            bindings.add(new RollbackGraphCodec.Binding(binding, contract, view));
        }
        if (bindings.size() != captured.contracts().size()) throw new IllegalStateException("Incomplete handoff graph bindings");
        validate();
    }

    List<RollbackGraphCodec.Binding> bindings() { return List.copyOf(bindings); }

    void validate() {
        if (RollbackClock.active() || RollbackDomain.active() || !org.bukkit.Bukkit.isPrimaryThread())
            throw new IllegalStateException("Validate staged actors on the live thread");
        for (var actor : actors) {
            var byUuid = world.getEntity(actor.getUUID()); var byNumber = world.getEntity(actor.getId());
            if (actor.level() != world || actor.isRemoved() || byUuid != null && byUuid != actor ||
                    byNumber != null && byNumber != actor || (byUuid == actor) != (byNumber == actor))
                throw new IllegalStateException("Actor handoff identity changed before registration");
            if (!world.hasChunkAt(actor.blockPosition())) throw new IllegalStateException("Actor handoff chunk unloaded");
        }
        for (var ride : rides) {
            var passenger = Objects.requireNonNull(bodies.get(ride.passenger()));
            var vehicle = Objects.requireNonNull(bodies.get(ride.vehicle()));
            if (passenger == vehicle || passenger.getVehicle() != null && passenger.getVehicle() != vehicle)
                throw new IllegalStateException("Live mount ownership changed");
        }
    }

    void commit() {
        validate();
        for (var actor : actors) {
            if (world.getEntity(actor.getUUID()) == actor) continue;
            // Null reason resumes an existing entity without firing a second spawn event.
            if (!world.addWithUUID(actor, null) || world.getEntity(actor.getUUID()) != actor)
                throw new IllegalStateException("World declined actor handoff");
        }
        for (var ride : rides) {
            var passenger = bodies.get(ride.passenger()); var vehicle = bodies.get(ride.vehicle());
            if (passenger.getVehicle() != vehicle && !passenger.startRiding(vehicle, true, true))
                throw new IllegalStateException("World declined restored mount");
        }
    }
}
