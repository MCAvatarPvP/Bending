package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the "Client-side visuals" setting a render-only switch: it hides the
 * client's own predicted visuals and asks Paper for its authoritative copies,
 * while gameplay prediction keeps running unchanged.
 */
class ClientVisualsSettingBoundaryTest {
    @Test
    void visualsSettingPersistsAndIsExposedNextToClientSideBending() throws IOException {
        final String config = source("src/main/java/com/projectkorra/projectkorra/fabric/client/config/ClientBendingConfig.java");
        final String screen = source("src/main/java/com/projectkorra/projectkorra/fabric/client/config/ProjectKorraConfigScreen.java");
        final String lang = source("src/main/resources/assets/projectkorra/lang/en_us.json");

        assertTrue(config.contains("\"clientSideVisuals\"")
                        && config.contains("private static boolean visualsEnabled = true")
                        && config.contains("readBoolean(properties, CLIENT_SIDE_VISUALS, visualsEnabled)")
                        && config.contains("properties.setProperty(CLIENT_SIDE_VISUALS, Boolean.toString(visualsEnabled))"),
                "client-side visuals must remain enabled by default and persist next to clientSideBending");
        assertTrue(config.contains("public static synchronized boolean isVisualsEnabled()")
                        && config.contains("public static synchronized void setVisualsEnabled(final boolean value)"),
                "the visuals setting needs its own accessor pair");
        assertTrue(screen.contains("ClientBendingConfig.setVisualsEnabled(enabled)")
                        && screen.contains("PredictionClient.onClientSideVisualsSettingChanged(enabled)")
                        && screen.contains("\"projectkorra.config.client_side_visuals\""),
                "the Mod Menu screen must expose the visuals toggle and apply it live");
        assertTrue(screen.contains("visualsButton.active = ClientBendingConfig.isEnabled()"),
                "visuals only matter while client-side bending is on");
        assertTrue(lang.contains("\"projectkorra.config.client_side_visuals\"")
                        && lang.contains("\"projectkorra.config.client_side_visuals.description.1\"")
                        && lang.contains("\"projectkorra.config.client_side_visuals.description.2\""),
                "every visuals translation key used by the screen must exist");
    }

    @Test
    void helloAdvertisesServerVisualsOnlyWhenVisualsAreOff() throws IOException {
        final String prediction = source("src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java");

        assertTrue(prediction.contains("CAPABILITY_SERVER_VISUALS = 16"),
                "the server-visuals capability must use the bit Paper reads");
        assertTrue(prediction.contains(
                        "return CAPABILITIES | (ClientBendingConfig.isVisualsEnabled() ? 0 : CAPABILITY_SERVER_VISUALS);"),
                "the hello must carry CAPABILITY_SERVER_VISUALS only when client-side visuals are off");
        assertFalse(prediction.contains("CAPABILITIES))") || prediction.contains("clientTick, CAPABILITIES"),
                "no hello may bypass the settings-aware capability mask");
        assertTrue(prediction.contains("clientTick, helloCapabilities()))")
                        && prediction.contains("final int capabilities = helloCapabilities();")
                        && prediction.contains("PROTOCOL_VERSION, clientTick, capabilities))"),
                "the join hello and the retry hello must both use helloCapabilities()");
    }

    @Test
    void togglingVisualsReHandshakesWithoutDisablingPrediction() throws IOException {
        final String prediction = source("src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java");
        final String toggle = method(prediction,
                "public static void onClientSideVisualsSettingChanged(final boolean enabled)",
                "public static String diagnosticStatus");

        assertTrue(toggle.indexOf("ClientBlockVisualOverlay.onVisualsSettingChanged()")
                        < toggle.indexOf("if (!initialized || !ClientBendingConfig.isEnabled()) return;"),
                "terrain must be rebuilt for the new setting even without a live session");
        assertTrue(toggle.contains("prediction.reset(client)")
                        && toggle.contains("new PredictionPayloads.ClientHello(")
                        && toggle.contains("helloCapabilities()")
                        && toggle.indexOf("prediction.reset(client)")
                        < toggle.indexOf("new PredictionPayloads.ClientHello("),
                "Paper fixes capabilities per session, so a toggle must reset and send a fresh hello");
        assertTrue(toggle.contains("prediction.lastHelloTick = prediction.clientTick - 20L"),
                "a hello that cannot be sent now must be retried on the next tick");
        assertFalse(toggle.contains("ClientDisabled") || toggle.contains("disableHandshakePending"),
                "turning visuals off must not turn gameplay prediction off");
    }

