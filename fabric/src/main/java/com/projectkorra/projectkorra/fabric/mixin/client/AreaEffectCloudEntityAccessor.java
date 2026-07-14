package com.projectkorra.projectkorra.fabric.mixin.client;

import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.data.TrackedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Client-only access to fields whose public setters intentionally reject client worlds.
 */
@Mixin(AreaEffectCloudEntity.class)
public interface AreaEffectCloudEntityAccessor {
    @Accessor("RADIUS")
    static TrackedData<Float> projectkorra$radius() {
        throw new AssertionError();
    }

    @Accessor("reapplicationDelay")
    void projectkorra$setReapplicationDelay(int ticks);
}
