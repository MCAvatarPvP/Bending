package com.projectkorra.projectkorra.ability;

import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.prediction.rollback.RollbackClock;
import com.projectkorra.projectkorra.prediction.rollback.RollbackRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public abstract class ChiCombatAbility extends ElementalAbility {

    private final long chiCombatSeed = PredictionDeterminism.combatSeed();
    private Map<UUID, Integer> chiBlockAttempts;

    /** Independent rolls per victim keep multi-hit strikes stable when candidate iteration order differs. */
    public double chiBlockRoll(Player victim) {
        if (RollbackClock.active() || chiCombatSeed <= 0) return RollbackRandom.fraction();
        if (chiBlockAttempts == null) chiBlockAttempts = new HashMap<>();
        int ordinal = chiBlockAttempts.merge(victim.getUniqueId(), 1, Integer::sum);
        return PredictionDeterminism.random(player.getUniqueId(),
                "chi-block/" + getName() + "/" + victim.getUniqueId() + "/" + ordinal, chiCombatSeed).nextDouble();
    }

    public static double passiveChiBlockRoll(Player attacker, Player victim) {
        if (AbilityExecutionContext.current() instanceof ChiCombatAbility ability) return ability.chiBlockRoll(victim);
        long seed = PredictionDeterminism.combatSeed();
        if (RollbackClock.active() || seed <= 0) return RollbackRandom.fraction();
        return PredictionDeterminism.random(attacker.getUniqueId(), "chi-passive/" + victim.getUniqueId(), seed).nextDouble();
    }

    protected ChiCombatAbility(Player player) {
        super(player);
    }
}
