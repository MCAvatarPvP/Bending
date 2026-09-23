package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackLivingState;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.player.Player;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Logical combat APIs over an owned native replica; no second health/effect store. */
final class PaperRollbackLivingAccess {
    private final PaperRollbackNativePlayerState owner;
    private final PaperRollbackWorldAccess world;
    private final Supplier<Player> player;
    private final Registry<Attribute> attributes;
    private final Registry<MobEffect> effects;

    PaperRollbackLivingAccess(PaperRollbackNativePlayerState owner, PaperRollbackWorldAccess world, Supplier<Player> player) {
        this.owner = owner; this.world = world; this.player = player;
        attributes = world.world().registryAccess().lookupOrThrow(Registries.ATTRIBUTE);
        effects = world.world().registryAccess().lookupOrThrow(Registries.MOB_EFFECT);
    }

    RollbackLivingState.Vitals read() {
        var p = player.get();
        return new RollbackLivingState.Vitals(p.getHealth(), p.getAbsorptionAmount(), p.getEyeHeight(), p.getAirSupply(),
                p.maxAirTicks, p.invulnerableTime, p.invulnerableDuration, p.lastHurt, false);
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
        if (next.remainingAir() != current.remainingAir()) world.air(p, next.remainingAir());
        p.invulnerableTime = next.noDamageTicks();
        // CraftLivingEntity.setAI only changes Mob instances, never players.
    }

    Double attributeValue(String name) {
        var p = player.get(); var holder = attributes.get(key(name)).orElse(null);
        return holder != null && p.getAttributes().hasAttribute(holder) ? p.getAttributeValue(holder) : null;
    }

    Map<String, Double> attributes() {
        var p = player.get(); var result = new LinkedHashMap<String, Double>();
        attributes.listElements().forEach(holder -> {
            if (p.getAttributes().hasAttribute(holder)) result.put(name(holder.key().identifier()), p.getAttributeValue(holder));
        });
        return Map.copyOf(result);
    }

    void attribute(RollbackLivingState target, String name, double value) {
        requireTarget(target); var p = player.get();
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Attribute value");
        var holder = attributes.get(key(name)).orElseThrow(() -> new IllegalArgumentException("Unknown attribute: " + name));
        var instance = p.getAttribute(holder);
        if (instance == null) throw new IllegalArgumentException("Player lacks attribute: " + name);
        instance.setBaseValue(value);
    }

    PotionEffect potion(PotionEffectType type) {
        var p = player.get(); var holder = effects.get(key(Objects.requireNonNull(type, "type").name())).orElse(null);
        return holder == null ? null : describe(p.getEffect(holder));
    }
    Collection<PotionEffect> potions() { return player.get().getActiveEffects().stream().map(this::describe).toList(); }

    private PotionEffect describe(MobEffectInstance effect) {
        if (effect == null) return null;
        var id = effect.getEffect().unwrapKey().orElseThrow().identifier();
        return new PotionEffect(PotionEffectType.valueOf(name(id)), effect.getDuration(), effect.getAmplifier());
    }

    boolean addPotion(RollbackLivingState target, PotionEffect effect, boolean force) {
        requireTarget(target);
        Holder<MobEffect> holder = effects.get(key(effect.getType().name())).orElseThrow(() -> new IllegalArgumentException("Unknown effect"));
        // Paper's Bukkit API ignores force, uses native merging and returns true
        // even if a listener cancels. Its PLUGIN cause and ordering are preserved.
        // BukkitMC creates the three-argument Bukkit descriptor, whose defaults
        // are ambient/particles/icon=true (native's short constructor differs).
        owner.use(value -> { world.potion((Player) value,
                new MobEffectInstance(holder, effect.getDuration(), effect.getAmplifier(), true, true, true)); return null; });
        return true;
    }
    void removePotion(RollbackLivingState target, PotionEffectType type) {
        requireTarget(target);
        Holder<MobEffect> holder = effects.get(key(type.name())).orElseThrow(() -> new IllegalArgumentException("Unknown effect"));
        owner.use(value -> { world.removePotion((Player) value, holder); return null; });
    }

    void damage(RollbackLivingState target, double amount, Entity source) {
        requireTarget(target);
        var sources = world.world().damageSources();
        var cause = sources.generic();
        if (source != null) {
            var body = RollbackEntityBody.logicalBody(source);
            if (body.world() != target.body().world() || !(body.kinematicsSource() instanceof PaperRollbackNativePlayerState attacker)) {
                throw new IllegalArgumentException("Damage source is not an owned native player view");
            }
            Player nativeAttacker = attacker.ownedPlayer();
            if (!world.ownsPlayer(nativeAttacker)) throw new IllegalArgumentException("Damage source belongs to another native world");
            cause = sources.playerAttack(nativeAttacker);
        }
        owner.damage(cause, (float) amount);
    }

    private void requireTarget(RollbackLivingState target) {
        player.get();
        if (target.body().kinematicsSource() != owner) throw new IllegalArgumentException("Foreign logical combat target");
    }
    private static Identifier key(String name) { return Identifier.parse(Objects.requireNonNull(name, "name").toLowerCase(Locale.ROOT)); }
    private static String name(Identifier id) { return id.getNamespace().equals("minecraft") ? id.getPath().toUpperCase(Locale.ROOT) : id.toString(); }
}
