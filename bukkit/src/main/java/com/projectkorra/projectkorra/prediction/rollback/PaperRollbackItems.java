package com.projectkorra.projectkorra.prediction.rollback;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.projectkorra.projectkorra.platform.mc.Color;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.potion.PotionData;
import com.projectkorra.projectkorra.platform.mc.potion.PotionType;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeItems;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeItems.Field;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeItems.Kind;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.*;
import net.minecraft.world.item.alchemy.PotionContents;
import org.bukkit.craftbukkit.util.CraftChatMessage;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Native component operations on owned stacks; no client world or profile-resolution calls. */
public final class PaperRollbackItems implements RollbackNativeItems.Access<ItemStack> {
    private final PaperRollbackItemCodec codec;
    private final HolderLookup.Provider registries;
    private final RegistryOps<Tag> ops;
    private final Map<String, Kind> kinds;

    public PaperRollbackItems(HolderLookup.Provider registries) { this(registries, PaperRollbackItemCatalog.capture()); }

    public PaperRollbackItems(HolderLookup.Provider registries, Map<String, Kind> serverMetadataCatalog) {
        this.registries = Objects.requireNonNull(registries, "registries");
        codec = new PaperRollbackItemCodec(registries);
        ops = registries.createSerializationContext(NbtOps.INSTANCE);
        kinds = Map.copyOf(serverMetadataCatalog);
    }

