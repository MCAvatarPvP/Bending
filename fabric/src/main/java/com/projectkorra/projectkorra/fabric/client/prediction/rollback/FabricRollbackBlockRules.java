package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell;
import com.projectkorra.projectkorra.prediction.rollback.RollbackWorldSettings;
import com.projectkorra.projectkorra.platform.fabric.FabricMC;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeItems;
import net.minecraft.util.math.BlockPos;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.block.Block;
import net.minecraft.block.IceBlock;
import net.minecraft.block.TurtleEggBlock;
import net.minecraft.block.BlockState;
import net.minecraft.world.rule.GameRules;
import net.minecraft.world.block.NeighborUpdater;
import net.minecraft.registry.tag.EnchantmentTags;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.world.attribute.EnvironmentAttributes;
import com.projectkorra.projectkorra.fabric.mixin.client.TurtleEggRollbackAccess;

import java.util.Collection;
import java.util.List;

/** Native block callbacks confined to the match's terrain, item owner and private level. */
public final class FabricRollbackBlockRules implements Rules, RollbackStateCell<Void> {
    private RollbackWorldSettings settings;
    private final RollbackNativeItems<net.minecraft.item.ItemStack> items;
    private FabricRollbackGeometry geometry;
    private RollbackBlockStore terrain;
    private record NativeUpdate(int flags, int remaining) { }
    private NativeUpdate pending;
    private ServerWorld world;

    public FabricRollbackBlockRules(RollbackNativeItems<net.minecraft.item.ItemStack> items) {
        this.items = java.util.Objects.requireNonNull(items);
    }

    void bind(FabricRollbackNativeScene scene, FabricRollbackGeometry geometry) {
        if (world != null) throw new IllegalStateException("Terrain rules already bound");
        terrain = scene.logical().terrain();
        world = (ServerWorld) scene.roster().world().world();
        settings = scene.services().settings();
        this.geometry = geometry;
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
        return settings.legacyBlockData().getOrDefault(BlockArgumentParser.stringifyBlockState(FabricRollbackGeometry.decode(data)), (byte) 0);
    }

    @Override public void physics(RollbackBlockStore store, Position position) {
        throw new IllegalStateException("Native physics requires the departing block state");
    }
    @Override public void physics(RollbackBlockStore store, Position position, Cell before, Cell after) {
        check(store);
        NativeUpdate update = pending;
        pending = null;
        int flags = update == null ? Block.NOTIFY_ALL : update.flags();
        int remaining = update == null ? net.minecraft.world.World.MAX_UPDATE_DEPTH : update.remaining();
        var pos = pos(position);
        var old = FabricRollbackGeometry.decode(before.data());
        var current = FabricRollbackGeometry.decode(after.data());
        if (old == current) return;
        if (old.getBlock() != current.getBlock()) {
            old.onStateReplaced(world, pos, false);
            current.onBlockAdded(world, pos, old, false);
        }
        if ((flags & Block.NOTIFY_NEIGHBORS) != 0) {
            for (var direction : NeighborUpdater.UPDATE_ORDER) {
                var neighbor = pos.offset(direction);
                world.getBlockState(neighbor).neighborUpdate(world, neighbor, old.getBlock(), null, false);
            }
            if (current.hasComparatorOutput()) world.updateComparators(pos, current.getBlock());
        }
        if ((flags & Block.FORCE_STATE) == 0 && remaining > 0) {
            int shapeFlags = flags & ~(Block.NOTIFY_NEIGHBORS | Block.SKIP_DROPS);
            old.prepare(world, pos, shapeFlags, remaining - 1);
            current.updateNeighbors(world, pos, shapeFlags, remaining - 1);
            current.prepare(world, pos, shapeFlags, remaining - 1);
        }
    }

    @Override public Collection<ItemStack> drops(RollbackBlockStore store, Position position, ItemStack tool) {
        check(store);
        var pos = pos(position);
        var state = world.getBlockState(pos);
        var nativeTool = tool == null ? net.minecraft.item.ItemStack.EMPTY : items.nativeCopy(tool);
        if (tool != null && state.isToolRequired() && !nativeTool.isSuitableFor(state)) return List.of();
        return FabricRollbackLoot.generate(world, settings.policy(),
                () -> Block.getDroppedStacks(state, world, pos, world.getBlockEntity(pos), null, nativeTool).stream().map(items::capture).toList());
    }
    @Override public boolean breakNaturally(RollbackBlockStore store, Position position, ItemStack tool) {
        check(store);
        var pos = pos(position);
        var state = world.getBlockState(pos);
        var nativeTool = tool == null ? net.minecraft.item.ItemStack.EMPTY : items.nativeCopy(tool);
        boolean harvested = !state.isOf(net.minecraft.block.Blocks.AIR)
                && (tool == null || !state.isToolRequired() || nativeTool.isSuitableFor(state));
        if (harvested) {
            for (var drop : drops(store, position, tool)) {
                var random = world.getRandom();
                var point = new Location(store.world(), pos.getX() + 0.25 + random.nextDouble() * 0.5,
                        pos.getY() + 0.25 + random.nextDouble() * 0.5 - net.minecraft.entity.EntityType.ITEM.getHeight() / 2,
                        pos.getZ() + 0.25 + random.nextDouble() * 0.5);
                if (world.getGameRules().getValue(GameRules.DO_TILE_DROPS)) store.world().dropItem(point, drop).setPickupDelay(10);
            }
            state.onStacksDropped(world, pos, nativeTool, false);
        }
        boolean removed = write(pos, state.getFluidState().getBlockState(), true);
        if (removed) state.getBlock().onBroken(world, pos, state);
        if (harvested && state.getBlock() instanceof IceBlock
                && !EnchantmentHelper.hasAnyEnchantmentsIn(nativeTool, EnchantmentTags.PREVENTS_ICE_MELTING)) {
            if (world.getEnvironmentAttributes().getAttributeValue(EnvironmentAttributes.WATER_EVAPORATES_GAMEPLAY, pos)) {
                write(pos, net.minecraft.block.Blocks.AIR.getDefaultState(), true);
            } else {
                var below = world.getBlockState(pos.down());
                if (below.blocksMovement() || below.isLiquid()) write(pos, IceBlock.getMeltedState(), true);
            }
        }
        if (harvested && state.getBlock() instanceof TurtleEggBlock eggs) ((TurtleEggRollbackAccess) eggs).rollback$breakEgg(world, pos, state);
        return removed && harvested;
    }
    boolean write(BlockPos pos, BlockState state, boolean physics) {
        return write(pos, state, physics ? Block.NOTIFY_ALL : Block.FORCE_STATE, net.minecraft.world.World.MAX_UPDATE_DEPTH);
    }
    boolean write(BlockPos pos, BlockState state, int flags, int remaining) {
        if (world.getBlockState(pos) == state) return false;
        var data = FabricMC.blockData(state);
        data.setExactState(BlockArgumentParser.stringifyBlockState(state));
        var previous = pending;
        pending = new NativeUpdate(flags, remaining);
        try {
            terrain.block(pos.getX(), pos.getY(), pos.getZ()).setBlockData(data, true);
            return true;
        } finally { pending = previous; }
    }
    private static BlockPos pos(Position position) { return new BlockPos(position.x(), position.y(), position.z()); }
    @Override public Void captureRollbackState() { check(terrain); return null; }
    @Override public void restoreRollbackState(Void state) { check(terrain); }
}
