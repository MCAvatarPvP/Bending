package com.projectkorra.projectkorra.prediction.rollback;

import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.protocol.Packet;

import java.lang.reflect.*;
import java.util.*;

/** Native queued-packet handoff, after the connection's network-loop barrier. */
final class PaperRollbackQueuedPackets {
    private static final Field QUEUE;
    private static final Method LISTENER, PACKET, HANDLE;
    private static final Constructor<?> ENTRY;
    static {
        try {
            QUEUE = PacketProcessor.class.getDeclaredField("packetsToBeHandled");
            QUEUE.setAccessible(true);
            Class<?> entry = Class.forName("net.minecraft.network.PacketProcessor$ListenerAndPacket");
            LISTENER = entry.getDeclaredMethod("listener");
            PACKET = entry.getDeclaredMethod("packet");
            HANDLE = entry.getDeclaredMethod("handle");
            ENTRY = entry.getDeclaredConstructor(PacketListener.class, Packet.class);
            LISTENER.setAccessible(true); PACKET.setAccessible(true); HANDLE.setAccessible(true); ENTRY.setAccessible(true);
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private PaperRollbackQueuedPackets() { }

    static int drain(PacketProcessor processor, PacketListener original, PacketListener replacement) {
        if (!processor.isSameThread() || RollbackClock.active() || RollbackDomain.active())
            throw new IllegalStateException("Drain packets at the live processor boundary");
        try {
            @SuppressWarnings("unchecked") var queue = (Queue<Object>) QUEUE.get(processor);
            var pending = new ArrayList<Object>();
            // No source producer may still be running: the caller first awaits the
            // connection event-loop barrier after replacing its listener.
            for (Object entry : queue) if (LISTENER.invoke(entry) == original) pending.add(entry);
            var dispatch = new ArrayList<Object>();
            for (Object entry : pending) {
                Packet<?> packet = (Packet<?>) PACKET.invoke(entry);
                // Drop even if an earlier command released the gate. These inputs
                // predate the handoff and must never resume as fresh live movement.
                if (PaperRollbackConnectionTickGate.packetDisposition(packet)
                        == PaperRollbackConnectionTickGate.PacketDisposition.DROP) continue;
                dispatch.add(ENTRY.newInstance(replacement, packet));
            }
            // Remove the entire source batch before callbacks. A teardown/error in
            // one callback must not leave later stale movement behind for vanilla.
            for (Object entry : pending)
                if (!queue.remove(entry)) throw new IllegalStateException("Concurrent native packet consumer");
            for (Object entry : dispatch) HANDLE.invoke(entry);
            return pending.size();
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Native queued packet failed", cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot hand off native queued packets", failure);
        }
    }
}
