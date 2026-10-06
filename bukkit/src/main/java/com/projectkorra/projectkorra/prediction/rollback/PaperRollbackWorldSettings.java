package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import io.papermc.paper.configuration.GlobalConfiguration;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.TreeMap;

/** One-time capture through compiled Paper/Spigot/native APIs; never retains the source world. */
public final class PaperRollbackWorldSettings {
    private PaperRollbackWorldSettings() { }
    public static RollbackWorldSettings capture(ServerLevel world, long randomSeed, long soundSeed) {
        if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Capture world settings on the tick thread before replay");
        var paper = world.paperConfig(); var spigot = world.spigotConfig; var global = GlobalConfiguration.get().unsupportedSettings;
        var bukkit = world.getWorld();
        var policy = new RollbackWorldSettings.Policy(RollbackWorldSettings.Dimension.valueOf(bukkit.getEnvironment().name()), world.isPvpAllowed(),
                global.skipVanillaDamageTickWhenShieldBlocked, global.updateEquipmentOnPlayerActions, paper.scoreboards.allowNonPlayerEntitiesOnScoreboards,
                paper.collisions.allowPlayerCrammingDamage, paper.collisions.maxEntityCollisions, spigot.jumpWalkExhaustion, spigot.jumpSprintExhaustion,
                spigot.regenExhaustion, paper.tickRates.containerUpdate, paper.entities.behavior.parrotsAreUnaffectedByPlayerMovement,
                bukkit.isVoidDamageEnabled(), bukkit.getVoidDamageAmount(), bukkit.getVoidDamageMinBuildHeightOffset(), paper.environment.netherCeilingVoidDamageHeight.value());
        return capture(world.getGameRules(), world.getGameTime(), randomSeed, soundSeed, world.getSeaLevel(), RollbackWorldSettings.Difficulty.valueOf(world.getDifficulty().name()), policy);
    }
    /** Also supports capture from an already detached native rule set. */
    public static RollbackWorldSettings capture(GameRules rules, long time, long randomSeed, long soundSeed, int seaLevel,
                                               RollbackWorldSettings.Difficulty difficulty, RollbackWorldSettings.Policy policy) {
        if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Capture native rules before replay");
        var values = new TreeMap<String, RollbackWorldSettings.Rule>();
        rules.availableRules().forEach(rule -> values.put(rule.getIdentifier().toString(), value(rules, rule)));
        return new RollbackWorldSettings(time, randomSeed, soundSeed, seaLevel, difficulty, policy, values);
    }
    private static <T> RollbackWorldSettings.Rule value(GameRules rules, GameRule<T> rule) {
        Object value = rules.get(rule);
        if (value instanceof Boolean flag) return new RollbackWorldSettings.Flag(flag);
        if (value instanceof Integer number) return new RollbackWorldSettings.IntegerRule(number);
        throw new IllegalArgumentException("Unsupported native rule type: " + rule.getIdentifier());
    }
}
