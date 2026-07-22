package com.projectkorra.projectkorra.prediction.hit;

import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.hit.PredictedContactSync;
import com.projectkorra.projectkorra.prediction.state.CooldownSync;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PredictedContactSyncTest {
    private final AtomicBoolean contactReported = new AtomicBoolean();
    private final PredictedContactSync.Listener contactSide =
            (ability, target) -> contactReported.set(true);
    private final CooldownSync.Listener predictionSide = new CooldownSync.Listener() {
        @Override public boolean isAuthoritative() { return false; }
        @Override public void onAdded(CoreAbility source, BendingPlayer player, String ability, long expiresAtMillis) { }
        @Override public void onRemoved(BendingPlayer player, String ability) { }
    };

    @AfterEach
    void cleanUp() {
        CooldownSync.clear(predictionSide);
        PredictedContactSync.clear(contactSide);
    }

    @Test
    void remoteStateIsSuppressedWhileReportingContactEvidence() {
        CooldownSync.install(predictionSide);
        PredictedContactSync.install(contactSide);
        DummyAbility ability = new DummyAbility(new FakePlayer(new UUID(1L, 2L)));
        Entity remote = new FakeEntity(new UUID(3L, 4L));
        AtomicBoolean continued = new AtomicBoolean();

        AbilityExecutionContext.run(ability, () -> {
            assertTrue(PredictedContactSync.mark(ability, remote));
            continued.set(true);
        });

        assertTrue(continued.get(), "a remote contact must not abandon later TempBlock/visual work");
        assertTrue(contactReported.get(), "the server-validation path must receive the contact evidence");
        assertFalse(ability.isRemoved(), "suppressing remote state does not itself remove the ability");
    }

    private static final class FakePlayer extends Player {
        private final UUID id;
        private FakePlayer(UUID id) { this.id = id; }
        @Override public UUID getUniqueId() { return id; }
    }

    private static final class FakeEntity extends Entity {
        private final UUID id;
        private FakeEntity(UUID id) { this.id = id; }
        @Override public UUID getUniqueId() { return id; }
    }

    private static final class DummyAbility extends CoreAbility {
        private DummyAbility(Player player) { this.player = player; }
        @Override public void progress() { }
        @Override public boolean isSneakAbility() { return false; }
        @Override public boolean isHarmlessAbility() { return true; }
        @Override public boolean isIgniteAbility() { return false; }
        @Override public boolean isExplosiveAbility() { return false; }
        @Override public long getCooldown() { return 0; }
        @Override public String getName() { return "RemoteContactTest"; }
        @Override public Element getElement() { return Element.WATER; }
        @Override public Location getLocation() { return null; }
    }
}
