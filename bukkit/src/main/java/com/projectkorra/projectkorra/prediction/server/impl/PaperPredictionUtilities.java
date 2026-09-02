package com.projectkorra.projectkorra.prediction.server.impl;

import com.projectkorra.projectkorra.prediction.protocol.PaperPredictionProtocol;
import com.projectkorra.projectkorra.prediction.snapshot.PaperPredictionSnapshot;
import com.projectkorra.projectkorra.prediction.snapshot.PaperRegionProtectionSnapshot;

import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.action.AbilityRemovalSync;
import com.projectkorra.projectkorra.prediction.action.NativeActionTagStream;
import com.projectkorra.projectkorra.prediction.action.PredictionActionSeed;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.prediction.authority.PredictionVisibility;
import com.projectkorra.projectkorra.prediction.authority.RegionProtectionAuthority;
import com.projectkorra.projectkorra.prediction.block.DirectBlockSync;
import com.projectkorra.projectkorra.prediction.block.TempBlockDeliveryTracker;
import com.projectkorra.projectkorra.prediction.block.TempBlockSync;
import com.projectkorra.projectkorra.prediction.block.TempFallingBlockSync;
import com.projectkorra.projectkorra.prediction.hit.ConfirmedHitEffects;
import com.projectkorra.projectkorra.prediction.hit.HitRewind;
import com.projectkorra.projectkorra.prediction.hit.HitRegistrationPolicy;
import com.projectkorra.projectkorra.prediction.movement.VelocitySync;
import com.projectkorra.projectkorra.prediction.state.AbilityCheckpointSync;
import com.projectkorra.projectkorra.prediction.state.AbilityStateSync;
import com.projectkorra.projectkorra.prediction.state.GlidingStateSync;
import com.projectkorra.projectkorra.prediction.state.CooldownSync;
import com.projectkorra.projectkorra.prediction.state.PlayerStatusSync;

import com.jedk1.jedcore.ability.passive.WallRun;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.ability.util.ComboManager;
import com.projectkorra.projectkorra.ability.util.MultiAbilityManager;
import com.projectkorra.projectkorra.ability.util.PassiveManager;
import com.projectkorra.projectkorra.firebending.FireBlastCharged;
import com.projectkorra.projectkorra.airbending.AirBurst;
import com.projectkorra.projectkorra.airbending.AirGlider;
import com.projectkorra.projectkorra.earthbending.EarthSmash;
import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.waterbending.passive.FastSwim;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import com.projectkorra.projectkorra.prediction.server.PaperPredictionServer;

public abstract class PaperPredictionUtilities extends PaperPredictionState {
    protected PaperPredictionUtilities(final JavaPlugin plugin) {
        super(plugin);
    }

    protected static void runWithOwner(UUID owner, Runnable task) {
        runWithOwner(owner, true, task);
    }

    protected static void runWithOwnerAndSequence(final UUID owner, final Long sequence,
                                                final Runnable task) {
        final Long previousSequence = INPUT_SEQUENCE.get();
        if (sequence == null) INPUT_SEQUENCE.remove();
        else INPUT_SEQUENCE.set(sequence);
        try {
            if (sequence == null || sequence <= 0L) runWithOwner(owner, true, task);
            else {
                final PaperPredictionServer server = PaperPredictionServer.activeInstance();
                final Session session = server == null ? null : server.sessions.get(owner);
                final Action action = session == null ? null : session.actions.get(sequence);
                final long seed = action == null ? PredictionDeterminism.currentSeed() : action.deterministicSeed;
                PredictionDeterminism.run(sequence, seed > 0L ? seed : sequence,
                        () -> runWithOwner(owner, true, task));
            }
        } finally {
            if (previousSequence == null) INPUT_SEQUENCE.remove();
            else INPUT_SEQUENCE.set(previousSequence);
        }
    }

    protected static void runWithOwner(UUID owner, boolean locallyPredicted, Runnable task) {
        UUID previous = EFFECT_OWNER.get();
        Boolean previousPredicted = EFFECT_PREDICTED.get();
        EFFECT_OWNER.set(owner);
        EFFECT_PREDICTED.set(locallyPredicted);
        try {
            task.run();
        } finally {
            if (previous == null) EFFECT_OWNER.remove();
            else EFFECT_OWNER.set(previous);
            if (previousPredicted == null) EFFECT_PREDICTED.remove();
            else EFFECT_PREDICTED.set(previousPredicted);
        }
    }

