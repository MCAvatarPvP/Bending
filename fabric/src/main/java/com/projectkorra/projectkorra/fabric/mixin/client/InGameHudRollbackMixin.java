package com.projectkorra.projectkorra.fabric.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackPlayerRenderer;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.entry.RegistryEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Read corrected replica vitals only at the HUD boundary; native player state is untouched. */
@Mixin(InGameHud.class)
public abstract class InGameHudRollbackMixin {
    @WrapOperation(method = "renderStatusBars", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;getHealth()F"))
    private float projectkorra$health(PlayerEntity player, Operation<Float> original) {
        var view = FabricRollbackPlayerRenderer.health(player); return view == null ? original.call(player) : view.value();
    }
    @WrapOperation(method = "renderStatusBars", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;getAttributeValue(Lnet/minecraft/registry/entry/RegistryEntry;)D"))
    private double projectkorra$maximum(PlayerEntity player, RegistryEntry<EntityAttribute> attribute, Operation<Double> original) {
        var view = FabricRollbackPlayerRenderer.health(player);
        return view == null || !attribute.equals(EntityAttributes.MAX_HEALTH) ? original.call(player, attribute) : view.maximum();
    }
    @WrapOperation(method = "renderStatusBars", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;getAbsorptionAmount()F"))
    private float projectkorra$absorption(PlayerEntity player, Operation<Float> original) {
        var view = FabricRollbackPlayerRenderer.health(player); return view == null ? original.call(player) : view.absorption();
    }
    @WrapOperation(method = "renderArmor", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;getArmor()I"))
    private static int projectkorra$armor(PlayerEntity player, Operation<Integer> original) {
        var view = FabricRollbackPlayerRenderer.health(player); return view == null ? original.call(player) : view.armor();
    }
    @WrapOperation(method = "renderStatusBars", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/player/PlayerEntity;timeUntilRegen:I"))
    private int projectkorra$regenerationDelay(PlayerEntity player, Operation<Integer> original) {
        var view = FabricRollbackPlayerRenderer.health(player); return view == null ? original.call(player) : view.regenerationDelay();
    }
    @WrapOperation(method = "renderStatusBars", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;hasStatusEffect(Lnet/minecraft/registry/entry/RegistryEntry;)Z"))
    private boolean projectkorra$regenerating(PlayerEntity player, RegistryEntry<StatusEffect> effect, Operation<Boolean> original) {
        var view = FabricRollbackPlayerRenderer.health(player);
        return view == null || !effect.equals(StatusEffects.REGENERATION) ? original.call(player, effect) : view.regenerating();
    }
}
