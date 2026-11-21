package com.projectkorra.projectkorra.prediction.server.impl;

import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.prediction.protocol.PaperPredictionProtocol;
import com.projectkorra.projectkorra.prediction.rollback.*;
import com.projectkorra.projectkorra.prediction.server.PaperPredictionServer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RollbackLegacyIngressTest {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), OTHER = new UUID(0, 3);
    private static final PaperPredictionProtocol.InputKind SWING = PaperPredictionProtocol.InputKind.LEFT_CLICK;

    @Test void enrolledPlayersCannotFeedLegacyInputsClaimsOrOutputsUntilWholeSessionTeardown() throws Exception {
        var constructor = PaperPredictionServer.class.getDeclaredConstructor(JavaPlugin.class);
        constructor.setAccessible(true);
        var server = constructor.newInstance((JavaPlugin) null);
        var field = PaperRollbackIngress.class.getDeclaredField("ingress");
        field.setAccessible(true);
        var ingress = (RollbackIngress) field.get(server.rollbackInputs);
        var session = session();
        var stopped = new ArrayList<RollbackIngress.StopReason>();
        var registration = ingress.enroll(session, value -> stopped.add(value.stopReason()));
        Player a = player(A), outsider = player(OTHER);
        var legacy = new PaperPredictionState.Session(A, UUID.randomUUID(), 8, 0, 0);
        legacy.ready = true;
        server.sessions.put(A, legacy);
        var foreign = new PaperPredictionState.Session(OTHER, UUID.randomUUID(), 8, 0, 0);
        foreign.ready = true;
        server.sessions.put(OTHER, foreign);
        server.onInputVeto(a, new PaperPredictionProtocol.InputVeto(legacy.session, 1, SWING, "Dynamic"));
        server.onActionTag(a, new PaperPredictionProtocol.ActionTag(legacy.session, 1, SWING, 0, "Dynamic"));
        server.onHitClaim(a, claim(legacy.session, B));
        server.onHitClaim(outsider, claim(foreign.session, B));
        assertEquals(0, legacy.claimLimiter.count);
        assertEquals(0, foreign.claimLimiter.count); // Defenders are protected even against an outsider's claim.
        assertTrue(legacy.inputVetoes.isEmpty());
        assertEquals(0, legacy.actionTags.consume(SWING, 0, "Dynamic"));
        server.onReady(a, new PaperPredictionProtocol.Ready(legacy.session, List.of("Dynamic")));
        assertTrue(legacy.supportedAbilities.isEmpty());
        assertDoesNotThrow(() -> server.send(a, PaperPredictionProtocol.STATE, new byte[0])); // Player rejects all native calls except UUID.
        var nativeCalls = new AtomicInteger();
        java.util.function.Supplier<CommonInputHandler.InputResult> nativeInput = () -> { nativeCalls.incrementAndGet(); return CommonInputHandler.InputResult.pass(); };
        assertTrue(server.handleVanilla0(a, SWING, nativeInput).cancelEvent());
        assertTrue(server.processInput(a, legacy, SWING, nativeInput).cancelEvent());
        assertEquals(0, nativeCalls.get());
        server.sessions.remove(OTHER);
        assertFalse(server.handleVanilla0(outsider, SWING, nativeInput).cancelEvent());
        assertEquals(1, nativeCalls.get());
        // A legacy reset is a whole-session stop, never permission to start one normal client.
        server.onHello(a, new PaperPredictionProtocol.Hello(PaperPredictionProtocol.VERSION, 0, 8));
        assertEquals(List.of(RollbackIngress.StopReason.CLIENT_RESET), stopped);
        assertTrue(session.closed());
        assertTrue(server.rollbackInputs.blocksLegacy(A)); assertTrue(server.rollbackInputs.blocksLegacy(B));
        assertTrue(server.handleVanilla0(a, SWING, nativeInput).cancelEvent());
        server.onClientDisabled(a, new PaperPredictionProtocol.ClientDisabled(PaperPredictionProtocol.VERSION));
        assertEquals(1, stopped.size());
        assertFalse(server.sessions.containsKey(A));
        registration.finishStop();
        assertFalse(server.handleVanilla0(a, SWING, nativeInput).cancelEvent());
        assertEquals(2, nativeCalls.get());
    }

    private static PaperPredictionProtocol.HitClaim claim(UUID session, UUID target) {
        return new PaperPredictionProtocol.HitClaim(session, 1, 1, 1, target, 2, "Dynamic", 0, 0, 0);
    }
    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> {
            if (method.getName().equals("getUniqueId")) return id;
            throw new AssertionError("Legacy path accessed live player: " + method);
        });
    }
    private static RollbackSession<Integer, String> session() {
        var idle = new RollbackPlayerInput(new RollbackMovementInput(0, 0, false, 0, 0), false, List.of());
        var simulation = new RollbackSimulation<Integer, RollbackPlayerInput, String>() {
            @Override public Integer snapshot() { return 0; }
            @Override public void restore(Integer state) { }
            @Override public RollbackPlayerInput predict(UUID player, RollbackPlayerInput previous) { return previous.predict(); }
            @Override public void step(long tick, Map<UUID, RollbackPlayerInput> inputs, RollbackStep<String> effects) { }
        };
        return new RollbackSession<>(UUID.randomUUID(), 1,
                new RollbackEngine<>(simulation, Map.of(A, idle, B, idle), new RollbackEngine.Limits(3, 1, 10, 50_000_000), 1000),
                List.of(new RollbackSession.Peer(A, new Object(), 10), new RollbackSession.Peer(B, new Object(), 20)));
    }
}
