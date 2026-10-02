package com.projectkorra.projectkorra.listener;

import com.projectkorra.projectkorra.*;
import com.projectkorra.projectkorra.ability.*;
import com.projectkorra.projectkorra.ability.util.PassiveManager;
import com.projectkorra.projectkorra.airbending.AirBurst;
import com.projectkorra.projectkorra.airbending.Suffocate;
import com.projectkorra.projectkorra.airbending.flight.FlightMultiAbility;
import com.projectkorra.projectkorra.airbending.passive.GracefulDescent;
import com.projectkorra.projectkorra.chiblocking.*;
import com.projectkorra.projectkorra.chiblocking.passive.Acrobatics;
import com.projectkorra.projectkorra.chiblocking.passive.ChiPassive;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.earthbending.EarthArmor;
import com.projectkorra.projectkorra.earthbending.EarthGrab;
import com.projectkorra.projectkorra.earthbending.Shockwave;
import com.projectkorra.projectkorra.earthbending.combo.EarthPillars;
import com.projectkorra.projectkorra.earthbending.passive.DensityShift;
import com.projectkorra.projectkorra.firebending.*;
import com.projectkorra.projectkorra.firebending.util.FireDamageTimer;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent.DamageCause;
import com.projectkorra.projectkorra.util.*;
import com.projectkorra.projectkorra.util.FlightHandler.Flight;
import com.projectkorra.projectkorra.waterbending.multiabilities.WaterArms;
import com.projectkorra.projectkorra.waterbending.passive.HydroSink;

/** Existing bending damage handlers shared by native listeners and private replay policy.
 * Call at the original listener boundary; the dispatcher owns priority/cancellation ordering.
 */
public final class CommonDamageHandler {
    private CommonDamageHandler() { }

    public static void environment(EntityDamageEvent event) {
        final var entity = event.getEntity();
        if (event.getCause() == DamageCause.FIRE && FireAbility.getSourcePlayers().containsKey(entity.getLocation().getBlock())) {
            new FireDamageTimer(entity, FireAbility.getSourcePlayers().get(entity.getLocation().getBlock()));
        }

        if (FireDamageTimer.isEnflamed(entity) && event.getCause() == DamageCause.FIRE_TICK) {
            event.setCancelled(true);
            FireDamageTimer.dealFlameDamage(entity);
        }

        if (entity instanceof Player player) {
            final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
            if (bPlayer == null) {
                return;
            }

            CoreAbility boundAbility = bPlayer.getBoundAbility();
            Element ele = boundAbility == null ? null : boundAbility.getElement();
            if (ele != null) {
                Element element = GeneralMethods.getParentElement(ele);
                int minFireTicks = ConfigManager.getConfig(bPlayer).getInt("Properties." + element.getName() + ".MinFireTickDuration");
                int maxFireTicks = ConfigManager.getConfig(bPlayer).getInt("Properties." + element.getName() + ".MaxFireTickDuration");
                int maxLavaTicks = ConfigManager.getConfig(bPlayer).getInt("Properties." + element.getName() + ".MaxLavaTickDuration");
                if (event.getCause() == DamageCause.FIRE) {
                    if (player.getFireTicks() < minFireTicks) player.setFireTicks(minFireTicks);
                    else if (player.getFireTicks() > maxFireTicks) player.setFireTicks(maxFireTicks);

                    double maxFireDmg = ConfigManager.getConfig(bPlayer).getDouble("Properties.Fire.MaxFireDamage");
                    if (event.getDamage() > maxFireDmg) event.setDamage(maxFireDmg);
                } else if (event.getCause() == DamageCause.LAVA) {
                    if (player.getFireTicks() > maxLavaTicks) player.setFireTicks(maxLavaTicks);

                    double maxLavaDmg = ConfigManager.getConfig(bPlayer).getDouble("Properties.Earth.MaxLavaDamage");
                    if (event.getDamage() > maxLavaDmg) event.setDamage(maxLavaDmg);
                }
            }

            if (CoreAbility.hasAbility(player, EarthGrab.class)) {
                final EarthGrab abil = CoreAbility.getAbility(player, EarthGrab.class);
                abil.remove();
            }

            if (CoreAbility.getAbility(player, FireJet.class) != null && event.getCause() == DamageCause.FLY_INTO_WALL) {
                event.setCancelled(true);
            }

            if (bPlayer.isElementToggled(Element.FIRE)) {
                return;
            }

            if (bPlayer.getBoundAbilityName().equalsIgnoreCase("HeatControl")) {
                if (event.getCause() == DamageCause.FIRE || event.getCause() == DamageCause.FIRE_TICK) {
                    player.setFireTicks(0);
                    event.setCancelled(true);
                }
            }
        }
    }

