package com.projectkorra.projectkorra.prediction.rollback;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Shared frozen native definitions; mutable stacks, components and attribute instances remain graph state. */
final class PaperRollbackMetadata {
    private static final Object ENCHANTMENT_ORDER = PaperRollbackPrivateAccess.enchantmentOrder();
    private static final Object DEFAULT_ATTRIBUTES = DefaultAttributes.getSupplier(EntityType.PLAYER);
    private static final Map<Object, Boolean> ITEM_PROTOTYPES = itemPrototypes();
    // Adventure's immutable enum-to-enum map lazily caches collection views.
    private static final Class<?> DECORATIONS = net.kyori.adventure.text.format.Style.empty().decorations().getClass();
    private static final List<Class<?>> TYPES = List.of(EntityType.class, Holder.Reference.class,
            EntityDataAccessor.class, DataComponentType.class, Item.class, BlockState.class,
            Codec.class, MapCodec.class, java.util.Locale.class, DECORATIONS);

    private PaperRollbackMetadata() { }

    private static Map<Object, Boolean> itemPrototypes() {
        var prototypes = new IdentityHashMap<Object, Boolean>();
        for (Item item : BuiltInRegistries.ITEM) prototypes.put(item.components(), true);
        return Collections.unmodifiableMap(prototypes);
    }

    static boolean frozen(Object value) {
        return value == ItemStack.EMPTY || value == ENCHANTMENT_ORDER || value == DEFAULT_ATTRIBUTES
                || ITEM_PROTOTYPES.containsKey(value) || TYPES.stream().anyMatch(type -> type.isInstance(value));
    }
}
