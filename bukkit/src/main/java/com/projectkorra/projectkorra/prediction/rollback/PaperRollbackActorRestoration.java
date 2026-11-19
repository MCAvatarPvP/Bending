package com.projectkorra.projectkorra.prediction.rollback;

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
        static Captured capture(UUID session, PaperRollbackNativeScene scene) { return capture(session, scene, null); }

        static Captured capture(UUID session, PaperRollbackNativeScene scene, RollbackActorData history) {
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
            return new Captured(registry == null ? new RollbackActorData(session, 0, List.of()) : registry.exportState(history),
                    Map.copyOf(contracts), List.copyOf(rides));
        }
    }

    private final ServerLevel world;
    private final java.util.function.Function<Entity, org.bukkit.craftbukkit.entity.CraftEntity> wrappers;
    private final List<Entity> actors = new ArrayList<>();
    private final Map<UUID, Entity> bodies = new HashMap<>();
    private final List<RollbackGraphCodec.Binding> bindings = new ArrayList<>();
    private final List<Ride> rides;
    private final java.lang.invoke.MethodHandle setVehicle;


    PaperRollbackActorRestoration(ServerLevel world, Map<UUID, ServerPlayer> players, Captured captured) {
        this(world, players, captured, Entity::getBukkitEntity);
    }

    PaperRollbackActorRestoration(ServerLevel world, Map<UUID, ServerPlayer> players, Captured captured,
            java.util.function.Function<Entity, org.bukkit.craftbukkit.entity.CraftEntity> wrappers) {
        this.world = world;
        this.wrappers = Objects.requireNonNull(wrappers);
        bodies.putAll(players);
        rides = captured.rides();
        try {
            var vehicle = Entity.class.getDeclaredField("vehicle");
            vehicle.setAccessible(true);
            setVehicle = java.lang.invoke.MethodHandles.lookup().unreflectSetter(vehicle);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native mount link changed", failure); }
        var applications = new ArrayList<PaperRollbackAreaEffectCloud.Applications>();
        for (var row : captured.data().actors()) {
            var id = row.identity();
            var actor = stage(world, wrappers, row, applications::add);
            var craft = actor.getBukkitEntity();
            actors.add(actor);
            if (bodies.putIfAbsent(actor.getUUID(), actor) != null) throw new IllegalArgumentException("Duplicate actor UUID");
            String binding = "actor/" + id.uuid();
            var contract = Objects.requireNonNull(captured.contracts().get(binding));
            var view = PaperRollbackActorView.wrap(contract, craft);
            bindings.add(new RollbackGraphCodec.Binding(binding, contract, view));
        }
        applications.forEach(pulse -> pulse.bind(bodies));
        if (bindings.size() != captured.contracts().size()) throw new IllegalStateException("Incomplete handoff graph bindings");
        validate();
    }

    static Entity stage(ServerLevel world, java.util.function.Function<Entity, org.bukkit.craftbukkit.entity.CraftEntity> wrappers,
                        RollbackActorData.Row row, java.util.function.Consumer<PaperRollbackAreaEffectCloud.Applications> applications) {
        var id = row.identity();
        var type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.withDefaultNamespace(
                id.type().name().toLowerCase(Locale.ROOT))).orElseThrow();
        var actor = Objects.requireNonNull(type.create(world, EntitySpawnReason.LOAD));
        if (actor instanceof ServerPlayer || actor.isRemoved() || actor.valid || actor.inWorld)
            throw new IllegalStateException("Invalid staged actor");
        PaperRollbackPrivateAccess.initializePlayer(actor, wrappers.apply(actor), new LegacyRandomSource(0));
        PaperRollbackActor.restore(actor, id, row.state(), actor.getId(), applications);
        return actor;
    }

    /** A removed damage source remains available to combat history without joining the live world tracker. */
    Map<UUID, Entity> combatHistory(RollbackActorData history, RollbackActorData active) {
        if (history == null) return Map.of();
        history.requireHistoryFor(active);
        var result = new HashMap<UUID, Entity>();
        for (var row : history.actors()) {
            var id = row.identity();
            var entity = bodies.get(id.uuid());
            if (entity == null) {
                entity = stage(world, wrappers, row, pulse -> pulse.bind(bodies));
                PaperRollbackPrivateAccess.retirePrivateActor(entity);
            } else if (entity instanceof ServerPlayer || !BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath()
                    .equals(id.type().name().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Combat history aliases another native actor type");
            }
            result.put(id.uuid(), entity);
        }
        return result;
    }

    List<RollbackGraphCodec.Binding> bindings() { return List.copyOf(bindings); }

    void validate() {
        if (RollbackClock.active() || RollbackDomain.active() || !ca.spottedleaf.moonrise.common.util.TickThread.isTickThread())
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
            if (passenger.getVehicle() == null) {
                try { setVehicle.invokeExact(passenger, vehicle); }
                catch (Throwable failure) { throw new IllegalStateException("Could not restore mounted actor", failure); }
            }
            if (!vehicle.passengers.contains(passenger))
                vehicle.passengers = com.google.common.collect.ImmutableList.<Entity>builder().addAll(vehicle.passengers).add(passenger).build();
            // The standard tracker observes passengers on its next sendChanges tick.

        }
    }
}
