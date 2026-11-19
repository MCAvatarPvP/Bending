package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.entity.*;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.InvocationHandlerAdapter;
import org.objenesis.ObjenesisStd;
import java.lang.reflect.*;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Gives live resumed actors the same common facade type held by their abilities. */
final class PaperRollbackActorView {
    private PaperRollbackActorView() { }
    private static final ClassValue<Class<?>> FACADES = new ClassValue<>() {
        @Override protected Class<?> computeValue(Class<?> contract) {
            return new ByteBuddy().subclass(contract).defineField("actor", InvocationHandler.class, Visibility.PUBLIC)
                    .method(isVirtual().and(not(isFinalizer()))).intercept(InvocationHandlerAdapter.toField("actor"))
                    .make().load(contract.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER).getLoaded();
        }
    };
    static Entity wrap(Class<?> contract, org.bukkit.entity.Entity nativeEntity) {
        var generic = BukkitMC.entity(nativeEntity);
        if (contract.isInstance(generic)) return generic;
        if (!Entity.class.isAssignableFrom(contract) || !contract.getPackageName().equals(Entity.class.getPackageName()))
            throw new IllegalArgumentException("Unknown actor facade");
        var view = (Entity) new ObjenesisStd().newInstance(FACADES.get(contract));
        InvocationHandler handler = (self, method, args) -> {
            try {
                if (method.getDeclaringClass().isInstance(generic)) return method.invoke(generic, args);
                return switch (method.getName()) {
                    case "setDirection" -> { ((org.bukkit.entity.Fireball) nativeEntity).setDirection(BukkitMC.vector(
                            (com.projectkorra.projectkorra.platform.mc.util.Vector) args[0])); yield null; }
                    case "setYield" -> { ((org.bukkit.entity.Fireball) nativeEntity).setYield((float) args[0]); yield null; }
                    case "setIsIncendiary" -> { ((org.bukkit.entity.Fireball) nativeEntity).setIsIncendiary((boolean) args[0]); yield null; }
                    case "setSize" -> { ((org.bukkit.entity.Slime) nativeEntity).setSize((int) args[0]); yield null; }
                    case "setShooter" -> {
                        var shooter = args[0] == null ? null : BukkitMC.entityHandle((Entity) args[0]);
                        ((org.bukkit.entity.Projectile) nativeEntity).setShooter((org.bukkit.projectiles.ProjectileSource) shooter);
                        yield null;
                    }
                    default -> throw new IllegalArgumentException("Unsupported native actor method " + method);
                };
            } catch (InvocationTargetException failure) { throw failure.getCause(); }
        };
        try { view.getClass().getField("actor").set(view, handler); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Could not bind native actor facade", failure); }
        return view;
    }
}
