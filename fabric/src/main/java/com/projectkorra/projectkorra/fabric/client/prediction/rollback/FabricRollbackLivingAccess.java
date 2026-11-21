package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.fabric.mixin.client.LivingEntityRollbackCombatAccess;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackLivingState;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Common combat APIs backed by the private native player and its existing checkpoint. */
final class FabricRollbackLivingAccess {
    // LivingEntity.damage in the supported native version resets timeUntilRegen
    // to 20. Unlike Paper it has no configurable invulnerableDuration field.
    private static final int NATIVE_DAMAGE_COOLDOWN = 20;
    private final FabricRollbackNativePlayerState owner;
    private final FabricRollbackWorldAccess world;
    private final Supplier<PlayerEntity> player;
    private final Registry<EntityAttribute> attributes;
    private final Registry<StatusEffect> effects;

    FabricRollbackLivingAccess(FabricRollbackNativePlayerState owner, FabricRollbackWorldAccess world, Supplier<PlayerEntity> player) {
        this.owner = owner; this.world = world; this.player = player;
        attributes = world.world().getRegistryManager().getOrThrow(RegistryKeys.ATTRIBUTE);
        effects = world.world().getRegistryManager().getOrThrow(RegistryKeys.STATUS_EFFECT);
    }

    RollbackLivingState.Vitals read() {
        var p = player.get();
        return new RollbackLivingState.Vitals(p.getHealth(), p.getAbsorptionAmount(), p.getStandingEyeHeight(), p.getAir(),
                p.getMaxAir(), p.timeUntilRegen, p instanceof FabricRollbackDamagePlayer owned ? owned.rollbackInvulnerableDuration : NATIVE_DAMAGE_COOLDOWN,
                ((LivingEntityRollbackCombatAccess) p).rollback$lastDamageTaken(), false);
    }

    void write(RollbackLivingState.Vitals next) {
        Objects.requireNonNull(next, "vitals");
        var p = player.get(); var current = read();
        if (next.eyeHeight() != current.eyeHeight() || next.maximumAir() != current.maximumAir()
                || next.maximumNoDamageTicks() != current.maximumNoDamageTicks() || next.lastDamage() != current.lastDamage()) {
            throw new IllegalArgumentException("Derived native vitals cannot be replaced");
        }
        boolean health = next.health() != current.health();
        float nativeHealth = (float) next.health();
        if (health && (!(nativeHealth > 0) || nativeHealth > p.getMaxHealth())) {
            throw new IllegalArgumentException("Positive health must fit native maximum; zero requires private death handling");
        }
        if (health) p.setHealth(nativeHealth);
        if (next.absorption() != current.absorption()) p.setAbsorptionAmount((float) next.absorption());
        if (next.remainingAir() != current.remainingAir()) p.setAir(next.remainingAir());
        p.timeUntilRegen = next.noDamageTicks();
        // The common player API's setAI is a no-op, as on Paper.
    }

    Double attributeValue(String name) {
        var p = player.get(); var holder = attributes.getEntry(key(name)).orElse(null);
        return holder != null && p.getAttributes().hasAttribute(holder) ? p.getAttributeValue(holder) : null;
    }
    Map<String, Double> attributes() {
        var p = player.get(); var result = new LinkedHashMap<String, Double>();
        attributes.streamEntries().forEach(holder -> {
            if (p.getAttributes().hasAttribute(holder)) result.put(name(holder.registryKey().getValue()), p.getAttributeValue(holder));
        });
        return Map.copyOf(result);
    }
    void attribute(RollbackLivingState target, String name, double value) {
        requireTarget(target); var p = player.get();
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Attribute value");
        var holder = attributes.getEntry(key(name)).orElseThrow(() -> new IllegalArgumentException("Unknown attribute: " + name));
        var instance = p.getAttributeInstance(holder);
        if (instance == null) throw new IllegalArgumentException("Player lacks attribute: " + name);
        instance.setBaseValue(value);
    }

    PotionEffect potion(PotionEffectType type) {
        var p = player.get(); var holder = effects.getEntry(key(Objects.requireNonNull(type, "type").name())).orElse(null);
        return holder == null ? null : describe(p.getStatusEffect(holder));
    }
    Collection<PotionEffect> potions() { return player.get().getStatusEffects().stream().map(this::describe).toList(); }
    private PotionEffect describe(StatusEffectInstance effect) {
        if (effect == null) return null;
        var id = effect.getEffectType().getKey().orElseThrow().getValue();
        return new PotionEffect(PotionEffectType.valueOf(name(id)), effect.getDuration(), effect.getAmplifier());
    }
    boolean addPotion(RollbackLivingState target, PotionEffect effect, boolean force) {
        requireTarget(target);
        var holder = effects.getEntry(key(effect.getType().name())).orElseThrow(() -> new IllegalArgumentException("Unknown effect"));
        // Rollback's common ability API follows the Paper descriptor defaults.
        // The native merge still decides whether to replace/extend a hidden effect.
        owner.use(p -> p.addStatusEffect(new StatusEffectInstance(holder, effect.getDuration(), effect.getAmplifier(), true, true, true)));
        return true;
    }
    void removePotion(RollbackLivingState target, PotionEffectType type) {
        requireTarget(target);
        var holder = effects.getEntry(key(type.name())).orElseThrow(() -> new IllegalArgumentException("Unknown effect"));
        owner.use(p -> p.removeStatusEffect(holder));
    }
    void damage(RollbackLivingState target, double amount, Entity source) {
        requireTarget(target);
        var sources = world.world().getDamageSources(); var cause = sources.generic();
        if (source != null) {
            var body = RollbackEntityBody.logicalBody(source);
            if (body.world() != target.body().world() || !(body.kinematicsSource() instanceof FabricRollbackNativePlayerState attacker)) {
                throw new IllegalArgumentException("Damage source is not an owned native player view");
            }
            var nativeAttacker = attacker.ownedPlayer();
            if (!world.ownsPlayer(nativeAttacker)) throw new IllegalArgumentException("Damage source belongs to another native world");
            cause = sources.playerAttack(nativeAttacker);
        }
        owner.damage(cause, (float) amount);
    }
    private void requireTarget(RollbackLivingState target) {
        player.get();
        if (target.body().kinematicsSource() != owner) throw new IllegalArgumentException("Foreign logical combat target");
    }
    private static Identifier key(String name) { return Identifier.of(Objects.requireNonNull(name, "name").toLowerCase(Locale.ROOT)); }
    private static String name(Identifier id) { return id.getNamespace().equals("minecraft") ? id.getPath().toUpperCase(Locale.ROOT) : id.toString(); }
}
