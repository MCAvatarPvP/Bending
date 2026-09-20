package com.jedk1.jedcore.ability.waterbending;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.data.Snowable;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.support.AbilityWorld;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.waterbending.ice.PhaseChange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FrostBreathCleanupTest {
    private final AbilityWorld world = new AbilityWorld();
    private final Player player = world.player(0.5, 65, 0.5);

    private FrostBreath frost() {
        BendingPlayer.getPlayers().put(player.getUniqueId(), new BendingPlayer(player) {
            @Override public boolean canBend(CoreAbility ability) { return false; }
        });
        return new FrostBreath(player) {
            @Override public boolean isEnabled() { return false; }
            @Override public void remove() {
                // This fixture never registers an active ability with the platform.
                this.player = null;
                super.remove();
            }
        };
    }

    @AfterEach void cleanup() throws Exception {
        TempBlock.removeAll();
        BendingPlayer.getPlayers().remove(player.getUniqueId());
        world.close();
    }

    @Test void decorativeSnowRefreshesOneLayerAndRemovalRestoresGrass() {
        var ground = world.getBlockAt(0, 64, 0);
        ground.setType(Material.GRASS_BLOCK);
        var block = world.getBlockAt(0, 65, 0);
        FrostBreath frost = frost();
        frost.updateFrozenBlock(block, Material.SNOW, 5000, false);
        TempBlock snow = TempBlock.get(block);
        frost.updateFrozenBlock(block, Material.SNOW, 10000, false);

        assertSame(snow, TempBlock.get(block));
        assertEquals(1, TempBlock.getAll(block).size());
        assertEquals(1, frost.getFrozenBlocks().size());
        assertTrue(snow.getRevertTime() > System.currentTimeMillis());
        assertFalse(PhaseChange.getFrozenBlocksMap().containsKey(snow));
        assertTrue(((Snowable) ground.getBlockData()).isSnowy());
        frost.remove();
        assertEquals(Material.AIR, block.getType());
        assertFalse(((Snowable) ground.getBlockData()).isSnowy());
        assertTrue(frost.getFrozenBlocks().isEmpty());
    }

    @Test void overlappingCastsDoNotRetireEachOthersSnow() {
        var block = world.getBlockAt(0, 65, 0);
        FrostBreath first = frost();
        FrostBreath second = frost();
        first.updateFrozenBlock(block, Material.SNOW, 5000, false);
        TempBlock firstSnow = TempBlock.get(block);
        second.updateFrozenBlock(block, Material.SNOW, 5000, false);
        TempBlock secondSnow = TempBlock.get(block);
        first.updateFrozenBlock(block, Material.SNOW, 10000, false);
        assertFalse(secondSnow.isReverted());
        assertSame(secondSnow, TempBlock.get(block));
        second.remove();
        assertSame(firstSnow, TempBlock.get(block));
        first.remove();
        assertEquals(Material.AIR, block.getType());
    }

    @Test void expiredBendableLayerCanBeRecreatedAndUnregisteredIndependently() {
        var block = world.getBlockAt(0, 65, 0);
        FrostBreath frost = frost();
        frost.updateFrozenBlock(block, Material.SNOW, 5000, true);
        TempBlock snow = TempBlock.get(block);
        assertTrue(PhaseChange.getFrozenBlocksMap().containsKey(snow));
        snow.revertBlock();
        assertFalse(PhaseChange.getFrozenBlocksMap().containsKey(snow));
        frost.updateFrozenBlock(block, Material.ICE, 5000, true);
        TempBlock ice = TempBlock.get(block);
        assertNotSame(snow, ice);
        assertEquals(Material.ICE, block.getType());
        frost.remove();
        assertEquals(Material.AIR, block.getType());
        assertFalse(PhaseChange.getFrozenBlocksMap().containsKey(ice));
    }
}
