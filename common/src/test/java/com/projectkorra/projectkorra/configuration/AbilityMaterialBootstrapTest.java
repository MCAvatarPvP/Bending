package com.projectkorra.projectkorra.configuration;

import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.earthbending.EarthTunnel;
import com.projectkorra.projectkorra.platform.PKMaterials;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.mc.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class AbilityMaterialBootstrapTest {
    @Test void earlyClassLoadingAllowsSetupAndConfigurationReload(@TempDir Path directory) throws Exception {
        // Other tests may already have initialized these classes and installed the process-wide platform.
        var dependencies = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("com.projectkorra.")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        var classes = new URL[]{Platform.class.getProtectionDomain().getCodeSource().getLocation(),
                getClass().getProtectionDomain().getCodeSource().getLocation()};
        try (var loader = new URLClassLoader(classes, dependencies)) {
            loader.loadClass(getClass().getName()).getMethod("verifyColdStart", Path.class).invoke(null, directory);
        }
    }

    public static void verifyColdStart(Path directory) throws Exception {
        assertFalse(Platform.isInstalled());
        assertNull(ConfigManager.getConfig());
        var loader = AbilityMaterialBootstrapTest.class.getClassLoader();
        Class.forName(ElementalAbility.class.getName(), true, loader);
        Class.forName(EarthTunnel.class.getName(), true, loader);

        Platform.install((ProjectKorraPlatform) Proxy.newProxyInstance(loader, new Class<?>[]{ProjectKorraPlatform.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("materials")) return (PKMaterials) material -> material == Material.STONE;
                    throw new AssertionError(method);
                }));
        var path = directory.resolve("config.yml");
        ConfigManager.defaultConfig = new Config(path.toFile(), false);
        var ignored = EarthTunnel.class.getDeclaredField("IGNORED_BLOCKS");
        ignored.setAccessible(true);
        for (var material : List.of(Material.STONE, Material.DIRT)) {
            Files.writeString(path, """
                    Properties:
                      Earth:
                        EarthBlocks: [%s]
                    Abilities:
                      Earth:
                        EarthTunnel:
                          IgnoredBlocks: [%s]
                    """.formatted(material, material));
            ConfigManager.defaultConfig.reload();
            ElementalAbility.clearBendableMaterials();
            ElementalAbility.setupBendableMaterials();
            EarthTunnel.clearBendableMaterials();
            EarthTunnel.setupBendableMaterials();
            assertEquals(List.of(material.name()), ElementalAbility.getEarthbendableBlocks());
            assertEquals(Set.of(material.name()), ignored.get(null));
            var transparent = Arrays.asList(ElementalAbility.getTransparentMaterials());
            assertTrue(transparent.contains(Material.AIR));
            assertFalse(transparent.contains(Material.STONE));
        }
    }
}
