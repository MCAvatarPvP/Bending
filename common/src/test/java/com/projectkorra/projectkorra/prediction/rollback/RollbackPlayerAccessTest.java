package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayerState;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RollbackPlayerAccessTest {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2);
    static RollbackPlayerAccess access(RollbackRosterData roster) {
        var entries = new TreeMap<UUID, RollbackPlayerAccess.Entry>();
        roster.players().forEach((id, player) -> {
            var identity = player.identity();
            entries.put(id, new RollbackPlayerAccess.Entry(id, new RollbackPlayerState.Profile(identity.name(), identity.mode().name(), identity.client().mainHand().name(),
                    true, true, false, 142), Set.of(), Map.of("bending.feature.allowed", true, "bending.feature.denied", false)));
        });
        return new RollbackPlayerAccess(entries);
    }
    private static RollbackPlayerState.Profile profile() { return new RollbackPlayerState.Profile("\u00a7bPlayer", "ADVENTURE", "LEFT", true, true, false, 142); }
    private static RollbackPlayerAccess data() {
        return new RollbackPlayerAccess(Map.of(A, new RollbackPlayerAccess.Entry(A, profile(), Set.of(B), Map.of("bending.*", true, "bending.feature", false)),
                B, new RollbackPlayerAccess.Entry(B, profile(), Set.of(), Map.of("bending.*", false, "bending.feature", true))));
    }

    @Test void roundTripPreservesServerProfileVisibilityExplicitDenialsAndIndependentPlayers() {
        var source = data(); var copy = RollbackPlayerAccess.decode(source.encode());
        assertArrayEquals(source.encode(), copy.encode()); assertEquals(source, copy);
        assertFalse(copy.players().get(A).hasPermission("BENDING.FEATURE"));
        assertTrue(copy.players().get(B).hasPermission("bending.feature"));
        assertEquals(Set.of(B), copy.players().get(A).hidden());
        assertThrows(IllegalStateException.class, () -> copy.players().get(A).hasPermission("bending.unregistered"), "Operator status and wildcard grants cannot invent unqueried provider decisions");
        assertThrows(UnsupportedOperationException.class, () -> copy.players().get(A).permissions().clear());
        assertThrows(UnsupportedOperationException.class, () -> copy.players().clear());
    }

    @Test void mutableCaptureCollectionsCannotAlterAnImportedPolicy() {
        var permissions = new HashMap<>(Map.of("bending.feature", false)); var hidden = new HashSet<>(Set.of(B));
        var entry = new RollbackPlayerAccess.Entry(A, profile(), hidden, permissions);
        permissions.put("bending.feature", true); hidden.clear();
        assertFalse(entry.hasPermission("bending.feature")); assertEquals(Set.of(B), entry.hidden());
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlayerAccess.Entry(A, profile(), Set.of(), Map.of("Bending.Feature", true)));
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlayerAccess(Map.of(A, data().players().get(B), B, data().players().get(A))));
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlayerAccess(Map.of(A, new RollbackPlayerAccess.Entry(A, profile(), Set.of(new UUID(0, 3)), Map.of()), B, data().players().get(B))));
    }

    @Test void malformedTruncatedOversizedAndNoncanonicalDataCannotReachPlayerBinding() {
        byte[] bytes = data().encode();
        for (int i = 0; i < bytes.length; i++) {
            byte[] truncated = Arrays.copyOf(bytes, i); assertThrows(IllegalArgumentException.class, () -> RollbackPlayerAccess.decode(truncated));
        }
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerAccess.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerAccess.decode(new byte[RollbackPlayerAccess.MAXIMUM_BYTES + 1]));
        byte[] count = bytes.clone(); ByteBuffer.wrap(count).putInt(4, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerAccess.decode(count));
        byte[] name = bytes.clone(); ByteBuffer.wrap(name).putInt(24, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerAccess.decode(name));
        byte[] malformed = bytes.clone(); malformed[28] = (byte) 0xff;
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerAccess.decode(malformed));
        int onlineOffset = 24;
        for (int i = 0; i < 3; i++) onlineOffset += 4 + ByteBuffer.wrap(bytes).getInt(onlineOffset);
        byte[] flag = bytes.clone(); flag[onlineOffset] = 2;
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerAccess.decode(flag));
    }
}
