package com.projectkorra.projectkorra.ability.util;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.PassiveAbility;
import com.projectkorra.projectkorra.earthbending.EarthSmash;
import com.projectkorra.projectkorra.event.AbilityCollisionEvent;
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
public class CollisionManager {

    private static final int LBVH_PAIR_THRESHOLD = 128;
    private final HashMap<String, Collision> collisionLookup;
    private final HashSet<String> disabledCollisionKeys;
    private final HashMap<CoreAbility, List<Location>> locationsCache;
    private final HashMap<Class<? extends CoreAbility>, List<CollisionEntry>> entriesCache;
    private final HashMap<Class<? extends CoreAbility>, LBVH> indexCache;
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
    }

    private static boolean isEarthSmashCollision(final Collision collision) {
        return collision.getAbilityFirst() instanceof EarthSmash || collision.getAbilitySecond() instanceof EarthSmash;
    }

    private static long collisionPairKey(final CoreAbility first, final CoreAbility second) {
        return ((long) first.getId() << 32) ^ (second.getId() & 0xffffffffL);
    }

    private static String collisionKey(final Class<? extends CoreAbility> first, final Class<? extends CoreAbility> second) {
        final String firstName = first.getName();
        final String secondName = second.getName();
        return firstName.compareTo(secondName) <= 0 ? firstName + "|" + secondName : secondName + "|" + firstName;
    }

    public void detectCollisions() {
        int activeInstanceCount = 0;

        for (final CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
            if (!(ability instanceof PassiveAbility)) {
                if (++activeInstanceCount > 1) {
                    break;
                }
            }
        }

        if (activeInstanceCount <= 1) {
            return;
        }

        this.locationsCache.clear();
        this.entriesCache.clear();
        this.indexCache.clear();

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

            if (possiblePairCount <= LBVH_PAIR_THRESHOLD) {
                this.detectDirect(collision, entriesFirst, entriesSecond, alreadyCollided, checkedPairs);
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
                    if (this.tryCollide(collision, entryFirst, entrySecond, alreadyCollided, checkedPairs) && !this.removeMultipleInstances) {
                        break;
                    }
                }
            }
        }
    }

    private void detectDirect(final Collision collision, final List<CollisionEntry> entriesFirst, final List<CollisionEntry> entriesSecond, final HashSet<CoreAbility> alreadyCollided, final HashSet<Long> checkedPairs) {
        for (final CollisionEntry entryFirst : entriesFirst) {
            final CoreAbility abilityFirst = entryFirst.ability;
            if (alreadyCollided.contains(abilityFirst)) {
                continue;
            }

            for (final CollisionEntry entrySecond : entriesSecond) {
                if (this.tryCollide(collision, entryFirst, entrySecond, alreadyCollided, checkedPairs) && !this.removeMultipleInstances) {
                    break;
                }
            }
        }
    }

    private boolean tryCollide(final Collision collision, final CollisionEntry entryFirst, final CollisionEntry entrySecond, final HashSet<CoreAbility> alreadyCollided, final HashSet<Long> checkedPairs) {
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

        final double dx = entryFirst.x - entrySecond.x;
        final double dy = entryFirst.y - entrySecond.y;
        final double dz = entryFirst.z - entrySecond.z;
        final double requiredDist = entryFirst.radius + entrySecond.radius;
        if (dx * dx + dy * dy + dz * dz > requiredDist * requiredDist) {
            return false;
        }

        checkedPairs.add(pair);
        final Collision forwardCollision = new Collision(abilityFirst, abilitySecond, activeCollision.isRemovingFirst(), activeCollision.isRemovingSecond(), entryFirst.location, entrySecond.location);
        final Collision reverseCollision = new Collision(abilitySecond, abilityFirst, activeCollision.isRemovingSecond(), activeCollision.isRemovingFirst(), entrySecond.location, entryFirst.location);
        final AbilityCollisionEvent event = new AbilityCollisionEvent(forwardCollision);
        Platform.events().call(event);
        if (event.isCancelled()) {
            return false;
        }
        if (!isEarthSmashCollision(forwardCollision)) {
            ElementalCollisionEffects.play(forwardCollision);
        }
        abilityFirst.handleCollision(forwardCollision);
        abilitySecond.handleCollision(reverseCollision);
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
            for (final Location location : locations) {
                if (location != null && location.getWorld() != null) {
                    entries.add(new CollisionEntry(ability, location, radius));
                }
            }
        }

        this.entriesCache.put(clazz, entries);
        return entries;
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
        private final double minX;
        private final double minY;
        private final double minZ;
        private final double maxX;
        private final double maxY;
        private final double maxZ;

        private CollisionEntry(final CoreAbility ability, final Location location, final double radius) {
            this.ability = ability;
            this.location = location;
            this.world = location.getWorld();
            this.playerId = ability.getPlayer().getUniqueId();
            this.radius = radius;
            this.x = location.getX();
            this.y = location.getY();
            this.z = location.getZ();
            this.minX = this.x - radius;
            this.minY = this.y - radius;
            this.minZ = this.z - radius;
            this.maxX = this.x + radius;
            this.maxY = this.y + radius;
            this.maxZ = this.z + radius;
        }

        private boolean intersectsBounds(final CollisionEntry other) {
            return this.minX <= other.maxX && this.maxX >= other.minX &&
                    this.minY <= other.maxY && this.maxY >= other.minY &&
                    this.minZ <= other.maxZ && this.maxZ >= other.minZ;
        }
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
