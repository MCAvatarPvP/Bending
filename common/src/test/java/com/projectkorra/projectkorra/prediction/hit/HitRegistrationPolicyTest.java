package com.projectkorra.projectkorra.prediction.hit;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.AvatarAbility;
import com.projectkorra.projectkorra.ability.ChiAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.ability.WaterAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import java.util.List;
import me.literka.ModernChiAbility;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HitRegistrationPolicyTest {
    private java.util.Map<Class<?>, Object> attributes;
    private Object previous;
    @org.junit.jupiter.api.BeforeEach
    @SuppressWarnings("unchecked")
    void preserveAbilityDefinition() throws Exception {
        var field = CoreAbility.class.getDeclaredField("ATTRIBUTE_FIELDS"); field.setAccessible(true);
        attributes = (java.util.Map<Class<?>, Object>) field.get(null);
        previous = attributes.get(TestAbility.class);
    }
    @org.junit.jupiter.api.AfterEach
    void restoreAbilityDefinition() {
        if (previous == null) attributes.remove(TestAbility.class);
        else attributes.put(TestAbility.class, previous);
    }
    @Test
    void airAndFireUseServerHitsForBothModdedAndUnmoddedTargets() {
        for (Element element : List.of(Element.AIR, Element.FIRE, Element.LIGHTNING, Element.FLIGHT)) {
            CoreAbility ability = new TestAbility(element);
            assertEquals(HitRegistrationPolicy.SERVER_CURRENT, HitRegistrationPolicy.forAbility(ability));
            assertFalse(HitRegistrationPolicy.includePredictedEntity(ability, new Player()),
                    "remote players must not enter predicted collision queries");
            HitRegistrationPolicy.targetAcquisition(() -> {
                assertTrue(HitRegistrationPolicy.includePredictedEntity(ability, new Player()),
                        "aiming must still see remote players");
                return null;
            });
            assertEquals(HitRegistrationPolicy.SERVER_CURRENT, HitRegistrationPolicy.forTarget(ability, true));
            assertEquals(HitRegistrationPolicy.SERVER_CURRENT, HitRegistrationPolicy.forTarget(ability, false));
        }
    }

    @Test
    void otherElementsKeepExistingValidationForEitherTargetType() {
        for (Element element : List.of(Element.WATER, Element.EARTH, Element.CHI, Element.AVATAR)) {
            CoreAbility ability = new TestAbility(element);
            assertEquals(HitRegistrationPolicy.REWIND_ASSISTED, HitRegistrationPolicy.forTarget(ability, true));
            assertEquals(HitRegistrationPolicy.REWIND_ASSISTED, HitRegistrationPolicy.forTarget(ability, false));
        }
    }

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

    private static final class TestAbility extends CoreAbility {
        private final Element element;
        private TestAbility(Element element) { this.element = element; }
        @Override public Element getElement() { return element; }
        @Override public void progress() { }
        @Override public boolean isSneakAbility() { return false; }
        @Override public boolean isHarmlessAbility() { return false; }
        @Override public boolean isIgniteAbility() { return false; }
        @Override public boolean isExplosiveAbility() { return false; }
        @Override public long getCooldown() { return 0; }
        @Override public String getName() { return "HitRegistrationTest"; }
        @Override public Location getLocation() { return null; }
    }
}
