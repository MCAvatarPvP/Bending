package com.projectkorra.projectkorra.airbending.passive;

import com.projectkorra.projectkorra.platform.mc.entity.Player;

/** Owns the vanilla air meter only while it is displaying airbending stamina. */
final class AirStaminaBar {
    private static final int VANILLA_LAND_REGEN_PER_TICK = 4;
    private boolean displayingStamina;
    private int vanillaAir;

    void display(final Player player, final float normalizedStamina) {
        if (player == null) return;
        final int maximumAir = Math.max(0, player.getMaximumAir());
        if (!this.displayingStamina) {
            this.vanillaAir = Math.max(0, Math.min(maximumAir, player.getRemainingAir()));
        } else {
            this.vanillaAir = Math.min(maximumAir,
                    this.vanillaAir + VANILLA_LAND_REGEN_PER_TICK);
        }

        final int air = airTicks(normalizedStamina, maximumAir);
        if (player.getRemainingAir() != air) {
            player.setRemainingAir(air);
        }
        this.displayingStamina = true;
    }

    /**
     * Gives the meter back to vanilla. Repeated calls deliberately do nothing,
     * so real underwater air lost after the handoff is never refilled.
     */
    void release(final Player player) {
        if (!this.displayingStamina || player == null) return;
        final int actualAir = Math.max(0, Math.min(player.getMaximumAir(), this.vanillaAir));
        if (player.getRemainingAir() != actualAir) {
            player.setRemainingAir(actualAir);
        }
        this.displayingStamina = false;
    }

    static int airTicks(final float normalizedStamina, final int maximumAir) {
        final float clamped = Float.isFinite(normalizedStamina)
                ? Math.max(0.0F, Math.min(1.0F, normalizedStamina)) : 0.0F;
        return Math.round(clamped * Math.max(0, maximumAir));
    }
}
