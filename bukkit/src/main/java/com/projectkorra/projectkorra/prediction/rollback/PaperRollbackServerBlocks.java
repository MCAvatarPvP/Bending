package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;

/** Registry-only block defaults; does not call Bukkit.createBlockData or access a server/world. */
public final class PaperRollbackServerBlocks implements RollbackServer.Blocks<Void> {
    @Override public BlockData create(Material material) {
        var nativeMaterial = org.bukkit.Material.getMaterial(java.util.Objects.requireNonNull(material).canonical().name());
        if (nativeMaterial == null || !nativeMaterial.isBlock()) throw new IllegalArgumentException("Material is not a native block: " + material);
        var block = CraftMagicNumbers.getBlock(nativeMaterial);
        if (block == null) throw new IllegalArgumentException("Missing native block: " + material);
        return BukkitMC.blockData(CraftBlockData.fromData(block.defaultBlockState()));
    }
    @Override public Void captureRollbackState() { return null; }
    @Override public void restoreRollbackState(Void ignored) { }
}
