package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.fabric.mixin.client.DamageTrackerRollbackAccess;
import com.projectkorra.projectkorra.fabric.mixin.client.LivingEntityRollbackHistoryAccess;
import com.projectkorra.projectkorra.prediction.rollback.RollbackClock;
import com.projectkorra.projectkorra.prediction.rollback.RollbackDomain;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerCombatData;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerCombatData.*;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerValues.Vector;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LazyEntityReference;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.damage.DamageRecord;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.entity.damage.FallLocation;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** Complete owned-roster validation and detached native combat-history import. */
public final class FabricRollbackPlayerCombatData {
    private FabricRollbackPlayerCombatData() { }
    public static void apply(Map<UUID, FabricRollbackNativePlayerState> states, Map<UUID, RollbackPlayerCombatData> seeds) {
        requireSetup();
        if (states.isEmpty() || states.size() > RollbackPlayerCombatData.MAXIMUM_ROSTER || !states.keySet().equals(seeds.keySet())) throw new IllegalArgumentException("Combat import roster changed");
        var roster = new TreeMap<UUID, PlayerEntity>();
        var entityIds = new HashSet<Integer>();
        states.forEach((id, state) -> state.use(player -> {
            if (!player.getUuid().equals(id)) throw new IllegalArgumentException("Combat import identity mismatch");
            if (!entityIds.add(player.getId())) throw new IllegalArgumentException("Duplicate native combat entity id");
            if (!roster.isEmpty() && roster.firstEntry().getValue().getEntityWorld() != player.getEntityWorld()) throw new IllegalArgumentException("Combat import crosses private worlds");
            roster.put(id, player); return null;
        }));
        var prepared = new ArrayList<Runnable>();
        for (var entry : roster.entrySet()) prepared.add(prepare(entry.getValue(), states.get(entry.getKey()), seeds.get(entry.getKey()), roster));
        prepared.forEach(Runnable::run);
    }
    private static Runnable prepare(PlayerEntity target, FabricRollbackNativePlayerState state, RollbackPlayerCombatData data, Map<UUID, PlayerEntity> roster) {
        if (!target.getUuid().equals(data.owner())) throw new IllegalArgumentException("Combat seed belongs to another player");
        data.requireRoster(roster.keySet());
        var registry = target.getEntityWorld().getRegistryManager().getOrThrow(RegistryKeys.DAMAGE_TYPE);
        var sources = data.sources().stream().map(source -> {
            var type = registry.getEntry(Identifier.of(source.type())).orElseThrow(() -> new IllegalArgumentException("Unknown imported damage type " + source.type()));
            return new ImportedSource(type, resolve(roster, source.direct()), resolve(roster, source.causing()), resolve(roster, source.eventDamager()),
                    source.position(), source.knownCause(), source.critical());
        }).toList();
        var lastPlayer = LazyEntityReference.of(resolve(roster, data.lastHurtByPlayer()));
        var lastMob = LazyEntityReference.<LivingEntity>of(resolve(roster, data.lastHurtByMob()));
        var attacked = resolve(roster, data.lastHurtMob());
        Object2LongMap<Entity> hits = data.hasKinetic() ? new Object2LongOpenHashMap<>() : null;
        data.kinetic().forEach((id, tick) -> hits.put(resolve(roster, id), tick.longValue()));
        var entries = data.entries().stream().map(entry -> new DamageRecord(sources.get(entry.source()), entry.damage(),
                entry.fallLocation() == null ? null : new FallLocation(entry.fallLocation()), entry.fallDistance())).toList();
        var living = (LivingEntityRollbackHistoryAccess) target; var tracker = (DamageTrackerRollbackAccess) target.getDamageTracker();
        return () -> {
            living.rollback$lastHurtByPlayer(lastPlayer); living.rollback$lastHurtByMob(lastMob); living.rollback$lastHurtMob(attacked);
            living.rollback$lastDamage(data.lastDamage() == -1 ? null : sources.get(data.lastDamage())); living.rollback$kinetic(hits);
            var t = data.tracker(); tracker.rollback$lastDamageTime(t.lastDamageTime()); tracker.rollback$combatStartTime(t.combatStartTime());
            tracker.rollback$combatEndTime(t.combatEndTime()); tracker.rollback$inCombat(t.inCombat()); tracker.rollback$takingDamage(t.takingDamage());
            tracker.rollback$entries().clear(); tracker.rollback$entries().addAll(entries);
            state.importedExplosionCause(data.explosionCause());
        };
    }
    public static RollbackPlayerCombatData capture(FabricRollbackNativePlayerState state) {
        requireSetup(); UUID explosion = state.importedExplosionCause();
        return state.use(player -> {
            var capture = new Capture(player); var living = (LivingEntityRollbackHistoryAccess) player;
            var tracker = (DamageTrackerRollbackAccess) player.getDamageTracker();
            var kinetic = living.rollback$kinetic(); var hits = new HashMap<UUID, Long>();
            if (kinetic != null) {
                if (kinetic.size() > RollbackPlayerCombatData.MAXIMUM_ROSTER) throw new IllegalArgumentException("Kinetic contact budget");
                kinetic.object2LongEntrySet().forEach(hit -> {
                    if (hits.put(capture.entity(hit.getKey()), hit.getLongValue()) != null) throw new IllegalArgumentException("Duplicate kinetic UUID");
                });
            }
            int lastDamage = capture.damage(living.rollback$lastDamage());
            if (tracker.rollback$entries().size() > RollbackPlayerCombatData.MAXIMUM_ENTRIES) throw new IllegalArgumentException("Combat history budget");
            var entries = tracker.rollback$entries().stream().map(entry -> new Entry(capture.damage(Objects.requireNonNull(entry.damageSource())), entry.damage(),
                    entry.fallLocation() == null ? null : entry.fallLocation().id(), entry.fallDistance())).toList();
            var t = new Tracker(tracker.rollback$lastDamageTime(), tracker.rollback$combatStartTime(), tracker.rollback$combatEndTime(), tracker.rollback$inCombat(), tracker.rollback$takingDamage());
            return new RollbackPlayerCombatData(player.getUuid(), reference(living.rollback$lastHurtByPlayer()), reference(living.rollback$lastHurtByMob()),
                    capture.entity(living.rollback$lastHurtMob()), explosion, kinetic != null, hits, t, capture.sources, lastDamage, entries);
        });
    }
    /** Retained Paper event metadata; the future event-policy adapter must consume these values. */
    public static String knownCause(DamageSource source) { return source instanceof ImportedSource imported ? imported.knownCause : null; }
    public static boolean critical(DamageSource source) { return source instanceof ImportedSource imported && imported.critical; }
    public static Entity eventDamager(DamageSource source) { return source instanceof ImportedSource imported ? imported.eventDamager : null; }
    private static UUID reference(LazyEntityReference<?> value) { return value == null ? null : value.getUuid(); }
    private static PlayerEntity resolve(Map<UUID, PlayerEntity> roster, UUID id) {
        return id == null ? null : Objects.requireNonNull(roster.get(id), "Missing imported combat participant " + id);
    }
    private static void requireSetup() {
        if (RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Import/capture combat at the roster bootstrap boundary");
    }

    /** Native source semantics plus a stored position and Paper metadata, without mutating final native fields. */
    private static final class ImportedSource extends DamageSource {
        private final Vec3d storedPosition;
        private final PlayerEntity eventDamager;
        private final String knownCause;
        private final boolean critical;
        ImportedSource(RegistryEntry<DamageType> type, PlayerEntity direct, PlayerEntity causing, PlayerEntity eventDamager, Vector position, String knownCause, boolean critical) {
            super(type, direct, causing); this.storedPosition = position == null ? null : new Vec3d(position.x(), position.y(), position.z());
            this.eventDamager = eventDamager; this.knownCause = knownCause; this.critical = critical;
        }
        @Override public Vec3d getPosition() { return storedPosition != null ? storedPosition : super.getPosition(); }
        @Override public Vec3d getStoredPosition() { return storedPosition; }
    }
    private static final class Capture {
        final PlayerEntity owner;
        final IdentityHashMap<DamageSource, Integer> ids = new IdentityHashMap<>();
        final List<Source> sources = new ArrayList<>();
        Capture(PlayerEntity owner) { this.owner = owner; }
        UUID entity(Entity value) {
            if (value == null) return null;
            if (!(value instanceof PlayerEntity) || value.getEntityWorld() != owner.getEntityWorld()) throw new IllegalArgumentException("Combat source requires non-player/world import");
            return value.getUuid();
        }
        int damage(DamageSource source) {
            if (source == null) return -1;
            var existing = ids.get(source); if (existing != null) return existing;
            if (source.getClass() != DamageSource.class && source.getClass() != ImportedSource.class) throw new IllegalArgumentException("Custom combat source requires an importer");
            if (sources.size() >= RollbackPlayerCombatData.MAXIMUM_SOURCES) throw new IllegalArgumentException("Damage-source budget");
            var p = source.getStoredPosition(); int id = sources.size();
            sources.add(new Source(source.getTypeRegistryEntry().getKey().orElseThrow().getValue().toString(), entity(source.getSource()), entity(source.getAttacker()),
                    entity(eventDamager(source)), p == null ? null : new Vector(p.x, p.y, p.z), knownCause(source), critical(source)));
            ids.put(source, id); return id;
        }
    }
}
