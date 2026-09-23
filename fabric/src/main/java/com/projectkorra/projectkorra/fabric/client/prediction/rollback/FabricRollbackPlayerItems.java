package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.fabric.mixin.client.ItemCooldownRollbackAccess;
import com.projectkorra.projectkorra.fabric.mixin.client.LivingEntityRollbackItemAccess;
import com.projectkorra.projectkorra.fabric.mixin.client.PlayerEntityRollbackItemAccess;
import com.projectkorra.projectkorra.prediction.rollback.RollbackClock;
import com.projectkorra.projectkorra.prediction.rollback.RollbackDomain;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerItems;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.*;
import java.util.function.IntFunction;

/** Imports Paper's item graph and exact cooldown interval without native use/equip/cooldown hooks. */
public final class FabricRollbackPlayerItems {
    private FabricRollbackPlayerItems() { }
    public static RollbackPlayerItems capture(FabricRollbackNativePlayerState state) {
        requireBootstrap(); return state.use(FabricRollbackPlayerItems::capture);
    }
    private static RollbackPlayerItems capture(PlayerEntity player) {
        var capture = new Items(new FabricRollbackItemCodec(player.getEntityWorld().getRegistryManager()));
        var inventory = capture.slots(player.getInventory().size(), player.getInventory()::getStack);
        var enderChest = capture.slots(player.getEnderChestInventory().size(), player.getEnderChestInventory()::getStack);
        var living = (LivingEntityRollbackItemAccess) player; var access = (PlayerEntityRollbackItemAccess) player;
        int use = capture.add(living.rollback$useItem()), last = capture.add(access.rollback$lastItem());
        int spin = living.rollback$spinItem() == null ? -1 : capture.add(living.rollback$spinItem());
        var equipment = new TreeMap<String, Integer>();
        var nativeEquipment = living.rollback$lastEquipment();
        nativeEquipment.keySet().stream().sorted(Comparator.comparing(Enum::name)).forEach(slot -> equipment.put(slot.name(), capture.add(nativeEquipment.get(slot))));
        var manager = (ItemCooldownRollbackAccess) player.getItemCooldownManager();
        if (manager.rollback$entries().size() > RollbackPlayerItems.MAXIMUM_COOLDOWNS) throw new IllegalArgumentException("Player cooldown budget");
        var cooldowns = new TreeMap<String, RollbackPlayerItems.Cooldown>();
        manager.rollback$entries().forEach((key, value) -> cooldowns.put(key.toString(), Cooldowns.read(value)));
        return new RollbackPlayerItems(capture.items, inventory, enderChest, player.getInventory().getSelectedSlot(), player.getInventory().getMaxCountPerStack(),
                use, last, spin, equipment, manager.rollback$tick(), cooldowns);
    }

