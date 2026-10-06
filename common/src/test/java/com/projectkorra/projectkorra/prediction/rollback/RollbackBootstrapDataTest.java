package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainCodec;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.*;

import static com.projectkorra.projectkorra.prediction.rollback.RollbackWorldSeedTest.*;
import static org.junit.jupiter.api.Assertions.*;

class RollbackBootstrapDataTest {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2);
    private static final String DEFINITIONS = "12".repeat(32);
    private static RollbackGraphCodec graph() {
        return new RollbackGraphCodec(new RollbackGraphCodec.Catalog(List.of(), List.of(), List.of()),
                new RollbackGraphCodec.Limits(100, 100, 8_192, 1_024));
    }
    private static RollbackBootstrapData data(long rosterTick, Map<UUID, UUID> sides) {
        var roster = new RollbackRosterData(rosterTick, Map.of(A, RollbackRosterDataTest.player(A, 101, B, List.of()),
                B, RollbackRosterDataTest.player(B, 102, A, List.of())));
        return new RollbackBootstrapData(new UUID(0, 3), new UUID(0, 4), new UUID(0, 5), 2, 1_700_000_000_000L, 9_000_000L,
                DEFINITIONS, sides, seed(20), roster, RollbackConfiguration.captureData(Map.of()), RollbackPlayerAccessTest.access(roster), new RollbackMaterials(Set.of(Material.STONE)), graph().encode(List.of("captured roots", List.of(A, B))));
    }
    private static RollbackBootstrapData data() { return data(20, Map.of(A, A, B, B)); }

    @Test void entireDuelStateAndExactCaptureEpochRoundTripWithOneFingerprint() {
        var source = data(); byte[] bytes = source.encode(STATES, LIMITS);
        var copy = RollbackBootstrapData.decode(bytes, DEFINITIONS, STATES, LIMITS);
        assertArrayEquals(bytes, copy.encode(STATES, LIMITS));
        assertEquals(source.session(), copy.session()); assertEquals(source.challenge(), copy.challenge());
        assertEquals(source.match(), copy.match()); assertEquals(2, copy.round());
        assertEquals(source.epochMillis(), copy.epochMillis()); assertEquals(source.epochNanos(), copy.epochNanos());
        assertArrayEquals(source.roster().encode(), copy.roster().encode());
        assertEquals(source.access(), copy.access());
        assertEquals(source.materials(), copy.materials());
        assertEquals(source.configuration().fingerprint(), copy.configuration().fingerprint());
        assertEquals(List.of("captured roots", List.of(A, B)), graph().decode(copy.bending()));
        assertEquals(RollbackBootstrapData.fingerprint(bytes), RollbackBootstrapData.fingerprint(copy.encode(STATES, LIMITS)));
        copy.bending()[0] = 8; assertArrayEquals(source.bending(), copy.bending());
        byte[] changed = bytes.clone(); changed[55] ^= 1;
        assertNotEquals(RollbackBootstrapData.fingerprint(bytes), RollbackBootstrapData.fingerprint(changed));
        assertThrows(UnsupportedOperationException.class, () -> copy.sides().clear());
    }

    @Test void mismatchedCaptureTicksMissingPlayersAndSingleSideAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> data(21, Map.of(A, A, B, B)));
        assertThrows(IllegalArgumentException.class, () -> data(20, Map.of(A, A)));
        assertThrows(IllegalArgumentException.class, () -> data(20, Map.of(A, A, B, A)));
        assertThrows(IllegalArgumentException.class, () -> data(20, Map.of(A, A, new UUID(0, 8), B)));
    }

    @Test void playerPolicyMustMatchTheNativeRostersIdentityModeAndMainHand() {
        var source = data(); var entries = new TreeMap<>(source.access().players()); var first = entries.get(A);
        var profile = first.profile();
        entries.put(A, new RollbackPlayerAccess.Entry(A, new com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayerState.Profile(
                profile.displayName(), "CREATIVE", profile.mainHand(), true, profile.operator(), profile.playedBefore(), profile.ping()), first.hidden(), first.permissions()));
        assertThrows(IllegalArgumentException.class, () -> replaceAccess(source, new RollbackPlayerAccess(entries)));
        entries.put(A, new RollbackPlayerAccess.Entry(A, new com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayerState.Profile(
                profile.displayName(), profile.gameMode(), "RIGHT", true, profile.operator(), profile.playedBefore(), profile.ping()), first.hidden(), first.permissions()));
        assertThrows(IllegalArgumentException.class, () -> replaceAccess(source, new RollbackPlayerAccess(entries)));
        entries.remove(A); var foreign = new UUID(0, 9);
        entries.put(foreign, new RollbackPlayerAccess.Entry(foreign, profile, Set.of(), first.permissions()));
        assertThrows(IllegalArgumentException.class, () -> replaceAccess(source, new RollbackPlayerAccess(entries)));
    }
    private static RollbackBootstrapData replaceAccess(RollbackBootstrapData source, RollbackPlayerAccess access) {
        return new RollbackBootstrapData(source.session(), source.challenge(), source.match(), source.round(), source.epochMillis(), source.epochNanos(),
                source.definitions(), source.sides(), source.world(), source.roster(), source.configuration(), access, source.materials(), source.bending());
    }

    @Test void foreignDefinitionsRejectBeforeNativeDecodeAndMalformedEnvelopesCannotBeAcknowledged() {
        byte[] bytes = data().encode(STATES, LIMITS);
        var unavailableNative = new RollbackTerrainCodec.BlockStates() {
            @Override public String encode(BlockData data) { throw new AssertionError("Native encoding reached"); }
            @Override public BlockData decode(Material material, String exact) { throw new AssertionError("Native decoding reached"); }
        };
        assertThrows(IllegalArgumentException.class, () -> RollbackBootstrapData.decode(bytes, "34".repeat(32), unavailableNative, LIMITS));
        for (int i = 0; i < bytes.length; i++) {
            byte[] cut = Arrays.copyOf(bytes, i);
            assertThrows(IllegalArgumentException.class, () -> RollbackBootstrapData.decode(cut, DEFINITIONS, STATES, LIMITS));
        }
        assertThrows(IllegalArgumentException.class, () -> RollbackBootstrapData.decode(Arrays.copyOf(bytes, bytes.length + 1), DEFINITIONS, STATES, LIMITS));
        byte[] count = bytes.clone(); ByteBuffer.wrap(count).putInt(104, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> RollbackBootstrapData.decode(count, DEFINITIONS, STATES, LIMITS));
        byte[] duplicate = bytes.clone(); System.arraycopy(duplicate, 108, duplicate, 140, 16);
        assertThrows(IllegalArgumentException.class, () -> RollbackBootstrapData.decode(duplicate, DEFINITIONS, STATES, LIMITS));
        byte[] length = bytes.clone(); ByteBuffer.wrap(length).putInt(172, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> RollbackBootstrapData.decode(length, DEFINITIONS, STATES, LIMITS));
    }
}
