package com.projectkorra.projectkorra.prediction.action;

import com.projectkorra.projectkorra.ability.CoreAbility;

/**
 * Makes the currently progressing ability visible to loader collision adapters.
 */
public final class AbilityExecutionContext {
    private static final ThreadLocal<CoreAbility> CURRENT = new ThreadLocal<>();

    private AbilityExecutionContext() {
    }

    public static CoreAbility current() {
        return CURRENT.get();
    }

    public static void run(final CoreAbility ability, final Runnable progress) {
        final CoreAbility previous = CURRENT.get();
        CURRENT.set(ability);
        try {
            progress.run();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
