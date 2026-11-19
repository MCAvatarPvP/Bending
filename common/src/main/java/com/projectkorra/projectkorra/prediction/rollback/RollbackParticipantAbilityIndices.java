package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.chiblocking.Smokescreen;
import com.projectkorra.projectkorra.firebending.FireBlastCharged;
import com.projectkorra.projectkorra.firebending.combo.LightningPunch;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.*;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitTask;
import com.projectkorra.projectkorra.waterbending.multiabilities.WaterArmsWhip;
import me.moros.hyperion.abilities.earthbending.EarthGlove;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Participant-owned targeting, delayed hits, and charged selections from core and bundled abilities. */
public final class RollbackParticipantAbilityIndices {
    private static final RollbackParticipantAbilityIndices LIVE = new RollbackParticipantAbilityIndices();
    public static RollbackParticipantAbilityIndices live() { return LIVE; }

    public final Map<Player, Long> chiBlocks = new ConcurrentHashMap<>();
    public final Map<Player, List<Long>> bombCooldowns = new HashMap<>();
    public final Map<Player, Long> bombThrows = new HashMap<>();
    public final Map<Integer, Smokescreen> smokeShots = new ConcurrentHashMap<>();
    public final Map<String, Long> smokeTimes = new ConcurrentHashMap<>();
    public final Map<String, Smokescreen> smokeEffects = new ConcurrentHashMap<>();
    public final Map<String, UUID> smokeNames = new ConcurrentHashMap<>();
    public final Map<Entity, Player> bloodTargets = new ConcurrentHashMap<>();
    public final HashMap<LivingEntity, WaterArmsWhip> grabbed = new HashMap<>();
    public final Map<Entity, FireBlastCharged> explosions = new ConcurrentHashMap<>();
    public final Map<UUID, LightningPunch.PendingHit> hits = new HashMap<>();
    public final Map<Entity, Player> flames = new ConcurrentHashMap<>();
    public final Map<Entity, Long> flameTimes = new ConcurrentHashMap<>();
    public final Map<Entity, Ability> flameAbilities = new ConcurrentHashMap<>();
    public final Map<UUID, Block> whipSources = new HashMap<>();
    public final Map<UUID, BukkitTask> whipParticles = new HashMap<>();
    public final HashMap<Player, Sound> sounds = new HashMap<>();
    public final Map<UUID, EarthGlove.Side> gloveSides = new ConcurrentHashMap<>();
    public final Map<UUID, EarthGlove> gloveParts = new ConcurrentHashMap<>();
    public final Map<LivingEntity, Player> bloodGrip = new HashMap<>();
    public final Map<LivingEntity, Player> weakness = new HashMap<>();

    public static RollbackParticipantAbilityIndices current() {
        var indices = RollbackArenaBlockIndices.current();
        return indices == null ? LIVE : indices.participants;
    }

    private static UUID player(Entity entity) {
        return entity instanceof Player ? entity.getUniqueId() : null;
    }

    private static UUID caster(Ability ability) {
        return ability == null ? null : player(ability.getPlayer());
    }

