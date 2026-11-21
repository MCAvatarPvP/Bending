package com.projectkorra.projectkorra.fabric.mixin.client;

import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.data.TrackedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Client-only access to fields whose public setters intentionally reject client worlds.
 */
@Mixin(AreaEffectCloudEntity.class)
public interface AreaEffectCloudEntityAccessor {
    @Accessor("RADIUS")
    static TrackedData<Float> projectkorra$radius() {
        throw new AssertionError();
    }

    @Invoker("clientTick")
    void projectkorra$clientTick();

    @Accessor("potionContentsComponent")
    net.minecraft.component.type.PotionContentsComponent projectkorra$potionContents();

    @Accessor("affectedEntities")
    java.util.Map<net.minecraft.entity.Entity, Integer> projectkorra$affectedEntities();

    @Accessor("reapplicationDelay")
    void projectkorra$setReapplicationDelay(int ticks);
}
