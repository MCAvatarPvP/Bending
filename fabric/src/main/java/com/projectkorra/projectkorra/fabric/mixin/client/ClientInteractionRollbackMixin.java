package com.projectkorra.projectkorra.fabric.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackNativeActions;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.component.type.PiercingWeaponComponent;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Capture before native item, block, inventory and entity side effects. Ordinary managers/players take the original path. */
@Mixin(ClientPlayerInteractionManager.class)
abstract class ClientInteractionRollbackMixin {
    @Inject(method = "interactItem", at = @At("HEAD"), cancellable = true)
    private void projectkorra$item(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> callback) {
        if (FabricRollbackNativeActions.packet((ClientPlayerInteractionManager) (Object) this, player,
                () -> new PlayerInteractItemC2SPacket(hand, 0, player.getYaw(), player.getPitch()))) callback.setReturnValue(ActionResult.CONSUME);
    }
    @Inject(method = "interactBlock", at = @At("HEAD"), cancellable = true)
    private void projectkorra$block(ClientPlayerEntity player, Hand hand, BlockHitResult hit, CallbackInfoReturnable<ActionResult> callback) {
        if (FabricRollbackNativeActions.packet((ClientPlayerInteractionManager) (Object) this, player,
                () -> new PlayerInteractBlockC2SPacket(hand, hit, 0))) callback.setReturnValue(ActionResult.CONSUME);
    }
    @Inject(method = "interactEntityAtLocation", at = @At("HEAD"), cancellable = true)
    private void projectkorra$entityAt(PlayerEntity player, Entity target, EntityHitResult hit, Hand hand, CallbackInfoReturnable<ActionResult> callback) {
        if (FabricRollbackNativeActions.packet((ClientPlayerInteractionManager) (Object) this, player,
                () -> PlayerInteractEntityC2SPacket.interactAt(target, player.isSneaking(), hand, hit.getPos().subtract(target.getEntityPos())))) callback.setReturnValue(ActionResult.CONSUME);
    }
    @Inject(method = "interactEntity", at = @At("HEAD"), cancellable = true)
    private void projectkorra$entity(PlayerEntity player, Entity target, Hand hand, CallbackInfoReturnable<ActionResult> callback) {
        if (FabricRollbackNativeActions.packet((ClientPlayerInteractionManager) (Object) this, player,
                () -> PlayerInteractEntityC2SPacket.interact(target, player.isSneaking(), hand))) callback.setReturnValue(ActionResult.CONSUME);
    }
    @Inject(method = "attackEntity", at = @At("HEAD"), cancellable = true)
    private void projectkorra$attack(PlayerEntity player, Entity target, CallbackInfo callback) {
        if (FabricRollbackNativeActions.packet((ClientPlayerInteractionManager) (Object) this, player,
                () -> PlayerInteractEntityC2SPacket.attack(target, player.isSneaking()))) callback.cancel();
    }
    @Inject(method = "stopUsingItem", at = @At("HEAD"), cancellable = true)
    private void projectkorra$release(PlayerEntity player, CallbackInfo callback) {
        if (FabricRollbackNativeActions.packet((ClientPlayerInteractionManager) (Object) this, player,
                () -> new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, Direction.DOWN))) callback.cancel();
    }
    @WrapMethod(method = "attackBlock")
    private boolean projectkorra$attackBlock(BlockPos pos, Direction direction, Operation<Boolean> original) {
        if (FabricRollbackNativeActions.packet((ClientPlayerInteractionManager) (Object) this, null,
                () -> new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, direction))) return false;
        return original.call(pos, direction);
    }
    @WrapMethod(method = "updateBlockBreakingProgress")
    private boolean projectkorra$mining(BlockPos pos, Direction direction, Operation<Boolean> original) {
        if (FabricRollbackNativeActions.unsupported((ClientPlayerInteractionManager) (Object) this, null, "continued block breaking")) return false;
        return original.call(pos, direction);
    }
    @Inject(method = "breakBlock", at = @At("HEAD"), cancellable = true)
    private void projectkorra$break(BlockPos pos, CallbackInfoReturnable<Boolean> callback) {
        if (FabricRollbackNativeActions.unsupported((ClientPlayerInteractionManager) (Object) this, null, "block breaking")) callback.setReturnValue(false);
    }
    @Inject(method = "clickSlot", at = @At("HEAD"), cancellable = true)
    private void projectkorra$slot(int sync, int slot, int button, SlotActionType type, PlayerEntity player, CallbackInfo callback) {
        if (FabricRollbackNativeActions.unsupported((ClientPlayerInteractionManager) (Object) this, player, "inventory click")) callback.cancel();
    }
    @Inject(method = "clickRecipe", at = @At("HEAD"), cancellable = true)
    private void projectkorra$recipe(int sync, NetworkRecipeId recipe, boolean all, CallbackInfo callback) {
        if (FabricRollbackNativeActions.unsupported((ClientPlayerInteractionManager) (Object) this, null, "recipe click")) callback.cancel();
    }
    @Inject(method = "clickButton", at = @At("HEAD"), cancellable = true)
    private void projectkorra$button(int sync, int button, CallbackInfo callback) {
        if (FabricRollbackNativeActions.unsupported((ClientPlayerInteractionManager) (Object) this, null, "container button")) callback.cancel();
    }
    @Inject(method = "clickCreativeStack", at = @At("HEAD"), cancellable = true)
    private void projectkorra$creativeStack(ItemStack stack, int slot, CallbackInfo callback) {
        if (FabricRollbackNativeActions.unsupported((ClientPlayerInteractionManager) (Object) this, null, "creative inventory")) callback.cancel();
    }
    @Inject(method = "dropCreativeStack", at = @At("HEAD"), cancellable = true)
    private void projectkorra$creativeDrop(ItemStack stack, CallbackInfo callback) {
        if (FabricRollbackNativeActions.unsupported((ClientPlayerInteractionManager) (Object) this, null, "creative drop")) callback.cancel();
    }
    @Inject(method = "attackWithPiercingWeapon", at = @At("HEAD"), cancellable = true)
    private void projectkorra$piercing(PiercingWeaponComponent weapon, CallbackInfo callback) {
        if (FabricRollbackNativeActions.unsupported((ClientPlayerInteractionManager) (Object) this, null, "piercing attack")) callback.cancel();
    }
    @Inject(method = "pickItemFromBlock", at = @At("HEAD"), cancellable = true)
    private void projectkorra$pickBlock(BlockPos pos, boolean data, CallbackInfo callback) {
        if (FabricRollbackNativeActions.unsupported((ClientPlayerInteractionManager) (Object) this, null, "pick block")) callback.cancel();
    }
    @Inject(method = "pickItemFromEntity", at = @At("HEAD"), cancellable = true)
    private void projectkorra$pickEntity(Entity entity, boolean data, CallbackInfo callback) {
        if (FabricRollbackNativeActions.unsupported((ClientPlayerInteractionManager) (Object) this, null, "pick entity")) callback.cancel();
    }
    @Inject(method = "slotChangedState", at = @At("HEAD"), cancellable = true)
    private void projectkorra$slotState(int slot, int sync, boolean state, CallbackInfo callback) {
        if (FabricRollbackNativeActions.unsupported((ClientPlayerInteractionManager) (Object) this, null, "container slot state")) callback.cancel();
    }
}
