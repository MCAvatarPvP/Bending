package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerValues.Vector;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackPlayerFields.*;

/** Native controls and contact-cache import, with time rebasing staged before any writes. */
public final class PaperRollbackPlayerContextData {
    private static final Field<Long> LAST_JUMP = new Field<>(LivingEntity.class, "lastJumpTime", long.class);
    private static final Field<Long> EAT_START = new Field<>(LivingEntity.class, "eatStartTime", long.class);
    private static final Field<Object2DoubleMap> FLUIDS = new Field<>(Entity.class, "fluidHeight", Object2DoubleMap.class);
    private static final Field<Set> EYES = new Field<>(Entity.class, "fluidOnEyes", Set.class);
    private static final Field<double[]> PISTONS = new Field<>(Entity.class, "pistonDeltas", double[].class);
    private PaperRollbackPlayerContextData() { }

    @SuppressWarnings("unchecked")
    static RollbackPlayerContext capture(ServerPlayer source, long capturedNanos) {
        var a = source.getAbilities(); var input = source.getLastClientInput(); var movement = source.getKnownMovement();
        var fluids = new TreeMap<String, Double>(); var eyes = new TreeSet<String>();
        ((Object2DoubleMap<TagKey<Fluid>>) FLUIDS.get(source)).forEach((key, value) -> fluids.put(key.location().toString(), value));
        ((Set<TagKey<Fluid>>) EYES.get(source)).forEach(key -> eyes.add(key.location().toString()));
        var pistons = PISTONS.get(source); if (pistons.length != 3) throw new IllegalStateException("Native piston schema changed");
        long eating = EAT_START.get(source);
        return new RollbackPlayerContext(new RollbackPlayerContext.Abilities(a.invulnerable, a.flying, a.mayfly, a.instabuild, a.mayBuild, a.flyingSpeed, a.walkingSpeed),
                new RollbackPlayerContext.Input(input.forward(), input.backward(), input.left(), input.right(), input.jump(), input.shift(), input.sprint()),
                new Vector(movement.x, movement.y, movement.z), Math.subtractExact(LAST_JUMP.get(source), capturedNanos),
                eating == -1 ? OptionalLong.empty() : OptionalLong.of(Math.subtractExact(eating, capturedNanos)), source.getTags(), source.collidableExemptions,
                fluids, eyes, new Vector(pistons[0], pistons[1], pistons[2]));
    }
    public static void apply(PaperRollbackNativePlayerState state, RollbackPlayerContext context, long initialNanos) {
        if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Import context at the player bootstrap boundary");
        state.use(value -> {
            if (!(value instanceof ServerPlayer player)) throw new IllegalArgumentException("Context requires a private server player");
            applyTo(player, context, initialNanos); return null;
        });
    }
    @SuppressWarnings("unchecked")
    static void applyTo(ServerPlayer player, RollbackPlayerContext context, long initialNanos) {
        prepare(player, context, initialNanos).run();
    }

    static Runnable prepare(ServerPlayer player, RollbackPlayerContext context, long initialNanos) {
        var clocks = context.rebase(initialNanos);
        var fluids = new HashMap<TagKey<Fluid>, Double>(); var eyes = new HashSet<TagKey<Fluid>>();
        context.fluidHeights().forEach((key, value) -> fluids.put(TagKey.create(Registries.FLUID, Identifier.parse(key)), value));
        context.eyeFluids().forEach(key -> eyes.add(TagKey.create(Registries.FLUID, Identifier.parse(key))));
        var a = context.abilities(); var abilities = new Abilities.Packed(a.invulnerable(), a.flying(), a.mayFly(), a.instantBuild(), a.mayBuild(), a.flySpeed(), a.walkSpeed());
        var i = context.input(); var input = new Input(i.forward(), i.backward(), i.left(), i.right(), i.jump(), i.shift(), i.sprint());
        var m = context.clientMovement(); var movement = new Vec3(m.x(), m.y(), m.z());
        var pistons = PISTONS.get(player); if (pistons.length != 3) throw new IllegalStateException("Native piston schema changed");
        return () -> {
            LAST_JUMP.set(player, clocks.lastJump()); EAT_START.set(player, clocks.eatingStart());
            player.getAbilities().apply(abilities); player.setLastClientInput(input); player.setKnownMovement(movement);
            player.getTags().clear(); player.getTags().addAll(context.tags());
            player.collidableExemptions.clear(); player.collidableExemptions.addAll(context.collisionExemptions());
            FLUIDS.get(player).clear(); FLUIDS.get(player).putAll(fluids); EYES.get(player).clear(); EYES.get(player).addAll(eyes);
            pistons[0] = context.pistons().x(); pistons[1] = context.pistons().y(); pistons[2] = context.pistons().z();
        };
    }
}
