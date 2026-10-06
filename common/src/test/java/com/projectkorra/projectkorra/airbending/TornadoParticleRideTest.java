package com.projectkorra.projectkorra.airbending;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards Tornado's shared particle renderer and cross-platform ride input. */
class TornadoParticleRideTest {
    @Test
    void chargeAndDeployedVisualsUseTheSameParticleRenderer() throws IOException {
        final String source = commonSource("airbending/Tornado.java");
        final String charge = method(source, "private void renderChargeAnimation",
                "private void updateChargeProgress");
        final String render = method(source, "private void renderTornadoAnimation",
                "private void renderParticleFunnel");
        final String funnel = method(source, "private void renderParticleFunnel",
                "private double particleRadiusAt");

        assertFalse(source.contains("BLINDNESS"),
                "the storm should pull victims using Tornado physics instead of applying SandStorm blindness");
        assertFalse(source.contains("ComboAbility") || source.contains("getCombination()"),
                "Tornado should be a normal bound ability, not a combo");
        assertTrue(charge.contains("this.renderParticleFunnel"),
                "charging should use the same visual funnel as the deployed tornado");
        assertTrue(render.contains("this.renderParticleFunnel"),
                "the deployed tornado should use the shared particle funnel");
        assertTrue(funnel.contains("TornadoVisuals.render("),
                "both charge stages should use the bounded SandStorm-style particle renderer");
    }

    @Test
    void rightClickTargetsOnlyTheCastersActiveTornado() throws IOException {
        final String tornado = commonSource("airbending/Tornado.java");
        final String bootstrap = commonSource("ability/activation/CoreAbilityActivationBootstrap.java");
        final String targeting = method(tornado, "private boolean isPlayerTargetingTornado",
                "private void controlRiddenTornado");
        final String activation = method(bootstrap, "private static boolean rightClickTornado",
                "private static boolean rightClickIceBullet");

        assertTrue(bootstrap.contains("registerGlobal(ClickType.RIGHT_CLICK, CoreAbilityActivationBootstrap::rightClickTornado)")
                        && bootstrap.contains("registerGlobal(ClickType.RIGHT_CLICK_BLOCK, CoreAbilityActivationBootstrap::rightClickTornado)"),
                "air and block right-clicks must share the platform-neutral activation path");
        assertTrue(activation.contains("CoreAbility.getAbility(context.getPlayer(), Tornado.class)")
                        && activation.contains("tornado.tryStartRiding()")
                        && activation.contains("context.stopProcessing()"),
                "only the player's own active instance should consume the click");
        assertTrue(targeting.contains("GeneralMethods.getDistanceFromLine")
                        && targeting.contains("distanceAlongRay")
                        && targeting.contains("GeneralMethods.isObstructed"),
                "the player must actually aim at the visible funnel");
    }

    @Test
    void riderUsesTheSlowDefensiveSpeedAndTracksHeightWithoutSnapping() throws IOException {
        final String source = commonSource("airbending/Tornado.java");
        final String config = commonSource("configuration/ConfigManager.java");
        final String control = method(source, "private void controlRiddenTornado",
                "private Location getRideTargetLocation");
        final String movement = method(source, "private void updateRiderMotion",
                "private void renderChargeAnimation");
        final String remove = method(source, "public void remove()",
                "public boolean isSneakAbility()");

        assertTrue(config.contains("Abilities.Air.Tornado.Ride.Speed\", 0.22"),
                "the default Tornado ride should move at its slow defensive speed");
        assertTrue(control.contains("facing.multiply(this.rideSpeed)")
                        && !control.contains("Math.max(this.speed, this.rideSpeed)"),
                "ride steering should use its own speed independently of throwing speed");
        assertTrue(movement.contains("this.player.getVelocity().getY() * 0.4")
                        && movement.contains("correction.getY() * this.rideVerticalSmoothing")
                        && movement.contains("this.rideMaxVerticalSpeed"),
                "height changes should use a damped, capped velocity instead of teleports");
        assertTrue(source.contains("this.flightHandler.createInstance(this.player, RIDE_FLIGHT_ID)")
                        && remove.contains("this.flightHandler.removeInstance(this.player, RIDE_FLIGHT_ID)"),
                "the shared flight lease must cover the complete ride lifecycle");
    }

    @Test
    void legacyComboSettingsArePromotedWithoutKeepingTheCombination() throws IOException {
        final String config = commonSource("configuration/ConfigManager.java");
        final String migration = method(config, "private static void migrateLegacyTornadoConfig",
                "private static boolean moveSection");

        assertTrue(config.contains("migrateLegacyTornadoConfig();"),
                "configuration migration must run before defaults are registered");
        assertTrue(migration.contains("Abilities.Air.Twister\", \"Abilities.Air.Tornado")
                        && migration.contains("Set.of(\"Combination\")")
                        && migration.contains("languageConfig.removeTree(\"Abilities.Air.Combo.Twister\")")
                        && migration.contains("TORNADO_INSTRUCTIONS"),
                "existing Twister tuning should follow Tornado while combo input and instructions are discarded");
    }

    private static String commonSource(final String relative) throws IOException {
        Path source = Path.of("src/main/java/com/projectkorra/projectkorra").resolve(relative);
        if (!Files.exists(source)) {
            source = Path.of("common/src/main/java/com/projectkorra/projectkorra").resolve(relative);
        }
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
}
