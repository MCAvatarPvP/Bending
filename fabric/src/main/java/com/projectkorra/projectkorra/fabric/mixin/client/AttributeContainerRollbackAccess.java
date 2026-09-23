package com.projectkorra.projectkorra.fabric.mixin.client;

import net.minecraft.entity.attribute.AttributeContainer;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.registry.entry.RegistryEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Map;

@Mixin(AttributeContainer.class)
public interface AttributeContainerRollbackAccess {
    @Accessor("custom") Map<RegistryEntry<EntityAttribute>, EntityAttributeInstance> rollback$custom();
    @Invoker("updateTrackedStatus") void rollback$updateTrackedStatus(EntityAttributeInstance instance);
}