    @Override public ItemStack importNative(ItemStack source) {
        if (source == null || source.isEmpty()) return create(Material.AIR, 0);
        return codec.decode(codec.encode(source));
    }
    @Override public ItemStack copyOwned(ItemStack source) {
        if (source == ItemStack.EMPTY) return create(Material.AIR, 0);
        // Paper's force-copy retains raw type/components/count for empty aliases.
        return source.copy(true);
    }
    @Override public ItemStack create(Material type, int amount) {
        Identifier id = Identifier.parse(type.canonical().name().toLowerCase(Locale.ROOT));
        if (!BuiltInRegistries.ITEM.containsKey(id)) throw new IllegalArgumentException("Not a native item: " + id);
        return new ItemStack(BuiltInRegistries.ITEM.getValue(id), amount);
    }
    @Override public Material type(ItemStack item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item.getItem());
        if (!id.getNamespace().equals("minecraft")) throw new IllegalArgumentException("Unknown common item namespace: " + id);
        return Material.valueOf(id.getPath().toUpperCase(Locale.ROOT));
    }
    @Override public ItemStack type(ItemStack item, Material type) {
        if (type == Material.AIR) return create(Material.AIR, 0);
        ItemStack destination = create(type, 1);
        int count = item.getCount();
        item.setCount(1);
        try {
            // Native transmutation owns default components; preserve the explicit patch.
            if (item.isEmpty()) return destination;
            return item.transmuteCopy(destination.getItem(), count);
        } finally { item.setCount(count); }
    }
    @Override public int amount(ItemStack item) { return item.getCount(); }
    @Override public void amount(ItemStack item, int amount) { item.setCount(amount); }
    @Override public short durability(ItemStack item) { return (short) item.getOrDefault(DataComponents.DAMAGE, 0).intValue(); }
    @Override public void durability(ItemStack item, short damage) {
        if (damage < 0) throw new IllegalArgumentException("Negative item damage");
        item.set(DataComponents.DAMAGE, (int) damage);
    }
    @Override public boolean isEmpty(ItemStack item) { return item.isEmpty(); }
    @Override public boolean similar(ItemStack first, ItemStack second) { return ItemStack.isSameItemSameComponents(first, second); }
    @Override public int maximumStack(ItemStack item) { return item.getMaxStackSize(); }
    @Override public boolean hasMeta(ItemStack item) { return !item.getComponentsPatch().isEmpty(); }
    @Override public Kind kind(ItemStack item) {
        String id = BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
        Kind kind = kinds.get(id);
        if (kind == null) throw new IllegalArgumentException("Item is missing from the authority's metadata catalog: " + id);
        return kind;
    }

    @Override public Object field(ItemStack item, Field field) {
        return switch (field) {
            case NAME -> legacy(item.get(DataComponents.CUSTOM_NAME));
            case LORE -> item.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().stream().map(PaperRollbackItems::legacy).toList();
            case MODEL -> {
                Float first = item.getOrDefault(DataComponents.CUSTOM_MODEL_DATA, CustomModelData.EMPTY).getFloat(0);
                yield first == null ? null : first.intValue();
            }
            case DAMAGE -> item.getOrDefault(DataComponents.DAMAGE, 0);
            case COLOR -> Color.fromRGB(item.getOrDefault(DataComponents.DYED_COLOR, new DyedItemColor(0xA06540)).rgb());
            case POTION -> new PotionData(PotionType.valueOf(item.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)
                    .potion().flatMap(value -> value.unwrapKey()).map(key -> key.identifier().getPath().toUpperCase(Locale.ROOT)).orElse("WATER")));
            case PROFILE_ID -> profileId(profile(item));
            case TEXTURE -> texture(profile(item));
            case TEXTURE_URL -> textureUrl(texture(profile(item)));
        };
    }
    @Override public Map<String, String> customData(ItemStack item) {
        CompoundTag custom = item.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        CompoundTag values = custom.getCompound("PublicBukkitValues").orElseGet(CompoundTag::new);
        Map<String, String> result = new TreeMap<>();
        for (String key : values.keySet()) values.getString(key).ifPresent(value -> result.put(key, value));
        return Map.copyOf(result);
    }

    @Override public ItemStack clearMeta(ItemStack target) {
        if (target.isEmpty()) return null;
        return new ItemStack(target.getItem(), target.getCount());
    }
    @Override public ItemStack applyMeta(ItemStack target, ItemStack baseline, Map<Field, Object> fields, Map<String, String> customChanges) {
        if (target.isEmpty() || (kind(baseline) != Kind.GENERIC && kind(baseline) != kind(target))) return null;
        ItemStack meta = copyOwned(baseline);
        for (var entry : fields.entrySet()) {
            Object value = entry.getValue();
            switch (entry.getKey()) {
                case NAME -> {
                    if (value == null || ((String) value).isEmpty()) meta.remove(DataComponents.CUSTOM_NAME);
                    else meta.set(DataComponents.CUSTOM_NAME, parseText((String) value));
                }
                case LORE -> {
                    @SuppressWarnings("unchecked") List<String> lore = (List<String>) value;
                    if (lore.isEmpty()) meta.remove(DataComponents.LORE);
                    else meta.set(DataComponents.LORE, new ItemLore(lore.stream().map(PaperRollbackItems::parseText).toList()));
                }
                case MODEL -> {
                    if (value == null) meta.remove(DataComponents.CUSTOM_MODEL_DATA);
                    else meta.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(((Integer) value).floatValue()), List.of(), List.of(), List.of()));
                }
                case DAMAGE -> meta.set(DataComponents.DAMAGE, (Integer) value);
                case COLOR -> {
                    if (value == null) meta.remove(DataComponents.DYED_COLOR);
                    else meta.set(DataComponents.DYED_COLOR, new DyedItemColor(((Color) value).asRGB()));
                }
                case POTION -> {
                    PotionContents previous = meta.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
                    if (value == null) meta.set(DataComponents.POTION_CONTENTS,
                            new PotionContents(Optional.empty(), previous.customColor(), previous.customEffects(), previous.customName()));
                    else {
                        Identifier id = Identifier.parse(((PotionData) value).getType().name().toLowerCase(Locale.ROOT));
                        var potion = registries.lookupOrThrow(Registries.POTION).getOrThrow(ResourceKey.create(Registries.POTION, id));
                        meta.set(DataComponents.POTION_CONTENTS, previous.withPotion(potion));
                    }
                }
                case PROFILE_ID, TEXTURE, TEXTURE_URL -> { /* Apply related profile fields together below. */ }
            }
        }
        if (!customChanges.isEmpty()) {
            CompoundTag custom = meta.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            CompoundTag values = custom.getCompound("PublicBukkitValues").orElseGet(CompoundTag::new);
            customChanges.forEach((key, value) -> {
                if (Identifier.tryParse(key) == null) throw new IllegalArgumentException("Invalid persistent data key: " + key);
                if (value == null) values.remove(key); else values.putString(key, value);
            });
            if (values.isEmpty()) custom.remove("PublicBukkitValues"); else custom.put("PublicBukkitValues", values);
            if (custom.isEmpty()) meta.remove(DataComponents.CUSTOM_DATA); else meta.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
        }
        if (fields.containsKey(Field.PROFILE_ID) || fields.containsKey(Field.TEXTURE) || fields.containsKey(Field.TEXTURE_URL)) applyProfile(meta, fields);
        ItemStack result = new ItemStack(target.getItem(), target.getCount());
        result.restorePatch(meta.getComponentsPatch());
        return result;
    }

    private CompoundTag profile(ItemStack item) {
        ResolvableProfile profile = item.get(DataComponents.PROFILE);
        if (profile == null) return new CompoundTag();
        Tag encoded = ResolvableProfile.CODEC.encodeStart(ops, profile).getOrThrow(IllegalArgumentException::new);
        if (encoded instanceof CompoundTag compound) return compound.copy();
        // The codec also accepts an unresolved profile name as a compact string.
        CompoundTag compound = new CompoundTag();
        compound.putString("name", encoded.asString().orElseThrow());
        return compound;
    }
    private static UUID profileId(CompoundTag profile) {
        int[] id = profile.getIntArray("id").orElse(null);
        return id == null || id.length != 4 ? null : new UUID(((long) id[0] << 32) | (id[1] & 0xffffffffL), ((long) id[2] << 32) | (id[3] & 0xffffffffL));
    }
    private static String texture(CompoundTag profile) {
        var properties = profile.getList("properties");
        if (properties.isEmpty()) return "";
        for (var entry : properties.get()) {
            if (entry instanceof CompoundTag property && property.getString("name").orElse("").equals("textures")) return property.getString("value").orElse("");
        }
        return "";
    }
    private static String textureUrl(String encoded) {
        if (encoded.isBlank()) return "";
        try {
            return JsonParser.parseString(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)).getAsJsonObject()
                    .getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString();
        } catch (RuntimeException invalid) { return ""; } // Native profiles may contain no skin or an opaque signed value.
    }
    private void applyProfile(ItemStack item, Map<Field, Object> fields) {
        CompoundTag profile = profile(item);
        if (fields.containsKey(Field.PROFILE_ID)) {
            UUID id = (UUID) fields.get(Field.PROFILE_ID);
            if (id == null) profile.remove("id");
            else profile.putIntArray("id", new int[]{(int) (id.getMostSignificantBits() >> 32), (int) id.getMostSignificantBits(), (int) (id.getLeastSignificantBits() >> 32), (int) id.getLeastSignificantBits()});
        }
        if (fields.containsKey(Field.TEXTURE) || fields.containsKey(Field.TEXTURE_URL)) {
            String texture = fields.containsKey(Field.TEXTURE) ? Objects.toString(fields.get(Field.TEXTURE), "") : texture(profile);
            if (fields.containsKey(Field.TEXTURE_URL) && (!fields.containsKey(Field.TEXTURE) || texture.isBlank())) {
                String url = Objects.toString(fields.get(Field.TEXTURE_URL), "");
                JsonObject root;
                try { root = JsonParser.parseString(new String(Base64.getDecoder().decode(texture), StandardCharsets.UTF_8)).getAsJsonObject(); }
                catch (RuntimeException malformed) { root = new JsonObject(); }
                JsonObject textures = root.has("textures") && root.get("textures").isJsonObject() ? root.getAsJsonObject("textures") : new JsonObject();
                if (url.isBlank()) textures.remove("SKIN");
                else {
                    JsonObject skin = textures.has("SKIN") && textures.get("SKIN").isJsonObject() ? textures.getAsJsonObject("SKIN") : new JsonObject();
                    skin.addProperty("url", url); textures.add("SKIN", skin);
                }
                if (textures.isEmpty()) root.remove("textures"); else root.add("textures", textures);
                texture = root.isEmpty() ? "" : Base64.getEncoder().encodeToString(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            var properties = profile.getList("properties").orElseGet(net.minecraft.nbt.ListTag::new).copy();
            properties.removeIf(entry -> entry instanceof CompoundTag property && property.getString("name").orElse("").equals("textures"));
            if (!texture.isBlank()) {
                CompoundTag property = new CompoundTag();
                property.putString("name", "textures"); property.putString("value", texture);
                properties.add(property);
            }
            if (properties.isEmpty()) profile.remove("properties"); else profile.put("properties", properties);
        }
        if (profile.isEmpty()) item.remove(DataComponents.PROFILE);
        else item.set(DataComponents.PROFILE, ResolvableProfile.CODEC.parse(ops, profile).getOrThrow(IllegalArgumentException::new));
    }

    private static String legacy(Component text) { return text == null ? "" : CraftChatMessage.fromComponent(text); }
    private static Component parseText(String text) { return CraftChatMessage.fromStringOrEmpty(text == null ? "" : text, true); }
}
