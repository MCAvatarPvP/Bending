package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.redstone.NeighborUpdater;
import org.bukkit.craftbukkit.block.data.CraftBlockData;

import java.util.Collection;
import java.util.List;

/** Native block callbacks confined to the match's terrain, item owner and private level. */
public final class PaperRollbackBlockRules implements Rules, RollbackStateCell<Void> {
    private final PaperRollbackBlockStates states = new PaperRollbackBlockStates();
    private final RollbackNativeItems<net.minecraft.world.item.ItemStack> items;
    private PaperRollbackGeometry geometry;
    private RollbackBlockStore terrain;
    private ServerLevel world;
    private boolean alternativeLuckFormula;

    public PaperRollbackBlockRules(RollbackNativeItems<net.minecraft.world.item.ItemStack> items) {
        this.items = java.util.Objects.requireNonNull(items);
    }

    void bind(PaperRollbackNativeScene scene, PaperRollbackGeometry geometry) {
        if (world != null) throw new IllegalStateException("Terrain rules already bound");
        terrain = scene.logical().terrain();
        world = scene.world().world();
        this.geometry = geometry;
        alternativeLuckFormula = scene.services().settings().policy().alternativeLuckFormula();
    }
    private void check(RollbackBlockStore store) {
        if (terrain != store || world == null) throw new IllegalArgumentException("Terrain belongs to another scene");
        items.requireOwnerThread();
    }
    @Override public Geometry geometry(RollbackBlockStore store, Position position, BlockData data) {
        check(store);
        return geometry.geometry(store, position, data);
    }
    @Override public byte legacyData(RollbackBlockStore store, Position position, BlockData data) {
        check(store);
        return geometry.legacyData(data);
    }

    @Override public void physics(RollbackBlockStore store, Position position) {
        throw new IllegalStateException("Native physics requires the departing block state");
    }
    @Override public void physics(RollbackBlockStore store, Position position, Cell before, Cell after) {
        check(store);
        var pos = pos(position);
        var old = states.decode(before.data());
        var current = states.decode(after.data());
        if (old == current) return;
        if (old.getBlock() != current.getBlock()) {
            old.affectNeighborsAfterRemoval(world, pos, false);
            current.onPlace(world, pos, old, false);
        }
        for (var direction : NeighborUpdater.UPDATE_ORDER) {
            var neighbor = pos.relative(direction);
            world.getBlockState(neighbor).handleNeighborChanged(world, neighbor, old.getBlock(), null, false);
        }
        if (current.hasAnalogOutputSignal()) world.updateNeighbourForOutputSignal(pos, current.getBlock());
        old.updateIndirectNeighbourShapes(world, pos, Block.UPDATE_CLIENTS, Block.UPDATE_LIMIT - 1);
        current.updateNeighbourShapes(world, pos, Block.UPDATE_CLIENTS, Block.UPDATE_LIMIT - 1);
        current.updateIndirectNeighbourShapes(world, pos, Block.UPDATE_CLIENTS, Block.UPDATE_LIMIT - 1);
    }

    @Override public Collection<ItemStack> drops(RollbackBlockStore store, Position position, ItemStack tool) {
        check(store);
        var pos = pos(position);
        var state = world.getBlockState(pos);
        var nativeTool = tool == null ? net.minecraft.world.item.ItemStack.EMPTY : items.nativeCopy(tool);
        if (tool != null && !CraftBlockData.isPreferredTool(state, nativeTool)) return List.of();
        if (io.papermc.paper.configuration.GlobalConfiguration.get().misc.useAlternativeLuckFormula != alternativeLuckFormula)
            throw new IllegalStateException("Paper loot policy changed during the match");
        return Block.getDrops(state, world, pos, world.getBlockEntity(pos), null, nativeTool).stream().map(items::capture).toList();
    }
    @Override public boolean breakNaturally(RollbackBlockStore store, Position position, ItemStack tool) {
        check(store);
        var pos = pos(position);
        var state = world.getBlockState(pos);
        var nativeTool = tool == null ? net.minecraft.world.item.ItemStack.EMPTY : items.nativeCopy(tool);
        boolean harvested = !state.is(net.minecraft.world.level.block.Blocks.AIR)
                && (tool == null || CraftBlockData.isPreferredTool(state, nativeTool));
        if (harvested) {
            for (var drop : drops(store, position, tool)) {
                var random = world.getRandom();
                var point = new Location(store.world(), pos.getX() + 0.25 + random.nextDouble() * 0.5,
                        pos.getY() + 0.25 + random.nextDouble() * 0.5 - net.minecraft.world.entity.EntityType.ITEM.getHeight() / 2,
                        pos.getZ() + 0.25 + random.nextDouble() * 0.5);
                if (world.getGameRules().get(GameRules.BLOCK_DROPS)) store.world().dropItem(point, drop).setPickupDelay(10);
            }
            state.spawnAfterBreak(world, pos, nativeTool, false);
        }
        boolean removed = write(pos, state.getFluidState().createLegacyBlock(), true);
        if (removed) state.getBlock().destroy(world, pos, state);
        if (harvested && state.getBlock() instanceof IceBlock ice) ice.afterDestroy(world, pos, nativeTool);
        if (harvested && state.getBlock() instanceof TurtleEggBlock eggs) eggs.decreaseEggs(world, pos, state);
        return removed && harvested;
    }
    boolean write(BlockPos pos, BlockState state, boolean physics) {
        if (world.getBlockState(pos) == state) return false;
        var craft = CraftBlockData.fromData(state);
        var data = BukkitMC.blockData(craft);
        data.setExactState(craft.getAsString());
        terrain.block(pos.getX(), pos.getY(), pos.getZ()).setBlockData(data, physics);
        return true;
    }
    private static BlockPos pos(Position position) { return new BlockPos(position.x(), position.y(), position.z()); }
    @Override public Void captureRollbackState() { check(terrain); return null; }
    @Override public void restoreRollbackState(Void state) { check(terrain); }
}
