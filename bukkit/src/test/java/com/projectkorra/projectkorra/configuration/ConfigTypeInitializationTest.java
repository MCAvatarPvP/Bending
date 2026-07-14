package com.projectkorra.projectkorra.configuration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTypeInitializationTest {
    @Test
    void coreTypesRegisterDuringStaticInitialization() {
        List<ConfigType> coreTypes = ConfigType.coreValues();

        assertEquals(10, coreTypes.size());
        assertTrue(coreTypes.contains(ConfigType.DEFAULT));
        assertTrue(ConfigType.values().containsAll(coreTypes));
    }
}
