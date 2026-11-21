package com.projectkorra.projectkorra.prediction.rollback;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackBorderNativeTest {
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }
    @Test void capturedMovingBorderMatchesNativeTicksAndProducesTheClientReference() throws Exception {
        onTickThread(() -> {
            int realTick = MinecraftServer.currentTick;
            try {
                var source = source();
                for (int i = 0; i < 3; i++) { MinecraftServer.currentTick++; source.tick(); }
                var data = PaperRollbackBorder.capture(source); var actual = new PaperRollbackBorder(data);
                String encoded = Base64.getEncoder().encodeToString(data.encode());
                try (var input = getClass().getResourceAsStream("/rollback/border.base64")) {
                    assertNotNull(input, "Current Paper border seed: " + encoded);
                    assertArrayEquals(Base64.getMimeDecoder().decode(input.readAllBytes()), data.encode());
                }
                var rows = new StringBuilder();
                for (int tick = 0; tick < 12; tick++) {
                    assertEquals(row(source, tick), row(actual, tick)); rows.append(row(source, tick));
                    assertFalse(Shapes.joinIsNotEmpty(source.getCollisionShape(), actual.getCollisionShape(), BooleanOp.NOT_SAME));
                    MinecraftServer.currentTick++; source.tick(); actual.advanceTick(tick);
                    var after = actual.data(); actual.advanceTick(tick); assertEquals(after, actual.data(), "A repeated logical tick cannot advance twice");
                }
                try (var input = getClass().getResourceAsStream("/rollback/border-frames.txt")) {
                    assertNotNull(input, "Current Paper border frames:\n" + rows);
                    assertEquals(new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"), rows.toString());
                }
                return null;
            } finally { MinecraftServer.currentTick = realTick; }
        });
    }
    @Test void rewindRestoresResizeGeometrySettingsAndLogicalTick() throws Exception {
        onTickThread(() -> {
            var border = new PaperRollbackBorder(PaperRollbackBorder.capture(source()));
            border.advanceTick(1); var saved = new RollbackStateGraph(value -> false, field -> true, 10_000).capture(List.of(border), List.of());
            border.advanceTick(2); var next = border.data(); var shape = border.getCollisionShape();
            assertEquals(9, next.transition().remaining(), "Both replay ticks must run in the same real server tick");
            border.setCenter(-4, 9); border.setAbsoluteMaxSize(100); border.setSize(90); border.setSafeZone(1); border.setDamagePerBlock(.9); border.setWarningTime(8); border.setWarningBlocks(7);
            saved.restore(); border.advanceTick(2); assertEquals(next, border.data());
            assertFalse(Shapes.joinIsNotEmpty(shape, border.getCollisionShape(), BooleanOp.NOT_SAME));
            assertThrows(IllegalStateException.class, () -> border.advanceTick(1)); assertThrows(IllegalStateException.class, border::tick);
            var other = new PaperRollbackBorder(PaperRollbackBorder.capture(new WorldBorder()));
            assertThrows(IllegalArgumentException.class, () -> border.restoreRollbackState(other.captureRollbackState()));
            assertThrows(UnsupportedOperationException.class, () -> border.addListener(null));
            var failure = assertThrows(CompletionException.class, () -> CompletableFuture.supplyAsync(border::getSize).join());
            assertInstanceOf(IllegalStateException.class, failure.getCause()); return null;
        });
    }
    @Test void serviceClockAdvancesAndRewindsTheBorderWithTheWorld() throws Exception {
        onTickThread(() -> {
            var callbacks = new PaperRollbackWorldServicesNativeTest.Callbacks();
            var services = PaperRollbackWorldServicesNativeTest.services(PaperRollbackWorldServicesNativeTest.settings(), callbacks);
            callbacks.border.lerpSizeBetween(41, 7, 11, 20);
            var snapshot = new RollbackStateGraph(value -> false, field -> true, 100_000).capture(List.of(services), List.of());
            services.advanceTick(); var after = callbacks.border.data(); assertEquals(10, after.transition().remaining());
            snapshot.restore(); assertEquals(20, services.time()); services.advanceTick(); assertEquals(after, callbacks.border.data()); return null;
        });
    }
    private static WorldBorder source() {
        var source = new WorldBorder(); source.setCenter(2.5, -3.25); source.setAbsoluteMaxSize(9);
        source.setDamagePerBlock(.3); source.setSafeZone(2); source.setWarningBlocks(4); source.setWarningTime(50); source.lerpSizeBetween(41, 7, 11, 100);
        return source;
    }
    static String row(WorldBorder border, int tick) {
        var row = new StringBuilder().append(tick).append(' ').append(Double.toHexString(border.getSize())).append(' ').append(border.getLerpTime())
                .append(' ').append(Double.toHexString(border.getLerpSpeed())).append(' ').append(border.getStatus());
        for (float partial : new float[]{0, .25F, 1}) for (double value : new double[]{border.getMinX(partial), border.getMinZ(partial), border.getMaxX(partial), border.getMaxZ(partial)}) row.append(' ').append(Double.toHexString(value));
        row.append(' ').append(border.isWithinBounds(8, 0)).append(' ').append(border.isWithinBounds(new AABB(-1, 0, -1, 1, 1, 1)))
                .append(' ').append(Double.toHexString(border.getDistanceToBorder(8, 0))).append('\n'); return row.toString();
    }
}
