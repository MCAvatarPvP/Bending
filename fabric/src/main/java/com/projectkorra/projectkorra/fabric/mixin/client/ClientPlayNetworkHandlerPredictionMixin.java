package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionSyncS2CPacket;
import net.minecraft.network.packet.s2c.play.ExperienceBarUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerAbilitiesS2CPacket;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/** Feeds vanilla authority into the prediction ledgers before it is applied. */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerPredictionMixin {
    @Shadow private ClientWorld world;

    @Inject(method = "onBlockUpdate", at = @At("HEAD"), cancellable = true)
    private void projectkorra$authoritativeBlock(BlockUpdateS2CPacket packet, CallbackInfo ci) {
        if (!MinecraftClient.getInstance().isOnThread()) return;
        if (ExactPredictionRuntime.authoritativeBlock(world, packet.getPos(), packet.getState())) ci.cancel();
    }

    @Inject(method = "onChunkDeltaUpdate", at = @At("HEAD"), cancellable = true)
    private void projectkorra$authoritativeBlockBatch(ChunkDeltaUpdateS2CPacket packet, CallbackInfo ci) {
        if (!MinecraftClient.getInstance().isOnThread()) return;
        List<BlockPos> positions = new ArrayList<>();
        List<BlockState> states = new ArrayList<>();
        packet.visitUpdates((pos, state) -> {
            positions.add(pos.toImmutable());
            states.add(state);
        });
        if (ExactPredictionRuntime.authoritativeBlockBatch(world, positions, states)) ci.cancel();
    }

    @Inject(method = "onChunkDeltaUpdate", at = @At("TAIL"))
    private void projectkorra$acceptAuthoritativeBlockBatch(ChunkDeltaUpdateS2CPacket packet, CallbackInfo ci) {
        if (!MinecraftClient.getInstance().isOnThread()) return;
        List<BlockPos> positions = new ArrayList<>();
        List<BlockState> states = new ArrayList<>();
        packet.visitUpdates((pos, state) -> {
            positions.add(pos.toImmutable());
            states.add(state);
        });
        ExactPredictionRuntime.acceptAuthoritativeBlockBatch(world, positions, states);
    }

    @Inject(method = "onChunkData", at = @At("TAIL"))
    private void projectkorra$acceptAuthoritativeChunk(ChunkDataS2CPacket packet, CallbackInfo ci) {
        if (!MinecraftClient.getInstance().isOnThread()) return;
        ExactPredictionRuntime.acceptAuthoritativeChunk(world, packet.getChunkX(), packet.getChunkZ());
    }

    @Inject(method = "onEntityVelocityUpdate", at = @At("HEAD"), cancellable = true)
    private void projectkorra$reconcileVelocity(EntityVelocityUpdateS2CPacket packet, CallbackInfo ci) {
        if (!MinecraftClient.getInstance().isOnThread()) return;
        if (ExactPredictionRuntime.hasEntityAlias(packet.getEntityId())) { ci.cancel(); return; }
        if (!ExactPredictionRuntime.tracksVelocityEntity(packet.getEntityId())) return;
        if (ExactPredictionRuntime.authoritativeVelocity(packet.getEntityId(), packet.getVelocity())) ci.cancel();
    }

    @Inject(method = "onPlayerAbilities", at = @At("HEAD"), cancellable = true)
    private void projectkorra$suppressPredictedAbilities(PlayerAbilitiesS2CPacket packet, CallbackInfo ci) {
        if (MinecraftClient.getInstance().isOnThread() && ExactPredictionRuntime.suppressAuthoritativeAbilityState(packet)) {
            ci.cancel();
        }
    }

    @Inject(method = "onExperienceBarUpdate", at = @At("HEAD"), cancellable = true)
    private void projectkorra$suppressPredictedExperience(ExperienceBarUpdateS2CPacket packet, CallbackInfo ci) {
        if (MinecraftClient.getInstance().isOnThread() && ExactPredictionRuntime.suppressAuthoritativeExperience(packet)) {
            ci.cancel();
        }
    }

    @Inject(method = "onUpdateSelectedSlot", at = @At("HEAD"), cancellable = true)
    private void projectkorra$suppressPredictedSelectedSlot(UpdateSelectedSlotS2CPacket packet, CallbackInfo ci) {
        if (MinecraftClient.getInstance().isOnThread() && ExactPredictionRuntime.suppressAuthoritativeSelectedSlot(packet)) {
            ci.cancel();
        }
    }

    @Inject(method = "onEntityPosition", at = @At("HEAD"), cancellable = true)
    private void projectkorra$keepPredictedPosition(EntityPositionS2CPacket packet, CallbackInfo ci) {
        if (MinecraftClient.getInstance().isOnThread() && ExactPredictionRuntime.hasEntityAlias(packet.entityId())) ci.cancel();
    }

    @Inject(method = "onEntityPositionSync", at = @At("HEAD"), cancellable = true)
    private void projectkorra$keepPredictedPositionSync(EntityPositionSyncS2CPacket packet, CallbackInfo ci) {
        if (MinecraftClient.getInstance().isOnThread() && ExactPredictionRuntime.hasEntityAlias(packet.id())) ci.cancel();
    }

    @Inject(method = "onEntitySpawn", at = @At("HEAD"), cancellable = true)
    private void projectkorra$reconcileSpawn(EntitySpawnS2CPacket packet, CallbackInfo ci) {
        if (!MinecraftClient.getInstance().isOnThread()) return;
        if (ExactPredictionRuntime.reconcileSpawn(packet)) ci.cancel();
    }

    @Inject(method = "onEntityTrackerUpdate", at = @At("HEAD"), cancellable = true)
    private void projectkorra$suppressAliasedEntityData(EntityTrackerUpdateS2CPacket packet, CallbackInfo ci) {
        if (MinecraftClient.getInstance().isOnThread()
                && ExactPredictionRuntime.suppressAuthoritativeEntityData(packet.id())) ci.cancel();
    }

    @Inject(method = "onBlockBreakingProgress", at = @At("HEAD"), cancellable = true)
    private void projectkorra$suppressPredictedBreakAnimation(BlockBreakingProgressS2CPacket packet, CallbackInfo ci) {
        if (MinecraftClient.getInstance().isOnThread()
                && ExactPredictionRuntime.suppressAuthoritativeBreakAnimation(world, packet.getPos())) ci.cancel();
    }
}
