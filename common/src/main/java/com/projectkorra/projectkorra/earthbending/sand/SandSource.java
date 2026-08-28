package com.projectkorra.projectkorra.earthbending.sand;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.earthbending.passive.DensityShift;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.BlockSource;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.TempBlock;

/** Shared selection and exact restoration for core sand abilities. */
final class SandSource {
    private final Block block;
    private final BlockData original;
    private final Material visualMaterial;
    private TempBlock reservation;

    private SandSource(final Block block) {
        this.block = block;
        this.original = block.getBlockData().clone();
        this.visualMaterial = isRed(block.getType()) ? Material.RED_SAND : Material.SAND;
    }

    static SandSource select(final CoreAbility ability, final Player player, final double range) {
        final Block block = BlockSource.getEarthSourceBlock(player, range, ClickType.SHIFT_DOWN);
        BlockSource.removeSource(player, BlockSource.BlockSourceType.EARTH, ClickType.SHIFT_DOWN);
        if (block == null || !ElementalAbility.isSand(block) || TempBlock.isTempBlock(block)
                || GeneralMethods.isRegionProtectedFromBuild(ability, block.getLocation())) return null;
        return new SandSource(block);
    }

    boolean reserve(final CoreAbility ability) {
        if (this.reservation != null && !this.reservation.isReverted()) return true;
        if (TempBlock.isTempBlock(this.block)
                || GeneralMethods.isRegionProtectedFromBuild(ability, this.block.getLocation())) return false;
        if (DensityShift.isPassiveSand(this.block)) DensityShift.revertSand(this.block);
        final Material placeholder = this.visualMaterial == Material.RED_SAND
                ? Material.RED_SANDSTONE : Material.SANDSTONE;
        this.reservation = new TempBlock(this.block, placeholder.createBlockData(), ability);
        return !this.reservation.isReverted();
    }

    void restore() {
        if (this.reservation != null && !this.reservation.isReverted()) this.reservation.revertBlock();
        this.reservation = null;
    }

    Block block() {
        return this.block;
    }

    BlockData original() {
        return this.original.clone();
    }

    Material visualMaterial() {
        return this.visualMaterial;
    }

    private static boolean isRed(final Material material) {
        return material != null && material.toString().contains("RED_SAND");
    }
}
