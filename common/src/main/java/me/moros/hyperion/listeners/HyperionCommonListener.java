package me.moros.hyperion.listeners;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.configuration.PKConfigurationSection;
import com.projectkorra.projectkorra.event.AbilityStartEvent;
import com.projectkorra.projectkorra.event.BendingReloadEvent;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.entity.Arrow;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.entity.Snowball;
import com.projectkorra.projectkorra.platform.mc.event.EventHandler;
import com.projectkorra.projectkorra.platform.mc.event.EventPriority;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageByEntityEvent;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent;
import com.projectkorra.projectkorra.platform.mc.event.entity.PlayerDeathEvent;
import com.projectkorra.projectkorra.platform.mc.event.entity.ProjectileHitEvent;
import com.projectkorra.projectkorra.platform.mc.event.player.PlayerQuitEvent;
import me.moros.hyperion.Hyperion;
import me.moros.hyperion.abilities.chiblocking.Smokescreen;
import me.moros.hyperion.abilities.chiblocking.Smokescreen.SmokescreenData;
import me.moros.hyperion.abilities.earthbending.EarthGuard;
import me.moros.hyperion.abilities.earthbending.MetalCable;
import me.moros.hyperion.configuration.ConfigManager;
import me.moros.hyperion.methods.CoreMethods;

public final class HyperionCommonListener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(final ProjectileHitEvent event) {
        if (event.getEntity() instanceof Arrow && event.getEntity().hasMetadata(CoreMethods.CABLE_KEY)) {
            final Object value = event.getEntity().getMetadata(CoreMethods.CABLE_KEY).get(0).value();
            if (value instanceof MetalCable cable) {
                if (event.getHitBlock() != null) {
                    cable.setHitBlock(event.getHitBlock());
                } else if (event.getHitEntity() instanceof LivingEntity) {
                    cable.setHitEntity(event.getHitEntity());
                } else {
                    event.getEntity().remove();
                }
            }
        } else if (event.getEntity() instanceof Snowball && event.getEntity().hasMetadata(CoreMethods.SMOKESCREEN_KEY)) {
            final Object value = event.getEntity().getMetadata(CoreMethods.SMOKESCREEN_KEY).get(0).value();
            if (value instanceof SmokescreenData data) {
                Smokescreen.createCloud(event.getEntity().getLocation(), data);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityDamageByEntity(final EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Arrow && event.getDamager().hasMetadata(CoreMethods.CABLE_KEY)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityDamage(final EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FIRE && event.getCause() != EntityDamageEvent.DamageCause.FIRE_TICK) {
            return;
        }
        if (event.getEntity() instanceof Player player && CoreAbility.hasAbility(player, EarthGuard.class)) {
            final EarthGuard guard = CoreAbility.getAbility(player, EarthGuard.class);
            if (guard.hasActiveArmor()) {
                player.setFireTicks(0);
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerDeath(final PlayerDeathEvent event) {
        final Player player = event.getPlayer();
        if (player != null && CoreAbility.hasAbility(player, EarthGuard.class)) {
            CoreAbility.getAbility(player, EarthGuard.class).remove();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(final PlayerQuitEvent event) {
        final Player player = event.getPlayer();
        if (player != null && CoreAbility.hasAbility(player, EarthGuard.class)) {
            CoreAbility.getAbility(player, EarthGuard.class).remove();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPKReload(final BendingReloadEvent event) {
        Platform.scheduler().runLater(Hyperion::reload, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAbilityStart(final AbilityStartEvent event) {
        if (!(event.getAbility() instanceof CoreAbility ability)) {
            return;
        }
        final Player player = ability.getPlayer();
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (player == null || bPlayer == null || !bPlayer.isAvatarState()) {
            return;
        }
        final PKConfigurationSection section = ConfigManager.modifiersConfig.getConfig().getConfigurationSection("AvatarState." + ability.getName());
        if (section != null) {
            CoreMethods.setAttributes(section, ability);
        }
    }
}
