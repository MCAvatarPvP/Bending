package com.projectkorra.projectkorra.prediction.rollback;

import com.mojang.authlib.GameProfile;
import com.projectkorra.projectkorra.*;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.ability.util.*;
import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.object.HorizontalVelocityTracker;
import com.projectkorra.projectkorra.platform.*;
import com.projectkorra.projectkorra.platform.mc.*;
import com.projectkorra.projectkorra.prediction.action.AbilityRemovalSync;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.prediction.authority.PredictionServices;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import com.projectkorra.projectkorra.prediction.state.PredictionConfigSync;
import com.projectkorra.projectkorra.util.*;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

/** Native player movement/damage plus the real bending manager; abilities and world policy are explicit fixtures. */
class PaperRollbackExecutionNativeTest {
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }
    @TempDir Path directory;
    private static final UUID A = new UUID(0, 901), B = new UUID(0, 902);
    private static final RollbackPlayerInput IDLE = input(0, List.of());
    private static RollbackPlayerInput input(float strafe, List<RollbackPlayerInput.Edge> edges) {
        return new RollbackPlayerInput(new RollbackMovementInput(strafe, 0, false, 0, 0), strafe != 0, edges);
    }
    private static RollbackPlayerInput.Edge click(long sequence, float yaw) {
        return new RollbackPlayerInput.Edge(new RollbackInputActions.Action(sequence, 23, RollbackInputActions.Kind.RIGHT_CLICK, -1), yaw, 0);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void lateDodgeOrJumpRetractsDynamicCollisionDamageAndMatchesOnTimeNativeFrames(boolean jumping) throws Exception {
        onTickThread(() -> {
            withConfig(() -> {
                var direct = scenario(); var late = scenario();
                Object directA = new Object(), directB = new Object(), lateA = new Object(), lateB = new Object();
                var sessionId = new UUID(0, 711);
                var directSession = new RollbackSession<>(sessionId, 55, direct.runtime, List.of(
                        new RollbackSession.Peer(A, directA, 100), new RollbackSession.Peer(B, directB, 500)));
                var lateSession = new RollbackSession<>(sessionId, 55, late.runtime, List.of(
                        new RollbackSession.Peer(A, lateA, 100), new RollbackSession.Peer(B, lateB, 500)));
                var dodge = jumping ? new RollbackPlayerInput(new RollbackMovementInput(0, 0, true, 0, 0), false, List.of()) : input(1, List.of());
                var outsideManager = BendingManager.getInstance(); var outsideCollision = ProjectKorra.collisionManager;
                receive(directSession, directA, 101, input(0, List.of(click(1, 0))));
                receive(lateSession, lateA, 101, input(0, List.of(click(1, 0))));
                receive(directSession, directB, 501, dodge);
                RollbackEngine.Update<RollbackDomain.Checkpoint, RollbackPlayerInput, Object> expected = null;
                for (int tick = 1; tick <= 3; tick++) { expected = directSession.advance(); lateSession.advance(); }
                assertEquals(20, direct.fixture.defender.getHealth());
                assertEquals(14, late.fixture.defender.getHealth(), "The provisional branch must actually hit before the dodge arrives");
                assertTrue(late.fixture.history.contains("collision"));
                assertEquals(RollbackSession.Status.ACCEPTED, receive(lateSession, lateB, 501, dodge).status());
                var corrected = lateSession.reconcile();
                assertEquals(1, corrected.replayedFrom());
                assertEquals(20, late.fixture.defender.getHealth());
                assertFalse(late.fixture.history.contains("collision"));
                assertEquals(direct.fixture.defender.body().kinematics(), late.fixture.defender.body().kinematics());
                assertEquals(direct.fixture.history, late.fixture.history);
                assertEquals(expected.head().effects(), corrected.head().effects());
                assertEquals(1, late.fixture.activations);
                assertEquals(3, late.fixture.worldTicks);
                assertEquals(List.of(1L), lateSession.acknowledgement(lateA).receivedTicks());
                assertEquals(RollbackSession.Status.CONFLICTING_INPUT, receive(lateSession, lateB, 501, IDLE).status());
                assertFalse(lateSession.closed());
                for (int tick = 4; tick <= 7; tick++) {
                    assertEquals(directSession.advance().finalizedEffects(), lateSession.advance().finalizedEffects());
                }
                assertEquals(1, late.fixture.activations, "Missing frames must not repeat the click");
                assertSame(outsideManager, BendingManager.getInstance()); assertSame(outsideCollision, ProjectKorra.collisionManager);
                assertFalse(RollbackDomain.active()); assertFalse(RollbackClock.active());
                assertNull(late.fixture.effects); assertNull(Bukkit.getServer());
            });
            return null;
        });
    }

    @Test void constructorRejectsForeignWorldsDuplicatePlayersAndDetachedControls() throws Exception {
        onTickThread(() -> {
            var scene = new Fixture(); var foreign = new Fixture();
            assertThrows(IllegalArgumentException.class, () -> new PaperRollbackExecution<>(List.of(scene.attacker, scene.attacker), scene));
            assertThrows(IllegalArgumentException.class, () -> new PaperRollbackExecution<>(List.of(scene.attacker, foreign.defender), scene));
            var nativeState = scene.attacker.state();
            var source = (PaperRollbackNativePlayerState) scene.attacker.body().kinematicsSource();
            var body = RollbackEntityBody.nativeBacked(source.identity(), scene.logical, source, BODY_RULES);
            var living = RollbackLivingState.nativeBacked(body, new RollbackEquipment(nativeState.inventory()), source);
            var detached = new RollbackPlayer(new RollbackPlayerState(living, nativeState.inventory(), nativeState.controls(),
                    nativeState.profile(), Set.of(), new com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard(),
                    (RollbackPlayerState.Rules) Proxy.newProxyInstance(RollbackPlayerState.Rules.class.getClassLoader(),
                            new Class<?>[]{RollbackPlayerState.Rules.class}, (proxy, method, arguments) -> { throw new AssertionError(method); })));
            assertThrows(IllegalArgumentException.class, () -> new PaperRollbackExecution<>(List.of(detached), scene));
            return null;
        });
    }

    @Test void actionsUseTheirOwnAimAndRepeatedSequencesStopBeforeReactivation() throws Exception {
        onTickThread(() -> {
            withConfig(() -> {
                var scene = scenario();
                scene.runtime.submit(A, 1, input(0, List.of(click(1, 90), click(2, -90))));
                var first = scene.runtime.advance();
                var actions = first.head().effects().stream().filter(ActionOutput.class::isInstance).map(ActionOutput.class::cast).toList();
                assertEquals(List.of(new ActionOutput(1, 90, true), new ActionOutput(2, -90, true)), actions);
                assertEquals(0, scene.fixture.attacker.getLocation().getYaw());
                assertEquals(2, scene.fixture.activations);
                scene.runtime.submit(A, 2, input(0, List.of(click(2, 0))));
                assertThrows(IllegalArgumentException.class, scene.runtime::advance);
                assertEquals(2, scene.fixture.activations);
                assertTrue(scene.runtime.failed()); assertNull(scene.fixture.effects);
            assertThrows(IllegalStateException.class, () -> scene.fixture.output.accept("late callback"));
                assertFalse(RollbackDomain.active()); assertFalse(RollbackClock.active());
                assertEquals(0, PredictionDeterminism.currentAction()); assertEquals(0, PredictionDeterminism.currentSeed());
                assertEquals(0, scene.runtime.diagnostics().confirmedTick());
            });
            return null;
        });
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void nativeCombatFramesMatchThePaperReference(int mode) throws Exception {
        final List<String> expected;
        try (var resource = Objects.requireNonNull(getClass().getResourceAsStream("/rollback/native-combat-frames.txt"))) {
            expected = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines()
                    .filter(line -> line.startsWith(mode + ",")).toList();
        }
        var actual = new ArrayList<String>();
        onTickThread(() -> {
            withConfig(() -> {
                var scene = scenario();
                scene.runtime.submit(A, 1, input(0, List.of(click(1, 0))));
                var defence = mode == 2 ? new RollbackPlayerInput(new RollbackMovementInput(0, 0, true, 0, 0), false, List.of())
                        : mode == 1 ? input(1, List.of()) : IDLE;
                scene.runtime.submit(B, 1, defence);
                for (int tick = 1; tick <= 7; tick++) {
                    var frame = scene.runtime.advance();
                    for (var output : frame.head().effects()) {
                        if (output instanceof PlayerFrame state) actual.add(mode + "," + tick + "," + state);
                    }
                }
            });
            return null;
        });
        assertEquals(14, expected.size());
        assertEquals(expected, actual);
    }

    private record ActionOutput(long sequence, float yaw, boolean cancelled) { }
    private record PlayerFrame(UUID id, RollbackEntityBody.Kinematics motion, double health, int age) { }
    private record Scenario(Fixture fixture, RollbackCombatRuntime<RollbackPlayerInput, Object> runtime) { }

    private static RollbackSession.Receipt receive(RollbackSession<RollbackDomain.Checkpoint, Object> session,
            Object connection, long clientTick, RollbackPlayerInput input) {
        var packet = new RollbackInputPacket(session.id(), clientTick, input.movement(), input.sprinting(), input.actions().stream()
                .map(edge -> new RollbackInputPacket.Edge(edge.action().sequence(), edge.action().kind(), edge.action().slot(), edge.yaw(), edge.pitch())).toList());
        return session.receive(connection, RollbackInputPacket.decode(packet.encode()));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void offHandSwingRunsAfterBendingCancellationAndReplaysFromLateInput(boolean cancelled) throws Exception {
        onTickThread(() -> {
            withConfig(() -> {
                java.util.function.Consumer<Fixture> install = fixture -> AbilityActivationManager.registerGlobal(ClickType.LEFT_CLICK, context -> {
                    if (cancelled) context.cancelEvent();
                    context.stopProcessing(); return true;
                });
                var direct = scenario(fixture -> { }, install); var late = scenario(fixture -> { }, install);
                var edge = new RollbackPlayerInput.Edge(new RollbackInputActions.Action(1, 23,
                        RollbackInputActions.Kind.OFF_HAND_SWING, -1), 0, 0);
                var held = input(0, List.of(edge));
                assertEquals(RollbackEngine.Submission.ACCEPTED, direct.runtime.submit(A, 1, held));
                var expected = direct.runtime.advance(); late.runtime.advance();
                assertEquals(RollbackEngine.Submission.ACCEPTED, late.runtime.submit(A, 1, held));
                var replay = late.runtime.reconcile();
                var directBody = ((PaperRollbackNativePlayerState) direct.fixture.attacker.body().kinematicsSource()).ownedPlayer();
                var lateBody = ((PaperRollbackNativePlayerState) late.fixture.attacker.body().kinematicsSource()).ownedPlayer();
                assertEquals(!cancelled, directBody.swinging); assertEquals(directBody.swinging, lateBody.swinging);
                assertEquals(directBody.swingTime, lateBody.swingTime);
                if (!cancelled) assertEquals(net.minecraft.world.InteractionHand.OFF_HAND, lateBody.swingingArm);
                var expectedAnimations = expected.head().effects().stream().filter(PaperRollbackPacketData.Tracked.class::isInstance).toList();
                var replayAnimations = replay.head().effects().stream().filter(PaperRollbackPacketData.Tracked.class::isInstance).toList();
                assertEquals(expectedAnimations, replayAnimations);
                assertEquals(cancelled ? 0 : 1, expectedAnimations.stream().map(PaperRollbackPacketData.Tracked.class::cast)
                        .filter(value -> value.data() instanceof PaperRollbackPacketData.Animation).count());
            }); return null;
        });
    }
    private Scenario scenario() {
        return scenario(fixture -> { }, fixture -> { });
    }
    private Scenario scenario(java.util.function.Consumer<Fixture> prepare, java.util.function.Consumer<Fixture> install) {
        var fixture = new Fixture();
        prepare.accept(fixture);
        var execution = new PaperRollbackExecution<Object>(List.of(fixture.defender, fixture.attacker), fixture);
        fixture.output = execution.output();
        var shared = sharedRoots();
        var scheduler = new RollbackScheduler(50, 50);
        var prediction = PredictionServices.builder().bind(AbilityRemovalSync.Listener.class,
                (ability, external) -> fixture.record("removed")).build();
        var environment = new RollbackCombatRuntime.Environment(graph(), shared, List.of(), platform(scheduler), fixture.items, prediction);
        var runtime = RollbackCombatRuntime.create(environment, execution, () -> {
            clearCollections(shared);
            BendingPlayer.getPlayers().put(A, new BendingPlayer(fixture.attacker));
            BendingPlayer.getPlayers().put(B, new BendingPlayer(fixture.defender));
            ProjectKorra.collisionManager = new CollisionManager();
            var volume = new MovingVolume(fixture); volume.start();
            ProjectKorra.collisionManager.addCollision(new Collision(new AcceleratingProjectile(fixture), volume, true, false));
            AbilityActivationManager.registerGlobal(ClickType.RIGHT_CLICK, context -> {
                fixture.activations++;
                new AcceleratingProjectile(fixture).start();
                context.cancelEvent();
                return true;
            });
            install.accept(fixture);
        }, Map.of(A, IDLE, B, IDLE), new RollbackEngine.Limits(3, 1, 500, 50_000_000), 1_000, 1_000_000_000);
        return new Scenario(fixture, runtime);
    }

    @Test void lateGlideRequestReplaysNativeMovementAndTheSameEventSequence() throws Exception {
        onTickThread(() -> {
            withConfig(() -> {
                java.util.function.Consumer<Fixture> prepare = fixture -> {
                    var player = ((PaperRollbackNativePlayerState) fixture.attacker.body().kinematicsSource()).ownedPlayer();
                    player.setPos(.5, 3, .5); player.setOnGround(false); player.setDeltaMovement(.15, -.1, .2);
                    player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ELYTRA));
                };
                var direct = scenario(prepare, fixture -> { }); var late = scenario(prepare, fixture -> { });
                var wire = new RollbackInputPacket(new UUID(0, 20), 1, new RollbackMovementInput(0, 0, false, 30, -15), false,
                        List.of(new RollbackInputPacket.Edge(1, RollbackInputActions.Kind.GLIDE_START, -1, 30, -15)));
                var input = RollbackInputPacket.decode(wire.encode()).playerInput(A, 55);
                assertEquals(RollbackEngine.Submission.ACCEPTED, direct.runtime.submit(A, 1, input));
                for (int i = 0; i < 3; i++) { direct.runtime.advance(); late.runtime.advance(); }
                assertTrue(direct.fixture.attacker.isGliding()); assertFalse(late.fixture.attacker.isGliding());
                assertNotEquals(direct.fixture.attacker.body().kinematics(), late.fixture.attacker.body().kinematics());
                assertEquals(RollbackEngine.Submission.ACCEPTED, late.runtime.submit(A, 1, input)); late.runtime.reconcile();
                assertTrue(late.fixture.attacker.isGliding());
                assertEquals(direct.fixture.attacker.body().kinematics(), late.fixture.attacker.body().kinematics());
                assertEquals(direct.fixture.combat.events, late.fixture.combat.events);
            }); return null;
        });
    }

    @Test void automaticGlideStopUsesThePrivateBendingRuleAndReleasesItsScope() throws Exception {
        onTickThread(() -> {
            withConfig(() -> {
                var outside = new HashSet<>(com.projectkorra.projectkorra.airbending.flight.FlightMultiAbility.getFlyingPlayers());
                var scene = scenario(fixture -> {
                    fixture.attacker.setGliding(true);
                    var player = ((PaperRollbackNativePlayerState) fixture.attacker.body().kinematicsSource()).ownedPlayer();
                    player.setPos(.5, 3, .5); player.setOnGround(false); player.setDeltaMovement(.15, -.1, .2);
                }, fixture -> com.projectkorra.projectkorra.airbending.flight.FlightMultiAbility.getFlyingPlayers().add(A));
                scene.runtime.advance();
                assertTrue(scene.fixture.attacker.isGliding());
                assertTrue(scene.fixture.combat.events.contains("org.bukkit.event.entity.EntityToggleGlideEvent"));
                assertEquals(outside, com.projectkorra.projectkorra.airbending.flight.FlightMultiAbility.getFlyingPlayers());
                assertFalse(RollbackControlEvents.cancelGlide(A));
            }); return null;
        });
    }

    private static final class Fixture implements PaperRollbackExecution.Services<Object, Fixture.Counters> {
        record Counters(int activations, int worldTicks) { }
        final World logical = new World();
        final PaperRollbackDamageNativeTest.Combat combat = new PaperRollbackDamageNativeTest.Combat();
        final PaperRollbackWorldAccessNativeTest.Queries queries = new PaperRollbackWorldAccessNativeTest.Queries(new RollbackBlockStore.Bounds(-5, -4, -5, 12, 12, 16));
        final PaperRollbackWorldAccess world = new PaperRollbackWorldAccess(queries, combat, 4);
        final RollbackNativeItems<net.minecraft.world.item.ItemStack> items = new RollbackNativeItems<>(new PaperRollbackItems(world.world().registryAccess()));
        final RollbackPlayer attacker = player(A, 0.5), defender = player(B, 2);
        final List<String> history = new ArrayList<>();
        RollbackStep<Object> effects;
        java.util.function.Consumer<Object> output;
        int activations, worldTicks;
        Fixture() {
            for (int x = -3; x <= 8; x++) for (int z = -3; z <= 10; z++) queries.block(x, 0, z, Material.STONE, "minecraft:stone");
        }
        private RollbackPlayer player(UUID id, double z) {
            var state = PaperRollbackNativePlayerState.serverPlayer(world, new GameProfile(id, "player" + id.getLeastSignificantBits()),
                    ClientInformation.createDefault(), GameType.SURVIVAL, id.getLeastSignificantBits(), 200_000);
            state.use(value -> {
                var player = (ServerPlayer) value; player.setId((int) id.getLeastSignificantBits());
                player.setPos(0.5, 1, z); player.setOnGround(true); player.valid = true; return null;
            });
            var body = RollbackEntityBody.nativeBacked(state.identity(), logical, state, BODY_RULES);
            var inventory = PaperRollbackInventory.bind(state, items);
            var living = RollbackLivingState.nativeBacked(body, new RollbackEquipment(inventory), state);
            var rules = (RollbackPlayerState.Rules) Proxy.newProxyInstance(RollbackPlayerState.Rules.class.getClassLoader(),
                    new Class<?>[]{RollbackPlayerState.Rules.class}, (proxy, method, arguments) -> { throw new AssertionError(method); });
            return new RollbackPlayer(RollbackPlayerState.nativeBacked(living, inventory,
                    new RollbackPlayerState.Profile(state.identity().name(), "SURVIVAL", "RIGHT", true, false, true, 100),
                    Set.of(), new com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard(), rules, state));
        }
        void record(String value) { history.add(value); this.output.accept(value); }
        @Override public void begin(RollbackStep<Object> output) {
            assertNull(effects); effects = output; combat.time = output.tick(); combat.outputs.clear(); combat.events.clear();
        }
        @Override public void action(RollbackPlayer player, RollbackPlayerInput.Edge edge, CommonInputHandler.InputResult result) {
            // Fixture actions are bending-only and explicitly cancel the native remainder.
            assertTrue(result.cancelEvent());
            assertEquals(edge.action().sequence(), PredictionDeterminism.currentAction());
            assertEquals(edge.action().seed(), PredictionDeterminism.currentSeed());
            this.output.accept(new ActionOutput(edge.action().sequence(), player.getLocation().getYaw(), result.cancelEvent()));
        }
        @Override public void tickWorld(long tick) { worldTicks++; record("world:" + tick); }
        @Override public void end() {
            if (effects == null) return;
            try {
                combat.outputs.forEach(this.output::accept);
                for (var player : List.of(attacker, defender)) {
                    var state = (PaperRollbackNativePlayerState) player.body().kinematicsSource();
                    this.output.accept(new PlayerFrame(player.getUniqueId(), player.body().kinematics(), player.getHealth(),
                            state.use(value -> ((ServerPlayer) value).tickCount)));
                }
            } finally { effects = null; }
        }
        @Override public Counters captureRollbackState() { assertNull(effects); return new Counters(activations, worldTicks); }
        @Override public void restoreRollbackState(Counters state) { activations = state.activations(); worldTicks = state.worldTicks(); }
        @Override public List<?> rollbackReferences() { return List.of(world, history); }
    }
    private static final RollbackEntityBody.Rules BODY_RULES = new RollbackEntityBody.Rules() {
        @Override public RollbackEntityBody.Pose teleport(RollbackEntityBody body, RollbackEntityBody.Pose destination) { return destination; }
        @Override public boolean canMount(RollbackEntityBody vehicle, RollbackEntityBody passenger) { return false; }
    };

    private abstract static class Dynamic extends CoreAbility {
        final Fixture fixture;
        Dynamic(Fixture fixture, RollbackPlayer owner) { super(); this.fixture = fixture; player = owner; }
        @Override public boolean isEnabled() { return true; }
        @Override public void recalculateAttributes() { }
        @Override public double getCollisionRadius() { return 0.1; }
        @Override public boolean isSneakAbility() { return false; }
        @Override public boolean isHarmlessAbility() { return false; }
        @Override public boolean isIgniteAbility() { return false; }
        @Override public boolean isExplosiveAbility() { return false; }
        @Override public long getCooldown() { return 0; }
        @Override public String getName() { return getClass().getSimpleName(); }
        @Override public Element getElement() { return null; }
    }
    private static final class AcceleratingProjectile extends Dynamic {
        final Location position;
        final com.projectkorra.projectkorra.platform.mc.util.Vector direction;
        int age;
        AcceleratingProjectile(Fixture fixture) {
            super(fixture, fixture.attacker); position = player.getEyeLocation(); direction = position.getDirection();
        }
        @Override public void progress() { position.add(direction.clone().multiply(++age * 0.25)); fixture.record("progress:" + age); }
        @Override public Location getLocation() { return position; }
        @Override public void handleCollision(Collision collision) {
            fixture.record("collision"); collision.getAbilitySecond().getPlayer().damage(6, player); super.handleCollision(collision);
        }
    }
    private static final class MovingVolume extends Dynamic {
        MovingVolume(Fixture fixture) { super(fixture, fixture.defender); }
        @Override public void progress() { }
        @Override public Location getLocation() { return player.getEyeLocation(); }
    }

    private void withConfig(Runnable test) {
        var previous = ConfigManager.defaultConfig;
        var saved = graph().capture(List.of(), RollbackStateGraph.staticFields(PredictionConfigSync.class, field -> true));
        try (var scope = Platform.using(platform(new RollbackScheduler(50, 50)))) {
            ConfigManager.defaultConfig = new Config(directory.resolve("combat.yml").toFile()); test.run();
        } finally { ConfigManager.defaultConfig = previous; saved.restore(); }
    }
    private static RollbackStateGraph graph() { return new RollbackStateGraph(value -> value instanceof World || value instanceof Config, field -> true, 300_000); }
    private ProjectKorraPlatform platform(RollbackScheduler scheduler) {
        PKEventBus events = (PKEventBus) Proxy.newProxyInstance(PKEventBus.class.getClassLoader(), new Class<?>[]{PKEventBus.class}, (proxy, method, args) -> null);
        PKServer server = (PKServer) Proxy.newProxyInstance(PKServer.class.getClassLoader(), new Class<?>[]{PKServer.class}, (proxy, method, args) -> {
            if (method.getName().equals("minecraftVersion")) return "1.21.11"; throw new AssertionError(method);
        });
        PKWorlds worlds = new PKWorlds() { @Override public <W> Collection<W> worlds() { return List.of(); } };
        return (ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(), new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, args) -> switch (method.getName()) {
            case "dataFolder" -> directory;
            case "logger" -> Logger.getLogger("NativeCombatRuntimeTest");
            case "events" -> events;
            case "worlds" -> worlds;
            case "scheduler" -> scheduler;
            case "materials" -> (PKMaterials) material -> false;
            case "server" -> server;
            default -> throw new AssertionError(method);
        });
    }
    private static List<Field> sharedRoots() {
        var shared = new ArrayList<Field>();
        shared.addAll(RollbackStateGraph.staticFields(CoreAbility.class, root -> root.getName().startsWith("INSTANCES")
                || Set.of("currentTick", "idCounter", "ATTRIBUTE_FIELDS").contains(root.getName())));
        // This fixture's explicit roots do not substitute for production addon/service registration.
        for (Class<?> type : List.of(OfflineBendingPlayer.class, AbilityActivationManager.class, ComboManager.class,
                MovementHandler.class, TempPotionEffect.class, RevertChecker.class, HorizontalVelocityTracker.class,
                TempArmor.class, TempFallingBlock.class, TempArmorStand.class, TempBlock.class,
                com.projectkorra.projectkorra.airbending.flight.FlightMultiAbility.class)) {
            shared.addAll(RollbackStateGraph.staticFields(type, field -> !field.getName().equals("DISCOVERED")
                    && (type != OfflineBendingPlayer.class || Set.of("ONLINE_PLAYERS", "PLAYERS").contains(field.getName()))
                    && (Map.class.isAssignableFrom(field.getType()) || Collection.class.isAssignableFrom(field.getType()))));
        }
        return shared;
    }
    private static void clearCollections(List<Field> fields) {
        try {
            for (var field : fields) {
                field.setAccessible(true); Object value = field.get(null);
                if (value instanceof Map<?, ?> map) map.clear(); else if (value instanceof Collection<?> collection) collection.clear();
            }
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
