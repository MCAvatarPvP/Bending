package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeMethods;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.component.DeathProtection;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.consume_effects.ClearAllStatusEffectsConsumeEffect;
import net.minecraft.world.item.consume_effects.ConsumeEffect;
import net.minecraft.world.item.consume_effects.PlaySoundConsumeEffect;
import net.minecraft.world.item.consume_effects.RemoveStatusEffectsConsumeEffect;
import org.bukkit.craftbukkit.potion.CraftPotionEffectType;
import org.bukkit.craftbukkit.potion.CraftPotionUtil;
import org.bukkit.potion.PotionEffectType;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.IdentityHashMap;
import java.util.Map;

/** Native item/effect dispatch and Bukkit effect views over captured registry metadata. */
final class PaperRollbackStatusEffects {
    private final Registry<MobEffect> effects;
    private final Map<Holder<MobEffect>, PotionEffectType> bukkitEffects = new IdentityHashMap<>();

    PaperRollbackStatusEffects(RegistryAccess registries) { effects = registries.lookupOrThrow(Registries.MOB_EFFECT); }

    void bind(RollbackNativeMethods methods) {
        try {
            methods.copy(MobEffectInstance.class.getMethod("tickServer", net.minecraft.server.level.ServerLevel.class,
                    net.minecraft.world.entity.LivingEntity.class, Runnable.class));
            // These native effect bodies mutate owned health/food or return a
            // predicate. Their event/damage calls share the combat method routes.
            // Raid/village effects need additional world services and stay unbound.
            for (var type : java.util.Set.of(MobEffect.class, MobEffects.ABSORPTION.value().getClass(),
                    MobEffects.INSTANT_HEALTH.value().getClass(), MobEffects.HUNGER.value().getClass(),
                    MobEffects.POISON.value().getClass(), MobEffects.REGENERATION.value().getClass(),
                    MobEffects.SATURATION.value().getClass(), MobEffects.WITHER.value().getClass())) {
                methods.copy(type.getDeclaredMethod("applyEffectTick", net.minecraft.server.level.ServerLevel.class,
                        net.minecraft.world.entity.LivingEntity.class, int.class));
            }
            methods.copy(DeathProtection.class.getMethod("applyEffects", net.minecraft.world.item.ItemStack.class, net.minecraft.world.entity.LivingEntity.class));
            // Copy the native interface defaults too: their virtual calls must
            // select an audited implementation or reject an unbound override.
            // Random teleport still requires a private teleport/world adapter.
            for (Class<?> type : new Class<?>[]{ConsumeEffect.class, ApplyStatusEffectsConsumeEffect.class,
                    RemoveStatusEffectsConsumeEffect.class, ClearAllStatusEffectsConsumeEffect.class, PlaySoundConsumeEffect.class}) {
                for (var method : type.getDeclaredMethods()) if (method.getName().equals("apply")) methods.copy(method);
            }
            methods.copy(CraftPotionUtil.class.getMethod("toBukkit", MobEffectInstance.class));
            methods.replace(CraftPotionEffectType.class.getMethod("minecraftHolderToBukkit", Holder.class),
                    MethodHandles.lookup().findVirtual(PaperRollbackStatusEffects.class, "bukkitType",
                            MethodType.methodType(PotionEffectType.class, Holder.class)).bindTo(this));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native status effect methods changed", failure); }
    }

    @SuppressWarnings("WrapperReferenceEquality")
    private PotionEffectType bukkitType(Holder<MobEffect> holder) {
        var key = holder.unwrapKey().orElseThrow(() -> new IllegalArgumentException("Unregistered native effect"));
        if (effects.getOrThrow(key) != holder) throw new IllegalArgumentException("Effect belongs to another native registry");
        return bukkitEffects.computeIfAbsent(holder, value -> new CraftPotionEffectType(value) {
            // CraftPotionEffectType's legacy ID lookup otherwise reads the global
            // CraftRegistry. This replica uses the same captured registry as NMS.
            @Override public int getId() { return effects.getId(value.value()) + 1; }
        });
    }
}
