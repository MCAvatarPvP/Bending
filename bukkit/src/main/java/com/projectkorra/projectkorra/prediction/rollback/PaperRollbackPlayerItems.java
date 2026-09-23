package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;

import java.util.*;
import java.util.function.IntFunction;

/** Full native items and alias roots; codecs and layouts are checked before private writes. */
public final class PaperRollbackPlayerItems {
    private static final PaperRollbackPlayerFields.Field<ItemStack> USE = new PaperRollbackPlayerFields.Field<>(LivingEntity.class, "useItem", ItemStack.class);
    private static final PaperRollbackPlayerFields.Field<ItemStack> LAST = new PaperRollbackPlayerFields.Field<>(Player.class, "lastItemInMainHand", ItemStack.class);
    private static final PaperRollbackPlayerFields.Field<ItemStack> SPIN = new PaperRollbackPlayerFields.Field<>(LivingEntity.class, "autoSpinAttackItemStack", ItemStack.class);
    private static final PaperRollbackPlayerFields.Field<Map> EQUIPMENT = new PaperRollbackPlayerFields.Field<>(LivingEntity.class, "lastEquipmentItems", Map.class);
    private PaperRollbackPlayerItems() { }

    @SuppressWarnings("unchecked")
    static RollbackPlayerItems capture(ServerPlayer player) {
        var capture = new Items(new PaperRollbackItemCodec(player.registryAccess()));
        var inventory = capture.slots(player.getInventory().getContainerSize(), player.getInventory()::getItem);
        var enderChest = capture.slots(player.getEnderChestInventory().getContainerSize(), player.getEnderChestInventory()::getItem);
        int use = capture.add(USE.get(player)), last = capture.add(LAST.get(player)), spin = SPIN.get(player) == null ? -1 : capture.add(SPIN.get(player));
        var equipment = new TreeMap<String, Integer>();
        var nativeEquipment = (Map<EquipmentSlot, ItemStack>) EQUIPMENT.get(player);
        nativeEquipment.keySet().stream().sorted(Comparator.comparing(Enum::name)).forEach(slot -> equipment.put(slot.name(), capture.add(nativeEquipment.get(slot))));
        var cooldowns = new TreeMap<String, RollbackPlayerItems.Cooldown>();
        if (player.getCooldowns().cooldowns.size() > RollbackPlayerItems.MAXIMUM_COOLDOWNS) throw new IllegalArgumentException("Player cooldown budget");
        player.getCooldowns().cooldowns.forEach((key, value) -> cooldowns.put(key.toString(), new RollbackPlayerItems.Cooldown(value.startTime(), value.endTime())));
        return new RollbackPlayerItems(capture.items, inventory, enderChest, player.getInventory().getSelectedSlot(), player.getInventory().getMaxStackSize(),
                use, last, spin, equipment, player.getCooldowns().tickCount, cooldowns);
    }

    public static void apply(PaperRollbackNativePlayerState state, RollbackPlayerItems items) {
        if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Import items at the player bootstrap boundary");
        state.use(value -> {
            if (!(value instanceof ServerPlayer player)) throw new IllegalArgumentException("Items require a private server player");
            applyTo(player, items); return null;
        });
    }
    @SuppressWarnings("unchecked")
    static void applyTo(ServerPlayer player, RollbackPlayerItems seed) {
        if (player.getInventory().getContainerSize() != seed.inventory().size() || player.getEnderChestInventory().getContainerSize() != seed.enderChest().size()) {
            throw new IllegalArgumentException("Native inventory layout changed");
        }
        var codec = new PaperRollbackItemCodec(player.registryAccess());
        var items = seed.items().stream().map(value -> { var stack = codec.decode(value.data()); if (!stack.isEmpty()) stack.setPopTime(value.popTime()); return stack; }).toList();
        var equipment = new EnumMap<EquipmentSlot, ItemStack>(EquipmentSlot.class);
        seed.lastEquipment().forEach((key, index) -> equipment.put(EquipmentSlot.valueOf(key), items.get(index)));
        var cooldowns = new HashMap<Identifier, ItemCooldowns.CooldownInstance>();
        seed.cooldowns().forEach((key, value) -> cooldowns.put(Identifier.parse(key), new ItemCooldowns.CooldownInstance(value.startTick(), value.endTick())));
        for (int slot = 0; slot < seed.inventory().size(); slot++) player.getInventory().setItem(slot, items.get(seed.inventory().get(slot)));
        for (int slot = 0; slot < seed.enderChest().size(); slot++) player.getEnderChestInventory().setItem(slot, items.get(seed.enderChest().get(slot)));
        player.getInventory().setSelectedSlot(seed.selected()); player.getInventory().setMaxStackSize(seed.maximumStack());
        USE.set(player, items.get(seed.useItem())); LAST.set(player, items.get(seed.lastItem())); SPIN.set(player, seed.spinItem() == -1 ? null : items.get(seed.spinItem()));
        EQUIPMENT.get(player).clear(); EQUIPMENT.get(player).putAll(equipment);
        player.getCooldowns().cooldowns.clear(); player.getCooldowns().cooldowns.putAll(cooldowns); player.getCooldowns().tickCount = seed.cooldownTick();
    }

    private static final class Items {
        final PaperRollbackItemCodec codec;
        final IdentityHashMap<ItemStack, Integer> ids = new IdentityHashMap<>();
        final List<RollbackPlayerItems.Item> items = new ArrayList<>();
        int bytes;
        Items(PaperRollbackItemCodec codec) { this.codec = codec; }
        int add(ItemStack stack) {
            var existing = ids.get(stack); if (existing != null) return existing;
            if (items.size() >= RollbackPlayerItems.MAXIMUM_ITEMS) throw new IllegalArgumentException("Player item budget");
            var data = codec.encode(stack); bytes += data.size();
            if (bytes > RollbackPlayerItems.MAXIMUM_ITEM_BYTES) throw new IllegalArgumentException("Player item bytes");
            int id = items.size(); ids.put(stack, id); items.add(new RollbackPlayerItems.Item(data, stack.getPopTime())); return id;
        }
        List<Integer> slots(int size, IntFunction<ItemStack> source) {
            if (size > RollbackPlayerItems.MAXIMUM_SLOTS) throw new IllegalArgumentException("Player inventory budget");
            var result = new ArrayList<Integer>(size); for (int i = 0; i < size; i++) result.add(add(source.apply(i))); return result;
        }
    }
}
