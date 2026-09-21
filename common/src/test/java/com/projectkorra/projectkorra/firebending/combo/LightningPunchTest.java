package com.projectkorra.projectkorra.firebending.combo;

import com.jedk1.jedcore.ability.firebending.FirePunch;
import com.jedk1.jedcore.ability.firebending.LightningBurst;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.Manager;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.util.ComboManager;
import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.event.AbilityDamageEntityEvent;
import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.platform.*;
import com.projectkorra.projectkorra.platform.mc.ChatColor;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.state.CooldownSync;
import com.projectkorra.projectkorra.prediction.state.PredictionConfigSync;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.support.AbilityWorld;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.FlightHandler;
import com.projectkorra.projectkorra.util.MovementHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LightningPunchTest {
    @TempDir Path directory;
    private final AbilityWorld world = new AbilityWorld();
    private final AbilityWorld.TestPlayer player = world.player(0.5, 64, 0.5);
    private final AbilityWorld.TestPlayer victim = world.player(0.5, 64, 2.5);
    private final List<AbilityDamageEntityEvent> hits = new ArrayList<>();
    private final List<Runnable> cleanupTasks = new ArrayList<>();
    private final List<Timer> timers = new ArrayList<>();
    private final List<ParticleSpawn> particles = new ArrayList<>();
    private final List<Runnable> restores = new ArrayList<>();
    private BendingPlayer bending;
    private String bound = "FirePunch";
    private com.jedk1.jedcore.configuration.Config previousJedCore;

    @BeforeEach void setup() throws Exception {
        backup(CoreAbility.class, "ATTRIBUTE_FIELDS");
        backup(CoreAbility.class, "ABILITIES_BY_CLASS");
        backup(CoreAbility.class, "ABILITIES_BY_NAME");
        backup(Manager.class, "MANAGERS");
        backup(PredictionConfigSync.class, "SOURCES");
        backup(ComboManager.class, "COMBO_ABILITIES");
        backup(ComboManager.class, "RECENTLY_USED");
        backup(ComboManager.class, "SCHEDULED_COMBO_ABILITY");
        backup(LightningPunch.class, "PENDING_HITS");
        map(Manager.class, "MANAGERS").put(FlightHandler.class, null);
        final Field color = field(Element.class, "color");
        final Object previousColor = color.get(Element.LIGHTNING);
        color.set(Element.LIGHTNING, ChatColor.AQUA);
        restores.add(() -> { try { color.set(Element.LIGHTNING, previousColor); } catch (Exception e) { throw new AssertionError(e); } });
        ProjectKorraPlatform delegate = Platform.current();
        PKEventBus events = (PKEventBus) Proxy.newProxyInstance(PKEventBus.class.getClassLoader(), new Class<?>[]{PKEventBus.class},
                (p, m, args) -> {
                    if (m.getName().equals("call") && args[0] instanceof AbilityDamageEntityEvent hit) {
                        hits.add(hit); hit.setCancelled(true);
                    }
                    return null;
                });
        Player viewer = new Player() {
            private final UUID id = UUID.randomUUID();
            @Override public UUID getUniqueId() { return id; }
            @Override public World getWorld() { return world; }
            @Override public <T> void spawnParticle(Particle type, Location point, int count,
                    double x, double y, double z, double speed, T data, boolean force) {
                particles.add(new ParticleSpawn(type, point.clone(), count, new Vector(x, y, z), speed,
                        AbilityExecutionContext.current()));
            }
        };
        PKPlayers players = (PKPlayers) Proxy.newProxyInstance(PKPlayers.class.getClassLoader(), new Class<?>[]{PKPlayers.class},
                (p, m, args) -> m.getReturnType() == boolean.class ? false : List.of(viewer));
        PKScheduler scheduler = (PKScheduler) Proxy.newProxyInstance(PKScheduler.class.getClassLoader(), new Class<?>[]{PKScheduler.class},
                (p, m, args) -> {
                    if (m.getName().equals("runLater")) cleanupTasks.add((Runnable) args[0]);
                    if (m.getName().equals("runTimer")) {
                        assertEquals(1L, args[1]);
                        assertEquals(1L, args[2]);
                        Timer timer = new Timer((Runnable) args[0]);
                        timers.add(timer);
                        return timer;
                    }
                    return null;
                });
        Platform.install((ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(), new Class<?>[]{ProjectKorraPlatform.class},
                (p, m, args) -> switch (m.getName()) {
                    case "events" -> events;
                    case "players" -> players;
                    case "scheduler" -> scheduler;
                    case "dataFolder" -> directory;
                    default -> m.invoke(delegate, args);
                }));
        previousJedCore = JedCoreConfig.config;
        JedCoreConfig.config = new com.jedk1.jedcore.configuration.Config(directory.resolve("jedcore.yml").toFile());
        JedCoreConfig.config.set("Abilities.Fire.FirePunch.Enabled", true);
        JedCoreConfig.config.set("Abilities.Fire.FirePunch.Cooldown", 4000);
        JedCoreConfig.config.set("Abilities.Fire.FirePunch.Damage", 2.0);
        JedCoreConfig.config.set("Abilities.Fire.FirePunch.ActivationOnPunch", true);
        ConfigManager.getConfig().set("Abilities.Fire.LightningPunch.Enabled", true);
        ConfigManager.getConfig().set("Abilities.Fire.Lightning.StunDuration", 1500);
        ConfigManager.getConfig().set("Properties.Fire.DayFactor", 1);
        ConfigManager.getConfig().set("Properties.DamageMultiplier", 1);
        bending = new BendingPlayer(player) {
            @Override public boolean canBendIgnoreBinds(CoreAbility ability) { return !isOnInputCooldown(ability.getName()); }
            @Override public boolean canBend(CoreAbility ability) { return !isOnInputCooldown(ability.getName()); }
            @Override public boolean canUseSubElement(Element.SubElement element) { return false; }
            @Override public boolean canCurrentlyBendWithWeapons() { return true; }
            @Override public String getBoundAbilityName() { return bound; }
        };
        BendingPlayer.getPlayers().put(player.getUniqueId(), bending);
        player.eyeHeight = victim.eyeHeight = 1.62;
        for (Class<? extends CoreAbility> type : List.of(LightningPunch.class, FirePunch.class, LightningBurst.class)) {
            map(CoreAbility.class, "ATTRIBUTE_FIELDS").put(type, new HashMap<>());
            final Field singleton = field(sun.misc.Unsafe.class, "theUnsafe");
            final CoreAbility prototype = (CoreAbility) ((sun.misc.Unsafe) singleton.get(null)).allocateInstance(type);
            map(CoreAbility.class, "ABILITIES_BY_CLASS").put(type, prototype);
            map(CoreAbility.class, "ABILITIES_BY_NAME").put(prototype.getName().toLowerCase(Locale.ROOT), prototype);
        }
        final LightningPunch prototype = (LightningPunch) CoreAbility.getAbility(LightningPunch.class);
        ComboManager.getComboAbilities().put("LightningPunch", new ComboManager.ComboAbilityInfo(
                "LightningPunch", prototype.getCombination(), prototype));
        RegionProtection.clearCache();
    }

    @AfterEach void cleanup() throws Exception {
        for (CoreAbility ability : List.copyOf(CoreAbility.getAbilitiesByInstances())) {
            if (player.equals(ability.getPlayer())) ability.remove();
        }
        for (MovementHandler handler : List.copyOf(MovementHandler.handlers)) {
            if (victim.equals(handler.getEntity())) handler.reset();
        }
        cleanupTasks.forEach(Runnable::run);
        BendingPlayer.getPlayers().remove(player.getUniqueId());
        JedCoreConfig.config = previousJedCore;
        RegionProtection.unloadPlugin("LightningPunchTest");
        RegionProtection.clearCache();
        Collections.reverse(restores);
        restores.forEach(Runnable::run);
        world.close();
    }

    @Test void readiedFistDoesNotAutoHitAndMeleeDealsThreeDamageOnce() {
        LightningPunch punch = new LightningPunch(player);
        tick(punch);
        assertTrue(punch.isStarted());
        assertTrue(hits.isEmpty(), "aiming at a nearby enemy is not a melee attack");
        assertTrue(CommonInputHandler.handleEntityLeftClick(player, victim));
        assertTrue(CommonInputHandler.handleEntityLeftClick(player, victim));
        assertTrue(CommonInputHandler.handleSwing(player, Set.of(), new HashSet<>()).cancelEvent());
        tick(punch);
        tick(punch);
        assertEquals(1, hits.size());
        assertEquals(3.0, hits.getFirst().getDamage());
        assertEquals("LightningPunch", hits.getFirst().getAbility().getName());
        assertTrue(punch.isRemoved());
        assertTrue(MovementHandler.isStopped(victim));
        assertTrue(CoreAbility.getAbilities(player, FirePunch.class).isEmpty());
        assertTrue(bending.isOnCooldown("FirePunch"));
        assertTrue(bending.isOnCooldown("LightningPunch"));
        long remaining = bending.getCooldown("FirePunch") - System.currentTimeMillis();
        assertTrue(remaining > 3500 && remaining <= 4000, "uses FirePunch's configured cooldown");
        assertFalse(new LightningPunch(player).isStarted());
    }

    @Test void missedSwingConsumesTheFistWithItsOwnConfiguredCooldown() {
        ConfigManager.getConfig().set("Abilities.Fire.LightningPunch.MissCooldown", 1200);
        ConfigManager.getConfig().set("Abilities.Fire.LightningPunch.Cooldown", 7000);
        LightningPunch punch = new LightningPunch(player);
        assertTrue(CommonInputHandler.handleSwing(player, Set.of(), new HashSet<>()).cancelEvent());
        tick(punch);
        assertFalse(punch.isRemoved(), "allow the entity attack event to arrive after its swing");
        tick(punch);
        assertTrue(punch.isRemoved());
        assertTrue(hits.isEmpty());
        assertTrue(timers.isEmpty(), "a miss must not play an impact ring");
        assertCooldown("LightningPunch", 1200);
        assertFalse(bending.isOnCooldown("FirePunch"));
        assertFalse(new LightningPunch(player).isStarted());
    }

    @Test void entityAttackAfterTheSwingUsesHitCooldownInsteadOfMissCooldown() {
        ConfigManager.getConfig().set("Abilities.Fire.LightningPunch.Cooldown", 7000);
        ConfigManager.getConfig().set("Abilities.Fire.LightningPunch.MissCooldown", 1000);
        LightningPunch punch = new LightningPunch(player);
        assertTrue(LightningPunch.swing(player));
        tick(punch);
        assertTrue(CommonInputHandler.handleEntityLeftClick(player, victim));
        tick(punch);
        assertEquals(1, hits.size());
        assertCooldown("LightningPunch", 7000);
        assertCooldown("FirePunch", 4000);
    }

    @Test void repeatedSwingsCannotPostponeTheMissCooldown() {
        LightningPunch punch = new LightningPunch(player);
        LightningPunch.swing(player);
        tick(punch);
        LightningPunch.swing(player);
        tick(punch);
        assertTrue(punch.isRemoved());
        assertCooldown("LightningPunch", 1000);
    }

    @Test void aTargetThatLeavesMeleeRangeCountsAsAMiss() {
        LightningPunch punch = new LightningPunch(player);
        LightningPunch.punch(player, victim);
        victim.location.add(0, 0, 10);
        tick(punch);
        tick(punch);
        assertTrue(punch.isRemoved());
        assertTrue(hits.isEmpty());
        assertCooldown("LightningPunch", 1000);
        assertFalse(bending.isOnCooldown("FirePunch"));
    }

    @Test void switchingSlotsAfterAMissedSwingStillAppliesTheMissCooldown() {
        LightningPunch punch = new LightningPunch(player);
        LightningPunch.swing(player);
        bound = "LightningBurst";
        tick(punch);
        assertTrue(punch.isRemoved());
        assertCooldown("LightningPunch", 1000);
        assertFalse(bending.isOnCooldown("FirePunch"));
    }

    @Test void impactManuallyDrawsCompleteExpandingCirclesAfterThePunchIsRemoved() {
        LightningPunch punch = new LightningPunch(player);
        Location center = FirePunch.getImpactLocation(player, victim);
        LightningPunch.punch(player, victim);
        tick(punch);
        assertTrue(punch.isRemoved());
        assertEquals(1, timers.size());
        Timer ring = timers.getFirst();
        double previousRadius = 0;
        Vector normal = null;
        for (int frame = 0; frame < 8; frame++) {
            List<ParticleSpawn> sparks = particles.stream().filter(p -> p.type == Particle.ELECTRIC_SPARK).toList();
            assertEquals(30, sparks.size(), "every animation frame draws the whole circle");
            double radius = sparks.getFirst().point.distance(center);
            assertTrue(radius > previousRadius, "the ring expands using positions, without particle velocity");
            Vector sum = new Vector();
            if (normal == null) normal = sparks.get(0).point.toVector().subtract(center.toVector())
                    .crossProduct(sparks.get(1).point.toVector().subtract(center.toVector())).normalize();
            for (int i = 0; i < sparks.size(); i++) {
                ParticleSpawn spark = sparks.get(i);
                Vector offset = spark.point.toVector().subtract(center.toVector());
                assertEquals(radius, offset.length(), 1e-9);
                assertEquals(0, normal.dot(offset), 1e-9);
                assertEquals(1, spark.count);
                assertEquals(0, spark.spread.lengthSquared());
                assertEquals(0, spark.speed);
                assertSame(punch, spark.source, "scheduled frames retain the consumed ability's prediction context");
                assertTrue(spark.point.distance(sparks.get((i + 1) % sparks.size()).point) < radius * 0.22,
                        "the circle closes without a gap");
                sum.add(offset);
            }
            assertEquals(0, sum.length(), 1e-9);
            assertNull(AbilityExecutionContext.current());
            previousRadius = radius;
            particles.clear();
            victim.location.add(0, 0, 1); // The ring stays at the original impact.
            ring.tick();
        }
        assertTrue(previousRadius > 1);
        assertTrue(ring.cancelled());
        assertTrue(particles.isEmpty());
        assertEquals(1, hits.size(), "animation frames cannot apply another hit");
    }

    @Test void impactAnimationStopsWhenTheCasterChangesWorlds() {
        LightningPunch punch = new LightningPunch(player);
        LightningPunch.punch(player, victim);
        tick(punch);
        particles.clear();
        player.location = new Location(new World(), 0, 64, 0);
        timers.getFirst().tick();
        assertTrue(timers.getFirst().cancelled());
        assertTrue(particles.isEmpty());
    }

    @Test void stunAlwaysUsesLightningDurationEvenWhenItsChanceIsZero() throws Exception {
        ConfigManager.getConfig().set("Abilities.Fire.Lightning.StunChance", 0);
        ConfigManager.getConfig().set("Abilities.Fire.Lightning.StunDuration", 2345);
        LightningPunch punch = new LightningPunch(player);
        LightningPunch.punch(player, victim);
        tick(punch);
        MovementHandler stun = MovementHandler.handlers.stream().filter(h -> victim.equals(h.getEntity())).findFirst().orElseThrow();
        assertEquals(2345L, field(MovementHandler.class, "duration").getLong(stun));
    }

    @ParameterizedTest @ValueSource(ints = {1, 5, 9})
    void lightningInAnySlotBlocksTheCombo(int slot) {
        bending.getAbilities().put(slot, "lIgHtNiNg");
        assertFalse(new LightningPunch(player).isStarted());
        assertFalse(bending.isOnCooldown("FirePunch"));
    }

    @Test void lightningBurstAndFirePunchBindsAreAllowedButCooldownIsRespected() {
        bending.getAbilities().put(1, "LightningBurst");
        bending.getAbilities().put(2, "FirePunch");
        bending.addCooldown("FirePunch", 4000);
        assertFalse(new LightningPunch(player).isStarted());
        bending.removeCooldown("FirePunch");
        assertTrue(new LightningPunch(player).isStarted());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void switchingToFirePunchReadiesComboAndFirstMeleeClickHits(boolean nativeSlotAlreadyUpdated) {
        bending.getAbilities().put(1, "LightningBurst");
        bending.getAbilities().put(2, "FirePunch");
        bound = "LightningBurst";
        ComboManager.addComboAbility(player, ClickType.LEFT_CLICK);
        if (nativeSlotAlreadyUpdated) bound = "FirePunch";
        assertTrue(CommonInputHandler.handleSlotChange(player, 1).accepted());
        bound = "FirePunch";
        LightningPunch punch = CoreAbility.getAbility(player, LightningPunch.class);
        assertNotNull(punch, "selecting the FirePunch slot must ready the combo without another click");
        assertTrue(CoreAbility.getAbilities(player, FirePunch.class).isEmpty());
        tick(punch);
        tick(punch);
        assertTrue(hits.isEmpty());
        assertFalse(bending.isOnCooldown("LightningPunch"));
        assertFalse(bending.isOnCooldown("FirePunch"));
        assertEquals(ClickType.SLOT_CHANGE, ComboManager.getRecentlyUsedAbilities(player, 1).getFirst().getClickType());
        assertTrue(CommonInputHandler.handleSwing(player, Set.of(), new HashSet<>()).cancelEvent());
        assertTrue(CommonInputHandler.handleEntityLeftClick(player, victim));
        tick(punch);
        assertEquals(1, hits.size());
        assertEquals(3, hits.getFirst().getDamage());
        assertTrue(punch.isRemoved());
        assertCooldown("LightningPunch", 4000);
        assertCooldown("FirePunch", 4000);
    }

    @Test void firstEmptySwingAfterSelectingFirePunchUsesMissCooldown() {
        bending.getAbilities().put(1, "LightningBurst");
        bending.getAbilities().put(2, "FirePunch");
        bound = "LightningBurst";
        ComboManager.addComboAbility(player, ClickType.LEFT_CLICK_ENTITY);
        CommonInputHandler.handleSlotChange(player, 1);
        bound = "FirePunch";
        LightningPunch punch = CoreAbility.getAbility(player, LightningPunch.class);
        assertNotNull(punch);
        CommonInputHandler.handleSwing(player, Set.of(), new HashSet<>());
        tick(punch);
        tick(punch);
        assertTrue(punch.isRemoved());
        assertTrue(hits.isEmpty());
        assertCooldown("LightningPunch", 1000);
        assertFalse(bending.isOnCooldown("FirePunch"));
    }

    @Test void unrelatedSlotChangesDoNotAddComboInputsOrReadyLightningPunch() {
        bending.getAbilities().put(1, "LightningBurst");
        bending.getAbilities().put(2, "FirePunch");
        CommonInputHandler.handleSlotChange(player, 1);
        assertNull(CoreAbility.getAbility(player, LightningPunch.class));
        assertTrue(ComboManager.getRecentlyUsedAbilities(player, 8).isEmpty());
        bound = "LightningBurst";
        ComboManager.addComboAbility(player, ClickType.LEFT_CLICK);
        List<ComboManager.AbilityInformation> history = List.copyOf(ComboManager.getRecentlyUsedAbilities(player, 8));
        CommonInputHandler.handleSlotChange(player, 0);
        assertNull(CoreAbility.getAbility(player, LightningPunch.class));
        assertEquals(history, ComboManager.getRecentlyUsedAbilities(player, 8));
    }

    @Test void customClickComboStillSupportsAnAttackBeforeTheSwing() {
        useClickCombination();
        bound = "LightningBurst";
        ComboManager.addComboAbility(player, ClickType.LEFT_CLICK);
        bound = "FirePunch";
        assertTrue(CommonInputHandler.handleEntityLeftClick(player, victim));
        assertTrue(hits.isEmpty());
        ComboManager.addComboAbility(player, ClickType.LEFT_CLICK_ENTITY);
        LightningPunch punch = CoreAbility.getAbility(player, LightningPunch.class);
        assertNotNull(punch);
        tick(punch);
        assertEquals(1, hits.size());
        assertTrue(bending.isOnCooldown("FirePunch"));
    }

    @Test void wrongOrderDoesNotCreateCombo() {
        ComboManager.addComboAbility(player, ClickType.LEFT_CLICK);
        bound = "LightningBurst";
        ComboManager.addComboAbility(player, ClickType.LEFT_CLICK);
        assertNull(CoreAbility.getAbility(player, LightningPunch.class));
    }

    @Test void staleAttackBeforeASwingDoesNotAutoHitLater() {
        useClickCombination();
        bound = "LightningBurst";
        ComboManager.addComboAbility(player, ClickType.LEFT_CLICK);
        bound = "FirePunch";
        assertTrue(CommonInputHandler.handleEntityLeftClick(player, victim));
        cleanupTasks.forEach(Runnable::run);
        ComboManager.addComboAbility(player, ClickType.LEFT_CLICK);
        tick(CoreAbility.getAbility(player, LightningPunch.class));
        assertTrue(hits.isEmpty(), "an old attack cannot turn a later air swing into a hit");
    }

    @Test void clientPredictionNeverAppliesTheAuthoritativeStun() {
        CooldownSync.Listener prediction = new CooldownSync.Listener() {
            @Override public boolean isAuthoritative() { return false; }
            @Override public void onAdded(CoreAbility source, BendingPlayer bender, String ability, long expires) { }
            @Override public void onRemoved(BendingPlayer bender, String ability) { }
        };
        CooldownSync.install(prediction);
        try {
            LightningPunch punch = new LightningPunch(player);
            LightningPunch.punch(player, victim);
            tick(punch);
            assertFalse(MovementHandler.isStopped(victim));
            assertTrue(bending.isOnCooldown("FirePunch"));
            assertCooldown("LightningPunch", 4000);
            bending.removeCooldown("FirePunch");
            bending.removeCooldown("LightningPunch");
            LightningPunch missed = new LightningPunch(player);
            LightningPunch.swing(player);
            tick(missed);
            tick(missed);
            assertTrue(missed.isRemoved());
            assertCooldown("LightningPunch", 1000);
            assertFalse(bending.isOnCooldown("FirePunch"));
        } finally {
            CooldownSync.clear(prediction);
        }
    }

    @Test void preparingComboReplacesAnExistingFirePunch() {
        FirePunch fire = new FirePunch(player, null);
        LightningPunch lightning = new LightningPunch(player);
        assertTrue(fire.isRemoved());
        assertTrue(lightning.isStarted());
    }

    @Test void changingSlotsOrBindingLightningCancelsThePreparedFist() {
        LightningPunch first = new LightningPunch(player);
        bound = "LightningBurst";
        tick(first);
        assertTrue(first.isRemoved());
        bound = "FirePunch";
        LightningPunch second = new LightningPunch(player);
        bending.getAbilities().put(7, "Lightning");
        tick(second);
        assertTrue(second.isRemoved());
        assertTrue(hits.isEmpty());
    }

    @Test void cannotPunchThroughSolidTerrainOrProtection() {
        LightningPunch punch = new LightningPunch(player);
        world.getBlockAt(0, 65, 1).setType(Material.STONE);
        assertTrue(LightningPunch.punch(player, victim));
        tick(punch);
        assertTrue(hits.isEmpty());
        world.getBlockAt(0, 65, 1).setType(Material.AIR);
        RegionProtection.registerRegionProtection("LightningPunchTest", (p, location, ability) -> location.getZ() > 2);
        RegionProtection.clearCache();
        assertTrue(LightningPunch.punch(player, victim));
        tick(punch);
        assertTrue(hits.isEmpty());
    }

    private void tick(LightningPunch punch) { AbilityExecutionContext.run(punch, punch::progress); }
    private void useClickCombination() {
        ConfigManager.getConfig().set("Abilities.Fire.LightningPunch.Combination",
                List.of("LightningBurst:LEFT_CLICK", "FirePunch:LEFT_CLICK"));
        final LightningPunch prototype = (LightningPunch) CoreAbility.getAbility(LightningPunch.class);
        ComboManager.getComboAbilities().put("LightningPunch", new ComboManager.ComboAbilityInfo(
                "LightningPunch", prototype.getCombination(), prototype));
    }
    private void assertCooldown(String ability, long expected) {
        long remaining = bending.getCooldown(ability) - System.currentTimeMillis();
        assertTrue(remaining > expected - 500 && remaining <= expected,
                ability + " cooldown should be " + expected + " ms, remaining " + remaining);
    }

    private record ParticleSpawn(Particle type, Location point, int count, Vector spread, double speed, CoreAbility source) { }
    private static final class Timer implements PKTask {
        private final Runnable action;
        private boolean cancelled;
        private Timer(Runnable action) { this.action = action; }
        private void tick() { if (!cancelled) action.run(); }
        @Override public void cancel() { cancelled = true; }
        @Override public boolean cancelled() { return cancelled; }
        @Override public int legacyId() { return 1; }
    }
    @SuppressWarnings("unchecked") private Map<Object, Object> map(Class<?> type, String name) throws Exception {
        return (Map<Object, Object>) field(type, name).get(null);
    }
    private void backup(Class<?> type, String name) throws Exception {
        Map<Object, Object> map = map(type, name), copy = new HashMap<>(map);
        restores.add(() -> { map.clear(); map.putAll(copy); });
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
