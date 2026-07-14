package com.projectkorra.projectkorra.ability.activation;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.ClickType;

import java.util.HashMap;
import java.util.Map;

public final class ActivationContext {
    private final Player player;
    private final BendingPlayer bendingPlayer;
    private final CoreAbility boundAbility;
    private final String abilityName;
    private final ClickType clickType;
    private final Map<String, Object> attributes = new HashMap<>();
    private boolean cancelEvent;
    private boolean stopProcessing;

    public ActivationContext(final Player player, final ClickType clickType) {
        this(player, BendingPlayer.getBendingPlayer(player), clickType);
    }

    public ActivationContext(final Player player, final BendingPlayer bendingPlayer, final ClickType clickType) {
        this(player, bendingPlayer, bendingPlayer != null ? bendingPlayer.getBoundAbility() : null,
                bendingPlayer != null ? bendingPlayer.getBoundAbilityName() : null, clickType);
    }

    private ActivationContext(final Player player, final BendingPlayer bendingPlayer, final CoreAbility boundAbility,
                              final String abilityName, final ClickType clickType) {
        this.player = player;
        this.bendingPlayer = bendingPlayer;
        this.boundAbility = boundAbility;
        this.abilityName = abilityName;
        this.clickType = clickType;
    }

    public ActivationContext withAbilityName(final String abilityName) {
        ActivationContext copy = new ActivationContext(this.player, this.bendingPlayer, this.boundAbility, abilityName, this.clickType);
        copy.attributes.putAll(this.attributes);
        copy.cancelEvent = this.cancelEvent;
        copy.stopProcessing = this.stopProcessing;
        return copy;
    }

    public Player getPlayer() {
        return this.player;
    }

    public BendingPlayer getBendingPlayer() {
        return this.bendingPlayer;
    }

    public CoreAbility getBoundAbility() {
        return this.boundAbility;
    }

    public String getAbilityName() {
        return this.abilityName;
    }

    public ClickType getClickType() {
        return this.clickType;
    }

    public void put(final String key, final Object value) {
        this.attributes.put(key, value);
    }

    public Object get(final String key) {
        return this.attributes.get(key);
    }

    public boolean getBoolean(final String key, final boolean fallback) {
        final Object value = this.attributes.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    public void cancelEvent() {
        this.cancelEvent = true;
    }

    public boolean shouldCancelEvent() {
        return this.cancelEvent;
    }

    public void stopProcessing() {
        this.stopProcessing = true;
    }

    public boolean shouldStopProcessing() {
        return this.stopProcessing;
    }
}