    void transfer(RollbackArenaBlockIndices.Operation operation, Set<UUID> roster) {
        var transfer = new Transfer(operation, roster);
        Function<String, UUID> smokeTarget = name -> smokeNames.getOrDefault(name, LIVE.smokeNames.get(name));
        transfer.map(LIVE.smokeNames, smokeNames, ignored -> null, Function.identity());
        transfer.map(LIVE.smokeTimes, smokeTimes, smokeTarget, ignored -> null);
        transfer.map(LIVE.smokeEffects, smokeEffects, smokeTarget, RollbackParticipantAbilityIndices::caster);
        // Native actor IDs are session-local. Resolve the restored projectile's live ID at handoff.
        if (operation == RollbackArenaBlockIndices.Operation.MERGE) {
            smokeShots.forEach((id, smoke) -> LIVE.smokeShots.put(smoke.getProjectile().getEntityId(), smoke));
        } else {
            transfer.map(LIVE.smokeShots, smokeShots, ignored -> null, RollbackParticipantAbilityIndices::caster);
            if (operation == RollbackArenaBlockIndices.Operation.CHECK_DESTINATION) {
                for (var smoke : smokeShots.values()) {
                    if (smoke.getProjectile() == null || LIVE.smokeShots.containsKey(smoke.getProjectile().getEntityId()))
                        throw new IllegalStateException("Smokescreen projectile has no free live ID");
                }
            }
        }
        transfer.map(LIVE.chiBlocks, chiBlocks, Player::getUniqueId, ignored -> null);
        transfer.map(LIVE.bombCooldowns, bombCooldowns, Player::getUniqueId, ignored -> null);
        if (operation == RollbackArenaBlockIndices.Operation.CAPTURE) {
            // Keep the source validation independent of later edits to a live cooldown list.
            var copies = new IdentityHashMap<List<Long>, List<Long>>();
            bombCooldowns.replaceAll((player, deadlines) -> copies.computeIfAbsent(deadlines, ArrayList::new));
        }
        transfer.map(LIVE.bombThrows, bombThrows, Player::getUniqueId, ignored -> null);
        transfer.map(LIVE.bloodTargets, bloodTargets, RollbackParticipantAbilityIndices::player, Player::getUniqueId);
        transfer.map(LIVE.grabbed, grabbed, RollbackParticipantAbilityIndices::player, RollbackParticipantAbilityIndices::caster);
        transfer.map(LIVE.explosions, explosions, RollbackParticipantAbilityIndices::player, RollbackParticipantAbilityIndices::caster);
        transfer.map(LIVE.hits, hits, Function.identity(), hit -> player(hit.target()));
        transfer.map(LIVE.flames, flames, RollbackParticipantAbilityIndices::player, Player::getUniqueId);
        // An owned projectile or summoned mob can burn too. Its source owns the timer row.
        Function<Entity, UUID> flameOwner = target -> {
            var caster = flames.get(target);
            if (caster == null) caster = LIVE.flames.get(target);
            return caster == null ? player(target) : caster.getUniqueId();
        };
        transfer.map(LIVE.flameTimes, flameTimes, flameOwner, ignored -> null);
        transfer.map(LIVE.flameAbilities, flameAbilities, flameOwner, RollbackParticipantAbilityIndices::caster);
        transfer.map(LIVE.whipSources, whipSources, Function.identity(), ignored -> null);
        transfer.map(LIVE.whipParticles, whipParticles, Function.identity(), ignored -> null);
        transfer.map(LIVE.sounds, sounds, Player::getUniqueId, ignored -> null);
        transfer.map(LIVE.gloveSides, gloveSides, Function.identity(), ignored -> null);
        transfer.map(LIVE.gloveParts, gloveParts, ignored -> null, RollbackParticipantAbilityIndices::caster);
        transfer.map(LIVE.bloodGrip, bloodGrip, RollbackParticipantAbilityIndices::player, Player::getUniqueId);
        transfer.map(LIVE.weakness, weakness, RollbackParticipantAbilityIndices::player, Player::getUniqueId);
    }

    private record Transfer(RollbackArenaBlockIndices.Operation operation, Set<UUID> roster) {
        boolean included(UUID first, UUID second) {
            boolean ours = first != null && roster.contains(first) || second != null && roster.contains(second);
            if (ours && (first != null && !roster.contains(first) || second != null && !roster.contains(second)))
                throw new IllegalStateException("Bending effect crosses the enrolled roster");
            return ours;
        }

        <K,V> void map(Map<K,V> live, Map<K,V> owned, Function<K,UUID> keyOwner, Function<V,UUID> valueOwner) {
            switch (operation) {
                case CAPTURE, CHECK_SOURCE -> {
                    var selected = new HashMap<K,V>();
                    live.forEach((key, value) -> {
                        if (included(keyOwner.apply(key), valueOwner.apply(value))) selected.put(key, value);
                    });
                    if (operation == RollbackArenaBlockIndices.Operation.CAPTURE) owned.putAll(selected);
                    else if (!selected.equals(owned)) throw changed();
                }
                case DETACH -> owned.keySet().forEach(live::remove);
                case CHECK_DESTINATION -> {
                    for (var entry : live.entrySet())
                        if (included(keyOwner.apply(entry.getKey()), valueOwner.apply(entry.getValue())) || owned.containsKey(entry.getKey())) throw changed();
                    for (var entry : owned.entrySet())
                        if (!included(keyOwner.apply(entry.getKey()), valueOwner.apply(entry.getValue()))) throw changed();
                }
                case MERGE -> live.putAll(owned);
            }
        }

        private static IllegalStateException changed() {
            return new IllegalStateException("Participant ability indices changed during rollback ownership");
        }
    }
}
