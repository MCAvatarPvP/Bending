package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.InvocationHandlerAdapter;
import org.objenesis.ObjenesisStd;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static net.bytebuddy.matcher.ElementMatchers.*;

/** Typed ability facade whose common Entity methods always use the private body. */
public final class RollbackTypedEntity {
    private RollbackTypedEntity() { }
    private static final String FIELD = "rollback$actor";
    private static final ClassValue<Class<?>> TYPES = new ClassValue<>() {
        @Override protected Class<?> computeValue(Class<?> type) {
            return new ByteBuddy().subclass(type).implement(RollbackEntityBody.View.class, RollbackStateCell.class)
                    .defineField(FIELD, InvocationHandler.class, Visibility.PUBLIC)
                    .method(isVirtual().and(not(isDeclaredBy(Object.class))).and(not(isFinalizer())))
                    .intercept(InvocationHandlerAdapter.toField(FIELD))
                    .make().load(type.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER).getLoaded();
        }
    };
    private static final ClassValue<Map<Method, Optional<Method>>> BASE = new ClassValue<>() {
        @Override protected Map<Method, Optional<Method>> computeValue(Class<?> type) { return new ConcurrentHashMap<>(); }
    };

    public static <T extends Entity> T create(Class<T> type, RollbackEntityBody body, T specialized, RollbackStateCell<?> owner) {
        Objects.requireNonNull(owner); Objects.requireNonNull(specialized);
        if (type == Entity.class || !type.isInstance(specialized)) throw new IllegalArgumentException("Expected specialized actor API");
        return dispatch(type, body, (method, args) -> method.invoke(specialized, args), owner);
    }

    @FunctionalInterface public interface Specialized {
        Object call(Method method, Object[] arguments) throws Throwable;
    }

    public static <T extends Entity> T dispatch(Class<T> type, RollbackEntityBody body, Specialized specialized, RollbackStateCell<?> owner) {
        Objects.requireNonNull(specialized); Objects.requireNonNull(owner);
        var base = new RollbackEntity(body, false);
        Class<?> facade = TYPES.get(type);
        T view = type.cast(new ObjenesisStd().newInstance(facade));
        InvocationHandler handler = (self, method, arguments) -> {
            switch (method.getName()) {
                case "body": return body;
                case "handle": return self;
                case "captureRollbackState", "restoreRollbackState": return null;
                case "rollbackReferences": return List.of(body, owner);
                default: break;
            }
            Optional<Method> common = BASE.get(type).computeIfAbsent(method, candidate -> {
                try { return Optional.of(Entity.class.getMethod(candidate.getName(), candidate.getParameterTypes())); }
                catch (NoSuchMethodException typed) { return Optional.empty(); }
            });
            try {
                if (common.isPresent()) return common.get().invoke(base, arguments);
                return specialized.call(method, arguments);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        };
        try {
            MethodHandles.publicLookup().findSetter(facade, FIELD, InvocationHandler.class).invoke(view, handler);
        } catch (Throwable failure) {
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("Could not construct typed private actor " + type.getName(), failure);
        }
        body.bind(view); return view;
    }
}
