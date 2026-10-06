package com.projectkorra.projectkorra.listener;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.*;
import com.projectkorra.projectkorra.airbending.AirScooter;
import com.projectkorra.projectkorra.attribute.*;
import com.projectkorra.projectkorra.attribute.markers.DayNightFactor;
import com.projectkorra.projectkorra.chiblocking.Paralyze;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.event.*;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.event.EventHandler;
import com.projectkorra.projectkorra.platform.mc.event.EventPriority;
import com.projectkorra.projectkorra.util.DamageHandler;
import java.util.List;

/** Shared combat event rules, captured with the ordinary gameplay listener graph. */
public final class CommonAbilityCombatListener {
    @EventHandler
    public void onHorizontalCollision(final HorizontalVelocityChangeEvent e) {
        if (e.getEntity() instanceof LivingEntity) {
            if (e.getEntity().getEntityId() != e.getInstigator().getEntityId()) {
                final double minimumDistance = ConfigManager.getConfig().getDouble("Properties.HorizontalCollisionPhysics.WallDamageMinimumDistance");
                final double maxDamage = ConfigManager.getConfig().getDouble("Properties.HorizontalCollisionPhysics.WallDamageCap");
                final double damage = ((e.getDistanceTraveled() - minimumDistance) < 0 ? 0 : e.getDistanceTraveled() - minimumDistance) / (e.getDifference().length());
                if (damage > 0) {
                    DamageHandler.damageEntity(e.getEntity(), Math.min(damage, maxDamage), e.getAbility());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAbilityVelocity(AbilityVelocityAffectEntityEvent event) {
        // Generic velocity updates can omit an ability; named collision rules cannot match them.
        if (event.getAbility() == null) return;
        var entity = event.getAffected();
        if (entity instanceof FallingBlock fb) {
            for (String s : ConfigManager.collisionConfig.get().getStringList("FallingBlockCollisions")) {
                String[] abilities = s.split("\\s*,\\s*");
                if (abilities.length != 2) continue;

                if (fb.hasMetadata(abilities[0].toLowerCase())
                        && event.getAbility().getName().equalsIgnoreCase(abilities[1])) {
                    event.setCancelled(true);
                }
            }
        }

        if (entity instanceof com.projectkorra.projectkorra.platform.mc.entity.Player target) {
            cancelAirScooterOnHit(target, event.getAbility());
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onAttributeRecalc(AbilityRecalculateAttributeEvent event) {
        CoreAbility ability = event.getAbility();
        var player = ability.getPlayer();
        var location = ability.getLocation();
        if (event.hasMarker(DayNightFactor.class) && player != null && location != null) {
            boolean day = FireAbility.isDay(location.getWorld());
            boolean night = WaterAbility.isNight(location.getWorld());
            if (ability instanceof WaterAbility && night && player.hasPermission("bending.water.nightfactor")) {
                DayNightFactor dayNightFactor = event.getMarker(DayNightFactor.class);
                double factor = dayNightFactor.factor() != -1 ? dayNightFactor.factor() : WaterAbility.getNightFactor();
                //If the factor isn't the default, use the one in the annotation

                AttributeModifier modifier = dayNightFactor.invert() ? AttributeModifier.DIVISION : AttributeModifier.MULTIPLICATION;
                AttributeModification mod = AttributeModification.of(modifier, factor, AttributeModification.NIGHT_FACTOR);
                event.addModification(mod);
            } else if (ability instanceof FireAbility && day && player.hasPermission("bending.fire.dayfactor")) {
                DayNightFactor dayNightFactor = event.getMarker(DayNightFactor.class);
                double factor = dayNightFactor.factor() == -1 ? FireAbility.getDayFactor() : dayNightFactor.factor();
                //If the factor isn't the default, use the one in the annotation

                AttributeModifier modifier = dayNightFactor.invert() ? AttributeModifier.DIVISION : AttributeModifier.MULTIPLICATION;
                AttributeModification mod = AttributeModification.of(modifier, factor, AttributeModification.DAY_FACTOR);
                event.addModification(mod);
            }
        }

        //Blue fire has factors for a few attributes. But only do it for pure fire abilities and not combustion/lightning
        Element element = ability.getElement();
        BendingPlayer bPlayer = ability.getBendingPlayer();
        if ((element == Element.FIRE || element == Element.BLUE_FIRE) && bPlayer.hasElement(Element.BLUE_FIRE) && player.hasPermission("bending.fire.bluefirefactor")) {
            switch (event.getAttribute()) {
                case Attribute.DAMAGE: {
                    double factor = BlueFireAbility.getDamageFactor();
                    event.addModification(AttributeModification.of(AttributeModifier.MULTIPLICATION, factor, AttributeModification.PRIORITY_NORMAL - 50, AttributeModification.BLUE_FIRE_DAMAGE));
                    break;
                }
                case Attribute.COOLDOWN: {
                    double factor = BlueFireAbility.getCooldownFactor();
                    event.addModification(AttributeModification.of(AttributeModifier.MULTIPLICATION, factor, AttributeModification.PRIORITY_NORMAL - 50, AttributeModification.BLUE_FIRE_COOLDOWN));
                    break;
                }
                case Attribute.RANGE: {
                    double factor = BlueFireAbility.getRangeFactor();
                    event.addModification(AttributeModification.of(AttributeModifier.MULTIPLICATION, factor, AttributeModification.PRIORITY_NORMAL - 50, AttributeModification.BLUE_FIRE_RANGE));
                    break;
                }
                default:
            }
        }
    }

    @EventHandler
    public void onAbilityDamage(final AbilityDamageEntityEvent event) {
        final var source = event.getSource();

        if (source != null) {
            final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(source);

            if (event.getEntity() instanceof LivingEntity
                    && !(event.getEntity()
                    instanceof com.projectkorra.projectkorra.platform.mc.entity.Player)) {

                final double multiplier = ConfigManager.getConfig(bPlayer)
                        .getDouble("Properties.MobDamageMultiplier");

                event.setDamage(event.getDamage() * multiplier);
            }

            if (event.getEntity() instanceof LivingEntity target
                    && event.getAbility().getName().equalsIgnoreCase("FlyingKick")) {

                final CoreAbility boundAbility = bPlayer.getBoundAbility();

                if (boundAbility != null
                        && bPlayer.canCurrentlyBendWithWeapons()
                        && bPlayer.canBend(boundAbility)) {

                    /*
                     * FlyingKick + Paralyze
                     */
                    if (boundAbility instanceof Paralyze) {
                        final boolean allowFusion = ConfigManager.getConfig(bPlayer)
                                .getBoolean(
                                        "Abilities.Chi.Paralyze.AllowFlyingKickFusion"
                                );

                        if (allowFusion) {
                            new Paralyze(source, target);
                        }
                    }

                    /*
                     * FlyingKick + RapidPunch
                     */
                    else if (boundAbility instanceof me.literka.abilities.RapidPunch) {
                        new me.literka.abilities.RapidPunch(source, target);
                    }
                }
            }
        }

        if (event.getEntity()
                instanceof com.projectkorra.projectkorra.platform.mc.entity.Player target) {
            cancelAirScooterOnHit(target, event.getAbility());
        }
    }

    private void cancelAirScooterOnHit(
            final com.projectkorra.projectkorra.platform.mc.entity.Player target,
            final Ability ability) {
        if (target == null || ability == null) {
            return;
        }

        final BendingPlayer targetBPlayer = BendingPlayer.getBendingPlayer(target);
        final AirScooter scooter = CoreAbility.getAbility(target, AirScooter.class);
        if (targetBPlayer == null || scooter == null) {
            return;
        }

        final String scooterSettings = scooter.isUsingOldScooter() ? "AirScooter" : "AirSurf";
        final List<String> cancelList = ConfigManager.getConfig(targetBPlayer)
                .getStringList("Abilities.Air." + scooterSettings + ".CancelOnHit");
        for (final String cancelAbility : cancelList) {
            if (cancelAbility.equalsIgnoreCase(ability.getName())) {
                scooter.stunned = true;
                scooter.remove();
                return;
            }
        }
    }
}
