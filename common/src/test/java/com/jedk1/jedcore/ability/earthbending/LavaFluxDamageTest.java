package com.jedk1.jedcore.ability.earthbending;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.firebending.util.FireDamageTimer;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent;
import com.projectkorra.projectkorra.support.AbilityWorld;
import com.projectkorra.projectkorra.util.TempBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LavaFluxDamageTest {
    private final AbilityWorld world = new AbilityWorld();
    private final Player caster = world.player(3, 64, 0);
    private final Player victim = world.player(0.5, 64, 0.5);

    private LavaFlux flux(double damage) {
        BendingPlayer.getPlayers().put(caster.getUniqueId(), new BendingPlayer(caster) {
            @Override public boolean canBend(CoreAbility ability) { return false; }
        });
        LavaFlux flux = new LavaFlux(caster) {
            @Override public boolean isEnabled() { return false; }
        };
        flux.setDamage(damage);
        return flux;
    }

    private EntityDamageEvent lavaDamage(Player victim, Block source) {
        EntityDamageEvent event = new EntityDamageEvent(victim, EntityDamageEvent.DamageCause.LAVA, 6);
        LavaFlux.handleLavaDamage(event, source);
        return event;
    }

    @AfterEach void cleanup() throws Exception {
        TempBlock.removeAll();
        FireDamageTimer.discardAllTracking();
        BendingPlayer.getPlayers().remove(caster.getUniqueId());
        world.close();
    }

    @Test void directContactUsesTheOwningCastsConfiguredDamage() {
        Block block = world.getBlockAt(0, 64, 0);
        new TempBlock(block, Material.LAVA.createBlockData(), 5000, flux(1.25));
        EntityDamageEvent event = lavaDamage(victim, block);
        assertFalse(event.isCancelled());
        assertEquals(1.25, event.getDamage());
        assertSame(caster, FireDamageTimer.getInstances().get(victim));
    }

    @Test void missingDamagerResolvesOverlappingLavaAfterTheWaveCompletes() {
        LavaFlux flux = flux(0.5);
        flux.setComplete(true);
        new TempBlock(world.getBlockAt(0, 64, 0), Material.LAVA.createBlockData(), 5000, flux);
        assertEquals(0.5, lavaDamage(victim, null).getDamage());
    }

    @Test void solidifiedOrRevertedLavaDoesNotOverrideVanillaDamage() {
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.LAVA);
        assertEquals(6, lavaDamage(victim, block).getDamage());
        TempBlock lava = new TempBlock(block, Material.LAVA.createBlockData(), 5000, flux(1));
        lava.setType(Material.STONE);
        assertNull(LavaFlux.getLavaSource(block));
        lava.revertBlock();
        assertEquals(Material.LAVA, block.getType());
        assertEquals(6, lavaDamage(victim, block).getDamage());
    }

    @Test void zeroDamageAndCasterExclusionAlsoApplyToLavaContact() {
        Block block = world.getBlockAt(0, 64, 0);
        LavaFlux flux = flux(0);
        new TempBlock(block, Material.LAVA.createBlockData(), 5000, flux);
        assertTrue(lavaDamage(victim, block).isCancelled());
        flux.setDamage(2);
        assertTrue(lavaDamage(caster, block).isCancelled());
        EntityDamageEvent fall = new EntityDamageEvent(victim, EntityDamageEvent.DamageCause.FALL, 6);
        LavaFlux.handleLavaDamage(fall, block);
        assertEquals(6, fall.getDamage());
        assertFalse(fall.isCancelled());
    }
}
