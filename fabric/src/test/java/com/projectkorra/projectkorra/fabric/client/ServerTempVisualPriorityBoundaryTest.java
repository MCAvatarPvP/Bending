package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards fail-open concealment and render priority for authoritative TempBlocks. */
class ServerTempVisualPriorityBoundaryTest {
    @Test
    void aClosedLocalPairCannotRemainLifecycleLinkedIndefinitely() throws IOException {
        final String authority = authority();
        final String semanticPair = method(authority, "private boolean hasSemanticPair",
                "private static EffectKey effectKey");
        final String pairing = method(authority, "private void tryMatchLocal",
                "private LocalLayer detachLocalLayer");
        final String expiry = method(authority, "private void expireUnconfirmedLayers",
                "private boolean preserveLocalAuthority");
        final String eligibility = method(authority, "private boolean eligibleForPair",
                "private boolean preserveLocalAuthority");

        assertTrue(authority.contains("private static final int CLOSED_PAIR_GRACE_TICKS = 20"),
                "a locally closed semantic pair needs an explicit, short reconciliation deadline");
        assertTrue(semanticPair.contains("eligibleForPair(local)"),
                "pair validity itself must fail after the closed-pair grace period");
        assertTrue(semanticPair.contains("topAuthoritativeEntry(key)")
                        && semanticPair.contains("paired.contains(top.getKey())")
                        && authority.contains("serverLayers.topLayerId(key)"),
                "semantic pair status must describe the current stack top, not a stale lower layer");
        assertTrue(semanticPair.contains("pairedServerLayers.remove(serverLayer, localLayer)")
                        && semanticPair.contains("local.serverLayerId = 0L")
                        && semanticPair.contains("if (invalidated) refreshVisual(key)"),
                "invalidating reconciliation must clear both directions of pair identity immediately");
        assertTrue(pairing.contains("eligibleForPair(local)"),
                "later metadata must not pair the same expired local tombstone again");
        assertTrue(expiry.contains("local.serverLayerId != 0L")
                        && expiry.contains("closedPairGraceExpired(local)")
                        && expiry.contains("unpairServer(serverLayerId)")
                        && expiry.contains("reconcileActionConcealment(actionSequence)"),
                "expiry must detach the pair and release every drifted coordinate's concealment lease");
        assertTrue(eligibility.contains("!local.serverClosed")
                        && eligibility.contains("!closedPairGraceExpired(local)")
                        && eligibility.contains("closeGraceTicks(local.actionSequence)")
                        && eligibility.contains("context.confirmationTicks(actionSequence)")
                        && eligibility.contains("Math.min(ACTION_RETENTION_TICKS"),
                "pair eligibility must reject aged closes after a latency-aware but strictly bounded grace");
    }

    @Test
    void authenticatedViewerOwnershipRequiresALiveMappedActionWithoutCoordinatePairing()
            throws IOException {
        final String authority = authority();
        final String concealment = method(authority, "private boolean hidesServerLayer",
                "private void indexAuthoritative");

        assertTrue(concealment.contains(
                        "serverLayers.hidesServerWorld(key, player.getUuid())")
                        && concealment.contains("authoritativeByCoordinate.get(key)")
                        && concealment.contains(
                        "!hasLocalActionConcealment(server.actionSequence)")
                        && concealment.contains("return foundOwnedLayer"),
                "authenticated ownership must be joined to a mapped live/recent action across coordinate drift");
        assertTrue(concealment.contains("findActiveLayer(entry.getKey()) != null")
                        && concealment.contains("!closedPairGraceExpired(local)"),
                "a stale owner tag must fail open after the local action's short close grace");
        assertFalse(concealment.contains("hasSemanticPair(key)"),
                "visual concealment must be independent from optional semantic lifecycle pairing");
        assertTrue(authority.contains("final boolean hiddenClosingLayer = hiddenBefore"),
                "a hidden owned layer must retain its close fence until the vanilla physical close is observed");
        assertFalse(authority.contains("setBlockState("),
                "the concealment fence must remain render-only and leave ClientWorld packet writes intact");
    }

    @Test
    void visibleAuthoritativeTempBlockOccupiesTempLayerAboveDirectPrediction() throws IOException {
        final String authority = authority();
        final String visual = method(authority, "private TempVisual tempVisual",
                "private void refreshVisual");
        final String application = method(authority, "private void applyAuthoritativeOperations",
                "private boolean advanceStream");
        final String repaintAll = method(authority, "private void repaintAll",
                "private void recordAuthoritative");
        final String overlay = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientBlockVisualOverlay.java");

        final int hiddenDecision = visual.indexOf("final boolean hiddenServerLayer = hidesServerLayer(key)");
        final int physicalLookup = visual.indexOf("serverLayers.physicalState(key)");
        final int physicalReturn = visual.indexOf(
                "if (!hiddenServerLayer && physical.isPresent()) return TempVisual.server(physical.get())");
        final int localLookup = visual.indexOf("clientState(key.world, key.pos)");
        assertTrue(physicalLookup >= 0 && hiddenDecision > physicalLookup
                        && physicalReturn > hiddenDecision && localLookup > physicalReturn,
                "an unhidden known Paper layer must be selected before a local visual can cover it");
        assertTrue(visual.contains("if (showServerLayers) return physical.map(TempVisual::server)"),
                "the debug reveal path must also put known server material in the TEMP layer");
        assertTrue(visual.contains("return TempVisual.underlay(viewer.get())")
                        && authority.contains("visualOverlay.setTempUnderlay"),
                "a concealed owner layer must reveal a current DIRECT prediction before its saved viewer fallback");
        assertTrue(visual.contains("return TempVisual.server(overlay.get())"),
                "a newer unowned Paper layer must remain an ordinary TEMP overlay above DIRECT");
        assertFalse(visual.contains("if (showServerLayers || key == null"),
                "the debug reveal path must show known server material, not remove the TEMP priority layer");
        assertFalse(repaintAll.contains("if (showServerLayers)"),
                "debug repaint must repopulate authoritative TEMP entries so DIRECT cannot cover them");
        assertTrue(overlay.indexOf("if (tempState != null) return tempState")
                        < overlay.indexOf("final BlockState directState"),
                "the compositor must keep authoritative TEMP material above DIRECT prediction");
        assertTrue(authority.contains("List.copyOf(authoritativeByCoordinate.keySet())"),
                "full chunk loads must refresh unpaired authoritative coordinates as well as paired ones");
        final int openDecision = application.indexOf("final boolean hiddenAfter = hidesServerLayer(key)");
        final int openHistory = application.indexOf("recordAuthoritative(\"OPEN", openDecision);
        final int metadataAuthority = application.indexOf(
                "Metadata is enough to establish visual authority", openDecision);
        final int openRefresh = application.lastIndexOf("refreshVisual(key);", openHistory);
        assertTrue(openDecision >= 0 && metadataAuthority > openDecision
                        && openRefresh > metadataAuthority && openRefresh < openHistory,
                "every accepted server OPEN must populate TEMP even while its vanilla packet is pending");
    }

    private static String authority() throws IOException {
        return source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientTempBlockAuthority.java");
    }

    private static String source(final String relative) throws IOException {
        Path path = Path.of(relative);
        if (!Files.exists(path)) path = Path.of("fabric").resolve(relative);
        assertTrue(Files.exists(path), "missing source: " + path);
        return Files.readString(path);
    }

    private static String method(final String source, final String start, final String end) {
        final int from = source.indexOf(start);
        final int to = source.indexOf(end, from);
        assertTrue(from >= 0 && to > from, "missing boundary " + start + " -> " + end);
        return source.substring(from, to);
    }
}
