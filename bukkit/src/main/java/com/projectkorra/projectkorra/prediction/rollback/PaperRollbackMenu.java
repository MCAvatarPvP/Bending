package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeMethods;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.item.ItemStack;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.RegisteredListener;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Native inventory callbacks with the session dispatcher in place of global Bukkit listeners. */
final class PaperRollbackMenu {
    private final PaperRollbackWorldAccess world;
    private final ServerPlayer player;
    private final ContainerListener nativeListener;
    private final ContainerListener listener;
    private final HandlerList handlers = new org.objenesis.ObjenesisStd(false).newInstance(HandlerList.class);
    private final MethodHandle changed, changedWithPrevious, dataChanged;

    PaperRollbackMenu(PaperRollbackWorldAccess world, ServerPlayer player) {
        this.world = world; this.player = player;
        nativeListener = PaperRollbackPrivateAccess.containerListener(player);
        var methods = new RollbackNativeMethods(); world.bindEvents(methods);
        try {
            var type = nativeListener.getClass();
            var changedMethod = type.getDeclaredMethod("slotChanged", AbstractContainerMenu.class, int.class, ItemStack.class);
            var previousMethod = type.getDeclaredMethod("slotChanged", AbstractContainerMenu.class, int.class, ItemStack.class, ItemStack.class);
            var dataMethod = type.getDeclaredMethod("dataChanged", AbstractContainerMenu.class, int.class, int.class);
            methods.copy(changedMethod).copy(previousMethod).copy(dataMethod);
            methods.replace(io.papermc.paper.event.player.PlayerInventorySlotChangeEvent.class.getMethod("getHandlerList"),
                    MethodHandles.constant(HandlerList.class, handlers));
            methods.replace(HandlerList.class.getMethod("getRegisteredListeners"), MethodHandles.lookup()
                    .findVirtual(PaperRollbackMenu.class, "privateListeners", MethodType.methodType(RegisteredListener[].class, HandlerList.class)).bindTo(this));
            var copied = methods.build();
            changed = copied.get(changedMethod).asType(MethodType.methodType(void.class, ContainerListener.class, AbstractContainerMenu.class, int.class, ItemStack.class));
            changedWithPrevious = copied.get(previousMethod).asType(MethodType.methodType(void.class, ContainerListener.class, AbstractContainerMenu.class, int.class, ItemStack.class, ItemStack.class));
            dataChanged = copied.get(dataMethod).asType(MethodType.methodType(void.class, ContainerListener.class, AbstractContainerMenu.class, int.class, int.class));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native inventory listener changed", failure); }
        listener = RollbackNativeQueryShell.create(ContainerListener.class)
                .outputQuery(value -> value.slotChanged(null, 0, null), args -> invoke(changed, args))
                .outputQuery(value -> value.slotChanged(null, 0, null, null), args -> invoke(changedWithPrevious, args))
                .outputQuery(value -> value.dataChanged(null, 0, 0), args -> invoke(dataChanged, args)).instance();
    }

    ContainerListener listener() { return listener; }
    void initialize() {
        requireMenu(player.inventoryMenu);
        player.inventoryMenu.addSlotListener(listener);
        player.inventoryMenu.setSynchronizer(player.containerSynchronizer);
    }
    private void requireMenu(AbstractContainerMenu menu) {
        if (!world.ownsPlayer(player) || player.level() != world.world() || (menu != player.inventoryMenu && menu != player.containerMenu)) {
            throw new IllegalArgumentException("Foreign inventory callback");
        }
    }
    private void invoke(MethodHandle method, Object[] arguments) {
        requireMenu((AbstractContainerMenu) arguments[0]);
        try {
            if (method == changed) changed.invokeExact(nativeListener, (AbstractContainerMenu) arguments[0], (int) arguments[1], (ItemStack) arguments[2]);
            else if (method == changedWithPrevious) changedWithPrevious.invokeExact(nativeListener, (AbstractContainerMenu) arguments[0], (int) arguments[1], (ItemStack) arguments[2], (ItemStack) arguments[3]);
            else dataChanged.invokeExact(nativeListener, (AbstractContainerMenu) arguments[0], (int) arguments[1], (int) arguments[2]);
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Private inventory callback failed", failure); }
    }
    private RegisteredListener[] privateListeners(HandlerList source) {
        if (source != handlers) throw new IllegalArgumentException("Foreign listener registry");
        // The audited native caller reads only length. One private dispatcher is
        // installed; event delivery itself uses the copied callEvent route.
        return new RegisteredListener[1];
    }
}
