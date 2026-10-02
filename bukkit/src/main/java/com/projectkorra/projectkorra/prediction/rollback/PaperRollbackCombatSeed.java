package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerCombatData.Source;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerCombatData.Entry;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerCombatData.Tracker;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerValues.Vector;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.CombatEntry;
import net.minecraft.world.damagesource.CombatTracker;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.FallLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

import java.util.*;

import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackPlayerFields.*;

/** Native combat history detached to registry keys, roster UUIDs and value data. */
public final class PaperRollbackCombatSeed {
    private static final Field<LivingEntity> LAST_HURT_MOB = new Field<>(LivingEntity.class, "lastHurtMob", LivingEntity.class);
    private static final Field<DamageSource> LAST_DAMAGE = new Field<>(LivingEntity.class, "lastDamageSource", DamageSource.class);
    private static final Field<Object2LongMap> KINETIC = new Field<>(LivingEntity.class, "recentKineticEnemies", Object2LongMap.class);
    private static final Values TRACKER = new Values(CombatTracker.class,
            "lastDamageTime", "combatStartTime", "combatEndTime", "inCombat", "takingDamage");

    private final RollbackPlayerCombatData data;
    private PaperRollbackCombatSeed(RollbackPlayerCombatData data) { this.data = Objects.requireNonNull(data); }
    public RollbackPlayerCombatData data() { return data; }

    @SuppressWarnings("unchecked")
    PaperRollbackCombatSeed(ServerPlayer source) {
        var capture = new Capture(source);
        UUID lastHurtByPlayer = capture.reference(source.lastHurtByPlayer);
        UUID lastHurtByMob = capture.reference(source.lastHurtByMob);
        UUID lastHurtMob = capture.entity(LAST_HURT_MOB.get(source));
        UUID explosionCause = capture.entity(source.currentExplosionCause);
        Object2LongMap<Entity> hits = KINETIC.get(source);
        boolean hasKinetic = hits != null;
        var detachedHits = new LinkedHashMap<UUID, Long>();
        if (hasKinetic) {
            if (hits.size() > RollbackPlayerCombatData.MAXIMUM_ROSTER) throw new IllegalArgumentException("Kinetic contact history exceeds the private roster budget");
            hits.object2LongEntrySet().forEach(hit -> {
                if (detachedHits.put(capture.entity(hit.getKey()), hit.getLongValue()) != null) {
                    throw new IllegalArgumentException("Kinetic contact history repeats an entity UUID");
                }
            });
        }
        var fields = TRACKER.capture(source.combatTracker);
        var tracker = new Tracker((int) fields[0], (int) fields[1], (int) fields[2], (boolean) fields[3], (boolean) fields[4]);
        int lastDamage = capture.damage(LAST_DAMAGE.get(source));
        if (source.combatTracker.entries.size() > RollbackPlayerCombatData.MAXIMUM_ENTRIES) throw new IllegalArgumentException("Combat history exceeds player capture budget");
        var entries = source.combatTracker.entries.stream().map(entry ->
                new Entry(capture.damage(Objects.requireNonNull(entry.source(), "combat entry source")), entry.damage(), entry.fallLocation() == null ? null : entry.fallLocation().id(), entry.fallDistance())).toList();
        data = new RollbackPlayerCombatData(source.getUUID(), lastHurtByPlayer, lastHurtByMob, lastHurtMob, explosionCause,
                hasKinetic, detachedHits, tracker, capture.sources, lastDamage, entries);
    }

    /** Validate the complete cohort before any native replicas are allocated. */
    void validate(Set<UUID> roster, RegistryAccess registries) {
        data.requireRoster(roster);
        var types = registries.lookupOrThrow(Registries.DAMAGE_TYPE);
        for (var source : data.sources()) {
            if (types.get(Identifier.parse(source.type())).isEmpty()) throw new IllegalArgumentException("Unknown imported damage type: " + source.type());
            if (source.knownCause() != null) DamageCause.valueOf(source.knownCause());
        }
    }

    void apply(ServerPlayer target, Map<UUID, ServerPlayer> roster) {
        prepare(target, roster).run();
    }

