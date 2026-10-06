package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.projectkorra.projectkorra.platform.fabric.FabricMC;
import com.projectkorra.projectkorra.platform.mc.Color;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.potion.PotionData;
import com.projectkorra.projectkorra.platform.mc.potion.PotionType;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeItems;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeItems.Field;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeItems.Kind;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.*;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Native component operations on owned stacks; no client world or profile-resolution calls. */
public final class FabricRollbackItems implements RollbackNativeItems.Access<ItemStack> {
    private final FabricRollbackItemCodec codec;
    private final RegistryWrapper.WrapperLookup registries;
    private final RegistryOps<NbtElement> ops;
    private final Map<String, Kind> kinds;

    public FabricRollbackItems(RegistryWrapper.WrapperLookup registries, Map<String, Kind> serverMetadataCatalog) {
        this.registries = Objects.requireNonNull(registries, "registries");
        codec = new FabricRollbackItemCodec(registries);
        ops = registries.getOps(NbtOps.INSTANCE);
        kinds = Map.copyOf(serverMetadataCatalog);
    }

    @Override public ItemStack importNative(ItemStack source) {
        if (source == null || source.isEmpty()) return create(Material.AIR, 0);
        return codec.decode(codec.encode(source));
    }
    @Override public ItemStack copyOwned(ItemStack source) {
        if (source == ItemStack.EMPTY) return create(Material.AIR, 0);
        int count = source.getCount();
        // Only privately owned stacks reach this method. Vanilla copy() discards the
        // underlying type/components at zero count, even though setCount can revive them.
        source.setCount(1);
        try {
            ItemStack copy = source.copy();
            if (copy == ItemStack.EMPTY) return create(Material.AIR, 0);
            copy.setCount(count);
            return copy;
        } finally { source.setCount(count); }
    }
    @Override public ItemStack create(Material type, int amount) {
        Identifier id = Identifier.ofVanilla(type.canonical().name().toLowerCase(Locale.ROOT));
        if (!Registries.ITEM.containsId(id)) throw new IllegalArgumentException("Not a native item: " + id);
        return new ItemStack(Registries.ITEM.get(id), amount);
    }
    @Override public Material type(ItemStack item) {
        Identifier id = Registries.ITEM.getId(item.getItem());
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
            return item.copyComponentsToNewStack(destination.getItem(), count);
        } finally { item.setCount(count); }
    }
    @Override public int amount(ItemStack item) { return item.getCount(); }
    @Override public void amount(ItemStack item, int amount) { item.setCount(amount); }
    @Override public short durability(ItemStack item) { return (short) item.getOrDefault(DataComponentTypes.DAMAGE, 0).intValue(); }
    @Override public void durability(ItemStack item, short damage) {
        if (damage < 0) throw new IllegalArgumentException("Negative item damage");
        item.set(DataComponentTypes.DAMAGE, (int) damage);
    }
    @Override public boolean isEmpty(ItemStack item) { return item.isEmpty(); }
    @Override public boolean similar(ItemStack first, ItemStack second) { return ItemStack.areItemsAndComponentsEqual(first, second); }
    @Override public int maximumStack(ItemStack item) { return item.getMaxCount(); }
    @Override public boolean hasMeta(ItemStack item) { return !item.getComponentChanges().isEmpty(); }
    @Override public Kind kind(ItemStack item) {
        String id = Registries.ITEM.getId(item.getItem()).toString();
        Kind kind = kinds.get(id);
        if (kind == null) throw new IllegalArgumentException("Item is missing from the authority's metadata catalog: " + id);
        return kind;
    }

    @Override public Object field(ItemStack item, Field field) {
        return switch (field) {
            case NAME -> legacy(item.get(DataComponentTypes.CUSTOM_NAME));
            case LORE -> item.getOrDefault(DataComponentTypes.LORE, LoreComponent.DEFAULT).lines().stream().map(FabricRollbackItems::legacy).toList();
            case MODEL -> {
                Float first = item.getOrDefault(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelDataComponent.DEFAULT).getFloat(0);
                yield first == null ? null : first.intValue();
            }
            case DAMAGE -> item.getOrDefault(DataComponentTypes.DAMAGE, 0);
            case COLOR -> Color.fromRGB(item.getOrDefault(DataComponentTypes.DYED_COLOR, new DyedColorComponent(0xA06540)).rgb());
            case POTION -> new PotionData(PotionType.valueOf(item.getOrDefault(DataComponentTypes.POTION_CONTENTS, PotionContentsComponent.DEFAULT)
                    .potion().flatMap(value -> value.getKey()).map(key -> key.getValue().getPath().toUpperCase(Locale.ROOT)).orElse("WATER")));
            case PROFILE_ID -> profileId(profile(item));
            case TEXTURE -> texture(profile(item));
            case TEXTURE_URL -> textureUrl(texture(profile(item)));
        };
    }
    @Override public Map<String, String> customData(ItemStack item) {
        NbtCompound custom = item.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt();
        NbtCompound values = custom.getCompound("PublicBukkitValues").orElseGet(NbtCompound::new);
        Map<String, String> result = new TreeMap<>();
        for (String key : values.getKeys()) values.getString(key).ifPresent(value -> result.put(key, value));
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
                    if (value == null || ((String) value).isEmpty()) meta.remove(DataComponentTypes.CUSTOM_NAME);
                    else meta.set(DataComponentTypes.CUSTOM_NAME, FabricMC.legacyText((String) value));
                }
                case LORE -> {
                    @SuppressWarnings("unchecked") List<String> lore = (List<String>) value;
                    if (lore.isEmpty()) meta.remove(DataComponentTypes.LORE);
                    else meta.set(DataComponentTypes.LORE, new LoreComponent(lore.stream().map(FabricMC::legacyText).toList()));
                }
                case MODEL -> {
                    if (value == null) meta.remove(DataComponentTypes.CUSTOM_MODEL_DATA);
                    else meta.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(List.of(((Integer) value).floatValue()), List.of(), List.of(), List.of()));
                }
                case DAMAGE -> meta.set(DataComponentTypes.DAMAGE, (Integer) value);
                case COLOR -> {
                    if (value == null) meta.remove(DataComponentTypes.DYED_COLOR);
                    else meta.set(DataComponentTypes.DYED_COLOR, new DyedColorComponent(((Color) value).asRGB()));
                }
                case POTION -> {
                    PotionContentsComponent previous = meta.getOrDefault(DataComponentTypes.POTION_CONTENTS, PotionContentsComponent.DEFAULT);
                    if (value == null) meta.set(DataComponentTypes.POTION_CONTENTS,
                            new PotionContentsComponent(Optional.empty(), previous.customColor(), previous.customEffects(), previous.customName()));
                    else {
                        Identifier id = Identifier.ofVanilla(((PotionData) value).getType().name().toLowerCase(Locale.ROOT));
                        var potion = registries.getOrThrow(RegistryKeys.POTION).getOrThrow(net.minecraft.registry.RegistryKey.of(RegistryKeys.POTION, id));
                        meta.set(DataComponentTypes.POTION_CONTENTS, previous.with(potion));
                    }
                }
                case PROFILE_ID, TEXTURE, TEXTURE_URL -> { /* Apply related profile fields together below. */ }
            }
        }
        if (!customChanges.isEmpty()) {
            NbtCompound custom = meta.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt();
            NbtCompound values = custom.getCompound("PublicBukkitValues").orElseGet(NbtCompound::new);
            customChanges.forEach((key, value) -> {
                if (Identifier.tryParse(key) == null) throw new IllegalArgumentException("Invalid persistent data key: " + key);
                if (value == null) values.remove(key); else values.putString(key, value);
            });
            if (values.isEmpty()) custom.remove("PublicBukkitValues"); else custom.put("PublicBukkitValues", values);
            if (custom.isEmpty()) meta.remove(DataComponentTypes.CUSTOM_DATA); else meta.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(custom));
        }
        if (fields.containsKey(Field.PROFILE_ID) || fields.containsKey(Field.TEXTURE) || fields.containsKey(Field.TEXTURE_URL)) applyProfile(meta, fields);
        return new ItemStack(target.getRegistryEntry(), target.getCount(), meta.getComponentChanges());
    }

    private NbtCompound profile(ItemStack item) {
        ProfileComponent profile = item.get(DataComponentTypes.PROFILE);
        if (profile == null) return new NbtCompound();
        NbtElement encoded = ProfileComponent.CODEC.encodeStart(ops, profile).getOrThrow(IllegalArgumentException::new);
        if (encoded instanceof NbtCompound compound) return compound.copy();
        // The codec also accepts an unresolved profile name as a compact string.
        NbtCompound compound = new NbtCompound();
        compound.putString("name", encoded.asString().orElseThrow());
        return compound;
    }
    private static UUID profileId(NbtCompound profile) {
        int[] id = profile.getIntArray("id").orElse(null);
        return id == null || id.length != 4 ? null : new UUID(((long) id[0] << 32) | (id[1] & 0xffffffffL), ((long) id[2] << 32) | (id[3] & 0xffffffffL));
    }
    private static String texture(NbtCompound profile) {
        var properties = profile.getList("properties");
        if (properties.isEmpty()) return "";
        for (var entry : properties.get()) {
            if (entry instanceof NbtCompound property && property.getString("name").orElse("").equals("textures")) return property.getString("value").orElse("");
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
        NbtCompound profile = profile(item);
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
            var properties = profile.getList("properties").orElseGet(net.minecraft.nbt.NbtList::new).copy();
            properties.removeIf(entry -> entry instanceof NbtCompound property && property.getString("name").orElse("").equals("textures"));
            if (!texture.isBlank()) {
                NbtCompound property = new NbtCompound();
                property.putString("name", "textures"); property.putString("value", texture);
                properties.add(property);
            }
            if (properties.isEmpty()) profile.remove("properties"); else profile.put("properties", properties);
        }
        if (profile.isEmpty()) item.remove(DataComponentTypes.PROFILE);
        else item.set(DataComponentTypes.PROFILE, ProfileComponent.CODEC.parse(ops, profile).getOrThrow(IllegalArgumentException::new));
    }

    private static String legacy(Text text) {
        if (text == null) return "";
        // Follow CraftChatMessage's common-API projection: visit raw component nodes
        // in order and retain its formatting/reset order, including empty styled nodes.
        StringBuilder result = new StringBuilder();
        ArrayDeque<Text> pending = new ArrayDeque<>();
        pending.push(text);
        boolean formatted = false;
        while (!pending.isEmpty()) {
            Text part = pending.pop();
            Style style = part.getStyle();
            if (part.getContent() != net.minecraft.text.PlainTextContent.EMPTY || style.getColor() != null) {
                if (style.getColor() != null) {
                    Formatting formatting = Formatting.byName(style.getColor().getName());
                    if (formatting != null) result.append(formatting);
                    else {
                        result.append('\u00a7').append('x');
                        for (char hex : String.format(Locale.ROOT, "%06x", style.getColor().getRgb()).toCharArray()) result.append('\u00a7').append(hex);
                    }
                    formatted = true;
                } else if (formatted) {
                    result.append(Formatting.RESET);
                    formatted = false;
                }
            }
            if (style.isBold()) { result.append(Formatting.BOLD); formatted = true; }
            if (style.isItalic()) { result.append(Formatting.ITALIC); formatted = true; }
            if (style.isUnderlined()) { result.append(Formatting.UNDERLINE); formatted = true; }
            if (style.isStrikethrough()) { result.append(Formatting.STRIKETHROUGH); formatted = true; }
            if (style.isObfuscated()) { result.append(Formatting.OBFUSCATED); formatted = true; }
            part.getContent().visit(content -> { result.append(content); return Optional.empty(); });
            List<Text> children = part.getSiblings();
            for (int index = children.size() - 1; index >= 0; index--) pending.push(children.get(index));
        }
        return result.toString();
    }
}
