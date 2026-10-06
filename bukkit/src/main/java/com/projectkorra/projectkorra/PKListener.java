package com.projectkorra.projectkorra;

import com.jedk1.jedcore.ability.earthbending.LavaFlux;
import com.projectkorra.projectkorra.Element.SubElement;
import com.projectkorra.projectkorra.ability.*;
import com.projectkorra.projectkorra.ability.util.ComboManager;
import com.projectkorra.projectkorra.ability.util.PassiveManager;
import com.projectkorra.projectkorra.airbending.AirBurst;
import com.projectkorra.projectkorra.airbending.AirScooter;
import com.projectkorra.projectkorra.airbending.Suffocate;
import com.projectkorra.projectkorra.airbending.Tornado;
import com.projectkorra.projectkorra.airbending.flight.FlightMultiAbility;
import com.projectkorra.projectkorra.airbending.passive.GracefulDescent;
import com.projectkorra.projectkorra.avatar.AvatarState;
import com.projectkorra.projectkorra.board.BendingBoardManager;
import com.projectkorra.projectkorra.chiblocking.*;
import com.projectkorra.projectkorra.chiblocking.passive.Acrobatics;
import com.projectkorra.projectkorra.chiblocking.passive.ChiPassive;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.earthbending.EarthArmor;
import com.projectkorra.projectkorra.earthbending.EarthBlast;
import com.projectkorra.projectkorra.earthbending.EarthGrab;
import com.projectkorra.projectkorra.earthbending.Shockwave;
import com.projectkorra.projectkorra.earthbending.combo.EarthPillars;
import com.projectkorra.projectkorra.earthbending.lava.LavaFlow;
import com.projectkorra.projectkorra.earthbending.lava.LavaSurge;
import com.projectkorra.projectkorra.earthbending.metal.MetalClips;
import com.projectkorra.projectkorra.earthbending.passive.DensityShift;
import com.projectkorra.projectkorra.earthbending.passive.EarthPassive;
import com.projectkorra.projectkorra.event.*;
import com.projectkorra.projectkorra.firebending.*;
import com.projectkorra.projectkorra.firebending.util.FireDamageTimer;
import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.listener.CommonAbilityLifecycleListener;
import com.projectkorra.projectkorra.listener.CommonDamageHandler;
import com.projectkorra.projectkorra.listener.CommonPlayerListenerCore;
import com.projectkorra.projectkorra.object.HorizontalVelocityTracker;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.bukkit.BukkitEntityMetadata;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import com.projectkorra.projectkorra.platform.mc.GameMode;
import com.projectkorra.projectkorra.platform.mc.damage.DamageSource;
import com.projectkorra.projectkorra.platform.mc.damage.DamageType;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Projectile;
import com.projectkorra.projectkorra.prediction.server.PaperPredictionServer;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.*;
import com.projectkorra.projectkorra.util.FlightHandler.Flight;
import com.projectkorra.projectkorra.waterbending.*;
import com.projectkorra.projectkorra.waterbending.blood.Bloodbending;
import com.projectkorra.projectkorra.waterbending.ice.PhaseChange;
import com.projectkorra.projectkorra.waterbending.multiabilities.WaterArms;
import com.projectkorra.projectkorra.waterbending.passive.HydroSink;
import me.moros.hyperion.util.BendingFallingBlock;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.Statistic;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.stream.Collectors;

public class PKListener implements Listener {
    private final com.projectkorra.projectkorra.listener.CommonAbilityCombatListener combatEvents = new com.projectkorra.projectkorra.listener.CommonAbilityCombatListener();

    private static final HashMap<UUID, String> BENDING_ENTITY_DEATH = new HashMap<>(); // Entities killed by Bending.
    private static final HashMap<Player, String> BENDING_PLAYER_DEATH = new HashMap<>(); // Player killed by Bending.
    private static final Set<UUID> RIGHT_CLICK_INTERACT = new HashSet<>(); // Player right click block.
    @Deprecated
    private static final ArrayList<UUID> TOGGLED_OUT = new ArrayList<>(); // Stands for toggled = false while logging out.
    private static final Set<UUID> PLAYER_DROPPED_ITEM = new HashSet<>(); // Player dropped an item.
    private static final Map<Player, Integer> JUMPS = new HashMap<>();
    JavaPlugin plugin;
    private final CommonAbilityLifecycleListener lifecycleEvents = new CommonAbilityLifecycleListener(this::publishLifecycleEffect);
    public CommonAbilityLifecycleListener lifecycleEvents() { return lifecycleEvents; }

