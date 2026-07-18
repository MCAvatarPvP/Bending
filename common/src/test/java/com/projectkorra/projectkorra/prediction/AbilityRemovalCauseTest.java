package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbilityRemovalCauseTest {
    private AbilityRemovalSync.Listener listener;

    @AfterEach
    void clearListener() {
        AbilityRemovalSync.clear(listener);
    }

    @Test
    void ordinarySelfProgressRemovalRemainsLocallyPredictable() {
        DummyAbility ability = new DummyAbility("WaterSpout");
        AtomicBoolean external = new AtomicBoolean(true);
        listener = (removed, externallyCaused) -> external.set(externallyCaused);
        AbilityRemovalSync.install(listener);

        AbilityExecutionContext.run(ability, () -> AbilityRemovalSync.publish(ability));

        assertFalse(external.get());
    }

    @Test
    void collisionScopeIsExternalEvenIfEnteredFromAnAbilityContext() {
        DummyAbility ability = new DummyAbility("WaterSpout");
        AtomicBoolean external = new AtomicBoolean(false);
        listener = (removed, externallyCaused) -> external.set(externallyCaused);
        AbilityRemovalSync.install(listener);

        AbilityExecutionContext.run(ability, () -> AbilityRemovalSync.runExternalCause(
                () -> AbilityRemovalSync.publish(ability)));

        assertTrue(external.get());
    }

    @Test
    void oneAbilityRemovingAnotherIsExternalWithoutSpecialCases() {
        DummyAbility source = new DummyAbility("FireBlast");
        DummyAbility removed = new DummyAbility("WaterSpout");
        AtomicBoolean external = new AtomicBoolean(false);
        listener = (ability, externallyCaused) -> external.set(externallyCaused);
        AbilityRemovalSync.install(listener);

        AbilityExecutionContext.run(source, () -> AbilityRemovalSync.publish(removed));

        assertTrue(external.get());
    }

    @Test
    void authoritativeClientCleanupHasAnExactScopedCause() {
        DummyAbility ability = new DummyAbility("AirBlast");
        AtomicBoolean forcedInside = new AtomicBoolean(false);

        PredictedContactSync.forceRemoval(ability,
                () -> forcedInside.set(PredictedContactSync.isForcedRemoval(ability)));

        assertTrue(forcedInside.get());
        assertFalse(PredictedContactSync.isForcedRemoval(ability));
    }

    private static final class DummyAbility extends CoreAbility {
        private final String name;

        private DummyAbility(final String name) {
            super(null);
            this.name = name;
        }

        @Override public void progress() { }
        @Override public boolean isSneakAbility() { return false; }
        @Override public boolean isHarmlessAbility() { return true; }
        @Override public boolean isIgniteAbility() { return false; }
        @Override public boolean isExplosiveAbility() { return false; }
        @Override public long getCooldown() { return 0; }
        @Override public String getName() { return name; }
        @Override public Element getElement() { return Element.WATER; }
        @Override public Location getLocation() { return null; }
    }
}
