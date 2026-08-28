package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.ElytraSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** AirGlider supplies its own wind audio, so vanilla must not start an Elytra loop. */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerAirGliderSoundMixin {
    @Redirect(method = "onTrackedDataSet", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/sound/SoundManager;play(Lnet/minecraft/client/sound/SoundInstance;)Lnet/minecraft/client/sound/SoundSystem$PlayResult;"))
    private SoundSystem.PlayResult projectkorra$suppressElytraSound(
            final SoundManager manager, final SoundInstance sound) {
        if (sound instanceof ElytraSoundInstance
                && ExactPredictionRuntime.suppressVanillaAirGliderEffects(
                (ClientPlayerEntity) (Object) this)) {
            return SoundSystem.PlayResult.NOT_STARTED;
        }
        return manager.play(sound);
    }
}