    /** Stage all participants first: a bad late player's source must not partly overwrite the cohort. */
    public static void apply(Map<UUID, PaperRollbackNativePlayerState> states, Map<UUID, RollbackPlayerCombatData> seeds) {
        requireSetup();
        if (states.isEmpty() || states.size() > RollbackPlayerCombatData.MAXIMUM_ROSTER || !states.keySet().equals(seeds.keySet())) throw new IllegalArgumentException("Combat import roster changed");
        var roster = new TreeMap<UUID, ServerPlayer>();
        var entityIds = new HashSet<Integer>();
        states.forEach((id, state) -> state.use(value -> {
            if (!(value instanceof ServerPlayer player) || !player.getUUID().equals(id)) throw new IllegalArgumentException("Combat import identity mismatch");
            if (!entityIds.add(player.getId())) throw new IllegalArgumentException("Duplicate native combat entity id");
            if (!roster.isEmpty() && roster.firstEntry().getValue().level() != player.level()) throw new IllegalArgumentException("Combat import crosses private worlds");
            roster.put(id, player); return null;
        }));
        var prepared = new ArrayList<Runnable>();
        for (var entry : roster.entrySet()) prepared.add(new PaperRollbackCombatSeed(seeds.get(entry.getKey())).prepare(entry.getValue(), roster));
        prepared.forEach(Runnable::run);
    }
    public static RollbackPlayerCombatData capture(PaperRollbackNativePlayerState state) {
        requireSetup(); return state.use(value -> {
            if (!(value instanceof ServerPlayer player)) throw new IllegalArgumentException("Combat seed requires a private server player");
            return new PaperRollbackCombatSeed(player).data;
        });
    }
    private Runnable prepare(ServerPlayer target, Map<UUID, ServerPlayer> roster) {
        if (!target.getUUID().equals(data.owner()) || roster.get(data.owner()) != target) throw new IllegalArgumentException("Combat import target is not in its roster");
        validate(roster.keySet(), target.registryAccess());
        var damage = data.sources().stream().map(source -> instantiate(source, target.registryAccess(), roster)).toList();
        var player = EntityReference.<net.minecraft.world.entity.player.Player>of(resolve(roster, data.lastHurtByPlayer()));
        var mob = EntityReference.<LivingEntity>of(resolve(roster, data.lastHurtByMob()));
        var attacked = resolve(roster, data.lastHurtMob()); var explosion = resolve(roster, data.explosionCause());
        Object2LongMap<Entity> hits = data.hasKinetic() ? new Object2LongOpenHashMap<>() : null;
        data.kinetic().forEach((id, tick) -> hits.put(resolve(roster, id), tick.longValue()));
        var entries = data.entries().stream().map(entry -> new CombatEntry(damage.get(entry.source()), entry.damage(),
                entry.fallLocation() == null ? null : new FallLocation(entry.fallLocation()), entry.fallDistance())).toList();
        var t = data.tracker(); var tracker = new Object[]{t.lastDamageTime(), t.combatStartTime(), t.combatEndTime(), t.inCombat(), t.takingDamage()};
        return () -> {
            target.lastHurtByPlayer = player; target.lastHurtByMob = mob; LAST_HURT_MOB.set(target, attacked); target.currentExplosionCause = explosion;
            LAST_DAMAGE.set(target, data.lastDamage() < 0 ? null : damage.get(data.lastDamage())); KINETIC.set(target, hits);
            TRACKER.apply(target.combatTracker, tracker); target.combatTracker.entries.clear(); target.combatTracker.entries.addAll(entries);
        };
    }
    private static DamageSource instantiate(Source source, RegistryAccess registries, Map<UUID, ServerPlayer> roster) {
        var holder = registries.lookupOrThrow(Registries.DAMAGE_TYPE).get(Identifier.parse(source.type())).orElseThrow();
        var p = source.position(); var position = p == null ? null : new Vec3(p.x(), p.y(), p.z());
        var result = new DamageSource(holder, resolve(roster, source.direct()), resolve(roster, source.causing()), position);
        if (source.eventDamager() != null) result = result.eventEntityDamager(resolve(roster, source.eventDamager()));
        if (source.knownCause() != null) result = result.knownCause(DamageCause.valueOf(source.knownCause()));
        if (source.critical()) result = result.critical();
        return result;
    }
    private static void requireSetup() {
        if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Import/capture combat at the roster bootstrap boundary");
    }

    private static ServerPlayer resolve(Map<UUID, ServerPlayer> roster, UUID id) {
        return id == null ? null : Objects.requireNonNull(roster.get(id), "Missing imported combat participant: " + id);
    }

    private static final class Capture {
        final ServerPlayer owner;
        final IdentityHashMap<DamageSource, Integer> ids = new IdentityHashMap<>();
        final List<Source> sources = new ArrayList<>();
        Capture(ServerPlayer owner) { this.owner = owner; }

        UUID reference(EntityReference<?> reference) {
            if (reference == null) return null;
            // getUUID does not resolve the reference or query/load a live world.
            return reference.getUUID();
        }
        UUID entity(Entity entity) {
            if (entity == null) return null;
            if (!(entity instanceof ServerPlayer) || entity.level() != owner.level()) {
                throw new IllegalArgumentException("Combat source needs the non-player/world entity import phase");
            }
            return entity.getUUID();
        }
        int damage(DamageSource damage) {
            if (damage == null) return -1;
            var existing = ids.get(damage); if (existing != null) return existing;
            if (damage.getClass() != DamageSource.class || damage.eventBlockDamager() != null || damage.causingBlockSnapshot() != null) {
                throw new IllegalArgumentException("Combat source needs a block or custom damage-source importer");
            }
            if (sources.size() >= RollbackPlayerCombatData.MAXIMUM_SOURCES) throw new IllegalArgumentException("Damage sources exceed player capture budget");
            var position = damage.sourcePositionRaw();
            if (position != null && (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z))) {
                throw new IllegalArgumentException("Nonfinite captured damage position");
            }
            int id = sources.size();
            sources.add(new Source(damage.typeHolder().unwrapKey().orElseThrow().identifier().toString(), entity(damage.getDirectEntity()),
                    entity(damage.getEntity()), entity(damage.eventEntityDamager()), position == null ? null : new Vector(position.x, position.y, position.z),
                    damage.knownCause() == null ? null : damage.knownCause().name(), damage.isCritical()));
            ids.put(damage, id); return id;
        }
    }
}
