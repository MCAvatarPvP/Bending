package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.object.HorizontalVelocityTracker;
import com.projectkorra.projectkorra.platform.mc.entity.*;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.util.*;
import java.util.*;

/** Movement, collision, potion and armor work owned exclusively by enrolled players. */
public final class RollbackParticipantStatuses {
    private final Map<Entity, HorizontalVelocityTracker> velocity = new LinkedHashMap<>();
    private final Set<MovementHandler> movement = new LinkedHashSet<>();
    private final Map<LivingEntity, TempPotionEffect> potions = new LinkedHashMap<>();
    private final Map<LivingEntity, PriorityQueue<TempArmor>> armor = new LinkedHashMap<>();
    private final Map<LivingEntity, ItemStack[]> originalArmor = new LinkedHashMap<>();

    public static RollbackParticipantStatuses current() {
        var globals = RollbackDomain.abilityGlobals();
        return globals == null ? null : globals.participantStatuses();
    }
    public Map<Entity, HorizontalVelocityTracker> velocity() { return velocity; }
    public Set<MovementHandler> movement() { return movement; }
    public Map<LivingEntity, TempPotionEffect> potions() { return potions; }
    public Map<LivingEntity, PriorityQueue<TempArmor>> armor() { return armor; }
    public Map<LivingEntity, ItemStack[]> originalArmor() { return originalArmor; }

    void capture(Collection<Player> participants) {
        var roster = new HashMap<UUID, Player>();
        for (var player : participants) {
            roster.put(player.getUniqueId(), player);
            var effect = TempPotionEffect.activeEffects().get(player);
            if (effect != null) potions.put(player, effect);
            var queue = TempArmor.activeArmor().get(player);
            if (queue != null) armor.put(player, new PriorityQueue<>(queue));
            var original = TempArmor.originalArmor().get(player);
            if (original != null) originalArmor.put(player, original.clone());
        }
        for (var handler : MovementHandler.handlers) {
            var caster = handler.getAbility() == null ? null : handler.getAbility().getPlayer();
            if (includes(roster, handler.getEntity()) || includes(roster, caster)) movement.add(handler);
        }
        HorizontalVelocityTracker.instances.forEach((target, tracker) -> {
            if (includes(roster, target) || includes(roster, tracker.rollbackInstigator())) velocity.put(target, tracker);
        });
        validate(roster);
    }

    private static boolean includes(Map<UUID, Player> roster, Entity entity) {
        return entity != null && roster.containsKey(entity.getUniqueId());
    }
    private static void requireOwner(Map<UUID, Player> roster, Entity entity) {
        var player = entity == null ? null : roster.get(entity.getUniqueId());
        if (player == null || player.handle() != entity.handle()) throw new IllegalStateException("Status effect crosses the duel boundary");
    }
    void validate(Map<UUID, Player> roster) {
        for (var handler : movement) {
            requireOwner(roster, handler.getEntity());
            if (handler.getAbility() != null && handler.getAbility().getPlayer() != null) requireOwner(roster, handler.getAbility().getPlayer());
        }
        velocity.forEach((target, tracker) -> {
            requireOwner(roster, target);
            if (tracker.rollbackTarget() == null || tracker.rollbackTarget().handle() != target.handle()) throw new IllegalStateException("Invalid velocity target");
            if (tracker.rollbackInstigator() != null) requireOwner(roster, tracker.rollbackInstigator());
        });
        potions.forEach((target, effect) -> {
            requireOwner(roster, target);
            if (effect.rollbackTarget() == null || effect.rollbackTarget().handle() != target.handle()) throw new IllegalStateException("Invalid potion target");
        });
        for (var entry : armor.entrySet()) {
            requireOwner(roster, entry.getKey());
            for (var layer : entry.getValue()) {
                requireOwner(roster, layer.getEntity());
                if (layer.getEntity().handle() != entry.getKey().handle()) throw new IllegalStateException("Invalid armor owner");
                if (layer.getAbility() != null && layer.getAbility().getPlayer() != null) requireOwner(roster, layer.getAbility().getPlayer());
            }
        }
        originalArmor.forEach((player, slots) -> {
            requireOwner(roster, player);
            if (slots.length != 4) throw new IllegalStateException("Invalid saved armor slots");
        });
    }
    void rebase(RollbackTimeShift time) {
        movement.forEach(handler -> handler.rebase(time));
        velocity.values().forEach(tracker -> tracker.rebase(time));
        potions.values().forEach(effect -> effect.rebase(time));
        for (var layers : armor.values()) for (var layer : layers) layer.rebase(time);
    }

    void merge(Map<UUID, Player> roster) {
        MovementHandler.handlers.removeIf(handler -> includes(roster, handler.getEntity()));
        MovementHandler.handlers.addAll(movement);
        for (var player : roster.values()) {
            restoreEntry(player, velocity, HorizontalVelocityTracker.instances);
            restoreEntry(player, potions, TempPotionEffect.activeEffects());
            restoreEntry(player, armor, TempArmor.activeArmor());
            restoreEntry(player, originalArmor, TempArmor.originalArmor());
        }
    }
    private static <K,V> void restoreEntry(K owner, Map<K,V> settled, Map<K,V> live) {
        var value = settled.get(owner);
        if (value == null) live.remove(owner);
        else live.put(owner, value);
    }
}
