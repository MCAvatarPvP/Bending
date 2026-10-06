package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.mojang.authlib.GameProfile;
import com.projectkorra.projectkorra.fabric.mixin.client.LivingEntityRollbackCombatAccess;
import com.projectkorra.projectkorra.fabric.mixin.client.LivingEntityRollbackHistoryAccess;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BlocksAttacksComponent;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.DamageUtil;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.registry.tag.EntityTypeTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.stat.Stats;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;

import java.util.EnumMap;
import java.util.function.DoubleUnaryOperator;
import static com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackDamageEvent.Modifier.*;

/** Native player damage with Paper's cancellable event boundary, restricted to owned match replicas. */
abstract class FabricRollbackDamagePlayer extends PlayerEntity {
    int rollbackInvulnerableDuration = 20;
    protected FabricRollbackDamagePlayer(World world, GameProfile profile) { super(world, profile); }

    @Override public boolean damage(ServerWorld world, DamageSource source, float amount) {
        var owner = FabricRollbackGliding.owner(this);
        if (!owner.hasRound()) return super.damage(world, source, amount);
        owner.requireDamageSource(source);
        if (world != getEntityWorld()) throw new IllegalArgumentException("Foreign damage world");
        if (!Float.isFinite(amount)) throw new IllegalArgumentException("Non-finite native damage");
        if (isInvulnerableTo(world, source) || (getAbilities().invulnerable && !source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY))) return false;
        despawnCounter = 0;
        if (isRemoved() || isDead()) return false;
        if (source.isScaledWithDifficulty()) {
            if (world.getDifficulty() == Difficulty.PEACEFUL) return false;
            if (world.getDifficulty() == Difficulty.EASY) amount = Math.min(amount / 2F + 1F, amount);
            if (world.getDifficulty() == Difficulty.HARD) amount = amount * 3F / 2F;
        }
        if (source.isIn(DamageTypeTags.IS_FIRE) && hasStatusEffect(StatusEffects.FIRE_RESISTANCE)) return false;
        if (isSleeping()) wakeUp();
        amount = Math.max(0, amount);
        float original = amount;
        ItemStack used = getActiveItem();
        boolean blocked = blocked(source, amount) > 0;
        if (!Float.isFinite(amount)) amount = Float.MAX_VALUE;
        var access = (LivingEntityRollbackCombatAccess) this;
        boolean fresh = !(timeUntilRegen > rollbackInvulnerableDuration / 2F && !source.isIn(DamageTypeTags.BYPASSES_COOLDOWN));
        float previous = fresh ? 0 : access.rollback$lastDamageTaken();
        if (!fresh && amount <= previous) return false;
        var event = event(world, source, amount, previous);
        owner.damageEvent(event);
        amount = event.sum(BASE, BLOCKING, FREEZING, HARD_HAT);
        if (!isInvulnerableTo(world, source) && (event.cancelled() || !applyEvent(owner, world, source, event))) return false;
        if (event.damage() == 0 && original == 0) return false;
        access.rollback$lastDamageTaken(amount);
        if (fresh) { timeUntilRegen = rollbackInvulnerableDuration; maxHurtTime = hurtTime = 10; }
        becomeAngry(source); setAttackingPlayer(source);
        if (fresh) {
            BlocksAttacksComponent component = used.get(DataComponentTypes.BLOCKS_ATTACKS);
            if (blocked && component != null) component.playBlockSound(world, this);
            else world.sendEntityDamage(this, source);
            if (!source.isIn(DamageTypeTags.NO_IMPACT) && !blocked) scheduleVelocityUpdate();
            if (!source.isIn(DamageTypeTags.NO_KNOCKBACK)) {
                double x = 0, z = 0;
                if (source.getSource() instanceof ProjectileEntity projectile) {
                    var direction = projectile.getKnockback(this, source); x = -direction.leftDouble(); z = -direction.rightDouble();
                } else if (source.getPosition() != null) {
                    x = source.getPosition().x - getX(); z = source.getPosition().z - getZ();
                }
                if (Math.abs(x) > 200) x = random.nextDouble() - random.nextDouble();
                if (Math.abs(z) > 200) z = random.nextDouble() - random.nextDouble();
                owner.damagePolicy().knockback(this, source, 0.4F, x, z);
                if (!blocked) tiltScreen(x, z);
            }
        }
        if (isDead()) {
            if (!access.rollback$tryUseDeathProtector(source)) owner.damagePolicy().death(this, source);
        } else if (fresh) { playHurtSound(source); access.rollback$playThornsSound(source); }
        if (!blocked) {
            ((LivingEntityRollbackHistoryAccess) this).rollback$lastDamage(source);
            access.rollback$lastDamageTime(world.getTime());
            for (var effect : getStatusEffects()) effect.onEntityDamage(world, this, source, amount);
            dropShoulderEntities();
        }
        return !blocked;
    }

    private FabricRollbackDamageEvent event(ServerWorld world, DamageSource source, float amount, float previous) {
        var functions = new EnumMap<FabricRollbackDamageEvent.Modifier, DoubleUnaryOperator>(FabricRollbackDamageEvent.Modifier.class);
        functions.put(BASE, value -> -0D);
        functions.put(INVULNERABILITY_REDUCTION, value -> previous == 0 || (float) value < previous ? 0D : -previous);
        functions.put(FREEZING, value -> source.isIn(DamageTypeTags.IS_FREEZING) && getType().isIn(EntityTypeTags.FREEZE_HURTS_EXTRA_TYPES) ? -(value - value * 5F) : -0D);
        functions.put(HARD_HAT, value -> source.isIn(DamageTypeTags.DAMAGES_HELMET) && !getEquippedStack(EquipmentSlot.HEAD).isEmpty() ? -(value - value * .75F) : -0D);
        functions.put(BLOCKING, value -> -(double) blocked(source, (float) value));
        functions.put(ARMOR, value -> -(value - (source.isIn(DamageTypeTags.BYPASSES_ARMOR) ? (float) value
                : DamageUtil.getDamageLeft(this, (float) value, source, getArmor(), (float) getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS)))));
        functions.put(RESISTANCE, value -> {
            if (source.isIn(DamageTypeTags.BYPASSES_EFFECTS) || !hasStatusEffect(StatusEffects.RESISTANCE) || source.isIn(DamageTypeTags.BYPASSES_RESISTANCE)) return -0D;
            int factor = 25 - (getStatusEffect(StatusEffects.RESISTANCE).getAmplifier() + 1) * 5;
            return -(value - Math.max((float) value * factor / 25F, 0F));
        });
        functions.put(MAGIC, value -> {
            float damage = (float) value;
            if (!source.isIn(DamageTypeTags.BYPASSES_EFFECTS)) {
                if (damage <= 0) damage = 0;
                else if (!source.isIn(DamageTypeTags.BYPASSES_ENCHANTMENTS)) {
                    float protection = EnchantmentHelper.getProtectionAmount(world, this, source);
                    if (protection > 0) damage = DamageUtil.getInflictedDamage(damage, protection);
                }
            }
            return -(value - damage);
        });
        functions.put(ABSORPTION, value -> -Math.max(value - Math.max(value - getAbsorptionAmount(), 0F), 0F));
        return new FabricRollbackDamageEvent(this, source, amount, functions);
    }

    /** Pure native component calculation: shield wear and hit effects belong after cancellation. */
    private float blocked(DamageSource source, float amount) {
        if (amount <= 0) return 0;
        ItemStack item = getBlockingItem();
        if (item == null) return 0;
        var component = item.get(DataComponentTypes.BLOCKS_ATTACKS);
        if (component == null || component.bypassedBy().map(source::isIn).orElse(false)
                || source.getSource() instanceof PersistentProjectileEntity projectile && projectile.getPierceLevel() > 0) return 0;
        double angle = (float) Math.PI;
        if (source.getPosition() != null) {
            Vec3d delta = source.getPosition().subtract(getEntityPos());
            angle = Math.acos(new Vec3d(delta.x, 0, delta.z).normalize().dotProduct(getRotationVector(0F, getHeadYaw())));
        }
        return component.getDamageReductionAmount(source, amount, angle);
    }

    private void shieldHit(FabricRollbackWorldAccess owner, ServerWorld world, DamageSource source, LivingEntity attacker) {
        owner.damagePolicy().knockback(this, source, .5, getX() - attacker.getX(), getZ() - attacker.getZ());
        ItemStack shield = getBlockingItem();
        var component = shield == null ? null : shield.get(DataComponentTypes.BLOCKS_ATTACKS);
        float seconds = attacker.getWeaponDisableBlockingForSeconds();
        if (seconds <= 0 || component == null) return;
        int ticks = Math.round(seconds * component.disableCooldownScale() * 20F);
        if (ticks <= 0) return;
        ticks = owner.damagePolicy().shieldDisable(this, attacker, shield, ticks);
        if (ticks < -1) throw new IllegalArgumentException("Invalid shield disable policy result");
        if (ticks == -1) return;
        int cooldown = owner.damagePolicy().itemCooldown(this, shield, ticks);
        if (cooldown < -1) throw new IllegalArgumentException("Invalid item cooldown policy result");
        if (cooldown >= 0) getItemCooldownManager().set(shield, cooldown);
        clearActiveItem();
        component.disableSound().ifPresent(sound -> world.playSound(null, getX(), getY(), getZ(), sound,
                getSoundCategory(), .8F, .8F + world.getRandom().nextFloat() * .4F));
    }

    private boolean applyEvent(FabricRollbackWorldAccess owner, ServerWorld world, DamageSource source, FabricRollbackDamageEvent event) {
        if (source.getAttacker() instanceof PlayerEntity attacker && (!attacker.isUsingItem() || !attacker.getActiveItem().contains(DataComponentTypes.KINETIC_WEAPON)))
            owner.damagePolicy().resetAttackCooldown(attacker, this);
        float resisted = (float) -event.damage(RESISTANCE);
        if (resisted > 0 && resisted < 3.4028235E37F) increaseStat(Stats.DAMAGE_RESISTED, Math.round(resisted * 10F));
        if (source.isIn(DamageTypeTags.DAMAGES_HELMET) && !getEquippedStack(EquipmentSlot.HEAD).isEmpty())
            damageHelmet(source, event.sum(BASE, INVULNERABILITY_REDUCTION, BLOCKING, FREEZING));
        if (!source.isIn(DamageTypeTags.BYPASSES_ARMOR)) damageArmor(source, event.sum(BASE, INVULNERABILITY_REDUCTION, BLOCKING, FREEZING, HARD_HAT));
        float blocking = (float) -event.damage(BLOCKING);
        if (blocking > 0) {
            ItemStack item = getBlockingItem();
            var component = item == null ? null : item.get(DataComponentTypes.BLOCKS_ATTACKS);
            if (component != null) {
                component.onShieldHit(world, item, this, getActiveHand(), blocking);
                if (!source.isIn(DamageTypeTags.IS_PROJECTILE) && source.getSource() instanceof LivingEntity attacker
                        && attacker.squaredDistanceTo(this) <= 200D * 200D) shieldHit(owner, world, source, attacker);
            }
        }
        float absorbed = (float) -event.damage(ABSORPTION);
        setAbsorptionAmount(Math.max(getAbsorptionAmount() - absorbed, 0F));
        if (absorbed > 0 && absorbed < 3.4028235E37F) {
            increaseStat(Stats.DAMAGE_ABSORBED, Math.round(absorbed * 10F));
            if (source.getAttacker() instanceof PlayerEntity attacker) attacker.increaseStat(Stats.DAMAGE_DEALT_ABSORBED, Math.round(absorbed * 10F));
        }
        float damage = (float) event.finalDamage();
        if (!Float.isFinite(damage)) throw new IllegalArgumentException("Damage event overflow");
        if (damage > 0) {
            owner.damagePolicy().exhaustion(this, source, source.getExhaustion());
            if (damage < 3.4028235E37F) increaseStat(Stats.DAMAGE_TAKEN, Math.round(damage * 10F));
            getDamageTracker().onDamage(source, damage); setHealth(getHealth() - damage); emitGameEvent(GameEvent.ENTITY_DAMAGE);
            return true;
        }
        return blocking <= 0 || !owner.damagePolicy().skipDamageTickWhenShieldBlocked();
    }
}
