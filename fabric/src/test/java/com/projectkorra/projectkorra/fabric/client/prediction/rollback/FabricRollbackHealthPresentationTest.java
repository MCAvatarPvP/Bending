package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackPlayerRendererTest.*;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackHealthPresentationTest {
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }
    @Test void transformedHudReadsDetachedCorrectedVitalsAndRestoresNativeReads() throws Exception {
        var simulation = roster(); var live = roster();
        var player = simulation.players().get(A).ownedPlayer(); var visible = live.players().get(A).ownedPlayer();
        var hud = new ObjenesisStd(false).newInstance(InGameHud.class); // Loads and validates all actual HUD injection targets.
        float liveHealth = visible.getHealth(); var initial = views(simulation);
        player.getAttributeInstance(EntityAttributes.MAX_ABSORPTION).setBaseValue(10);
        player.setHealth(7); player.setAbsorptionAmount(4); player.timeUntilRegen = 13;
        player.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(30);
        var damaged = views(simulation);
        player.setHealth(19); player.setAbsorptionAmount(0); player.timeUntilRegen = 0;
        var current = new AtomicBoolean(true);
        try (var owner = new FabricRollbackPlayerRenderer(UUID.randomUUID(), live.world().world(), Set.of(A, B), current::get, () -> Vec3d.ZERO, pos -> 0)) {
            assertNull(FabricRollbackPlayerRenderer.health(visible));
            owner.publish(1, 0, damaged);
            assertEquals(7F, call(hud, "health", args -> visible.getHealth(), visible));
            assertEquals(30D, call(hud, "maximum", args -> -1D, visible, EntityAttributes.MAX_HEALTH));
            assertEquals(4F, call(hud, "absorption", args -> -1F, visible));
            assertEquals(13, call(hud, "regenerationDelay", args -> -1, visible));
            assertEquals(damaged.get(A).health().armor(), call(hud, "armor", args -> -1, visible));
            assertEquals(damaged.get(A).health().regenerating(), call(hud, "regenerating", args -> true, visible, StatusEffects.REGENERATION));
            assertEquals(42D, call(hud, "maximum", args -> 42D, visible, EntityAttributes.MOVEMENT_SPEED));
            assertEquals(true, call(hud, "regenerating", args -> true, visible, StatusEffects.POISON));
            assertNull(FabricRollbackPlayerRenderer.health(player), "Private/foreign world cannot consume live HUD lease");
            assertEquals(liveHealth, visible.getHealth());
            owner.publish(1, 1, initial);
            assertEquals(initial.get(A).health().value(), call(hud, "health", args -> -1F, visible));
            assertEquals(initial.get(A).health().absorption(), call(hud, "absorption", args -> -1F, visible));
            current.set(false);
            assertEquals(liveHealth, call(hud, "health", args -> visible.getHealth(), visible));
            assertNull(FabricRollbackPlayerRenderer.health(visible));
        }
        assertEquals(liveHealth, visible.getHealth());
        assertEquals(liveHealth, call(hud, "health", args -> visible.getHealth(), visible));
    }
    private static Object call(InGameHud hud, String suffix, Operation<?> original, Object... args) throws Exception {
        var method = Arrays.stream(InGameHud.class.getDeclaredMethods()).filter(value -> value.getName().endsWith("projectkorra$" + suffix)).findFirst().orElseThrow();
        method.setAccessible(true); var all = Arrays.copyOf(args, args.length + 1); all[args.length] = original;
        return method.invoke(hud, all);
    }
}
