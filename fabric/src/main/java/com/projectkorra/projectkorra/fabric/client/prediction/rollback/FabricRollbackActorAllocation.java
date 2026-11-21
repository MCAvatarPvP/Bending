package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.Identity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import java.util.Objects;

/** Resolves constructor identity and initial random draws only for the actor being created in this private world. */
public final class FabricRollbackActorAllocation {
    private static final ThreadLocal<FabricRollbackActorAllocation> ACTIVE = new ThreadLocal<>();
    private final ServerWorld world;
    private final EntityType<?> type;
    private final Identity identity;
    private final FabricRollbackPaperRandom random;
    private boolean idTaken, randomTaken;

    private FabricRollbackActorAllocation(ServerWorld world, EntityType<?> type, Identity identity) {
        this.world = world;
        this.type = type;
        this.identity = identity;
        random = new FabricRollbackPaperRandom(identity.uuid().getMostSignificantBits() ^ identity.uuid().getLeastSignificantBits());
    }

    static Entity create(ServerWorld world, EntityType<?> type, Identity identity) {
        if (ACTIVE.get() != null) throw new IllegalStateException("Nested private entity allocation");
        var allocation = new FabricRollbackActorAllocation(world, type, identity);
        ACTIVE.set(allocation);
        try {
            var entity = Objects.requireNonNull(type.create(world, SpawnReason.TRIGGERED), "Cannot create private actor " + identity);
            if (!allocation.idTaken || !allocation.randomTaken || entity.getId() != identity.networkId() || entity.getRandom() != allocation.random)
                throw new IllegalStateException("Native actor constructor did not use its reserved identity and RNG");
            return entity;
        } finally { ACTIVE.remove(); }
    }

    public static Integer networkId(EntityType<?> type, World world) {
        var current = ACTIVE.get();
        if (current == null || current.world != world || current.type != type) return null;
        if (current.idTaken) throw new IllegalStateException("Native actor constructor allocated a second entity");
        current.idTaken = true;
        return current.identity.networkId();
    }

    public static Random random(EntityType<?> type, World world) {
        var current = ACTIVE.get();
        if (current == null || current.world != world || current.type != type) return null;
        if (current.randomTaken) throw new IllegalStateException("Native actor constructor allocated a second RNG");
        current.randomTaken = true;
        return current.random;
    }
}
