package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.platform.mc.permissions.Permission;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PaperPredictionProtocolTest {

    @Test
    void reconcileCarriesTheAuthoritativeInputAndComboOutcome() {
        UUID session = UUID.randomUUID();
        PaperPredictionProtocol.Reader reader = new PaperPredictionProtocol.Reader(
                PaperPredictionProtocol.reconcile(session, 41L, true, "accepted", 90L,
                        12_000L, "AirBlast", 1.25, 64.0, -3.5, 0L,
                        true, true, List.of("AirBlast", "AirSweep")));

        assertEquals(session, reader.uuid());
        assertEquals(41L, reader.varLong());
        assertTrue(reader.bool());
        assertEquals("accepted", reader.string(128));
        assertEquals(90L, reader.i64());
        assertEquals(12_000L, reader.i64());
        assertEquals("AirBlast", reader.string(128));
        assertEquals(1.25, reader.f64());
        assertEquals(64.0, reader.f64());
        assertEquals(-3.5, reader.f64());
        assertEquals(0L, reader.i64());
        assertTrue(reader.bool());
        assertTrue(reader.bool());
        assertEquals(2, reader.varInt());
        assertEquals("AirBlast", reader.string(128));
        assertEquals("AirSweep", reader.string(128));
        reader.finished();
    }

    @Test
    void worldStateCarriesOpaqueBukkitWorldIdentity() {
        UUID session = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        PaperPredictionProtocol.Reader reader = new PaperPredictionProtocol.Reader(
                PaperPredictionProtocol.worldState(session, 7L, world.toString()));

        assertEquals(session, reader.uuid());
        assertEquals(7L, reader.varLong());
        assertEquals(world.toString(), reader.string(128));
        reader.finished();
    }

    @Test
    void protocolIncludesExactAbilityStateOwnershipFence() {
        assertEquals(43, PaperPredictionProtocol.VERSION);
        assertEquals("projectkorra:ability_state_owner", PaperPredictionProtocol.ABILITY_STATE_OWNER);
        UUID owner = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        assertTrue(PaperPredictionProtocol.abilityStateOwner(7L, 3L, 2,
                owner, target, "WaterSpout", true, true, 0.1F).length > 32);

        PaperPredictionProtocol.Reader reader = new PaperPredictionProtocol.Reader(
                PaperPredictionProtocol.abilityStateOwner(7L, 3L, 2,
                        owner, target, "WaterSpout", true, true, 0.1F));
        assertEquals(7L, reader.i64());
        assertEquals(3L, reader.varLong());
        assertEquals(2, reader.varInt());
        assertEquals(owner, reader.uuid());
        assertEquals(target, reader.uuid());
        assertEquals("WaterSpout", reader.string(128));
        assertTrue(reader.bool());
        assertTrue(reader.bool());
        assertEquals(0.1F, reader.f32());
        reader.finished();
    }

    @Test
    void airBlastTraceCarriesTheActualLaunchAndFirstProgressEvidence() {
        UUID session = UUID.randomUUID();
        AirBlastTraceSync.Trace trace = new AirBlastTraceSync.Trace(
                3, AirBlastTraceSync.Phase.PROGRESS, 1,
                1.25, 65.62, -8.75, 180.0F, 90.0F,
                1.25, 64.2, -5.75, 1.25, 0.2, -5.75,
                1.25, 64.2, -5.75, 0.0, -1.0, 0.0,
                23.0, 11.0, 1.5, 1.2, 1.0,
                1, 64, -5, "AIR", false);

        PaperPredictionProtocol.Reader reader = new PaperPredictionProtocol.Reader(
                PaperPredictionProtocol.airBlastTrace(session, 41L, 92L, trace));
        assertEquals(session, reader.uuid());
        assertEquals(41L, reader.varLong());
        assertEquals(92L, reader.i64());
        assertEquals(3, reader.varInt());
        assertEquals(AirBlastTraceSync.Phase.PROGRESS,
                reader.enumeration(AirBlastTraceSync.Phase.values()));
        assertEquals(1, reader.varInt());
        assertEquals(1.25, reader.f64());
        assertEquals(65.62, reader.f64());
        assertEquals(-8.75, reader.f64());
        assertEquals(180.0F, reader.f32());
        assertEquals(90.0F, reader.f32());
        for (double expected : new double[]{
                1.25, 64.2, -5.75, 1.25, 0.2, -5.75,
                1.25, 64.2, -5.75, 0.0, -1.0, 0.0,
                23.0, 11.0, 1.5, 1.2, 1.0}) {
            assertEquals(expected, reader.f64());
        }
        assertEquals(1, reader.i32());
        assertEquals(64, reader.i32());
        assertEquals(-5, reader.i32());
        assertEquals("AIR", reader.string(128));
        assertFalse(reader.bool());
        reader.finished();
    }

    @Test
    void inputVetoDecoderCarriesOnlyNegativeNativeEventIdentity() {
        UUID session = UUID.randomUUID();
        byte[] payload = new PaperPredictionProtocol.Writer().uuid(session).varLong(41L)
                .enumeration(PaperPredictionProtocol.InputKind.LEFT_CLICK)
                .string("AirBurst", 128).bytes();
        PaperPredictionProtocol.InputVeto veto = PaperPredictionProtocol.readInputVeto(payload);

        assertEquals(session, veto.session());
        assertEquals(41L, veto.sequence());
        assertEquals(PaperPredictionProtocol.InputKind.LEFT_CLICK, veto.kind());
        assertEquals("AirBurst", veto.ability());
    }

    @Test
    void actionTagAndHitClaimDecodeTheClientEvidenceEnvelope() {
        UUID session = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        byte[] tagPayload = new PaperPredictionProtocol.Writer().uuid(session).varLong(17L)
                .enumeration(PaperPredictionProtocol.InputKind.LEFT_CLICK)
                .varInt(2).string("AirBlast", 128).bytes();
        PaperPredictionProtocol.ActionTag tag = PaperPredictionProtocol.readActionTag(tagPayload);
        assertEquals(session, tag.session());
        assertEquals(17L, tag.clientSequence());
        assertEquals(PaperPredictionProtocol.InputKind.LEFT_CLICK, tag.kind());
        assertEquals(2, tag.selectedSlot());
        assertEquals("AirBlast", tag.ability());

        byte[] hitPayload = new PaperPredictionProtocol.Writer().uuid(session)
                .varLong(17L).varLong(16L).i64(400L).uuid(target).varInt(91)
                .string("AirBlast", 128).f64(1.25).f64(64.9).f64(-3.5).bytes();
        PaperPredictionProtocol.HitClaim hit = PaperPredictionProtocol.readHitClaim(hitPayload);
        assertEquals(session, hit.session());
        assertEquals(17L, hit.clientSequence());
        assertEquals(16L, hit.serverSequence());
        assertEquals(400L, hit.clientTick());
        assertEquals(target, hit.target());
        assertEquals(91, hit.entityId());
        assertEquals("AirBlast", hit.ability());
        assertEquals(1.25, hit.x());
        assertEquals(64.9, hit.y());
        assertEquals(-3.5, hit.z());
    }

    @Test
    void readyDecoderCarriesSupportedClientAbilityCatalog() {
        UUID session = UUID.randomUUID();
        byte[] payload = new PaperPredictionProtocol.Writer().uuid(session).varInt(3)
                .string("PhaseChange", 128).string("EarthSmash", 128)
                .string("WaterArms", 128).bytes();
        PaperPredictionProtocol.Ready ready = PaperPredictionProtocol.readReady(payload);

        assertEquals(session, ready.session());
        assertEquals(List.of("PhaseChange", "EarthSmash", "WaterArms"), ready.supportedAbilities());
    }

    @Test
    void readyDecoderRejectsUnboundedAbilityCatalog() {
        byte[] payload = new PaperPredictionProtocol.Writer().uuid(UUID.randomUUID()).varInt(2_049).bytes();
        assertThrows(IllegalArgumentException.class, () ->
                PaperPredictionProtocol.readReady(payload));
    }

    @Test
    void blockEchoPolicyKeepsOrderedMovementAheadOfOlderServerEchoes() {
        assertTrue(PredictionEchoPolicy.shouldSuppress(false, true, false));
        assertTrue(PredictionEchoPolicy.shouldSuppress(false, false, true));
        assertFalse(PredictionEchoPolicy.shouldSuppress(false, false, false));
        assertTrue(PredictionEchoPolicy.shouldSuppress(true, false, false));
        assertFalse(PredictionEchoPolicy.confirmedByLatestAuthority(true, false),
                "an older match must not keep a diverged block confirmed");
        assertTrue(PredictionEchoPolicy.confirmedByLatestAuthority(false, true));
    }

    @Test
    void activeServerTempLayerCannotBeRemovedByLocalCleanup() {
        assertFalse(PredictionEchoPolicy.mayApplyLocalMutationOverServerTemp(true, false));
        assertTrue(PredictionEchoPolicy.mayApplyLocalMutationOverServerTemp(true, true));
        assertTrue(PredictionEchoPolicy.mayApplyLocalMutationOverServerTemp(false, false));
    }

    @Test
    void tempBlockStateIdentityIncludesFluidLevelAndWaterlogging() {
        com.projectkorra.projectkorra.platform.mc.block.data.Levelled source =
                new com.projectkorra.projectkorra.platform.mc.block.data.Levelled(
                        com.projectkorra.projectkorra.platform.mc.Material.WATER);
        com.projectkorra.projectkorra.platform.mc.block.data.Levelled flowing = source.clone();
        flowing.setLevel(7);
        assertNotEquals(TempBlockSync.encode(source), TempBlockSync.encode(flowing));

        com.projectkorra.projectkorra.platform.mc.block.data.Levelled waterlogged = source.clone();
        waterlogged.setWaterlogged(true);
        assertNotEquals(TempBlockSync.encode(source), TempBlockSync.encode(waterlogged));
    }

    @Test
    void loaderNeutralPermissionRetainsAddonDefaultParent() {
        Permission player = new Permission("bending.player");
        Permission addon = new Permission("bending.ability.ExampleAddon");

        addon.addParent(player, true);

        assertEquals(Boolean.TRUE, addon.getParents().get(player));
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
    void directBlockReceiptCarriesCausalEarthWriteIdentity() {
        UUID owner = UUID.randomUUID();
        byte[] payload = PaperPredictionProtocol.directBlock(93, 31, 7, owner,
                "RaiseEarth", "world", 12, 64, -8, "minecraft:stone", true);
        PaperPredictionProtocol.Reader reader = new PaperPredictionProtocol.Reader(payload);
        assertEquals(93L, reader.i64());
        assertEquals(31L, reader.varLong());
        assertEquals(7, reader.varInt());
        assertEquals(owner, reader.uuid());
        assertEquals("RaiseEarth", reader.string(128));
        assertEquals("world", reader.string(256));
        assertEquals(12, reader.i32());
        assertEquals(64, reader.i32());
        assertEquals(-8, reader.i32());
        assertEquals("minecraft:stone", reader.string(PaperPredictionProtocol.MAX_BLOCK_STATE_CHARACTERS));
        assertTrue(reader.bool());
        reader.finished();
    }

    @Test
    void abilityRemovalDistinguishesExternalCollisionFromNormalLifecycle() {
        UUID owner = UUID.randomUUID();
        PaperPredictionProtocol.Reader reader = new PaperPredictionProtocol.Reader(
                PaperPredictionProtocol.abilityRemoved(owner, "WaterSpout",
                        "com.projectkorra.projectkorra.waterbending.WaterSpoutWave", 47L, true,
                        52L, 0));
        assertEquals(owner, reader.uuid());
        assertEquals("WaterSpout", reader.string(128));
        assertEquals("com.projectkorra.projectkorra.waterbending.WaterSpoutWave", reader.string(256));
        assertEquals(47L, reader.varLong());
        assertTrue(reader.bool());
        assertEquals(52L, reader.varLong());
        assertEquals(0, reader.varInt());
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
    void tempFallingBlockReceiptCarriesExactCasterActionOrdinalAndEntity() {
        UUID owner = UUID.randomUUID();
        byte[] payload = PaperPredictionProtocol.tempFallingBlock(
                93, 29, 6, owner, 418, "EarthLine");
        PaperPredictionProtocol.Reader reader = new PaperPredictionProtocol.Reader(payload);
        assertEquals(93L, reader.i64());
        assertEquals(29L, reader.varLong());
        assertEquals(6, reader.varInt());
        assertEquals(owner, reader.uuid());
        assertEquals(418, reader.varInt());
        assertEquals("EarthLine", reader.string(128));
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
    void playerStateCarriesAcknowledgedInputBeforeFlightState() {
        UUID session = UUID.randomUUID();
        byte[] payload = PaperPredictionProtocol.state(session, 90, 10_000, 44,
                Map.of(), Map.of(), List.of(), List.of(),
                List.of("bending.ability.waterspout.wave"), 1.0, true,
                RegionProtectionAuthority.Snapshot.empty(), List.of("waterspout"));
        PaperPredictionProtocol.Reader reader = new PaperPredictionProtocol.Reader(payload);
        assertEquals(session, reader.uuid());
        assertEquals(90L, reader.i64());
        assertEquals(10_000L, reader.i64());
        assertEquals(44L, reader.varLong());
        assertEquals(0, reader.varInt());
        assertEquals(0, reader.varInt());
        assertEquals(0, reader.varInt());
        assertEquals(0, reader.varInt());
        assertEquals(1, reader.varInt());
        assertEquals("bending.ability.waterspout.wave", reader.string(128));
        assertEquals(1.0, reader.f64());
        assertTrue(reader.bool());
        assertEquals("", reader.string(256));
        assertEquals(9, reader.varInt());
        assertEquals("", reader.string(128));
        for (int policy = 0; policy < 8; policy++) {
            assertEquals("@policy:" + policy, reader.string(128));
        }
        assertEquals(0, reader.varInt());
        assertEquals(1, reader.varInt());
        assertEquals("waterspout", reader.string(8_192));
        reader.finished();
    }

    @Test
    void tempBlockReceiptCarriesStableLayerOwnerAndHiddenState() {
        UUID session = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        PaperPredictionProtocol.TempBlockOp operation = new PaperPredictionProtocol.TempBlockOp(
                PaperPredictionProtocol.TempOperation.CREATE, "world", 1, 64, 2,
                "minecraft:stone", 12_000, 45, "EarthSmash", 6, 3,
                7, 99, owner, "minecraft:air", true);
        byte[] payload = PaperPredictionProtocol.tempBlocks(
                session, 4L, world.toString(), false, 91, 10_000, List.of(operation));
        PaperPredictionProtocol.Reader reader = new PaperPredictionProtocol.Reader(payload);
        assertEquals(session, reader.uuid());
        assertEquals(4L, reader.varLong());
        assertEquals(world.toString(), reader.string(128));
        assertFalse(reader.bool());
        assertEquals(91L, reader.i64());
        assertEquals(10_000L, reader.i64());
        assertEquals(1, reader.varInt());
        assertEquals(PaperPredictionProtocol.TempOperation.CREATE,
                reader.enumeration(PaperPredictionProtocol.TempOperation.values()));
        assertEquals("world", reader.string(256));
        assertEquals(1, reader.i32());
        assertEquals(64, reader.i32());
        assertEquals(2, reader.i32());
        assertEquals("minecraft:stone", reader.string(PaperPredictionProtocol.MAX_BLOCK_STATE_CHARACTERS));
        assertEquals(12_000L, reader.i64());
        assertEquals(45L, reader.varLong());
        assertEquals("EarthSmash", reader.string(128));
        assertEquals(6L, reader.i64());
        assertEquals(3, reader.varInt());
        assertEquals(7L, reader.varLong());
        assertEquals(99L, reader.varLong());
        assertTrue(reader.bool());
        assertEquals(owner, reader.uuid());
        assertEquals("minecraft:air", reader.string(PaperPredictionProtocol.MAX_BLOCK_STATE_CHARACTERS));
        assertTrue(reader.bool());
        reader.finished();
    }

    @Test
    void boundedTempBlockPageAlwaysFitsPluginMessageLimit() {
        String maximumWorld = "界".repeat(256);
        String maximumState = "界".repeat(PaperPredictionProtocol.MAX_BLOCK_STATE_CHARACTERS);
        String maximumAbility = "界".repeat(128);
        UUID owner = UUID.randomUUID();
        List<PaperPredictionProtocol.TempBlockOp> page = java.util.stream.LongStream.range(0, 4)
                .mapToObj(layer -> new PaperPredictionProtocol.TempBlockOp(
                        PaperPredictionProtocol.TempOperation.CREATE, maximumWorld,
                        Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, maximumState,
                        Long.MAX_VALUE, Long.MAX_VALUE, maximumAbility, Long.MAX_VALUE,
                        Integer.MAX_VALUE, layer, Long.MAX_VALUE, owner, maximumState, true))
                .toList();

        assertTrue(PaperPredictionProtocol.tempBlocks(UUID.randomUUID(), Long.MAX_VALUE,
                "ç•Œ".repeat(128), true, Long.MAX_VALUE, Long.MAX_VALUE, page).length
                <= 32_766); // Bukkit Messenger.MAX_MESSAGE_SIZE
    }

    @Test
    void exactTempBlockStateIsNeverTruncatedAtTheLegacyMaterialLimit() {
        String exactState = "minecraft:test_block[" + "property=value,".repeat(18) + "last=true]";
        PaperPredictionProtocol.TempBlockOp operation = new PaperPredictionProtocol.TempBlockOp(
                PaperPredictionProtocol.TempOperation.CREATE, "world", 1, 64, 2,
                exactState, 0L, 1L, "FireBlast", 2L, 1,
                2L, 3L, null, exactState, true);
        PaperPredictionProtocol.Reader reader = new PaperPredictionProtocol.Reader(
                PaperPredictionProtocol.tempBlocks(UUID.randomUUID(), 1L, "world-id", true,
                        1L, 2L, List.of(operation)));

        reader.uuid();
        reader.varLong();
        reader.string(128);
        reader.bool();
        reader.i64();
        reader.i64();
        reader.varInt();
        reader.enumeration(PaperPredictionProtocol.TempOperation.values());
        reader.string(256);
        reader.i32();
        reader.i32();
        reader.i32();
        assertEquals(exactState, reader.string(PaperPredictionProtocol.MAX_BLOCK_STATE_CHARACTERS));
        reader.i64();
        reader.varLong();
        reader.string(128);
        reader.i64();
        reader.varInt();
        reader.varLong();
        reader.varLong();
        assertFalse(reader.bool());
        assertEquals(exactState, reader.string(PaperPredictionProtocol.MAX_BLOCK_STATE_CHARACTERS));
        assertTrue(reader.bool());
        reader.finished();
    }

    @Test
    void nativeActionReceiptMatchesFabricOrdering() {
        UUID session = UUID.randomUUID();
        byte[] payload = PaperPredictionProtocol.nativeAction(session, 81, 120,
                PaperPredictionProtocol.InputKind.LEFT_CLICK, 2, "EarthSmash",
                1.25, 64.5, -8.75, 35.0F, -12.0F, true);
        PaperPredictionProtocol.Reader reader = new PaperPredictionProtocol.Reader(payload);
        assertEquals(session, reader.uuid());
        assertEquals(81L, reader.varLong());
        assertEquals(120L, reader.i64());
        assertEquals(PaperPredictionProtocol.InputKind.LEFT_CLICK,
                reader.enumeration(PaperPredictionProtocol.InputKind.values()));
        assertEquals(2, reader.varInt());
        assertEquals("EarthSmash", reader.string(128));
        assertEquals(1.25, reader.f64());
        assertEquals(64.5, reader.f64());
        assertEquals(-8.75, reader.f64());
        assertEquals(35.0F, reader.f32());
        assertEquals(-12.0F, reader.f32());
        assertTrue(reader.bool());
        reader.finished();
    }
}
