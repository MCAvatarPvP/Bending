package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackBorderData;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateGraph;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.border.WorldBorder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackBorderTest {
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }
    static RollbackBorderData seed() throws Exception {
        try (var source = Objects.requireNonNull(FabricRollbackBorderTest.class.getResourceAsStream("/rollback/border.base64"))) {
            return RollbackBorderData.decode(Base64.getMimeDecoder().decode(source.readAllBytes()));
        }
    }
    @Test void importedResizeMatchesTheNativePaperReferenceThroughCompletion() throws Exception {
        var data = seed(); var border = new FabricRollbackBorder(data); assertEquals(data, border.data());
        var rows = new StringBuilder();
        for (int tick = 0; tick < 12; tick++) {
            rows.append(row(border, tick)); border.advanceTick(tick);
            var after = border.data(); border.advanceTick(tick); assertEquals(after, border.data());
        }
        try (var source = Objects.requireNonNull(getClass().getResourceAsStream("/rollback/border-frames.txt"))) {
            assertEquals(new String(source.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"), rows.toString());
        }
    }
    @Test void rewindRestoresResizeGeometrySettingsAndLogicalTick() throws Exception {
        var border = new FabricRollbackBorder(seed()); border.advanceTick(1);
        var saved = new RollbackStateGraph(value -> false, field -> true, 10_000).capture(List.of(border), List.of());
        border.advanceTick(2); var next = border.data(); var shape = border.asVoxelShape();
        border.setCenter(-4, 9); border.setMaxRadius(100); border.setSize(90); border.setSafeZone(1); border.setDamagePerBlock(.9); border.setWarningTime(8); border.setWarningBlocks(7);
        saved.restore(); border.advanceTick(2); assertEquals(next, border.data());
        assertFalse(VoxelShapes.matchesAnywhere(shape, border.asVoxelShape(), BooleanBiFunction.NOT_SAME));
        assertThrows(IllegalStateException.class, () -> border.advanceTick(1)); assertThrows(IllegalStateException.class, border::tick);
        var other = new FabricRollbackBorder(seed()); assertThrows(IllegalArgumentException.class, () -> border.restoreRollbackState(other.captureRollbackState()));
        assertThrows(UnsupportedOperationException.class, () -> border.addListener(null));
        var failure = assertThrows(CompletionException.class, () -> CompletableFuture.supplyAsync(border::getSize).join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }
    @Test void serviceClockAdvancesAndRewindsTheBorderWithTheWorld() throws Exception {
        var callbacks = new FabricRollbackWorldServicesTest.Callbacks();
        var services = FabricRollbackWorldServicesTest.services(FabricRollbackWorldServicesTest.settings(), callbacks);
        callbacks.border.interpolateSize(41, 7, 11, 20);
        var snapshot = new RollbackStateGraph(value -> false, field -> true, 100_000).capture(List.of(services), List.of());
        services.advanceTick(); var after = callbacks.border.data(); assertEquals(10, after.transition().remaining());
        snapshot.restore(); assertEquals(20, services.time()); services.advanceTick(); assertEquals(after, callbacks.border.data());
    }
    static String row(WorldBorder border, int tick) {
        var row = new StringBuilder().append(tick).append(' ').append(Double.toHexString(border.getSize())).append(' ').append(border.getSizeLerpTime())
                .append(' ').append(Double.toHexString(border.getShrinkingSpeed())).append(' ').append(border.getStage());
        for (float partial : new float[]{0, .25F, 1}) for (double value : new double[]{border.getBoundWest(partial), border.getBoundNorth(partial), border.getBoundEast(partial), border.getBoundSouth(partial)}) row.append(' ').append(Double.toHexString(value));
        row.append(' ').append(border.contains(8, 0)).append(' ').append(border.contains(new Box(-1, 0, -1, 1, 1, 1)))
                .append(' ').append(Double.toHexString(border.getDistanceInsideBorder(8, 0))).append('\n'); return row.toString();
    }
}
