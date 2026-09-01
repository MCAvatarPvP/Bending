package com.projectkorra.projectkorra.ability.util;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.PassiveAbility;
import com.projectkorra.projectkorra.earthbending.EarthSmash;
import com.projectkorra.projectkorra.event.AbilityCollisionEvent;
import com.projectkorra.projectkorra.prediction.action.AbilityRemovalSync;
import com.projectkorra.projectkorra.prediction.combat.AirFireCombat;
import com.projectkorra.projectkorra.prediction.combat.SweptSphereContact;
import com.projectkorra.projectkorra.prediction.hit.HitRegistrationPolicy;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitRunnable;

import java.util.*;

/**
 * A CollisionManager is used to monitor possible collisions between all
 * CoreAbilities. Use {@link #addCollision(Collision)} to begin monitoring for
 * collision between two abilities, as shown in {@link CollisionInitializer}.
 * <p>
 * Addon developers should use:<br>
 * ProjectKorra.getCollisionInitializer().addCollision(myCoreAbility)
 * ProjectKorra.getCollisionInitializer().addSmallAbility(myCoreAbility)
 * <p>
 * For a CoreAbility to collide properly, the {@link CoreAbility#isCollidable}
 * , {@link CoreAbility#getCollisionRadius},
 * {@link CoreAbility#getLocations}, and {@link CoreAbility#handleCollision}
 * should be overridden if necessary.
 * <p>
 * During a Collision the {@link AbilityCollisionEvent} is called, then if not
 * cancelled, abilityFirst.handleCollision, and finally
 * abilitySecond.handleCollision.
 */
public class CollisionManager implements AirFireCombat.ReactiveCollisionPolicy {

    private static final int LBVH_PAIR_THRESHOLD = 128;
    private final HashMap<String, Collision> collisionLookup;
    private final HashSet<String> disabledCollisionKeys;
    private final HashMap<CoreAbility, List<Location>> locationsCache;
    private final HashMap<Class<? extends CoreAbility>, List<CollisionEntry>> entriesCache;
    private final HashMap<Class<? extends CoreAbility>, LBVH> indexCache;
    private final IdentityHashMap<CoreAbility, ArrayDeque<AbilityFrame>> reactiveHistory;
    private long collisionTick;
    /*
     * If true an ability instance can remove multiple other instances on a
     * single tick. e.g. 3 Colliding WaterManipulations can all be removed
     * instantly, rather than just 2.
     */
    private boolean removeMultipleInstances;
    /*
     * The amount of ticks in between checking for collisions. Higher values
     * reduce lag but are less accurate in detection.
     */
    private long detectionDelay;
    /*
     * Used for efficiency. The distance that we can guarantee that two
     * abilities will not collide so that we can stop comparing locations early.
     * For example, two Torrents that are thousands of blocks apart should not
     * be fully checked.
     */
    private double certainNoCollisionDistance;
    private ArrayList<Collision> collisions;
    private BukkitRunnable detectionRunnable;

    public CollisionManager() {
        this.removeMultipleInstances = true;
        this.detectionDelay = 1;
        this.certainNoCollisionDistance = 100;
        this.collisions = new ArrayList<>();
        this.collisionLookup = new HashMap<String, Collision>();
        this.disabledCollisionKeys = new HashSet<>();
        this.locationsCache = new HashMap<CoreAbility, List<Location>>();
        this.entriesCache = new HashMap<Class<? extends CoreAbility>, List<CollisionEntry>>();
        this.indexCache = new HashMap<Class<? extends CoreAbility>, LBVH>();
        this.reactiveHistory = new IdentityHashMap<>();
    }

    private static boolean isEarthSmashCollision(final Collision collision) {
        return collision.getAbilityFirst() instanceof EarthSmash || collision.getAbilitySecond() instanceof EarthSmash;
    }

    private static long collisionPairKey(final CoreAbility first, final CoreAbility second) {
        final int low = Math.min(first.getId(), second.getId());
        final int high = Math.max(first.getId(), second.getId());
        return ((long) low << 32) ^ (high & 0xffffffffL);
    }

