package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.util.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.TickThrottler;
import org.bukkit.event.player.PlayerKickEvent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Paper's paused connection maintenance, without its private tickPlayer/doTick call. */
final class PaperRollbackConnectionMaintenance {
    private static final PaperRollbackPlayerFields.Field<Integer> ACK =
            new PaperRollbackPlayerFields.Field<>(ServerGamePacketListenerImpl.class, "ackBlockChangesUpTo", int.class);
    private static final List<PaperRollbackPlayerFields.Field<TickThrottler>> THROTTLERS =
            List.of("chatSpamThrottler", "dropSpamThrottler", "tabSpamThrottler", "recipeSpamPackets").stream()
                    .map(name -> new PaperRollbackPlayerFields.Field<>(ServerGamePacketListenerImpl.class, name, TickThrottler.class)).toList();
    private static final MethodHandle KEEPALIVE;
    static {
        try {
            KEEPALIVE = MethodHandles.privateLookupIn(ServerCommonPacketListenerImpl.class, MethodHandles.lookup())
                    .findVirtual(ServerCommonPacketListenerImpl.class, "keepConnectionAlive", MethodType.methodType(void.class));
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private PaperRollbackConnectionMaintenance() { }

    static void tick(ServerGamePacketListenerImpl listener) {
        if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active())
            throw new IllegalStateException("Maintain a suspended connection on the live server tick thread");
        if (listener.isDisconnected()) return;
        int ack = ACK.get(listener);
        if (ack > -1) {
            listener.send(new ClientboundBlockChangedAckPacket(ack));
            ACK.set(listener, -1);
        }
        try { KEEPALIVE.invokeExact((ServerCommonPacketListenerImpl) listener); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Native connection maintenance failed", failure); }
        for (var field : THROTTLERS) field.get(listener).tick();
        var player = listener.player;
        if (player.getLastActionTime() > 0L) {
            var server = player.level().getServer();
            if (server.playerIdleTimeout() > 0
                    && Util.getMillis() - player.getLastActionTime() > TimeUnit.MINUTES.toMillis(server.playerIdleTimeout())
                    && !player.wonGame)
                listener.disconnect(Component.translatable("multiplayer.disconnect.idling"), PlayerKickEvent.Cause.IDLING);
        }
    }
}
