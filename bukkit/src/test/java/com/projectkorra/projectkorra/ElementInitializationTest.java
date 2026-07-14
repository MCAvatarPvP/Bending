package com.projectkorra.projectkorra;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElementInitializationTest {
    @Test
    void coreElementsRegisterDuringStaticInitialization() {
        assertSame(Element.AIR, Element.getElement("air"));
        assertSame(Element.BLUE_FIRE, Element.getElement("bluefire"));
        assertTrue(Arrays.asList(Element.getElements()).contains(Element.FLIGHT));
        assertTrue(Arrays.asList(Element.getAllElements()).contains(Element.FIRE));
    }
}
