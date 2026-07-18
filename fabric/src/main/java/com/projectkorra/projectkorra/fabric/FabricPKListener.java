package com.projectkorra.projectkorra.fabric;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.ChiAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.airbending.AirBurst;
import com.projectkorra.projectkorra.airbending.AirScooter;
import com.projectkorra.projectkorra.airbending.AirSpout;
import com.projectkorra.projectkorra.board.BendingBoardManager;
import com.projectkorra.projectkorra.chiblocking.Paralyze;
import com.projectkorra.projectkorra.chiblocking.QuickStrike;
import com.projectkorra.projectkorra.chiblocking.RapidPunch;
import com.projectkorra.projectkorra.chiblocking.SwiftKick;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.earthbending.Shockwave;
import com.projectkorra.projectkorra.earthbending.combo.EarthPillars;
import com.projectkorra.projectkorra.event.PlayerBindChangeEvent;
import com.projectkorra.projectkorra.event.PlayerChangeElementEvent;
import com.projectkorra.projectkorra.event.PlayerChangeSubElementEvent;
import com.projectkorra.projectkorra.firebending.FireJet;
import com.projectkorra.projectkorra.listener.CommonPlayerListenerCore;
import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.fabric.FabricMC;
import com.projectkorra.projectkorra.fabric.prediction.PredictionServer;
import com.projectkorra.projectkorra.platform.mc.GameMode;
import com.projectkorra.projectkorra.platform.mc.damage.DamageSource;
import com.projectkorra.projectkorra.platform.mc.damage.DamageType;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.airbending.passive.GracefulDescent;
import com.projectkorra.projectkorra.earthbending.passive.DensityShift;
import com.projectkorra.projectkorra.firebending.passive.FirePassive;
import com.projectkorra.projectkorra.platform.mc.event.EventHandler;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageByEntityEvent;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent;
import com.projectkorra.projectkorra.platform.mc.event.entity.PlayerDeathEvent;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.waterbending.passive.FastSwim;
import com.projectkorra.projectkorra.waterbending.multiabilities.WaterArms;
import com.projectkorra.projectkorra.waterbending.passive.HydroSink;
import com.projectkorra.projectkorra.util.FallHandler;
import com.projectkorra.projectkorra.ability.util.PassiveManager;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Fabric-native counterpart to Bukkit's PKListener. */
public final class FabricPKListener extends FabricGameplayBridge {
    private final Map<UUID, PlayerState> states = new HashMap<>();
    private final Set<UUID> initializedPassives = new HashSet<>();
    private boolean nativeEventsRegistered;

