package com.projectkorra.projectkorra.airbending.passive;

import com.projectkorra.projectkorra.platform.mc.entity.Player;

final class AirStaminaBar {

    private static final int VANILLA_LAND_REGEN_PER_TICK = 4;
    private boolean displayingStamina;
    private int vanillaAir;

    void display(final Player player, final float normalizedStamina) {
        if (player == null) {
            return;
        }

        final float clamped = Float.isFinite(normalizedStamina)
                ? Math.max(0.0F, Math.min(1.0F, normalizedStamina))
                : 0.0F;

        if (clamped >= 1.0F) {
            release(player);
            return;
        }

        final int maximumAir = Math.max(0, player.getMaximumAir());

        if (!this.displayingStamina) {
            this.vanillaAir = Math.max(
                    0,
                    Math.min(maximumAir, player.getRemainingAir())
            );
        } else {
            this.vanillaAir = Math.min(
                    maximumAir,
                    this.vanillaAir + VANILLA_LAND_REGEN_PER_TICK
            );
        }

        final int air = airTicks(clamped, maximumAir);

        if (player.getRemainingAir() != air) {
            player.setRemainingAir(air);
        }

        this.displayingStamina = true;
    }

    void release(final Player player) {
        if (!this.displayingStamina || player == null) {
            return;
        }

        final int actualAir = Math.max(
                0,
                Math.min(player.getMaximumAir(), this.vanillaAir)
        );

        if (player.getRemainingAir() != actualAir) {
            player.setRemainingAir(actualAir);
        }

        this.displayingStamina = false;
    }

    // Reference:
    // https://www.spigotmc.org/threads/solved-how-to-change-hud-air-bar-level-in-a-client-using-packets.464182/
    // f(x) = 30 * (x - 1) + 3
    static int airTicks(final float normalizedStamina, final int maximumAir) {
        if (maximumAir <= 0) {
            return 0;
        }

        final float clamped = Float.isFinite(normalizedStamina)
                ? Math.max(0.0F, Math.min(1.0F, normalizedStamina))
                : 0.0F;

        if (clamped <= 0.0F) {
            return 0;
        }

        final int bubbles = Math.max(
                1,
                Math.min(10, (int) Math.ceil(clamped * 10.0F))
        );

        final double ticksPerBubble = maximumAir / 10.0D;

        return Math.max(
                1,
                Math.min(
                        maximumAir - 1,
                        (int) Math.ceil(
                                3.0D * maximumAir / 300.0D
                                        + (bubbles - 1) * ticksPerBubble
                        )
                )
        );
    }
}