package com.projectkorra.projectkorra.prediction.combat;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.ability.util.CollisionManager;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AirFireCombatTest {
    @AfterEach
    void deactivate() {
        AirFireCombat.deactivate();
    }

    @Test
    void irreversiblePlayerEffectWaitsForTheResponseWindow() {
        AirFireCombat.activate();
        final DummyAbility ability = new DummyAbility(new TestPlayer(UUID.randomUUID()));
        final TestPlayer target = new TestPlayer(UUID.randomUUID());
        target.ping = 20;
        final AtomicBoolean committed = new AtomicBoolean();

        assertTrue(deferDamage(ability, target, () -> committed.set(true)));
        assertTrue(AirFireCombat.deferRemoval(ability));
        assertFalse(committed.get());
        advanceTicks(2);

        assertTrue(committed.get());
    }

    @Test
    void highPingUsesThreeTicksOnlyAsTheCap() {
        AirFireCombat.activate();
        final DummyAbility ability = new DummyAbility(new TestPlayer(UUID.randomUUID()));
        final TestPlayer target = new TestPlayer(UUID.randomUUID());
        target.ping = 250;
        final AtomicBoolean committed = new AtomicBoolean();

        assertTrue(deferDamage(ability, target, () -> committed.set(true)));
        assertTrue(AirFireCombat.deferRemoval(ability));
        advanceTicks(3);
        assertFalse(committed.get());
        advanceTicks(1);
        assertTrue(committed.get());
    }

    @Test
    void movementEscapeCancelsTheProvisionalContact() {
        AirFireCombat.activate((target, timelineTick) -> new Vector(1.0, 0.9, 0.0));
        final DummyAbility ability = new DummyAbility(new TestPlayer(UUID.randomUUID()));
        final TestPlayer target = new TestPlayer(UUID.randomUUID());
        final AtomicBoolean committed = new AtomicBoolean();

        assertTrue(deferDamage(ability, target, () -> committed.set(true)));
        assertTrue(AirFireCombat.deferRemoval(ability));
        advanceTicks(2);

        assertFalse(committed.get());
        assertFalse(AirFireCombat.isSuspended(ability),
                "a dodged projectile must resume instead of disappearing at its old contact");
    }

    @Test
    void movementAfterContactIsNotGrantedByTheConfirmationDelay() {
        final AtomicReference<Long> requestedTick = new AtomicReference<>();
        AirFireCombat.activate((target, timelineTick) -> {
            requestedTick.set(timelineTick);
            return new Vector(0.0, 0.9, 0.0);
        });
        final DummyAbility ability = new DummyAbility(new TestPlayer(UUID.randomUUID()));
        final TestPlayer target = new TestPlayer(UUID.randomUUID());
        final AtomicBoolean committed = new AtomicBoolean();
        final long contactTick = CoreAbility.getCurrentTick() + 1L;

        assertTrue(deferDamage(ability, target, () -> committed.set(true)));
        assertTrue(AirFireCombat.deferRemoval(ability));
        target.centerX = 1.0;
        advanceTicks(2);

        assertTrue(committed.get());
        assertEquals(contactTick, requestedTick.get());
    }

    @Test
    void recentMeasuredJitterAddsOnlyOneBoundedTick() {
        AirFireCombat.activate();
        final DummyAbility ability = new DummyAbility(new TestPlayer(UUID.randomUUID()));
        final TestPlayer target = new TestPlayer(UUID.randomUUID());
        target.ping = 80;
        AirFireCombat.registerAction(target.getUniqueId(), 12L, 1, 1);
        final AtomicBoolean committed = new AtomicBoolean();

        assertTrue(deferDamage(ability, target, () -> committed.set(true)));
        assertTrue(AirFireCombat.deferRemoval(ability));
        advanceTicks(2);
        assertFalse(committed.get());
        advanceTicks(1);
        assertTrue(committed.get());
    }

    @Test
    void stableSampleClearsAnOlderJitterSpike() {
        AirFireCombat.activate();
        final DummyAbility ability = new DummyAbility(new TestPlayer(UUID.randomUUID()));
        final TestPlayer target = new TestPlayer(UUID.randomUUID());
        target.ping = 80;
        AirFireCombat.registerAction(target.getUniqueId(), 12L, 1, 1);
        AirFireCombat.registerAction(target.getUniqueId(), 13L, 1, 0);
        final AtomicBoolean committed = new AtomicBoolean();

        assertTrue(deferDamage(ability, target, () -> committed.set(true)));
        assertTrue(AirFireCombat.deferRemoval(ability));
        advanceTicks(2);

        assertTrue(committed.get());
    }

    @Test
    void registeredCollisionRemovalCancelsAProvisionalHit() {
        AirFireCombat.activate();
        final DummyAbility attack = new DummyAbility(new TestPlayer(UUID.randomUUID()));
        final DummyAbility defense = new DummyAbility(new TestPlayer(UUID.randomUUID()));
        final TestPlayer target = new TestPlayer(UUID.randomUUID());
        final AtomicBoolean committed = new AtomicBoolean();
        assertTrue(deferDamage(attack, target, () -> committed.set(true)));
        assertTrue(AirFireCombat.deferRemoval(attack));

        AirFireCombat.resolveCollision(new Collision(attack, defense, true, false),
                Long.MIN_VALUE, () -> { });
        advanceTicks(2);

        assertFalse(committed.get());
    }

    @Test
    void defenseCreatedAfterContactCannotRetroactivelyCancelIt() {
        AirFireCombat.activate();
        final DummyAbility attack = new DummyAbility(new TestPlayer(UUID.randomUUID()));
        final DummyAbility defense = new DummyAbility(new TestPlayer(UUID.randomUUID()));
        final TestPlayer target = new TestPlayer(UUID.randomUUID());
        final AtomicBoolean committed = new AtomicBoolean();
        final long contactTick = CoreAbility.getCurrentTick() + 1L;
        assertTrue(deferDamage(attack, target, () -> committed.set(true)));
        assertTrue(AirFireCombat.deferRemoval(attack));

        AirFireCombat.resolveCollision(new Collision(attack, defense, true, false),
                contactTick + 1L, () -> { });
        advanceTicks(2);

        assertTrue(committed.get());
    }

    @Test
    void actionAgeIsInheritedByTheConstructedAbility() {
        AirFireCombat.activate();
        final TestPlayer owner = new TestPlayer(UUID.randomUUID());
        AirFireCombat.registerAction(owner.getUniqueId(), 91L, 3);
        final AtomicReference<DummyAbility> created = new AtomicReference<>();
        PredictionDeterminism.run(91L, 7L,
                () -> created.set(new DummyAbility(owner)));
        AirFireCombat.onAbilityStarted(created.get());

        assertEquals(3, AirFireCombat.timelineOffset(created.get()));
    }

    @Test
    void activationTimeTargetEffectsAreNotMisclassifiedAsProjectileHits() {
        AirFireCombat.activate();
        final DummyAbility ability = new DummyAbility(new TestPlayer(UUID.randomUUID()));
        final TestPlayer target = new TestPlayer(UUID.randomUUID());

        assertFalse(AirFireCombat.deferVelocity(ability, target, () -> { }));
    }

    @Test
    void collisionManagerPolicySelectsRegisteredRemovableContacts() {
        final CollisionManager manager = new CollisionManager();
        final DummyAbility attack = new DummyAbility(new TestPlayer(UUID.randomUUID()));
        final DummyDefense defense = new DummyDefense(new TestPlayer(UUID.randomUUID()));
        manager.addCollision(new Collision(attack, defense, true, false));

        assertTrue(manager.participates(attack));
        assertTrue(manager.participates(defense));
        assertTrue(manager.canBeRemoved(attack));
        assertFalse(manager.canBeRemoved(defense));
    }

    @Test
    void registeredTimelinePolicyDoesNotCaptureUnregisteredAreaEffects() {
        AirFireCombat.activate(null, AirFireCombat.ReactiveCollisionPolicy.NONE);
        final DummyAbility ability = new DummyAbility(new TestPlayer(UUID.randomUUID()));
        final TestPlayer target = new TestPlayer(UUID.randomUUID());
        final AtomicBoolean deferred = new AtomicBoolean(true);

        AbilityExecutionContext.run(ability, () -> deferred.set(
                AirFireCombat.deferVelocity(ability, target, () -> { })));

        assertFalse(deferred.get());
        assertFalse(AirFireCombat.isTimelineManaged(ability));
    }

    @Test
    void sustainedContactCommitsAfterTheSameCollisionPass() {
        AirFireCombat.activate();
        final DummyAbility ability = new DummyAbility(new TestPlayer(UUID.randomUUID()));
        final TestPlayer target = new TestPlayer(UUID.randomUUID());
        target.ping = 250;
        final AtomicBoolean committed = new AtomicBoolean();

        assertTrue(deferDamage(ability, target, () -> committed.set(true)));
        assertFalse(committed.get());
        AirFireCombat.tick();

        assertTrue(committed.get(),
                "a persistent contact must not inherit a projectile response delay");
    }

    private static void advanceTicks(final int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            CoreAbility.progressAll();
            AirFireCombat.tick();
        }
    }

    private static boolean deferDamage(final DummyAbility ability, final TestPlayer target,
                                       final Runnable mutation) {
        final AtomicBoolean deferred = new AtomicBoolean();
        AbilityExecutionContext.run(ability, () -> deferred.set(
                AirFireCombat.deferDamage(ability, target, mutation)));
        return deferred.get();
    }

    private static final class TestPlayer extends Player {
        private final UUID id;
        private final World world = new World();
        private double centerX;
        private int ping;

        private TestPlayer(final UUID id) {
            this.id = id;
        }

        @Override public UUID getUniqueId() { return id; }
        @Override public World getWorld() { return world; }
        @Override public boolean isValid() { return true; }
        @Override public boolean isDead() { return false; }
        @Override public int getPing() { return ping; }
        @Override public BoundingBox getBoundingBox() {
            return new BoundingBox(new Vector(centerX - 0.3, 0, -0.3),
                    new Vector(centerX + 0.3, 1.8, 0.3));
        }
    }

    private static class DummyAbility extends CoreAbility {
        private final Player owner;

        private DummyAbility(final Player owner) {
            super(null);
            this.owner = owner;
        }

        @Override public Player getPlayer() { return owner; }
        @Override public void progress() { }
        @Override public boolean isSneakAbility() { return false; }
        @Override public boolean isHarmlessAbility() { return false; }
        @Override public boolean isIgniteAbility() { return false; }
        @Override public boolean isExplosiveAbility() { return false; }
        @Override public long getCooldown() { return 0; }
        @Override public String getName() { return "ReactiveTest"; }
        @Override public Element getElement() { return Element.FIRE; }
        @Override public Location getLocation() { return null; }
    }

    private static final class DummyDefense extends DummyAbility {
        private DummyDefense(final Player owner) {
            super(owner);
        }
    }
}
