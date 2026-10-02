package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;

import java.util.Objects;

/**
 * Makes ordinary new ItemStack(...) calls inside the existing ability code use the
 * session's native item implementation. The runtime must scope initialization, input,
 * progress and scheduled callbacks; construction outside a scope remains unchanged.
 */
public final class RollbackItemScope implements AutoCloseable {
    private static final ThreadLocal<RollbackItemScope> CURRENT = new ThreadLocal<>();
    private final Thread thread = Thread.currentThread();
    private final RollbackItemScope previous;
    private final RollbackNativeItems<?> items;
    private boolean closed;

    private RollbackItemScope(RollbackNativeItems<?> items) {
        this.items = Objects.requireNonNull(items, "items");
        previous = CURRENT.get();
        CURRENT.set(this);
    }
    public static RollbackItemScope using(RollbackNativeItems<?> items) { return new RollbackItemScope(items); }

    /** Constructor hook; a native implementation's subclass must not call it recursively. */
    public static ItemStack createIfActive(Material type, int count) {
        RollbackItemScope scope = CURRENT.get();
        return scope == null ? null : scope.items.create(type == null ? Material.AIR : type, count);
    }
    /** Existing plain items also use native comparison while an ability is being replayed. */
    public static Boolean similarIfActive(ItemStack first, ItemStack second) {
        RollbackItemScope scope = CURRENT.get();
        return scope == null ? null : scope.items.similar(first, second);
    }
    @Override public void close() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Item scope crossed threads");
        if (closed || CURRENT.get() != this) throw new IllegalStateException("Item scopes must close once in reverse order");
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        closed = true;
    }
}
