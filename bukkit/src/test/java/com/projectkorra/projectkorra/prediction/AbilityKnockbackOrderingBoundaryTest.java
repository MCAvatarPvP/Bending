package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards configured locomotion teardown from consuming the hit's velocity. */
class AbilityKnockbackOrderingBoundaryTest {
    @Test
    void cancelOnHitTeardownPrecedesTheAcceptedVelocityWrite() throws IOException {
        final String listener = read(
                "src/main/java/com/projectkorra/projectkorra/PKListener.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/PKListener.java");
        final String velocityHandler = method(listener,
                "private void onAbilityVelocity(AbilityVelocityAffectEntityEvent event)",
                "public void onInventoryClick");

        assertTrue(velocityHandler.contains("cancelAirScooterOnHit(target, event.getAbility())"),
                "velocity-first abilities must remove a configured Scooter before Bukkit commits knockback");
        assertTrue(listener.contains("cancelAirScooterOnHit(target, event.getAbility())"),
                "damage-only and damage-first abilities must retain the existing CancelOnHit path");
    }

    @Test
    void fireBlastConfiguredVelocityIsTheFinalWriteAfterDamage() throws IOException {
        final String fireBlast = read(
                "../common/src/main/java/com/projectkorra/projectkorra/firebending/FireBlast.java",
                "common/src/main/java/com/projectkorra/projectkorra/firebending/FireBlast.java");
        final String affect = method(fireBlast, "private void affect(final Entity entity)",
                "private void ignite(final Location location)");

        final int metadata = affect.indexOf("entity.setMetadata(NO_KNOCKBACK_METADATA");
        final int damage = affect.indexOf("DamageHandler.damageEntity");
        final int clearMetadata = affect.indexOf("entity.removeMetadata(NO_KNOCKBACK_METADATA");
        final int configuredVelocity = affect.indexOf("GeneralMethods.setVelocity");
        assertTrue(metadata >= 0 && metadata < damage
                        && damage < clearMetadata && clearMetadata < configuredVelocity,
                "only native damage knockback may be cancelled; FireBlast's configured velocity must be written last");
    }

    @Test
    void locomotionRemovedByAHitCannotProgressAgainFromTheTickSnapshot() throws IOException {
        final String coreAbility = read(
                "../common/src/main/java/com/projectkorra/projectkorra/ability/CoreAbility.java",
                "common/src/main/java/com/projectkorra/projectkorra/ability/CoreAbility.java");
        final String progressAll = method(coreAbility, "public static void progressAll()",
                "public static void removeAll()");

        final int removedGuard = progressAll.indexOf("if (abil.isRemoved())");
        final int progress = progressAll.indexOf("AbilityExecutionContext.run(abil, abil::progress)");
        assertTrue(removedGuard >= 0 && removedGuard < progress,
                "a locomotion ability retired by an earlier hit must not overwrite that hit later in the same snapshot");
    }

    private static String read(final String moduleRelative, final String rootRelative) throws IOException {
        Path path = Path.of(moduleRelative);
        if (!Files.exists(path)) path = Path.of(rootRelative);
        assertTrue(Files.exists(path), "missing source " + path);
        return Files.readString(path);
    }

    private static String method(final String source, final String startMarker,
                                 final String endMarker) {
        final int start = source.indexOf(startMarker);
        final int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start,
                () -> "missing method boundary " + startMarker + " -> " + endMarker);
        return source.substring(start, end);
    }
}
