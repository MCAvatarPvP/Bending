package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.platform.mc.Color;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.inventory.meta.*;
import com.projectkorra.projectkorra.platform.mc.potion.PotionData;
import com.projectkorra.projectkorra.platform.mc.potion.PotionType;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateGraph;

import java.util.*;

/**
 * Existing ItemStack/ItemMeta APIs over privately owned native state. Metadata views
 * retain a complete native baseline and record explicit field edits, preserving
 * components and formatted text that the common API cannot otherwise represent.
 */
public final class RollbackNativeItems<N> implements RollbackItems {
    public enum Kind { GENERIC, LEATHER, POTION, SKULL }
    public enum Field { NAME, LORE, MODEL, DAMAGE, COLOR, POTION, PROFILE_ID, TEXTURE, TEXTURE_URL }

    /**
     * Native component operations, with no live inventory, network or profile lookup.
     * importNative must detach all data from an external item. copyOwned must retain
     * underlying item/components even at zero count; its input is never a live item.
     * applyMeta returns an owned replacement or null if metadata is inapplicable.
     * Metadata getters must return detached values; only immutable values leave them.
     */
    public interface Access<N> {
        N importNative(N source);
        N copyOwned(N source);
        N create(Material type, int amount);
        Material type(N item);
        N type(N item, Material type);
        int amount(N item);
        void amount(N item, int amount);
        short durability(N item);
        void durability(N item, short damage);
        boolean isEmpty(N item);
        boolean similar(N first, N second);
        int maximumStack(N item);
        boolean hasMeta(N item);
        Kind kind(N item);
        Object field(N item, Field field);
        Map<String, String> customData(N item);
        N applyMeta(N target, N baseline, Map<Field, Object> fields, Map<String, String> customChanges);
        N clearMeta(N target);
    }

    /**
     * Binding supplied only by a private native inventory. Checkpoints restore the
     * original stack in place, including aliases retained after it leaves a slot.
     * overwrite preserves native object identity; neither method may touch live items.
     */
    public interface MirrorOwner<N> {
        void requireOwner();
        RollbackStateGraph.Snapshot capture(N item);
        void overwrite(N target, N replacement);
        RollbackStateCell<?> root();
    }

    private final Access<N> access;
    private final Thread thread = Thread.currentThread();
    public RollbackNativeItems(Access<N> access) { this.access = Objects.requireNonNull(access, "access"); }
    /** Native capture adapters validate ownership before reading even an entirely empty inventory. */
    public void requireOwnerThread() { checkThread(); }

    public ItemStack capture(N nativeItem) {
        checkThread();
        N owned = Objects.requireNonNull(access.importNative(nativeItem), "native item");
        access.type(owned); // Fail capture now if the common material registry cannot represent the item.
        return new Item(owned);
    }
    public ItemStack create(Material type, int amount) {
        checkThread();
        return new Item(access.create(Objects.requireNonNull(type, "type"), amount));
    }
    public ItemStack mirror(N item, MirrorOwner<N> owner) {
        checkThread();
        Objects.requireNonNull(owner, "owner").requireOwner();
        Objects.requireNonNull(item, "item");
        // Never let an empty-hand view mutate the shared native EMPTY singleton.
        if (access.isEmpty(item)) return emptyStack();
        access.type(item);
        return new Item(item, owner);
    }
    /** A detached native copy for the physics adapter or a finalized effect. */
    public N nativeCopy(ItemStack item) {
        checkThread();
        // Native ItemStack.copy() may share immutable-by-convention component objects.
        // Re-import before exposing a copy to code that can reach native mutable data.
        return access.importNative(logical(item).value);
    }
    @Override public ItemStack copy(ItemStack source) { checkThread(); return logical(source).clone(); }
    @Override public ItemStack emptyStack() { return create(Material.AIR, 0); }
    @Override public boolean isEmpty(ItemStack item) { checkThread(); return access.isEmpty(logical(item).value); }
    @Override public boolean similar(ItemStack first, ItemStack second) {
        checkThread();
        return second != null && access.similar(logical(first).value, logical(second).value);
    }
    @Override public int maximumStack(ItemStack item) { checkThread(); return access.maximumStack(logical(item).value); }

