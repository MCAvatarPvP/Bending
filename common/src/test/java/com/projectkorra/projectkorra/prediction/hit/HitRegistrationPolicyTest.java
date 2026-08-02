package com.projectkorra.projectkorra.prediction.hit;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.AvatarAbility;
import com.projectkorra.projectkorra.ability.ChiAbility;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.ability.WaterAbility;
import me.literka.ModernChiAbility;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HitRegistrationPolicyTest {
    @Test
    void fireAirAndTheirSubelementsUseCurrentServerPositions() {
        assertEquals(HitRegistrationPolicy.SERVER_CURRENT,
                HitRegistrationPolicy.resolve(AirAbility.class, Element.AIR));
        assertEquals(HitRegistrationPolicy.SERVER_CURRENT,
                HitRegistrationPolicy.resolve(FireAbility.class, Element.FIRE));
        assertEquals(HitRegistrationPolicy.SERVER_CURRENT,
                HitRegistrationPolicy.resolve(null, Element.FLIGHT));
        assertEquals(HitRegistrationPolicy.SERVER_CURRENT,
                HitRegistrationPolicy.resolve(null, Element.LIGHTNING));

        Element.MultiSubElement mixed = new Element.MultiSubElement(
                "ReactivePolicyTest", Element.EARTH, Element.AIR);
        assertEquals(HitRegistrationPolicy.SERVER_CURRENT,
                HitRegistrationPolicy.resolve(null, mixed));
        Element.MultiSubElement nested = new Element.MultiSubElement(
                "NestedReactivePolicyTest", Element.EARTH, Element.LIGHTNING);
        assertEquals(HitRegistrationPolicy.SERVER_CURRENT,
                HitRegistrationPolicy.resolve(null, nested));
    }

    @Test
    void deliberateAndMartialFamiliesRetainRewind() {
        assertEquals(HitRegistrationPolicy.REWIND_ASSISTED,
                HitRegistrationPolicy.resolve(WaterAbility.class, Element.WATER));
        assertEquals(HitRegistrationPolicy.REWIND_ASSISTED,
                HitRegistrationPolicy.resolve(ChiAbility.class, Element.CHI));
        assertEquals(HitRegistrationPolicy.REWIND_ASSISTED,
                HitRegistrationPolicy.resolve(ModernChiAbility.class,
                        new Element("MartialArtsPolicyTest", Element.ElementType.NO_SUFFIX)));
        assertEquals(HitRegistrationPolicy.REWIND_ASSISTED,
                HitRegistrationPolicy.resolve(null, Element.EARTH));
        assertEquals(HitRegistrationPolicy.REWIND_ASSISTED,
                HitRegistrationPolicy.resolve(AvatarAbility.class, Element.AVATAR));
    }

    @Test
    void targetAcquisitionScopeIsNestedAndAlwaysRestored() {
        assertFalse(HitRegistrationPolicy.isTargetAcquisition());
        HitRegistrationPolicy.targetAcquisition(() -> {
            assertTrue(HitRegistrationPolicy.isTargetAcquisition());
            HitRegistrationPolicy.targetAcquisition(() -> {
                assertTrue(HitRegistrationPolicy.isTargetAcquisition());
                return null;
            });
            assertTrue(HitRegistrationPolicy.isTargetAcquisition());
            return null;
        });
        assertFalse(HitRegistrationPolicy.isTargetAcquisition());

        assertThrows(IllegalStateException.class,
                () -> HitRegistrationPolicy.targetAcquisition(() -> {
                    throw new IllegalStateException("expected");
                }));
        assertFalse(HitRegistrationPolicy.isTargetAcquisition());
    }
}