    public static void apply(FabricRollbackNativePlayerState state, RollbackPlayerItems items) {
        requireBootstrap(); state.use(player -> { apply(player, items); return null; });
    }
    private static void apply(PlayerEntity player, RollbackPlayerItems seed) {
        if (player.getInventory().size() != seed.inventory().size() || player.getEnderChestInventory().size() != seed.enderChest().size()) {
            throw new IllegalArgumentException("Native inventory layout changed");
        }
        var codec = new FabricRollbackItemCodec(player.getEntityWorld().getRegistryManager());
        var items = seed.items().stream().map(value -> { var stack = codec.decode(value.data()); if (!stack.isEmpty()) stack.setBobbingAnimationTime(value.popTime()); return stack; }).toList();
        var equipment = new EnumMap<EquipmentSlot, ItemStack>(EquipmentSlot.class);
        seed.lastEquipment().forEach((key, index) -> equipment.put(EquipmentSlot.valueOf(key), items.get(index)));
        var cooldowns = new HashMap<Identifier, Object>();
        seed.cooldowns().forEach((key, value) -> cooldowns.put(Identifier.of(key), Cooldowns.create(value)));
        var limit = (FabricRollbackInventoryLimit) player.getInventory();
        var living = (LivingEntityRollbackItemAccess) player; var access = (PlayerEntityRollbackItemAccess) player;
        var manager = (ItemCooldownRollbackAccess) player.getItemCooldownManager();
        // Decoding, native enum/identifier checks and internal-entry construction precede all writes.
        for (int slot = 0; slot < seed.inventory().size(); slot++) player.getInventory().setStack(slot, items.get(seed.inventory().get(slot)));
        for (int slot = 0; slot < seed.enderChest().size(); slot++) player.getEnderChestInventory().setStack(slot, items.get(seed.enderChest().get(slot)));
        player.getInventory().setSelectedSlot(seed.selected()); limit.rollback$maximumStack(seed.maximumStack());
        living.rollback$useItem(items.get(seed.useItem())); access.rollback$lastItem(items.get(seed.lastItem()));
        living.rollback$spinItem(seed.spinItem() == -1 ? null : items.get(seed.spinItem()));
        living.rollback$lastEquipment().clear(); living.rollback$lastEquipment().putAll(equipment);
        manager.rollback$entries().clear(); manager.rollback$entries().putAll(cooldowns); manager.rollback$tick(seed.cooldownTick());
    }
    private static void requireBootstrap() {
        if (RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Import/capture player items before replay");
    }

    private static final class Items {
        final FabricRollbackItemCodec codec;
        final IdentityHashMap<ItemStack, Integer> ids = new IdentityHashMap<>();
        final List<RollbackPlayerItems.Item> items = new ArrayList<>();
        int bytes;
        Items(FabricRollbackItemCodec codec) { this.codec = codec; }
        int add(ItemStack stack) {
            var existing = ids.get(stack); if (existing != null) return existing;
            if (items.size() >= RollbackPlayerItems.MAXIMUM_ITEMS) throw new IllegalArgumentException("Player item budget");
            var data = codec.encode(stack); bytes += data.size();
            if (bytes > RollbackPlayerItems.MAXIMUM_ITEM_BYTES) throw new IllegalArgumentException("Player item bytes");
            int id = items.size(); ids.put(stack, id); items.add(new RollbackPlayerItems.Item(data, stack.getBobbingAnimationTime())); return id;
        }
        List<Integer> slots(int size, IntFunction<ItemStack> source) {
            if (size > RollbackPlayerItems.MAXIMUM_SLOTS) throw new IllegalArgumentException("Player inventory budget");
            var result = new ArrayList<Integer>(size); for (int i = 0; i < size; i++) result.add(add(source.apply(i))); return result;
        }
    }

    /** Vanilla's package-private immutable record; resolve its fixed 1.21.11 schema once. */
    private static final class Cooldowns {
        static final MethodHandle CONSTRUCTOR, START, END;
        static {
            try {
                var mappings = FabricLoader.getInstance().getMappingResolver();
                String owner = "net.minecraft.class_1796$class_1797";
                Class<?> type = Class.forName(mappings.mapClassName("intermediary", owner));
                var lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
                CONSTRUCTOR = lookup.findConstructor(type, MethodType.methodType(void.class, int.class, int.class)).asType(MethodType.methodType(Object.class, int.class, int.class));
                START = lookup.findGetter(type, mappings.mapFieldName("intermediary", owner, "comp_3083", "I"), int.class).asType(MethodType.methodType(int.class, Object.class));
                END = lookup.findGetter(type, mappings.mapFieldName("intermediary", owner, "comp_3084", "I"), int.class).asType(MethodType.methodType(int.class, Object.class));
            } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
        }
        static Object create(RollbackPlayerItems.Cooldown value) {
            try { return (Object) CONSTRUCTOR.invokeExact(value.startTick(), value.endTick()); }
            catch (Throwable failure) { throw failed(failure); }
        }
        static RollbackPlayerItems.Cooldown read(Object value) {
            try { return new RollbackPlayerItems.Cooldown((int) START.invokeExact(value), (int) END.invokeExact(value)); }
            catch (Throwable failure) { throw failed(failure); }
        }
        private static RuntimeException failed(Throwable failure) {
            if (failure instanceof Error error) throw error;
            if (failure instanceof RuntimeException runtime) return runtime;
            return new IllegalStateException("Native cooldown access failed", failure);
        }
    }
}
