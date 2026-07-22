package com.projectkorra.projectkorra.prediction.action;

import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.action.AbilityRemovalSync;
import com.projectkorra.projectkorra.prediction.hit.PredictedContactSync;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void authoritativeValidationRejectionOverridesAnOptimisticLifecycle() {
        DummyAbility ability = new DummyAbility("EarthSmash");
        AtomicBoolean external = new AtomicBoolean(false);
        AtomicBoolean rejected = new AtomicBoolean(false);
        listener = new AbilityRemovalSync.Listener() {
            @Override
            public void onRemoved(CoreAbility removed, boolean externallyCaused) {
                external.set(externallyCaused);
            }

            @Override
            public void onRemoved(CoreAbility removed, boolean externallyCaused,
                                  boolean predictionRejected) {
                external.set(externallyCaused);
                rejected.set(predictionRejected);
            }
        };
        AbilityRemovalSync.install(listener);

        AbilityExecutionContext.run(ability, () -> AbilityRemovalSync.runAuthoritativeRejection(
                () -> AbilityRemovalSync.publish(ability)));

        assertTrue(external.get());
        assertTrue(rejected.get());
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

    @Test
    void ownershipTransferNotifiesBothExactControllersWithoutRemovingTheAbility() {
        DummyAbility ability = new DummyAbility("EarthSmash");
        UUID previous = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        AtomicReference<CoreAbility> transferred = new AtomicReference<>();
        AtomicReference<UUID> observedPrevious = new AtomicReference<>();
        AtomicReference<UUID> observedNext = new AtomicReference<>();
        listener = new AbilityRemovalSync.Listener() {
            @Override
            public void onRemoved(CoreAbility removed, boolean externallyCaused) {
            }

            @Override
            public void onOwnerTransferred(CoreAbility moved, UUID oldOwner, UUID newOwner) {
                transferred.set(moved);
                observedPrevious.set(oldOwner);
                observedNext.set(newOwner);
            }
        };
        AbilityRemovalSync.install(listener);

        AbilityRemovalSync.ownerTransferred(ability, previous, next);

        assertEquals(ability, transferred.get());
        assertEquals(previous, observedPrevious.get());
        assertEquals(next, observedNext.get());
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
