package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.block.data.Levelled;
import com.projectkorra.projectkorra.platform.mc.block.data.Snowable;
import com.projectkorra.projectkorra.platform.mc.block.data.type.Fire;
import com.projectkorra.projectkorra.platform.mc.block.data.type.Snow;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;

import java.util.LinkedHashMap;
import java.util.Map;

/** Native registry decoding plus common property edits, without a live Bukkit server factory. */
final class PaperRollbackBlockStates implements PaperRollbackGeometry.States {
    private final Map<String, BlockState> parsed = new LinkedHashMap<>();

    @Override public BlockState decode(BlockData data) {
        var block = CraftMagicNumbers.getBlock(BukkitMC.material(data.getMaterial()));
        if (block == null) throw new IllegalArgumentException("Material has no native block state");
        BlockState state = block.defaultBlockState();
        String exact = data.getExactState();
        if (exact != null) {
            state = parsed.get(exact);
            if (state == null) {
                try { state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, exact, false).blockState(); }
                catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) { throw new IllegalArgumentException("Invalid captured block state", failure); }
                if (parsed.size() >= 4096) parsed.remove(parsed.keySet().iterator().next());
                parsed.put(exact, state);
            }
            if (state.getBlock() != block) throw new IllegalArgumentException("Block material does not match captured state");
        }
        if (data.getClass() == BlockData.class) return state;
        var nativeData = CraftBlockData.fromData(state);
        // Apply supported mutable facade properties onto the exact base, preserving
        // native properties that the common API does not expose.
        if (data instanceof Levelled levelled) {
            if (nativeData instanceof org.bukkit.block.data.Levelled target) target.setLevel(Math.max(0, Math.min(target.getMaximumLevel(), levelled.getLevel())));
            if (nativeData instanceof org.bukkit.block.data.Waterlogged target) target.setWaterlogged(levelled.isWaterlogged());
        } else if (data instanceof Snowable snowable && nativeData instanceof org.bukkit.block.data.Snowable target) {
            target.setSnowy(snowable.isSnowy());
        } else if (data instanceof Snow snow && nativeData instanceof org.bukkit.block.data.type.Snow target) {
            target.setLayers(Math.max(target.getMinimumLayers(), Math.min(target.getMaximumLayers(), snow.getLayers())));
        } else if (data instanceof Fire fire && nativeData instanceof org.bukkit.block.data.MultipleFacing target) {
            for (var face : target.getAllowedFaces()) target.setFace(face, fire.hasFace(BlockFace.valueOf(face.name())));
        }
        return nativeData.getState();
    }

    @Override public boolean solid(BlockData data) { return BukkitMC.material(data.getMaterial()).isSolid(); }
}
