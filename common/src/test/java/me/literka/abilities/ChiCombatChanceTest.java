package me.literka.abilities;

import com.projectkorra.projectkorra.ability.ChiAbility;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.support.AbilityWorld;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ChiCombatChanceTest {
    @Test void serverOwnedRollsSurviveDifferentLocalActionIdsAndTargetIterationOrder() throws Exception {
        try (var ignored = new AbilityWorld()) {
            var attacker = player(1); var a = player(2); var b = player(3);
            UUID session = new UUID(17, 31);
            long seed = PredictionDeterminism.combatSeed(session, 40);
            Probe[] skills = new Probe[2];
            double[] passive = new double[2];
            PredictionDeterminism.runCombat(40, 252, seed, () -> {
                skills[0] = new Probe(attacker);
                passive[0] = ChiAbility.passiveChiBlockRoll(attacker, a);
            });
            PredictionDeterminism.runCombat(638, 447, seed, () -> {
                skills[1] = new Probe(attacker);
                passive[1] = ChiAbility.passiveChiBlockRoll(attacker, a);
            });
            assertEquals(passive[0], passive[1]);
            assertEquals(0, PredictionDeterminism.combatSeed());
            double first = skills[0].chiBlockRoll(a);
            double other = skills[0].chiBlockRoll(b);
            double second = skills[0].chiBlockRoll(a);
            assertEquals(other, skills[1].chiBlockRoll(b));
            assertEquals(first, skills[1].chiBlockRoll(a));
            assertEquals(second, skills[1].chiBlockRoll(a));
            assertNotEquals(first, second);
            assertNotEquals(other, first);
            assertNotEquals(seed, PredictionDeterminism.combatSeed(session, 41));
            assertNotEquals(seed, PredictionDeterminism.combatSeed(new UUID(17, 32), 40));
        }
    }

    private static Player player(int id) {
        return new Player() {
            @Override public UUID getUniqueId() { return new UUID(0, id); }
            @Override public boolean isSneaking() { return true; }
        };
    }

    private static final class Probe extends RopeDart {
        private Probe(Player owner) { super(owner); }
        @Override public boolean isEnabled() { return false; }
    }
}
