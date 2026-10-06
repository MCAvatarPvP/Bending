package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.*;

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
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.GameMode;
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

import static org.junit.jupiter.api.Assertions.*;

/** Native player movement/damage plus the real bending manager; abilities and world policy are explicit fixtures. */
class FabricRollbackExecutionTest {
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }
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
            assertNull(late.fixture.effects);
        });
    }

    @Test void constructorRejectsForeignWorldsDuplicatePlayersAndDetachedControls() throws Exception {
        var scene = new Fixture(); var foreign = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> new FabricRollbackExecution<>(List.of(scene.attacker, scene.attacker), scene));
        assertThrows(IllegalArgumentException.class, () -> new FabricRollbackExecution<>(List.of(scene.attacker, foreign.defender), scene));
        var nativeState = scene.attacker.state();
        var source = (FabricRollbackNativePlayerState) scene.attacker.body().kinematicsSource();
        var body = RollbackEntityBody.nativeBacked(source.identity(), scene.logical, source, BODY_RULES);
        var living = RollbackLivingState.nativeBacked(body, new RollbackEquipment(nativeState.inventory()), source);
        var detached = new RollbackPlayer(new RollbackPlayerState(living, nativeState.inventory(), nativeState.controls(),
                nativeState.profile(), Set.of(), new com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard(),
                (RollbackPlayerState.Rules) Proxy.newProxyInstance(RollbackPlayerState.Rules.class.getClassLoader(),
                        new Class<?>[]{RollbackPlayerState.Rules.class}, (proxy, method, arguments) -> { throw new AssertionError(method); })));
        assertThrows(IllegalArgumentException.class, () -> new FabricRollbackExecution<>(List.of(detached), scene));
    }

    @Test void offHandIntentReplaysWithoutActivatingMainHandBendingOrRepeatingOnMissingFrames() throws Exception {
        withConfig(() -> {
            var direct = scenario(); var late = scenario();
            var off = new RollbackPlayerInput.Edge(new RollbackInputActions.Action(1, 73,
                    RollbackInputActions.Kind.RIGHT_CLICK, -1, RollbackInputActions.Hand.OFF), 30, -10);
            direct.runtime.submit(A, 1, input(0, List.of(off)));
            var expected = direct.runtime.advance();
            late.runtime.advance();
            late.runtime.submit(A, 1, input(0, List.of(off)));
            var replayed = late.runtime.reconcile();
            var actions = replayed.head().effects().stream().filter(ActionOutput.class::isInstance).toList();
            assertEquals(List.of(new ActionOutput(1, 30, false)), actions);
            assertEquals(expected.head().effects(), replayed.head().effects());
            assertEquals(0, direct.fixture.activations); assertEquals(0, late.fixture.activations);
            assertTrue(late.runtime.advance().head().effects().stream().noneMatch(ActionOutput.class::isInstance));
        });
    }

    @Test void actionsUseTheirOwnAimAndRepeatedSequencesStopBeforeReactivation() throws Exception {
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
    }


    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void nativeCombatFramesMatchThePaperReference(int mode) throws Exception {
        final List<String> expected;
        try (var resource = Objects.requireNonNull(getClass().getResourceAsStream("/rollback/native-combat-frames.txt"))) {
            expected = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines()
                    .filter(line -> line.startsWith(mode + ",")).toList();
        }
        var actual = new ArrayList<String>();
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
    void authoritativeUpdatesRetractPredictedNativeDamageAndKnockback(boolean serverReceivedShot) {
        withConfig(() -> {
            var authority = scenario(); var prediction = scenario(true);
            var id = new UUID(0, 712); Object a = new Object(), b = new Object();
            var server = new RollbackSession<>(id, 55, authority.runtime, List.of(
                    new RollbackSession.Peer(A, a, 0), new RollbackSession.Peer(B, b, 0)));
            var sent = new ArrayList<RollbackInputPacket>();
            var displayed = new ArrayList<RollbackEngine.Update<RollbackDomain.Checkpoint, RollbackPlayerInput, Object>>();
            var sink = new RollbackClientRuntime.Output<RollbackDomain.Checkpoint, Object>() {
                        @Override public void update(RollbackEngine.Update<RollbackDomain.Checkpoint, RollbackPlayerInput, Object> update) { displayed.add(update); }
                        @Override public void stop(RollbackStartServerEndpoint.Failure failure) { fail(failure.detail(), failure.cause()); }
                    };
            var nativePlayers = Map.of(A, (FabricRollbackNativePlayerState) prediction.fixture.attacker.body().kinematicsSource(),
                    B, (FabricRollbackNativePlayerState) prediction.fixture.defender.body().kinematicsSource());
            try (var rendered = new FabricRollbackPlayerRenderer(id, prediction.fixture.world.world(), Set.of(A, B), () -> true,
                    () -> net.minecraft.util.math.Vec3d.ZERO, position -> 123)) {
            var presentation = new FabricRollbackPresentation<>(nativePlayers, A, prediction.runtime, sink, rendered);
            var local = nativePlayers.get(A).ownedPlayer();
            var visibleWorld = RollbackNativeQueryShell.create(net.minecraft.world.World.class).instance();
            var visible = RollbackNativeQueryShell.create(net.minecraft.client.network.ClientPlayerEntity.class)
                    .constant(PlayerEntity::getEntityWorld, visibleWorld).constant(PlayerEntity::getUuid, A)
                    .constant(PlayerEntity::getYaw, 0F).constant(PlayerEntity::getPitch, 0F)
                    .constant(PlayerEntity::getInventory, local.getInventory())
                    .nativeAction(net.minecraft.client.network.ClientPlayerEntity::tick, args -> { }).instance();
            int[] samples = {0};
            visible.input = new net.minecraft.client.input.Input() { @Override public void tick() { samples[0]++; } };
            var client = new FabricRollbackClientRuntime<>(id, 55, visible, prediction.runtime, sent::add, presentation,
                    nativePlayers.get(A), () -> 7, () -> false);
            long[] physicalTick = {0};
            try (var nativeTicks = new FabricRollbackNativeTick(visible, visibleWorld, () -> true,
                    () -> client.nativeTick(physicalTick[0]), failure -> fail("Native input tick failed", failure))) {
            var wire = new RollbackAuthorityChunk.Assembler(id, 0, 40);
            client.start(0);
            var manager = new org.objenesis.ObjenesisStd(false).newInstance(net.minecraft.client.network.ClientPlayerInteractionManager.class);
            try (var nativeActions = new FabricRollbackNativeActions(manager, visible, visibleWorld, () -> true,
                    packet -> client.packet(packet, 0), failure -> fail("Native input capture failed", failure))) {
                assertSame(net.minecraft.util.ActionResult.CONSUME, manager.interactItem(visible, net.minecraft.util.Hand.MAIN_HAND));
            }
            if (serverReceivedShot) {
                server.receive(b, new RollbackInputPacket(id, 1, new RollbackMovementInput(0, 0, true, 0, 0), false, List.of()));
            }
            var publications = new ArrayList<RollbackAuthorityUpdate>();
            for (int tick = 1; tick <= 3; tick++) {
                physicalTick[0] = tick; visible.tick();
                client.tick(tick);
                if (serverReceivedShot || tick > 1) server.receive(a, RollbackInputPacket.decode(sent.getLast().encode()));
                server.advance(); publications.add(server.publish());
            }
            assertEquals(14, prediction.fixture.defender.getHealth());
            assertTrue(prediction.fixture.defender.getVelocity().lengthSquared() > 0.1);
            assertTrue(prediction.fixture.defender.body().kinematics().velocityChanged());
            var hitView = new net.minecraft.client.render.entity.state.PlayerEntityRenderState();
            try (var extraction = FabricRollbackPlayerRenderer.extracting()) { FabricRollbackPlayerRenderer.apply(nativePlayers.get(B).ownedPlayer(), hitView, 1); }
            assertTrue(hitView.hurt, "The provisional native hit must be visible before its authority reply");
            assertEquals(nativePlayers.get(B).ownedPlayer().getEntityPos(), new net.minecraft.util.math.Vec3d(hitView.x, hitView.y, hitView.z));
            assertEquals(20, authority.fixture.defender.getHealth());
            assertEquals(0, prediction.runtime.diagnostics().confirmedTick());
            for (var publication : publications) client.authority(deliver(wire, publication));
            assertEquals(serverReceivedShot ? 20 : 14, prediction.fixture.defender.getHealth(),
                    "An unreceived local shot stays provisional while its tick can still be accepted");
            var serverUpdate = server.advance(); var publication = server.publish();
            client.authority(deliver(wire, publication));
            var corrected = displayed.getLast();
            assertEquals(20, prediction.fixture.defender.getHealth());
            assertEquals(authority.fixture.defender.body().kinematics(), prediction.fixture.defender.body().kinematics());
            assertEquals(authority.fixture.history, prediction.fixture.history);
            var correctedView = new net.minecraft.client.render.entity.state.PlayerEntityRenderState();
            try (var extraction = FabricRollbackPlayerRenderer.extracting()) { FabricRollbackPlayerRenderer.apply(nativePlayers.get(B).ownedPlayer(), correctedView, 1); }
            assertFalse(correctedView.hurt, "A retracted hit cannot leave its hurt appearance behind");
            assertEquals(nativePlayers.get(B).ownedPlayer().getEntityPos(), new net.minecraft.util.math.Vec3d(correctedView.x, correctedView.y, correctedView.z));
            assertEquals(serverUpdate.finalizedEffects(), corrected.finalizedEffects());
            assertEquals(1, corrected.confirmed().tick());
            for (var chunk : RollbackAuthorityChunk.split(publication)) assertNull(wire.receive(chunk, publication.headTick()));
            int displays = displayed.size(); client.authority(publication);
            assertEquals(displays, displayed.size()); assertEquals(3, sent.size(), "Corrections never resend physical input");
            assertEquals(3, samples[0], "Corrections cannot resample keyboard input");
            assertEquals(0, visible.age, "Only the private player can run its gameplay tick");
            assertFalse(RollbackDomain.active()); assertFalse(RollbackClock.active());
            }
            }
        });
    }

    private static RollbackAuthorityUpdate deliver(RollbackAuthorityChunk.Assembler wire, RollbackAuthorityUpdate publication) {
        RollbackAuthorityUpdate delivered = null;
        for (var chunk : RollbackAuthorityChunk.split(publication)) {
            assertNull(delivered, "No correction can become visible before its final chunk");
            delivered = wire.receive(RollbackAuthorityChunk.decode(chunk.encode()), publication.headTick());
        }
        return java.util.Objects.requireNonNull(delivered);
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "true,true"})
    void lateInventoryActionReplaysInventoryAndNativeOutputs(boolean slotChange, boolean cancelled) throws Exception {
        withConfig(() -> {
            java.util.function.Consumer<Fixture> prepare = fixture -> {
                fixture.queries.cancelSlot = cancelled;
                var player = ((FabricRollbackNativePlayerState) fixture.attacker.body().kinematicsSource()).ownedPlayer();
                    player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND_SWORD));
                    player.setStackInHand(net.minecraft.util.Hand.OFF_HAND, new net.minecraft.item.ItemStack(net.minecraft.item.Items.SHIELD));
            };
            var direct = scenario(false, prepare, fixture -> { }); var late = scenario(false, prepare, fixture -> { });
            var edge = new RollbackPlayerInput.Edge(new RollbackInputActions.Action(1, 23,
                    slotChange ? RollbackInputActions.Kind.SLOT_CHANGE : RollbackInputActions.Kind.SWAP_HANDS, slotChange ? 1 : -1), 0, 0);
            var held = input(0, List.of(edge));
            assertEquals(RollbackEngine.Submission.ACCEPTED, direct.runtime.submit(A, 1, held));
            var expected = direct.runtime.advance(); late.runtime.advance();
            assertEquals(RollbackEngine.Submission.ACCEPTED, late.runtime.submit(A, 1, held));
            var replay = late.runtime.reconcile();
            var directBody = ((FabricRollbackNativePlayerState) direct.fixture.attacker.body().kinematicsSource()).ownedPlayer();
            var lateBody = ((FabricRollbackNativePlayerState) late.fixture.attacker.body().kinematicsSource()).ownedPlayer();
            assertEquals(slotChange ? (cancelled ? net.minecraft.item.Items.DIAMOND_SWORD : net.minecraft.item.Items.AIR) : net.minecraft.item.Items.SHIELD, directBody.getMainHandStack().getItem());
            assertEquals(slotChange ? net.minecraft.item.Items.SHIELD : net.minecraft.item.Items.DIAMOND_SWORD, directBody.getOffHandStack().getItem());
            assertEquals(directBody.getMainHandStack().getItem(), lateBody.getMainHandStack().getItem());
            assertEquals(directBody.getOffHandStack().getItem(), lateBody.getOffHandStack().getItem());
            assertEquals(expected.head().effects(), replay.head().effects());
            if (cancelled) assertTrue(replay.head().effects().contains(new FabricRollbackPacketData.HeldSlot(A, 0)));

        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void offHandSwingRunsAfterBendingCancellationAndReplaysFromLateInput(boolean cancelled) throws Exception {
        withConfig(() -> {
            java.util.function.Consumer<Fixture> install = fixture -> AbilityActivationManager.registerGlobal(ClickType.LEFT_CLICK, context -> {
                if (cancelled) context.cancelEvent();
                context.stopProcessing();
                return true;
            });
            var direct = scenario(false, fixture -> { }, install);
            var late = scenario(false, fixture -> { }, install);
            var edge = new RollbackPlayerInput.Edge(new RollbackInputActions.Action(1, 23,
                    RollbackInputActions.Kind.OFF_HAND_SWING, -1), 0, 0);
            var held = input(0, List.of(edge));
            assertEquals(RollbackEngine.Submission.ACCEPTED, direct.runtime.submit(A, 1, held));
            var expected = direct.runtime.advance(); late.runtime.advance();
            assertEquals(RollbackEngine.Submission.ACCEPTED, late.runtime.submit(A, 1, held));
            var replay = late.runtime.reconcile();
            var directBody = ((FabricRollbackNativePlayerState) direct.fixture.attacker.body().kinematicsSource()).ownedPlayer();
            var lateBody = ((FabricRollbackNativePlayerState) late.fixture.attacker.body().kinematicsSource()).ownedPlayer();
            assertEquals(!cancelled, directBody.handSwinging); assertEquals(directBody.handSwinging, lateBody.handSwinging);
            assertEquals(directBody.handSwingTicks, lateBody.handSwingTicks);
            if (!cancelled) assertEquals(net.minecraft.util.Hand.OFF_HAND, lateBody.preferredHand);
            var expectedAnimations = expected.head().effects().stream().filter(FabricRollbackPacketData.Tracked.class::isInstance).toList();
            var replayAnimations = replay.head().effects().stream().filter(FabricRollbackPacketData.Tracked.class::isInstance).toList();
            assertEquals(expectedAnimations, replayAnimations);
            assertEquals(cancelled ? 0 : 1, expectedAnimations.stream().map(FabricRollbackPacketData.Tracked.class::cast)
                    .filter(value -> value.data() instanceof FabricRollbackPacketData.Animation).count());
        });
    }
    private Scenario scenario() { return scenario(false); }
    private Scenario scenario(boolean replica) {
        return scenario(replica, fixture -> { });
    }
    private Scenario scenario(boolean replica, java.util.function.Consumer<Fixture> prepare) {
        return scenario(replica, prepare, fixture -> { });
    }
    private Scenario scenario(boolean replica, java.util.function.Consumer<Fixture> prepare, java.util.function.Consumer<Fixture> install) {
        var fixture = new Fixture();
        prepare.accept(fixture);
        var execution = new FabricRollbackExecution<Object>(List.of(fixture.defender, fixture.attacker), fixture);
        fixture.output = execution.output();
        var shared = sharedRoots();
        var scheduler = new RollbackScheduler(50, 50);
        var prediction = PredictionServices.builder().bind(AbilityRemovalSync.Listener.class,
                (ability, external) -> fixture.record("removed")).build();
        var environment = new RollbackCombatRuntime.Environment(graph(), shared, List.of(), platform(scheduler), fixture.items, prediction);
        Runnable bootstrap = () -> {
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
        };
        var limits = new RollbackEngine.Limits(replica ? 10 : 3, 1, 500, 50_000_000);
        var runtime = replica
                ? RollbackCombatRuntime.createReplica(environment, execution, bootstrap, Map.of(A, IDLE, B, IDLE), limits, 1_000, 1_000_000_000)
                : RollbackCombatRuntime.create(environment, execution, bootstrap, Map.of(A, IDLE, B, IDLE), limits, 1_000, 1_000_000_000);
        return new Scenario(fixture, runtime);
    }

    @Test void lateGlideRequestReplaysTheSameNativeThreeDimensionalMovementAsOnTimeInput() throws Exception {
        withConfig(() -> {
            java.util.function.Consumer<Fixture> prepare = fixture -> {
                var player = ((FabricRollbackNativePlayerState) fixture.attacker.body().kinematicsSource()).ownedPlayer();
                player.setPosition(.5, 3, .5); player.setOnGround(false); player.setVelocity(.15, -.1, .2);
                player.equipStack(net.minecraft.entity.EquipmentSlot.CHEST, new net.minecraft.item.ItemStack(net.minecraft.item.Items.ELYTRA));
            };
            var direct = scenario(false, prepare); var late = scenario(false, prepare);
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
            assertEquals(direct.fixture.queries.events, late.fixture.queries.events);
        });
    }

    @Test void automaticNativeStopUsesThePrivateBendingFlightRuleAndDoesNotLeakItsRegistry() throws Exception {
        withConfig(() -> {
            var outside = new HashSet<>(com.projectkorra.projectkorra.airbending.flight.FlightMultiAbility.getFlyingPlayers());
            var scene = scenario(false, fixture -> {
                fixture.attacker.setGliding(true);
                var player = ((FabricRollbackNativePlayerState) fixture.attacker.body().kinematicsSource()).ownedPlayer();
                player.setPosition(.5, 3, .5); player.setOnGround(false); player.setVelocity(.15, -.1, .2);
            }, fixture -> com.projectkorra.projectkorra.airbending.flight.FlightMultiAbility.getFlyingPlayers().add(A));
            scene.runtime.advance();
            assertTrue(scene.fixture.attacker.isGliding(), "The existing bending restriction must cancel a native stop even without Elytra");
            assertTrue(scene.fixture.queries.events.stream().anyMatch(event -> event.event().equals("glide:false:true")));
            assertEquals(outside, com.projectkorra.projectkorra.airbending.flight.FlightMultiAbility.getFlyingPlayers());
            assertFalse(RollbackControlEvents.cancelGlide(A), "The native event scope must be released after execution");
        });
    }

    @Test void lateFlightIntentReplaysTakeoffToTheSameNativeHeadAsOnTimeInput() throws Exception {
        withConfig(() -> {
            var direct = scenario(false, fixture -> fixture.attacker.setAllowFlight(true));
            var late = scenario(false, fixture -> fixture.attacker.setAllowFlight(true));
            var wire = new RollbackInputPacket(new UUID(0, 19), 1, IDLE.movement(), false,
                    List.of(new RollbackInputPacket.Edge(1, RollbackInputActions.Kind.FLIGHT_START, -1, 0, 0)));
            var input = RollbackInputPacket.decode(wire.encode()).playerInput(A, 55);
            assertEquals(RollbackEngine.Submission.ACCEPTED, direct.runtime.submit(A, 1, input));
            for (int i = 0; i < 3; i++) { direct.runtime.advance(); late.runtime.advance(); }
            assertTrue(direct.fixture.attacker.isFlying()); assertFalse(late.fixture.attacker.isFlying());
            assertTrue(direct.fixture.attacker.getLocation().getY() > late.fixture.attacker.getLocation().getY());
            assertEquals(RollbackEngine.Submission.ACCEPTED, late.runtime.submit(A, 1, input)); late.runtime.reconcile();
            assertTrue(late.fixture.attacker.isFlying());
            assertEquals(direct.fixture.attacker.body().kinematics(), late.fixture.attacker.body().kinematics());
            assertEquals(direct.fixture.attacker.state().controls(), late.fixture.attacker.state().controls());
            assertEquals(direct.fixture.queries.events, late.fixture.queries.events);
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void landingRequestsFlightStopAndHonorsCapturedCancellation(boolean cancelled) throws Exception {
        withConfig(() -> {
            var scene = scenario(false, fixture -> {
                fixture.attacker.setAllowFlight(true); fixture.attacker.setFlying(true);
                var state = (FabricRollbackNativePlayerState) fixture.attacker.body().kinematicsSource();
                state.ownedPlayer().setOnGround(false); state.ownedPlayer().setVelocity(0, -.1, 0);
                fixture.queries.cancelFlight = cancelled;
            });
            scene.runtime.advance();
            assertTrue(scene.fixture.attacker.isOnGround()); assertEquals(cancelled, scene.fixture.attacker.isFlying());
            assertTrue(scene.fixture.queries.events.stream().anyMatch(event -> event.event().equals("flight:false:false")));
        });
    }

    private static final class Fixture implements FabricRollbackExecution.Services<Object, Fixture.Counters> {
        record Counters(int activations, int worldTicks) { }
        final FabricRollbackWorldAccessTest.Queries queries = new FabricRollbackWorldAccessTest.Queries(new RollbackBlockStore.Bounds(-5, -4, -5, 12, 12, 16));
        final World logical = queries.logical;
        final FabricRollbackWorldAccess world = new FabricRollbackWorldAccess(queries);
        final RollbackNativeItems<net.minecraft.item.ItemStack> items = new RollbackNativeItems<>(new FabricRollbackItems(queries.registries(), Map.of("minecraft:air", RollbackNativeItems.Kind.GENERIC, "minecraft:stone", RollbackNativeItems.Kind.GENERIC)));
        final RollbackPlayer attacker = player(A, 0.5), defender = player(B, 2);
        final List<String> history = new ArrayList<>();
        RollbackStep<Object> effects;
        java.util.function.Consumer<Object> output;
        int activations, worldTicks;
        Fixture() {
            for (int x = -3; x <= 8; x++) for (int z = -3; z <= 10; z++) {
                var data = new com.projectkorra.projectkorra.platform.mc.block.data.BlockData(Material.STONE);
                data.setExactState("minecraft:stone"); queries.logical.getBlockAt(x, 0, z).setBlockData(data, false);
            }
        }
        private RollbackPlayer player(UUID id, double z) {
            var nativePlayer = new NativePlayer(world.world(), new GameProfile(id, "player" + id.getLeastSignificantBits()));
            nativePlayer.setId((int) id.getLeastSignificantBits());
            nativePlayer.setPosition(0.5, 1, z); nativePlayer.setYaw(0); nativePlayer.setPitch(0); nativePlayer.setOnGround(true);
            nativePlayer.getRandom().setSeed(id.getLeastSignificantBits());
            var state = new FabricRollbackNativePlayerState(nativePlayer, world, 200_000);
            var body = RollbackEntityBody.nativeBacked(state.identity(), logical, state, BODY_RULES);
            var inventory = FabricRollbackInventory.bind(state, items);
            var living = RollbackLivingState.nativeBacked(body, new RollbackEquipment(inventory), state);
            var rules = (RollbackPlayerState.Rules) Proxy.newProxyInstance(RollbackPlayerState.Rules.class.getClassLoader(),
                    new Class<?>[]{RollbackPlayerState.Rules.class}, (proxy, method, arguments) -> { throw new AssertionError(method); });
            return new RollbackPlayer(RollbackPlayerState.nativeBacked(living, inventory,
                    new RollbackPlayerState.Profile(state.identity().name(), "SURVIVAL", "RIGHT", true, false, true, 100),
                    Set.of(), new com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard(), rules, state));
        }
        void record(String value) { history.add(value); this.output.accept(value); }
        @Override public void begin(RollbackStep<Object> output) {
            assertNull(effects); effects = output; queries.time = output.tick(); queries.outputs.clear(); queries.events.clear(); queries.waypoints.clear();
        }
        @Override public void action(RollbackPlayer player, RollbackPlayerInput.Edge edge, CommonInputHandler.InputResult result) {
            // Main-hand fixture actions activate bending; off-hand use passes to the native remainder.
            assertEquals(edge.action().hand() == RollbackInputActions.Hand.MAIN, result.cancelEvent());
            assertEquals(edge.action().sequence(), PredictionDeterminism.currentAction());
            assertEquals(edge.action().seed(), PredictionDeterminism.currentSeed());
            this.output.accept(new ActionOutput(edge.action().sequence(), player.getLocation().getYaw(), result.cancelEvent()));
        }
        @Override public void tickWorld(long tick) { worldTicks++; record("world:" + tick); }
        @Override public void end() {
            if (effects == null) return;
            try {
                queries.outputs.forEach(this.output::accept);
                for (var player : List.of(attacker, defender)) {
                    var state = (FabricRollbackNativePlayerState) player.body().kinematicsSource();
                    this.output.accept(new PlayerFrame(player.getUniqueId(), player.body().kinematics(), player.getHealth(),
                            state.use(value -> value.age)));
                }
            } finally { effects = null; }
        }
        @Override public Counters captureRollbackState() { assertNull(effects); return new Counters(activations, worldTicks); }
        @Override public void restoreRollbackState(Counters state) { activations = state.activations(); worldTicks = state.worldTicks(); }
        @Override public List<?> rollbackReferences() { return List.of(world, history); }
    }
    private static final class NativePlayer extends FabricRollbackSimulatedPlayer {
        NativePlayer(net.minecraft.world.World world, GameProfile profile) { super(world, profile); }
        @Override public GameMode getGameMode() { return GameMode.SURVIVAL; }
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
