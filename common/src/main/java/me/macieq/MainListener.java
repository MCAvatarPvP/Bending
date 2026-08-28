package me.macieq;

import com.projectkorra.projectkorra.event.BendingReloadEvent;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.event.EventHandler;
import com.projectkorra.projectkorra.platform.mc.event.EventPriority;

/** Handles Molten lifecycle events; ability input is registered centrally. */
public final class MainListener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBendingReload(final BendingReloadEvent event) {
        Platform.scheduler().runLater(() -> {
            FloorIsLava.reload();
            event.getSender().sendMessage("Molten reloaded!");
        }, 1L);
    }
}
