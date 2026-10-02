package com.projectkorra.projectkorra.prediction.rollback;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;

/** Shared results are produced by actual patched Paper damage, not a duplicate damage formula. */
class PaperRollbackRoundParityNativeTest {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), ID = new UUID(0, 9);
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }
    @Test void authoritativeNativeRoundResultsMatchSharedFixture() throws Exception {
        try (var stream = getClass().getResourceAsStream("/rollback/round-damage.csv")) {
            for (String line : new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                var fields = line.split(",");
                String result = onTickThread(() -> run(fields));
                assertEquals(String.join(",", Arrays.copyOfRange(fields, 10, fields.length)), result, fields[0]);
            }
        }
    }
    private static String run(String[] f) throws Exception {
        var combat = new PaperRollbackDamageNativeTest.Combat();
        var world = new PaperRollbackWorldAccess(new PaperRollbackWorldAccessNativeTest.Queries(), combat, 4);
        var attackerState = PaperRollbackNativePlayerState.serverPlayer(world, new GameProfile(A, "parity1"), ClientInformation.createDefault(), GameType.SURVIVAL, 103, 200_000);
        var targetState = PaperRollbackNativePlayerState.serverPlayer(world, new GameProfile(B, "parity2"), ClientInformation.createDefault(), GameType.SURVIVAL, 107, 200_000);
        var attacker = attackerState.use(p -> (net.minecraft.server.level.ServerPlayer) p); var target = targetState.use(p -> (net.minecraft.server.level.ServerPlayer) p);
        attacker.setPos(-1, 1, 0); target.setPos(0, 1, 0); target.setOnGround(true);
        target.setHealth(Float.parseFloat(f[2])); target.getAttribute(Attributes.ARMOR).setBaseValue(Double.parseDouble(f[3]));
        target.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(4); target.setAbsorptionAmount(Float.parseFloat(f[4]));
        int resistance = Integer.parseInt(f[5]), protection = Integer.parseInt(f[6]);
        if (resistance > 0) target.getActiveEffectsMap().put(MobEffects.RESISTANCE, new MobEffectInstance(MobEffects.RESISTANCE, 80, resistance - 1));
        ItemStack chest = Double.parseDouble(f[3]) > 0 || protection > 0 ? new ItemStack(Items.DIAMOND_CHESTPLATE) : ItemStack.EMPTY;
        if (!chest.isEmpty()) {
            if (protection > 0) {
                var enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
                enchantments.set(((net.minecraft.core.RegistryAccess) combat.registryAccess()).lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION), protection);
                chest.set(net.minecraft.core.component.DataComponents.ENCHANTMENTS, enchantments.toImmutable());
            }
            target.setItemSlot(EquipmentSlot.CHEST, chest);
        }
        combat.multiplier = Double.parseDouble(f[7]);
        target.lastHurt = Float.parseFloat(f[8]); if (target.lastHurt > 0) target.invulnerableTime = 20;
        if (Boolean.parseBoolean(f[9])) target.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        if (f[0].startsWith("shield")) {
            ItemStack shield = new ItemStack(Items.SHIELD); target.setItemSlot(EquipmentSlot.OFFHAND, shield);
            target.setYHeadRot(90);
            // Import an already-active shield; setup must not dispatch a live Bukkit event.
            var use = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("useItem"); use.setAccessible(true); use.set(target, shield);
            var left = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("useItemRemaining"); left.setAccessible(true); left.setInt(target, shield.getUseDuration(target) - 6);
            var flags = net.minecraft.world.entity.LivingEntity.class.getDeclaredMethod("setLivingEntityFlag", int.class, boolean.class); flags.setAccessible(true);
            flags.invoke(target, 1, true); flags.invoke(target, 2, true);
            combat.cancel = f[0].equals("shield-cancelled");
        }
        var round = new RollbackRound(ID, Map.of(A, A, B, B)); world.bindRound(round); round.beginTick(1);
        boolean accepted = targetState.damage(world.world().damageSources().playerAttack(attacker), Float.parseFloat(f[1]));
        return accepted + "," + target.getHealth() + "," + target.getAbsorptionAmount() + "," + chest.getDamageValue()
                + "," + target.invulnerableTime + "," + target.lastHurt + "," + target.getDeltaMovement().x + "," + target.getDeltaMovement().y
                + "," + target.getDeltaMovement().z + "," + round.ended() + "," + target.getOffhandItem().getCount() + "," + target.getOffhandItem().getDamageValue();
    }
}
