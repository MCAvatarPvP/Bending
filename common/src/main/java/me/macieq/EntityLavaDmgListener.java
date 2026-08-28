package me.macieq;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.event.EventHandler;
import com.projectkorra.projectkorra.platform.mc.event.EventPriority;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.TempBlock;
import me.macieq.abilities.Eruption;
import me.macieq.abilities.LavaManipulation;
import me.macieq.abilities.LavaMortar;
import me.macieq.abilities.LavaWave;
import me.macieq.abilities.MagmaShot;
import me.macieq.abilities.VolcanicFlow;
import me.macieq.utils.Pair;

/**
 * Replaces vanilla damage from ability-created lava with the owning ability's
 * configured damage on every supported platform.
 */
public final class EntityLavaDmgListener {
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamage(final EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.LAVA) return;

        final Entity entity = event.getEntity();
        final World world = entity.getWorld();
        if (BendingPlayer.isWorldDisabled(world)) return;

        final BoundingBox box = entity.getBoundingBox();
        for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(box.getMaxX()); x++) {
            for (int y = (int) Math.floor(box.getMinY()); y <= (int) Math.floor(box.getMaxY()); y++) {
                for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(box.getMaxZ()); z++) {
                    final Block block = world.getBlockAt(x, y, z);
                    if (!TempBlock.isTempBlock(block) || !EarthAbility.isLava(block)) continue;

                    final CoreAbility ability = TempBlock.get(block).getAbility().orElse(null);
                    if (ability != null && applyDamage(event, entity, ability)) return;
                }
            }
        }
    }

    private static boolean applyDamage(final EntityDamageEvent event, final Entity entity,
                                       final CoreAbility ability) {
        double damage;
        int fireTicks;

        if (ability instanceof MagmaShot value) {
            damage = value.getLavaDamage();
            fireTicks = value.getFireTicks();
        } else if (ability instanceof LavaWave value) {
            damage = value.getLavaDamage();
            fireTicks = value.getFireTicks();
        } else if (ability instanceof Eruption value) {
            damage = value.getLavaDamage();
            fireTicks = value.getFireTicks();
        } else if (ability instanceof LavaManipulation value) {
            damage = value.getLavaDamage();
            fireTicks = value.getFireTicks();
        } else if (ability instanceof VolcanicFlow value) {
            damage = value.getLavaDamage();
            fireTicks = value.getFireTicks();
        } else if (ability instanceof LavaMortar value) {
            damage = value.getLavaDamage();
            fireTicks = value.getFireTicks();
        } else {
            final Pair<Double, Integer> configured = MainConfig.getPair(ability.getName());
            if (configured == null) return false;
            damage = configured.getFirst();
            fireTicks = configured.getSecond();
        }

        event.setCancelled(true);
        DamageHandler.damageEntity(entity, ability.getPlayer(), damage, ability, false, true);
        entity.setFireTicks(fireTicks);
        return true;
    }
}
