package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.object.Style;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.prediction.authority.PredictionServices;
import com.projectkorra.projectkorra.prediction.state.PredictionConfigSync;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objenesis.ObjenesisStd;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class RollbackConfigurationTest {
    @TempDir Path directory;
    private Platform.Scope platform;
    private ProjectKorraPlatform services;
    private RollbackStateGraph.Snapshot registry;
    private final RollbackStateGraph graph = new RollbackStateGraph(value -> value instanceof Config, field -> true, 10_000);

    @BeforeEach void setup() {
        registry = graph.capture(List.of(), RollbackStateGraph.staticFields(PredictionConfigSync.class, field -> true));
        services = (ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(),
                new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "dataFolder" -> directory;
                    case "logger" -> Logger.getLogger("RollbackConfigurationTest");
                    default -> throw new AssertionError(method);
                });
        platform = Platform.using(services);
    }
    @AfterEach void cleanup() { platform.close(); registry.restore(); assertFalse(RollbackDomain.active()); }
    private Config config(String name) { return new Config(directory.resolve(name + ".yml").toFile()); }
    private RollbackDomain domain(RollbackConfiguration configuration) {
        return RollbackDomain.create(graph, List.of(), List.of(), services, null, PredictionServices.empty(), configuration, () -> { });
    }
    private RollbackConfiguration prepared(Map<String, Config> sources) {
        return RollbackConfiguration.prepare(RollbackConfiguration.captureData(sources), sources);
    }

    @Test void typedDefaultsExplicitValuesOrderAndAliasesSurviveWireTransfer() {
        Config source = config("source");
        source.addDefault("Abilities.Speed", 20.0); source.addDefault("Fallback", "23.0 # 20.85");
        var sequence = new ArrayList<Object>(Arrays.asList(null, true, (byte) 1, (short) 2, 3, 4L, -0.0F, -0.0D,
                new BigInteger("92233720368547758081"), new BigDecimal("123.45600"), new Date(12345), "\uD83D\uDD25"));
        sequence.add(new LinkedHashMap<>(Map.of("nested", List.of("a", "b"))));
        sequence.add(new LinkedHashSet<>(List.of("z", "a")));
        source.applyRemoteValues(new LinkedHashMap<>(Map.of("Abilities.Speed", 15.5, "Sequence", sequence)));
        var sources = Map.of("file_source_yml", source, "addon", source);
        var seed = RollbackConfiguration.captureData(sources); var bytes = seed.encode();
        var expectedKeys = source.getKeys(true);
        var received = RollbackConfiguration.Data.decode(bytes);
        assertArrayEquals(bytes, received.encode()); assertEquals(seed.fingerprint(), received.fingerprint());
        assertEquals(Set.of("addon", "file_source_yml"), received.names());
        Config client = config("client"); client.addDefault("ClientOnly", 99); client.set("Abilities.Speed", 500);
        var configuration = RollbackConfiguration.prepare(received, Map.of("file_source_yml", client, "addon", client));
        var session = domain(configuration);
        session.call(() -> {
            assertEquals(15.5, client.getDouble("Abilities.Speed")); assertEquals(23, client.getInt("Fallback"));
            assertTrue(client.containsExplicit("Abilities.Speed")); assertFalse(client.containsExplicit("Fallback"));
            assertTrue(client.hasLoadedValues()); assertFalse(client.contains("ClientOnly"));
            assertEquals(sequence, client.get("Sequence"));
            assertEquals(expectedKeys, client.getKeys(true));
            return null;
        });
        assertEquals(500, client.getDouble("Abilities.Speed")); assertEquals(99, client.getInt("ClientOnly"));
        assertEquals(1, configuration.bindings().size());
    }

    @Test void reloadingOrMutatingLiveConfigCannotChangeCapturedSessions() throws Exception {
        Config config = config("combat"); config.set("Abilities.FireBlast.Speed", 20);
        var first = domain(prepared(Map.of("combat", config)));
        config.set("Abilities.FireBlast.Speed", 40);
        var second = domain(prepared(Map.of("combat", config)));
        Files.writeString(directory.resolve("combat.yml"), "Abilities:\n  FireBlast:\n    Speed: 99\n"); config.reload();
        for (int i = 0; i < 3; i++) {
            first.call(() -> { assertEquals(20, config.getInt("Abilities.FireBlast.Speed")); return null; });
            assertEquals(99, config.getInt("Abilities.FireBlast.Speed"));
            second.call(() -> {
                var section = config.getConfigurationSection("Abilities.FireBlast");
                assertEquals(Set.of("Speed"), section.getKeys(false)); assertEquals(40, section.getInt("Speed")); return null;
            });
        }
    }

    @Test void styleSelectionRewindsWithoutLeakingIntoAnotherSessionOrOrdinaryGameplay() {
        Config base = config("base"), style = config("style"); base.set("Speed", 20); style.set("Speed", 30);
        BendingPlayer styled = new ObjenesisStd().newInstance(BendingPlayer.class);
        Style definition = new ObjenesisStd().newInstance(Style.class); definition.setConfig(style); styled.setStyle(definition);
        var sources = Map.of("base", base, "style", style);
        var first = domain(prepared(sources)); var second = domain(prepared(sources));
        base.get(styled); // Ordinary gameplay's context must remain outside both domains.
        var initial = first.call(() -> { assertEquals(20, base.getInt("Speed")); return first.capture(); });
        first.call(() -> { assertEquals(30, base.get(styled).getInt("Speed")); return null; });
        second.call(() -> { assertEquals(20, base.getInt("Speed")); return null; });
        first.call(() -> {
            assertEquals(30, base.getInt("Speed")); first.restore(initial); assertEquals(20, base.getInt("Speed")); return null;
        });
        assertEquals(30, base.getInt("Speed"));
    }

    @Test void decodedAbilityGraphBindsItsConfigToTheSamePrivateViewAsStaticAccess() {
        Config source = config("server"), target = config("client"); source.set("Speed", 12); target.set("Speed", 999);
        var seed = RollbackConfiguration.captureData(Map.of("combat", source));
        var sender = RollbackConfiguration.prepare(seed, Map.of("combat", source));
        var receiver = RollbackConfiguration.prepare(RollbackConfiguration.Data.decode(seed.encode()), Map.of("combat", target));
        var limits = new RollbackGraphCodec.Limits(100, 1000, 4096, 1024);
        var encoder = new RollbackGraphCodec(new RollbackGraphCodec.Catalog(List.of(AbilitySettings.class), List.of(), sender.sourceBindings()), limits);
        var decoder = new RollbackGraphCodec(new RollbackGraphCodec.Catalog(List.of(AbilitySettings.class), List.of(), receiver.bindings()), limits);
        var copied = (AbilitySettings) decoder.decode(encoder.encode(List.of(new AbilitySettings(source)))).getFirst();
        var session = domain(receiver);
        session.call(() -> { assertSame(target.get(), copied.config); assertEquals(12, copied.config.getInt("Speed")); return null; });
        assertEquals(999, target.getInt("Speed"));
        assertThrows(IllegalStateException.class, () -> copied.config.set("Speed", 100));
    }
    private static final class AbilitySettings { final Config config; AbilitySettings(Config config) { this.config = config; } }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void addonStyleReaderSemanticsAndContextsSurviveRewind(boolean jedCore) {
        Config addon = jedCore ? new com.jedk1.jedcore.configuration.Config(directory.resolve("jedcore.yml").toFile())
                : new me.moros.hyperion.configuration.Config("hyperion.yml");
        Config style = config("addon-style"); addon.set("Speed", 20); style.set("Speed", 30);
        BendingPlayer styled = new ObjenesisStd().newInstance(BendingPlayer.class);
        Style definition = new ObjenesisStd().newInstance(Style.class); definition.setConfig(style); styled.setStyle(definition);
        var configuration = prepared(Map.of("addon", addon, "style", style));
        var session = domain(configuration);
        select(addon, styled);
        session.call(() -> {
            assertEquals(20, addon.getInt("Speed"));
            Config selected = select(addon, styled);
            assertNotSame(addon, selected); assertTrue(addon.getClass().isInstance(selected));
            var checkpoint = session.capture();
            assertEquals(30, addon.getInt("Speed")); assertEquals(jedCore ? 30 : 20, addon.getInt("Speed"));
            session.restore(checkpoint);
            assertEquals(30, selected.getInt("Speed")); assertEquals(jedCore ? 30 : 20, selected.getInt("Speed"));
            return null;
        });
        assertEquals(30, addon.getInt("Speed")); assertEquals(jedCore ? 30 : 20, addon.getInt("Speed"));
    }
    private static Config select(Config config, BendingPlayer player) {
        return config instanceof com.jedk1.jedcore.configuration.Config jed ? jed.getConfig(player)
                : ((me.moros.hyperion.configuration.Config) config).getConfig(player);
    }

    @Test void addonTypedFieldsKeepTheirNativeConfigContractInThePortableGraph() {
        var source = new com.jedk1.jedcore.configuration.Config(directory.resolve("source-jedcore.yml").toFile());
        var target = new com.jedk1.jedcore.configuration.Config(directory.resolve("target-jedcore.yml").toFile());
        source.set("Speed", 4); target.set("Speed", 100);
        var data = RollbackConfiguration.captureData(Map.of("addon", source));
        var sender = RollbackConfiguration.prepare(data, Map.of("addon", source));
        var receiver = RollbackConfiguration.prepare(RollbackConfiguration.Data.decode(data.encode()), Map.of("addon", target));
        var limits = new RollbackGraphCodec.Limits(100, 1000, 4096, 1024);
        var encoder = new RollbackGraphCodec(new RollbackGraphCodec.Catalog(List.of(AddonSettings.class), List.of(), sender.sourceBindings()), limits);
        var decoder = new RollbackGraphCodec(new RollbackGraphCodec.Catalog(List.of(AddonSettings.class), List.of(), receiver.bindings()), limits);
        var decoded = (AddonSettings) decoder.decode(encoder.encode(List.of(new AddonSettings(source)))).getFirst();
        domain(receiver).call(() -> { assertSame(target.getConfig(), decoded.config); assertEquals(4, decoded.config.getInt("Speed")); return null; });
        assertThrows(IllegalArgumentException.class, () -> RollbackConfiguration.prepare(data, Map.of("addon", config("wrong-class"))));
    }
    private static final class AddonSettings {
        final com.jedk1.jedcore.configuration.Config config;
        AddonSettings(com.jedk1.jedcore.configuration.Config config) { this.config = config; }
    }

    @Test void fileAndRegistrySideEffectsAreRejectedBeforeTheyCanChangeAnything() throws Exception {
        Config config = config("settings"); config.set("Speed", 3); config.save();
        byte[] original = Files.readAllBytes(directory.resolve("settings.yml")); var names = PredictionConfigSync.sources();
        var session = domain(prepared(Map.of("settings", config)));
        session.call(() -> {
            for (Runnable change : List.<Runnable>of(() -> config.set("Speed", 4), () -> config.addDefault("New", 1),
                    () -> config.applyRemoteValues(Map.of()), () -> config.removeTree("Speed"), config::save, config::reload, config::create,
                    () -> new Config(directory.resolve("unexpected.yml").toFile()), () -> config.get().set("Speed", 4),
                    () -> PredictionConfigSync.register("unexpected", config))) {
                assertThrows(IllegalStateException.class, change::run);
            }
            return null;
        });
        assertEquals(3, config.getInt("Speed")); assertArrayEquals(original, Files.readAllBytes(directory.resolve("settings.yml")));
        assertFalse(Files.exists(directory.resolve("unexpected.yml"))); assertEquals(names, PredictionConfigSync.sources());
    }

    @Test void nestedValuesAndTimestampReadsCannotModifyTheSessionSeed() {
        Config config = config("nested"); var date = new Date(1000); var nested = new ArrayList<>(List.of(date));
        config.set("Nested", nested); var captured = prepared(Map.of("nested", config)); var session = domain(captured);
        String digest = captured.data().fingerprint(); date.setTime(9000); nested.clear();
        session.call(() -> {
            List<?> value = (List<?>) config.get("Nested"); assertEquals(1000, ((Date) value.getFirst()).getTime());
            assertThrows(UnsupportedOperationException.class, value::clear); ((Date) value.getFirst()).setTime(7000);
            assertEquals(1000, ((Date) ((List<?>) config.get("Nested")).getFirst()).getTime()); return null;
        });
        assertEquals(digest, captured.data().fingerprint()); assertEquals(List.of(), config.get("Nested"));
    }

    @Test void configurationMismatchOrAnUncapturedConfigCannotSilentlyReadLocalSettings() {
        Config a = config("a"), b = config("b"); a.set("Speed", 1); b.set("Speed", 2);
        var seed = RollbackConfiguration.captureData(Map.of("a", a, "alias", a));
        assertThrows(IllegalArgumentException.class, () -> RollbackConfiguration.prepare(seed, Map.of("a", a)));
        assertThrows(IllegalArgumentException.class, () -> RollbackConfiguration.prepare(seed, Map.of("a", a, "alias", b)));
        var session = domain(prepared(Map.of("a", a)));
        assertThrows(IllegalArgumentException.class, () -> session.call(() -> b.getInt("Speed")));
        assertTrue(session.failed()); assertFalse(RollbackDomain.active()); assertEquals(2, b.getInt("Speed"));
        a.set("Speed", 3); assertEquals(3, a.getInt("Speed"));
    }

    @Test void wireRejectsTruncationOldVersionsTrailingBytesAndInvalidUtf8() {
        Config config = config("wire"); config.set("Speed", 12);
        var bytes = RollbackConfiguration.captureData(Map.of("a", config)).encode();
        for (int length = 0; length < bytes.length; length++) {
            byte[] truncated = Arrays.copyOf(bytes, length);
            assertThrows(IllegalArgumentException.class, () -> RollbackConfiguration.Data.decode(truncated));
        }
        byte[] old = bytes.clone(); ByteBuffer.wrap(old).putInt(0);
        assertThrows(IllegalArgumentException.class, () -> RollbackConfiguration.Data.decode(old));
        assertThrows(IllegalArgumentException.class, () -> RollbackConfiguration.Data.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        byte[] unicode = bytes.clone(); unicode[12] = (byte) 0xFF;
        assertThrows(IllegalArgumentException.class, () -> RollbackConfiguration.Data.decode(unicode));
    }

    @Test void cyclesAndOpaqueObjectsCannotEscapeThroughConfigTrees() {
        Config config = config("invalid"); var cyclic = new ArrayList<>(); cyclic.add(cyclic); config.set("Cycle", cyclic);
        assertThrows(IllegalArgumentException.class, () -> RollbackConfiguration.captureData(Map.of("invalid", config)));
        config.set("Cycle", null); config.set("Opaque", new Object());
        assertThrows(IllegalArgumentException.class, () -> RollbackConfiguration.captureData(Map.of("invalid", config)));
    }

    @Test void fingerprintTracksDefaultsValueTypesAndAliasIdentity() {
        Config config = config("hash"); config.addDefault("Speed", 5);
        String original = RollbackConfiguration.captureData(Map.of("hash", config)).fingerprint();
        config.set("Speed", 5);
        String explicit = RollbackConfiguration.captureData(Map.of("hash", config)).fingerprint(); assertNotEquals(original, explicit);
        config.set("Speed", 5L);
        String longType = RollbackConfiguration.captureData(Map.of("hash", config)).fingerprint(); assertNotEquals(explicit, longType);
        assertNotEquals(longType, RollbackConfiguration.captureData(Map.of("hash", config, "alias", config)).fingerprint());
    }
    @Test void gameplayFactoryBindsCapturedConfigurationInsteadOfCopyingLiveConfigInternals() {
        var source = config("factory"); source.set("Speed", 2.0);
        var configuration = prepared(Map.of("main", source));
        var world = new com.projectkorra.projectkorra.platform.mc.World();
        var players = new ArrayList<com.projectkorra.projectkorra.platform.mc.entity.Player>();
        for (int index = 1; index <= 2; index++) {
            final UUID id = new UUID(0, index);
            players.add(new com.projectkorra.projectkorra.platform.mc.entity.Player() {
                @Override public UUID getUniqueId() { return id; }
                @Override public com.projectkorra.projectkorra.platform.mc.World getWorld() { return world; }
                @Override public boolean isOnline() { return true; }
            });
        }
        var roster = new RollbackRosterBindings(world, players);
        var lifecycle = new com.projectkorra.projectkorra.listener.CommonAbilityLifecycleListener(effect -> { });
        var installed = RollbackGameplayCatalog.installed(getClass().getClassLoader());
        var limits = new RollbackGraphCodec.Limits(1000, 10000, 1000000, 10000);
        var live = RollbackGameplayGraph.create(installed, limits, RollbackGameplayGraph.Side.LIVE,
                roster, configuration, lifecycle, List.of(), new RollbackGraphViews());
        var replica = RollbackGameplayGraph.create(installed, limits, RollbackGameplayGraph.Side.PRIVATE,
                roster, configuration, lifecycle, List.of(), new RollbackGraphViews());
        var privateConfig = (Config) replica.decode(live.encode(List.of(source))).getFirst();
        assertSame(configuration.bindings().getFirst().value(), privateConfig);
        assertNotSame(source, privateConfig);
        source.set("Speed", 9.0);
        assertEquals(2.0, privateConfig.getDouble("Speed"));
        assertSame(source, live.decode(replica.encode(List.of(privateConfig))).getFirst());
    }}
