package com.projectkorra.projectkorra.prediction.rollback;

import com.mojang.authlib.GameProfile;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.UseEffects;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackMovementFactorsNativeTest {
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }

    @Test void nativePaperPolicyMatchesAllActualClientReferenceAxesIncludingItemAndMountRules() throws Exception {
        var text = new String(Objects.requireNonNull(getClass().getResourceAsStream("/rollback/client-movement-factors.txt")).readAllBytes(), StandardCharsets.UTF_8);
        onTickThread(() -> {
            var probe = new Policy(); int rows = 0;
            for (var line : text.lines().toList()) {
                if (line.isBlank() || line.startsWith("#")) continue;
                var fields = line.split(" "); int flags = Integer.parseInt(fields[2]);
                probe.using = (flags & 1) != 0; probe.mounted = (flags & 2) != 0; probe.slow = (flags & 4) != 0;
                probe.item.set(DataComponents.USE_EFFECTS, new UseEffects(false, true, Float.parseFloat(fields[3])));
                probe.sneak = Float.parseFloat(fields[4]); probe.player.xxa = Float.parseFloat(fields[0]); probe.player.zza = Float.parseFloat(fields[1]);
                PaperRollbackMovementFactors.apply(probe.player);
                assertEquals(Float.floatToIntBits(Float.parseFloat(fields[5])), Float.floatToIntBits(probe.player.xxa), line);
                assertEquals(Float.floatToIntBits(Float.parseFloat(fields[6])), Float.floatToIntBits(probe.player.zza), line);
                rows++;
            }
            assertEquals(576, rows); return null;
        });
    }

    @Test void realServerPlayerUsesTheNewInputPhaseOnceAndRestoresItemPoseAndMovementTogether() throws Exception {
        onTickThread(() -> {
            var queries = new PaperRollbackWorldAccessNativeTest.Queries(); var combat = new PaperRollbackDamageNativeTest.Combat();
            for (int x = -2; x <= 3; x++) for (int z = -2; z <= 4; z++) queries.block(x, 0, z, Material.STONE, "minecraft:stone");
            var world = new PaperRollbackWorldAccess(queries, combat, 4);
            var state = PaperRollbackNativePlayerState.serverPlayer(world, new GameProfile(new UUID(0, 8201), "Movement"),
                    ClientInformation.createDefault(), GameType.SURVIVAL, 82, 300_000);
            var player = (ServerPlayer) state.ownedPlayer(); player.setPos(.5, 1, .5); player.setOnGround(true);
            // Seed tracked pose as the native importer does; runtime pose transitions use the private copied event route.
            PoseSeed.set(player, Pose.CROUCHING);
            player.setShiftKeyDown(true); player.valid = true;
            player.getAttribute(Attributes.SNEAKING_SPEED).setBaseValue(.45);
            state.use(value -> {
                var item = new ItemStack(Items.APPLE, 4); item.set(DataComponents.USE_EFFECTS, new UseEffects(false, true, .35F));
                player.setItemInHand(InteractionHand.MAIN_HAND, item); player.startUsingItem(InteractionHand.MAIN_HAND); return null;
            });
            var saved = state.captureRollbackState(); var expected = RollbackMovementFactors.apply(.6F, .8F, .35F, .45F);
            step(state);
            assertEquals(expected.strafe(), player.xxa); assertEquals(expected.forward(), player.zza);
            var firstPosition = player.position(); var firstVelocity = player.getDeltaMovement();
            state.restoreRollbackState(saved);
            state.use(value -> { player.stopUsingItem(); player.setShiftKeyDown(false); PoseSeed.set(player, Pose.STANDING); return null; });
            step(state);
            var full = RollbackMovementFactors.apply(.6F, .8F, 1, 1);
            assertEquals(full.strafe(), player.xxa); assertEquals(full.forward(), player.zza);
            assertTrue(player.position().distanceToSqr(.5, 1, .5) > firstPosition.distanceToSqr(.5, 1, .5));
            state.restoreRollbackState(saved); step(state);
            assertEquals(expected.strafe(), player.xxa); assertEquals(expected.forward(), player.zza);
            assertEquals(firstPosition, player.position()); assertEquals(firstVelocity, player.getDeltaMovement());
            assertEquals(4, player.getUseItem().getCount());
            return null;
        });
    }
    private static void step(PaperRollbackNativePlayerState state) {
        try (var clock = RollbackClock.at(1000, 0, 1, 50_000_000)) {
            state.movementInput(new RollbackMovementInput(.6F, .8F, false, 0, 0)); state.tick();
        }
    }
    /** Compiled access to the protected tracked-pose key for test seed construction; never instantiated. */
    private abstract static class PoseSeed extends Player {
        private PoseSeed(net.minecraft.world.level.Level world, GameProfile profile) { super(world, profile); }
        static void set(Player player, Pose pose) { player.getEntityData().set(DATA_POSE, pose); }
    }
    private static final class Policy {
        boolean using, mounted, slow; double sneak;
        final ItemStack item = new ItemStack(Items.APPLE);
        final Player player = RollbackNativeQueryShell.create(Player.class)
                .query(Player::isUsingItem, false, args -> using)
                .query(Player::isPassenger, false, args -> mounted)
                .query(Player::isCrouching, false, args -> slow)
                .constant(Player::isVisuallyCrawling, false)
                .constant(Player::getUseItem, item)
                .query(value -> value.getAttributeValue(Attributes.SNEAKING_SPEED), 0D, args -> {
                    assertEquals(Attributes.SNEAKING_SPEED, args[0]); return sneak;
                }).instance();
    }
}