    @SuppressWarnings("unchecked")
    private Item logical(ItemStack source) {
        Objects.requireNonNull(source, "item");
        source = source.simulationView();
        if (source instanceof RollbackNativeItems<?>.Item candidate) {
            if (candidate.owner() != this) throw new IllegalArgumentException("Item belongs to another native item context");
            candidate.checkItem();
            return (Item) candidate;
        }
        if (source.getClass() != ItemStack.class) throw new IllegalArgumentException("Unrecognized native item wrapper");
        Item item = new Item(access.create(source.getType() == null ? Material.AIR : source.getType(), source.getAmount()));
        if (source.getDurability() != 0) item.setDurability(source.getDurability());
        if (source.hasItemMeta() && !item.setItemMeta(source.getItemMeta())) throw new IllegalArgumentException("Inapplicable item metadata");
        return item;
    }

    private final class Item extends ItemStack implements RollbackStateCell<ItemState> {
        private N value;
        private final MirrorOwner<N> mirror;
        private Item(N value) { this(value, null); }
        private Item(N value, MirrorOwner<N> mirror) {
            this.value = Objects.requireNonNull(value, "value");
            this.mirror = mirror;
        }
        private void checkItem() { checkThread(); if (mirror != null) mirror.requireOwner(); }
        private void replace(N replacement) {
            if (mirror == null) value = replacement;
            else mirror.overwrite(value, replacement);
        }
        private RollbackNativeItems<N> owner() { return RollbackNativeItems.this; }
        @Override public Material getType() { checkItem(); return access.type(value); }
        @Override public void setType(Material type) {
            checkItem();
            if (getType().canonical() == Objects.requireNonNull(type, "type").canonical()) return;
            N replacement = Objects.requireNonNull(access.type(value, Objects.requireNonNull(type, "type")), "item");
            // CraftItemStack.setType(AIR) detaches its handle, leaving the slot intact.
            if (type.canonical() == Material.AIR) value = replacement;
            else replace(replacement);
        }
        @Override public int getAmount() { checkItem(); return access.amount(value); }
        @Override public void setAmount(int amount) { checkItem(); access.amount(value, amount); }
        @Override public short getDurability() { checkItem(); return access.durability(value); }
        @Override public void setDurability(short damage) { checkItem(); access.durability(value, damage); }
        @Override public boolean hasItemMeta() { checkItem(); return access.hasMeta(value); }
        @Override public ItemMeta getItemMeta() {
            checkItem();
            if (access.type(value) == Material.AIR) return null;
            return metadata(new Metadata(access.copyOwned(value)));
        }
        @Override public boolean setItemMeta(ItemMeta meta) {
            checkItem();
            if (meta == null) {
                N cleared = access.clearMeta(value);
                if (cleared == null) return false;
                replace(cleared);
                return true;
            }
            Metadata state;
            if (meta instanceof NativeMeta nativeMeta) {
                if (nativeMeta.context() != RollbackNativeItems.this) throw new IllegalArgumentException("Metadata belongs to another native context");
                @SuppressWarnings("unchecked") Metadata owned = (Metadata) nativeMeta.state();
                state = owned;
            } else {
                if (meta.getClass() != ItemMeta.class && meta.getClass() != LeatherArmorMeta.class
                        && meta.getClass() != PotionMeta.class && meta.getClass() != SkullMeta.class) {
                    throw new IllegalArgumentException("Unrecognized native metadata wrapper");
                }
                state = new Metadata(access.create(getType(), 1));
                state.set(Field.NAME, meta.getDisplayName());
                state.set(Field.LORE, meta.getLore());
                state.set(Field.MODEL, meta.getCustomModelData());
                meta.getCustomData().forEach(state::custom);
                Kind kind = access.kind(state.baseline);
                if (kind == Kind.LEATHER && meta instanceof LeatherArmorMeta leather) state.set(Field.COLOR, leather.getColor());
                if (kind == Kind.POTION && meta instanceof PotionMeta potion) state.set(Field.POTION, potion.getBasePotionData());
                if (kind == Kind.SKULL && meta instanceof SkullMeta skull) {
                    state.set(Field.PROFILE_ID, skull.getProfileId());
                    state.set(Field.TEXTURE, skull.getTexture());
                    state.set(Field.TEXTURE_URL, skull.getTextureUrl());
                }
            }
            N replacement = access.applyMeta(value, state.baseline, state.fields, state.custom);
            if (replacement == null) return false;
            replace(replacement);
            return true;
        }
        @Override public boolean isSimilar(ItemStack other) { return RollbackNativeItems.this.similar(this, other); }
        @Override public Item clone() { checkItem(); return new Item(access.copyOwned(value)); }
        @Override public ItemState captureRollbackState() {
            checkItem();
            return new ItemState(this, mirror == null ? access.copyOwned(value) : value,
                    mirror == null ? null : mirror.capture(value));
        }
        @Override public void restoreRollbackState(ItemState state) {
            checkItem();
            if (state.owner != this) throw new IllegalArgumentException("Item checkpoint belongs to another item");
            if (mirror == null) value = access.copyOwned(state.value);
            else { value = state.value; state.nativeState.restore(); }
        }
        @Override public Collection<?> rollbackReferences() { checkItem(); return mirror == null ? List.of() : List.of(mirror.root()); }
    }

