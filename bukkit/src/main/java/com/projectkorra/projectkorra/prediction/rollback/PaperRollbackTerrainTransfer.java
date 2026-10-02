package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainCodec;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainSeed;
import org.bukkit.craftbukkit.block.data.CraftBlockData;

/** Portable terrain uses the same compiled native state decoder as private Paper geometry. */
public final class PaperRollbackTerrainTransfer implements RollbackTerrainCodec.BlockStates {
    private final PaperRollbackBlockStates states = new PaperRollbackBlockStates();
    @Override public String encode(BlockData data) { return CraftBlockData.fromData(states.decode(data)).getAsString(); }
    @Override public BlockData decode(Material material, String exact) {
        var base = new BlockData(material); base.setExactState(exact);
        var result = BukkitMC.blockData(CraftBlockData.fromData(states.decode(base)));
        result.setExactState(exact); return result;
    }
    public static byte[] encode(RollbackTerrainSeed seed, RollbackTerrainCodec.Limits limits) {
        return RollbackTerrainCodec.encode(seed, new PaperRollbackTerrainTransfer(), limits);
    }
    public static RollbackTerrainSeed decode(byte[] bytes, RollbackTerrainCodec.Limits limits) {
        return RollbackTerrainCodec.decode(bytes, new PaperRollbackTerrainTransfer(), limits);
    }
}
