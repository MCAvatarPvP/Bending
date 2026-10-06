package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.*;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.CraftBlockStates;
import org.junit.jupiter.api.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackBlockSnapshotsNativeTest {
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }
    @Test void chestSnapshotTransfersSavedContentsInsteadOfLaterSourceContents() throws Exception {
        var registries = (RegistryAccess) new PaperRollbackDamageNativeTest.Combat().registryAccess();
        var nativeWorld = RollbackNativeQueryShell.create(ServerLevel.class)
                .constant(ServerLevel::registryAccess, registries).instance();
        var world = RollbackNativeQueryShell.create(CraftWorld.class)
                .constant(CraftWorld::getHandle, nativeWorld).constant(CraftWorld::getUID, UUID.randomUUID()).instance();
        var position = new BlockPos(0, 0, 0);
        var chest = new ChestBlockEntity(position, Blocks.CHEST.defaultBlockState());
        chest.setItem(0, new net.minecraft.world.item.ItemStack(Items.DIAMOND, 3));
        var saved = CraftBlockStates.getBlockState(world, position, Blocks.CHEST.defaultBlockState(), chest);
        chest.setItem(0, new net.minecraft.world.item.ItemStack(Items.DIRT, 9));
        var wrapped = BukkitMC.blockState(saved);
        var target = new Arena();
        var sender = codec(BukkitMC.world(world)); var receiver = codec(target);
        var copies = receiver.decode(sender.encode(List.of(wrapped, BukkitMC.blockState(saved))));
        var copy = (BlockState) copies.getFirst();
        assertSame(copy, copies.get(1)); assertTrue(copy.hasBlockEntity());
        assertFalse(copy.update(false, false));
        assertTrue(copy.update(true, false));
        var bytes = target.terrain.cell(new Position(0, 0, 0)).blockEntity();
        var tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)), NbtAccounter.unlimitedHeap());
        var restored = (ChestBlockEntity) net.minecraft.world.level.block.entity.BlockEntity.loadStatic(position,
                Blocks.CHEST.defaultBlockState(), tag, registries);
        assertNotNull(restored); assertEquals(3, restored.getItem(0).getCount()); assertTrue(restored.getItem(0).is(Items.DIAMOND));
        assertTrue(chest.getItem(0).is(Items.DIRT));
        assertEquals((byte) 11, target.getBlockAt(0, 0, 0).getLightLevel());
        var unplaced = (org.bukkit.block.BlockState) Proxy.newProxyInstance(org.bukkit.block.BlockState.class.getClassLoader(),
                new Class<?>[]{org.bukkit.block.BlockState.class}, (proxy, method, args) -> {
                    if (method.getName().equals("isPlaced")) return false;
                    throw new AssertionError(method);
                });
        assertThrows(IllegalArgumentException.class, () -> PaperRollbackBlockSnapshots.capture(unplaced));
    }
    private static RollbackGraphCodec codec(World world) {
        var catalog = RollbackGameplayCatalog.create(RollbackGameplayCatalog.installed(PaperRollbackBlockSnapshotsNativeTest.class.getClassLoader()),
                List.of(new RollbackGraphCodec.Binding("world", World.class, world)));
        return new RollbackGraphCodec(catalog, new RollbackGraphCodec.Limits(1000, 10000, 1000000, 10000),
                ignored -> null, new PaperRollbackGraphViews());
    }
    private static final class Arena extends World {
        final RollbackBlockStore terrain;
        Arena() {
            Rules rules = (Rules) Proxy.newProxyInstance(Rules.class.getClassLoader(), new Class<?>[]{Rules.class},
                    (proxy, method, args) -> {
                        if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                        throw new AssertionError(method);
                    });
            terrain = new RollbackBlockStore(this, new Bounds(0, 0, 0, 1, 1, 1), Map.of(),
                    new Cell(Material.AIR.createBlockData(), null, Biome.DESERT, (byte) 11, 0.8, 0.4), rules, 20);
        }
        @Override public Block getBlockAt(int x, int y, int z) { return terrain.block(x, y, z); }
    }
}

