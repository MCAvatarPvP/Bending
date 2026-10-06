package com.projectkorra.projectkorra.prediction.rollback;

import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Last damage stage inside a private simulation, after captured damage modifiers
 * and cancellation. This is deliberately not a globally registered Bukkit listener.
 * The session must supply its private replicas, checkpoint the round alongside
 * those replicas, and deliver defeats only at the engine's confirmed frontier.
 */
final class PaperRollbackRoundEvents implements Consumer<EntityDamageEvent> {
    private final RollbackRound round;
    private final Map<UUID, Player> replicas;

    PaperRollbackRoundEvents(RollbackRound round, Map<UUID, ? extends Player> replicas) {
        this.round = Objects.requireNonNull(round, "round");
        this.replicas = Map.copyOf(replicas);
        if (!this.replicas.keySet().equals(round.participants())) {
            throw new IllegalArgumentException("Private replicas must cover exactly the captured round");
        }
        this.replicas.forEach((id, player) -> {
            if (!id.equals(player.getUniqueId())) throw new IllegalArgumentException("Replica identity does not match its participant");
        });
    }

    RollbackRound round() { return round; }

    @Override public void accept(EntityDamageEvent event) {
        Objects.requireNonNull(event, "event");
        if (!(event.getEntity() instanceof Player victim)) return;
        UUID victimId = owned(victim);
        // Native damage sources retain the causing player for indirect attacks.
        UUID attacker = playerId(event.getDamageSource().getCausingEntity());
        UUID direct = event instanceof EntityDamageByEntityEvent hit ? playerId(hit.getDamager()) : null;
        if (attacker == null) attacker = direct;
        double health = victim.getHealth(), damage = event.getFinalDamage();
        boolean totem = !event.isCancelled() && RollbackRound.endsLife(health, damage, false)
                && (victim.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                        || victim.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING);
        switch (round.damage(victimId, attacker, health, damage, totem, event.isCancelled())) {
            case ALLOW -> { }
            case CANCEL -> event.setCancelled(true);
            case DEFEAT -> {
                // Same health reset/cancellation as MatchListener, without calling
                // live Match.onDeath or publishing its irreversible side effects.
                victim.setHealth(20);
                event.setCancelled(true);
            }
        }
    }

    private UUID playerId(Entity entity) { return entity instanceof Player player ? owned(player) : null; }

    @SuppressWarnings("WrapperReferenceEquality")
    private UUID owned(Player player) {
        UUID id = player.getUniqueId();
        if (replicas.get(id) != player) throw new IllegalArgumentException("Damage references a foreign player replica");
        return id;
    }
}