    protected static boolean createdAnyAbility(Set<CoreAbility> before, UUID owner) {
        for (CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
            if (!before.contains(ability) && ability.getPlayer() != null
                    && ability.getPlayer().getUniqueId().equals(owner)) return true;
        }
        return false;
    }

    protected static List<String> activeFlightAbilities(UUID playerId) {
        return CoreAbility.getAbilitiesByInstances().stream()
                .filter(ability -> !ability.isRemoved() && ability.getPlayer() != null
                        && playerId.equals(ability.getPlayer().getUniqueId()))
                .map(CoreAbility::getName)
        .filter(name -> name.equalsIgnoreCase("AirScooter") || name.equalsIgnoreCase("AirSpout")
                        || name.equalsIgnoreCase("WaterSpout") || name.equalsIgnoreCase("SandSpout")
                        || name.equalsIgnoreCase("AirGlider")
                        || name.equalsIgnoreCase("FireJet")
                        || name.equalsIgnoreCase("Flight"))
                .map(name -> name.toLowerCase(Locale.ROOT)).distinct().sorted().toList();
    }

    protected static Set<CoreAbility> identitySet(Iterable<CoreAbility> abilities) {
        Set<CoreAbility> result = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CoreAbility ability : abilities) result.add(ability);
        return result;
    }

    protected static Vector direction(float yaw, float pitch) {
        return new Location(null, 0, 0, 0, yaw, pitch).getDirection().normalize();
    }

    protected static boolean isSneakTransition(PaperPredictionProtocol.InputKind kind) {
        return kind == PaperPredictionProtocol.InputKind.SNEAK_START || kind == PaperPredictionProtocol.InputKind.SNEAK_STOP;
    }

    protected static List<String> inputVetoCooldowns(final String ability,
                                                   final PaperPredictionProtocol.InputKind kind) {
        if (ability == null || ability.isBlank()) return List.of();
        if (ability.equalsIgnoreCase("PhaseChange")) {
            if (kind == PaperPredictionProtocol.InputKind.LEFT_CLICK) {
                return List.of(ability, "PhaseChangeFreeze");
            }
            if (kind == PaperPredictionProtocol.InputKind.SNEAK_START) {
                return List.of(ability, "PhaseChangeMelt");
            }
        }
        return List.of(ability);
    }

    protected static String logicalInputAbility(com.projectkorra.projectkorra.platform.mc.entity.Player player,
                                              BendingPlayer bending, PaperPredictionProtocol.InputKind kind,
                                              String fallback) {
        if (player == null || bending == null) return fallback == null ? "" : fallback;
        if (kind == PaperPredictionProtocol.InputKind.SNEAK_START && !CoreAbility.hasAbility(player, FastSwim.class)) {
            CoreAbility bound = bending.getBoundAbility();
            CoreAbility passive = CoreAbility.getAbility(FastSwim.class);
            if ((bound == null || !bound.isSneakAbility()) && PassiveManager.hasPassive(player, passive)) {
                return "FastSwim";
            }
        }
        String multi = MultiAbilityManager.getBoundMultiAbility(player);
        if (multi != null && !multi.isBlank()) return multi;
        String selected = fallback == null ? "" : fallback;
        if (selected.equalsIgnoreCase("FireBlast") && isSneakTransition(kind)) {
            return "FireBlastCharged";
        }
        if (selected.isBlank() && kind == PaperPredictionProtocol.InputKind.LEFT_CLICK
                && bending.isToggled() && CoreAbility.getAbility(WallRun.class) != null) {
            return "WallRun";
        }
        return selected;
    }

    protected static boolean matchesInputAbility(CoreAbility ability, String inputName) {
        return ability != null && inputName != null
                && (inputName.equalsIgnoreCase(ability.getName())
                || inputName.equalsIgnoreCase("FireBlastCharged") && ability instanceof FireBlastCharged);
    }

    protected static String materialName(Material material) {
        return "minecraft:" + material.name().toLowerCase(Locale.ROOT);
    }

    protected static String worldKey(com.projectkorra.projectkorra.platform.mc.World world) {
        if (world == null || !(world.handle() instanceof World bukkitWorld)) return "";
        return bukkitWorld.getKey().toString();
    }

    protected static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    protected static ComboManager.AbilityInformation latestComboInput(
            final com.projectkorra.projectkorra.platform.mc.entity.Player player) {
        if (player == null) return null;
        final List<ComboManager.AbilityInformation> recent =
                ComboManager.getRecentlyUsedAbilities(player, 1);
        return recent.isEmpty() ? null : recent.get(recent.size() - 1);
    }
}
