package com.projectkorra.projectkorra.fabric.client.prediction.action;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ClientAbilityIdentityTest {
    @Test
    void delayedRegrabOfExistingSmashDoesNotSelectNewlyPredictedSmashOnThatInput() {
        final ClientAbilityIdentity<Smash> identities = new ClientAbilityIdentity<>();
        final Smash original = new Smash(10);
        final Smash mistakenlyCreatedByRegrab = new Smash(30);
        final List<Smash> live = List.of(mistakenlyCreatedByRegrab, original);
        assertSame(original, identities.find(501, 10, live, Smash::creation));
        identities.bind(501, original);
        // Further throw/release/grab actions and expired action aliases cannot
        // change which instance a checkpoint for server creation 501 describes.
        assertSame(original, identities.find(501, 0, live, Smash::creation));
        assertNull(identities.find(502, 20, live, Smash::creation));
    }

    @Test
    void repeatedCheckpointReusesAuthorityRestoredInstanceWithoutLocalCreationAlias() {
        final ClientAbilityIdentity<Smash> identities = new ClientAbilityIdentity<>();
        assertNull(identities.find(501, 0, List.of(), Smash::creation));
        final Smash restored = new Smash(30);
        identities.bind(501, restored);
        assertSame(restored, identities.find(501, 0, List.of(restored), Smash::creation));
        identities.retain(List.of());
        assertNull(identities.find(501, 0, List.of(restored), Smash::creation));
    }

    @Test
    void creationIdentityDistinguishesTwoLegitimateSmashesWithSharedTransitions() {
        final ClientAbilityIdentity<Smash> identities = new ClientAbilityIdentity<>();
        final Smash first = new Smash(10);
        final Smash second = new Smash(20);
        identities.bind(501, first);
        identities.bind(502, second);
        final List<Smash> live = List.of(second, first);
        assertSame(first, identities.find(501, 10, live, Smash::creation));
        assertSame(second, identities.find(502, 20, live, Smash::creation));
        identities.clear();
        assertNull(identities.find(501, 0, live, Smash::creation));
    }

    private record Smash(long creation) { }
}