    @Override void start(MinecraftServer server) {
        if (!nativeEventsRegistered) {
            ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
                if (source.isOf(DamageTypes.IN_WALL)) {
                    return false;
                }

                if (entity instanceof net.minecraft.entity.LivingEntity nativeLiving) {
                    Entity nativeDamager = source.getAttacker();
                    if (nativeDamager == null) nativeDamager = source.getSource();
                    if (nativeDamager != null) {
                        LivingEntity target = FabricMC.living(nativeLiving);
                        if (target != null) {
                            EntityDamageByEntityEvent damageEvent =
                                    new EntityDamageByEntityEvent(
                                            FabricMC.entity(nativeDamager),
                                            target,
                                            source.isOf(DamageTypes.PLAYER_ATTACK)
                                                    ? EntityDamageEvent.DamageCause.ENTITY_ATTACK
                                                    : commonDamageCause(source),
                                            DamageSource.builder(DamageType.GENERIC).build(),
                                            amount);
                            if (source.isOf(DamageTypes.PLAYER_ATTACK)) {
                                handleChiHit(damageEvent);
                                if (damageEvent.isCancelled()) return false;
                            }
                            Platform.events().call(damageEvent);
                            if (damageEvent.isCancelled()) return false;
                        }
                    } else {
                        final EntityDamageEvent damageEvent =
                                new EntityDamageEvent(
                                        FabricMC.living(nativeLiving),
                                        commonDamageCause(source),
                                        amount);
                        Platform.events().call(damageEvent);
                        if (damageEvent.isCancelled()) return false;
                    }
                }

                if (!(entity instanceof ServerPlayerEntity nativePlayer)) return true;
                Player player = FabricMC.player(nativePlayer);
                BendingPlayer bendingPlayer = BendingPlayer.getBendingPlayer(player);
                if (bendingPlayer == null) return true;
                if (source.isIn(DamageTypeTags.IS_FIRE)
                        && CoreAbility.getAbility(player, FireJet.class) != null) {
                    player.setFireTicks(0);
                    return false;
                }
                if (!applyFireAndLavaDamageLimits(nativePlayer, player, bendingPlayer, source, amount)) {
                    return false;
                }
                if (source.isIn(DamageTypeTags.IS_FALL) && !bendingPlayer.isChiBlocked()) {
                    String bound = bendingPlayer.getBoundAbilityName();
                    if (bendingPlayer.hasElement(Element.EARTH)) {
                        if (bound.equalsIgnoreCase("Shockwave")) {
                            new Shockwave(player, true);
                        } else if (bound.equalsIgnoreCase("Catapult")) {
                            new EarthPillars(player, true);
                        }
                    }
                    if (bendingPlayer.hasElement(Element.AIR) && bound.equalsIgnoreCase("AirBurst")) {
                        new AirBurst(player, true);
                    }
                }
                if (source.isIn(DamageTypeTags.IS_FALL) && FallHandler.contains(player)) {
                    FallHandler.removePlayer(player);
                    return false;
                }
                if (source.isIn(DamageTypeTags.IS_FALL) && shouldCancelPassiveFallDamage(player, bendingPlayer)) {
                    return false;
                }
                if (source.isIn(DamageTypeTags.IS_FALL)) {
                    double maxFallDamage = ConfigManager.getConfig(bendingPlayer).getDouble("Properties.MaxFallDamage");
                    if (maxFallDamage >= 0 && amount > maxFallDamage) {
                        if (maxFallDamage > 0) {
                            nativePlayer.damage(nativePlayer.getEntityWorld(), source, (float) maxFallDamage);
                        }
                        return false;
                    }
                }
                return true;
            });
            ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
                if (entity instanceof ServerPlayerEntity nativePlayer) {
                    final Player player = FabricMC.player(nativePlayer);
                    final PlayerDeathEvent event =
                            new PlayerDeathEvent();
                    event.setPlayer(player);
                    Platform.events().call(event);
                    CommonPlayerListenerCore.removeActiveAbilities(player);
                }
            });
            nativeEventsRegistered = true;
        }
        super.start(server);
        Platform.events().registerListener(this);
        server.getPlayerManager().getPlayerList().forEach(this::remember);
    }

    private static boolean applyFireAndLavaDamageLimits(ServerPlayerEntity nativePlayer, Player player, BendingPlayer bendingPlayer,
                                                        net.minecraft.entity.damage.DamageSource source, float amount) {
        CoreAbility boundAbility = bendingPlayer.getBoundAbility();
        Element element = boundAbility == null ? null : GeneralMethods.getParentElement(boundAbility.getElement());
        if (element == null) {
            return true;
        }

        String prefix = "Properties." + element.getName() + ".";
        int minFireTicks = ConfigManager.getConfig(bendingPlayer).getInt(prefix + "MinFireTickDuration");
        int maxFireTicks = ConfigManager.getConfig(bendingPlayer).getInt(prefix + "MaxFireTickDuration");
        int maxLavaTicks = ConfigManager.getConfig(bendingPlayer).getInt(prefix + "MaxLavaTickDuration");

        if (source.isOf(DamageTypes.LAVA)) {
            if (player.getFireTicks() > maxLavaTicks) {
                player.setFireTicks(maxLavaTicks);
            }
            double maxLavaDamage = ConfigManager.getConfig(bendingPlayer).getDouble("Properties.Earth.MaxLavaDamage");
            return applyDamageCap(nativePlayer, source, amount, maxLavaDamage);
        }

        if (source.isIn(DamageTypeTags.IS_FIRE)) {
            if (player.getFireTicks() < minFireTicks) {
                player.setFireTicks(minFireTicks);
            } else if (player.getFireTicks() > maxFireTicks) {
                player.setFireTicks(maxFireTicks);
            }
            double maxFireDamage = ConfigManager.getConfig(bendingPlayer).getDouble("Properties.Fire.MaxFireDamage");
            return applyDamageCap(nativePlayer, source, amount, maxFireDamage);
        }

        return true;
    }

    private static EntityDamageEvent.DamageCause commonDamageCause(
            net.minecraft.entity.damage.DamageSource source) {
        if (source.isOf(DamageTypes.LAVA)) {
            return EntityDamageEvent.DamageCause.LAVA;
        }
        if (source.isOf(DamageTypes.ON_FIRE)) {
            return EntityDamageEvent.DamageCause.FIRE_TICK;
        }
        if (source.isOf(DamageTypes.IN_FIRE) || source.isIn(DamageTypeTags.IS_FIRE)) {
            return EntityDamageEvent.DamageCause.FIRE;
        }
        if (source.isIn(DamageTypeTags.IS_FALL)) {
            return EntityDamageEvent.DamageCause.FALL;
        }
        if (source.isOf(DamageTypes.IN_WALL)) {
            return EntityDamageEvent.DamageCause.SUFFOCATION;
        }
        if (source.isOf(DamageTypes.FLY_INTO_WALL)) {
            return EntityDamageEvent.DamageCause.FLY_INTO_WALL;
        }
        return EntityDamageEvent.DamageCause.CUSTOM;
    }

    private static boolean applyDamageCap(ServerPlayerEntity nativePlayer, net.minecraft.entity.damage.DamageSource source, float amount, double maxDamage) {
        if (maxDamage >= 0 && amount > maxDamage) {
            if (maxDamage > 0) {
                nativePlayer.damage(nativePlayer.getEntityWorld(), source, (float) maxDamage);
            }
            return false;
        }
        return true;
    }

    @Override void tick(MinecraftServer server) {
        super.tick(server);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) track(player);
        states.keySet().removeIf(uuid -> server.getPlayerManager().getPlayer(uuid) == null);
    }

    @Override void stop() {
        states.clear();
        initializedPassives.clear();
        super.stop();
    }

    private void remember(ServerPlayerEntity player) { states.put(player.getUuid(), PlayerState.of(player)); }

    private void handleChiHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player sourcePlayer)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity targetLiving)) {
            return;
        }
        if (!sourcePlayer.getWorld().equals(targetLiving.getWorld())
                || sourcePlayer.getLocation().distanceSquared(targetLiving.getLocation()) > 25.0
                || DamageHandler.isReceivingDamage(targetLiving)) {
            return;
        }
        if (CommonInputHandler.handleEntityLeftClick(sourcePlayer, targetLiving)) {
            event.setCancelled(true);
            return;
        }

        BendingPlayer sourceBPlayer = BendingPlayer.getBendingPlayer(sourcePlayer);
        if (sourceBPlayer == null || !sourceBPlayer.canCurrentlyBendWithWeapons() || !sourceBPlayer.isElementToggled(Element.CHI)) {
            return;
        }

        CoreAbility boundAbility = sourceBPlayer.getBoundAbility();
        if (!(boundAbility instanceof ChiAbility)
                || sourceBPlayer.isOnCooldown(boundAbility)
                || !sourceBPlayer.canBend(boundAbility)) {
            return;
        }

        if (boundAbility.equals(CoreAbility.getAbility(Paralyze.class))) {
            new Paralyze(sourcePlayer, event.getEntity());
        } else if (boundAbility.equals(CoreAbility.getAbility(QuickStrike.class))) {
            new QuickStrike(sourcePlayer, event.getEntity());
            event.setCancelled(true);
        } else if (boundAbility.equals(CoreAbility.getAbility(SwiftKick.class))) {
            new SwiftKick(sourcePlayer, event.getEntity());
            event.setCancelled(true);
        } else if (boundAbility.equals(CoreAbility.getAbility(RapidPunch.class))) {
            new RapidPunch(sourcePlayer, event.getEntity());
            event.setCancelled(true);
        }
    }

    private void track(ServerPlayerEntity nativePlayer) {
        PlayerState before = states.put(nativePlayer.getUuid(), PlayerState.of(nativePlayer));
        if (before == null) return;
        Player player = FabricMC.player(nativePlayer);
        BendingPlayer bendingPlayer = BendingPlayer.getBendingPlayer(player);
        if (bendingPlayer != null) {
            if (initializedPassives.add(nativePlayer.getUuid())) {
                PassiveManager.registerPassives(player);
                FirePassive.handle(player);
                BendingBoardManager.changeWorld(player);
            }
            handleFastSwimEdge(nativePlayer, player, bendingPlayer);
        }
        Vec3d position = nativePlayer.getEntityPos();
        boolean blockChanged = Math.floor(position.x) != Math.floor(before.position.x)
                || Math.floor(position.y) != Math.floor(before.position.y)
                || Math.floor(position.z) != Math.floor(before.position.z);
        boolean jumpDetected = before.onGround && !nativePlayer.isOnGround() && position.y - before.position.y > 0.1;
        CommonPlayerListenerCore.handleMove(player,
                FabricMC.location(nativePlayer.getEntityWorld(), before.position),
                FabricMC.location(nativePlayer.getEntityWorld(), position),
                blockChanged, jumpDetected, position.y - before.position.y);

        String world = nativePlayer.getEntityWorld().getRegistryKey().getValue().toString();
        if (!before.world.equals(world)) {
            CommonPlayerListenerCore.handleWorldChange(player);
            PredictionServer.synchronizeWorld(nativePlayer);
        }
        boolean spectator = nativePlayer.getGameMode() == net.minecraft.world.GameMode.SPECTATOR;
        if (spectator != before.spectator) {
            CommonPlayerListenerCore.handleGameModeChange(player,
                    spectator ? GameMode.SPECTATOR : GameMode.valueOf(nativePlayer.getGameMode().name()));
        }

        if (CoreAbility.hasAbility(player, AirSpout.class)
                || CoreAbility.hasAbility(player, AirScooter.class)) {
            boolean changed = !nativePlayer.getAbilities().allowFlying || !nativePlayer.getAbilities().flying;
            nativePlayer.getAbilities().allowFlying = true;
            nativePlayer.getAbilities().flying = true;
            if (changed) nativePlayer.sendAbilitiesUpdate();
        }
    }


    private static void handleFastSwimEdge(ServerPlayerEntity nativePlayer, Player player, BendingPlayer bendingPlayer) {
        if (bendingPlayer == null || !nativePlayer.isSneaking() || CoreAbility.hasAbility(player, FastSwim.class)) {
            return;
        }
        CoreAbility passive = CoreAbility.getAbility(FastSwim.class);
        if (passive == null || !PassiveManager.hasPassive(player, passive)) {
            return;
        }
        if (isFabricPlayerInWater(player)) {
            new FastSwim(player, true);
        }
    }

    private static boolean isFabricPlayerInWater(Player player) {
        return ElementalAbility.isWater(player.getLocation().getBlock())
                || ElementalAbility.isWater(player.getLocation().clone().add(0, 0.5, 0).getBlock())
                || ElementalAbility.isWater(player.getEyeLocation().getBlock());
    }

    private static boolean shouldCancelPassiveFallDamage(Player player, BendingPlayer bendingPlayer) {
        CoreAbility gracefulDescent = CoreAbility.getAbility(GracefulDescent.class);
        if (gracefulDescent != null
                && bendingPlayer.hasElement(Element.AIR)
                && bendingPlayer.canBendPassive(gracefulDescent)
                && bendingPlayer.canUsePassive(gracefulDescent)
                && gracefulDescent.isEnabled()
                && PassiveManager.hasPassive(player, gracefulDescent)) {
            return true;
        }

        CoreAbility densityShift = CoreAbility.getAbility(DensityShift.class);
        if (densityShift != null
                && bendingPlayer.hasElement(Element.EARTH)
                && bendingPlayer.canBendPassive(densityShift)
                && bendingPlayer.canUsePassive(densityShift)
                && densityShift.isEnabled()
                && PassiveManager.hasPassive(player, densityShift)
                && DensityShift.softenLanding(player)) {
            return true;
        }

        CoreAbility hydroSink = CoreAbility.getAbility(HydroSink.class);
        if (hydroSink != null
                && bendingPlayer.hasElement(Element.WATER)
                && bendingPlayer.canBendPassive(hydroSink)
                && bendingPlayer.canUsePassive(hydroSink)
                && hydroSink.isEnabled()
                && PassiveManager.hasPassive(player, hydroSink)
                && HydroSink.applyNoFall(player)) {
            return true;
        }

        CoreAbility waterArms = CoreAbility.getAbility(player, WaterArms.class);
        return waterArms != null
                && bendingPlayer.hasElement(Element.WATER)
                && !ConfigManager.getConfig(bendingPlayer).getBoolean("Abilities.Water.WaterArms.FallDamage");
    }

    @EventHandler
    public void onElementChange(PlayerChangeElementEvent event) {
        if (event.isTargetOnline() && event.getTarget().getPlayer() != null) {
            CommonPlayerListenerCore.handleElementChanged(event.getTarget().getPlayer());
        }
    }

    @EventHandler
    public void onSubElementChange(PlayerChangeSubElementEvent event) {
        if (event.isTargetOnline() && event.getTarget().getPlayer() != null) {
            CommonPlayerListenerCore.handleElementChanged(event.getTarget().getPlayer());
        }
    }

    @EventHandler
    public void onBindChange(PlayerBindChangeEvent event) {
        if (event.isOnline() && event.getPlayer().getPlayer() != null) {
            CommonPlayerListenerCore.handleBindChanged(event.getPlayer().getPlayer());
        }
    }

    private record PlayerState(Vec3d position, boolean onGround, int slot, String world, boolean spectator) {
        static PlayerState of(ServerPlayerEntity player) {
            return new PlayerState(player.getEntityPos(), player.isOnGround(), player.getInventory().getSelectedSlot(),
                    player.getEntityWorld().getRegistryKey().getValue().toString(), player.getGameMode() == net.minecraft.world.GameMode.SPECTATOR);
        }
    }
}