    public static void player(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);

            if (bPlayer == null) {
                return;
            } else if (bPlayer.isChiBlocked()) {
                return;
            }

            if (FlightMultiAbility.getFlyingPlayers().contains(player.getUniqueId())) {
                final FlightMultiAbility fma = CoreAbility.getAbility(player, FlightMultiAbility.class);
                fma.cancel("taking damage");
            }

            Suffocate.remove(player);

            if (bPlayer.hasElement(Element.EARTH) && event.getCause() == DamageCause.FALL) {
                if (bPlayer.getBoundAbilityName().equalsIgnoreCase("Shockwave")) {
                    new Shockwave(player, true);
                } else if (bPlayer.getBoundAbilityName().equalsIgnoreCase("Catapult")) {
                    new EarthPillars(player, true);
                }
            }

            if (bPlayer.hasElement(Element.AIR) && event.getCause() == DamageCause.FALL) {
                if (bPlayer.getBoundAbilityName().equalsIgnoreCase("AirBurst")) {
                    new AirBurst(player, true);
                }
            }

            CoreAbility gd = CoreAbility.getAbility(GracefulDescent.class);
            CoreAbility ds = CoreAbility.getAbility(DensityShift.class);
            CoreAbility hs = CoreAbility.getAbility(HydroSink.class);
            CoreAbility ab = CoreAbility.getAbility(Acrobatics.class);
            CoreAbility wa = CoreAbility.getAbility(player, WaterArms.class);

            if (event.getCause() == DamageCause.FALL) {
                event.setCancelled((gd != null && bPlayer.hasElement(Element.AIR) && bPlayer.canBendPassive(gd) && bPlayer.canUsePassive(gd) && gd.isEnabled() && PassiveManager.hasPassive(player, gd))
                        || (ds != null && bPlayer.hasElement(Element.EARTH) && bPlayer.canBendPassive(ds) && bPlayer.canUsePassive(ds) && ds.isEnabled() && PassiveManager.hasPassive(player, ds) && DensityShift.softenLanding(player))
                        || (hs != null && bPlayer.hasElement(Element.WATER) && bPlayer.canBendPassive(hs) && bPlayer.canUsePassive(hs) && hs.isEnabled() && PassiveManager.hasPassive(player, hs) && HydroSink.applyNoFall(player)));
            }

            boolean fallDamage = ConfigManager.getConfig(bPlayer).getBoolean("Abilities.Water.WaterArms.FallDamage");
            if (wa != null && bPlayer.hasElement(Element.WATER) && event.getCause() == DamageCause.FALL && !fallDamage) {
                event.setCancelled(true);
            }

            boolean ignoreChiBlock = ConfigManager.getConfig(bPlayer).getBoolean("Abilities.Chi.Passive.Acrobatics.IgnoreChiBlock");
            if (ab != null && bPlayer.hasElement(Element.CHI) && event.getCause() == DamageCause.FALL && bPlayer.canBendPassive(ab) && bPlayer.canUsePassive(ab, ignoreChiBlock) && ab.isEnabled() && PassiveManager.hasPassive(player, ab)) {
                final double initdamage = event.getDamage();
                final double newdamage = event.getDamage() * Acrobatics.getFallReductionFactor(bPlayer);
                final double finaldamage = initdamage - newdamage;
                event.setDamage(finaldamage);
                if (finaldamage <= 0.4) {
                    event.setCancelled(true);
                }
            }

            if (event.getCause() == DamageCause.FALL) {
                double maxFallDamage = ConfigManager.getConfig(bPlayer).getDouble("Properties.MaxFallDamage");
                if (maxFallDamage >= 0 && event.getDamage() > maxFallDamage) {
                    event.setDamage(maxFallDamage);
                }

                final Flight flight = Manager.getManager(FlightHandler.class).getInstance(player);
                if (flight != null) {
                    if (flight.getPlayer().equals(flight.getSource())) {
                        event.setCancelled(true);
                    }
                }
            }

            CoreAbility hc = CoreAbility.getAbility(HeatControl.class);

