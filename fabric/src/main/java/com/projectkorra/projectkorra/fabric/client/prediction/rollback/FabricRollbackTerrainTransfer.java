package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.platform.fabric.FabricMC;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainCodec;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainSeed;
import net.minecraft.command.argument.BlockArgumentParser;

/** Restores the Paper terrain bytes with native Fabric validation and typed common block properties. */
public final class FabricRollbackTerrainTransfer implements RollbackTerrainCodec.BlockStates {
    @Override public String encode(BlockData data) { return BlockArgumentParser.stringifyBlockState(FabricRollbackGeometry.decode(data)); }
    @Override public BlockData decode(Material material, String exact) {
        var base = new BlockData(material); base.setExactState(exact);
        var result = FabricMC.blockData(FabricRollbackGeometry.decode(base));
        result.setExactState(exact); return result;
    }
    public static byte[] encode(RollbackTerrainSeed seed, RollbackTerrainCodec.Limits limits) {
        return RollbackTerrainCodec.encode(seed, new FabricRollbackTerrainTransfer(), limits);
    }
    public static RollbackTerrainSeed decode(byte[] bytes, RollbackTerrainCodec.Limits limits) {
        return RollbackTerrainCodec.decode(bytes, new FabricRollbackTerrainTransfer(), limits);
    }
}
