package com.projectkorra.projectkorra.ability.activation;

import com.projectkorra.projectkorra.util.ClickType;

import java.util.Collection;

/**
 * Optional interface for abilities that want to provide their own activation
 * routing without being added to PKListener or to the core activation bootstrap.
 * <p>
 * The instance ProjectKorra creates during ability registration is used only as
 * a lightweight descriptor. Implementations should use context.getPlayer() to
 * create/start the real player-owned ability instance.
 */
public interface DynamicActivationAbility {
    Collection<ClickType> getActivationTypes();

    /**
     * @return true when the activation was handled.
     */
    boolean activate(ActivationContext context);
}
