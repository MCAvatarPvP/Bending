package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.entity.ArmorStand;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import me.moros.hyperion.util.BendingFallingBlock;
import me.moros.hyperion.util.TempArmorStand;
import java.util.*;
import java.util.function.Predicate;

/** Hyperion actor membership and the original manager deadlines in simulation ticks. */
public final class RollbackAddonActors {
    public final Map<ArmorStand, TempArmorStand> stands = new LinkedHashMap<>();
    public final Map<FallingBlock, BendingFallingBlock> falling = new LinkedHashMap<>();
    public final PriorityQueue<TempArmorStand> standExpiry = new PriorityQueue<>(new TempArmorStand.ExpiryOrder());
    public final PriorityQueue<BendingFallingBlock> fallingExpiry = new PriorityQueue<>(new BendingFallingBlock.ExpiryOrder());
    private long nextStands = -1, nextFalling = -1;

    public static RollbackAddonActors current() {
        var globals = RollbackDomain.abilityGlobals();
        return globals == null ? null : globals.addonActors();
    }

    public void timerDelays(long stands, long falling) {
        if (stands != -1 && stands != 1 || falling < -1 || falling == 0 || falling > 5)
            throw new IllegalArgumentException("Invalid Hyperion maintenance phase");
        nextStands = stands; nextFalling = falling;
    }

    public void tick(long tick) {
        if (tick == nextStands) {
            nextStands = Math.addExact(nextStands, 1);
            TempArmorStand.manage();
        }
        if (tick == nextFalling) {
            nextFalling = Math.addExact(nextFalling, 5);
            BendingFallingBlock.manage();
        }
    }

    static void requireNoInitialActors(Predicate<Entity> inside, Set<UUID> players) {
        for (var stand : TempArmorStand.managed()) {
            var caster = stand.getAbility().getPlayer();
            if (inside.test(stand.getArmorStand()) || caster != null && players.contains(caster.getUniqueId()))
                throw new IllegalStateException("Duel overlaps an existing Hyperion armor stand");
        }
        for (var block : BendingFallingBlock.managed()) {
            var caster = block.getAbility().getPlayer();
            if (inside.test(block.getFallingBlock()) || caster != null && players.contains(caster.getUniqueId()))
                throw new IllegalStateException("Duel overlaps an existing Hyperion falling block");
        }
    }

    void validateOwners(Map<UUID, com.projectkorra.projectkorra.platform.mc.entity.Player> players) {
        if (!new HashSet<>(standExpiry).equals(new HashSet<>(stands.values())) || standExpiry.size() != stands.size()
                || !new HashSet<>(fallingExpiry).equals(new HashSet<>(falling.values())) || fallingExpiry.size() != falling.size())
            throw new IllegalStateException("Hyperion manager references an unregistered actor");
        for (var entry : stands.entrySet())
            check(entry.getKey(), entry.getValue().getArmorStand(), entry.getValue().getAbility(), players);
        for (var entry : falling.entrySet())
            check(entry.getKey(), entry.getValue().getFallingBlock(), entry.getValue().getAbility(), players);
    }

    private static void check(Entity key, Entity entity, com.projectkorra.projectkorra.ability.CoreAbility ability,
                              Map<UUID, com.projectkorra.projectkorra.platform.mc.entity.Player> players) {
        var caster = ability == null ? null : ability.getPlayer();
        var owner = caster == null ? null : players.get(caster.getUniqueId());
        if (owner == null || owner.handle() != caster.handle() || key.handle() != entity.handle()
                || entity.getWorld().handle() != owner.getWorld().handle())
            throw new IllegalStateException("Unowned Hyperion temporary actor");
    }

    void validateActors(Map<UUID, com.projectkorra.projectkorra.platform.mc.entity.EntityType> registered, Set<UUID> referenced) {
        for (var entity : stands.keySet()) RollbackAbilityGlobals.requireTemporaryActor(entity, registered, referenced);
        for (var entity : falling.keySet()) RollbackAbilityGlobals.requireTemporaryActor(entity, registered, referenced);
    }

    void validateLive() {
        for (var entry : stands.entrySet()) {
            var live = TempArmorStand.get(entry.getKey());
            if (live != null && live != entry.getValue()) throw new IllegalStateException("Hyperion armor stand has a live owner");
        }
        for (var entry : falling.entrySet()) {
            var live = BendingFallingBlock.get(entry.getKey());
            if (live != null && live != entry.getValue()) throw new IllegalStateException("Hyperion falling block has a live owner");
        }
    }

    void merge() {
        validateLive();
        for (var stand : standExpiry) TempArmorStand.resume(stand);
        for (var block : fallingExpiry) BendingFallingBlock.resume(block);
    }
}
