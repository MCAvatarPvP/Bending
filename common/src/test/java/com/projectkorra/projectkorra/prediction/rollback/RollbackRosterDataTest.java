package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackRosterData.*;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerValues.Vector;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemData;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RollbackRosterDataTest {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2);
    @Test void wholeRosterRoundTripPreservesIdentitySignedPropertiesAndAllComponents() {
        var properties = new ArrayList<>(List.of(new Property("textures", "textures", "skin", "signature"), new Property("textures", "extra", "\u6c34", null)));
        var first = player(A, 1, B, properties); properties.clear();
        var roster = new HashMap<>(Map.of(B, player(B, 2, A, List.of()), A, first));
        var data = new RollbackRosterData(20, roster); roster.clear();
        var decoded = RollbackRosterData.decode(data.encode());
        assertArrayEquals(data.encode(), decoded.encode());
        assertEquals(List.of(A, B), new ArrayList<>(decoded.players().keySet()));
        assertEquals(first.identity(), decoded.players().get(A).identity());
        assertEquals(17, decoded.players().get(A).randomSeed());
        assertEquals(B, decoded.players().get(A).combat().lastHurtByPlayer());
        assertThrows(UnsupportedOperationException.class, () -> decoded.players().clear());
        assertThrows(IllegalArgumentException.class, () -> new RollbackRosterData(20, Map.of(A, first)));
        assertThrows(IllegalArgumentException.class, () -> new RollbackRosterData(20, Map.of(A, first, B, player(B, 1, A, List.of()))));
        assertThrows(IllegalArgumentException.class, () -> new RollbackRosterData(20, Map.of(A, player(B, 2, null, List.of()))));
    }
    @Test void malformedLengthsOrderFlagsEnumsAndUtf8RejectBeforeNativeConstruction() {
        byte[] bytes = new RollbackRosterData(20, Map.of(A, player(A, 1, null, List.of()))).encode();
        for (int i = 0; i < bytes.length; i++) {
            byte[] cut = Arrays.copyOf(bytes, i);
            assertThrows(IllegalArgumentException.class, () -> RollbackRosterData.decode(cut));
        }
        assertThrows(IllegalArgumentException.class, () -> RollbackRosterData.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        byte[] count = bytes.clone(); ByteBuffer.wrap(count).putInt(12, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> RollbackRosterData.decode(count));
        byte[] name = bytes.clone(); name[40] = (byte) 0xc0;
        assertThrows(IllegalArgumentException.class, () -> RollbackRosterData.decode(name));
        byte[] mode = bytes.clone(); mode[45] = (byte) 255;
        assertThrows(IllegalArgumentException.class, () -> RollbackRosterData.decode(mode));
        byte[] flag = bytes.clone(); flag[57] = 2;
        assertThrows(IllegalArgumentException.class, () -> RollbackRosterData.decode(flag));
        assertThrows(IllegalArgumentException.class, () -> new Property("textures", "textures", "\ud800", null));
    }
    @Test void aggregateBudgetAppliesEvenWhenEveryIndividualComponentIsValid() {
        var properties = Collections.nCopies(64, new Property("textures", "textures", "x".repeat(RollbackRosterData.MAXIMUM_PROPERTY_BYTES), null));
        var players = new HashMap<UUID, Player>();
        for (int i = 1; i <= 5; i++) { var id = new UUID(0, i); players.put(id, player(id, i, null, properties)); }
        assertThrows(IllegalArgumentException.class, () -> new RollbackRosterData(20, players).encode());
        assertThrows(IllegalArgumentException.class, () -> RollbackRosterData.decode(new byte[RollbackRosterData.MAXIMUM_BYTES + 1]));
    }
    static Player player(UUID id, int entityId, UUID attacker, List<Property> properties) {
        var identity = new Identity(id, entityId, "p", properties, Mode.ADVENTURE,
                new Client("en_us", 8, Chat.SYSTEM, true, 127, Hand.LEFT, false, true, Particles.DECREASED));
        var vector = new Vector(0, 0, 0);
        var context = new RollbackPlayerContext(new RollbackPlayerContext.Abilities(false, false, false, false, true, .05f, .1f),
                new RollbackPlayerContext.Input(true, false, false, false, false, false, false), vector, -100, OptionalLong.empty(), Set.of("duel"), Set.of(), Map.of(), Set.of(), vector);
        var combat = new RollbackPlayerCombatData(id, attacker, null, null, null, false, Map.of(),
                new RollbackPlayerCombatData.Tracker(0, 0, 0, false, false), List.of(), -1, List.of());
        var items = new RollbackPlayerItems(List.of(new RollbackPlayerItems.Item(RollbackItemData.EMPTY, 0)), List.of(0), List.of(), 0, 64, 0, 0, -1, Map.of(), 0, Map.of());
        return new Player(identity, 17, new RollbackPlayerValues(Map.of()), new RollbackPlayerVitals(new byte[]{1}, new byte[]{0}, List.of()), items, context, combat);
    }
}
