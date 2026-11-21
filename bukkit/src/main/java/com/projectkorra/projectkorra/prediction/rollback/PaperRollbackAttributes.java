package com.projectkorra.projectkorra.prediction.rollback;

import net.kyori.adventure.key.Key;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.craftbukkit.attribute.CraftAttribute;
import org.bukkit.craftbukkit.attribute.CraftAttributeInstance;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Bukkit attribute views that always resolve the currently restored native instance. */
final class PaperRollbackAttributes {
    private final RollbackStateCell<?> owner;
    private final Supplier<Player> ownedPlayer;

    PaperRollbackAttributes(RollbackStateCell<?> owner, Supplier<Player> ownedPlayer) {
        this.owner = owner;
        this.ownedPlayer = ownedPlayer;
    }

    @SuppressWarnings("WrapperReferenceEquality")
    AttributeInstance get(Attribute attribute) {
        Player player = ownedPlayer.get();
        Holder<net.minecraft.world.entity.ai.attributes.Attribute> holder =
                CraftAttribute.bukkitToMinecraftHolder(Objects.requireNonNull(attribute, "attribute"));
        var key = holder.unwrapKey().orElseThrow(() -> new IllegalArgumentException("Unregistered native attribute"));
        if (BuiltInRegistries.ATTRIBUTE.getOrThrow(key) != holder) throw new IllegalArgumentException("Foreign native attribute registry");
        return player.getAttribute(holder) == null ? null : new View(attribute, holder);
    }

    private final class View implements AttributeInstance, RollbackStateCell<Void> {
        private final Attribute attribute;
        private final Holder<net.minecraft.world.entity.ai.attributes.Attribute> holder;

        View(Attribute attribute, Holder<net.minecraft.world.entity.ai.attributes.Attribute> holder) {
            this.attribute = attribute;
            this.holder = holder;
        }

        private CraftAttributeInstance current() {
            var instance = ownedPlayer.get().getAttribute(holder);
            if (instance == null) throw new IllegalStateException("Native attribute is no longer present");
            return new CraftAttributeInstance(instance, attribute);
        }

        @Override public Attribute getAttribute() { ownedPlayer.get(); return attribute; }
        @Override public double getBaseValue() { return current().getBaseValue(); }
        @Override public void setBaseValue(double value) { current().setBaseValue(value); }
        @Override public double getValue() { return current().getValue(); }
        @Override public double getDefaultValue() { return current().getDefaultValue(); }
        @Override public Collection<AttributeModifier> getModifiers() { return current().getModifiers(); }
        @Override public AttributeModifier getModifier(Key key) { return current().getModifier(key); }
        @Override public void removeModifier(Key key) { current().removeModifier(key); }
        @Override public AttributeModifier getModifier(UUID id) { return current().getModifier(id); }
        @Override public void removeModifier(UUID id) { current().removeModifier(id); }
        @Override public void addModifier(AttributeModifier modifier) { current().addModifier(modifier); }
        @Override public void addTransientModifier(AttributeModifier modifier) { current().addTransientModifier(modifier); }
        @Override public void removeModifier(AttributeModifier modifier) { current().removeModifier(modifier); }

        // Listener code can retain an attribute view as checkpoint state. Its
        // identity survives; the native instance itself may have been discarded.
        @Override public Void captureRollbackState() { ownedPlayer.get(); return null; }
        @Override public void restoreRollbackState(Void ignored) { ownedPlayer.get(); }
        @Override public List<?> rollbackReferences() { ownedPlayer.get(); return List.of(owner); }
    }
}
