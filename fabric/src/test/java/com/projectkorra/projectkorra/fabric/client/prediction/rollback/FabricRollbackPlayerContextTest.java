package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateGraph;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Box;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackMovementSolver.*;
import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.EntityShapeContext;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackPlayerContextTest {
    @BeforeAll static void bootstrap() {
        FabricRollbackTestRegistry.bootstrap();
    }

    @Test void nativePowderSnowClippingFollowsRestoredEquipmentDescentAndFallState() {
        var scene = new Scene();
        scene.block(Material.POWDER_SNOW, "minecraft:powder_snow");
        assertEquals(-0.2, scene.fall(), 1e-8);
        scene.inventory.setItem(scene.inventory.layout().boots(), scene.items.create(Material.LEATHER_BOOTS, 1));
        var before = scene.capture();
        assertEquals(0, scene.fall(), 1e-8);
        scene.player.state().controls(scene.player.state().controls().with(RollbackPlayerState.Flag.SNEAKING, true));
        assertEquals(-0.2, scene.fall(), 1e-8);
        scene.player.setFallDistance(3);
        assertEquals(-0.1, scene.fall(), 1e-6); // Native falling shape is 0.9F tall.
        before.restore();
        assertEquals(0, scene.fall(), 1e-8);
        scene.inventory.setItem(scene.inventory.layout().boots(), scene.items.create(Material.DIAMOND_BOOTS, 1));
        assertEquals(-0.2, scene.fall(), 1e-8);
        before.restore();
        scene.pose(0.8);
        assertEquals(-0.2, scene.fall(), 1e-8); // Boots do not create a floor when already inside.
        before.restore();
        assertEquals(0, scene.fall(), 1e-8);
    }

    @Test void contextUsesActualNativePlayerAndRestoresSelectedHandWithoutNativeWorldAccess() {
        var scene = new Scene();
        scene.inventory.setItem(2, scene.items.create(Material.STICK, 1));
        scene.inventory.setHeldItemSlot(2);
        var before = scene.capture();
        scene.context.query(value -> {
            var context = (EntityShapeContext) value;
            var entity = context.getEntity();
            assertInstanceOf(PlayerEntity.class, entity);
            assertEquals(EntityType.PLAYER, entity.getType());
            assertEquals(scene.player.getUniqueId(), entity.getUuid());
            assertEquals(scene.player.getEntityId(), entity.getId());
            assertEquals(1, entity.getY());
            assertFalse(context.isDescending());
            assertFalse(context.isPlacement());
            assertTrue(context.isHolding(Items.STICK));
            assertThrows(IllegalStateException.class, () -> entity.getEntityWorld().getBlockState(BlockPos.ORIGIN));
            return null;
        });
        scene.inventory.setHeldItemSlot(0);
        scene.inventory.setItem(0, scene.items.create(Material.STONE, 1));
        assertTrue(scene.context.<Boolean>query(value -> (value).isHolding(Items.STONE)));
        before.restore();
        assertTrue(scene.context.<Boolean>query(value -> (value).isHolding(Items.STICK)));
        assertEquals(Material.STICK, scene.inventory.getItem(2).getType());
        assertNull(scene.inventory.getItem(0));
    }

    @Test void nativeScaffoldingAndQueryGuardsUseTheCurrentLogicalPlayer() {
        var scene = new Scene();
        scene.block(Material.SCAFFOLDING, "minecraft:scaffolding[bottom=false,distance=0,waterlogged=false]");
        assertEquals(0, scene.fall(), 1e-8);
        scene.player.state().controls(scene.player.state().controls().with(RollbackPlayerState.Flag.SNEAKING, true));
        assertEquals(-0.2, scene.fall(), 1e-8);
        assertThrows(IllegalStateException.class, () -> scene.context.query(value -> scene.context.query(nested -> null)));
        assertThrows(IllegalStateException.class, () -> scene.context.query(value -> scene.capture()));
        var other = new Scene();
        assertThrows(IllegalArgumentException.class, () -> scene.context.movementColliders(other.world.shapes, other.world.terrain,
                new Box(0, 0, 0, 1, 1, 1)));
        assertEquals(-0.2, scene.fall(), 1e-8); // Exception unwinds the query guard.
        var failure = assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(scene::fall).join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        var before = scene.capture();
        scene.player.remove();
        assertThrows(IllegalStateException.class, scene::fall);
        before.restore();
        assertEquals(-0.2, scene.fall(), 1e-8);
    }


    @Test void lateDescentInputReplaysNativeContextsAndCorrectsTheMovementPath() {
        var onTime = new DescentSimulation();
        var late = new DescentSimulation();
        UUID participant = onTime.scene.player.getUniqueId();
        var limits = new com.projectkorra.projectkorra.prediction.rollback.RollbackEngine.Limits(4, 2, 100, 50_000_000);
        var first = new com.projectkorra.projectkorra.prediction.rollback.RollbackEngine<>(onTime, Map.of(participant, false), limits, 0);
        var second = new com.projectkorra.projectkorra.prediction.rollback.RollbackEngine<>(late, Map.of(participant, false), limits, 0);
        first.submit(participant, 1, true);
        for (int tick = 1; tick <= 3; tick++) { first.advance(); second.advance(); }
        assertEquals(0.4, onTime.scene.player.getLocation().getY(), 1e-8);
        assertEquals(1, late.scene.player.getLocation().getY());
        second.submit(participant, 1, true);
        assertEquals(1, second.reconcile().replayedFrom());
        assertEquals(onTime.scene.player.getLocation().getY(), late.scene.player.getLocation().getY());
        assertEquals(first.head().effects(), second.head().effects());
        for (int tick = 4; tick <= 7; tick++) {
            assertEquals(first.advance().finalizedEffects(), second.advance().finalizedEffects());
        }
    }

    private static final class DescentSimulation implements com.projectkorra.projectkorra.prediction.rollback.RollbackSimulation<RollbackStateGraph.Snapshot, Boolean, Double> {
        final Scene scene = new Scene();
        DescentSimulation() {
            scene.block(Material.POWDER_SNOW, "minecraft:powder_snow");
            scene.inventory.setItem(scene.inventory.layout().boots(), scene.items.create(Material.LEATHER_BOOTS, 1));
        }
        @Override public RollbackStateGraph.Snapshot snapshot() { return scene.capture(); }
        @Override public void restore(RollbackStateGraph.Snapshot snapshot) { snapshot.restore(); }
        @Override public Boolean predict(UUID player, Boolean previous) { return previous; }
        @Override public void step(long tick, Map<UUID, Boolean> inputs, com.projectkorra.projectkorra.prediction.rollback.RollbackStep<Double> effects) {
            scene.player.state().controls(scene.player.state().controls().with(RollbackPlayerState.Flag.SNEAKING, inputs.get(scene.player.getUniqueId())));
            // Requested displacement is an explicit fixture, not native gravity/travel.
            scene.pose(scene.player.getLocation().getY() + scene.fall());
            effects.emit(scene.player.getLocation().getY());
        }
    }

    private static final class Scene implements Source<net.minecraft.util.shape.VoxelShape> {
        final FabricRollbackGeometryTest.LogicalWorld world =
                new FabricRollbackGeometryTest.LogicalWorld();
        final RollbackNativeItems<ItemStack> items = new RollbackNativeItems<>(new FabricRollbackItems(
                BuiltinRegistries.createWrapperLookup(), Map.of("minecraft:air", RollbackNativeItems.Kind.GENERIC, "minecraft:stone", RollbackNativeItems.Kind.GENERIC, "minecraft:stick", RollbackNativeItems.Kind.GENERIC, "minecraft:leather_boots", RollbackNativeItems.Kind.LEATHER, "minecraft:diamond_boots", RollbackNativeItems.Kind.GENERIC)));
        final RollbackInventory inventory = new RollbackInventory(FabricRollbackInventory.layout(), items,
                new com.projectkorra.projectkorra.platform.mc.inventory.ItemStack[43], 0, 64);
        final RollbackPlayer player;
        final FabricRollbackPlayerContext context;
        final RollbackMovementSolver<net.minecraft.util.shape.VoxelShape> solver =
                new RollbackMovementSolver<>(new FabricRollbackMovement(), Limits.standard());
        Scene() {
            var body = new RollbackEntityBody(new Identity(new UUID(0, 4), 4, "query-player",
                    com.projectkorra.projectkorra.platform.mc.entity.EntityType.PLAYER), world,
                    new Kinematics(new Pose(0.5, 1, 0.5, 0, 0), new Motion(0, 0, 0),
                            new Box(-0.3, 0, -0.3, 0.3, 1.8, 0.3), 1.8, true, 0, false),
                    noCalls(RollbackEntityBody.Rules.class));
            var living = new RollbackLivingState(body, new RollbackLivingState.Vitals(20, 0, 1.62, 300, 300, 0, 20, 0, true),
                    Map.of(Attribute.MAX_HEALTH.name(), 20D), List.of(), new RollbackEquipment(inventory), noCalls(RollbackLivingState.Rules.class));
            player = new RollbackPlayer(new RollbackPlayerState(living, inventory,
                    new RollbackPlayerState.Controls(Set.of(), 0.1F, 0, 0),
                    new RollbackPlayerState.Profile("query-player", "SURVIVAL", "RIGHT", true, false, true, 100),
                    Set.of(), new Scoreboard(), noCalls(RollbackPlayerState.Rules.class)));
            context = new FabricRollbackPlayerContext(player, items);
        }
        void pose(double y) {
            var old = player.body().kinematics();
            player.body().kinematics(new Kinematics(new Pose(0.5, y, 0.5, 0, 0), old.velocity(), old.bounds(),
                    old.height(), old.onGround(), old.fallDistance(), old.velocityChanged()));
        }
        void block(Material material, String exact) {
            var data = new BlockData(material); data.setExactState(exact);
            world.getBlockAt(0, 0, 0).setBlockData(data, false);
        }
        RollbackStateGraph.Snapshot capture() {
            return new RollbackStateGraph(value -> false, field -> true, 10_000).capture(List.of(context), List.of());
        }
        double fall() {
            var bounds = player.getBoundingBox();
            var box = new Box(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(), bounds.getMaxX(), bounds.getMaxY(), bounds.getMaxZ());
            return solver.resolve(new Input(box, new Motion(0, -0.2, 0), 0, true), this).displacement().y();
        }
        @Override public List<Box> entities(Box bounds) { return List.of(); }
        @Override public Colliders<net.minecraft.util.shape.VoxelShape> terrainAndBorder(Box bounds) {
            return new Colliders<>(context.movementColliders(world.shapes, world.terrain, bounds), List.of());
        }
    }
    private static <T> T noCalls(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            throw new AssertionError("Query invoked a gameplay service: " + method);
        }));
    }
}
