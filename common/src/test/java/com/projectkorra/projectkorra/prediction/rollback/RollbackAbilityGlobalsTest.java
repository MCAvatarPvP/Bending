package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.earthbending.Catapult;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.BlockSource;
import com.projectkorra.projectkorra.util.BlockSource.BlockSourceType;
import com.projectkorra.projectkorra.util.BlockSourceInformation;
import com.projectkorra.projectkorra.util.ClickType;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollbackAbilityGlobalsTest {
    @Test void nearbyExistingStandDeclinesWhileAnOutsideStandContinuesNormally() throws Exception {
        var world = new com.projectkorra.projectkorra.platform.mc.World();
        var stand = new com.projectkorra.projectkorra.platform.mc.entity.ArmorStand() {
            @Override public com.projectkorra.projectkorra.platform.mc.Location getLocation() {
                return new com.projectkorra.projectkorra.platform.mc.Location(world, 0, 63, 0);
            }
        };
        var type = com.projectkorra.projectkorra.util.TempArmorStand.class;
        var owner = new org.objenesis.ObjenesisStd().newInstance(type);
        var target = type.getDeclaredField("stand"); target.setAccessible(true); target.set(owner, stand);
        var live = com.projectkorra.projectkorra.util.TempArmorStand.getTempStands();
        live.add(owner);
        try {
            var state = new RollbackAbilityGlobals();
            var near = new com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds(-2, 60, -2, 2, 65, 2);
            var far = new com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds(100, 60, 100, 103, 65, 103);
            assertThrows(IllegalStateException.class, () -> state.requireNoInitialActors(world, near, Set.of()));
            state.requireNoInitialActors(world, far, Set.of());
            assertTrue(live.contains(owner));
        } finally { live.remove(owner); }
    }

    @Test void enrollmentRewindRepairAndHandoffOnlyTouchTheirParticipant() {
        var owner = player(23); var outsider = player(42);
        var block = new Block();
        var outsideSource = source(outsider, block);
        var ownerRestriction = new com.projectkorra.projectkorra.util.MovementHandler(owner, null);
        var outsideRestriction = new com.projectkorra.projectkorra.util.MovementHandler(outsider, null);
        com.projectkorra.projectkorra.util.MovementHandler.handlers.add(ownerRestriction);
        Catapult.restoreRollbackLanding(owner.getUniqueId(), true);
        BlockSource.restoreRollbackSources(owner, source(owner, block));
        try {
            var codec = new RollbackGraphCodec(new RollbackGraphCodec.Catalog(List.of(RollbackAbilityGlobals.class, RollbackParticipantStatuses.class, BlockSourceInformation.class,
                    com.projectkorra.projectkorra.util.MovementHandler.class, com.projectkorra.projectkorra.platform.mc.Location.class),
                    List.of(BlockSourceType.class, ClickType.class), List.of(new RollbackGraphCodec.Binding("player", Player.class, owner),
                    new RollbackGraphCodec.Binding("block", Block.class, block))), new RollbackGraphCodec.Limits(256, 2048, 32768, 1024));
            var seed = RollbackAbilityGlobals.capture(List.of(owner));
            var incoming = (RollbackAbilityGlobals)codec.decode(codec.encode(List.of(seed))).getFirst();
            var graph = new RollbackStateGraph(Set.of(owner, block)::contains, field -> true, 256);
            var platform = (ProjectKorraPlatform) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ProjectKorraPlatform.class},
                    (proxy, method, args) -> { throw new AssertionError(method); });
            var domain = RollbackDomain.create(graph, List.of(), List.of(), platform, () -> RollbackDomain.installAbilityGlobals(incoming));
            BlockSource.restoreRollbackSources(outsider, outsideSource);
            com.projectkorra.projectkorra.util.MovementHandler.handlers.add(outsideRestriction);
            Catapult.restoreRollbackLanding(outsider.getUniqueId(), true);
            var checkpoint = domain.call(domain::capture);
            domain.call(() -> {
                assertTrue(Catapult.isAwaitingLanding(owner));
                assertPrivateRestriction(RollbackParticipantStatuses.current().movement(), owner, ownerRestriction);
                RollbackParticipantStatuses.current().movement().clear();
                assertFalse(Catapult.isAwaitingLanding(outsider));
                assertNull(BlockSource.captureRollbackSources(outsider));
                assertSame(block, BlockSource.getBlockSourceInformation(owner, BlockSourceType.EARTH, ClickType.LEFT_CLICK).getBlock());
                Catapult.discardAllTracking();
                BlockSource.clearAll();
                return null;
            });
            assertTrue(Catapult.isAwaitingLanding(owner));
            var settled = domain.call(() -> {
                domain.restore(checkpoint);
                assertTrue(Catapult.isAwaitingLanding(owner));
                assertPrivateRestriction(RollbackParticipantStatuses.current().movement(), owner, ownerRestriction);
                RollbackParticipantStatuses.current().movement().clear();
                BlockSource.removeSource(owner, BlockSourceType.EARTH, ClickType.LEFT_CLICK);
                Catapult.discardAllTracking();
                return RollbackDomain.abilityGlobals();
            });
            assertNotNull(BlockSource.getBlockSourceInformation(owner, BlockSourceType.EARTH, ClickType.LEFT_CLICK));
            settled.merge(Map.of(owner.getUniqueId(), owner));
            assertTrue(com.projectkorra.projectkorra.util.MovementHandler.handlers.contains(outsideRestriction));
            assertFalse(com.projectkorra.projectkorra.util.MovementHandler.handlers.contains(ownerRestriction));
            assertFalse(Catapult.isAwaitingLanding(owner));
            assertTrue(Catapult.isAwaitingLanding(outsider));
            assertNull(BlockSource.getBlockSourceInformation(owner, BlockSourceType.EARTH, ClickType.LEFT_CLICK));
            assertSame(outsideSource.get(BlockSourceType.EARTH).get(ClickType.LEFT_CLICK),
                    BlockSource.getBlockSourceInformation(outsider, BlockSourceType.EARTH, ClickType.LEFT_CLICK));
            BlockSource.clearAll();
            assertNull(BlockSource.captureRollbackSources(owner));
            assertNull(BlockSource.captureRollbackSources(outsider));
            Catapult.discardAllTracking();
            assertFalse(Catapult.isAwaitingLanding(outsider));
        } finally {
            com.projectkorra.projectkorra.util.MovementHandler.handlers.remove(ownerRestriction);
            com.projectkorra.projectkorra.util.MovementHandler.handlers.remove(outsideRestriction);
            for (var player : List.of(owner, outsider)) {
                BlockSource.restoreRollbackSources(player, null);
                Catapult.restoreRollbackLanding(player.getUniqueId(), false);
            }
        }
    }
    private static void assertPrivateRestriction(Set<com.projectkorra.projectkorra.util.MovementHandler> selected, Player owner,
            com.projectkorra.projectkorra.util.MovementHandler original) {
        assertEquals(1, selected.size());
        var imported = selected.iterator().next();
        assertSame(owner, imported.getEntity());
        assertNotSame(original, imported);
    }
    private static HashMap<BlockSourceType, HashMap<ClickType, BlockSourceInformation>> source(Player player, Block block) {
        var hits = new HashMap<ClickType, BlockSourceInformation>();
        hits.put(ClickType.LEFT_CLICK, new BlockSourceInformation(player, block, BlockSourceType.EARTH, ClickType.LEFT_CLICK));
        var result = new HashMap<BlockSourceType, HashMap<ClickType, BlockSourceInformation>>();
        result.put(BlockSourceType.EARTH, hits);
        return result;
    }
    private static Player player(int id) {
        return new Player() { @Override public UUID getUniqueId() { return new UUID(0, id); } };
    }
}