    private final class ItemState {
        private final Item owner;
        private final N value;
        private final RollbackStateGraph.Snapshot nativeState;
        private ItemState(Item owner, N value, RollbackStateGraph.Snapshot nativeState) {
            this.owner = owner; this.value = value; this.nativeState = nativeState;
        }
    }

    private interface NativeMeta {
        Object context();
        Object state();
    }
    private final class Metadata implements RollbackStateCell<MetadataState> {
        private final N baseline;
        private Map<Field, Object> fields = Map.of();
        private Map<String, String> custom = Map.of();
        private Metadata(N baseline) { this.baseline = baseline; }
        private Object get(Field field) {
            checkThread();
            return fields.containsKey(field) ? fields.get(field) : detached(field, access.field(baseline, field));
        }
        private void set(Field field, Object value) {
            checkThread();
            Map<Field, Object> next = new EnumMap<>(Field.class);
            next.putAll(fields);
            next.put(field, detached(field, value));
            fields = Collections.unmodifiableMap(next);
        }
        private Map<String, String> custom() {
            checkThread();
            Map<String, String> values = new LinkedHashMap<>(access.customData(baseline));
            custom.forEach((key, value) -> { if (value == null) values.remove(key); else values.put(key, value); });
            return Map.copyOf(values);
        }
        private void custom(String key, String value) {
            checkThread();
            if (key == null || key.isBlank()) return;
            Map<String, String> next = new LinkedHashMap<>(custom);
            next.put(key, value);
            custom = Collections.unmodifiableMap(next);
        }
        @Override public MetadataState captureRollbackState() { checkThread(); return new MetadataState(this, fields, custom); }
        @Override public void restoreRollbackState(MetadataState state) {
            checkThread();
            if (state.owner != this) throw new IllegalArgumentException("Metadata checkpoint belongs to another view");
            fields = state.fields; custom = state.custom;
        }
    }

    private final class MetadataState {
        private final Metadata owner;
        private final Map<Field, Object> fields;
        private final Map<String, String> custom;
        private MetadataState(Metadata owner, Map<Field, Object> fields, Map<String, String> custom) {
            this.owner = owner; this.fields = fields; this.custom = custom;
        }
    }

    private ItemMeta metadata(Metadata state) {
        return switch (access.kind(state.baseline)) {
            case GENERIC -> new GenericMeta(state);
            case LEATHER -> new LeatherMeta(state);
            case POTION -> new PotionView(state);
            case SKULL -> new SkullView(state);
        };
    }

