package com.projectkorra.projectkorra.airbending;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards Tornado's continuous particle funnel and cross-platform ride input. */
class TornadoParticleRideTest {
    @Test
    void chargeAndDeployedVisualsUseTheSameSmoothTaperedFunnel() throws IOException {
        final String source = commonSource("airbending/Tornado.java");
        final String charge = method(source, "private void renderChargeAnimation",
                "private void updateChargeProgress");
        final String render = method(source, "private void renderTornadoAnimation",
                "private void renderParticleFunnel");
        final String funnel = method(source, "private void renderParticleFunnel",
                "private void renderParticleRing");
        final String radius = method(source, "private double particleRadiusAt",
                "private void spawnFunnelParticle");

        assertFalse(source.contains("BlockDisplay") || source.contains("Transformation"),
                "Tornado should be entirely particle-rendered");
        assertFalse(source.contains("ComboAbility") || source.contains("getCombination()"),
                "Tornado should be a normal bound ability, not a combo");
        assertTrue(charge.contains("this.renderParticleFunnel")
                        && charge.contains("formingHeight") && charge.contains("formingRadius"),
                "charging should grow the same funnel instead of emitting detached wisps");
        assertTrue(render.contains("this.renderParticleFunnel(this.currentLoc")
                        && render.contains("this.vortexAngle +="),
                "the deployed funnel should rotate around its grounded center");
        assertTrue(funnel.contains("verticalSamples")
                        && funnel.contains("PARTICLE_STREAMS")
                        && funnel.contains("PARTICLE_INNER_STREAMS"),
                "normalized vertical samples and inner/outer helices should create a filled silhouette");
        assertTrue(radius.contains("Math.pow(progress") && radius.contains("maximumRadius"),
                "the particle envelope should widen smoothly from base to top");
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
    void riderIsFasterThanScooterAndTracksHeightWithoutSnapping() throws IOException {
        final String source = commonSource("airbending/Tornado.java");
        final String config = commonSource("configuration/ConfigManager.java");
        final String control = method(source, "private void controlRiddenTornado",
                "private Location getRideTargetLocation");
        final String movement = method(source, "private void updateRiderMotion",
                "private void renderChargeAnimation");
        final String remove = method(source, "public void remove()",
                "public boolean isSneakAbility()");

        assertTrue(config.contains("Abilities.Air.Tornado.Ride.Speed\", 0.8")
                        && config.contains("Abilities.Air.AirScooter.Speed\", 0.675"),
                "the default Tornado ride should be slightly faster than AirScooter");
        assertTrue(control.contains("Math.max(this.speed, this.rideSpeed)"),
                "ride steering should use the configured faster speed");
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
