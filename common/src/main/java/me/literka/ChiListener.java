package me.literka;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.event.*;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.entity.*;
import com.projectkorra.projectkorra.platform.mc.event.EventHandler;
import com.projectkorra.projectkorra.platform.mc.event.EventPriority;
import com.projectkorra.projectkorra.platform.mc.event.Listener;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageByEntityEvent;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent.DamageCause;
import com.projectkorra.projectkorra.platform.mc.event.entity.PlayerDeathEvent;
import com.projectkorra.projectkorra.platform.mc.event.entity.ProjectileHitEvent;
import com.projectkorra.projectkorra.platform.mc.event.player.PlayerQuitEvent;
import com.projectkorra.projectkorra.platform.mc.event.player.PlayerToggleSneakEvent;
import com.projectkorra.projectkorra.util.ChatUtil;
import com.projectkorra.projectkorra.util.DamageHandler;
import me.literka.abilities.*;
import me.literka.util.Utils;

import java.util.*;

public class ChiListener implements Listener {
    private final Set<UUID> ability = new HashSet<>();


    @EventHandler
    public void onSwing(PlayerSwingEvent event) {
        Player player = event.getPlayer();
        BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);

        if (bPlayer == null) return;

        if (bPlayer.canBend(CoreAbility.getAbility(TwisterPunch.class)) && !CoreAbility.hasAbility(player, TwisterPunch.class)) {
            new TwisterPunch(player);
        } else if (bPlayer.canBendIgnoreCooldowns(CoreAbility.getAbility(StickyBomb.class))) {
            new StickyBomb(player);
        } else if (bPlayer.canBend(CoreAbility.getAbility(RopeDart.class)) && !CoreAbility.hasAbility(player, RopeDart.class)) {
            new RopeDart(player);
        } else if (bPlayer.canBend(CoreAbility.getAbility(WhipKick.class)) && !CoreAbility.hasAbility(player, WhipKick.class)) {
            new WhipKick(player);
        } else if (bPlayer.canBend(CoreAbility.getAbility(AxeKick.class)) && !CoreAbility.hasAbility(player, AxeKick.class)) {
            new AxeKick(player);
        } else if (bPlayer.canBend(CoreAbility.getAbility(Deflect.class)) && !CoreAbility.hasAbility(player, Deflect.class)) {
            new Deflect(player);
        } else if (bPlayer.canBend(CoreAbility.getAbility(Evade.class)) && !CoreAbility.hasAbility(player, Evade.class)) {
            new Evade(player);
        } else if (bPlayer.canBend(CoreAbility.getAbility(DaggerThrow.class))) {
            new DaggerThrow(player);
        } else if (bPlayer.canBend(CoreAbility.getAbility(Bola.class)) && !CoreAbility.hasAbility(player, Bola.class)) {
            new Bola(player);
        } else if (bPlayer.canBend(CoreAbility.getAbility(HighJump.class))) {
            new HighJump(player, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwingLater(PlayerSwingEvent event) {
        Player player = event.getPlayer();
        BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);

        if (bPlayer == null) return;

        CoreAbility ability = bPlayer.getBoundAbility();
        if (ability != null && !bPlayer.isOnCooldown(ability) && !CoreAbility.hasAbility(player, ability.getClass())) {
            List<String> punchAbils = List.of("Uppercut", "HeelCrash", "Gouge", "Headbutt", "QuickStrike", "SwiftKick", "RapidPunch", "Backstab");
            String name = ability.getName();
            long cooldown = ChiRework.config().getLong("Abilities." + name + ".SwingCooldown");

            boolean ignoreCooldown = false;
            for (String s : ChiRework.config().getStringList("General.IgnoreCooldownWhenAbility")) {
                CoreAbility ab = CoreAbility.getAbility(s);
                if (ab != null && CoreAbility.hasAbility(player, ab.getClass())) ignoreCooldown = true;
            }

            if (!ignoreCooldown && punchAbils.contains(name))
                bPlayer.addCooldown(name, cooldown);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityHit(EntityDamageByEntityEvent event) {
        DamageCause cause = event.getCause();
        if (ability.remove(event.getDamager().getUniqueId())) return;

        if (event.getDamager() instanceof Player p) {
            BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(p);
            if (bPlayer.isChiBlocked() && !ChiRework.config().getBoolean("General.AllowHitsOnBlock")) {
                event.setCancelled(true);
                return;
            }
        }

        if (event.getDamager() instanceof LivingEntity && event.getEntity() instanceof Player p) {
            Counter counter = CoreAbility.getAbility(p, Counter.class);
            if (counter != null) {
                counter.onHit(event);
                return;
            }
        }

        if (event.getDamager() instanceof Player player && event.getEntity() instanceof LivingEntity le) {
            BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);

            if (bPlayer == null || DamageHandler.isReceivingDamage(le)) return;

            if (canActivateHitAbility(bPlayer, "Uppercut")) {
                new Uppercut(player, le);
                event.setCancelled(true);
            } else if (canActivateHitAbility(bPlayer, "HeelCrash")) {
                if (CoreAbility.hasAbility(player, HeelCrash.class)) return;
                new HeelCrash(player, le);
                event.setCancelled(true);
            } else if (canActivateHitAbility(bPlayer, "Gouge")) {
                new Gouge(player, le);
                event.setCancelled(true);
            } else if (canActivateHitAbility(bPlayer, "Headbutt")) {
                new Headbutt(player, le);
                event.setCancelled(true);
            } else if (canActivateHitAbility(bPlayer, "QuickStrike")) {
                new QuickStrike(player, le);
                event.setCancelled(true);
            } else if (canActivateHitAbility(bPlayer, "SwiftKick")) {
                new SwiftKick(player, le);
                event.setCancelled(true);
            } else if (canActivateHitAbility(bPlayer, "RapidPunch")) {
                new RapidPunch(player, le);
                event.setCancelled(true);
            } else if (canActivateHitAbility(bPlayer, "Backstab")) {
                if (new Backstab(player, le).shouldCancel()) {
                    event.setCancelled(true);
                }
            }
        }

        if (event.getEntity() instanceof ShulkerBullet bullet) {
            event.setCancelled(bullet.hasMetadata("stickybomb"));
        }
    }

    private boolean canActivateHitAbility(BendingPlayer bPlayer, String abilityName) {
        CoreAbility ability = CoreAbility.getAbility(abilityName);
        if (ability == null) {
            return false;
        }
        if (bPlayer.canBend(ability)) {
            return true;
        }
        if (!abilityName.equalsIgnoreCase(bPlayer.getBoundAbilityName()) || !bPlayer.canBendIgnoreCooldowns(ability)) {
            return false;
        }
        long cooldownUntil = bPlayer.getCooldown(abilityName);
        if (cooldownUntil <= 0) {
            return false;
        }
        long swingCooldown = ChiRework.config().getLong("Abilities." + abilityName + ".SwingCooldown");
        long remaining = cooldownUntil - System.currentTimeMillis();
        if (swingCooldown > 0 && remaining > 0 && remaining <= swingCooldown + 100) {
            bPlayer.removeCooldown(abilityName);
            return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityHitLater(EntityDamageByEntityEvent event) {
        DamageCause cause = event.getCause();
        if (!(cause == DamageCause.ENTITY_ATTACK || cause == DamageCause.ENTITY_SWEEP_ATTACK || cause == DamageCause.CUSTOM))
            return;
        if (!(event.getDamager() instanceof Player damager)) return;
        if (!(event.getEntity() instanceof Player victim)) return;

        BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(victim);
        boolean oldChi = !bPlayer.getElements().contains(ChiRework.MODERN_CHI);
        boolean cancel;

        if (oldChi) {
            cancel = ChiRework.config().getBoolean("Abilities.RopeDart.OldCancelOnHit");
        } else {
            cancel = ChiRework.config().getBoolean("Abilities.RopeDart.CancelOnHit");
        }
        RopeDart ropeDart = CoreAbility.getAbility(victim, RopeDart.class);
        if (ropeDart != null && cancel) {
            ropeDart.removeWithCooldown();
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);

        if (bPlayer == null) return;

        if (player.isSneaking()) {
            if (bPlayer.canBendIgnoreCooldowns(CoreAbility.getAbility(StickyBomb.class))) {
                Collection<StickyBomb> stickyBombs = CoreAbility.getAbilities(player, StickyBomb.class);
                for (StickyBomb bomb : stickyBombs) {
                    bomb.explode();
                }
            }
        }

        if (bPlayer.canBend(CoreAbility.getAbility(Block.class)) && !CoreAbility.hasAbility(player, Block.class)) {
            new Block(player);
        } else if (bPlayer.canBendIgnoreBindsCooldowns(CoreAbility.getAbility(Counter.class))
                && bPlayer.getBoundAbilityName().equalsIgnoreCase("Evade")
                && !bPlayer.isOnCooldown("Counter")
                && !CoreAbility.hasAbility(player, Counter.class)) {
            new Counter(player);
        } else if (bPlayer.canBend(CoreAbility.getAbility(HighJump.class))) {
            new HighJump(player, true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof ShulkerBullet bullet && bullet.hasMetadata("stickybomb")) {
            StickyBomb bomb = (StickyBomb) bullet.getMetadata("stickybomb").get(0).value();
            boolean stuck = false;
            if (event.getHitEntity() instanceof LivingEntity e)
                stuck = bomb.stickEntity(e, bullet.getLocation());

            if (event.getHitBlock() != null)
                stuck |= bomb.stickBlock(event.getHitBlock(), bullet.getLocation());

            if (stuck) {
                bullet.remove();
            }
            event.setCancelled(true);
        }

        if (!(event.getEntity() instanceof Arrow arrow)) return;

        if (arrow.hasMetadata("ropedart") &&
                (event.getHitEntity() instanceof ShulkerBullet || event.getHitEntity() instanceof FallingBlock)) {
            event.setCancelled(true);
        }

        if (event.getHitEntity() instanceof Player p) {
            Deflect deflect = CoreAbility.getAbility(p, Deflect.class);
            if (deflect != null) {
                deflect.onProjectileHit(event);
                return;
            }
        }

        if (arrow.hasMetadata("ropedart") && event.getHitEntity() instanceof LivingEntity le) {
            ((RopeDart) arrow.getMetadata("ropedart").get(0).value()).setTarget(le);
            event.setCancelled(true);
        }

        if (arrow.hasMetadata("daggerthrow") && arrow.getShooter() instanceof Player shooter) {
            if (event.getHitBlock() != null) {
                arrow.remove();
                event.setCancelled(true);
            }

            if (event.getHitEntity() != null) {
                Entity hitEntity = event.getHitEntity();
                if (hitEntity instanceof LivingEntity le && hitEntity.getEntityId() != shooter.getEntityId()) {
                    ((DaggerThrow) arrow.getMetadata("daggerthrow").get(0).value()).damageEntityFromArrow(le, arrow);
                }

                event.setCancelled(true);
                arrow.remove();
                arrow.removeMetadata("daggerthrow", ChiRework.plugin);
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onAbilityHit(AbilityDamageEntityEvent event) {
        Player source = event.getSource();
        if (source == null) return;
        BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(source);
        ability.add(event.getSource().getUniqueId());

        if (event.getEntity() instanceof LivingEntity le && event.getAbility().getName().equalsIgnoreCase("FlyingKick")) {
            boolean canFusion = ChiRework.config().getBoolean("Abilities.Uppercut.AllowFlyingKickFusion");
            if (bPlayer.canBend(CoreAbility.getAbility(Uppercut.class)) && canFusion) {
                new Uppercut(source, le);
            }
        }

        if (!(event.getEntity() instanceof Player player)) return;

        Block block = CoreAbility.getAbility(player, Block.class);
        if (block != null) block.onAbilityHit(event);

        Evade evade = CoreAbility.getAbility(player, Evade.class);
        if (evade != null) evade.onAbilityHit(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onVelocity(AbilityVelocityAffectEntityEvent event) {
        Ability ability = event.getAbility();
        if (ability == null) return;

        Entity affected = event.getAffected();
        String abilityName = ability.getName();
        Player player;
        CoreAbility abil;
        List<String> cancel;

        if (affected.hasMetadata("ropedart")) {
            abil = (RopeDart) affected.getMetadata("ropedart").get(0).value();
            player = abil.getPlayer();

            BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
            boolean oldChi = bPlayer.getElements().contains(Element.CHI);

            if (oldChi) {
                cancel = ChiRework.config().getStringList("Abilities.RopeDart.OldCancelVelocityAbilities");
            } else {
                cancel = ChiRework.config().getStringList("Abilities.RopeDart.CancelVelocityAbilities");
            }

            for (String name : cancel) {
                if (abilityName.equalsIgnoreCase(name)) {
                    event.setCancelled(true);
                    break;
                }
            }
        } else if ((abil = CoreAbility.getAbility(player = ability.getPlayer(), RopeDart.class)) != null && player.getUniqueId() == affected.getUniqueId()) {
            BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
            boolean oldChi = bPlayer.getElements().contains(Element.CHI);

            if (oldChi) {
                cancel = ChiRework.config().getStringList("Abilities.RopeDart.OldCancellingAbilities");
            } else {
                cancel = ChiRework.config().getStringList("Abilities.RopeDart.CancellingAbilities");
            }

            for (String name : cancel) {
                if (abilityName.equalsIgnoreCase(name)) {
                    if (abil instanceof RopeDart ropeDart) {
                        ropeDart.removeWithCooldown();
                    } else {
                        abil.remove();
                    }
                }
            }
        } else if (affected.hasMetadata("stickybomb")) {
            cancel = ChiRework.config().getStringList("Abilities.StickyBomb.CancelVelocityAbilities");

            for (String name : cancel) {
                if (abilityName.equalsIgnoreCase(name)) {
                    event.setCancelled(true);
                    break;
                }
            }
        }
    }

    @EventHandler
    public void onAbilityStart(AbilityStartEvent event) {
        if (event.getAbility() instanceof StickyBomb stickyBomb) {
            Platform.scheduler().runLater(() -> ChatUtil.displayMovePreview(stickyBomb.getPlayer()), 1);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (bPlayer != null) bPlayer.unblockChi();
        Utils.chiblocks.remove(player);
        StickyBomb.cooldowns.remove(player);
        StickyBomb.throwTime.remove(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Utils.chiblocks.remove(player);
        StickyBomb.cooldowns.remove(player);
        StickyBomb.throwTime.remove(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onReload(BendingReloadEvent event) {
        Platform.scheduler().runLater(() -> {
            for (Map.Entry<Player, Long> entry : Utils.chiblocks.entrySet()) {
                BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(entry.getKey());
                if (bPlayer != null) bPlayer.unblockChi();
            }
            Utils.chiblocks.clear();
            StickyBomb.cooldowns.clear();
            StickyBomb.throwTime.clear();
            ChiRework.config.reload();
            ChiRework.plugin.setupConfig();
            Platform.logger().info("Reloaded ChiRework");
        }, 1);
    }
}
