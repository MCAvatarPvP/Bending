package com.projectkorra.projectkorra.fabric.client.prediction.movement;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.ability.util.PassiveManager;
import com.projectkorra.projectkorra.earthbending.EarthArmor;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.waterbending.WaterSpout;
import com.projectkorra.projectkorra.waterbending.multiabilities.WaterArms;
import com.projectkorra.projectkorra.waterbending.passive.FastSwim;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Retains recent shift-held decisions so opening chat does not erase a failed swim attempt. */
public final class FastSwimPredictionDiagnostics {
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private String previousState = "";
    private long writeTick = -1;
    private String write = "none";

    public void recordWrite(long tick, boolean blocked, Vec3d velocity) {
        writeTick = tick;
        write = (blocked ? "blocked" : "applied") + velocity;
    }

    public void sample(long tick, MinecraftClient client, BendingPlayer bending, boolean fenced) {
        if (client.player == null || bending == null) return;
        final Player player = bending.getPlayer();
        if (!client.player.isSneaking() && !player.isSneaking()) return;
        try {
            final CoreAbility prototype = CoreAbility.getAbility(FastSwim.class);
            final CoreAbility bound = bending.getBoundAbility();
            final Location feet = player.getLocation();
            final String state = "keySneak=" + client.player.isSneaking()
                    + " predictedSneak=" + player.isSneaking()
                    + " nativeWater=" + client.player.isTouchingWater()
                    + " samples=" + waterSample(feet) + "/" + waterSample(feet.clone().add(0, .5, 0))
                    + "/" + waterSample(player.getEyeLocation())
                    + " active=" + CoreAbility.hasAbility(player, FastSwim.class)
                    + " eligible=" + PassiveManager.hasPassive(player, prototype)
                    + " enabled=" + (prototype != null && prototype.isEnabled())
                    + " hasWater=" + bending.hasElement(Element.WATER)
                    + " passivePerm=" + player.hasPermission("bending.water.passive")
                    + " abilityPerm=" + player.hasPermission("bending.ability.FastSwim")
                    + " canBend=" + (prototype != null && bending.canBendPassive(prototype))
                    + " canUse=" + (prototype != null && bending.canUsePassive(prototype))
                    + " cooldown=" + bending.isOnCooldown("FastSwim")
                    + " bound=" + (bound == null ? "none" : bound.getName())
                    + " boundSneak=" + (bound != null && bound.isSneakAbility())
                    + " spout=" + CoreAbility.hasAbility(player, WaterSpout.class)
                    + " arms=" + CoreAbility.hasAbility(player, WaterArms.class)
                    + " armor=" + CoreAbility.hasAbility(player, EarthArmor.class)
                    + " speed=" + FastSwim.getSwimSpeed(bending)
                    + " velocity=" + (writeTick == tick ? write : "none")
                    + " externalFence=" + fenced;
            remember(tick, state);
        } catch (RuntimeException failure) {
            remember(tick, "diagnostic unavailable: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    private static String waterSample(Location location) {
        final var block = location.getBlock();
        return block.getType() + ":" + ElementalAbility.isWater(block);
    }

    private void remember(long tick, String state) {
        if (state.equals(previousState)) return;
        previousState = state;
        history.addLast("tick=" + tick + " " + state);
        while (history.size() > 8) history.removeFirst();
    }

    public List<String> report() {
        final List<String> report = new ArrayList<>();
        report.add("FastSwim prediction (recent shift-held states, oldest first):");
        if (history.isEmpty()) report.add("No local shift-held tick recorded in this session.");
        else report.addAll(history);
        return List.copyOf(report);
    }

    public void clear() {
        history.clear();
        previousState = "";
        writeTick = -1;
        write = "none";
    }
}
