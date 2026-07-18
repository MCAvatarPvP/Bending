package com.projectkorra.projectkorra.listener;

import com.projectkorra.projectkorra.*;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.util.MultiAbilityManager;
import com.projectkorra.projectkorra.ability.util.PassiveManager;
import com.projectkorra.projectkorra.airbending.AirSpout;
import com.projectkorra.projectkorra.board.BendingBoardManager;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.earthbending.metal.MetalClips;
import com.projectkorra.projectkorra.event.PlayerJumpEvent;
import com.projectkorra.projectkorra.firebending.passive.FirePassive;
import com.projectkorra.projectkorra.object.Preset;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.GameMode;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.AbilityExecutionContext;
import com.projectkorra.projectkorra.util.*;
import com.projectkorra.projectkorra.waterbending.WaterSpout;
import com.projectkorra.projectkorra.waterbending.blood.Bloodbending;
import com.projectkorra.projectkorra.waterbending.passive.FastSwim;

import java.util.List;

public final class CommonPlayerListenerCore {
    private CommonPlayerListenerCore() {
    }

    public static void handleJoin(final Player player) {
        BendingPlayer.getOrLoadOfflineAsync(player);
        if (ProjectKorra.isStatisticsEnabled()) {
            Manager.getManager(StatisticsManager.class).load(player.getUniqueId());
        }
    }

    public static void handleWorldChange(final Player player) {
        PassiveManager.registerPassives(player);
        BendingBoardManager.changeWorld(player);
    }

    public static void handleGameModeChange(final Player player, final GameMode newGameMode) {
        if (GameMode.SPECTATOR.equals(newGameMode)) {
            Commands.invincible.add(player.getName());
            MultiAbilityManager.remove(player);
            removeActiveAbilities(player);
            final FlightHandler flights = Manager.getManager(FlightHandler.class);
            if (flights != null) {
                flights.removeAll(player);
            }
        } else {
            Commands.invincible.remove(player.getName());
            // Mode-change events can fire before the platform has applied the
            // new mode. Registering here makes canBendPassive still see
            // spectator and permanently skips AirAgility after a duel
            // respawn, so restore passives once the mode is authoritative.
            Platform.scheduler().runLater(() -> {
                PassiveManager.registerPassives(player);
                FirePassive.handle(player);
                final FlightHandler flights = Manager.getManager(FlightHandler.class);
                if (player.getGameMode() != GameMode.CREATIVE
                        && player.getGameMode() != GameMode.SPECTATOR
                        && (flights == null || flights.getInstance(player) == null)) {
                    player.setFlying(false);
                }
            }, 1L);
        }
    }

    public static void handleQuit(final Player player) {
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);

        Platform.scheduler().runLater(() -> BendingBoardManager.clean(player), 1);

        if (ProjectKorra.isStatisticsEnabled()) {
            Manager.getManager(StatisticsManager.class).store(player.getUniqueId());
        }
        if (bPlayer != null && ProjectKorra.isDatabaseCooldownsEnabled()) {
            bPlayer.saveCooldowns();
        }

        Commands.invincible.remove(player.getName());
        Preset.unloadPreset(player);

        if (TempArmor.hasTempArmor(player)) {
            for (final TempArmor armor : TempArmor.getTempArmorList(player)) {
                armor.revert();
            }
        }

        if (MetalClips.isControlled(player)) {
            MetalClips.removeControlledEnitity(player);
        }

        MultiAbilityManager.remove(player);
        removeActiveAbilities(player);