    private static String collisionKey(final Class<? extends CoreAbility> first, final Class<? extends CoreAbility> second) {
        final String firstName = first.getName();
        final String secondName = second.getName();
        return firstName.compareTo(secondName) <= 0 ? firstName + "|" + secondName : secondName + "|" + firstName;
    }

    public void detectCollisions() {
        this.collisionTick++;
        int activeInstanceCount = 0;

        for (final CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
            if (!(ability instanceof PassiveAbility)) {
                if (++activeInstanceCount > 1) {
                    break;
                }
            }
        }

        this.locationsCache.clear();
        this.entriesCache.clear();
        this.indexCache.clear();

        if (activeInstanceCount <= 1) {
            this.captureReactiveHistory();
            return;
        }

        for (final Collision collision : this.collisions) {
            final Class<? extends CoreAbility> classFirst = collision.getAbilityFirst().getClass();
            final Class<? extends CoreAbility> classSecond = collision.getAbilitySecond().getClass();
            final List<CollisionEntry> entriesFirst = this.getCollisionEntries(classFirst);
            if (entriesFirst.isEmpty()) {
                continue;
            }
            final List<CollisionEntry> entriesSecond = this.getCollisionEntries(classSecond);
            if (entriesSecond.isEmpty()) {
                continue;
            }
            final HashSet<CoreAbility> alreadyCollided = new HashSet<CoreAbility>();
            final HashSet<Long> checkedPairs = new HashSet<Long>();
            final long possiblePairCount = (long) entriesFirst.size() * (long) entriesSecond.size();

            if (isReactivePair(collision)) {
                this.detectReactive(entriesFirst, entriesSecond,
                        alreadyCollided, checkedPairs);
                continue;
            }

            if (possiblePairCount <= LBVH_PAIR_THRESHOLD) {
                this.detectDirect(entriesFirst, entriesSecond, alreadyCollided, checkedPairs);
                continue;
            }

            final LBVH secondIndex = this.indexCache.computeIfAbsent(classSecond, clazz -> LBVH.build(entriesSecond));
            final ArrayList<CollisionEntry> candidates = new ArrayList<CollisionEntry>();

            for (final CollisionEntry entryFirst : entriesFirst) {
                final CoreAbility abilityFirst = entryFirst.ability;
                if (alreadyCollided.contains(abilityFirst)) {
                    continue;
                }

                candidates.clear();
                secondIndex.query(entryFirst, candidates);

                for (final CollisionEntry entrySecond : candidates) {
                    if (this.tryCollide(entryFirst, entrySecond, alreadyCollided, checkedPairs) && !this.removeMultipleInstances) {
                        break;
                    }
                }
            }
        }
        this.captureReactiveHistory();
    }

    private static boolean isReactivePair(final Collision collision) {
        return collision != null
                && AirFireCombat.isTimelineManaged(collision.getAbilityFirst())
                && AirFireCombat.isTimelineManaged(collision.getAbilitySecond());
    }

    @Override
    public boolean participates(final CoreAbility ability) {
        if (ability == null) return false;
        final Class<? extends CoreAbility> type = ability.getClass();
        for (final Collision collision : this.collisions) {
            if (!isAirFireTemplate(collision)) continue;
            if (collision.getAbilityFirst().getClass() == type
                    || collision.getAbilitySecond().getClass() == type) return true;
        }
        return false;
    }

    @Override
    public boolean canBeRemoved(final CoreAbility ability) {
        if (ability == null) return false;
        final Class<? extends CoreAbility> type = ability.getClass();
        for (final Collision collision : this.collisions) {
            if (!isAirFireTemplate(collision)) continue;
            if ((collision.getAbilityFirst().getClass() == type
                    && collision.isRemovingFirst())
                    || (collision.getAbilitySecond().getClass() == type
                    && collision.isRemovingSecond())) return true;
        }
        return false;
    }

    private static boolean isAirFireTemplate(final Collision collision) {
        return collision != null
                && HitRegistrationPolicy.forAbility(collision.getAbilityFirst())
                == HitRegistrationPolicy.SERVER_CURRENT
                && HitRegistrationPolicy.forAbility(collision.getAbilitySecond())
                == HitRegistrationPolicy.SERVER_CURRENT;
    }

