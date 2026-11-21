package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackVerticalInputNativeTest {
    private static final UUID A = new UUID(0, 7001), B = new UUID(0, 7002);
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }

    @Test void paperPolicyAndVelocityWritesMatchAll384ActualClientReferenceCases() throws Exception {
        String reference;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/rollback/client-vertical-input.txt"))) {
            reference = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        onTickThread(() -> {
            var probe = new Probe(); int rows = 0;
            for (var line : reference.lines().toList()) {
                if (line.isBlank() || line.startsWith("#")) continue;
                var fields = line.split(" "); probe.flags = Integer.parseInt(fields[0]); probe.abilities.flying = (probe.flags & 16) != 0;
                probe.abilities.flyingSpeed = Float.parseFloat(fields[1]); probe.velocity = new Vec3(-0D, Double.parseDouble(fields[2]), .25); probe.writes = 0;
                PaperRollbackClientMovement.vertical(probe.player);
                assertEquals(new Vec3(Double.parseDouble(fields[3]), Double.parseDouble(fields[4]), Double.parseDouble(fields[5])), probe.velocity, line);
                assertEquals(Integer.parseInt(fields[6]), probe.writes, line); rows++;
            }
            assertEquals(384, rows); return null;
        });
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 1})
    void nativeServerPlayerFlightUsesVerticalKeysAndRewindsPermissionSpeedAndMomentum(int direction) throws Exception {
        onTickThread(() -> {
            var scene = new PaperRollbackPrivateQueriesNativeTest.Scene(); var state = scene.players.get(A); var player = (ServerPlayer) state.ownedPlayer();
            scene.players.get(B).ownedPlayer().setPos(3, 1, 3);
            player.setPos(.5, 2, .5); player.setDeltaMovement(Vec3.ZERO); player.setOnGround(false);
            player.getAbilities().mayfly = true; player.getAbilities().flying = true; player.getAbilities().flyingSpeed = .15F;
            player.setShiftKeyDown(direction < 0);
            var saved = new RollbackStateGraph(value -> false, field -> true, 800_000).capture(List.of(scene.queries), List.of());
            step(state, direction > 0); var position = player.position(); var velocity = player.getDeltaMovement();
            assertEquals(Integer.signum(direction), Double.compare(position.y, 2));
            saved.restore(); player.getAbilities().mayfly = false; player.getAbilities().flying = false; player.getAbilities().flyingSpeed = .01F;
            step(state, direction > 0); assertNotEquals(velocity, player.getDeltaMovement());
            saved.restore(); step(state, direction > 0);
            assertTrue(player.getAbilities().mayfly); assertTrue(player.getAbilities().flying); assertEquals(.15F, player.getAbilities().getFlyingSpeed());
            assertEquals(position, player.position()); assertEquals(velocity, player.getDeltaMovement()); assertNull(org.bukkit.Bukkit.getServer());
            return null;
        });
    }
    private static void step(PaperRollbackNativePlayerState state, boolean jump) {
        try (var clock = RollbackClock.at(1000, 0, 1, 50_000_000)) {
            state.movementInput(new RollbackMovementInput(0, 0, jump, 0, 0)); state.tick();
        }
    }
    @Test void nativeWaterContactAppliesSneakDescentAndRewindsItsMomentum() throws Exception {
        onTickThread(() -> {
            var scene = new PaperRollbackPrivateQueriesNativeTest.Scene(); var state = scene.players.get(A); var player = (ServerPlayer) state.ownedPlayer();
            scene.players.get(B).ownedPlayer().setPos(3, 1, 3);
            for (int y = 1; y <= 3; y++) scene.block(new BlockPos(0, y, 0), Material.WATER, "minecraft:water[level=0]");
            player.setPos(.5, 2, .5); player.setDeltaMovement(Vec3.ZERO); player.setOnGround(false);
            player.getAbilities().mayfly = false; player.getAbilities().flying = false; player.setShiftKeyDown(false); player.setSprinting(false);
            var saved = new RollbackStateGraph(value -> false, field -> true, 800_000).capture(List.of(scene.queries), List.of());
            step(state, false); assertTrue(player.isInWater()); double ordinaryY = player.getY();
            saved.restore(); player.setShiftKeyDown(true); step(state, false);
            var position = player.position(); var velocity = player.getDeltaMovement(); assertTrue(position.y < ordinaryY);
            saved.restore(); player.setShiftKeyDown(true); step(state, false);
            assertEquals(position, player.position()); assertEquals(velocity, player.getDeltaMovement()); return null;
        });
    }
    private static final class Probe {
        final Abilities abilities = new Abilities(); int flags, writes; Vec3 velocity;
        final Player player = RollbackNativeQueryShell.create(Player.class)
                .constant(Player::getAbilities, abilities)
                .query(Player::isInWater, false, args -> (flags & 1) != 0)
                .query(Player::isAffectedByFluids, false, args -> (flags & 2) != 0)
                .query(Player::isShiftKeyDown, false, args -> (flags & 4) != 0)
                .query(Player::isJumping, false, args -> (flags & 8) != 0)
                .query(Player::getDeltaMovement, Vec3.ZERO, args -> velocity)
                .outputQuery(value -> value.setDeltaMovement(Vec3.ZERO), args -> { velocity = (Vec3) args[0]; writes++; }).instance();
    }
}
