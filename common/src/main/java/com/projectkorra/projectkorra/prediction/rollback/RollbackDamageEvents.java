package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.listener.CommonDamageHandler;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent.DamageCause;

/** ProjectKorra's shared damage listener stages, followed by the loader's Neptune round decision. */
public final class RollbackDamageEvents {
    private RollbackDamageEvents() { }

    @SuppressWarnings("WrapperReferenceEquality") // The active duel owns these exact logical identities.
    public static void dispatch(EntityDamageEvent view, Entity directDamager, Block damagingBlock) {
        if (!RollbackDomain.active() || !RollbackClock.active() || !(Platform.events() instanceof RollbackEventBus events)) {
            throw new IllegalStateException("Bending damage requires the private combat tick and event bus");
        }
        if (RollbackControlEvents.player(view.getEntity().getUniqueId()) != view.getEntity()
                || directDamager != null && RollbackControlEvents.player(directDamager.getUniqueId()) != directDamager
                || damagingBlock != null && damagingBlock.getWorld() != view.getEntity().getWorld()) {
            throw new IllegalArgumentException("Damage references a foreign combatant or terrain");
        }
        if (view.isCancelled()) return;
        if (directDamager == null) {
            CommonDamageHandler.byBlock(view, damagingBlock);
            if (!view.isCancelled()) CommonDamageHandler.forwardEnvironment(view, damagingBlock, events);
        } else {
            CommonDamageHandler.forwardByEntity(view, directDamager, events);
        }
        if (!view.isCancelled()) CommonDamageHandler.environment(view);
        if (view.getCause() == DamageCause.SUFFOCATION) view.setCancelled(true);
        if (!view.isCancelled()) CommonDamageHandler.player(view);
        if (!view.isCancelled()) CommonDamageHandler.fall(view);
        if (directDamager != null && !view.isCancelled()) CommonDamageHandler.byEntity(view, directDamager);
    }
}