    /**
     * Resolves every registered Air/Fire pair on one aligned simulation tick.
     * A late ability uses its current replay state against the other ability's
     * retained frame; no ability names or attack/shield roles are encoded here.
     */
    private void detectReactive(final List<CollisionEntry> entriesFirst,
                                final List<CollisionEntry> entriesSecond,
                                final HashSet<CoreAbility> alreadyCollided,
                                final HashSet<Long> checkedPairs) {
        final HashMap<Long, CollisionCandidate> earliest = new HashMap<>();
        for (final CollisionEntry currentFirst : entriesFirst) {
            for (final CollisionEntry currentSecond : entriesSecond) {
                if (currentFirst.ability == currentSecond.ability
                        || currentFirst.playerId.equals(currentSecond.playerId)) continue;
                final long alignedTick = Math.min(currentFirst.timelineTick,
                        currentSecond.timelineTick);
                final CollisionEntry first = this.atTimelineTick(currentFirst, alignedTick);
                final CollisionEntry second = this.atTimelineTick(currentSecond, alignedTick);
                if (first == null || second == null || first.world != second.world
                        || !first.intersectsBounds(second)) continue;

                final double contact = SweptSphereContact.firstContact(
                        first.previousX, first.previousY, first.previousZ,
                        first.x, first.y, first.z,
                        second.previousX, second.previousY, second.previousZ,
                        second.x, second.y, second.z,
                        first.radius + second.radius);
                if (Double.isNaN(contact)) continue;
                final long pair = collisionPairKey(first.ability, second.ability);
                final CollisionCandidate prior = earliest.get(pair);
                if (prior == null || contact < prior.contactFraction) {
                    earliest.put(pair, new CollisionCandidate(first, second, contact));
                }
            }
        }

        final ArrayList<CollisionCandidate> ordered = new ArrayList<>(earliest.values());
        ordered.sort(Comparator
                .comparingDouble((CollisionCandidate candidate) -> candidate.contactFraction)
                .thenComparing(candidate -> stableAbilityKey(candidate.first.ability))
                .thenComparing(candidate -> stableAbilityKey(candidate.second.ability)));
        for (final CollisionCandidate candidate : ordered) {
            if (candidate.first.ability.isRemoved() || candidate.second.ability.isRemoved()) continue;
            if (this.resolveCollision(candidate.first, candidate.second,
                    candidate.contactFraction, alreadyCollided, checkedPairs)
                    && !this.removeMultipleInstances) {
                // Continue iterating so a candidate not involving either
                // consumed ability can still resolve in deterministic order.
            }
        }
    }

    private static String stableAbilityKey(final CoreAbility ability) {
        return (ability.getPlayer() == null ? "" : ability.getPlayer().getUniqueId().toString())
                + '|' + ability.getClass().getName() + '|' + ability.getId();
    }

    private void detectDirect(final List<CollisionEntry> entriesFirst, final List<CollisionEntry> entriesSecond, final HashSet<CoreAbility> alreadyCollided, final HashSet<Long> checkedPairs) {
        for (final CollisionEntry entryFirst : entriesFirst) {
            final CoreAbility abilityFirst = entryFirst.ability;
            if (alreadyCollided.contains(abilityFirst)) {
                continue;
            }

            for (final CollisionEntry entrySecond : entriesSecond) {
                if (this.tryCollide(entryFirst, entrySecond, alreadyCollided, checkedPairs) && !this.removeMultipleInstances) {
                    break;
                }
            }
        }
    }

    private boolean tryCollide(final CollisionEntry entryFirst, final CollisionEntry entrySecond, final HashSet<CoreAbility> alreadyCollided, final HashSet<Long> checkedPairs) {
        final CoreAbility abilityFirst = entryFirst.ability;
        final CoreAbility abilitySecond = entrySecond.ability;
        if (alreadyCollided.contains(abilityFirst) || alreadyCollided.contains(abilitySecond) || entryFirst.playerId.equals(entrySecond.playerId)) {
            return false;
        }

        if (entryFirst.world != entrySecond.world || !entryFirst.intersectsBounds(entrySecond)) {
            return false;
        }

        final long pair = collisionPairKey(abilityFirst, abilitySecond);
        if (checkedPairs.contains(pair)) {
            return false;
        }

        final Collision activeCollision = this.getCollision(abilityFirst, abilitySecond);
        if (activeCollision == null) {
            return false;
        }

        final double contact = SweptSphereContact.firstContact(
                entryFirst.x, entryFirst.y, entryFirst.z, entryFirst.x, entryFirst.y, entryFirst.z,
                entrySecond.x, entrySecond.y, entrySecond.z, entrySecond.x, entrySecond.y, entrySecond.z,
                entryFirst.radius + entrySecond.radius);
        if (Double.isNaN(contact)) {
            return false;
        }

        return this.resolveCollision(entryFirst, entrySecond, contact,
                alreadyCollided, checkedPairs);
    }

