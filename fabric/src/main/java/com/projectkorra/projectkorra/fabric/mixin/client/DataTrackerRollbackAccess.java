package com.projectkorra.projectkorra.fabric.mixin.client;

import net.minecraft.entity.data.DataTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Private bootstrap access to full tracked state without invoking live entity callbacks. */
@Mixin(DataTracker.class)
public interface DataTrackerRollbackAccess {
    @Accessor("entries") DataTracker.Entry<?>[] rollback$entries();
}
