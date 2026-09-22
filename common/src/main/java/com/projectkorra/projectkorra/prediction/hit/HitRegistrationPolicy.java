package com.projectkorra.projectkorra.prediction.hit;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.Element.MultiSubElement;
import com.projectkorra.projectkorra.Element.SubElement;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.AvatarAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

import java.util.function.Supplier;

/**
 * Selects which position authority an ability uses for player hit registration.
 *
 * <p>All elements can report client contacts through the existing bounded rewind
 * path. Server validation accepts Fire/Air reports only for modded targets;
 * their other targets retain the current server hit path.</p>
 */
public enum HitRegistrationPolicy {
    REWIND_ASSISTED,
    SERVER_CURRENT;

    private static final ThreadLocal<Integer> TARGET_ACQUISITION_DEPTH =
            ThreadLocal.withInitial(() -> 0);

    /** Client reports share one path; the server checks target eligibility separately. */
    public static HitRegistrationPolicy forAbility(final CoreAbility ability) {
        return forTarget(ability, true);
    }

    /** The mod flag must come from the server's existing client session. */
    public static HitRegistrationPolicy forTarget(final CoreAbility ability, final boolean targetHasMod) {
        return targetHasMod || ability == null
                ? REWIND_ASSISTED
                : resolve(ability.getClass(), ability.getElement());
    }

    /**
     * Resolves both inheritance and element metadata so embedded/addon
     * abilities work whether they extend an elemental base or expose a proper
     * subelement.
     */
    public static HitRegistrationPolicy resolve(final Class<?> abilityType,
                                                final Element element) {
        // Avatar is an explicit non-reactive exception and must win first.
        if ((abilityType != null && AvatarAbility.class.isAssignableFrom(abilityType))
                || element == Element.AVATAR) {
            return REWIND_ASSISTED;
        }
        if (abilityType != null && (AirAbility.class.isAssignableFrom(abilityType)
                || FireAbility.class.isAssignableFrom(abilityType))) {
            return SERVER_CURRENT;
        }
        return isFamily(element, Element.AIR) || isFamily(element, Element.FIRE)
                ? SERVER_CURRENT : REWIND_ASSISTED;
    }

    private static boolean isFamily(final Element element, final Element parent) {
        if (element == null || parent == null) return false;
        if (element == parent) return true;
        if (element instanceof MultiSubElement multi) {
            for (Element candidate : multi.getParentElements()) {
                if (isFamily(candidate, parent)) return true;
            }
            return false;
        }
        return element instanceof SubElement sub
                && isFamily(sub.getParentElement(), parent);
    }

    /**
     * Runs an entity lookup whose purpose is choosing/aiming at a target rather
     * than resolving projectile or area contact.
     */
    public static <T> T targetAcquisition(final Supplier<T> query) {
        if (query == null) return null;
        TARGET_ACQUISITION_DEPTH.set(TARGET_ACQUISITION_DEPTH.get() + 1);
        try {
            return query.get();
        } finally {
            final int remaining = TARGET_ACQUISITION_DEPTH.get() - 1;
            if (remaining <= 0) TARGET_ACQUISITION_DEPTH.remove();
            else TARGET_ACQUISITION_DEPTH.set(remaining);
        }
    }

    static boolean isTargetAcquisition() {
        return TARGET_ACQUISITION_DEPTH.get() > 0;
    }

    /**
     * Client contact candidates use the shared reporting policy. Target-specific
     * Fire/Air eligibility is checked when Paper validates the resulting claim.
     */
    public static boolean includePredictedEntity(final CoreAbility ability,
                                                 final Entity candidate) {
        if (!(candidate instanceof Player) || ability == null
                || forAbility(ability) != SERVER_CURRENT
                || isTargetAcquisition()) {
            return true;
        }
        final Player owner = ability.getPlayer();
        return owner != null && owner.getUniqueId().equals(candidate.getUniqueId());
    }
}