    private boolean resolveCollision(final CollisionEntry entryFirst,
                                     final CollisionEntry entrySecond,
                                     final double contactFraction,
                                     final HashSet<CoreAbility> alreadyCollided,
                                     final HashSet<Long> checkedPairs) {
        final CoreAbility abilityFirst = entryFirst.ability;
        final CoreAbility abilitySecond = entrySecond.ability;
        if (alreadyCollided.contains(abilityFirst) || alreadyCollided.contains(abilitySecond)
                || abilityFirst.isRemoved() || abilitySecond.isRemoved()) return false;

        final long pair = collisionPairKey(abilityFirst, abilitySecond);
        if (!checkedPairs.add(pair)) return false;
        final Collision activeCollision = this.getCollision(abilityFirst, abilitySecond);
        if (activeCollision == null) return false;

        final Location firstContact = entryFirst.locationAt(contactFraction);
        final Location secondContact = entrySecond.locationAt(contactFraction);
        final Collision forwardCollision = new Collision(abilityFirst, abilitySecond,
                activeCollision.isRemovingFirst(), activeCollision.isRemovingSecond(),
                firstContact, secondContact);
        final AbilityCollisionEvent event = new AbilityCollisionEvent(forwardCollision);
        AbilityRemovalSync.runExternalCause(() -> Platform.events().call(event));
        if (event.isCancelled()) {
            return false;
        }
        AbilityRemovalSync.runExternalCause(() -> {
            final Collision reverseCollision = new Collision(abilitySecond, abilityFirst,
                    forwardCollision.isRemovingSecond(), forwardCollision.isRemovingFirst(),
                    forwardCollision.getLocationSecond(), forwardCollision.getLocationFirst());
            AirFireCombat.resolveCollision(forwardCollision,
                    Math.min(entryFirst.timelineTick, entrySecond.timelineTick), () -> {
                if (!isEarthSmashCollision(forwardCollision)) {
                    ElementalCollisionEffects.play(forwardCollision);
                }
                abilityFirst.handleCollision(forwardCollision);
                abilitySecond.handleCollision(reverseCollision);
            });
        });
        if (!this.removeMultipleInstances) {
            alreadyCollided.add(abilityFirst);
            alreadyCollided.add(abilitySecond);
        }
        return true;
    }

    private Collision getCollision(final CoreAbility abilityFirst, final CoreAbility abilitySecond) {
        return this.collisionLookup.get(collisionKey(abilityFirst.getClass(), abilitySecond.getClass()));
    }

    private List<CollisionEntry> getCollisionEntries(final Class<? extends CoreAbility> clazz) {
        if (this.entriesCache.containsKey(clazz)) {
            return this.entriesCache.get(clazz);
        }

        final Collection<? extends CoreAbility> instances = CoreAbility.getAbilities(clazz);
        if (instances.isEmpty()) {
            this.entriesCache.put(clazz, Collections.emptyList());
            return this.entriesCache.get(clazz);
        }

        final ArrayList<CollisionEntry> entries = new ArrayList<CollisionEntry>(instances.size());
        for (final CoreAbility ability : instances) {
            if (ability.getPlayer() == null || !ability.isCollidable()) {
                continue;
            }

            List<Location> locations = this.locationsCache.get(ability);
            if (locations == null) {
                locations = ability.getLocations();
                this.locationsCache.put(ability, locations);
            }
            if (locations == null || locations.isEmpty()) {
                continue;
            }

            final double radius = Math.max(0, ability.getCollisionRadius());
            final long timelineTick = this.collisionTick - AirFireCombat.timelineOffset(ability);
            for (int index = 0; index < locations.size(); index++) {
                final Location location = locations.get(index);
                if (location != null && location.getWorld() != null) {
                    entries.add(this.entry(ability, location, radius, index, timelineTick));
                }
            }
        }

        this.entriesCache.put(clazz, entries);
        return entries;
    }

