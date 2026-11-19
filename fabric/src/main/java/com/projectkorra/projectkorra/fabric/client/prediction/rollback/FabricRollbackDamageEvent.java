package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent.DamageCause;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.entity.player.PlayerEntity;

import java.util.EnumMap;
import java.util.Objects;
import java.util.function.DoubleUnaryOperator;

/** Paper-compatible modifier stage, before native durability, absorption or health changes. */
public final class FabricRollbackDamageEvent {
    public enum Modifier { BASE, INVULNERABILITY_REDUCTION, FREEZING, HARD_HAT, BLOCKING, ARMOR, RESISTANCE, MAGIC, ABSORPTION }
    private final PlayerEntity player;
    private final DamageSource source;
    private final EnumMap<Modifier, Double> values = new EnumMap<>(Modifier.class);
    private final EnumMap<Modifier, DoubleUnaryOperator> functions;
    private boolean cancelled;

    FabricRollbackDamageEvent(PlayerEntity player, DamageSource source, float amount, EnumMap<Modifier, DoubleUnaryOperator> functions) {
        this.player = Objects.requireNonNull(player); this.source = Objects.requireNonNull(source);
        this.functions = new EnumMap<>(functions);
        if (functions.size() != Modifier.values().length) throw new IllegalArgumentException("Incomplete damage modifiers");
        values.put(Modifier.BASE, (double) amount);
        for (var modifier : Modifier.values()) if (modifier != Modifier.BASE) {
            // Paper narrows each initial delta and running amount to float.
            float change = (float) functions.get(modifier).applyAsDouble(amount);
            values.put(modifier, (double) change); amount += change;
        }
    }
    public PlayerEntity player() { return player; }
    public DamageSource source() { return source; }
    /** Common causes exposed by CraftEventFactory for private player and environmental sources. */
    static DamageCause cause(DamageSource source, Entity damager) {
        if (source.isIn(DamageTypeTags.IS_EXPLOSION)) return DamageCause.CUSTOM;
        if (damager == null && source.isOf(DamageTypes.LAVA)) return DamageCause.LAVA;
        if (damager == null && source.isOf(DamageTypes.OUT_OF_WORLD)) return DamageCause.CUSTOM;
        String known = FabricRollbackPlayerCombatData.knownCause(source);
        if (known != null) return switch (known) {
            case "ENTITY_ATTACK" -> DamageCause.ENTITY_ATTACK;
            case "ENTITY_SWEEP_ATTACK" -> DamageCause.ENTITY_SWEEP_ATTACK;
            case "FIRE" -> DamageCause.FIRE;
            case "FIRE_TICK" -> DamageCause.FIRE_TICK;
            case "LAVA" -> DamageCause.LAVA;
            case "FALL" -> DamageCause.FALL;
            case "SUFFOCATION" -> DamageCause.SUFFOCATION;
            case "FLY_INTO_WALL" -> DamageCause.FLY_INTO_WALL;
            default -> DamageCause.CUSTOM;
        };
        if (source.isOf(DamageTypes.FALL)) return DamageCause.FALL;
        if (damager != null) {
            if (source.isOf(DamageTypes.THORNS) || source.isOf(DamageTypes.SONIC_BOOM) || source.isOf(DamageTypes.FALLING_STALACTITE)
                    || source.isOf(DamageTypes.FALLING_BLOCK) || source.isOf(DamageTypes.FALLING_ANVIL) || source.isOf(DamageTypes.LIGHTNING_BOLT)
                    || source.isOf(DamageTypes.DRAGON_BREATH) || source.isOf(DamageTypes.MAGIC)) return DamageCause.CUSTOM;
            return DamageCause.ENTITY_ATTACK;
        }
        if (source.isOf(DamageTypes.IN_FIRE)) return DamageCause.FIRE;
        if (source.isOf(DamageTypes.ON_FIRE)) return DamageCause.FIRE_TICK;
        if (source.isOf(DamageTypes.IN_WALL)) return DamageCause.SUFFOCATION;
        if (source.isOf(DamageTypes.FLY_INTO_WALL)) return DamageCause.FLY_INTO_WALL;
        return DamageCause.CUSTOM;
    }
    public boolean cancelled() { return cancelled; }
    public void cancelled(boolean value) { cancelled = value; }
    public double damage() { return damage(Modifier.BASE); }
    public double damage(Modifier modifier) { return values.get(Objects.requireNonNull(modifier)); }
    public double finalDamage() { double result = 0; for (var modifier : Modifier.values()) result += damage(modifier); return result; }
    public void damage(Modifier modifier, double amount) {
        if (!Double.isFinite(amount)) throw new IllegalArgumentException("Non-finite damage modifier");
        values.put(Objects.requireNonNull(modifier), amount);
    }
    /** Recalculate standard reductions while retaining prior listener adjustments, as Bukkit does. */
    public void damage(double amount) {
        if (!Double.isFinite(amount)) throw new IllegalArgumentException("Non-finite damage");
        double next = amount, previous = damage();
        for (var modifier : Modifier.values()) {
            var function = functions.get(modifier);
            double nextDelta = function.applyAsDouble(next), previousDelta = function.applyAsDouble(previous);
            double old = damage(modifier), adjusted = old - (previousDelta - nextDelta);
            damage(modifier, old > 0 ? Math.max(0, adjusted) : Math.min(0, adjusted));
            next += nextDelta; previous += previousDelta;
        }
        damage(Modifier.BASE, amount);
    }
    /** Live view for existing common handlers; the caller supplies the owned logical entity/cause. */
    public com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent commonEvent(
            com.projectkorra.projectkorra.platform.mc.entity.Entity entity,
            com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent.DamageCause cause) {
        if (!player.getUuid().equals(Objects.requireNonNull(entity).getUniqueId()))
            throw new IllegalArgumentException("Damage view belongs to another player");
        return new com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent() {
            { setEntity(entity); setCause(Objects.requireNonNull(cause)); }
            @Override public double getDamage() { return damage(); }
            @Override public void setDamage(double amount) { damage(amount); }
            @Override public double getDamage(DamageModifier modifier) { return damage(Modifier.valueOf(modifier.name())); }
            @Override public void setDamage(DamageModifier modifier, double amount) { damage(Modifier.valueOf(modifier.name()), amount); }
            @Override public boolean isCancelled() { return cancelled(); }
            @Override public void setCancelled(boolean value) { cancelled(value); }
        };
    }

    float sum(Modifier... modifiers) { float amount = 0; for (var modifier : modifiers) amount += (float) damage(modifier); return amount; }
}