    /** Called by ordinary live events, or by a session's confirmed output publisher. */
    public void publishLifecycleEffect(CommonAbilityLifecycleListener.Effect effect) {
        if (com.projectkorra.projectkorra.prediction.rollback.RollbackDomain.active()
                || com.projectkorra.projectkorra.prediction.rollback.RollbackClock.active() || !org.bukkit.Bukkit.isPrimaryThread())
            throw new IllegalStateException("Cannot publish lifecycle effects during replay");
        if (effect instanceof CommonAbilityLifecycleListener.ConsoleCommand command) {
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command.command());
        } else if (effect instanceof CommonAbilityLifecycleListener.Board board) {
            Runnable update = () -> {
                var nativePlayer = plugin.getServer().getPlayer(board.player());
                if (nativePlayer == null) return;
                var player = BukkitMC.player(nativePlayer);
                if (board.allSlots()) BendingBoardManager.updateAllSlots(player);
                else BendingBoardManager.updateBoard(player, board.ability(), false, board.slot());
            };
            if (board.delayed()) plugin.getServer().getScheduler().runTaskLater(plugin, update, 1);
            else update.run();
        } else if (effect instanceof CommonAbilityLifecycleListener.Death death) {
            BENDING_ENTITY_DEATH.put(death.entity(), death.ability());
            var player = death.player() ? plugin.getServer().getPlayer(death.entity()) : null;
            if (player != null && death.message() != null) {
                BENDING_PLAYER_DEATH.put(player, death.message());
                plugin.getServer().getScheduler().runTaskLater(plugin,
                        () -> BENDING_PLAYER_DEATH.remove(player, death.message()), 20);
            }
        } else throw new IllegalArgumentException("Unknown lifecycle effect");
    }


    public PKListener(final JavaPlugin plugin) {

        this.plugin = plugin;
    }

    public static HashMap<Player, String> getBendingPlayerDeath() {
        return BENDING_PLAYER_DEATH;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemovedFromWorld(final EntityRemoveFromWorldEvent event) {
        // Player world changes do not end their metadata lifetime; quit does.
        if (!(event.getEntity() instanceof Player)) {
            BukkitEntityMetadata.clear(event.getEntity(), this.plugin);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void clearDepartedPlayerMetadata(final PlayerQuitEvent event) {
        BukkitEntityMetadata.clear(event.getPlayer(), this.plugin);
    }

    public static Set<UUID> getRightClickPlayers() {
        return RIGHT_CLICK_INTERACT;
    }

    /**
     * Use {@link #getRightClickPlayers()} instead.
     */
    @Deprecated
    public static List<UUID> getRightClickInteract() {
        return new ArrayList<>(RIGHT_CLICK_INTERACT);
    }

    /**
     * Deprecated. Use {@link OfflineBendingPlayer#isToggled()} instead.
     *
     * @return list of players with bending toggled off
     */
    @Deprecated
    public static ArrayList<UUID> getToggledOut() {
        return BendingPlayer.getOfflinePlayers().values().stream().filter(player -> !player.isToggled()).map(OfflineBendingPlayer::getUUID).collect(Collectors.toCollection(ArrayList::new));
    }

    public static Map<Player, Integer> getJumpStatistics() {
        return JUMPS;
    }

    private static com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent.DamageCause commonDamageCause(final DamageCause cause) {
        if (cause == DamageCause.ENTITY_ATTACK) {
            return com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK;
        }
        if (cause == DamageCause.ENTITY_SWEEP_ATTACK) {
            return com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK;
        }
        if (cause == DamageCause.FIRE) {
            return com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent.DamageCause.FIRE;
        }
        if (cause == DamageCause.FIRE_TICK) {
            return com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent.DamageCause.FIRE_TICK;
        }
        if (cause == DamageCause.LAVA) {
            return com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent.DamageCause.LAVA;
        }
        if (cause == DamageCause.FALL) {
            return com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent.DamageCause.FALL;
        }
        if (cause == DamageCause.SUFFOCATION) {
            return com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent.DamageCause.SUFFOCATION;
        }
        if (cause == DamageCause.FLY_INTO_WALL) {
            return com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent.DamageCause.FLY_INTO_WALL;
        }
        return com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent.DamageCause.CUSTOM;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event) {
        final var block = BukkitMC.block(event.getBlock());
        final var player = BukkitMC.player(event.getPlayer());
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
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
                event.setCancelled(true);
                return;
            }
        }

        final EarthBlast blast = EarthBlast.getBlastFromSource(block);
        if (blast != null) {
            blast.remove();
        }

        if (PhaseChange.getFrozenBlocksAsBlock().contains(block)) {
            if (PhaseChange.thaw(block)) {
                event.setCancelled(true);
            }
        } else if (SurgeWall.getWallBlocks().containsKey(block)) {
            event.setCancelled(true);
        } else if (Illumination.isIlluminationTorch(block)) {
            event.setCancelled(true);
        } else if (!SurgeWave.canThaw(block)) {
            SurgeWave.thaw(block);
            event.setCancelled(true);
        } else if (LavaFlow.isLavaFlowBlock(block)) {
            LavaFlow.removeBlock(block);
        } else if (EarthAbility.getMovedEarth().containsKey(block)) {
            EarthAbility.removeRevertIndex(block);
        } else if (TempBlock.isTempBlock(block)) {
            event.setCancelled(true);
            TempBlock.revertBlock(block, com.projectkorra.projectkorra.platform.mc.Material.AIR);
        } else if (DensityShift.isPassiveSand(block)) {
            DensityShift.revertSand(block);
        } else if (WaterBubble.isAir(block)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockFlowTo(final BlockFromToEvent event) {
        final var toblock = BukkitMC.block(event.getToBlock());
        final var fromblock = BukkitMC.block(event.getBlock());

        if (TempBlock.isTempBlock(fromblock) || TempBlock.isTempBlock(toblock)) {
            event.setCancelled(true);
        } else {
            if (ElementalAbility.isLava(fromblock)) {
                event.setCancelled(!EarthPassive.canFlowFromTo(fromblock, toblock));
            } else if (ElementalAbility.isWater(fromblock)) {
                event.setCancelled(WaterBubble.isAir(toblock));
                if (!event.isCancelled()) {
                    event.setCancelled(!WaterManipulation.canFlowFromTo(fromblock, toblock));
                }

                if (!event.isCancelled()) {
                    if (Illumination.isIlluminationTorch(toblock)) {
                        toblock.setType(com.projectkorra.projectkorra.platform.mc.Material.AIR);
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFluidLevelChange(final FluidLevelChangeEvent event) {
        if (TempBlock.isTempBlock(BukkitMC.block(event.getBlock()))) {
            event.setCancelled(true);
        } else if (TempBlock.isTouchingTempBlock(BukkitMC.block(event.getBlock()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockForm(final BlockFormEvent event) {
        final var block = BukkitMC.block(event.getBlock());
        if (TempBlock.isTempBlock(block)) {
            event.setCancelled(true);
        }

        if (!WaterManipulation.canPhysicsChange(block)) {
            event.setCancelled(true);
        }

        if (!EarthPassive.canPhysicsChange(block)) {
            event.setCancelled(true);
        }

        if (block.getType().toString().equals("CONCRETE_POWDER")) {
            final com.projectkorra.projectkorra.platform.mc.block.BlockFace[] faces = com.projectkorra.projectkorra.platform.mc.block.BlockFace.values();

            boolean marked = true;
            for (final com.projectkorra.projectkorra.platform.mc.block.BlockFace face : faces) {
                final var b = block.getRelative(face);
                if (b.getType() == com.projectkorra.projectkorra.platform.mc.Material.WATER) {
                    if (!TempBlock.isTempBlock(b)) {
                        marked = false; // if there is any normal water around it, prevent it.
                        break;
                    }
                }
            }

            if (marked) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockMeltEvent(final BlockFadeEvent event) {
        final var block = BukkitMC.block(event.getBlock());
        if (TempBlock.isTempBlock(block)) {
            // Expiry/removal is the sole authority for a live layer. Allowing
            // vanilla fire, ice, snow, or coral fading here would mutate the
            // physical block while leaving a client-visible layer registered.
            event.setCancelled(true);
            return;
        }

        if (block.getType() == com.projectkorra.projectkorra.platform.mc.Material.FIRE) {
            return;
        }

        event.setCancelled(Illumination.isIlluminationTorch(block));
        if (!event.isCancelled()) {
            event.setCancelled(!WaterManipulation.canPhysicsChange(block));
        }

        if (!event.isCancelled()) {
            event.setCancelled(!EarthPassive.canPhysicsChange(block));
        }

        if (!event.isCancelled()) {
            event.setCancelled(PhaseChange.getFrozenBlocksAsBlock().contains(block));
        }

        if (!event.isCancelled()) {
            event.setCancelled(!SurgeWave.canThaw(block));
        }

        if (!event.isCancelled()) {
            event.setCancelled(!Torrent.canThaw(block));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockPhysics(final BlockPhysicsEvent event) {
        final var block = BukkitMC.block(event.getBlock());
        if (TempBlock.isTempBlock(block)) {
            event.setCancelled(true);
            return;
        }

        //try (MCTiming timing = TimingPhysicsWaterManipulationCheck.startTiming()) {
        if (!WaterManipulation.canPhysicsChange(block)) {
            event.setCancelled(true);
            return;
        }
        //}

        //try (MCTiming timing = TimingPhysicsEarthPassiveCheck.startTiming()) {
        if (!EarthPassive.canPhysicsChange(block)) {
            event.setCancelled(true);
            return;
        }
        //}

        //try (MCTiming timing = TimingPhysicsEarthAbilityCheck.startTiming()) {
        if (EarthAbility.getPreventPhysicsBlocks().contains(block)) {
            event.setCancelled(true);
            return;
        }
        //}

        // If there is a TempBlock of Air bellow FallingSand blocks, prevent it from updating.
        //try (MCTiming timing = TimingPhysicsAirTempBlockBelowFallingBlockCheck.startTiming()) {
        if ((block.getType() == com.projectkorra.projectkorra.platform.mc.Material.SAND || block.getType() == com.projectkorra.projectkorra.platform.mc.Material.RED_SAND || block.getType() == com.projectkorra.projectkorra.platform.mc.Material.GRAVEL || block.getType() == com.projectkorra.projectkorra.platform.mc.Material.ANVIL || block.getType() == com.projectkorra.projectkorra.platform.mc.Material.DRAGON_EGG) && ElementalAbility.isAir(block.getRelative(com.projectkorra.projectkorra.platform.mc.block.BlockFace.DOWN).getType()) && TempBlock.isTempBlock(block.getRelative(com.projectkorra.projectkorra.platform.mc.block.BlockFace.DOWN))) {
            event.setCancelled(true);
        }
        //}
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onTempBlockBurn(final BlockBurnEvent event) {
        if (TempBlock.isTempBlock(BukkitMC.block(event.getBlock()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onTempBlockGrow(final BlockGrowEvent event) {
        if (TempBlock.isTempBlock(BukkitMC.block(event.getBlock()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onTempBlockSpread(final BlockSpreadEvent event) {
        if (TempBlock.isTempBlock(BukkitMC.block(event.getBlock()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onTempLeavesDecay(final LeavesDecayEvent event) {
        if (TempBlock.isTempBlock(BukkitMC.block(event.getBlock()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onTempMoistureChange(final MoistureChangeEvent event) {
        if (TempBlock.isTempBlock(BukkitMC.block(event.getBlock()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockPlace(final BlockPlaceEvent event) {
        final var player = BukkitMC.player(event.getPlayer());

        if (MovementHandler.isStopped(player) || Bloodbending.isBloodbent(player) || Suffocate.isBreathbent(player)) {
            event.setCancelled(true);
            return;
        }

        //Stop combos from triggering from placing blocks.
        //The block place method triggers AFTER interactions, so we have to remove
        //triggers that have already been added.
        ComboManager.removeRecentType(player, ClickType.RIGHT_CLICK_BLOCK);

    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlaceAuthority(final BlockPlaceEvent event) {
        final var block = BukkitMC.block(event.getBlock());
        if (TempBlock.isTempBlock(block)) {
            // Placement is explicit world authority. At MONITOR the final
            // cancellation state is known and Bukkit already exposes the
            // placed data, so retire the stack without restoring underneath.
            TempBlock.removeBlock(block);
        }
    }

    public void onElementChange(final PlayerChangeElementEvent event) {
        lifecycleEvents.onElementChange(event);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityChangeBlockEvent(final EntityChangeBlockEvent event) {
        final var entity = BukkitMC.entity(event.getEntity());
        if (MovementHandler.isStopped(entity) || Bloodbending.isBloodbent(entity) || Suffocate.isBreathbent(entity)) {
            event.setCancelled(true);
        }

        if (event.getEntityType() == EntityType.FALLING_BLOCK) {
            if (LavaSurge.getAllFallingBlocks().contains(entity)) {
                LavaSurge.getAllFallingBlocks().remove(entity);
                event.setCancelled(true);
            }

            var fb = BukkitMC.falling((org.bukkit.entity.FallingBlock) event.getEntity());
            if (fb.hasMetadata("CrushFallingBlock")) {
                event.setCancelled(true);
                fb.remove();
                return;
            }

            if (TempFallingBlock.isTempFallingBlock(fb)) {
                TempFallingBlock tfb = TempFallingBlock.get(fb);
                tfb.tryPlace();
                tfb.remove();
                event.setCancelled(true);
            }

            if (BendingFallingBlock.isBendingFallingBlock(fb)) {
                BendingFallingBlock bfb = BendingFallingBlock.get(fb);
                bfb.remove();
                event.setCancelled(true);
            }
        }

    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlockAuthority(final EntityChangeBlockEvent event) {
        final var block = BukkitMC.block(event.getBlock());
        if (TempBlock.isTempBlock(block)) {
            // A vanilla falling block, enderman, or other entity is about to
            // replace this coordinate. Hand off without a stale restore.
            TempBlock.removeBlock(block);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityCombust(final EntityCombustEvent event) {
        final var entity = BukkitMC.entity(event.getEntity());
        final var block = entity.getLocation().getBlock();
        if (FireAbility.getSourcePlayers().containsKey(block) && entity instanceof LivingEntity) {
            new FireDamageTimer(entity, FireAbility.getSourcePlayers().get(block));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRegen(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }

        if (event.getRegainReason() != EntityRegainHealthEvent.RegainReason.CUSTOM && event.getRegainReason() != EntityRegainHealthEvent.RegainReason.MAGIC_REGEN) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamageByBlock(final EntityDamageByBlockEvent event) {
        final var block = BukkitMC.block(event.getDamager());
        if (block == null) {
            return;
        }

        if (TempBlock.isTempBlock(block)) {
            if (EarthAbility.isEarthbendable(block.getType(), true, true, true) && GeneralMethods.isSolid(block)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityDamageEvent(final EntityDamageEvent event) {
        final var entity = BukkitMC.entity(event.getEntity());
        if (!(event instanceof EntityDamageByEntityEvent)) {
            final com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent commonEvent =
                    new com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent(entity, commonDamageCause(event.getCause()), event.getDamage());
            LavaFlux.handleLavaDamage(commonEvent,
                    event instanceof EntityDamageByBlockEvent byBlock ? BukkitMC.block(byBlock.getDamager()) : null);
            Platform.events().call(commonEvent);
            event.setCancelled(commonEvent.isCancelled());
            event.setDamage(commonEvent.getDamage());
            if (commonEvent.isCancelled()) {
                return;
            }
        }

        CommonDamageHandler.environment(BukkitMC.damageEvent(event));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityDeath(final EntityDeathEvent event) {
        final var living = BukkitMC.living(event.getEntity());
        if (TempArmor.hasTempArmor(living)) {
            for (final TempArmor tarmor : TempArmor.getTempArmorList(living)) {
                tarmor.revert(BukkitMC.items(event.getDrops()), false);
            }

            if (MetalClips.isControlled(living)) {
                event.getDrops().add(new ItemStack(Material.IRON_INGOT, MetalClips.getTargetToAbility().get(event.getEntity()).getMetalClipsCount()));
            }
        }

        final CoreAbility[] cookingFireCombos = {CoreAbility.getAbility("JetBlast"), CoreAbility.getAbility("FireWheel"), CoreAbility.getAbility("FireSpin"), CoreAbility.getAbility("FireKick")};

        if (BENDING_ENTITY_DEATH.containsKey(event.getEntity().getUniqueId())) {
            final String abilityName = BENDING_ENTITY_DEATH.remove(event.getEntity().getUniqueId());
            for (final CoreAbility fireCombo : cookingFireCombos) {
                if (abilityName.equalsIgnoreCase(fireCombo.getName())) {
                    final List<ItemStack> drops = event.getDrops();
                    final List<ItemStack> newDrops = new ArrayList<>();
                    for (ItemStack cooked : drops) {
                        final Material material = cooked.getType();
                        switch (material) {
                            case BEEF:
                                cooked = new ItemStack(Material.COOKED_BEEF);
                                break;
                            case SALMON:
                                cooked = new ItemStack(Material.COOKED_SALMON);
                                break;
                            case CHICKEN:
                                cooked = new ItemStack(Material.COOKED_CHICKEN);
                                break;
                            case PORKCHOP:
                                cooked = new ItemStack(Material.COOKED_PORKCHOP);
                                break;
                            case MUTTON:
                                cooked = new ItemStack(Material.COOKED_MUTTON);
                                break;
                            case RABBIT:
                                cooked = new ItemStack(Material.COOKED_RABBIT);
                                break;
                            case COD:
                                cooked = new ItemStack(Material.COOKED_COD);
                                break;
                            default:
                                break;
                        }
                        newDrops.add(cooked);
                    }
                    event.getDrops().clear();
                    event.getDrops().addAll(newDrops);
                    break;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        for (final Block block : event.blockList()) {
            final var commonBlock = BukkitMC.block(block);
            final EarthBlast blast = EarthBlast.getBlastFromSource(commonBlock);

            if (blast != null) {
                blast.remove();
            }

            if (PhaseChange.getFrozenBlocksAsBlock().contains(commonBlock)) {
                PhaseChange.thaw(commonBlock);
            }

            if (SurgeWall.getWallBlocks().containsKey(commonBlock)) {
                block.setType(Material.AIR);
            }

            if (!SurgeWave.canThaw(commonBlock)) {
                SurgeWave.thaw(commonBlock);
            }

            if (EarthAbility.getMovedEarth().containsKey(commonBlock)) {
                EarthAbility.removeRevertIndex(commonBlock);
            }

        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplodeAuthority(final EntityExplodeEvent event) {
        for (final Block block : event.blockList()) {
            final var commonBlock = BukkitMC.block(block);
            if (TempBlock.isTempBlock(commonBlock)) {
                // The explosion supplies the final block state immediately
                // after the event. Retire metadata, never restore a snapshot.
                TempBlock.removeBlock(commonBlock);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        for (final Block block : event.blockList()) {
            final var commonBlock = BukkitMC.block(block);
            if (TempBlock.isTempBlock(commonBlock)) {
                TempBlock.removeBlock(commonBlock);
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityExplodeEvent(final EntityExplodeEvent event) {
        final var entity = BukkitMC.entity(event.getEntity());
        if (entity != null) {
            if (MovementHandler.isStopped(entity) || Bloodbending.isBloodbent(entity) || Suffocate.isBreathbent(entity)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityInteractEvent(final EntityInteractEvent event) {
        final var entity = BukkitMC.entity(event.getEntity());
        if (MovementHandler.isStopped(entity) || Bloodbending.isBloodbent(entity) || Suffocate.isBreathbent(entity)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityProjectileLaunchEvent(final ProjectileLaunchEvent event) {
        final var entity = BukkitMC.entity(event.getEntity());
        if (MovementHandler.isStopped(entity) || Bloodbending.isBloodbent(entity) || Suffocate.isBreathbent(entity)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityShootBowEvent(final EntityShootBowEvent event) {
        final var entity = BukkitMC.entity(event.getEntity());
        if (MovementHandler.isStopped(entity) || Bloodbending.isBloodbent(entity) || Suffocate.isBreathbent(entity)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntitySlimeSplitEvent(final SlimeSplitEvent event) {
        final var entity = BukkitMC.entity(event.getEntity());
        if (MovementHandler.isStopped(entity) || Bloodbending.isBloodbent(entity) || Suffocate.isBreathbent(entity)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntitySuffocatedByTempBlocks(final EntityDamageEvent event) {
        if (event.getCause() == DamageCause.SUFFOCATION) {
            event.setCancelled(true); // TODO: Make this an option

            //if (TempBlock.isTempBlock(event.getEntity().getLocation().add(0, 1, 0).getBlock())) {
            //	event.setCancelled(true);
            //}
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityTarget(final EntityTargetEvent event) {
        final var entity = BukkitMC.entity(event.getEntity());
        if (MovementHandler.isStopped(entity) || Bloodbending.isBloodbent(entity)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityTargetLiving(final EntityTargetLivingEntityEvent event) {
        final var entity = BukkitMC.entity(event.getEntity());
        if (MovementHandler.isStopped(entity) || Bloodbending.isBloodbent(entity)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityTeleportEvent(final EntityTeleportEvent event) {
        final var entity = BukkitMC.entity(event.getEntity());
        if (MovementHandler.isStopped(entity) || Bloodbending.isBloodbent(entity) || Suffocate.isBreathbent(entity) || (entity instanceof LivingEntity living && MetalClips.isControlled(living))) {
            event.setCancelled(true);
        }

        if (entity instanceof LivingEntity living && TempArmor.hasTempArmor(living)) {
            for (final TempArmor armor : TempArmor.getTempArmorList(living)) {
                armor.revert();
            }
        }

        if (entity instanceof com.projectkorra.projectkorra.platform.mc.entity.Player player) {
            if (CoreAbility.hasAbility(player, EarthArmor.class)) {
                final EarthArmor abil = CoreAbility.getAbility(player, EarthArmor.class);
                abil.remove();
            }
        }
    }

    public void onHorizontalCollision(final HorizontalVelocityChangeEvent e) {
        combatEvents.onHorizontalCollision(e);
    }

    public void onAbilityVelocity(AbilityVelocityAffectEntityEvent event) {
        combatEvents.onAbilityVelocity(event);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryClick(final InventoryClickEvent event) {
        for (final MetalClips clips : CoreAbility.getAbilities(MetalClips.class)) {
            if (clips.getTargetEntity() != null && clips.getTargetEntity().getEntityId() == event.getWhoClicked().getEntityId()) {
                event.setCancelled(true);
                break;
            }
        }

        for (int i = 0; i < 4; i++) {
            if (event.getSlot() == 36 + i && event.getWhoClicked() instanceof org.bukkit.entity.LivingEntity living && TempArmor.hasTempArmor(BukkitMC.living(living))) {
                event.setCancelled(true);
            }
        }
    }

    public void onEntityBendingDeath(final EntityBendingDeathEvent event) {
        lifecycleEvents.onEntityBendingDeath(event);
    }

    @EventHandler
    public void onPlayerBucketEmpty(final PlayerBucketEmptyEvent event) {
        final var block = BukkitMC.block(event.getBlockClicked().getRelative(event.getBlockFace()));

        if (Illumination.isIlluminationTorch(block)) {
            final var player = Illumination.getBlocks().get(TempBlock.get(block));
            CoreAbility.getAbility(player, Illumination.class).remove();
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerChat(final AsyncPlayerChatEvent event) {
        final var player = BukkitMC.player(event.getPlayer());
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);

        String e = "Nonbender";
        String c = ChatColor.WHITE.toString();
        if (bPlayer != null) {
            if (player.hasPermission("bending.avatar") || bPlayer.getElements().size() > 1) {
                c = Element.AVATAR.getColor().toString();
                e = Element.AVATAR.getName();
            } else if (bPlayer.getElements().size() > 0) {
                c = bPlayer.getElements().get(0).getColor().toString();
                e = bPlayer.getElements().get(0).getName();
            }
        }
        final String element = ConfigManager.languageConfig.get().getString("Chat.Prefixes." + e);
        event.setFormat(event.getFormat().replace("{element}", c + element + ChatColor.RESET).replace("{ELEMENT}", c + element + ChatColor.RESET).replace("{elementcolor}", c + "").replace("{ELEMENTCOLOR}", c + ""));

        if (event.getMessage().toLowerCase().contains("let the fun begin :)")) {
            FireBlastCharged.toggleFun(player, true);
            event.setCancelled(true);
        } else if (event.getMessage().toLowerCase().contains("stop the fun :(")) {
            FireBlastCharged.toggleFun(player, false);
            event.setCancelled(true);
        }

        if (!ConfigManager.languageConfig.get().getBoolean("Chat.Enable")) {
            return;
        }

        String color = ChatColor.WHITE.toString();

        if (bPlayer == null) {
            return;
        }

        if (player.hasPermission("bending.avatar") || (bPlayer.hasElement(Element.AIR) && bPlayer.hasElement(Element.EARTH) && bPlayer.hasElement(Element.FIRE) && bPlayer.hasElement(Element.WATER))) {
            color = this.parseChatColor(ConfigManager.languageConfig.get().getString("Chat.Colors.Avatar"), ChatColor.WHITE).toString();
        } else if (bPlayer.getElements().size() > 0) {
            color = bPlayer.getElements().get(0).getColor().toString();
        }

        String format = ConfigManager.languageConfig.get().getString("Chat.Format");
        format = format.replace("<message>", "%2$s");
        format = format.replace("<name>", color + player.getDisplayName() + ChatColor.RESET);
        event.setFormat(format);
    }

    private ChatColor parseChatColor(final String value, final ChatColor fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }

        try {
            return ChatColor.of(value);
        } catch (final IllegalArgumentException ignored) {
            try {
                return ChatColor.valueOf(value.toUpperCase());
            } catch (final IllegalArgumentException ignoredAgain) {
                return fallback;
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerDamage(final EntityDamageEvent event) {
        CommonDamageHandler.player(BukkitMC.damageEvent(event));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        CommonDamageHandler.fall(BukkitMC.damageEvent(event));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommonEntityDamageByEntity(final EntityDamageByEntityEvent event) {
        final var source = BukkitMC.entity(event.getDamager());
        final var entity = BukkitMC.entity(event.getEntity());

        // JedCore consumes a prepared FirePunch before the rest of the damage
        // pipeline. Running it later lets other bridges cancel the melee event
        // first, leaving the prepared punch alive but unusable.
        if (event.getCause() == DamageCause.ENTITY_ATTACK
                && event.getDamager() instanceof Player nativePlayer
                && event.getEntity() instanceof org.bukkit.entity.LivingEntity nativeTarget
                && nativePlayer.getWorld().equals(nativeTarget.getWorld())
                && nativePlayer.getLocation().distanceSquared(nativeTarget.getLocation()) <= 25.0
                && entity instanceof LivingEntity targetLiving
                && !DamageHandler.isReceivingDamage(targetLiving)
                && CommonInputHandler.handleEntityLeftClick(BukkitMC.player(nativePlayer), targetLiving)) {
            event.setCancelled(true);
            return;
        }

        com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent.DamageCause cause =
                com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent.DamageCause.CUSTOM;
        if (event.getCause() == DamageCause.ENTITY_ATTACK) {
            cause = com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK;
        } else if (event.getCause() == DamageCause.ENTITY_SWEEP_ATTACK) {
            cause = com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK;
        }

        com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageByEntityEvent commonEvent =
                new com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageByEntityEvent(
                        source,
                        entity,
                        cause,
                        DamageSource.builder(DamageType.GENERIC).build(),
                        event.getDamage());
        Platform.events().call(commonEvent);
        event.setCancelled(commonEvent.isCancelled());
        event.setDamage(commonEvent.getDamage());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerDamageByPlayer(final EntityDamageByEntityEvent e) {
        CommonDamageHandler.byEntity(BukkitMC.damageEvent(e), BukkitMC.entity(e.getDamager()));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerDeath(final PlayerDeathEvent event) {
        final var commonPlayer = BukkitMC.player(event.getEntity());
        final com.projectkorra.projectkorra.platform.mc.event.entity.PlayerDeathEvent commonEvent =
                new com.projectkorra.projectkorra.platform.mc.event.entity.PlayerDeathEvent();
        commonEvent.setPlayer(commonPlayer);
        Platform.events().call(commonEvent);

        if (event.getKeepInventory()) {
            if (TempArmor.hasTempArmor(commonPlayer)) {
                for (final TempArmor armor : TempArmor.getTempArmorList(commonPlayer)) {
                    armor.revert(BukkitMC.items(event.getDrops()), false);
                }
            } // Do nothing. TempArmor drops are handled by the EntityDeath event and not PlayerDeath.
        }

        if (event.getEntity().getKiller() != null) {
            if (BENDING_PLAYER_DEATH.containsKey(event.getEntity())) {
                String message = ConfigManager.languageConfig.get().getString("DeathMessages.Default");
                final String ability = BENDING_PLAYER_DEATH.get(event.getEntity());
                final String tempAbility = ChatColor.stripColor(ability).replaceAll(" ", "");
                final CoreAbility coreAbil = CoreAbility.getAbility(tempAbility);
                Element element = null;
                final boolean isAvatarAbility = false;

                if (coreAbil != null) {
                    element = coreAbil.getElement();
                }

                if (HorizontalVelocityTracker.hasBeenDamagedByHorizontalVelocity(commonPlayer) && Arrays.asList(HorizontalVelocityTracker.abils).contains(tempAbility)) {
                    if (ConfigManager.languageConfig.get().contains("Abilities." + element.getName() + "." + tempAbility + ".HorizontalVelocityDeath")) {
                        message = ConfigManager.languageConfig.get().getString("Abilities." + element.getName() + "." + tempAbility + ".HorizontalVelocityDeath");
                    }
                } else if (element != null) {
                    if (element instanceof SubElement) {
                        element = ((SubElement) element).getParentElement();
                    }
                    if (ConfigManager.languageConfig.get().contains("Abilities." + element.getName() + "." + tempAbility + ".DeathMessage")) {
                        message = ConfigManager.languageConfig.get().getString("Abilities." + element.getName() + "." + tempAbility + ".DeathMessage");
                    } else if (ConfigManager.languageConfig.get().contains("Abilities." + element.getName() + ".Combo." + tempAbility + ".DeathMessage")) {
                        message = ConfigManager.languageConfig.get().getString("Abilities." + element.getName() + ".Combo." + tempAbility + ".DeathMessage");
                    }
                } else {
                    if (isAvatarAbility) {
                        if (ConfigManager.languageConfig.get().contains("Abilities.Avatar." + tempAbility + ".DeathMessage")) {
                            message = ConfigManager.languageConfig.get().getString("Abilities.Avatar." + tempAbility + ".DeathMessage");
                        }
                    } else if (ConfigManager.languageConfig.get().contains("Abilities.Avatar.Combo." + tempAbility + ".DeathMessage")) {
                        message = ConfigManager.languageConfig.get().getString("Abilities.Avatar.Combo." + tempAbility + ".DeathMessage");
                    }
                }
                message = message.replace("{victim}", event.getEntity().getName()).replace("{attacker}", event.getEntity().getKiller().getName()).replace("{ability}", ability);
                event.setDeathMessage(message);
                BENDING_PLAYER_DEATH.remove(event.getEntity());
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerItemDrop(PlayerDropItemEvent event) {
        final var player = BukkitMC.player(event.getPlayer());

        if (event.isCancelled())
            return;

        if (CommonInputHandler.shouldTrackDrop(player)) {
            PLAYER_DROPPED_ITEM.add(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCancelledTornadoAirInteraction(final PlayerInteractEvent event) {
        if (!event.isCancelled() || event.getAction() != Action.RIGHT_CLICK_AIR
                || event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        final var player = BukkitMC.player(event.getPlayer());
        if (!CoreAbility.hasAbility(player, Tornado.class)) {
            return;
        }

        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        AbilityTimingDebugger.recordInput(player, "INTERACT_RIGHT_CLICK_AIR",
                bPlayer != null ? bPlayer.getBoundAbilityName() : null);
        PaperPredictionServer.handleRightClick(event.getPlayer(), false,
                () -> CommonInputHandler.handleRightClick(player, ClickType.RIGHT_CLICK));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerInteraction(final PlayerInteractEvent event) {
        final var player = BukkitMC.player(event.getPlayer());
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        AbilityTimingDebugger.recordInput(player, "INTERACT_" + event.getAction().name(), bPlayer != null ? bPlayer.getBoundAbilityName() : null);

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            final UUID uuid = player.getUniqueId();

            if (RIGHT_CLICK_INTERACT.add(uuid)) { //Add if it isn't already in there. And if it isn't in there...
                Platform.scheduler().runLater(() -> RIGHT_CLICK_INTERACT.remove(uuid), 2L);
            }
        }

        if ((event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_AIR) && event.getHand() == EquipmentSlot.HAND) {
            final ClickType clickType = event.getAction() == Action.RIGHT_CLICK_BLOCK ? ClickType.RIGHT_CLICK_BLOCK : ClickType.RIGHT_CLICK;
            final CommonInputHandler.InputResult result = PaperPredictionServer.handleRightClick(event.getPlayer(),
                    event.getAction() == Action.RIGHT_CLICK_BLOCK,
                    () -> CommonInputHandler.handleRightClick(player, clickType));
            if (result.cancelEvent()) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerInteractEntity(final PlayerInteractAtEntityEvent event) {
        final var player = BukkitMC.player(event.getPlayer());
        if (event.getHand().equals(EquipmentSlot.HAND)) {
            final CommonInputHandler.InputResult result = PaperPredictionServer.handleRightClickEntity(event.getPlayer(),
                    () -> {
                        CommonInputHandler.prepareRightClickEntity(player);
                        // These are still native PlayerInteractAtEntityEvent
                        // inputs. Keep them inside the receipt boundary even
                        // when legacy handling consumes them before a bound
                        // ability, otherwise one trap click offsets every
                        // later client/server action ordinal.
                        if (event.getRightClicked().hasMetadata("earthgrab:trap")) {
                            final EarthGrab eg = (EarthGrab) event.getRightClicked()
                                    .getMetadata("earthgrab:trap").get(0).value();
                            eg.damageTrap();
                            return CommonInputHandler.InputResult.cancel();
                        }
                        if (event.getRightClicked().hasMetadata("temparmorstand")) {
                            return CommonInputHandler.InputResult.cancel();
                        }
                        return CommonInputHandler.handlePreparedRightClickEntity(player);
                    });
            if (result.cancelEvent()) {
                event.setCancelled(true);
                return;
            }
        } else {
            CommonInputHandler.prepareRightClickEntity(player);
            if (event.getRightClicked().hasMetadata("earthgrab:trap")) {
                final EarthGrab eg = (EarthGrab) event.getRightClicked()
                        .getMetadata("earthgrab:trap").get(0).value();
                eg.damageTrap();
                event.setCancelled(true);
                return;
            }
            if (event.getRightClicked().hasMetadata("temparmorstand")) {
                event.setCancelled(true);
                return;
            }
        }

        if (!RIGHT_CLICK_INTERACT.contains(player.getUniqueId())) {
            if (event.getRightClicked() instanceof org.bukkit.entity.Player nativeTarget) {
                final var target = BukkitMC.player(nativeTarget);
                if (FlightMultiAbility.getFlyingPlayers().contains(player.getUniqueId())) {
                    final FlightMultiAbility fma = CoreAbility.getAbility(player, FlightMultiAbility.class);
                    fma.requestCarry(target);
                    final UUID uuid = player.getUniqueId();
                    if (RIGHT_CLICK_INTERACT.add(uuid)) { //Add if it isn't already in there. And if it isn't in there...
                        Platform.scheduler().runLater(() -> RIGHT_CLICK_INTERACT.remove(uuid), 2L);
                    }
                } else if (FlightMultiAbility.getFlyingPlayers().contains(target.getUniqueId())) {
                    FlightMultiAbility.acceptCarryRequest(player, target);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerItemDamage(final PlayerItemDamageEvent event) {
        final var player = BukkitMC.player(event.getPlayer());
        if (TempArmor.hasTempArmor(player)) {
            final TempArmor armor = TempArmor.getVisibleTempArmor(player);
            for (final com.projectkorra.projectkorra.platform.mc.inventory.ItemStack i : armor.getNewArmor()) {
                if (i != null && event.getItem().isSimilar(BukkitMC.itemHandle(i))) {
                    event.setCancelled(true);
                    break;
                }
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(final PlayerJoinEvent event) {
        BukkitMC.clearPlayerState(event.getPlayer());
        BukkitAirGliderRecipes.discover(event.getPlayer());
        final var player = BukkitMC.player(event.getPlayer());
        JUMPS.put(event.getPlayer(), event.getPlayer().getStatistic(Statistic.JUMP));

        CommonPlayerListenerCore.handleJoin(player);

        if (ConfigManager.languageConfig.get().getBoolean("Chat.Branding.JoinMessage.Enabled")) {
            Platform.scheduler().runLater(() -> {
                ChatColor color = ChatColor.of(ConfigManager.languageConfig.get().getString("Chat.Branding.Color").toUpperCase());
                color = color == null ? ChatColor.GOLD : color;
                final String topBorder = ConfigManager.languageConfig.get().getString("Chat.Branding.Borders.TopBorder");
                final String bottomBorder = ConfigManager.languageConfig.get().getString("Chat.Branding.Borders.BottomBorder");
                if (!topBorder.isEmpty()) {
                    player.sendMessage(ChatUtil.color(topBorder));
                }
                player.sendMessage(ChatUtil.multiline(color + "This server is running ProjectKorra version " + plugin.getDescription().getVersion() + " for bending! Find out more at http://www.projectkorra.com!"));
                if (!bottomBorder.isEmpty()) {
                    player.sendMessage(ChatUtil.color(bottomBorder));
                }
            }, 20 * 4);
        }
    }

    @EventHandler
    public void onPlayerChangeWorld(final PlayerChangedWorldEvent event) {
        CommonPlayerListenerCore.handleWorldChange(BukkitMC.player(event.getPlayer()));
        PaperPredictionServer.synchronizeWorld(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerKick(final PlayerKickEvent event) {
        JUMPS.remove(event.getPlayer());
        BukkitMC.clearPlayerState(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerMove(final PlayerMoveEvent event) {
        if (event.getTo().getX() == event.getFrom().getX() && event.getTo().getY() == event.getFrom().getY() && event.getTo().getZ() == event.getFrom().getZ()) {
            return;
        }

        final var nativePlayer = event.getPlayer();
        final var player = BukkitMC.player(nativePlayer);
        boolean jumpDetected = false;
        double jumpDelta = 0;
        if (event.getTo().getY() > event.getFrom().getY()) {
            final int current = nativePlayer.getStatistic(Statistic.JUMP);
            final int last = JUMPS.get(nativePlayer);
            if (last != current) {
                JUMPS.put(nativePlayer, current);
                jumpDetected = true;
                jumpDelta = event.getTo().getY() - event.getFrom().getY();
            }
        }

        final CommonPlayerListenerCore.MovementResult result = CommonPlayerListenerCore.handleMove(player,
                BukkitMC.location(event.getFrom()), BukkitMC.location(event.getTo()),
                event.getTo().getBlock() != event.getFrom().getBlock(), jumpDetected, jumpDelta);
        if (result.cancelEvent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerGamemodeChange(final PlayerGameModeChangeEvent event) {
        CommonPlayerListenerCore.handleGameModeChange(BukkitMC.player(event.getPlayer()),
                GameMode.valueOf(event.getNewGameMode().name()));
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent event) {
        final var player = BukkitMC.player(event.getPlayer());
        CommonPlayerListenerCore.handleQuit(player);
        JUMPS.remove(event.getPlayer());
        BukkitMC.clearPlayerState(event.getPlayer());
    }

    public void onAttributeRecalc(AbilityRecalculateAttributeEvent event) {
        combatEvents.onAttributeRecalc(event);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerSneak(final PlayerToggleSneakEvent event) {
        final var player = BukkitMC.player(event.getPlayer());
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        AbilityTimingDebugger.recordInput(player, event.isSneaking() ? "SNEAK_DOWN" : "SNEAK_UP", bPlayer != null ? bPlayer.getBoundAbilityName() : null);

        final CommonInputHandler.InputResult result = PaperPredictionServer.handleSneak(event.getPlayer(),
                event.isSneaking(), () -> CommonInputHandler.handleSneak(player, !event.isSneaking()));
        if (result.cancelEvent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerSlotChange(final PlayerItemHeldEvent event) {
        final var player = BukkitMC.player(event.getPlayer());
        final CommonInputHandler.SlotResult result = CommonInputHandler.handleSlotChange(player, event.getNewSlot());
        if (!result.accepted()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerSwapItems(final PlayerSwapHandItemsEvent event) {
        final var player = BukkitMC.player(event.getPlayer());
        final ItemStack main = event.getMainHandItem();
        final ItemStack off = event.getOffHandItem();
        PaperPredictionServer.handleSwapHands(event.getPlayer(),
                () -> CommonInputHandler.handleSwapHands(player, main.getType() == Material.AIR,
                        off == null || off.getType() == Material.AIR));
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerSwing(final PlayerAnimationEvent event) {
        final var player = BukkitMC.player(event.getPlayer());
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        AbilityTimingDebugger.recordInput(player, "SWING_" + event.getAnimationType().name(), bPlayer != null ? bPlayer.getBoundAbilityName() : null);
//		if (event.getHand() != EquipmentSlot.HAND) {
//			return;
//		}
//		if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.LEFT_CLICK_AIR) {
//			return;
//		}
//		if (event.getAction() == Action.LEFT_CLICK_BLOCK && event.isCancelled()) {
//			return;
//		}
        final CommonInputHandler.InputResult result = PaperPredictionServer.handleLeftClick(event.getPlayer(),
                () -> CommonInputHandler.handleSwing(player, RIGHT_CLICK_INTERACT, PLAYER_DROPPED_ITEM));
        if (result.cancelEvent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerToggleFlight(final PlayerToggleFlightEvent event) {
        event.setCancelled(CommonInputHandler.handleToggleFlight(BukkitMC.player(event.getPlayer())).cancelEvent());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerToggleGlide(final EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.Player nativePlayer)) {
            return;
        }
        event.setCancelled(CommonInputHandler.handleToggleGlide(BukkitMC.player(nativePlayer)).cancelEvent());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileHit(final ProjectileHitEvent event) {
        final var projectile = BukkitMC.entity(event.getEntity());
        if (projectile instanceof Projectile commonProjectile) {
            com.projectkorra.projectkorra.platform.mc.event.entity.ProjectileHitEvent commonEvent =
                    new com.projectkorra.projectkorra.platform.mc.event.entity.ProjectileHitEvent();
            commonEvent.setEntity(commonProjectile);
            commonEvent.setHitEntity(BukkitMC.entity(event.getHitEntity()));
            commonEvent.setHitBlock(BukkitMC.block(event.getHitBlock()));
            Platform.events().call(commonEvent);
            if (commonEvent.isCancelled()) {
                event.setCancelled(true);
            }
        }

        final Integer id = event.getEntity().getEntityId();
        final Smokescreen smokescreen = Smokescreen.getSnowballs().get(id);
        if (smokescreen != null) {
            final var loc = BukkitMC.location(event.getEntity().getLocation());
            Smokescreen.playEffect(loc);
            for (final var en : GeneralMethods.getEntitiesAroundPoint(loc, smokescreen.getRadius())) {
                smokescreen.applyBlindness(en);
            }

            Smokescreen.getSnowballs().remove(id);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPickupItem(final EntityPickupItemEvent event) {
        for (final MetalClips metalClips : CoreAbility.getAbilities(MetalClips.class)) {
            if (metalClips.getTrackedIngots().contains(event.getItem())) {
                event.setCancelled(true);
            }
        }

        if (event.isCancelled()) {
            return;
        }

        if (event.getEntity() instanceof org.bukkit.entity.LivingEntity nativeLiving) {
            var lent = BukkitMC.living(nativeLiving);

            if (TempArmor.hasTempArmor(lent)) {
                TempArmor armor = TempArmor.getVisibleTempArmor(lent);
                var is = BukkitMC.item(event.getItem().getItemStack());
                int index = GeneralMethods.getArmorIndex(is.getType());

                if (index == -1) {
                    return;
                }

                event.setCancelled(true);
                var prev = armor.getOldArmor()[index];

                if (GeneralMethods.compareArmor(is.getType(), prev.getType()) > 0) {
                    event.getEntity().getWorld().dropItemNaturally(event.getEntity().getLocation(), BukkitMC.itemHandle(prev));
                    armor.getOldArmor()[index] = is;
                    event.getItem().remove();
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryPickupItem(final InventoryPickupItemEvent event) {
        for (final MetalClips metalClips : CoreAbility.getAbilities(MetalClips.class)) {
            if (metalClips.getTrackedIngots().contains(event.getItem())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onItemMerge(final ItemMergeEvent event) {
        for (final MetalClips metalClips : CoreAbility.getAbilities(MetalClips.class)) {
            if (metalClips.getTrackedIngots().contains(event.getEntity()) || metalClips.getTrackedIngots().contains(event.getTarget())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityVelocity(final PlayerVelocityEvent event) {
        final Entity entity = event.getPlayer();
        if (!entity.hasMetadata(FireBlast.NO_KNOCKBACK_METADATA)) {
            return;
        }

        long now = System.currentTimeMillis();
        for (MetadataValue value : entity.getMetadata(FireBlast.NO_KNOCKBACK_METADATA)) {
            if (value.getOwningPlugin() == plugin) {
                if (now - value.asLong() <= 150L) {
                    event.setCancelled(true);
                }
                break;
            }
        }
        entity.removeMetadata(FireBlast.NO_KNOCKBACK_METADATA, plugin);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPistonExtendEvent(final BlockPistonExtendEvent event) {
        for (final Block b : event.getBlocks()) {
            if (TempBlock.isTempBlock(BukkitMC.block(b))
                    || TempBlock.isTempBlock(BukkitMC.block(b.getRelative(event.getDirection())))) {
                event.setCancelled(true);
                break;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPistonRetractEvent(final BlockPistonRetractEvent event) {
        for (final Block b : event.getBlocks()) {
            if (TempBlock.isTempBlock(BukkitMC.block(b))
                    || TempBlock.isTempBlock(BukkitMC.block(b.getRelative(event.getDirection())))) {
                event.setCancelled(true);
                break;
            }
        }
    }

    public void onBendingSubElementChange(final PlayerChangeSubElementEvent event) {
        lifecycleEvents.onBendingSubElementChange(event);
    }

    public void onBindChange(final PlayerBindChangeEvent event) {
        lifecycleEvents.onBindChange(event);
    }

    public void onPlayerStanceChange(final PlayerStanceChangeEvent event) {
        lifecycleEvents.onPlayerStanceChange(event);
    }

    @EventHandler
    public void onPluginUnload(PluginDisableEvent event) {
        RegionProtection.unloadPlugin((JavaPlugin) event.getPlugin());
        BendingPlayer.BEND_HOOKS.remove((JavaPlugin) event.getPlugin());
        BendingPlayer.BIND_HOOKS.remove((JavaPlugin) event.getPlugin());
    }

    public void onAbilityStart(AbilityStartEvent event) {
        lifecycleEvents.onAbilityStart(event);
    }

    public void onAbilityDamage(final AbilityDamageEntityEvent event) {
        combatEvents.onAbilityDamage(event);
    }


}