    private CollisionEntry entry(final CoreAbility ability, final Location location,
                                 final double radius, final int locationIndex,
                                 final long timelineTick) {
        final LocationPoint previous = this.pointAt(ability, timelineTick - 1L, locationIndex);
        return new CollisionEntry(ability, location, radius, locationIndex, timelineTick,
                previous == null || previous.world != location.getWorld() ? location.getX() : previous.x,
                previous == null || previous.world != location.getWorld() ? location.getY() : previous.y,
                previous == null || previous.world != location.getWorld() ? location.getZ() : previous.z);
    }

    private CollisionEntry atTimelineTick(final CollisionEntry current, final long timelineTick) {
        if (current.timelineTick == timelineTick) return current;
        final LocationPoint point = this.pointAt(current.ability, timelineTick,
                current.locationIndex);
        if (point == null) return null;
        final LocationPoint previous = this.pointAt(current.ability, timelineTick - 1L,
                current.locationIndex);
        final Location location = new Location(point.world, point.x, point.y, point.z);
        return new CollisionEntry(current.ability, location, current.radius,
                current.locationIndex, timelineTick,
                previous == null || previous.world != point.world ? point.x : previous.x,
                previous == null || previous.world != point.world ? point.y : previous.y,
                previous == null || previous.world != point.world ? point.z : previous.z);
    }

    private LocationPoint pointAt(final CoreAbility ability, final long timelineTick,
                                  final int locationIndex) {
        final ArrayDeque<AbilityFrame> frames = this.reactiveHistory.get(ability);
        if (frames == null) return null;
        for (final AbilityFrame frame : frames) {
            if (frame.timelineTick != timelineTick) continue;
            return locationIndex >= 0 && locationIndex < frame.locations.size()
                    ? frame.locations.get(locationIndex) : null;
        }
        return null;
    }