        if (bPlayer != null) {
            Platform.scheduler().runLater(() -> OfflineBendingPlayer.convertToOfflineAndUncache(bPlayer, 5 * 60 * 1000), 1L);
        }
    }

    public static MovementResult handleMove(final Player player, final Location from, final Location to,
                                            final boolean blockChanged, final boolean jumpDetected,
                                            final double jumpDelta) {
        if (from == null || to == null) {
            return MovementResult.pass();
        }

        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);

        if (handleSpoutMovement(player, bPlayer, from, to)) {
            return MovementResult.pass();
        }

        if (Bloodbending.isBloodbent(player)) {
            final BendingPlayer bender = Bloodbending.getBloodbender(player);
            if (bender.isAvatarState()) {
                return MovementResult.cancel();
            }

            final Location location = Bloodbending.getBloodbendingLocation(player);
            if (player.getWorld().equals(location.getWorld()) && !player.getVelocity().equals(Bloodbending.getBloodbendingVector(player))) {
                player.setVelocity(Bloodbending.getBloodbendingVector(player));
            }
            return MovementResult.pass();
        }

        if (bPlayer != null) {
            if (bPlayer.hasElement(Element.AIR) || bPlayer.hasElement(Element.CHI)) {
                PassiveHandler.checkExhaustionPassives(player);
            }

            if (bPlayer.hasElement(Element.WATER)) {
                FastSwim.applyCosmeticIceSpeed(player);
            }

            if (blockChanged) {
                FirePassive.handle(player);
            }
        }

        if (jumpDetected && isValidJumpOrigin(player) && isLegacyJumpDelta(jumpDelta)) {
            Platform.events().call(new PlayerJumpEvent(player, jumpDelta));
        }

        if (FallHandler.contains(player)) {
            FallHandler.move(player);
        }

        return MovementResult.pass();
    }

    public static void handleElementChanged(final Player player) {
        PassiveManager.registerPassives(player);
        FirePassive.handle(player);
        BendingBoardManager.updateAllSlots(player);
    }

    public static void handleBindChanged(final Player player) {
        BendingBoardManager.updateAllSlots(player);
    }

    public static void removeActiveAbilities(final Player player) {
        if (player == null) {
            return;
        }
        for (final CoreAbility active : List.copyOf(CoreAbility.getAbilitiesByInstances())) {
            if (active.getPlayer() != null
                    && player.getUniqueId().equals(active.getPlayer().getUniqueId())) {
                active.remove();
            }
        }
    }

    private static boolean handleSpoutMovement(final Player player, final BendingPlayer bPlayer,
                                               final Location from, final Location to) {
        final boolean hasWaterSpout = CoreAbility.hasAbility(player, WaterSpout.class);
        final boolean hasAirSpout = CoreAbility.hasAbility(player, AirSpout.class);
        if (!hasWaterSpout && !hasAirSpout) {
            return false;
        }

        final Vector movement = to.toVector().subtract(from.toVector());
        final Vector horizontal = movement.clone().setY(0);
        final var config = bPlayer != null ? ConfigManager.getConfig(bPlayer) : ConfigManager.defaultConfig.get();
        double maxSpeed = Double.MAX_VALUE;
        double airMaxSpeed = Double.MAX_VALUE;
        if (hasWaterSpout) {
            maxSpeed = Math.min(maxSpeed, Math.max(0, config.getDouble("Abilities.Water.WaterSpout.FlightSpeed", 0.2)));
        }
        if (hasAirSpout) {
            airMaxSpeed = Math.max(0, config.getDouble("Abilities.Air.AirSpout.FlightSpeed", 0.2));
            maxSpeed = Math.min(maxSpeed, airMaxSpeed);
        }

        final double upperMaxSpeed = maxSpeed + 0.01;
        final Vector cappedVelocity = player.getVelocity();
        final Vector horizontalVelocity = cappedVelocity.clone().setY(0);
        final boolean capHorizontal = horizontal.lengthSquared() > upperMaxSpeed * upperMaxSpeed
                && horizontalVelocity.lengthSquared() > upperMaxSpeed * upperMaxSpeed;
        final boolean capVertical = hasAirSpout
                && Math.abs(movement.getY()) > airMaxSpeed + 0.01
                && Math.abs(cappedVelocity.getY()) > airMaxSpeed + 0.01;
        if (capHorizontal || capVertical) {
            if (capHorizontal) {
                horizontalVelocity.normalize().multiply(maxSpeed);
                cappedVelocity.setX(horizontalVelocity.getX()).setZ(horizontalVelocity.getZ());
            }
            if (capVertical) {
                cappedVelocity.setY(Math.copySign(airMaxSpeed, cappedVelocity.getY()));
            }
            final CoreAbility owner = hasAirSpout
                    ? CoreAbility.getAbility(player, AirSpout.class)
                    : CoreAbility.getAbility(player, WaterSpout.class);
            if (owner != null) {
                // Movement events run outside CoreAbility#progress. Restore
                // the owning ability context so the prediction side records
                // the same action and impulse ordinal as the server metadata.
                AbilityExecutionContext.run(owner,
                        () -> GeneralMethods.setVelocity(owner, player, cappedVelocity));
            } else {
                player.setVelocity(cappedVelocity);
            }
        }
        return true;
    }

    private static boolean isValidJumpOrigin(final Player player) {
        final Material type = player.getLocation().getBlock().getType();
        return type != Material.VINE && type != Material.LADDER;
    }

    private static boolean isLegacyJumpDelta(final double jumpDelta) {
        return (jumpDelta < 0.035 || jumpDelta > 0.037) && (jumpDelta < 0.116 || jumpDelta > 0.118);
    }

    public record MovementResult(boolean cancelEvent) {
        public static MovementResult pass() {
            return new MovementResult(false);
        }

        public static MovementResult cancel() {
            return new MovementResult(true);
        }
    }
}