            if (hc != null && bPlayer.hasElement(Element.FIRE) && bPlayer.canBendPassive(hc) && bPlayer.canUsePassive(hc) && (event.getCause() == DamageCause.FIRE || event.getCause() == DamageCause.FIRE_TICK)) {
                event.setCancelled(!HeatControl.canBurn(player));
            }

            if (bPlayer.hasElement(Element.EARTH) && event.getCause() == DamageCause.SUFFOCATION && TempBlock.isTempBlock(player.getEyeLocation().getBlock())) {
                event.setDamage(0D);
                event.setCancelled(true);
            }

            if (CoreAbility.getAbility(player, EarthArmor.class) != null) {
                final EarthArmor eartharmor = CoreAbility.getAbility(player, EarthArmor.class);
                eartharmor.updateAbsorbtion();
            }
        }
    }

    public static void fall(EntityDamageEvent event) {
        if (event.getCause() != DamageCause.FALL || !(event.getEntity() instanceof Player player))
            return;
        if (!FallHandler.contains(player)) return;

        event.setCancelled(true);
        FallHandler.removePlayer(player);
    }

    public static void byEntity(EntityDamageEvent e, Entity source) {
        final var entity = e.getEntity();
        final FireBlastCharged fireball = FireBlastCharged.getFireball(source);

        DamageHandler.entityDamageCallback(e);

        if (fireball != null) {
            e.setCancelled(true);
            fireball.dealDamage(entity);
            return;
        }

        if (MovementHandler.isStopped(source)) {
            final CoreAbility ability = (CoreAbility) source.getMetadata("movement:stop").get(0).value();
            if (!(ability instanceof EarthGrab)) {
                e.setCancelled(true);
                return;
            }
        }

        if (entity instanceof Player target) {
            Suffocate.remove(target);
        }

        // DamageHandler raises a nested entity-damage event for the actual
        // bending damage. Never reinterpret that event as another melee input;
        // doing so cancels FirePunch's own damage before health is changed.
        if (entity instanceof LivingEntity livingEntity
                && DamageHandler.isReceivingDamage(livingEntity)) {
            return;
        }

        if (source instanceof Player sourcePlayer
                && entity instanceof LivingEntity targetLiving
                && CommonInputHandler.handleEntityLeftClick(sourcePlayer, targetLiving)) {
            e.setCancelled(true);
            return;
        }

        if (source instanceof Player sourcePlayer) { // This is the player hitting someone.
            final BendingPlayer sourceBPlayer = BendingPlayer.getBendingPlayer(sourcePlayer);
            if (sourceBPlayer == null) {
                return;
            }

            final Ability boundAbil = sourceBPlayer.getBoundAbility();

            if (sourceBPlayer.getBoundAbility() != null) {
                if (!sourceBPlayer.isOnCooldown(boundAbil)) {
                    if (sourceBPlayer.canBendPassive(sourceBPlayer.getBoundAbility())) {
                        if (e.getCause() == DamageCause.ENTITY_ATTACK) {
                            if (sourceBPlayer.getBoundAbility() instanceof ChiAbility) {
                                if (sourceBPlayer.canCurrentlyBendWithWeapons()) {
                                    if (sourceBPlayer.isElementToggled(Element.CHI)) {
                                        if (boundAbil.equals(CoreAbility.getAbility(Paralyze.class))) {
                                            new Paralyze(sourcePlayer, entity);
                                        } else if (boundAbil.equals(CoreAbility.getAbility(QuickStrike.class))) {
                                            new QuickStrike(sourcePlayer, entity);
                                            e.setCancelled(true);
                                        } else if (boundAbil.equals(CoreAbility.getAbility(SwiftKick.class))) {
                                            new SwiftKick(sourcePlayer, entity);
                                            e.setCancelled(true);
                                        } else if (boundAbil.equals(CoreAbility.getAbility(RapidPunch.class))) {
                                            new RapidPunch(sourcePlayer, entity);
                                            e.setCancelled(true);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                if (e.getCause() == DamageCause.ENTITY_ATTACK) {
                    if (sourceBPlayer.canCurrentlyBendWithWeapons()) {
                        if (sourceBPlayer.isElementToggled(Element.CHI)) {
                            if (entity instanceof Player targetPlayer) {
                                if (ChiPassive.willChiBlock(sourcePlayer, targetPlayer)) {
                                    ChiPassive.blockChi(targetPlayer);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
