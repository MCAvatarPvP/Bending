package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.hooks.CanBendHook;
import com.projectkorra.projectkorra.hooks.CanBindHook;
import java.util.*;

/** The registered hook order and private callback instances for a single duel. */
public final class RollbackBendingHooks implements CanBendHook, CanBindHook {
    static final RollbackBendingHooks EMPTY = new RollbackBendingHooks(List.of(), List.of());
    private final List<CanBendHook> bend;
    private final List<CanBindHook> bind;

    public RollbackBendingHooks(Collection<CanBendHook> bend, Collection<CanBindHook> bind) {
        this.bend = new ArrayList<>(bend);
        this.bind = new ArrayList<>(bind);
    }

    public static RollbackBendingHooks current() {
        if (!RollbackDomain.active()) return null;
        var registries = RollbackDomain.importingRegistries();
        return registries == null ? EMPTY : registries.bendingHooks;
    }

    @Override public Optional<Boolean> canBend(BendingPlayer player, CoreAbility ability, boolean ignoreBinds, boolean ignoreCooldowns) {
        for (var hook : bend) {
            var result = hook.canBend(player, ability, ignoreBinds, ignoreCooldowns);
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }

    @Override public Optional<Boolean> canBind(BendingPlayer player, CoreAbility ability) {
        for (var hook : bind) {
            var result = hook.canBind(player, ability);
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }

    /** External match spectator decisions are constant for the duration of the enrolled round. */
    public static final class Spectators implements CanBindHook, CanBendHook {
        private final Set<UUID> spectators;

        public Spectators(Collection<UUID> spectators) { this.spectators = new TreeSet<>(spectators); }

        @Override public Optional<Boolean> canBind(BendingPlayer player, CoreAbility ability) {
            return spectators.contains(player.getUUID()) ? Optional.of(false) : Optional.empty();
        }
        @Override public Optional<Boolean> canBend(BendingPlayer player, CoreAbility ability, boolean ignoreBinds, boolean ignoreCooldowns) {
            return canBind(player, ability);
        }
    }
}
