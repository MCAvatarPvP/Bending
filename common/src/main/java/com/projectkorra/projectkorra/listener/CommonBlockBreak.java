package com.projectkorra.projectkorra.listener;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.WaterAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.firebending.Illumination;
import com.projectkorra.projectkorra.earthbending.EarthBlast;
import com.projectkorra.projectkorra.earthbending.lava.LavaFlow;
import com.projectkorra.projectkorra.earthbending.passive.DensityShift;
import com.projectkorra.projectkorra.waterbending.SurgeWall;
import com.projectkorra.projectkorra.waterbending.SurgeWave;
import com.projectkorra.projectkorra.waterbending.Torrent;
import com.projectkorra.projectkorra.waterbending.WaterSpoutWave;
import com.projectkorra.projectkorra.waterbending.WaterBubble;
import com.projectkorra.projectkorra.waterbending.ice.PhaseChange;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.block.Block;

/** Bending state changes shared by stock block breaks and match-owned block breaks. */
public final class CommonBlockBreak {
    private CommonBlockBreak() { }

    public static boolean cancelled(Player player, Block block) {
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (bPlayer == null) return false;
        final String abil = bPlayer.getBoundAbilityName();
        CoreAbility ability;

        if (Illumination.isIlluminationTorch(block.getRelative(com.projectkorra.projectkorra.platform.mc.block.BlockFace.UP))) {
            TempBlock torch = TempBlock.get(block.getRelative(com.projectkorra.projectkorra.platform.mc.block.BlockFace.UP));
            var user = Illumination.getBlocks().get(torch);
            Illumination illumination = CoreAbility.getAbility(user, Illumination.class);
            if (illumination != null) {
                illumination.remove();
            }
        }

        if (bPlayer.isElementToggled(Element.WATER) && bPlayer.isToggled()) {
            if (abil != null && abil.equalsIgnoreCase("Surge")) {
                ability = CoreAbility.getAbility(SurgeWall.class);
            } else if (abil != null && abil.equalsIgnoreCase("Torrent")) {
                ability = CoreAbility.getAbility(Torrent.class);
            } else if (abil != null && abil.equalsIgnoreCase("WaterSpout")) {
                ability = CoreAbility.getAbility(WaterSpoutWave.class);
            } else {
                ability = CoreAbility.getAbility(abil);
            }

            if (ability instanceof WaterAbility && !((WaterAbility) ability).allowBreakPlants() && WaterAbility.isPlantbendable(player, block.getType(), false)) {
                return true;
            }
        }

        final EarthBlast blast = EarthBlast.getBlastFromSource(block);
        if (blast != null) {
            blast.remove();
        }

        if (PhaseChange.getFrozenBlocksAsBlock().contains(block)) {
            if (PhaseChange.thaw(block)) {
                return true;
            }
        } else if (SurgeWall.getWallBlocks().containsKey(block)) {
            return true;
        } else if (Illumination.isIlluminationTorch(block)) {
            return true;
        } else if (!SurgeWave.canThaw(block)) {
            SurgeWave.thaw(block);
            return true;
        } else if (LavaFlow.isLavaFlowBlock(block)) {
            LavaFlow.removeBlock(block);
        } else if (EarthAbility.getMovedEarth().containsKey(block)) {
            EarthAbility.removeRevertIndex(block);
        } else if (TempBlock.isTempBlock(block)) {
            TempBlock.revertBlock(block, com.projectkorra.projectkorra.platform.mc.Material.AIR);
            return true;
        } else if (DensityShift.isPassiveSand(block)) {
            DensityShift.revertSand(block);
        } else if (WaterBubble.isAir(block)) {
            return true;
        }
        return false;
    }
}
