package com.projectkorra.projectkorra.ability.activation;

@FunctionalInterface
public interface ActivationHandler {
    /**
     * @return true when this handler consumed/handled the activation input.
     */
    boolean activate(ActivationContext context);
}
