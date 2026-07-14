package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.platform.mc.permissions.Permission;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PaperPredictionProtocolTest {
    private static byte[] input(UUID session, int encodedFlag) {
        return input(session, encodedFlag, 0);
    }

    private static byte[] input(UUID session, int predictedFlag, int cooldownFlag) {
        byte[] payload = new PaperPredictionProtocol.Writer()
                .uuid(session)
                .varLong(42)
                .i64(100)
                .enumeration(PaperPredictionProtocol.InputKind.LEFT_CLICK)
                .varInt(0)
                .f32(10.0F)
                .f32(5.0F)
                .f64(1.0)
                .f64(2.0)
                .f64(3.0)
                .bool(false)
                .bool(false)
                .bytes();
        payload[payload.length - 2] = (byte) predictedFlag;
        payload[payload.length - 1] = (byte) cooldownFlag;
        return payload;
    }

    @Test
    void inputDecoderConsumesFabricLocallyPredictedFlag() {
        UUID session = UUID.randomUUID();
        byte[] predicted = input(session, 1, 0);
        byte[] declined = input(session, 0, 0);

        PaperPredictionProtocol.Input predictedInput = PaperPredictionProtocol.readInput(predicted);
        PaperPredictionProtocol.Input declinedInput = PaperPredictionProtocol.readInput(declined);

        assertEquals(session, predictedInput.session());
        assertTrue(predictedInput.locallyPredicted());
        assertFalse(declinedInput.locallyPredicted());
    }

    @Test
    void inputDecoderRejectsNonBooleanPredictionFlag() {
        assertThrows(IllegalArgumentException.class, () ->
                PaperPredictionProtocol.readInput(input(UUID.randomUUID(), 2)));
    }

    @Test
    void blockEchoPolicySuppressesIntermediateButAllowsDivergedFinalRestore() {
        assertTrue(PredictionEchoPolicy.shouldSuppress(false, true, false));
        assertTrue(PredictionEchoPolicy.shouldSuppress(false, false, true));
        assertFalse(PredictionEchoPolicy.shouldSuppress(false, false, false));
        assertTrue(PredictionEchoPolicy.shouldSuppress(true, true, false));
    }

    @Test
    void activeServerTempLayerCannotBeRemovedByLocalCleanup() {
        assertFalse(PredictionEchoPolicy.mayApplyLocalMutationOverServerTemp(true, false));
        assertTrue(PredictionEchoPolicy.mayApplyLocalMutationOverServerTemp(true, true));
        assertTrue(PredictionEchoPolicy.mayApplyLocalMutationOverServerTemp(false, false));
    }

    @Test
    void loaderNeutralPermissionRetainsAddonDefaultParent() {
        Permission player = new Permission("bending.player");
        Permission addon = new Permission("bending.ability.ExampleAddon");

        addon.addParent(player, true);

        assertEquals(Boolean.TRUE, addon.getParents().get(player));
    }

    @Test
    void cooldownTimelineBackdatesServerExpiryToLogicalInputTime() {
        assertEquals(1_600L, PredictionCooldownTimeline.alignedExpiry(2_000L, 400L, 1_000L));
        assertEquals(0L, PredictionCooldownTimeline.alignedExpiry(1_300L, 400L, 1_000L));
    }

    @Test
    void cooldownGuardStillAllowsCombosAndExistingAbilityActions() {
        assertTrue(PredictionCooldownTimeline.allowsCooldownGuardedInput(true, false, false));
        assertTrue(PredictionCooldownTimeline.allowsCooldownGuardedInput(false, true, true));
        assertFalse(PredictionCooldownTimeline.allowsCooldownGuardedInput(false, true, false));
        assertFalse(PredictionCooldownTimeline.allowsCooldownGuardedInput(false, false, true));
    }

    @Test
    void activationOutcomeDistinguishesHandledCastFromComboOnlyInput() {
        AbilityActivationManager.beginTracking();
        assertFalse(AbilityActivationManager.finishTracking());

        AbilityActivationManager.beginTracking();
        AbilityActivationManager.markHandled();
        assertTrue(AbilityActivationManager.finishTracking());
    }

    @Test
    void velocityReceiptCarriesExactOwnerActionTargetAndOrdinal() {
        UUID owner = UUID.randomUUID();
        byte[] payload = PaperPredictionProtocol.velocityOwner(91, 27, 4, owner, owner,
                "AirScooter");
        PaperPredictionProtocol.Reader reader = new PaperPredictionProtocol.Reader(payload);
        assertEquals(91L, reader.i64());
        assertEquals(27L, reader.varLong());
        assertEquals(4, reader.varInt());
        assertEquals(owner, reader.uuid());
        assertEquals(owner, reader.uuid());
        assertEquals("AirScooter", reader.string(128));
        reader.finished();
    }

    @Test
    void velocityV2ReceiptCarriesVectorForPacketOwnership() {
        UUID owner = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        byte[] payload = PaperPredictionProtocol.velocityOwnerV2(92, 28, 5, owner, target,
                417, "FireSpin", 1.25, 0.1, -0.75);
        PaperPredictionProtocol.Reader reader = new PaperPredictionProtocol.Reader(payload);
        assertEquals(92L, reader.i64());
        assertEquals(28L, reader.varLong());
        assertEquals(5, reader.varInt());
        assertEquals(owner, reader.uuid());
        assertEquals(target, reader.uuid());
        assertEquals(417, reader.varInt());
        assertEquals("FireSpin", reader.string(128));
        assertEquals(1.25, reader.f64());
        assertEquals(0.1, reader.f64());
        assertEquals(-0.75, reader.f64());
        reader.finished();
    }

    @Test
    void stateDirectiveCarriesExternalCooldownAddition() {
        UUID session = UUID.randomUUID();
        byte[] payload = PaperPredictionProtocol.stateDirective(session, "", "AirSpout",
                12_500L, 10_000L, false, Double.NaN);
        PaperPredictionProtocol.Reader reader = new PaperPredictionProtocol.Reader(payload);
        assertEquals(session, reader.uuid());
        assertEquals("", reader.string(128));
        assertEquals("AirSpout", reader.string(128));
        assertEquals(12_500L, reader.i64());
        assertEquals(10_000L, reader.i64());
        assertFalse(reader.bool());
        assertTrue(Double.isNaN(reader.f64()));
        reader.finished();
    }

    @Test
    void inputDecoderConsumesClientCooldownDecision() {
        PaperPredictionProtocol.Input input = PaperPredictionProtocol.readInput(input(UUID.randomUUID(), 0, 1));
        assertTrue(input.locallyBlockedByCooldown());
        assertFalse(input.locallyPredicted());
    }

    @Test
    void authorityHandoffDecoderRequiresExactPayload() {
        UUID session = UUID.randomUUID();
        byte[] payload = new PaperPredictionProtocol.Writer().uuid(session).varLong(73).bytes();
        PaperPredictionProtocol.Handoff handoff = PaperPredictionProtocol.readHandoff(payload);
        assertEquals(session, handoff.session());
        assertEquals(73L, handoff.sequence());
        assertThrows(IllegalArgumentException.class,
                () -> PaperPredictionProtocol.readHandoff(Arrays.copyOf(payload, payload.length - 1)));
    }

    @Test
    void actionPrepareDecoderMatchesFabricOrdering() {
        UUID session = UUID.randomUUID();
        byte[] payload = new PaperPredictionProtocol.Writer()
                .uuid(session).varLong(81).i64(120)
                .enumeration(PaperPredictionProtocol.InputKind.LEFT_CLICK).varInt(2)
                .f32(35.0F).f32(-12.0F).f64(1.25).f64(64.5).f64(-8.75).bytes();
        PaperPredictionProtocol.Prepare prepare = PaperPredictionProtocol.readPrepare(payload);
        assertEquals(session, prepare.session());
        assertEquals(81L, prepare.sequence());
        assertEquals(PaperPredictionProtocol.InputKind.LEFT_CLICK, prepare.kind());
        assertEquals(2, prepare.selectedSlot());
        assertEquals(1.25, prepare.x());
        assertThrows(IllegalArgumentException.class,
                () -> PaperPredictionProtocol.readPrepare(Arrays.copyOf(payload, payload.length - 1)));
    }
}
