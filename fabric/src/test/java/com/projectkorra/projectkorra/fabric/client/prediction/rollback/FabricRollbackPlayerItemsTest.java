package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.mojang.authlib.GameProfile;
import com.projectkorra.projectkorra.fabric.mixin.client.ItemCooldownRollbackAccess;
import com.projectkorra.projectkorra.fabric.mixin.client.LivingEntityRollbackItemAccess;
import com.projectkorra.projectkorra.fabric.mixin.client.PlayerEntityRollbackItemAccess;
import com.projectkorra.projectkorra.prediction.rollback.RollbackEngine;
import com.projectkorra.projectkorra.prediction.rollback.RollbackSimulation;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStep;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerItems;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateGraph;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemData;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackPlayerItemsTest {
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }

    @Test void actualPaperItemGraphImportsAndRewindsAliasesIndependentCopiesLimitsAndCooldowns() throws Exception {
        var seed = RollbackPlayerItems.decode(fixture()); var state = player(); var other = player();
        assertEquals(64, other.<Integer>use(value -> value.getInventory().getMaxCountPerStack()));
        FabricRollbackPlayerItems.apply(state, seed);
        assertComponent(seed, FabricRollbackPlayerItems.capture(state), state);
        byte[] importedBytes = FabricRollbackPlayerItems.capture(state).encode();
        state.use(value -> {
            var stack = value.getInventory().getStack(3); var twin = value.getInventory().getStack(4);
            assertNotSame(stack, twin); assertTrue(ItemStack.areEqual(stack, twin));
            assertEquals(Items.DIAMOND_SWORD, stack.getItem()); assertEquals(7, stack.getDamage());
            assertEquals("captured", stack.get(DataComponentTypes.CUSTOM_NAME).getString()); assertEquals(4, stack.getBobbingAnimationTime());
            assertSame(stack, value.getInventory().getStack(8)); assertSame(stack, value.getEnderChestInventory().getStack(5));
            assertSame(stack, value.getActiveItem()); assertSame(stack, ((PlayerEntityRollbackItemAccess) value).rollback$lastItem());
            assertSame(twin, ((LivingEntityRollbackItemAccess) value).rollback$spinItem()); assertSame(twin, value.getInventory().getStack(40));
            assertSame(stack, ((LivingEntityRollbackItemAccess) value).rollback$lastEquipment().get(EquipmentSlot.MAINHAND));
            assertEquals(Items.DIAMOND_BOOTS, value.getInventory().getStack(36).getItem());
            assertEquals(3, value.getInventory().getSelectedSlot()); assertEquals(32, value.getInventory().getMaxCountPerStack());
            assertEquals(.8F, value.getItemCooldownManager().getCooldownProgress(new ItemStack(Items.ENDER_PEARL), 0));
            return null;
        });
        FabricRollbackPlayerItems.apply(other, seed);
        assertNotSame(state.use(value -> value.getInventory().getStack(3)), other.use(value -> value.getInventory().getStack(3)));
        var checkpoint = new RollbackStateGraph(value -> false, field -> true, 300_000).capture(List.of(state), List.of());
        state.use(value -> {
            value.getInventory().getStack(3).setDamage(18);
            value.getInventory().setSelectedSlot(4); ((FabricRollbackInventoryLimit) value.getInventory()).rollback$maximumStack(64);
            for (int tick = 0; tick < 32; tick++) value.getItemCooldownManager().update();
            assertFalse(value.getItemCooldownManager().isCoolingDown(new ItemStack(Items.ENDER_PEARL)));
            return null;
        });
        assertFalse(Arrays.equals(importedBytes, FabricRollbackPlayerItems.capture(state).encode()));
        assertComponent(seed, FabricRollbackPlayerItems.capture(other), other);
        checkpoint.restore();
        assertArrayEquals(importedBytes, FabricRollbackPlayerItems.capture(state).encode());
        assertEquals(18, state.<Integer>use(value -> ((ItemCooldownRollbackAccess) value.getItemCooldownManager()).rollback$tick()));
        state.use(value -> {
            assertSame(value.getActiveItem(), value.getInventory().getStack(3));
            assertEquals(.8F, value.getItemCooldownManager().getCooldownProgress(new ItemStack(Items.ENDER_PEARL), 0));
            return null;
        });
    }

    @Test void malformedNativeItemsEquipmentAndLayoutRejectBeforeTargetMutationAndReplayDisallowsImport() throws Exception {
        var seed = RollbackPlayerItems.decode(fixture()); var state = player();
        byte[] before = FabricRollbackPlayerItems.capture(state).encode();
        var table = new ArrayList<>(seed.items());
        table.set(seed.useItem(), new RollbackPlayerItems.Item(new RollbackItemData(new byte[]{99}), 0));
        assertThrows(RuntimeException.class, () -> FabricRollbackPlayerItems.apply(state, copy(seed, table, seed.inventory(), seed.lastEquipment())));
        var equipment = new TreeMap<>(seed.lastEquipment()); equipment.put("WRONG", seed.useItem());
        assertThrows(IllegalArgumentException.class, () -> FabricRollbackPlayerItems.apply(state, copy(seed, seed.items(), seed.inventory(), equipment)));
        assertThrows(IllegalArgumentException.class, () -> FabricRollbackPlayerItems.apply(state, copy(seed, seed.items(), seed.inventory().subList(0, 42), seed.lastEquipment())));
        var simulation = new RollbackSimulation<Boolean, Boolean, Boolean>() {
            @Override public Boolean snapshot() { return true; }
            @Override public void restore(Boolean ignored) { }
            @Override public Boolean predict(UUID player, Boolean previous) { return previous; }
            @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<Boolean> effects) {
                assertThrows(IllegalStateException.class, () -> FabricRollbackPlayerItems.apply(state, seed));
                assertThrows(IllegalStateException.class, () -> FabricRollbackPlayerItems.capture(state));
            }
        };
        new RollbackEngine<>(simulation, Map.of(new UUID(0, 451), false), new RollbackEngine.Limits(1, 1, 4, 50_000_000), 0).advance();
        assertArrayEquals(before, FabricRollbackPlayerItems.capture(state).encode());
    }

    private static RollbackPlayerItems copy(RollbackPlayerItems seed, List<RollbackPlayerItems.Item> table, List<Integer> inventory, Map<String, Integer> equipment) {
        return new RollbackPlayerItems(table, inventory, seed.enderChest(), seed.selected(), seed.maximumStack(), seed.useItem(), seed.lastItem(), seed.spinItem(), equipment, seed.cooldownTick(), seed.cooldowns());
    }
    private static void assertComponent(RollbackPlayerItems expected, RollbackPlayerItems actual, FabricRollbackNativePlayerState state) {
        assertEquals(expected.items().size(), actual.items().size());
        state.use(player -> {
            var codec = new FabricRollbackItemCodec(player.getEntityWorld().getRegistryManager());
            for (int i = 0; i < expected.items().size(); i++) {
                var first = expected.items().get(i); var second = actual.items().get(i);
                assertEquals(first.popTime(), second.popTime());
                assertTrue(ItemStack.areEqual(codec.decode(first.data()), codec.decode(second.data())));
            }
            return null;
        });
        // Native NBT key ordering is not part of component equality; every root/index is.
        assertArrayEquals(expected.encode(), copy(actual, expected.items(), actual.inventory(), actual.lastEquipment()).encode());
    }
    private byte[] fixture() throws Exception {
        try (var resource = Objects.requireNonNull(getClass().getResourceAsStream("/rollback/player-items.base64"))) {
            return Base64.getMimeDecoder().decode(resource.readAllBytes());
        }
    }
    private static FabricRollbackNativePlayerState player() {
        var world = new FabricRollbackWorldAccess(new FabricRollbackWorldAccessTest.Queries());
        return new FabricRollbackNativePlayerState(new Player(world.world()), world, 300_000);
    }
    private static final class Player extends PlayerEntity {
        Player(World world) { super(world, new GameProfile(new UUID(0, 451), "imported")); }
        @Override public GameMode getGameMode() { return GameMode.SURVIVAL; }
    }
}
