package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.airbending.Tornado;
import com.projectkorra.projectkorra.airbending.flight.FlightMultiAbility;
import com.projectkorra.projectkorra.avatar.AvatarState;
import com.projectkorra.projectkorra.firebending.FireJet;
import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.platform.mc.GameMode;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RollbackFlightPolicyTest {
    @Test void sharedPolicyPreservesExistingAbilityRestrictionsAndCreativeException() throws Exception {
        var field = CoreAbility.class.getDeclaredField("INSTANCES_BY_PLAYER"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var instances = (Map<Class<?>, Map<UUID, Map<Integer, CoreAbility>>>) field.get(null);
        var saved = new HashMap<>(instances);
        var player = new InputPlayer(); var allocator = new ObjenesisStd(false);
        try {
            instances.clear();
            assertFalse(CommonInputHandler.handleToggleFlight(player).cancelEvent());
            for (var type : List.of(Tornado.class, FireJet.class, AvatarState.class)) {
                instances.put(type, Map.of(player.getUniqueId(), Map.of(1, allocator.newInstance(type))));
                for (var mode : List.of(GameMode.SURVIVAL, GameMode.CREATIVE, GameMode.ADVENTURE, GameMode.SPECTATOR)) {
                    player.mode = mode;
                    assertEquals(mode != GameMode.CREATIVE, CommonInputHandler.handleToggleFlight(player).cancelEvent(), type + " " + mode);
                }
                instances.clear();
            }
        } finally { instances.clear(); instances.putAll(saved); }
    }

    @Test void flightMultiAbilityProtectsExistingFlightButDoesNotCancelEnteringFlight() {
        var player = new InputPlayer(); var tracking = FlightMultiAbility.getFlyingPlayers();
        var saved = new HashSet<>(tracking);
        try {
            tracking.add(player.getUniqueId());
            assertFalse(CommonInputHandler.handleToggleFlight(player).cancelEvent());
            player.flying = true; assertTrue(CommonInputHandler.handleToggleFlight(player).cancelEvent());
            tracking.remove(player.getUniqueId()); assertFalse(CommonInputHandler.handleToggleFlight(player).cancelEvent());
        } finally { tracking.clear(); tracking.addAll(saved); }
    }
    private static final class InputPlayer extends Player {
        final UUID id = UUID.randomUUID(); GameMode mode = GameMode.SURVIVAL; boolean flying;
        @Override public UUID getUniqueId() { return id; }
        @Override public GameMode getGameMode() { return mode; }
        @Override public boolean isFlying() { return flying; }
    }
}
