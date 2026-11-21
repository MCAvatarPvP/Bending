package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Biome;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Cell;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackBlockEntitiesNativeTest {
    private static final Position POSITION = new Position(0, 0, 0);
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }

    @Test void nativeContentsFollowLogicalSnapshotsAndRetainTheirTileAcrossARewind() {
        var registries = (RegistryAccess) new PaperRollbackDamageNativeTest.Combat().registryAccess();
        var tiles = new PaperRollbackBlockEntities(registries);
        var terrain = PaperRollbackWorldQueriesNativeTest.world().terrain();
        tiles.bind(terrain);
        var state = Blocks.CHEST.defaultBlockState();
        var source = new ChestBlockEntity(BlockPos.ZERO, state);
        source.setItem(0, new ItemStack(Items.DIAMOND, 3));
        var tag = source.saveWithFullMetadata(registries);
        tag.putString("custom_fixture_tag", "preserved");
        var data = new BlockData(Material.CHEST);
        data.setExactState("minecraft:chest");
        terrain.replace(POSITION, new Cell(data, PaperRollbackBlockSnapshots.encode(tag), Biome.DESERT, (byte) 0, .8, .4), false);
        var tile = (ChestBlockEntity) tiles.get(terrain, POSITION, state);
        assertNotSame(source, tile);
        assertEquals(3, tile.getItem(0).getCount());
        terrain.drainChanges();
        var heldStack = tile.getItem(0);
        var saved = terrain.captureRollbackState();

        tile.triggerEvent(1, 1);
        ChestBlockEntity.lidAnimateTick(null, BlockPos.ZERO, state, tile);
        assertTrue(tile.getOpenNess(1) > 0);
        heldStack.setCount(1);
        tile.clearContent();
        var changes = terrain.drainChanges();
        assertEquals(1, changes.size());
        var emptied = PaperRollbackBlockSnapshots.decode(changes.getFirst().value().blockEntity());
        assertEquals("preserved", emptied.getStringOr("custom_fixture_tag", ""));
        var emptyChest = (ChestBlockEntity) tiles.create(POSITION, state, changes.getFirst().value().blockEntity());
        assertTrue(emptyChest.getItem(0).isEmpty());

        var changed = new BlockData(Material.CHEST);
        changed.setExactState("minecraft:chest[facing=south]");
        terrain.block(0, 0, 0).setBlockData(changed, false);
        assertSame(tile, tiles.get(terrain, POSITION, tiles.decodeState(changed)));
        assertSame(tiles.decodeState(changed), tile.getBlockState());
        terrain.restoreRollbackState(saved);
        assertSame(tile, tiles.get(terrain, POSITION, state));
        assertSame(state, tile.getBlockState());
        assertSame(heldStack, tile.getItem(0));
        assertEquals(0, tile.getOpenNess(1));
        assertEquals(3, tile.getItem(0).getCount());
        assertTrue(tile.getItem(0).is(Items.DIAMOND));
        assertTrue(terrain.drainChanges().isEmpty());
    }

    @Test void packedDefaultsMaterializeOnMutationAndForeignMetadataIsRejected() {
        var registries = (RegistryAccess) new PaperRollbackDamageNativeTest.Combat().registryAccess();
        var tiles = new PaperRollbackBlockEntities(registries);
        var state = Blocks.CHEST.defaultBlockState();
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("id", "DUMMY");
        tag.putInt("x", 0);
        tag.putInt("y", 0);
        tag.putInt("z", 0);
        byte[] packed = PaperRollbackBlockSnapshots.encode(tag);
        var tile = (ChestBlockEntity) tiles.create(POSITION, state, packed);
        byte[] before = tiles.encode(tile);
        tile.setItem(1, new ItemStack(Items.EMERALD, 2));
        var changed = PaperRollbackBlockSnapshots.decode(tiles.merge(packed, before, tiles.encode(tile)));
        assertEquals("minecraft:chest", changed.getStringOr("id", ""));
        assertThrows(IllegalArgumentException.class, () -> tiles.create(new Position(1, 2, 3), state, packed));
        changed.putString("id", "minecraft:furnace");
        assertThrows(IllegalArgumentException.class, () -> tiles.create(POSITION, state, PaperRollbackBlockSnapshots.encode(changed)));
    }
}
