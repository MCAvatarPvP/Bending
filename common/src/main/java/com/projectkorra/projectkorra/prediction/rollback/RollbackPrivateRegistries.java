package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.Manager;
import com.projectkorra.projectkorra.OfflineBendingPlayer;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.ability.util.ComboManager;
import java.util.HashMap;
import java.util.Map;

/** Registry membership and counters for one imported match, visible only while its owner thread runs it. */
public final class RollbackPrivateRegistries {
    private final OfflineBendingPlayer.PrivateRegistries players = new OfflineBendingPlayer.PrivateRegistries();
    private final CoreAbility.PrivateRegistries abilities = new CoreAbility.PrivateRegistries();
    private final ComboManager.PrivateRegistries combos = new ComboManager.PrivateRegistries();
    private final AbilityActivationManager.PrivateRegistries activations = new AbilityActivationManager.PrivateRegistries();
    private final ElementalAbility.PrivateRegistries materials = new ElementalAbility.PrivateRegistries();
    private final Map<Class<? extends Manager>, Manager> managers = new HashMap<>();

    public OfflineBendingPlayer.PrivateRegistries players() { return players; }
    public CoreAbility.PrivateRegistries abilities() { return abilities; }
    public ComboManager.PrivateRegistries combos() { return combos; }
    public AbilityActivationManager.PrivateRegistries activations() { return activations; }
    public ElementalAbility.PrivateRegistries materials() { return materials; }
    public Map<Class<? extends Manager>, Manager> managers() { return managers; }
}
