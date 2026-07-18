package com.projectkorra.projectkorra.airbending.combo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards SummonSelf's persistent, prediction-safe BlockDisplay silhouette. */
class SummonSelfDisplayModelTest {
    @Test
    void projectileUsesSixTransparentHumanoidCuboidsInsteadOfAParticleOutline() throws IOException {
        final String source = source();
        final String create = method(source, "private void createDisplayModel",
                "private void updateDisplayModel");
        final String update = method(source, "private void updateDisplayModel",
                "private Location modelPartLocation");

        assertTrue(source.contains("private static final Material MODEL_MATERIAL = Material.WHITE_STAINED_GLASS;"),
                "the model should use a vanilla texture with transparent pixels");
        assertEquals(6, occurrences(source, "new ModelPart("),
                "head, torso, two arms, and two legs should each have one display");
        assertTrue(source.contains("private final ArrayList<BlockDisplay> modelDisplays"));
        assertTrue(create.contains("this.location.getWorld().spawn(partLocation, BlockDisplay.class"));
        assertTrue(create.contains("display.setBlock(MODEL_MATERIAL.createBlockData())")
                        && create.contains("display.setBillboard(Display.Billboard.FIXED)")
                        && create.contains("display.setShadowRadius(0.0F)")
                        && create.contains("display.setTeleportDuration(MODEL_TELEPORT_DURATION)"),
                "each body part should be an unshadowed, smoothly moving fixed BlockDisplay");
        assertTrue(update.contains("display.teleport(this.modelPartLocation(part))"),
                "the six entities must persist and move instead of being respawned every tick");
        assertFalse(source.contains("renderPlayerOutline") || source.contains("renderOutlineRing")
                        || source.contains("renderOutlineSegment"),
                "the old per-tick particle mannequin must be removed");
    }

    @Test
    void transformedCuboidsStayCenteredAndAreAlwaysCleanedUp() throws IOException {
        final String source = source();
        final String transform = method(source, "private Transformation modelPartTransformation",
                "private Vector horizontalFacing");
        final String remove = method(source, "public void remove()", "public String getName()");

        assertTrue(transform.contains("rotation.transform(translation)")
                        && transform.contains("translation.negate()"),
                "rotation must preserve each scaled block's center at its display entity");
        assertTrue(remove.contains("for (final BlockDisplay display : this.modelDisplays)")
                        && remove.contains("display.remove()")
                        && remove.contains("this.modelDisplays.clear()"),
                "impact, range, collision, and world-change removal must drain every display");
    }

    @Test
    void modelUsesInterpolatedMotionAndLeavesALightDirectionalAirTrail() throws IOException {
        final String source = source();
        final String progress = method(source, "public void progress()", "private boolean hasHitEntity");
        final String trail = method(source, "private void renderAirTrail", "private void impact");

        assertTrue(source.contains("private static final int MODEL_TELEPORT_DURATION = 3;"),
                "one-tick display teleports are still visible as snapping on the predicting client");
        assertTrue(progress.contains("this.renderAirTrail()"));
        assertTrue(trail.contains("this.velocity.clone().normalize().multiply(-")
                        && trail.contains("playAirbendingParticles"),
                "air particles should trail behind the projectile rather than obscure its front");
    }

    private static String source() throws IOException {
        Path source = Path.of("src/main/java/com/projectkorra/projectkorra/airbending/combo/SummonSelf.java");
        if (!Files.exists(source)) source = Path.of("common").resolve(source);
        assertTrue(Files.exists(source));
        return Files.readString(source);
    }

    private static String method(final String source, final String startMarker, final String endMarker) {
        final int start = source.indexOf(startMarker);
        final int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start,
                "missing method boundary " + startMarker + " -> " + endMarker);
        return source.substring(start, end);
    }

    private static int occurrences(final String source, final String needle) {
        int count = 0;
        for (int index = source.indexOf(needle); index >= 0; index = source.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }
}