    private static Object detached(Field field, Object value) {
        if (value == null) return field == Field.LORE ? List.of() : null;
        return switch (field) {
            case NAME, TEXTURE, TEXTURE_URL -> (String) value;
            case LORE -> {
                List<?> list = (List<?>) value;
                if (list.size() > 1_024) throw new IllegalArgumentException("Lore budget");
                yield list.stream().map(String.class::cast).toList();
            }
            case MODEL, DAMAGE -> (Integer) value;
            case PROFILE_ID -> (UUID) value;
            case COLOR -> Color.fromRGB(((Color) value).asRGB());
            case POTION -> new PotionData(PotionType.valueOf(((PotionData) value).getType().name()));
        };
    }
    private void checkThread() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Native item view crossed threads");
    }

    private final class GenericMeta extends ItemMeta implements NativeMeta, Damageable, RollbackStateCell<Void> {
        private final Metadata data;
        private GenericMeta(Metadata data) { this.data = data; }
        @Override public Object context() { return RollbackNativeItems.this; }
        @Override public Object state() { return data; }
        @Override public String getDisplayName() { return (String) data.get(Field.NAME); }
        @Override public void setDisplayName(String value) { data.set(Field.NAME, value); }
        @SuppressWarnings("unchecked")
        @Override public List<String> getLore() { return (List<String>) data.get(Field.LORE); }
        @Override public void setLore(List<String> value) { data.set(Field.LORE, value); }
        @Override public Integer getCustomModelData() { return (Integer) data.get(Field.MODEL); }
        @Override public void setCustomModelData(Integer value) { data.set(Field.MODEL, value); }
        @Override public String getCustomData(String key) { return key == null ? null : data.custom().get(key); }
        @Override public Map<String, String> getCustomData() { return data.custom(); }
        @Override public void setCustomData(String key, String value) { data.custom(key, value); }
        @Override public int getDamage() { return (Integer) data.get(Field.DAMAGE); }
        @Override public void setDamage(int value) {
            if (value < 0) throw new IllegalArgumentException("Negative item damage");
            data.set(Field.DAMAGE, value);
        }
        @Override public Void captureRollbackState() { checkThread(); return null; }
        @Override public void restoreRollbackState(Void ignored) { checkThread(); }
        @Override public Collection<?> rollbackReferences() { checkThread(); return List.of(data); }
    }

    private final class LeatherMeta extends LeatherArmorMeta implements NativeMeta, Damageable, RollbackStateCell<Void> {
        private final Metadata data;
        private LeatherMeta(Metadata data) { this.data = data; }
        @Override public Object context() { return RollbackNativeItems.this; }
        @Override public Object state() { return data; }
        @Override public String getDisplayName() { return (String) data.get(Field.NAME); }
        @Override public void setDisplayName(String value) { data.set(Field.NAME, value); }
        @SuppressWarnings("unchecked")
        @Override public List<String> getLore() { return (List<String>) data.get(Field.LORE); }
        @Override public void setLore(List<String> value) { data.set(Field.LORE, value); }
        @Override public Integer getCustomModelData() { return (Integer) data.get(Field.MODEL); }
        @Override public void setCustomModelData(Integer value) { data.set(Field.MODEL, value); }
        @Override public String getCustomData(String key) { return key == null ? null : data.custom().get(key); }
        @Override public Map<String, String> getCustomData() { return data.custom(); }
        @Override public void setCustomData(String key, String value) { data.custom(key, value); }
        @Override public int getDamage() { return (Integer) data.get(Field.DAMAGE); }
        @Override public void setDamage(int value) {
            if (value < 0) throw new IllegalArgumentException("Negative item damage");
            data.set(Field.DAMAGE, value);
        }
        @Override public Color getColor() { return (Color) data.get(Field.COLOR); }
        @Override public void setColor(Color color) { data.set(Field.COLOR, color); }
        @Override public Void captureRollbackState() { checkThread(); return null; }
        @Override public void restoreRollbackState(Void ignored) { checkThread(); }
        @Override public Collection<?> rollbackReferences() { checkThread(); return List.of(data); }
    }

    private final class PotionView extends PotionMeta implements NativeMeta, Damageable, RollbackStateCell<Void> {
        private final Metadata data;
        private PotionView(Metadata data) { this.data = data; }
        @Override public Object context() { return RollbackNativeItems.this; }
        @Override public Object state() { return data; }
        @Override public String getDisplayName() { return (String) data.get(Field.NAME); }
        @Override public void setDisplayName(String value) { data.set(Field.NAME, value); }
        @SuppressWarnings("unchecked")
        @Override public List<String> getLore() { return (List<String>) data.get(Field.LORE); }
        @Override public void setLore(List<String> value) { data.set(Field.LORE, value); }
        @Override public Integer getCustomModelData() { return (Integer) data.get(Field.MODEL); }
        @Override public void setCustomModelData(Integer value) { data.set(Field.MODEL, value); }
        @Override public String getCustomData(String key) { return key == null ? null : data.custom().get(key); }
        @Override public Map<String, String> getCustomData() { return data.custom(); }
        @Override public void setCustomData(String key, String value) { data.custom(key, value); }
        @Override public int getDamage() { return (Integer) data.get(Field.DAMAGE); }
        @Override public void setDamage(int value) {
            if (value < 0) throw new IllegalArgumentException("Negative item damage");
            data.set(Field.DAMAGE, value);
        }
        @Override public PotionData getBasePotionData() { return (PotionData) data.get(Field.POTION); }
        @Override public void setBasePotionData(PotionData value) { data.set(Field.POTION, value); }
        @Override public Void captureRollbackState() { checkThread(); return null; }
        @Override public void restoreRollbackState(Void ignored) { checkThread(); }
        @Override public Collection<?> rollbackReferences() { checkThread(); return List.of(data); }
    }

    private final class SkullView extends SkullMeta implements NativeMeta, Damageable, RollbackStateCell<Void> {
        private final Metadata data;
        private SkullView(Metadata data) { this.data = data; }
        @Override public Object context() { return RollbackNativeItems.this; }
        @Override public Object state() { return data; }
        @Override public String getDisplayName() { return (String) data.get(Field.NAME); }
        @Override public void setDisplayName(String value) { data.set(Field.NAME, value); }
        @SuppressWarnings("unchecked")
        @Override public List<String> getLore() { return (List<String>) data.get(Field.LORE); }
        @Override public void setLore(List<String> value) { data.set(Field.LORE, value); }
        @Override public Integer getCustomModelData() { return (Integer) data.get(Field.MODEL); }
        @Override public void setCustomModelData(Integer value) { data.set(Field.MODEL, value); }
        @Override public String getCustomData(String key) { return key == null ? null : data.custom().get(key); }
        @Override public Map<String, String> getCustomData() { return data.custom(); }
        @Override public void setCustomData(String key, String value) { data.custom(key, value); }
        @Override public int getDamage() { return (Integer) data.get(Field.DAMAGE); }
        @Override public void setDamage(int value) {
            if (value < 0) throw new IllegalArgumentException("Negative item damage");
            data.set(Field.DAMAGE, value);
        }
        @Override public UUID getProfileId() { return (UUID) data.get(Field.PROFILE_ID); }
        @Override public void setProfileId(UUID value) { data.set(Field.PROFILE_ID, value); }
        @Override public String getTexture() { return (String) data.get(Field.TEXTURE); }
        @Override public void setTexture(String value) { data.set(Field.TEXTURE, value == null ? "" : value); }
        @Override public String getTextureUrl() { return (String) data.get(Field.TEXTURE_URL); }
        @Override public void setTextureUrl(String value) { data.set(Field.TEXTURE_URL, value == null ? "" : value); }
        @Override public Void captureRollbackState() { checkThread(); return null; }
        @Override public void restoreRollbackState(Void ignored) { checkThread(); }
        @Override public Collection<?> rollbackReferences() { checkThread(); return List.of(data); }
    }
}
