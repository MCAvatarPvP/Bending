package com.projectkorra.projectkorra.prediction.rollback;

import com.mojang.authlib.GameProfile;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackPlayerSeedNativeTest {
    private static final long EPOCH = 5_000_000_000L;
    private static final PaperRollbackPlayerFields.Field<Long> LAST_JUMP = new PaperRollbackPlayerFields.Field<>(LivingEntity.class, "lastJumpTime", long.class);
    private static final PaperRollbackPlayerFields.Field<ItemStack> USE_ITEM = new PaperRollbackPlayerFields.Field<>(LivingEntity.class, "useItem", ItemStack.class);
    private static final PaperRollbackPlayerFields.Field<ItemStack> LAST_ITEM = new PaperRollbackPlayerFields.Field<>(net.minecraft.world.entity.player.Player.class, "lastItemInMainHand", ItemStack.class);
    private static final PaperRollbackPlayerFields.Field<ItemStack> SPIN_ITEM = new PaperRollbackPlayerFields.Field<>(LivingEntity.class, "autoSpinAttackItemStack", ItemStack.class);
    private static final PaperRollbackPlayerFields.Field<java.util.Map> LAST_EQUIPMENT = new PaperRollbackPlayerFields.Field<>(LivingEntity.class, "lastEquipmentItems", java.util.Map.class);
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }

    @Test @SuppressWarnings("unchecked") void portableContextKeepsControlsContactCachesAndRebasedTimersWithoutLiveHooks() throws Exception {
        onTickThread(() -> {
            var source = new Scene(); var original = source.create(); var player = (ServerPlayer) original.ownedPlayer(); configure(player);
            var eating = new PaperRollbackPlayerFields.Field<Long>(LivingEntity.class, "eatStartTime", long.class);
            var fluids = new PaperRollbackPlayerFields.Field<it.unimi.dsi.fastutil.objects.Object2DoubleMap>(Entity.class, "fluidHeight", it.unimi.dsi.fastutil.objects.Object2DoubleMap.class);
            var eyes = new PaperRollbackPlayerFields.Field<java.util.Set>(Entity.class, "fluidOnEyes", java.util.Set.class);
            var pistons = new PaperRollbackPlayerFields.Field<double[]>(Entity.class, "pistonDeltas", double[].class);
            var a = player.getAbilities(); a.invulnerable = true; a.flying = true; a.mayfly = true; a.mayBuild = false; a.flyingSpeed = .08F; a.walkingSpeed = .12F;
            player.setLastClientInput(new net.minecraft.world.entity.player.Input(true, false, false, true, true, false, true));
            player.setKnownMovement(new Vec3(.15, -.05, .25)); eating.set(player, EPOCH - 200_000_000L);
            player.addTag("duel"); player.addTag("\u6c34"); player.collidableExemptions.add(new UUID(0, 999));
            var customFluid = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.FLUID, Identifier.parse("test:mist"));
            fluids.get(player).put(net.minecraft.tags.FluidTags.WATER, .75); fluids.get(player).put(customFluid, .125);
            eyes.get(player).add(net.minecraft.tags.FluidTags.WATER); eyes.get(player).add(customFluid);
            System.arraycopy(new double[]{.2, -.3, .4}, 0, pistons.get(player), 0, 3);
            var seed = PaperRollbackPlayerSeed.capture(player, EPOCH); var context = RollbackPlayerContext.decode(seed.context().encode());
            var target = new Scene(); var state = target.imported(seed, 0); var copy = (ServerPlayer) state.ownedPlayer();
            assertArrayEquals(context.encode(), PaperRollbackPlayerContextData.capture(copy, 0).encode());
            assertEquals(-100_000_000L, LAST_JUMP.get(copy)); assertEquals(-200_000_000L, eating.get(copy));
            assertEquals(.75, copy.getFluidHeight(net.minecraft.tags.FluidTags.WATER)); assertTrue(copy.isEyeInFluid(net.minecraft.tags.FluidTags.WATER));
            assertEquals(player.getLastClientInput(), copy.getLastClientInput()); assertEquals(player.getKnownMovement(), copy.getKnownMovement());
            assertEquals(java.util.Set.of(new UUID(0, 999)), copy.collidableExemptions);
            var checkpoint = new RollbackStateGraph(value -> false, field -> true, 300_000).capture(List.of(state), List.of());
            copy.getAbilities().flying = false; fluids.get(copy).clear(); eyes.get(copy).clear(); copy.getTags().clear(); pistons.get(copy)[0] = 0;
            LAST_JUMP.set(copy, 44L); eating.set(copy, -1L); copy.collidableExemptions.clear();
            checkpoint.restore(); assertArrayEquals(context.encode(), PaperRollbackPlayerContextData.capture(copy, 0).encode());
            copy.getAbilities().flying = false; byte[] before = PaperRollbackPlayerContextData.capture(copy, 0).encode();
            int events = target.combat.events.size(), outputs = target.combat.outputs.size();
            assertThrows(ArithmeticException.class, () -> PaperRollbackPlayerContextData.apply(state, context, Long.MIN_VALUE));
            assertThrows(IllegalArgumentException.class, () -> PaperRollbackPlayerContextData.apply(state, context, 199_999_999));
            assertArrayEquals(before, PaperRollbackPlayerContextData.capture(copy, 0).encode());
            PaperRollbackPlayerContextData.apply(state, context, 6_000_000_000L);
            assertEquals(5_900_000_000L, LAST_JUMP.get(copy)); assertEquals(5_800_000_000L, eating.get(copy));
            assertEquals(events, target.combat.events.size()); assertEquals(outputs, target.combat.outputs.size());
            player.getTags().clear(); fluids.get(player).clear();
            assertEquals(2, context.tags().size()); assertEquals(2, context.fluidHeights().size());
            try (var fixture = getClass().getResourceAsStream("/rollback/player-context.base64")) {
                byte[] bytes = context.encode(); String message = "Current Paper player-context fixture: " + java.util.Base64.getEncoder().encodeToString(bytes);
                assertNotNull(fixture, message); assertArrayEquals(bytes, java.util.Base64.getMimeDecoder().decode(fixture.readAllBytes()), message);
            }
            return null;
        });
    }

    @Test @SuppressWarnings("unchecked") void portableItemsPreserveAliasesDistinctStacksCooldownsAndNativeLimitsWithoutHooks() throws Exception {
        onTickThread(() -> {
            var source = new Scene(); var original = source.create(); var player = (ServerPlayer) original.ownedPlayer(); configure(player);
            var sword = new ItemStack(Items.DIAMOND_SWORD); sword.set(DataComponents.CUSTOM_NAME, Component.literal("captured")); sword.setDamageValue(7); sword.setPopTime(4);
            var twin = sword.copy();
            player.getInventory().setItem(3, sword); player.getInventory().setItem(4, twin); player.getInventory().setItem(8, sword);
            player.getInventory().setItem(36, new ItemStack(Items.DIAMOND_BOOTS)); player.getInventory().setItem(40, twin);
            player.getEnderChestInventory().setItem(5, sword); player.getInventory().setSelectedSlot(3); player.getInventory().setMaxStackSize(32);
            USE_ITEM.set(player, sword); LAST_ITEM.set(player, sword); SPIN_ITEM.set(player, twin);
            LAST_EQUIPMENT.get(player).put(net.minecraft.world.entity.EquipmentSlot.MAINHAND, sword);
            LAST_EQUIPMENT.get(player).put(net.minecraft.world.entity.EquipmentSlot.OFFHAND, twin);
            player.getCooldowns().cooldowns.put(Identifier.parse("test:group"), new ItemCooldowns.CooldownInstance(3, 44));
            var seed = PaperRollbackPlayerSeed.capture(player, EPOCH);
            var items = RollbackPlayerItems.decode(seed.items().encode());
            var target = new Scene(); var state = target.imported(seed, EPOCH); var copy = (ServerPlayer) state.ownedPlayer();
            var copied = copy.getInventory().getItem(3); var copiedTwin = copy.getInventory().getItem(4);
            assertNotSame(sword, copied); assertNotSame(copied, copiedTwin); assertTrue(ItemStack.matches(copied, copiedTwin));
            assertSame(copied, copy.getInventory().getItem(8)); assertSame(copied, copy.getEnderChestInventory().getItem(5));
            assertSame(copied, USE_ITEM.get(copy)); assertSame(copied, LAST_ITEM.get(copy)); assertSame(copiedTwin, SPIN_ITEM.get(copy));
            assertSame(copiedTwin, copy.getInventory().getItem(40));
            assertSame(copied, LAST_EQUIPMENT.get(copy).get(net.minecraft.world.entity.EquipmentSlot.MAINHAND));
            assertEquals(32, copy.getInventory().getMaxStackSize()); assertEquals(4, copied.getPopTime());
            assertEquals(.8F, copy.getCooldowns().getCooldownPercent(new ItemStack(Items.ENDER_PEARL), 0));
            assertArrayEquals(items.encode(), PaperRollbackPlayerItems.capture(copy).encode());
            copied.setDamageValue(18); sword.setCount(2); copy.getInventory().setMaxStackSize(64);
            int outputs = target.combat.outputs.size(), events = target.combat.events.size();
            PaperRollbackPlayerItems.apply(state, items);
            assertEquals(outputs, target.combat.outputs.size()); assertEquals(events, target.combat.events.size());
            assertArrayEquals(items.encode(), PaperRollbackPlayerItems.capture(copy).encode());
            byte[] before = PaperRollbackPlayerItems.capture(copy).encode();
            var badTable = new java.util.ArrayList<>(items.items());
            badTable.set(items.useItem(), new RollbackPlayerItems.Item(new com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemData(new byte[]{99}), 0));
            var bad = new RollbackPlayerItems(badTable, items.inventory(), items.enderChest(), items.selected(), items.maximumStack(), items.useItem(), items.lastItem(), items.spinItem(), items.lastEquipment(), items.cooldownTick(), items.cooldowns());
            assertThrows(RuntimeException.class, () -> PaperRollbackPlayerItems.apply(state, bad));
            var wrongSlots = new java.util.TreeMap<>(items.lastEquipment()); wrongSlots.put("WRONG", items.useItem());
            var wrong = new RollbackPlayerItems(items.items(), items.inventory(), items.enderChest(), items.selected(), items.maximumStack(), items.useItem(), items.lastItem(), items.spinItem(), wrongSlots, items.cooldownTick(), items.cooldowns());
            assertThrows(IllegalArgumentException.class, () -> PaperRollbackPlayerItems.apply(state, wrong));
            assertArrayEquals(before, PaperRollbackPlayerItems.capture(copy).encode());
            try (var fixture = getClass().getResourceAsStream("/rollback/player-items.base64")) {
                byte[] bytes = items.encode(); String message = "Current Paper player-items fixture: " + java.util.Base64.getEncoder().encodeToString(bytes);
                assertNotNull(fixture, message); assertArrayEquals(bytes, java.util.Base64.getMimeDecoder().decode(fixture.readAllBytes()), message);
            }
            return null;
        });
    }

    @Test void portableVitalsKeepTrackedPoseHiddenEffectsAndAllAttributeModifiersWithoutImportHooks() throws Exception {
        onTickThread(() -> {
            var source = new Scene(); var original = source.create(); var player = (ServerPlayer) original.ownedPlayer();
            configure(player); player.setShiftKeyDown(true); original.clientLoaded(true); tick(original);
            source.world.air(player, 180);
            var effect = new MobEffectInstance(MobEffects.SPEED, 40, 2, false, false, false,
                    new MobEffectInstance(MobEffects.SPEED, 200, 0));
            player.getActiveEffectsMap().put(MobEffects.SPEED, effect);
            var speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
            speed.addPermanentModifier(new AttributeModifier(Identifier.parse("test:permanent"), .02, AttributeModifier.Operation.ADD_VALUE));
            speed.addTransientModifier(new AttributeModifier(Identifier.parse("test:temporary"), .4, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            player.getAttributes().registerAttribute(Attributes.FOLLOW_RANGE);
            player.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(42);
            var seed = PaperRollbackPlayerSeed.capture(player, EPOCH);
            var vitals = RollbackPlayerVitals.decode(seed.vitals().encode());
            var target = new Scene(); var imported = target.imported(seed, EPOCH); var copy = (ServerPlayer) imported.ownedPlayer();
            assertArrayEquals(seed.vitals().encode(), PaperRollbackPlayerSeed.capture(copy, EPOCH).vitals().encode());
            var copiedEffect = copy.getEffect(MobEffects.SPEED);
            assertNotSame(effect, copiedEffect); assertNotSame(effect.hiddenEffect, copiedEffect.hiddenEffect);
            assertEquals(200, copiedEffect.hiddenEffect.getDuration());
            copy.setHealth(4); target.world.air(copy, 8); copy.getActiveEffectsMap().clear(); copy.getAttribute(Attributes.MOVEMENT_SPEED).removeModifiers();
            int events = target.combat.events.size(), outputs = target.combat.outputs.size();
            PaperRollbackPlayerSeed.applyValues(imported, seed.values());
            PaperRollbackPlayerVitals.apply(imported, vitals);
            assertEquals(events, target.combat.events.size()); assertEquals(outputs, target.combat.outputs.size());
            assertEquals(17, copy.getHealth()); assertEquals(180, copy.getAirSupply()); assertEquals(net.minecraft.world.entity.Pose.CROUCHING, copy.getPose());
            assertEquals(42, copy.getAttributeValue(Attributes.FOLLOW_RANGE));
            assertEquals(1, copy.getAttribute(Attributes.MOVEMENT_SPEED).getPermanentModifiers().size());
            assertEquals(2, copy.getAttribute(Attributes.MOVEMENT_SPEED).getModifiers().size());
            byte[] before = PaperRollbackPlayerSeed.capture(copy, EPOCH).vitals().encode();
            var unknown = new java.util.ArrayList<>(vitals.attributes());
            unknown.add(new RollbackPlayerVitals.Attribute("test:missing", 1, List.of()));
            assertThrows(IllegalArgumentException.class, () -> PaperRollbackPlayerVitals.apply(imported,
                    new RollbackPlayerVitals(vitals.tracked(), vitals.effects(), unknown)));
            var badTracked = vitals.tracked(); badTracked[2] = (byte) 127;
            assertThrows(IllegalArgumentException.class, () -> PaperRollbackPlayerVitals.apply(imported,
                    new RollbackPlayerVitals(badTracked, vitals.effects(), vitals.attributes())));
            assertThrows(IllegalArgumentException.class, () -> PaperRollbackPlayerVitals.apply(imported,
                    new RollbackPlayerVitals(vitals.tracked(), new byte[]{99}, vitals.attributes())));
            assertArrayEquals(before, PaperRollbackPlayerSeed.capture(copy, EPOCH).vitals().encode());
            assertEquals(17, player.getHealth()); assertSame(effect, player.getEffect(MobEffects.SPEED));
            try (var fixture = getClass().getResourceAsStream("/rollback/player-vitals.base64")) {
                byte[] bytes = seed.vitals().encode();
                String message = "Current Paper player-vitals fixture: " + java.util.Base64.getEncoder().encodeToString(bytes);
                assertNotNull(fixture, message);
                assertArrayEquals(bytes, java.util.Base64.getMimeDecoder().decode(fixture.readAllBytes()), message);
            }
            return null;
        });
    }

    @Test void portableValueComponentRoundTripsEveryCapturedFieldAndPublishesThePaperFixture() throws Exception {
        onTickThread(() -> {
            var source = new Scene(); var original = source.create(); var player = (ServerPlayer) original.ownedPlayer();
            configure(player);
            player.hurtMarked = true; player.needsSync = true; player.swingingArm = net.minecraft.world.InteractionHand.OFF_HAND;
            player.yHeadRot = 20; player.yHeadRotO = 20; // Constructor head jitter is not a stable fixture input.
            new PaperRollbackPlayerFields.Field<>(Entity.class, "mainSupportingBlockPos", java.util.Optional.class)
                    .set(player, java.util.Optional.of(new net.minecraft.core.BlockPos(2, 0, 3)));
            var seed = PaperRollbackPlayerSeed.capture(player, EPOCH);
            var values = seed.values(); byte[] bytes = values.encode();
            var decoded = RollbackPlayerValues.decode(bytes);
            assertEquals(149, values.fields().size());
            assertEquals(values.fields(), decoded.fields());
            var target = new Scene().imported(seed, EPOCH);
            var copy = (ServerPlayer) target.ownedPlayer();
            copy.setPos(3, 4, 5); copy.setDeltaMovement(1, 2, 3); copy.tickCount = 888; copy.hurtMarked = false;
            PaperRollbackPlayerSeed.applyValues(target, decoded);
            assertEquals(values.fields(), PaperRollbackPlayerSeed.capture(copy, EPOCH).values().fields());
            assertEquals(original.readKinematics(), target.readKinematics());
            assertEquals(123, player.tickCount); assertTrue(player.hurtMarked);
            var malformed = new java.util.TreeMap<>(decoded.fields()); malformed.remove("walk.speed");
            copy.tickCount = 999;
            assertThrows(IllegalArgumentException.class, () -> PaperRollbackPlayerSeed.applyValues(target, new RollbackPlayerValues(malformed)));
            assertEquals(999, copy.tickCount, "A late schema error must not partially apply earlier fields");
            try (var fixture = getClass().getResourceAsStream("/rollback/player-values.base64")) {
                String message = "Current Paper player-values fixture: " + java.util.Base64.getEncoder().encodeToString(bytes);
                assertNotNull(fixture, message);
                assertArrayEquals(bytes, java.util.Base64.getMimeDecoder().decode(fixture.readAllBytes()), message);
            }
            return null;
        });
    }

    @Test void importDetachesComponentsEffectsModifiersAndAliasedItemsWithoutChangingTheSource() throws Exception {
        onTickThread(() -> {
            var source = new Scene(); var nativeSource = source.create();
            var player = (ServerPlayer) nativeSource.ownedPlayer();
            configure(player);
            var item = new ItemStack(Items.DIAMOND_SWORD, 1);
            item.set(DataComponents.CUSTOM_NAME, Component.literal("captured")); item.setPopTime(4);
            player.getInventory().setItem(3, item); player.getInventory().setSelectedSlot(3);
            USE_ITEM.set(player, item); player.useItemRemaining = 12;
            var effect = new MobEffectInstance(MobEffects.SPEED, 40, 2, false, false, false,
                    new MobEffectInstance(MobEffects.SPEED, 200, 0));
            player.getActiveEffectsMap().put(MobEffects.SPEED, effect);
            var speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
            var permanent = new AttributeModifier(Identifier.parse("test:permanent"), .02, AttributeModifier.Operation.ADD_VALUE);
            var transientModifier = new AttributeModifier(Identifier.parse("test:transient"), .4, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
            speed.addPermanentModifier(permanent); speed.addTransientModifier(transientModifier);
            var sourceConnection = player.connection; var sourceWorld = player.level();
            source.combat.outputs.clear(); source.combat.events.clear();
            var seed = PaperRollbackPlayerSeed.capture(player, EPOCH);
            assertTrue(source.combat.outputs.isEmpty()); assertTrue(source.combat.events.isEmpty());
            assertSame(sourceConnection, player.connection); assertSame(sourceWorld, player.level());
            assertSame(item, player.getInventory().getItem(3)); assertSame(effect, player.getEffect(MobEffects.SPEED));
            item.set(DataComponents.CUSTOM_NAME, Component.literal("changed")); item.setCount(2);
            effect.hiddenEffect.update(new MobEffectInstance(MobEffects.SPEED, 3, 3));
            speed.removeModifiers(); player.setHealth(4); player.setPos(3, 3, 3);
            var target = new Scene(); var imported = target.imported(seed, EPOCH + 1_000_000_000L);
            var copy = (ServerPlayer) imported.ownedPlayer();
            assertEquals(seed.id(), copy.getUUID()); assertEquals(player.getId(), copy.getId());
            assertEquals(new Vec3(.5, 1, .5), copy.position()); assertEquals(17, copy.getHealth());
            assertEquals(7, copy.invulnerableTime); assertEquals(6, copy.hurtTime); assertEquals(11, copy.lastHurt);
            assertEquals(123, copy.tickCount); assertEquals(55, copy.totalEntityAge);
            assertEquals(EPOCH + 900_000_000L, LAST_JUMP.get(copy));
            assertEquals(3, copy.getInventory().getSelectedSlot());
            var copiedItem = copy.getInventory().getItem(3);
            assertNotSame(item, copiedItem); assertEquals(1, copiedItem.getCount()); assertEquals(4, copiedItem.getPopTime());
            assertEquals("captured", copiedItem.get(DataComponents.CUSTOM_NAME).getString());
            assertSame(copiedItem, USE_ITEM.get(copy)); assertEquals(12, copy.useItemRemaining);
            var copiedEffect = copy.getEffect(MobEffects.SPEED);
            assertNotSame(effect, copiedEffect); assertNotSame(effect.hiddenEffect, copiedEffect.hiddenEffect);
            assertEquals(200, copiedEffect.hiddenEffect.getDuration()); assertFalse(copiedEffect.isVisible());
            assertEquals(2, copy.getAttribute(Attributes.MOVEMENT_SPEED).getModifiers().size());
            assertEquals(java.util.Set.of(permanent), copy.getAttribute(Attributes.MOVEMENT_SPEED).getPermanentModifiers());
            assertEquals(new ItemCooldowns.CooldownInstance(10, 50), copy.getCooldowns().cooldowns.get(Identifier.parse("minecraft:ender_pearl")));
            assertEquals(18, copy.getCooldowns().tickCount);
            assertNotSame(sourceConnection, copy.connection); assertNotSame(sourceWorld, copy.level());
            assertNotSame(player.random, copy.random); assertNotSame(Entity.SHARED_RANDOM, copy.random);
            copiedItem.setCount(5); copiedEffect.hiddenEffect.update(new MobEffectInstance(MobEffects.SPEED, 1, 4));
            var another = (ServerPlayer) new Scene().imported(seed, EPOCH).ownedPlayer();
            assertEquals(1, another.getInventory().getItem(3).getCount());
            assertEquals(200, another.getEffect(MobEffects.SPEED).hiddenEffect.getDuration());
            assertNull(org.bukkit.Bukkit.getServer());
            return null;
        });
    }

    @Test void importedMovementContinuesFromTheSourceAndRewindsNativeTicks() throws Exception {
        onTickThread(() -> {
            var source = new Scene(); var original = source.create();
            configure((ServerPlayer) original.ownedPlayer());
            var seed = PaperRollbackPlayerSeed.capture((ServerPlayer) original.ownedPlayer(), EPOCH);
            var target = new Scene(); var imported = target.imported(seed, EPOCH);
            original.clientLoaded(true); imported.clientLoaded(true);
            var checkpoint = new RollbackStateGraph(value -> false, field -> true, 300_000).capture(List.of(imported), List.of());
            tick(original); tick(imported);
            assertEquals(original.readKinematics(), imported.readKinematics());
            assertEquals(original.readVitals(), imported.readVitals());
            assertEquals(original.readControls(), imported.readControls());
            assertEquals(124, imported.ownedPlayer().tickCount);
            var motion = imported.readKinematics(); var vitals = imported.readVitals();
            var outputs = List.copyOf(target.combat.outputs);
            checkpoint.restore();
            assertEquals(123, imported.ownedPlayer().tickCount); assertEquals(7, imported.ownedPlayer().invulnerableTime);
            tick(imported);
            assertEquals(motion, imported.readKinematics()); assertEquals(vitals, imported.readVitals());
            assertEquals(outputs, target.combat.outputs);
            assertEquals(124, original.ownedPlayer().tickCount, "Rewinding the copy must not touch the source");
            return null;
        });
    }

    @Test void captureRejectsWrongThreadsReplayAndUnboundRelationsBeforeCreatingAReplica() throws Exception {
        onTickThread(() -> {
            var source = new Scene(); var state = source.create(); var player = (ServerPlayer) state.ownedPlayer();
            var failure = assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(() -> PaperRollbackPlayerSeed.capture(player, EPOCH)).join());
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            try (var ignored = RollbackClock.at(0, 1, 50_000_000)) {
                assertThrows(IllegalStateException.class, () -> PaperRollbackPlayerSeed.capture(player, EPOCH));
            }
            player.isChangingDimension = true;
            assertThrows(IllegalStateException.class, () -> PaperRollbackPlayerSeed.capture(player, EPOCH));
            player.isChangingDimension = false;
            var seed = PaperRollbackPlayerSeed.capture(player, EPOCH);
            var target = new Scene(); target.imported(seed, EPOCH);
            assertThrows(IllegalArgumentException.class, () -> target.imported(seed, EPOCH));
            var badClock = new Scene();
            assertThrows(ArithmeticException.class, () -> badClock.imported(seed, Long.MIN_VALUE));
            assertDoesNotThrow(() -> badClock.imported(seed, 0), "A rejected clock must not reserve/register the native player");
            var wrongTick = new Scene(); wrongTick.combat.time++;
            assertThrows(IllegalArgumentException.class, () -> wrongTick.imported(seed, EPOCH));
            assertTrue(source.combat.outputs.isEmpty());
            return null;
        });
    }

    @Test void aZeroBasedPrivateClockPreservesTheFirstSprintJumpImpulse() throws Exception {
        onTickThread(() -> {
            var source = new Scene(); var original = source.create(); var player = (ServerPlayer) original.ownedPlayer();
            configure(player); LAST_JUMP.set(player, 0L); player.setSprinting(true); original.clientLoaded(true);
            var seed = PaperRollbackPlayerSeed.capture(player, EPOCH);
            var imported = new Scene().imported(seed, 0); imported.clientLoaded(true);
            var input = new RollbackMovementInput(0, 1, true, 0, 0);
            try (var ignored = RollbackClock.at(1000, EPOCH, 1, 50_000_000)) { original.movementInput(input); original.tick(); }
            try (var ignored = RollbackClock.at(1000, 0, 1, 50_000_000)) { imported.movementInput(input); imported.tick(); }
            assertEquals(original.readKinematics(), imported.readKinematics());
            assertTrue(imported.ownedPlayer().getDeltaMovement().y > 0);
            assertEquals(50_000_000L, LAST_JUMP.get(imported.ownedPlayer()));
            return null;
        });
    }

    @Test void crouchedPoseImportsTheActualCollisionBoxAndEyeHeight() throws Exception {
        onTickThread(() -> {
            var source = new Scene(); var original = source.create(); var player = (ServerPlayer) original.ownedPlayer();
            configure(player); player.setShiftKeyDown(true); original.clientLoaded(true); tick(original);
            assertEquals(net.minecraft.world.entity.Pose.CROUCHING, player.getPose());
            var before = original.readKinematics();
            var seed = PaperRollbackPlayerSeed.capture(player, EPOCH + 50_000_000);
            var imported = new Scene().imported(seed, EPOCH + 50_000_000);
            assertEquals(before, imported.readKinematics());
            assertEquals(player.getEyeHeight(), imported.ownedPlayer().getEyeHeight());
            assertEquals(player.getPose(), imported.ownedPlayer().getPose());
            assertTrue(imported.ownedPlayer().isShiftKeyDown());
            assertEquals(1.5, imported.ownedPlayer().getBoundingBox().getYsize());
            return null;
        });
    }

    @Test void rosterComponentsValidateAllPlayersBeforeWritesAndCommitOnlyOnce() throws Exception {
        onTickThread(() -> {
            var source = new Scene();
            var first = (ServerPlayer) source.create().ownedPlayer();
            var second = (ServerPlayer) source.create(new UUID(0, 452)).ownedPlayer();
            configure(first); configure(second);
            first.lastHurtByMob = net.minecraft.world.entity.EntityReference.of(second);
            var firstSeed = PaperRollbackPlayerSeed.capture(first, EPOCH).portable(45);
            var secondSeed = PaperRollbackPlayerSeed.capture(second, EPOCH).portable(46);
            var outgoing = new RollbackRosterData(first.level().getGameTime(), java.util.Map.of(first.getUUID(), firstSeed, second.getUUID(), secondSeed));
            var target = new Scene();
            var targetA = (ServerPlayer) target.create().ownedPlayer();
            var targetB = (ServerPlayer) target.create(new UUID(0, 452)).ownedPlayer();
            targetA.setId(first.getId()); targetB.setId(second.getId());
            targetA.setHealth(3); targetB.setHealth(4);
            var roster = java.util.Map.of(targetA.getUUID(), targetA, targetB.getUUID(), targetB);
            var beforeA = PaperRollbackPlayerSeed.capture(targetA, EPOCH).portable(45);
            var beforeB = PaperRollbackPlayerSeed.capture(targetB, EPOCH).portable(46);
            var brokenItems = new java.util.ArrayList<>(secondSeed.items().items());
            brokenItems.set(0, new RollbackPlayerItems.Item(new com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemData(new byte[]{99}), 0));
            var items = secondSeed.items();
            var invalidItems = new RollbackPlayerItems(brokenItems, items.inventory(), items.enderChest(), items.selected(), items.maximumStack(),
                    items.useItem(), items.lastItem(), items.spinItem(), items.lastEquipment(), items.cooldownTick(), items.cooldowns());
            var broken = new RollbackRosterData.Player(secondSeed.identity(), secondSeed.randomSeed(), secondSeed.values(), secondSeed.vitals(),
                    invalidItems, secondSeed.context(), secondSeed.combat());
            assertThrows(RuntimeException.class, () -> PaperRollbackRosterComponents.prepare(roster,
                    new RollbackRosterData(outgoing.worldTime(), java.util.Map.of(first.getUUID(), firstSeed, second.getUUID(), broken)), EPOCH));
            assertArrayEquals(beforeA.vitals().encode(), PaperRollbackPlayerVitals.capture(targetA).encode());
            assertArrayEquals(beforeB.vitals().encode(), PaperRollbackPlayerVitals.capture(targetB).encode());
            var prepared = PaperRollbackRosterComponents.prepare(roster, outgoing, EPOCH);
            assertEquals(3, targetA.getHealth()); assertEquals(4, targetB.getHealth());
            targetB.setId(second.getId() + 1);
            assertThrows(IllegalStateException.class, prepared::commit);
            assertEquals(3, targetA.getHealth());
            targetB.setId(second.getId());
            int events = target.combat.events.size(), outputs = target.combat.outputs.size();
            prepared.commit();
            assertEquals(17, targetA.getHealth()); assertEquals(17, targetB.getHealth());
            assertArrayEquals(firstSeed.values().encode(), PaperRollbackPlayerSeed.capture(targetA, EPOCH).values().encode());
            assertArrayEquals(firstSeed.items().encode(), PaperRollbackPlayerItems.capture(targetA).encode());
            assertArrayEquals(firstSeed.context().encode(), PaperRollbackPlayerContextData.capture(targetA, EPOCH).encode());
            assertSame(targetB, targetA.lastHurtByMob.getEntity(targetA.level(), LivingEntity.class));
            assertEquals(events, target.combat.events.size()); assertEquals(outputs, target.combat.outputs.size());
            targetA.setHealth(9);
            prepared.commit();
            assertEquals(9, targetA.getHealth(), "Retry must not replay an already completed restoration");
            assertEquals(17, first.getHealth());
            return null;
        });
    }

    @Test void worldTickGatePreservesOutsidersAndRetainsOwnershipUntilCleanupSucceeds() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var a = (ServerPlayer) scene.create().ownedPlayer();
            var b = (ServerPlayer) scene.create(new UUID(0, 452)).ownedPlayer();
            var outsider = (ServerPlayer) scene.create(new UUID(0, 453)).ownedPlayer();
            var field = net.minecraft.server.level.ServerLevel.class.getDeclaredField("entityTickList");
            field.setAccessible(true);
            var original = new net.minecraft.world.level.entity.EntityTickList();
            original.add(a); original.add(b); original.add(outsider); field.set(a.level(), original);
            var first = PaperRollbackEntityTickGate.prepare(List.of(a));
            assertSame(original, field.get(a.level()));
            first.acquire();
            var overlap = PaperRollbackEntityTickGate.prepare(List.of(b, a));
            assertThrows(IllegalStateException.class, overlap::acquire);
            var ticks = new java.util.ArrayList<Entity>();
            ((net.minecraft.world.level.entity.EntityTickList) assertDoesNotThrow(() -> field.get(a.level()))).forEach(ticks::add);
            assertEquals(List.of(b, outsider), ticks);
            var second = PaperRollbackEntityTickGate.prepare(List.of(b)); second.acquire();
            // Chunk membership changes must not accidentally re-enable an owned player.
            ((net.minecraft.world.level.entity.EntityTickList) assertDoesNotThrow(() -> field.get(a.level()))).remove(a); ((net.minecraft.world.level.entity.EntityTickList) assertDoesNotThrow(() -> field.get(a.level()))).add(a);
            ticks.clear(); ((net.minecraft.world.level.entity.EntityTickList) assertDoesNotThrow(() -> field.get(a.level()))).forEach(ticks::add); assertEquals(List.of(outsider), ticks);
            assertThrows(IllegalArgumentException.class, () -> first.restoreAndRelease(() -> {
                assertThrows(IllegalStateException.class, () -> first.restoreAndRelease(() -> {}));
                throw new IllegalArgumentException("restoration failed");
            }));
            first.requireCurrent(); second.requireCurrent();
            ticks.clear(); ((net.minecraft.world.level.entity.EntityTickList) assertDoesNotThrow(() -> field.get(a.level()))).forEach(ticks::add); assertEquals(List.of(outsider), ticks);
            first.restoreAndRelease(() -> {
                ticks.clear(); ((net.minecraft.world.level.entity.EntityTickList) assertDoesNotThrow(() -> field.get(a.level()))).forEach(ticks::add); assertEquals(List.of(outsider), ticks);
            });
            assertNotSame(original, field.get(a.level()));
            ticks.clear(); ((net.minecraft.world.level.entity.EntityTickList) assertDoesNotThrow(() -> field.get(a.level()))).forEach(ticks::add); assertEquals(List.of(outsider, a), ticks);
            second.restoreAndRelease(() -> {});
            assertSame(original, field.get(a.level()));
            first.restoreAndRelease(() -> fail("Cleanup repeated"));
            ticks.clear(); original.forEach(ticks::add); assertEquals(List.of(b, outsider, a), ticks);
            overlap.restoreAndRelease(() -> fail("Unacquired owner must not restore"));
            return null;
        });
    }

    @Test void suspendedConnectionMaintenanceKeepsProtocolWorkWithoutTickingPlayer() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(); var nativePlayer = scene.create();
            var player = (ServerPlayer) nativePlayer.ownedPlayer(); configure(player);
            player.joining = true;
            new PaperRollbackPlayerFields.Field<Long>(ServerPlayer.class, "lastActionTime", long.class).set(player, 0L);
            var listener = new org.objenesis.ObjenesisStd().newInstance(MaintenanceProbe.class);
            listener.player = player; listener.sent = new java.util.ArrayList<>();
            var throttlers = new java.util.ArrayList<net.minecraft.util.TickThrottler>();
            for (var name : List.of("chatSpamThrottler", "dropSpamThrottler", "tabSpamThrottler", "recipeSpamPackets")) {
                var field = net.minecraft.server.network.ServerGamePacketListenerImpl.class.getDeclaredField(name);
                field.setAccessible(true);
                var throttler = new net.minecraft.util.TickThrottler(1, 1); throttler.increment();
                field.set(listener, throttler); throttlers.add(throttler);
            }
            var ack = new PaperRollbackPlayerFields.Field<Integer>(net.minecraft.server.network.ServerGamePacketListenerImpl.class, "ackBlockChangesUpTo", int.class);
            ack.set(listener, 12);
            var before = PaperRollbackPlayerSeed.capture(player, EPOCH);
            PaperRollbackConnectionMaintenance.tick(listener);
            assertEquals(1, listener.keepalives); assertEquals(-1, ack.get(listener));
            assertEquals(1, listener.sent.size());
            assertEquals(12, ((net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket) listener.sent.getFirst()).sequence());
            assertTrue(throttlers.stream().allMatch(net.minecraft.util.TickThrottler::isUnderThreshold));
            assertArrayEquals(before.values().encode(), PaperRollbackPlayerSeed.capture(player, EPOCH).values().encode());
            assertArrayEquals(before.items().encode(), PaperRollbackPlayerItems.capture(player).encode());
            PaperRollbackConnectionMaintenance.tick(listener);
            assertEquals(2, listener.keepalives); assertEquals(1, listener.sent.size());
            listener.processedDisconnect = true; ack.set(listener, 13);
            PaperRollbackConnectionMaintenance.tick(listener);
            assertEquals(2, listener.keepalives); assertEquals(13, ack.get(listener));
            try (var clock = RollbackClock.at(0, 1, 50_000_000)) {
                assertThrows(IllegalStateException.class, () -> PaperRollbackConnectionMaintenance.tick(listener));
            }
            var wrongThread = assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(
                    () -> PaperRollbackConnectionMaintenance.tick(listener)).join());
            assertInstanceOf(IllegalStateException.class, wrongThread.getCause());
            return null;
        });
    }

    public static class MaintenanceProbe extends net.minecraft.server.network.ServerGamePacketListenerImpl {
        int keepalives, nativeTicks, movements, commands, pongs;
        @Override public boolean shouldHandleMessage(net.minecraft.network.protocol.Packet<?> packet) { return true; }
        @Override public void onPacketError(net.minecraft.network.protocol.Packet packet, Exception failure) { throw new IllegalStateException(failure); }
        @Override public void handlePong(net.minecraft.network.protocol.common.ServerboundPongPacket packet) { pongs++; }
        @Override public void handleChatCommand(net.minecraft.network.protocol.game.ServerboundChatCommandPacket packet) { commands++; }
        @Override public void handleMovePlayer(net.minecraft.network.protocol.game.ServerboundMovePlayerPacket packet) { movements++; }
        @Override public void tick() { nativeTicks++; }
        java.util.List<net.minecraft.network.protocol.Packet<?>> sent;
        private MaintenanceProbe() { super(null, null, null, null); }
        @Override protected void keepConnectionAlive() { keepalives++; }
        @Override public void send(net.minecraft.network.protocol.Packet<?> packet) { sent.add(packet); }
    }

    @Test void connectionTickHandoffUsesOriginalStateAndRestoresListenerAfterSuccessfulCleanup() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(); var player = (ServerPlayer) scene.create().ownedPlayer();
            player.joining = true;
            new PaperRollbackPlayerFields.Field<Long>(ServerPlayer.class, "lastActionTime", long.class).set(player, 0L);
            var original = new org.objenesis.ObjenesisStd().newInstance(MaintenanceProbe.class);
            original.player = player; original.sent = new java.util.ArrayList<>(); player.connection = original;
            var configField = io.papermc.paper.configuration.GlobalConfiguration.class.getDeclaredField("instance");
            configField.setAccessible(true); var previousConfig = configField.get(null);
            var config = new io.papermc.paper.configuration.GlobalConfiguration();
            config.misc = config.new Misc(); config.packetLimiter = config.new PacketLimiter();
            configField.set(null, config);
            try {
                var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
                // The disconnected fixture has no channel; exercise Connection.tick without its unrelated disconnect callback.
                new PaperRollbackPlayerFields.Field<Boolean>(net.minecraft.network.Connection.class, "disconnectionHandled", boolean.class).set(connection, true);
                var nativeConnection = net.minecraft.server.network.ServerCommonPacketListenerImpl.class.getDeclaredField("connection");
                nativeConnection.setAccessible(true); nativeConnection.set(original, connection);
                var listenerField = new PaperRollbackPlayerFields.Field<net.minecraft.network.PacketListener>(net.minecraft.network.Connection.class,
                        "packetListener", net.minecraft.network.PacketListener.class);
                listenerField.set(connection, original);
                for (var name : List.of("chatSpamThrottler", "dropSpamThrottler", "tabSpamThrottler", "recipeSpamPackets")) {
                    var field = net.minecraft.server.network.ServerGamePacketListenerImpl.class.getDeclaredField(name);
                    field.setAccessible(true); field.set(original, new net.minecraft.util.TickThrottler(1, 1));
                }
                var ack = new PaperRollbackPlayerFields.Field<Integer>(net.minecraft.server.network.ServerGamePacketListenerImpl.class, "ackBlockChangesUpTo", int.class);
                ack.set(original, -1);
                var stop = new java.util.concurrent.atomic.AtomicReference<Runnable>(() -> { throw new IllegalStateException("stop requested"); });
                var lease = PaperRollbackConnectionTickGate.prepare(player, () -> stop.get().run());
                assertSame(original, connection.getPacketListener());
                // Native state changes after preparation must still be observed at acquisition.
                ack.set(original, 25);
                lease.acquire();
                var intercepted = (net.minecraft.server.network.ServerGamePacketListenerImpl) connection.getPacketListener();
                assertNotSame(original, intercepted);
                assertSame(original, player.connection);
                assertSame(player, intercepted.player); assertSame(player, intercepted.getPlayer());
                assertSame(connection, intercepted.connection);
                var movement = new org.objenesis.ObjenesisStd().newInstance(net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos.class);
                intercepted.handleMovePlayer(movement);
                assertEquals(0, original.movements);
                var networkThread = new java.util.concurrent.FutureTask<Void>(() -> { intercepted.handleMovePlayer(movement); return null; });
                new Thread(networkThread).start(); networkThread.get();
                assertEquals(0, original.movements);
                assertThrows(IllegalStateException.class, () -> PaperRollbackConnectionTickGate.prepare(player, () -> { throw new IllegalStateException("stop requested"); }));
                connection.tick();
                assertEquals(0, original.nativeTicks); assertEquals(1, original.keepalives);
                assertEquals(-1, ack.get(original)); assertEquals(1, original.sent.size());
                intercepted.send(new net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket(26));
                assertEquals(2, original.sent.size());
                assertThrows(IllegalArgumentException.class, () -> lease.restoreAndRelease(() -> {
                    assertSame(intercepted, connection.getPacketListener());
                    throw new IllegalArgumentException("restore not complete");
                }));
                lease.requireCurrent(); connection.tick();
                assertEquals(0, original.nativeTicks); assertEquals(2, original.keepalives);
                listenerField.set(connection, original);
                assertThrows(IllegalStateException.class, () -> lease.restoreAndRelease(() -> fail("Foreign listener replacement")));
                assertSame(original, connection.getPacketListener());
                listenerField.set(connection, intercepted);
                var processor = new net.minecraft.network.PacketProcessor(Thread.currentThread());
                var channel = new io.netty.channel.embedded.EmbeddedChannel(); connection.channel = channel;
                var serverTasks = new java.util.ArrayDeque<Runnable>();
                java.util.concurrent.Executor serverQueue = serverTasks::add;
                var oldCommandFinished = new java.util.concurrent.atomic.AtomicBoolean();
                serverTasks.add(() -> oldCommandFinished.set(true));
                var outsider = new org.objenesis.ObjenesisStd().newInstance(MaintenanceProbe.class);
                processor.scheduleIfPossible(original, movement);
                processor.scheduleIfPossible(outsider, movement);
                processor.scheduleIfPossible(original, new net.minecraft.network.protocol.common.ServerboundPongPacket(7));
                assertFalse(lease.pollPacketDrain(processor, serverQueue));
                assertEquals(0, original.pongs);
                // Simulate an old listener callback scheduling just before the barrier.
                processor.scheduleIfPossible(original, movement);
                channel.runPendingTasks();
                assertFalse(lease.pollPacketDrain(processor, serverQueue));
                assertFalse(oldCommandFinished.get());
                serverTasks.remove().run(); assertTrue(oldCommandFinished.get());
                assertFalse(lease.pollPacketDrain(processor, serverQueue));
                serverTasks.remove().run();
                assertTrue(lease.pollPacketDrain(processor, serverQueue));
                assertEquals(0, original.movements); assertEquals(1, original.pongs);
                assertEquals(0, outsider.movements);
                assertTrue(processor.executeSinglePacket()); assertEquals(1, outsider.movements);
                assertFalse(processor.executeSinglePacket());
                assertTrue(lease.pollPacketDrain(processor, serverQueue)); assertEquals(1, original.pongs);
                assertThrows(IllegalStateException.class,
                        () -> lease.pollPacketDrain(new net.minecraft.network.PacketProcessor(Thread.currentThread()), serverQueue));
                channel.finishAndReleaseAll(); connection.channel = null;
                var command = new net.minecraft.network.protocol.game.ServerboundChatCommandPacket("kill");
                assertThrows(IllegalStateException.class, () -> intercepted.handleChatCommand(command));
                assertEquals(0, original.commands); lease.requireCurrent();
                processor.scheduleIfPossible(original, command);
                processor.scheduleIfPossible(original, movement);
                assertThrows(IllegalStateException.class, () -> PaperRollbackQueuedPackets.drain(processor, original, intercepted));
                assertEquals(0, original.movements); assertFalse(processor.executeSinglePacket());
                lease.requireCurrent();
                stop.set(() -> { });
                assertThrows(IllegalStateException.class, () -> intercepted.handleChatCommand(command));
                assertEquals(0, original.commands); lease.requireCurrent();
                stop.set(() -> lease.restoreAndRelease(() -> assertSame(intercepted, connection.getPacketListener())));
                processor.scheduleIfPossible(original, command);
                processor.scheduleIfPossible(original, movement);
                assertEquals(2, PaperRollbackQueuedPackets.drain(processor, original, intercepted));
                assertEquals(1, original.commands); assertEquals(0, original.movements);
                assertFalse(processor.executeSinglePacket());
                assertSame(original, connection.getPacketListener());
                connection.tick(); assertEquals(1, original.nativeTicks);
                // A previously captured facade reference must resume normal delegation after release.
                intercepted.tick(); assertEquals(2, original.nativeTicks);
                intercepted.handleMovePlayer(movement); assertEquals(1, original.movements);
                lease.restoreAndRelease(() -> fail("Restoration repeated"));
                assertThrows(IllegalStateException.class, lease::acquire);
                var nextLease = PaperRollbackConnectionTickGate.prepare(player,
                        () -> { throw new IllegalStateException("next session stop requested"); });
                nextLease.acquire();
                intercepted.handleMovePlayer(movement); assertEquals(1, original.movements);
                intercepted.tick(); assertEquals(2, original.nativeTicks);
                assertThrows(IllegalStateException.class, () -> intercepted.handleChatCommand(command));
                assertEquals(1, original.commands); nextLease.requireCurrent();
                nextLease.restoreAndRelease(() -> { });
                intercepted.handleMovePlayer(movement); assertEquals(2, original.movements);
                return null;
            } finally { configField.set(null, previousConfig); }
        });
    }

    @Test void nativeRosterWaitsForEveryPeerAndRecoversGatesAfterFailedWorldRelease() throws Exception {
        onTickThread(() -> {
            var configField = io.papermc.paper.configuration.GlobalConfiguration.class.getDeclaredField("instance");
            configField.setAccessible(true); var previousConfig = configField.get(null);
            var config = new io.papermc.paper.configuration.GlobalConfiguration();
            config.misc = config.new Misc(); config.packetLimiter = config.new PacketLimiter(); configField.set(null, config);
            var channels = new java.util.ArrayList<io.netty.channel.embedded.EmbeddedChannel>();
            try {
                var scene = new Scene();
                var a = (ServerPlayer) scene.create().ownedPlayer();
                var b = (ServerPlayer) scene.create(new UUID(0, 452)).ownedPlayer();
                var outsider = (ServerPlayer) scene.create(new UUID(0, 453)).ownedPlayer();
                var worldField = net.minecraft.server.level.ServerLevel.class.getDeclaredField("entityTickList"); worldField.setAccessible(true);
                var originalTicks = new net.minecraft.world.level.entity.EntityTickList();
                originalTicks.add(a); originalTicks.add(b); originalTicks.add(outsider); worldField.set(a.level(), originalTicks);
                var originals = new java.util.ArrayList<MaintenanceProbe>();
                for (var player : List.of(a, b)) {
                    var original = nativeOwnershipConnection(player); originals.add(original);
                    var channel = new io.netty.channel.embedded.EmbeddedChannel(); channels.add(channel); original.connection.channel = channel;
                }
                var group = PaperRollbackNativeOwnership.prepare(List.of(a, b), () -> { throw new IllegalStateException("stop requested"); });
                assertSame(originalTicks, worldField.get(a.level()));
                assertSame(originals.getFirst(), originals.getFirst().connection.getPacketListener());
                assertThrows(IllegalStateException.class, group::requireReady);
                group.acquire();
                var gatedTicks = worldField.get(a.level());
                var visible = new java.util.ArrayList<Entity>();
                ((net.minecraft.world.level.entity.EntityTickList) gatedTicks).forEach(visible::add);
                assertEquals(List.of(outsider), visible);
                originals.forEach(original -> ((net.minecraft.server.network.ServerGamePacketListenerImpl) original.connection.getPacketListener()).tick());
                originals.forEach(original -> assertEquals(0, original.nativeTicks));
                var processor = new net.minecraft.network.PacketProcessor(Thread.currentThread());
                var tasks = new java.util.ArrayDeque<Runnable>(); java.util.concurrent.Executor executor = tasks::add;
                assertFalse(group.pollReady(processor, executor));
                channels.getFirst().runPendingTasks(); tasks.remove().run();
                assertFalse(group.pollReady(processor, executor));
                assertThrows(IllegalStateException.class, group::requireReady);
                channels.getLast().runPendingTasks(); tasks.remove().run();
                assertTrue(group.pollReady(processor, executor)); group.requireReady();
                assertThrows(IllegalArgumentException.class, () -> group.restoreAndRelease(() -> { throw new IllegalArgumentException("restore failed"); }));
                assertThrows(IllegalStateException.class, group::requireReady);
                assertThrows(IllegalStateException.class, () -> group.pollReady(processor, executor));
                originals.forEach(original -> assertNotSame(original, original.connection.getPacketListener()));
                // Fail the world release after connection detach, then verify both facades are recovered.
                assertThrows(IllegalStateException.class, () -> group.restoreAndRelease(() -> {
                    assertDoesNotThrow(() -> worldField.set(a.level(), originalTicks));
                }));
                originals.forEach(original -> assertNotSame(original, original.connection.getPacketListener()));
                worldField.set(a.level(), gatedTicks);
                assertThrows(IllegalStateException.class, () -> group.restoreAndRelease(() -> fail("Retry requires packet handoff")));
                var move = new org.objenesis.ObjenesisStd().newInstance(net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos.class);
                processor.scheduleIfPossible(originals.getFirst(), move);
                assertFalse(group.pollCleanup(processor, executor));
                channels.forEach(io.netty.channel.embedded.EmbeddedChannel::runPendingTasks);
                while (!tasks.isEmpty()) tasks.remove().run();
                assertTrue(group.pollCleanup(processor, executor));
                assertEquals(0, originals.getFirst().movements); assertFalse(processor.executeSinglePacket());
                group.restoreAndRelease(() -> {
                    originals.forEach(original -> assertNotSame(original, original.connection.getPacketListener()));
                    assertSame(gatedTicks, assertDoesNotThrow(() -> worldField.get(a.level())));
                });
                assertSame(originalTicks, worldField.get(a.level()));
                originals.forEach(original -> {
                    assertSame(original, original.connection.getPacketListener());
                    ((net.minecraft.server.network.ServerGamePacketListenerImpl) original.connection.getPacketListener()).tick(); assertEquals(1, original.nativeTicks);
                });
                group.restoreAndRelease(() -> fail("Cleanup repeated"));
                assertThrows(IllegalStateException.class, group::acquire);
                var partial = PaperRollbackNativeOwnership.prepare(List.of(a, b), () -> fail("Unexpected stop"));
                var listenerField = new PaperRollbackPlayerFields.Field<net.minecraft.network.PacketListener>(net.minecraft.network.Connection.class,
                        "packetListener", net.minecraft.network.PacketListener.class);
                var secondConnection = originals.getLast().connection;
                listenerField.set(secondConnection, originals.getFirst());
                assertThrows(IllegalStateException.class, partial::acquire);
                assertNotSame(originals.getFirst(), originals.getFirst().connection.getPacketListener());
                partial.restoreAndRelease(() -> {
                    assertNotSame(originals.getFirst(), originals.getFirst().connection.getPacketListener());
                    assertSame(originals.getFirst(), secondConnection.getPacketListener());
                });
                assertSame(originalTicks, worldField.get(a.level()));
                assertSame(originals.getFirst(), originals.getFirst().connection.getPacketListener());
                assertSame(originals.getFirst(), secondConnection.getPacketListener());
                listenerField.set(secondConnection, originals.getLast());
                var unacquired = PaperRollbackNativeOwnership.prepare(List.of(a, b), () -> fail("Unexpected stop"));
                unacquired.restoreAndRelease(() -> fail("No native state was acquired"));
                assertSame(originalTicks, worldField.get(a.level()));
                return null;
            } finally { channels.forEach(io.netty.channel.embedded.EmbeddedChannel::finishAndReleaseAll); configField.set(null, previousConfig); }
        });
    }

    private static MaintenanceProbe nativeOwnershipConnection(ServerPlayer player) throws Exception {
        player.joining = true;
        new PaperRollbackPlayerFields.Field<Long>(ServerPlayer.class, "lastActionTime", long.class).set(player, 0L);
        var original = new org.objenesis.ObjenesisStd().newInstance(MaintenanceProbe.class);
        original.player = player; original.sent = new java.util.ArrayList<>(); player.connection = original;
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        var nativeConnection = net.minecraft.server.network.ServerCommonPacketListenerImpl.class.getDeclaredField("connection");
        nativeConnection.setAccessible(true); nativeConnection.set(original, connection);
        new PaperRollbackPlayerFields.Field<net.minecraft.network.PacketListener>(net.minecraft.network.Connection.class,
                "packetListener", net.minecraft.network.PacketListener.class).set(connection, original);
        for (var name : List.of("chatSpamThrottler", "dropSpamThrottler", "tabSpamThrottler", "recipeSpamPackets")) {
            var field = net.minecraft.server.network.ServerGamePacketListenerImpl.class.getDeclaredField(name);
            field.setAccessible(true); field.set(original, new net.minecraft.util.TickThrottler(1, 1));
        }
        new PaperRollbackPlayerFields.Field<Integer>(net.minecraft.server.network.ServerGamePacketListenerImpl.class,
                "ackBlockChangesUpTo", int.class).set(original, -1);
        return original;
    }

    @Test void connectionPacketPolicyOnlyPassesAuditedControlAndRollbackChannels() {
        var allocator = new org.objenesis.ObjenesisStd();
        for (var type : List.of(net.minecraft.network.protocol.common.ServerboundKeepAlivePacket.class,
                net.minecraft.network.protocol.common.ServerboundPongPacket.class,
                net.minecraft.network.protocol.game.ServerboundChatAckPacket.class,
                net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket.class)) {
            assertEquals(PaperRollbackConnectionTickGate.PacketDisposition.PASS,
                    PaperRollbackConnectionTickGate.packetDisposition(allocator.newInstance(type)), type.getName());
        }
        for (var type : List.of(net.minecraft.network.protocol.game.ServerboundContainerClickPacket.class,
                net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket.class,
                net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket.class,
                net.minecraft.network.protocol.game.ServerboundChatCommandPacket.class)) {
            assertEquals(PaperRollbackConnectionTickGate.PacketDisposition.STOP,
                    PaperRollbackConnectionTickGate.packetDisposition(allocator.newInstance(type)), type.getName());
        }
        for (var channel : List.of(RollbackInputPacket.CHANNEL, RollbackStartPacket.CLIENT_CHANNEL,
                RollbackBootstrapPacket.CLIENT_CHANNEL, "other:mutation", "projectkorra:rollback_input_extra")) {
            var packet = new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
                    new net.minecraft.network.protocol.common.custom.DiscardedPayload(Identifier.parse(channel), new byte[0]));
            assertEquals(channel.equals("other:mutation") || channel.endsWith("_extra")
                            ? PaperRollbackConnectionTickGate.PacketDisposition.STOP : PaperRollbackConnectionTickGate.PacketDisposition.PASS,
                    PaperRollbackConnectionTickGate.packetDisposition(packet), channel);
        }
    }

    private static void configure(ServerPlayer player) {
        player.setPos(.5, 1, .5); player.setOnGround(true); player.setYRot(20); player.setXRot(-12);
        player.setDeltaMovement(.05, 0, .08); player.setHealth(17);
        player.tickCount = 123; player.totalEntityAge = 55; player.invulnerableTime = 7; player.hurtTime = 6; player.lastHurt = 11;
        player.experienceLevel = 8; player.experienceProgress = .4F; player.getFoodData().foodLevel = 13;
        player.getFoodData().exhaustionLevel = 1.25F; player.getFoodData().saturationLevel = 2.5F;
        LAST_JUMP.set(player, EPOCH - 100_000_000L);
        player.getCooldowns().tickCount = 18;
        player.getCooldowns().cooldowns.put(Identifier.parse("minecraft:ender_pearl"), new ItemCooldowns.CooldownInstance(10, 50));
    }

    private static void tick(PaperRollbackNativePlayerState state) {
        try (var ignored = RollbackClock.at(1000, EPOCH, 1, 50_000_000)) {
            state.movementInput(new RollbackMovementInput(.4F, .7F, false, 20, -12)); state.tick();
        }
    }

    private static final class Scene {
        final PaperRollbackDamageNativeTest.Combat combat = new PaperRollbackDamageNativeTest.Combat();
        final PaperRollbackWorldAccessNativeTest.Queries queries = new PaperRollbackWorldAccessNativeTest.Queries(new RollbackBlockStore.Bounds(-5, -4, -5, 12, 12, 16));
        final PaperRollbackWorldAccess world = new PaperRollbackWorldAccess(queries, combat, 4);
        Scene() { for (int x = -3; x < 8; x++) for (int z = -3; z < 10; z++) queries.block(x, 0, z, Material.STONE, "minecraft:stone"); }
        PaperRollbackNativePlayerState create() { return create(new UUID(0, 451)); }
        PaperRollbackNativePlayerState create(UUID id) {
            var state = PaperRollbackNativePlayerState.serverPlayer(world, new GameProfile(id, "imported"),
                    ClientInformation.createDefault(), GameType.SURVIVAL, 45, 300_000);
            state.ownedPlayer().valid = true;
            return state;
        }
        PaperRollbackNativePlayerState imported(PaperRollbackPlayerSeed seed, long epoch) {
            return seed.instantiate(world, epoch, 45, 300_000, PaperRollbackStatistics.Seed.fresh(), PaperRollbackAdvancements.Seed.empty());
        }
    }
}
