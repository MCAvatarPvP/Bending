package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.fabric.mixin.client.EntityRollbackContextAccess;
import com.projectkorra.projectkorra.prediction.rollback.RollbackClock;
import com.projectkorra.projectkorra.prediction.rollback.RollbackDomain;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerContext;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerValues.Vector;
import net.minecraft.fluid.Fluid;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import java.util.*;

/** Native controls/contact values plus explicit retained state for Paper-only policy. */
public final class FabricRollbackPlayerContextData {
    private FabricRollbackPlayerContextData() { }
    /** These values must be consumed/evolved by the Paper policy adapter before live use. */
    public record Retained(RollbackPlayerContext.Input input, Vector clientMovement, long lastJump, long eatingStart, Set<UUID> collisionExemptions) {
        public Retained { Objects.requireNonNull(input); Objects.requireNonNull(clientMovement); collisionExemptions = Set.copyOf(collisionExemptions); }
    }
    public static void apply(FabricRollbackNativePlayerState state, RollbackPlayerContext context, long initialNanos) {
        requireBootstrap(); var clocks = context.rebase(initialNanos);
        var retained = new Retained(context.input(), context.clientMovement(), clocks.lastJump(), clocks.eatingStart(), context.collisionExemptions());
        var fluids = new HashMap<TagKey<Fluid>, Double>(); var eyes = new HashSet<TagKey<Fluid>>();
        context.fluidHeights().forEach((key, value) -> fluids.put(TagKey.of(RegistryKeys.FLUID, Identifier.of(key)), value));
        context.eyeFluids().forEach(key -> eyes.add(TagKey.of(RegistryKeys.FLUID, Identifier.of(key))));
        state.use(player -> {
            var access = (EntityRollbackContextAccess) player; var pistons = access.rollback$pistons();
            if (pistons.length != 3) throw new IllegalStateException("Native piston schema changed");
            var a = context.abilities(); var target = player.getAbilities();
            target.invulnerable = a.invulnerable(); target.flying = a.flying(); target.allowFlying = a.mayFly(); target.creativeMode = a.instantBuild(); target.allowModifyWorld = a.mayBuild();
            target.setFlySpeed(a.flySpeed()); target.setWalkSpeed(a.walkSpeed());
            player.getCommandTags().clear(); player.getCommandTags().addAll(context.tags());
            access.rollback$fluidHeights().clear(); access.rollback$fluidHeights().putAll(fluids);
            access.rollback$eyeFluids().clear(); access.rollback$eyeFluids().addAll(eyes);
            pistons[0] = context.pistons().x(); pistons[1] = context.pistons().y(); pistons[2] = context.pistons().z();
            return null;
        });
        state.importedContext(retained);
    }
    public static Retained retained(FabricRollbackNativePlayerState state) {
        requireBootstrap(); var value = state.importedContext();
        if (value == null) throw new IllegalStateException("Player context has not been imported");
        return value;
    }
    public static RollbackPlayerContext capture(FabricRollbackNativePlayerState state, long capturedNanos) {
        requireBootstrap(); var retained = retained(state);
        return state.use(player -> {
            var a = player.getAbilities(); var access = (EntityRollbackContextAccess) player;
            var fluids = new TreeMap<String, Double>(); var eyes = new TreeSet<String>();
            access.rollback$fluidHeights().forEach((key, value) -> fluids.put(key.id().toString(), value));
            access.rollback$eyeFluids().forEach(key -> eyes.add(key.id().toString()));
            var pistons = access.rollback$pistons();
            return new RollbackPlayerContext(new RollbackPlayerContext.Abilities(a.invulnerable, a.flying, a.allowFlying, a.creativeMode, a.allowModifyWorld, a.getFlySpeed(), a.getWalkSpeed()),
                    retained.input(), retained.clientMovement(), Math.subtractExact(retained.lastJump(), capturedNanos),
                    retained.eatingStart() == -1 ? OptionalLong.empty() : OptionalLong.of(Math.subtractExact(retained.eatingStart(), capturedNanos)),
                    player.getCommandTags(), retained.collisionExemptions(), fluids, eyes, new Vector(pistons[0], pistons[1], pistons[2]));
        });
    }
    private static void requireBootstrap() {
        if (RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Import/capture player context before replay");
    }
}
