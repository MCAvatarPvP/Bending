package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.dynamic.scaffold.MethodGraph;
import net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy;
import net.bytebuddy.implementation.InvocationHandlerAdapter;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.objenesis.ObjenesisStd;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Plugin-only interception of Connection.tick's listener call. All other virtual
 * calls retain the original listener and its state. Gameplay packet interception,
 * queued-packet drainage and the world tick gate are separate required ownership
 * components; installing only this component does not freeze a player's gameplay.
 */
public final class PaperRollbackConnectionTickGate {
    private static final String HANDLER = "rollback$liveTickHandler";
    private static final VarHandle LISTENER;
    static {
        try {
            LISTENER = MethodHandles.privateLookupIn(Connection.class, MethodHandles.lookup())
                    .findVarHandle(Connection.class, "packetListener", PacketListener.class);
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private record Layout(Class<?> proxy, Field handler, List<Field> fields, Map<Method, Method> methods) { }
    private static final ClassValue<Layout> LAYOUTS = new ClassValue<>() {
        @Override protected Layout computeValue(Class<?> source) {
            if (!ServerGamePacketListenerImpl.class.isAssignableFrom(source) || Modifier.isFinal(source.getModifiers()))
                throw new IllegalArgumentException("Unsupported live listener class");
            try {
                var fields = new ArrayList<Field>();
                for (Class<?> type = source; type != Object.class; type = type.getSuperclass()) {
                    for (var field : type.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers())) {
                        field.setAccessible(true); fields.add(field);
                    }
                    for (var method : type.getDeclaredMethods()) {
                        int modifiers = method.getModifiers();
                        if (Modifier.isFinal(modifiers) && !Modifier.isStatic(modifiers) && !Modifier.isPrivate(modifiers)
                                && !auditedFinal(method)) throw new IllegalArgumentException("Unroutable final listener method: " + method);
                    }
                }
                var proxy = new ByteBuddy().with(MethodGraph.Compiler.Default.forJVMHierarchy()).ignore(none())
                        .subclass(source, ConstructorStrategy.Default.NO_CONSTRUCTORS)
                        .defineField(HANDLER, InvocationHandler.class, Visibility.PUBLIC)
                        .method(isVirtual().and(not(isFinal())).and(not(isDeclaredBy(Object.class))))
                        .intercept(InvocationHandlerAdapter.toField(HANDLER))
                        .make().load(source.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER).getLoaded();
                return new Layout(proxy, proxy.getField(HANDLER), List.copyOf(fields), new ConcurrentHashMap<>());
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot prepare live listener gate", failure); }
        }
    };
    private PaperRollbackConnectionTickGate() { }

    private static boolean auditedFinal(Method method) {
        // isDisconnected reads the shared player/connection plus processedDisconnect.
        // Both final disconnectAsync overloads immediately delegate to a routed virtual method.
        return (method.getDeclaringClass() == ServerGamePacketListenerImpl.class && method.getName().equals("isDisconnected")
                    && method.getParameterCount() == 0 && method.getReturnType() == boolean.class)
                || (method.getDeclaringClass() == ServerCommonPacketListenerImpl.class && method.getName().equals("disconnectAsync")
                    && method.getReturnType() == void.class);
    }
    public static Lease prepare(ServerPlayer player) {
        boundary(); return new Lease(Objects.requireNonNull(player));
    }
    public static final class Lease {
        private final Thread owner = Thread.currentThread();
        private final ServerPlayer player;
        private final ServerGamePacketListenerImpl original, proxy;
        private final Connection connection;
        private final Layout layout;
        private volatile boolean suspended;
        private boolean released, restoring;
        private Lease(ServerPlayer player) {
            this.player = player;
            original = Objects.requireNonNull(player.connection, "Player listener");
            connection = Objects.requireNonNull(original.connection, "Native connection");
            requireOriginal();
            layout = LAYOUTS.get(original.getClass());
            proxy = (ServerGamePacketListenerImpl) new ObjenesisStd(false).newInstance(layout.proxy());
            try { layout.handler().set(proxy, (InvocationHandler) this::invoke); copyDirectFields(); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot initialize live listener gate", failure); }
        }
        public void acquire() {
            checkThread();
            if (released || restoring) throw new IllegalStateException("Connection tick gate is closed/restoring");
            if (suspended) { requireCurrent(); return; }
            requireOriginal();
            try { copyDirectFields(); }
            catch (IllegalAccessException failure) { throw new IllegalStateException("Cannot refresh listener gate", failure); }
            suspended = true;
            if (!LISTENER.compareAndSet(connection, original, proxy)) {
                suspended = false; throw new IllegalStateException("Connection listener changed during acquisition");
            }
        }
        public void requireCurrent() {
            checkThread();
            if (!suspended || released || player.connection != original || original.player != player
                    || connection.getPacketListener() != proxy)
                throw new IllegalStateException("Connection tick ownership changed");
        }
        public void restoreAndRelease(Runnable restore) {
            checkThread(); Objects.requireNonNull(restore);
            if (restoring) throw new IllegalStateException("Recursive connection cleanup");
            if (released) return;
            if (!suspended) { released = true; return; }
            requireCurrent(); restoring = true;
            try {
                restore.run(); requireCurrent();
                if (!LISTENER.compareAndSet(connection, proxy, original))
                    throw new IllegalStateException("Connection listener changed during cleanup");
                suspended = false; released = true;
            } finally { restoring = false; }
        }
        private Object invoke(Object receiver, Method method, Object[] args) throws Throwable {
            if (method.getName().equals("tick") && method.getParameterCount() == 0 && suspended) {
                requireCurrent();
                try { PaperRollbackConnectionMaintenance.tick(original); return null; }
                finally { proxy.processedDisconnect = original.processedDisconnect; }
            }
            var callable = layout.methods().computeIfAbsent(method, value -> { value.setAccessible(true); return value; });
            try { return callable.invoke(original, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
            finally { proxy.processedDisconnect = original.processedDisconnect; }
        }
        private void requireOriginal() {
            if (player.connection != original || original.player != player || connection.getPacketListener() != original)
                throw new IllegalStateException("Player does not own its original connection listener");
        }
        private void copyDirectFields() throws IllegalAccessException {
            for (var field : layout.fields()) field.set(proxy, field.get(original));
        }
        private void checkThread() {
            boundary(); if (Thread.currentThread() != owner) throw new IllegalStateException("Connection tick gate crossed threads");
        }
    }
    private static void boundary() {
        if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active())
            throw new IllegalStateException("Change connection tick ownership at the live tick boundary");
    }
}
