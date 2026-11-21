package com.projectkorra.projectkorra.prediction.action;

import com.projectkorra.projectkorra.prediction.protocol.PaperPredictionProtocol.MeleeAction;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeMeleeActionStreamTest {
    private static final UUID SESSION = new UUID(1, 2);
    private static final UUID VICTIM = new UUID(3, 4);
    private static final UUID OTHER = new UUID(3, 5);
    private static final MeleeAction TAG = new MeleeAction(SESSION, 81, 4, "Uppercut", 23, VICTIM);

    @Test void damageOnlyClaimsOneRealAcceptedAttackAgainstTheSameEntity() {
        var stream = new NativeMeleeActionStream();
        stream.offer(TAG, 8);
        assertNull(stream.damage(8, 23, VICTIM));
        stream.attack(9, 23, VICTIM, true);
        assertSame(TAG, stream.damage(9, 23, VICTIM));
        assertNull(stream.damage(9, 23, VICTIM));
        stream.offer(TAG, 10);
        stream.attack(10, 23, OTHER, true);
        assertNull(stream.damage(10, 23, OTHER));
        stream.offer(TAG, 11);
        stream.attack(11, 23, VICTIM, true);
        assertNull(stream.damage(11, 23, OTHER));
        assertNull(stream.damage(11, 23, VICTIM));
        stream.offer(TAG, 12);
        stream.attack(12, 23, VICTIM, false);
        assertNull(stream.damage(12, 23, VICTIM));
    }

    @Test void stalePacketsAndInterveningInputsCannotClaimAnotherStrike() {
        var stream = new NativeMeleeActionStream();
        stream.offer(TAG, 3);
        stream.attack(6, 23, VICTIM, true);
        assertNull(stream.damage(6, 23, VICTIM));
        stream.offer(TAG, 9);
        stream.attack(9, 23, VICTIM, true);
        assertNull(stream.damage(10, 23, VICTIM));
        stream.offer(TAG, 12);
        stream.attack(12, 23, VICTIM, true);
        stream.clear();
        assertNull(stream.damage(12, 23, VICTIM));
        stream.offer(TAG, 13);
        stream.attack(13, 24, VICTIM, true);
        stream.attack(13, 23, VICTIM, true);
        assertNull(stream.damage(13, 23, VICTIM));
    }
}