    private void captureReactiveHistory() {
        final Set<CoreAbility> live = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
            if (ability.getPlayer() == null || ability.isRemoved() || !ability.isCollidable()
                    || !AirFireCombat.isTimelineManaged(ability)) continue;
            live.add(ability);
            List<Location> locations = this.locationsCache.get(ability);
            if (locations == null) {
                locations = ability.getLocations();
                this.locationsCache.put(ability, locations);
            }
            if (locations == null || locations.isEmpty()) continue;
            final ArrayList<LocationPoint> points = new ArrayList<>(locations.size());
            for (final Location location : locations) {
                points.add(location == null || location.getWorld() == null ? null
                        : new LocationPoint(location.getWorld(), location.getX(),
                        location.getY(), location.getZ()));
            }
            final ArrayDeque<AbilityFrame> frames = this.reactiveHistory.computeIfAbsent(
                    ability, ignored -> new ArrayDeque<>());
            final long timelineTick = this.collisionTick - AirFireCombat.timelineOffset(ability);
            if (!frames.isEmpty() && frames.getLast().timelineTick == timelineTick) frames.removeLast();
            frames.addLast(new AbilityFrame(timelineTick,
                    Collections.unmodifiableList(points)));
            while (frames.size() > AirFireCombat.MAX_ROLLBACK_TICKS + 2) frames.removeFirst();
        }
        this.reactiveHistory.entrySet().removeIf(entry -> !live.contains(entry.getKey())
                && (entry.getValue().isEmpty() || this.collisionTick
                - entry.getValue().getLast().timelineTick > AirFireCombat.MAX_ROLLBACK_TICKS + 2L));
    }

    /**
     * Adds a "fake" Collision to the CollisionManager so that two abilities can
     * be checked for collisions. This Collision only needs to define the
     * abilityFirst, abilitySecond, removeFirst, and removeSecond.
     *
     * @param collision a Collision containing two CoreAbility classes
     */
    public void addCollision(final Collision collision) {
        if (collision == null || collision.getAbilityFirst() == null || collision.getAbilitySecond() == null) {
            return;
        }

        final String key = collisionKey(collision.getAbilityFirst().getClass(), collision.getAbilitySecond().getClass());
        if (this.disabledCollisionKeys.contains(key)) {
            return;
        }
        final Collision existingCollision = this.collisionLookup.remove(key);
        if (existingCollision != null) {
            this.collisions.remove(existingCollision);
        }

        for (int x = 0; x < this.collisions.size(); x++) {
            final Collision existing = this.collisions.get(x);
            final boolean sameDirection = existing.getAbilityFirst().equals(collision.getAbilityFirst()) && existing.getAbilitySecond().equals(collision.getAbilitySecond());
            final boolean reverseDirection = existing.getAbilityFirst().equals(collision.getAbilitySecond()) && existing.getAbilitySecond().equals(collision.getAbilityFirst());
            if (sameDirection || reverseDirection) {
                this.collisions.remove(x--);
            }
        }

        this.collisions.add(collision);
        this.collisionLookup.put(key, collision);
    }

    /**
     * Persistently disables a collision pair for this manager. Addons may
     * register their collisions a few ticks after the core registry; retaining
     * the key prevents those late registrations from bypassing collision.yml.
     */
    public void disableCollision(final CoreAbility first, final CoreAbility second) {
        if (first == null || second == null) return;
        final String key = collisionKey(first.getClass(), second.getClass());
        this.disabledCollisionKeys.add(key);
        final Collision existing = this.collisionLookup.remove(key);
        if (existing != null) this.collisions.remove(existing);
    }

    /**
     * Re-enables a pair before applying an explicit AddCollisions entry.
     */
    public void enableCollision(final CoreAbility first, final CoreAbility second) {
        if (first == null || second == null) return;
        this.disabledCollisionKeys.remove(collisionKey(first.getClass(), second.getClass()));
    }

    public boolean removeCollision(final Collision collision) {
        if (collision == null || collision.getAbilityFirst() == null || collision.getAbilitySecond() == null) {
            return false;
        }

        final String key = collisionKey(collision.getAbilityFirst().getClass(), collision.getAbilitySecond().getClass());
        final Collision existing = this.collisionLookup.remove(key);
        if (existing == null) {
            return false;
        }

        this.collisions.remove(existing);
        return true;
    }

    /**
     * Legacy hook retained for compatibility. Collision detection is now driven
     * by the main ability tick in {@link com.projectkorra.projectkorra.BendingManager}.
     */
    public void startCollisionDetection() {
        this.stopCollisionDetection();
    }

    /**
     * Stops the collision detecting BukkitRunnable.
     */
    public void stopCollisionDetection() {
        if (this.detectionRunnable != null) {
            this.detectionRunnable.cancel();
            this.detectionRunnable = null;
        }
    }

    public boolean isRemoveMultipleInstances() {
        return this.removeMultipleInstances;
    }

    public void setRemoveMultipleInstances(final boolean removeMultipleInstances) {
        this.removeMultipleInstances = removeMultipleInstances;
    }

    public long getDetectionDelay() {
        return this.detectionDelay;
    }

    public void setDetectionDelay(final long detectionDelay) {
        this.detectionDelay = detectionDelay;
    }

    public double getCertainNoCollisionDistance() {
        return this.certainNoCollisionDistance;
    }

    public void setCertainNoCollisionDistance(final double certainNoCollisionDistance) {
        this.certainNoCollisionDistance = certainNoCollisionDistance;
    }

    public ArrayList<Collision> getCollisions() {
        return this.collisions;
    }

    public void setCollisions(final ArrayList<Collision> collisions) {
        this.collisions = collisions;
    }

    public BukkitRunnable getDetectionRunnable() {
        return this.detectionRunnable;
    }

    public void setDetectionRunnable(final BukkitRunnable detectionRunnable) {
        this.detectionRunnable = detectionRunnable;
    }

    private static final class CollisionEntry {
        private final CoreAbility ability;
        private final Location location;
        private final World world;
        private final UUID playerId;
        private final double radius;
        private final double x;
        private final double y;
        private final double z;
        private final int locationIndex;
        private final long timelineTick;
        private final double previousX;
        private final double previousY;
        private final double previousZ;
        private final double minX;
        private final double minY;
        private final double minZ;
        private final double maxX;
        private final double maxY;
        private final double maxZ;

        private CollisionEntry(final CoreAbility ability, final Location location, final double radius,
                               final int locationIndex, final long timelineTick,
                               final double previousX, final double previousY,
                               final double previousZ) {
            this.ability = ability;
            this.location = location;
            this.world = location.getWorld();
            this.playerId = ability.getPlayer().getUniqueId();
            this.radius = radius;
            this.x = location.getX();
            this.y = location.getY();
            this.z = location.getZ();
            this.locationIndex = locationIndex;
            this.timelineTick = timelineTick;
            this.previousX = previousX;
            this.previousY = previousY;
            this.previousZ = previousZ;
            this.minX = Math.min(this.x, previousX) - radius;
            this.minY = Math.min(this.y, previousY) - radius;
            this.minZ = Math.min(this.z, previousZ) - radius;
            this.maxX = Math.max(this.x, previousX) + radius;
            this.maxY = Math.max(this.y, previousY) + radius;
            this.maxZ = Math.max(this.z, previousZ) + radius;
        }

        private boolean intersectsBounds(final CollisionEntry other) {
            return this.minX <= other.maxX && this.maxX >= other.minX &&
                    this.minY <= other.maxY && this.maxY >= other.minY &&
                    this.minZ <= other.maxZ && this.maxZ >= other.minZ;
        }

        private Location locationAt(final double fraction) {
            final double bounded = Math.max(0.0, Math.min(1.0, fraction));
            return new Location(this.world,
                    this.previousX + (this.x - this.previousX) * bounded,
                    this.previousY + (this.y - this.previousY) * bounded,
                    this.previousZ + (this.z - this.previousZ) * bounded);
        }
    }

    private record CollisionCandidate(CollisionEntry first, CollisionEntry second,
                                      double contactFraction) {
    }

    private record LocationPoint(World world, double x, double y, double z) {
    }

    private record AbilityFrame(long timelineTick, List<LocationPoint> locations) {
    }

    private static final class LBVH {
        private final HashMap<World, Node> roots;

        private LBVH(final HashMap<World, Node> roots) {
            this.roots = roots;
        }

        private static LBVH build(final List<CollisionEntry> entries) {
            final HashMap<World, ArrayList<CollisionEntry>> entriesByWorld = new HashMap<World, ArrayList<CollisionEntry>>();
            for (final CollisionEntry entry : entries) {
                entriesByWorld.computeIfAbsent(entry.world, world -> new ArrayList<CollisionEntry>()).add(entry);
            }

            final HashMap<World, Node> roots = new HashMap<World, Node>();
            for (final Map.Entry<World, ArrayList<CollisionEntry>> worldEntries : entriesByWorld.entrySet()) {
                roots.put(worldEntries.getKey(), buildWorld(worldEntries.getValue()));
            }
            return new LBVH(roots);
        }

        private static Node buildWorld(final List<CollisionEntry> entries) {
            final ArrayList<MortonEntry> mortonEntries = new ArrayList<MortonEntry>(entries.size());
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;

            for (final CollisionEntry entry : entries) {
                minX = Math.min(minX, entry.x);
                minY = Math.min(minY, entry.y);
                minZ = Math.min(minZ, entry.z);
                maxX = Math.max(maxX, entry.x);
                maxY = Math.max(maxY, entry.y);
                maxZ = Math.max(maxZ, entry.z);
            }

            final double sizeX = Math.max(maxX - minX, 1.0E-9);
            final double sizeY = Math.max(maxY - minY, 1.0E-9);
            final double sizeZ = Math.max(maxZ - minZ, 1.0E-9);
            for (final CollisionEntry entry : entries) {
                final int x = quantize((entry.x - minX) / sizeX);
                final int y = quantize((entry.y - minY) / sizeY);
                final int z = quantize((entry.z - minZ) / sizeZ);
                mortonEntries.add(new MortonEntry(entry, morton3D(x, y, z)));
            }

            mortonEntries.sort(Comparator.comparingLong(mortonEntry -> mortonEntry.code));
            return buildNode(mortonEntries, 0, mortonEntries.size() - 1);
        }

        private static Node buildNode(final List<MortonEntry> entries, final int first, final int last) {
            if (first == last) {
                return new Node(entries.get(first).entry);
            }

            final int split = findSplit(entries, first, last);
            return new Node(buildNode(entries, first, split), buildNode(entries, split + 1, last));
        }

        private static int findSplit(final List<MortonEntry> entries, final int first, final int last) {
            final long firstCode = entries.get(first).code;
            final long lastCode = entries.get(last).code;
            if (firstCode == lastCode) {
                return (first + last) >>> 1;
            }

            final int commonPrefix = Long.numberOfLeadingZeros(firstCode ^ lastCode);
            int split = first;
            int step = last - first;

            do {
                step = (step + 1) >>> 1;
                final int newSplit = split + step;
                if (newSplit < last) {
                    final long splitCode = entries.get(newSplit).code;
                    final int splitPrefix = Long.numberOfLeadingZeros(firstCode ^ splitCode);
                    if (splitPrefix > commonPrefix) {
                        split = newSplit;
                    }
                }
            } while (step > 1);

            return split;
        }

        private static int quantize(final double value) {
            return Math.max(0, Math.min(1023, (int) (value * 1023.0)));
        }

        private static long morton3D(final int x, final int y, final int z) {
            long code = 0;
            for (int bit = 0; bit < 10; bit++) {
                code |= ((long) (x >> bit) & 1L) << (3 * bit + 2);
                code |= ((long) (y >> bit) & 1L) << (3 * bit + 1);
                code |= ((long) (z >> bit) & 1L) << (3 * bit);
            }
            return code;
        }

        private void query(final CollisionEntry entry, final List<CollisionEntry> result) {
            final Node root = this.roots.get(entry.world);
            if (root != null) {
                root.query(entry, entry.playerId, result);
            }
        }
    }

    private static final class MortonEntry {
        private final CollisionEntry entry;
        private final long code;

        private MortonEntry(final CollisionEntry entry, final long code) {
            this.entry = entry;
            this.code = code;
        }
    }

    private static final class Node {
        private final CollisionEntry entry;
        private final Node left;
        private final Node right;
        private final World world;
        private final UUID playerId;
        private final double minX;
        private final double minY;
        private final double minZ;
        private final double maxX;
        private final double maxY;
        private final double maxZ;

        private Node(final CollisionEntry entry) {
            this.entry = entry;
            this.left = null;
            this.right = null;
            this.world = entry.world;
            this.playerId = entry.playerId;
            this.minX = entry.minX;
            this.minY = entry.minY;
            this.minZ = entry.minZ;
            this.maxX = entry.maxX;
            this.maxY = entry.maxY;
            this.maxZ = entry.maxZ;
        }

        private Node(final Node left, final Node right) {
            this.entry = null;
            this.left = left;
            this.right = right;
            this.world = left.world == right.world ? left.world : null;
            this.playerId = left.playerId != null && left.playerId.equals(right.playerId) ? left.playerId : null;
            this.minX = Math.min(left.minX, right.minX);
            this.minY = Math.min(left.minY, right.minY);
            this.minZ = Math.min(left.minZ, right.minZ);
            this.maxX = Math.max(left.maxX, right.maxX);
            this.maxY = Math.max(left.maxY, right.maxY);
            this.maxZ = Math.max(left.maxZ, right.maxZ);
        }

        private void query(final CollisionEntry query, final UUID excludedPlayerId, final List<CollisionEntry> result) {
            if ((this.playerId != null && this.playerId.equals(excludedPlayerId)) ||
                    (this.world != null && this.world != query.world) || !this.intersects(query)) {
                return;
            }

            if (this.entry != null) {
                result.add(this.entry);
                return;
            }

            this.left.query(query, excludedPlayerId, result);
            this.right.query(query, excludedPlayerId, result);
        }

        private boolean intersects(final CollisionEntry query) {
            return this.minX <= query.maxX && this.maxX >= query.minX &&
                    this.minY <= query.maxY && this.maxY >= query.minY &&
                    this.minZ <= query.maxZ && this.maxZ >= query.minZ;
        }
    }
}