    @Test
    void predictedBlocksAreGatedOnlyOnRenderPaths() throws IOException {
        final String overlay = source("src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientBlockVisualOverlay.java");
        final String vanilla = source("src/main/java/com/projectkorra/projectkorra/fabric/mixin/client/ChunkRendererRegionPredictionMixin.java");
        final String sodium = source("src/main/java/com/projectkorra/projectkorra/fabric/mixin/client/SodiumLevelSlicePredictionMixin.java");
        final String vulkan = source("src/main/java/com/projectkorra/projectkorra/fabric/mixin/client/VulkanRenderRegionPredictionMixin.java");
        final String desync = source("src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionDesyncRenderer.java");
        final String collision = source("src/main/java/com/projectkorra/projectkorra/fabric/mixin/client/BlockCollisionSpliteratorPredictionMixin.java");

        final String logical = method(overlay, "public BlockState compose(", "public BlockState composeTerrain(");
        final String terrain = method(overlay, "public BlockState composeTerrain(", "public void set(");
        final String foreground = method(overlay, "public List<VisualBlock> foregroundBlocks(", "final long now");

        assertTrue(overlay.contains("private static volatile boolean visualsEnabled"),
                "terrain workers read the setting through a volatile cache");
        assertTrue(terrain.contains("if (!visualsEnabled || world == null || pos == null) return authoritativeState;"),
                "with visuals off the terrain mesh must show the plain backing ClientWorld");
        assertTrue(foreground.contains("!visualsEnabled"),
                "with visuals off the per-frame foreground renderer must draw nothing");
        assertFalse(logical.contains("visualsEnabled"),
                "the logical composition used by gameplay and collision must ignore the visuals setting");
        assertFalse(collision.contains("visualsEnabled") || collision.contains("isVisualsEnabled"),
                "predicted movement collision is gameplay and must not depend on the visuals setting");
        assertTrue(method(overlay, "public static void onVisualsSettingChanged()", "private void rebuildAll()")
                        .contains("overlay.rebuildAll()"),
                "a settings change must rebuild every section an overlay entry touches");
        assertTrue(vanilla.contains("ClientBlockVisualOverlay.visualsEnabled()")
                        && sodium.contains("if (!ClientBlockVisualOverlay.visualsEnabled()) return;")
                        && vulkan.contains("if (!ClientBlockVisualOverlay.visualsEnabled()) return;"),
                "vanilla, Sodium and VulkanMod terrain composition must all honor the visuals setting");
        assertTrue(desync.contains("if (!ClientBlockVisualOverlay.visualsEnabled()) return;"),
                "the predicted desync outline is a visual too");
    }

    @Test
    void predictedParticlesSoundsAndEntitiesAreGated() throws IOException {
        final String platform = source("src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricPredictionMC.java");
        final String sounds = source("src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/effect/ClientSoundAuthority.java");
        final String network = source("src/main/java/com/projectkorra/projectkorra/fabric/mixin/client/ClientPlayNetworkHandlerPredictionMixin.java");
        final String renderer = source("src/main/java/com/projectkorra/projectkorra/fabric/mixin/client/EntityRendererPredictionDisplayMixin.java");
        final String entities = source("src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/entity/ClientEntityReconciliation.java");

        assertTrue(platform.contains("if (!ClientBendingConfig.isVisualsEnabled()) return;")
                        && platform.contains("|| !ClientBendingConfig.isVisualsEnabled()) return;"),
                "local predicted particles and sounds must not be emitted with visuals off");
        assertTrue(network.contains("isOnThread() && ClientBendingConfig.isVisualsEnabled()"),
                "Paper's sound echo is the only copy with visuals off and must never be suppressed");
        assertTrue(sounds.contains("this.predicted.clear();")
                        && sounds.contains("return false;"),
                "the sound ledger must not match an echo against a sound that was never played");
        assertTrue(renderer.contains("PredictedEntityIdAllocator.isPredictedId(entity.getId())")
                        && renderer.contains("ExactPredictionRuntime.isPredictedOwned(entity)"),
                "predicted entities keep simulating but are not drawn with visuals off");
        assertTrue(entities.contains("final boolean conceal = ClientBendingConfig.isVisualsEnabled();")
                        && entities.contains("if (conceal) hiddenTempFallingEntities.add(receipt.serverEntityId());")
                        && entities.contains("visibleAuthorityPairs.put(packet.getEntityId(),"),
                "Paper's entities must spawn visibly with visuals off while removal still retires the local copy");
    }

    private static String method(final String source, final String start, final String end) {
        final int from = source.indexOf(start);
        assertTrue(from >= 0, "missing start: " + start);
        final int to = source.indexOf(end, from);
        assertTrue(to > from, "missing end: " + end);
        return source.substring(from, to);
    }

    private static String source(final String relative) throws IOException {
        Path path = Path.of(relative);
        if (!Files.exists(path)) path = Path.of("fabric").resolve(relative);
        assertTrue(Files.exists(path), "missing source: " + path);
        return com.projectkorra.projectkorra.testutil.PredictionSourceBundle.read(path);
    }
}
