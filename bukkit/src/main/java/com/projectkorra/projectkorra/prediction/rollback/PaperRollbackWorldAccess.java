package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Box;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackMovementSolver;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.EntityLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;

/**
 * Private server-level read boundary for Paper's native movement code. Moonrise's
 * chunk/palette reads see current logical blocks, including after a restore. No
 * native level/chunk constructors run, no live world is retained, and unconfigured
 * world calls fail. This is not a complete server-level tick or damage adapter.
 */
public final class PaperRollbackWorldAccess implements RollbackStateCell<Void> {
    /** Native combat dependencies over captured state; no live-server fallbacks. */
    public record WorldPolicy(org.bukkit.World.Environment environment, boolean voidDamageEnabled, float voidDamageAmount,
                              double voidDamageHeightOffset, java.util.OptionalInt netherCeilingHeight) {
        public WorldPolicy {
            Objects.requireNonNull(environment, "environment"); Objects.requireNonNull(netherCeilingHeight, "netherCeilingHeight");
            if (!Float.isFinite(voidDamageAmount) || voidDamageAmount < 0 || !Double.isFinite(voidDamageHeightOffset)) {
                throw new IllegalArgumentException("Captured void damage policy");
            }
        }
    }
    public interface Combat<S> extends RollbackStateCell<S> {
        Object registryAccess();
        Object difficulty();
        Object random();
        default net.minecraft.world.flag.FeatureFlagSet terrainFeatures() { throw new IllegalStateException("Terrain feature flags were not supplied"); }
        default RollbackStructurePieces structures() { return RollbackStructurePieces.EMPTY; }
        default RollbackWorldSettings.ArrowLifetime arrowLifetime() { return RollbackWorldSettings.ArrowLifetime.DEFAULT; }
        default RollbackItemRules itemRules() { return RollbackItemRules.DEFAULT; }
        default java.util.Map<String, Integer> actorDespawnTimes() { return java.util.Map.of(); }
        default boolean splitOverstackedLoot() { return true; }
        Object rule(Object nativeRule);
        long time();
        long nextSoundSeed();
        boolean skipVanillaDamageTickWhenShieldBlocked();
        boolean updateEquipmentOnPlayerActions();
        boolean allowNonPlayerEntitiesOnScoreboards();
        boolean pvpAllowed();
        boolean allowPlayerCrammingDamage();
        int maximumEntityCollisions();
        float jumpExhaustion(boolean sprinting);
        WorldPolicy worldPolicy();
        int containerUpdateRate();
        float regenerationExhaustion();
        default boolean disablePlayerCrits() { return false; }
        default boolean disableSprintInterruptionOnAttack() { return false; }
        default float combatExhaustion() { return .1F; }
        boolean parrotsStayOnShoulder();
        void event(org.bukkit.event.Event event);
        void gameEvent(Object nativeEvent, Object position, Object context);
        void output(PaperRollbackCombatAccess.Output output);
    }
    public interface Queries<S> extends RollbackStateCell<S> {
        int minimumY();
        int height();
        Object blockState(int x, int y, int z);
        Object blockEntity(int x, int y, int z);
        Object worldBorder();
        /** Private native entities intersecting the bounds, excluding except. Predicate filtering is applied by the bridge. */
        List<?> entities(Object except, Box bounds, boolean hardCollidingOnly);
        List<net.minecraft.world.phys.shapes.VoxelShape> getEntityCollisions(Entity except, AABB bounds);
        boolean isUnobstructed(Entity except, net.minecraft.world.phys.shapes.VoxelShape shape);
        /** Native CollisionGetter queries not handled by Moonrise's chunk/palette iteration. */
        Object collisionQuery(String method, Object[] arguments);
        default net.minecraft.world.phys.BlockHitResult projectileRay(net.minecraft.world.level.ClipContext ray) {
            throw new IllegalStateException("Native projectile queries require captured collision geometry");
        }
        boolean raining();
        boolean skyVisible(BlockPos position);
        BlockPos topPosition(net.minecraft.world.level.levelgen.Heightmap.Types type, BlockPos position);
        net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome(BlockPos position);
        int seaLevel();
        /** Captured availability; must reject queries beyond the session's captured region. */
        boolean chunksLoaded(int minimumX, int minimumZ, int maximumX, int maximumZ);
        /** Chunk coordinates; a partially captured edge chunk is allowed, but its block reads remain bounded. */
        boolean chunkLoaded(int x, int z);
        default boolean hasCapturedChunk(int x, int z) { return chunkLoaded(x, z); }
        net.minecraft.world.attribute.EnvironmentAttributeReader environmentAttributes();
    }

    private static final class Native {
        @SuppressWarnings("unchecked")
        static final Class<PalettedContainer<BlockState>> PALETTE = (Class<PalettedContainer<BlockState>>) (Class<?>) PalettedContainer.class;
        static final BlockState AIR = Blocks.AIR.defaultBlockState();
        static final FluidState FLUID = AIR.getFluidState();
        static final AABB BOX = new AABB(0, 0, 0, 1, 1, 1);
    }

    private PaperRollbackScheduledTicks scheduledTicks;
    void bindTicks(PaperRollbackScheduledTicks ticks) {
        checkThread();
        if (scheduledTicks != null) throw new IllegalStateException("Native tick scheduler already bound");
        scheduledTicks = Objects.requireNonNull(ticks);
    }
    private PaperRollbackScheduledTicks scheduled() {
        checkThread();
        if (scheduledTicks == null) throw new IllegalStateException("Private scheduled terrain was not supplied");
        return scheduledTicks;
    }
    private PaperRollbackBlockRules terrainRules;
    void bindTerrain(PaperRollbackBlockRules rules) {
        checkThread();
        if (terrainRules != null) throw new IllegalStateException("Terrain rules already bound");
        terrainRules = Objects.requireNonNull(rules);
    }
    private PaperRollbackBlockRules terrain() {
        checkThread();
        if (terrainRules == null) throw new IllegalStateException("Private native terrain rules were not supplied");
        return terrainRules;
    }
    private final Thread thread = Thread.currentThread();
    private java.util.function.Consumer<Runnable> bootstrapCompiler;
    private final Queries<?> queries;
    private final int minimumY, height, minimumSection, sectionCount, maximumChunks;
    private final Map<Long, LevelChunk> chunks = new HashMap<>();
    private final Map<Object, PaperRollbackNativePlayerState> players = new IdentityHashMap<>();
    private final Map<Object, Boolean> playerWrappers = new IdentityHashMap<>();
    private final List<PaperRollbackNativePlayerState> playerOrder = new ArrayList<>();
    private boolean playersSealed, checkpointed;
    private final ServerLevel world;
    private final Combat<?> combatState;
    private final PaperRollbackCombatAccess combat;
    private final PaperRollbackScoreboards scoreboards;
    private final PaperRollbackPacketData packetData;
    private PaperRollbackRoundEvents roundEvents;
    private RollbackActorRegistry actors;

    public PaperRollbackWorldAccess(Queries<?> queries, int maximumChunks) {
        this(queries, null, maximumChunks);
    }

    public PaperRollbackWorldAccess(Queries<?> queries, Combat<?> combatState, int maximumChunks) {
        this(queries, combatState, maximumChunks, Runnable::run);
    }

    PaperRollbackWorldAccess(Queries<?> queries, Combat<?> combatState, int maximumChunks,
            java.util.function.Consumer<Runnable> compile) {
        this.bootstrapCompiler = Objects.requireNonNull(compile);
        this.queries = Objects.requireNonNull(queries, "queries");
        this.combatState = combatState;
        packetData = combatState == null ? null : new PaperRollbackPacketData((net.minecraft.core.RegistryAccess) combatState.registryAccess());
        minimumY = queries.minimumY(); height = queries.height();
        if ((minimumY & 15) != 0 || height <= 0 || height > 4_096 || (height & 15) != 0 || maximumChunks < 1) {
            throw new IllegalArgumentException("Native world dimensions or chunk budget");
        }
        int maximumY = Math.addExact(minimumY, height) - 1;
        minimumSection = minimumY >> 4;
        sectionCount = height >> 4;
        this.maximumChunks = maximumChunks;
        ServerChunkCache source = RollbackNativeQueryShell.create(ServerChunkCache.class)
                .query(value -> value.hasChunk(0, 0), false, args -> queries.hasCapturedChunk((int) args[0], (int) args[1]))
                .query(value -> value.getChunk(0, 0, ChunkStatus.FULL, false), null, args -> chunk((int) args[0], (int) args[1]))
                .query(value -> value.getChunkAtIfLoadedImmediately(0, 0), null, args -> chunk((int) args[0], (int) args[1]))
                .outputQuery(value -> value.sendToTrackingPlayers(null, null), args -> trackedPacket((Entity) args[0], (net.minecraft.network.protocol.Packet<?>) args[1], false))
                .outputQuery(value -> value.sendToTrackingPlayersAndSelf(null, null), args -> trackedPacket((Entity) args[0], (net.minecraft.network.protocol.Packet<?>) args[1], true))
                .instance();
        EntityLookup lookup = RollbackNativeQueryShell.create(EntityLookup.class)
                .outputQuery(value -> value.getHardCollidingEntities(null, Native.BOX, new ArrayList<>(), null), args ->
                        outputEntities(args[2], entities(args[0], args[1], true, args[3])))
                .instance();
        var environment = RollbackNativeQueryShell.create(net.minecraft.world.attribute.EnvironmentAttributeSystem.class)
                .query(value -> value.getDimensionValue(net.minecraft.world.attribute.EnvironmentAttributes.FAST_LAVA), false, this::environmentValue)
                .query(value -> value.getValue(net.minecraft.world.attribute.EnvironmentAttributes.FAST_LAVA, BlockPos.ZERO), false, this::environmentValue)
                .query(value -> value.getValue(net.minecraft.world.attribute.EnvironmentAttributes.FAST_LAVA, net.minecraft.world.phys.Vec3.ZERO), false, this::environmentValue)
                .query(value -> value.getValue(net.minecraft.world.attribute.EnvironmentAttributes.FAST_LAVA, net.minecraft.world.phys.Vec3.ZERO, null), false, this::environmentValue)
                .instance();
        var shell = RollbackNativeQueryShell.create(ServerLevel.class)
                .constant(ServerLevel::isClientSide, false)
                .constant(ServerLevel::environmentAttributes, environment)
                .constant(ServerLevel::getMinY, minimumY)
                .constant(ServerLevel::getMaxY, maximumY)
                .constant(ServerLevel::getHeight, height)
                .constant(ServerLevel::getMinSectionY, minimumSection)
                .constant(ServerLevel::getMaxSectionY, maximumY >> 4)
                .constant(ServerLevel::getSectionsCount, sectionCount)
                .constant(value -> ((net.minecraft.world.level.Level) value).getChunkSource(), ServerLevel::getChunkSource, source)
                .nativeQuery(value -> value.isInValidBounds(BlockPos.ZERO), false, args -> checkThread())
                .nativeQuery(value -> value.isLoaded(BlockPos.ZERO), false, args -> checkThread())
                .query(value -> value.clipIncludingBorder(null), null, args -> queries.projectileRay((net.minecraft.world.level.ClipContext) args[0]))
                .nativeQuery(value -> value.hasNeighborSignal(BlockPos.ZERO), false, args -> terrain())
                .nativeQuery(value -> value.getBestNeighborSignal(BlockPos.ZERO), 0, args -> terrain())
                .nativeQuery(value -> value.getDirectSignalTo(BlockPos.ZERO), 0, args -> terrain())
                .nativeQuery(value -> value.hasSignal(BlockPos.ZERO, net.minecraft.core.Direction.UP), false, args -> terrain())
                .nativeQuery(value -> value.getSignal(BlockPos.ZERO, net.minecraft.core.Direction.UP), 0, args -> terrain())
                .nativeQuery(value -> value.getDirectSignal(BlockPos.ZERO, net.minecraft.core.Direction.UP), 0, args -> terrain())
                .query(ServerLevel::getMinecraftWorld, null, args -> world())
                .query(ServerLevel::getBlockTicks, null, args -> scheduled().blocks())
                .query(ServerLevel::getFluidTicks, null, args -> scheduled().fluids())
                .outputQuery(value -> value.scheduleTick(BlockPos.ZERO, Blocks.AIR, 1),
                        args -> scheduled().schedule((BlockPos) args[0], args[1], (int) args[2], net.minecraft.world.ticks.TickPriority.NORMAL))
                .outputQuery(value -> value.scheduleTick(BlockPos.ZERO, Blocks.AIR, 1, net.minecraft.world.ticks.TickPriority.NORMAL),
                        args -> scheduled().schedule((BlockPos) args[0], args[1], (int) args[2], (net.minecraft.world.ticks.TickPriority) args[3]))
                .outputQuery(value -> value.scheduleTick(BlockPos.ZERO, net.minecraft.world.level.material.Fluids.EMPTY, 1),
                        args -> scheduled().schedule((BlockPos) args[0], args[1], (int) args[2], net.minecraft.world.ticks.TickPriority.NORMAL))
                .outputQuery(value -> value.scheduleTick(BlockPos.ZERO, net.minecraft.world.level.material.Fluids.EMPTY, 1, net.minecraft.world.ticks.TickPriority.NORMAL),
                        args -> scheduled().schedule((BlockPos) args[0], args[1], (int) args[2], (net.minecraft.world.ticks.TickPriority) args[3]))
                .query(value -> value.getChunkIfLoadedImmediately(BlockPos.ZERO), null,
                        args -> chunk(((BlockPos) args[0]).getX() >> 4, ((BlockPos) args[0]).getZ() >> 4))
                .query(value -> value.getSectionIndex(0), 0, args -> ((int) args[0] >> 4) - minimumSection)
                .query(value -> value.getSectionIndexFromSectionY(0), 0, args -> (int) args[0] - minimumSection)
                .query(value -> value.getSectionYFromSectionIndex(0), 0, args -> (int) args[0] + minimumSection)
                .query(value -> value.isOutsideBuildHeight(BlockPos.ZERO), false, args -> outside(((BlockPos) args[0]).getY()))
                .query(value -> value.isOutsideBuildHeight(0), false, args -> outside((int) args[0]))
                .query(value -> value.getBlockState(BlockPos.ZERO), Native.AIR, args -> block((BlockPos) args[0]))
                .query(value -> value.setBlock(BlockPos.ZERO, Native.AIR, 0), false, args ->
                        terrain().write((BlockPos) args[0], (BlockState) args[1], (int) args[2], net.minecraft.world.level.block.Block.UPDATE_LIMIT))
                .query(value -> value.setBlock(BlockPos.ZERO, Native.AIR, 0, 0), false, args ->
                        terrain().write((BlockPos) args[0], (BlockState) args[1], (int) args[2], (int) args[3]))
                .nativeQuery(value -> value.setBlockAndUpdate(BlockPos.ZERO, Native.AIR), false, args -> terrain())
                .nativeQuery(value -> value.removeBlock(BlockPos.ZERO, false), false, args -> terrain())
                .outputQuery(value -> value.neighborShapeChanged(net.minecraft.core.Direction.UP, BlockPos.ZERO, BlockPos.ZERO, Native.AIR, 0, 0),
                        args -> net.minecraft.world.level.redstone.NeighborUpdater.executeShapeUpdate(world(), (net.minecraft.core.Direction) args[0],
                                (BlockPos) args[1], (BlockPos) args[2], (BlockState) args[3], (int) args[4], (int) args[5]))
                .nativeAction(value -> value.updateNeighbourForOutputSignal(BlockPos.ZERO, Blocks.AIR), args -> terrain())
                .query(value -> value.getFluidState(BlockPos.ZERO), Native.FLUID, args -> block((BlockPos) args[0]).getFluidState())
                .query(value -> value.getBlockEntity(BlockPos.ZERO), null, args -> { BlockPos pos = (BlockPos) args[0]; return queries.blockEntity(pos.getX(), pos.getY(), pos.getZ()); })
                .query(ServerLevel::isRaining, false, args -> queries.raining())
                .query(value -> value.canSeeSky(BlockPos.ZERO), false, args -> queries.skyVisible((BlockPos) args[0]))
                .query(value -> value.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, BlockPos.ZERO), null,
                        args -> queries.topPosition((net.minecraft.world.level.levelgen.Heightmap.Types) args[0], (BlockPos) args[1]))
                .query(value -> value.getBiome(BlockPos.ZERO), null, args -> queries.biome((BlockPos) args[0]))
                .query(ServerLevel::getSeaLevel, 0, args -> queries.seaLevel())
                .query(value -> value.hasChunksAt(0, 0, 0, 0), false,
                        args -> queries.chunksLoaded((int) args[0], (int) args[1], (int) args[2], (int) args[3]))
                // Level's server-side addParticle body is intentionally empty;
                // broadcast particles use ServerLevel.sendParticles instead.
                .nativeAction(value -> value.addParticle(net.minecraft.core.particles.ParticleTypes.SPLASH, 0, 0, 0, 0, 0, 0), args -> { })
                .nativeQuery(value -> value.precipitationAt(BlockPos.ZERO), null, args -> { })
                .nativeQuery(value -> value.isRainingAt(BlockPos.ZERO), false, args -> { })
                .query(ServerLevel::getWorldBorder, null, args -> queries.worldBorder())
                .query(value -> value.getPlayerInAnyDimension(null), null, args -> importedPlayer((java.util.UUID) args[0]))
                .query(value -> value.getEntityInAnyDimension(null), null, args -> importedEntity((java.util.UUID) args[0]))
                .query(value -> value.getPlayerByUUID(null), null, args -> importedPlayer((java.util.UUID) args[0]))
                .query(value -> value.getEntities((Entity) null, Native.BOX, entity -> true), List.of(), args -> entities(args[0], args[1], false, args[2]))
                .query(value -> value.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(Entity.class),
                        Native.BOX, entity -> true), List.of(), args -> typedEntities(args[0], args[1], args[2]))
                .query(value -> value.getEntityCollisions(null, Native.BOX), List.of(), args -> {
                    bounds(args[1]); return List.copyOf(queries.getEntityCollisions((Entity) args[0], (AABB) args[1]));
                })
                .query(value -> value.isUnobstructed(null, net.minecraft.world.phys.shapes.Shapes.empty()), false,
                        args -> queries.isUnobstructed((Entity) args[0], (net.minecraft.world.phys.shapes.VoxelShape) args[1]))
                .query(value -> value.findSupportingBlock(null, Native.BOX), java.util.Optional.empty(), args -> collision("findSupportingBlock", args))
                .query(value -> value.noCollision(null, Native.BOX), false, args -> collision("noCollision", args))
                .query(value -> value.collidesWithSuffocatingBlock(null, Native.BOX), false, args -> collision("collidesWithSuffocatingBlock", args))
                .query(value -> value.getBlockCollisions(null, Native.BOX), List.of(), args -> collision("getBlockCollisions", args));
        scoreboards = combatState == null ? null : new PaperRollbackScoreboards(this,
                (net.minecraft.core.RegistryAccess) combatState.registryAccess(), combatState::output);
        combat = combatState == null ? null : new PaperRollbackCombatAccess(this, combatState);
        if (combat != null) {
            combat.bind(shell);
            var structures = new PaperRollbackStructures(combatState.structures(), (net.minecraft.core.RegistryAccess) combatState.registryAccess());
            shell.constant(ServerLevel::structureManager, structures.manager());
        }
        world = shell.instance();
        PaperRollbackPrivateAccess.initializeChunks(world, source); // Level's final loaded-block access reads this field directly.
        PaperRollbackPrivateAccess.initializeConfig(world);
        if (combatState != null) {
            world.spigotConfig.arrowDespawnRate = combatState.arrowLifetime().groundTicks();
            world.spigotConfig.itemDespawnRate = combatState.itemRules().despawnTicks();
            world.spigotConfig.itemMerge = combatState.itemRules().mergeRadius();
            world.spigotConfig.tridentDespawnRate = combatState.arrowLifetime().tridentTicks();
        }
        world.moonrise$setEntityLookup(lookup);
    }

    ServerLevel world() { checkThread(); return world; }
    boolean usesQueries(Queries<?> candidate) { checkThread(); return queries == candidate; }
    int playerCount() { checkThread(); return playerOrder.size(); }
    Map<java.lang.reflect.Method, java.lang.invoke.MethodHandle> prepareMethods(
            com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeMethods methods) {
        checkThread();
        return bootstrapCompiler == null ? methods.build() : methods.prepare(bootstrapCompiler);
    }

    void finishNativePreparation() {
        checkThread();
        bootstrapCompiler = null;
    }

    void bindEvents(com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeMethods methods) {
        checkThread();
        if (combat == null) throw new IllegalStateException("Native event services were not supplied");
        combat.bindEvents(methods);
    }
    void bindAdvancementRewards(com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeMethods methods) {
        checkThread();
        if (combat == null) throw new IllegalStateException("Native reward services were not supplied");
        combat.bindAdvancementRewards(methods);
    }
    void output(PaperRollbackCombatAccess.Output output) {
        checkThread();
        if (combatState == null) throw new IllegalStateException("Native output services were not supplied");
        combatState.output(output);
    }
    private void trackedPacket(Entity entity, net.minecraft.network.protocol.Packet<?> packet, boolean includeSelf) {
        checkThread();
        if (packetData == null) throw new IllegalStateException("Native packet services were not supplied");
        if (!ownsPlayer(entity) || entity.level() != world) throw new IllegalArgumentException("Foreign tracked entity");
        var data = packetData.capture(Objects.requireNonNull(packet, "packet"));
        int target = data instanceof PaperRollbackPacketData.Equipment equipment ? equipment.entity()
                : data instanceof PaperRollbackPacketData.EntityStatus status ? status.entity()
                : data instanceof PaperRollbackPacketData.Animation animation ? animation.entity() : Integer.MIN_VALUE;
        if (target != entity.getId()) throw new IllegalArgumentException("Unsupported packet or mismatched tracked entity");
        output(new PaperRollbackPacketData.Tracked(entity.getUUID(), includeSelf, data));
    }
    public PaperRollbackScoreboards scoreboards() {
        checkThread();
        if (scoreboards == null) throw new IllegalStateException("Native scoreboard services were not supplied");
        return scoreboards;
    }
    void statistic(Object player, Object statistic, int amount, boolean reset) {
        checkThread();
        if (combat == null) throw new IllegalStateException("Native player services were not supplied");
        combat.statistic(player, statistic, amount, reset);
    }
    void event(org.bukkit.event.Event event) {
        checkThread();
        if (combatState == null) throw new IllegalStateException("Native event services were not supplied");
        if (roundEvents == null || !(event instanceof org.bukkit.event.entity.EntityDamageEvent damage)) {
            combatState.event(event);
            return;
        }
        var victim = logicalPlayer(damage.getEntity());
        var source = damage instanceof org.bukkit.event.entity.EntityDamageByEntityEvent hit ? hit.getDamager() : null;
        com.projectkorra.projectkorra.platform.mc.entity.Entity direct = null;
        if (source != null && victim != null) {
            direct = logicalPlayer(source);
            if (direct == null && actors != null && actors.get(source.getUniqueId()) instanceof PaperRollbackActor actor) {
                if (actor.entity().getBukkitEntityRaw() != source) throw new IllegalArgumentException("Foreign actor damage source");
                direct = actor.view();
            }
            if (direct == null) throw new IllegalArgumentException("Unregistered attack source in the private world");
        }
        var block = damage instanceof org.bukkit.event.entity.EntityDamageByBlockEvent hit ? hit.getDamager() : null;
        if (victim != null && block != null
                && (!(block instanceof org.bukkit.craftbukkit.block.CraftBlock craft) || craft.getHandle() != world)) {
            throw new IllegalArgumentException("Damage block is outside the private world");
        }
        combatState.event(event);
        if (victim != null) {
            var logicalBlock = block == null ? null : victim.getWorld().getBlockAt(block.getX(), block.getY(), block.getZ());
            var view = com.projectkorra.projectkorra.platform.bukkit.BukkitMC.damageEvent(damage, victim);
            RollbackDamageEvents.dispatch(view, direct, logicalBlock);
        }
        roundEvents.accept(damage);
    }

    @SuppressWarnings("WrapperReferenceEquality")
    private com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayer logicalPlayer(org.bukkit.entity.Entity nativeEntity) {
        var player = RollbackControlEvents.player(nativeEntity.getUniqueId());
        if (player != null && (!(player.body().kinematicsSource() instanceof PaperRollbackNativePlayerState state)
                || state.ownedPlayer().getBukkitEntityRaw() != nativeEntity || !ownsWrapper(nativeEntity))) {
            throw new IllegalArgumentException("Damage entity differs from the active private combatant");
        }
        return player;
    }
    /** Install after complete native roster import and before the first checkpoint. */
    public void bindRound(RollbackRound round) {
        checkThread(); Objects.requireNonNull(round);
        if (checkpointed || roundEvents != null || RollbackClock.active() || RollbackDomain.active())
            throw new IllegalStateException("Bind native round rules before replay/checkpointing");
        if (combatState == null || round.tick() != 0) throw new IllegalStateException("Round requires native combat and a fresh timeline");
        var roster = new java.util.TreeMap<java.util.UUID, org.bukkit.entity.Player>();
        for (var state : playerOrder) {
            if (!(state.ownedPlayer() instanceof net.minecraft.server.level.ServerPlayer player))
                throw new IllegalArgumentException("Round requires the private server-player roster");
            roster.put(player.getUUID(), player.getBukkitEntity());
        }
        roundEvents = new PaperRollbackRoundEvents(round, roster);
        sealPlayers();
    }

    void useItem(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.InteractionHand hand) {
        checkThread(); if (!ownsPlayer(player)) throw new IllegalArgumentException("Foreign item user");
        combat.useItem(player, hand);
    }
    void releaseItem(net.minecraft.server.level.ServerPlayer player) {
        checkThread(); if (!ownsPlayer(player)) throw new IllegalArgumentException("Foreign item user");
        combat.releaseItem(player);
    }
    void useBlock(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
        checkThread(); if (!ownsPlayer(player)) throw new IllegalArgumentException("Foreign block user");
        combat.useBlock(player, hand, hit);
    }
    void interact(net.minecraft.server.level.ServerPlayer player, Entity target, net.minecraft.world.InteractionHand hand,
                  RollbackInteractionTarget.Offset offset) {
        checkThread(); if (!ownsPlayer(player) || !ownsEntity(target)) throw new IllegalArgumentException("Foreign entity interaction");
        combat.interact(player, target, hand, offset);
    }
    boolean consumeMeleeVelocity(net.minecraft.server.level.ServerPlayer recipient,
            net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket packet) {
        checkThread(); return combat != null && combat.consumeMeleeVelocity(recipient, packet);
    }
    boolean mount(Entity rider, Entity vehicle) { checkThread(); return combat.mount(rider, vehicle); }
    void unmount(Entity rider) { checkThread(); combat.unmount(rider); }
    void attack(net.minecraft.server.level.ServerPlayer player, Entity target) {
        checkThread();
        if (combat == null || !ownsPlayer(player) || !ownsEntity(target)) throw new IllegalArgumentException("Foreign melee body");
        combat.attack(player, target);
    }

    boolean damage(Object player, Object source, float amount) {
        checkThread();
        if (combat == null) throw new IllegalStateException("Native combat services were not supplied");
        return combat.damage(player, source, amount);
    }

    void potion(net.minecraft.world.entity.player.Player player, net.minecraft.world.effect.MobEffectInstance effect) {
        checkThread();
        if (combat == null) throw new IllegalStateException("Native combat services were not supplied");
        combat.potion(player, effect);
    }
    void removePotion(net.minecraft.world.entity.player.Player player, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect) {
        checkThread();
        if (combat == null) throw new IllegalStateException("Native combat services were not supplied");
        combat.removePotion(player, effect);
    }
    void air(net.minecraft.world.entity.player.Player player, int amount) {
        checkThread();
        if (combat == null) throw new IllegalStateException("Native combat services were not supplied");
        combat.air(player, amount);
    }

    void requirePlayerConstruction() {
        checkThread();
        if (playersSealed || RollbackClock.active()) throw new IllegalStateException("Native player roster is closed");
        if (players.size() >= 128) throw new IllegalStateException("Native player roster budget exceeded");
    }
    void requirePlayerImport(java.util.UUID id, int entityId) {
        requirePlayerConstruction();
        for (var state : playerOrder) {
            var player = state.ownedPlayer();
            if (player.getUUID().equals(id) || player.getId() == entityId) {
                throw new IllegalArgumentException("Player import duplicates a private roster identity");
            }
        }
    }
    void requireRosterImport(int size) {
        requirePlayerConstruction();
        if (size < 1 || size > 128 - playerOrder.size()) throw new IllegalArgumentException("Native player roster budget exceeded");
    }
    private net.minecraft.world.entity.player.Player importedPlayer(java.util.UUID id) {
        checkThread();
        for (var state : playerOrder) {
            var player = state.ownedPlayer();
            if (player.getUUID().equals(id) && !player.isRemoved()) return player;
        }
        return null;
    }
    void swimming(net.minecraft.world.entity.player.Player player, boolean value) {
        checkThread();
        if (combat == null) throw new IllegalStateException("Native combat services were not supplied");
        combat.swimming(player, value);
    }
    boolean requestFlight(net.minecraft.server.level.ServerPlayer player, boolean flying, boolean cancelled) {
        checkThread();
        if (combat == null) throw new IllegalStateException("Native flight services were not supplied");
        return combat.requestFlight(player, flying, cancelled);
    }
    boolean requestGlide(net.minecraft.world.entity.player.Player player) {
        checkThread();
        if (combat == null) throw new IllegalStateException("Native glide services were not supplied");
        return combat.requestGlide(player);
    }
    void stepMovement(net.minecraft.world.entity.player.Player player) {
        checkThread();
        if (combat == null) throw new IllegalStateException("Native combat services were not supplied");
        combat.stepMovement(player);
    }
    void tickPlayerBody(net.minecraft.world.entity.player.Player player) {
        checkThread();
        if (combat == null) throw new IllegalStateException("Native combat services were not supplied");
        combat.tickPlayerBody(player);
    }
    void applyUseCooldown(net.minecraft.world.entity.player.Player player, net.minecraft.world.item.ItemStack usedStack) {
        checkThread();
        if (combat == null || !ownsPlayer(player) || player.level() != world)
            throw new IllegalArgumentException("Item-use cooldown requires an owned combat player");
        combat.applyUseCooldown(player, usedStack);
    }
    void startItemUse(net.minecraft.world.entity.player.Player player, boolean offHand) {
        checkThread();
        if (combat == null || !ownsPlayer(player) || player.level() != world)
            throw new IllegalArgumentException("Item use requires an owned combat player");
        combat.startItemUse(player, offHand);
    }
    boolean selectSlot(net.minecraft.world.entity.player.Player player, int slot, boolean cancelled) {
        checkThread();
        if (combat == null || !ownsPlayer(player) || player.level() != world)
            throw new IllegalArgumentException("Slot selection requires an owned combat player");
        return combat.selectSlot(player, slot, cancelled);
    }
    boolean swapHands(net.minecraft.world.entity.player.Player player) {
        checkThread();
        if (combat == null || !ownsPlayer(player) || player.level() != world)
            throw new IllegalArgumentException("Hand swap requires an owned combat player");
        return combat.swapHands(player);
    }
    void tickPlayer(net.minecraft.server.level.ServerPlayer player) {
        checkThread();
        if (combat == null) throw new IllegalStateException("Native combat services were not supplied");
        combat.tickPlayer(player);
    }
    void requirePlayerRegistration(Object player) {
        requirePlayerConstruction();
        if (players.containsKey(player)) throw new IllegalArgumentException("Native player is already registered");
    }
    void registerPlayer(Object player, Object wrapper, PaperRollbackNativePlayerState state) {
        requirePlayerRegistration(player);
        players.put(player, state); playerWrappers.put(wrapper, true); playerOrder.add(state);
    }
    RollbackActorRegistry actors() { checkThread(); return actors; }
    void bindActors(RollbackActorRegistry actors) {
        checkThread();
        if (this.actors != null || checkpointed || RollbackClock.active() || RollbackDomain.active())
            throw new IllegalStateException("Bind actors before the arena starts");
        this.actors = Objects.requireNonNull(actors);
    }
    private net.minecraft.world.entity.Entity importedEntity(java.util.UUID id) {
        var player = importedPlayer(id);
        if (player != null || actors == null) return player;
        var actor = actors.get(id);
        return actor instanceof PaperRollbackActor owned && !owned.entity().isRemoved() ? owned.entity() : null;
    }
    List<net.minecraft.world.entity.Entity> spawnedEntities() {
        checkThread();
        return actors == null ? List.of() : actors.active().stream().map(actor -> ((PaperRollbackActor) actor).entity()).toList();
    }
    boolean ownsEntity(net.minecraft.world.entity.Entity entity) {
        checkThread();
        return ownsPlayer(entity) || (actors != null && actors.get(entity.getUUID()) instanceof PaperRollbackActor owned
                && owned.entity() == entity && !entity.isRemoved());
    }
    void sealPlayers() { checkThread(); playersSealed = true; }
    void beginCheckpoint() { sealPlayers(); checkpointed = true; }
    boolean ownsPlayer(Object player) { checkThread(); return players.containsKey(player); }
    boolean ownsWrapper(Object wrapper) {
        checkThread();
        return playerWrappers.containsKey(wrapper) || (wrapper instanceof org.bukkit.craftbukkit.entity.CraftEntity craft
                && actors != null && craft.getHandle().getBukkitEntityRaw() == craft && ownsEntity(craft.getHandle()));
    }

    private Object collision(String method, Object[] args) {
        bounds(args[1]);
        return queries.collisionQuery(method, args.clone());
    }

    private boolean outside(int y) { return y < minimumY || (long) y >= (long) minimumY + height; }
    @SuppressWarnings("unchecked")
    private Object environmentValue(Object[] args) {
        var attribute = (net.minecraft.world.attribute.EnvironmentAttribute<Object>) args[0];
        var reader = Objects.requireNonNull(queries.environmentAttributes(), "captured environment attributes");
        if (args.length == 1) return reader.getDimensionValue(attribute);
        if (args[1] instanceof BlockPos pos) return reader.getValue(attribute, pos);
        var pos = (net.minecraft.world.phys.Vec3) args[1];
        if (!Double.isFinite(pos.x) || !Double.isFinite(pos.y) || !Double.isFinite(pos.z)) throw new IllegalArgumentException("Environment query position");
        return reader.getValue(attribute, pos, args.length == 3 ? (net.minecraft.world.attribute.SpatialAttributeInterpolator) args[2] : null);
    }
    private BlockState block(BlockPos position) {
        return (BlockState) Objects.requireNonNull(queries.blockState(position.getX(), position.getY(), position.getZ()), "logical block state");
    }

    private LevelChunk chunk(int x, int z) {
        checkThread();
        // Chunk views are derived caches; their existence does not mean the restored world has loaded them.
        if (!queries.chunkLoaded(x, z)) return null;
        long key = ((long) x << 32) | Integer.toUnsignedLong(z);
        LevelChunk existing = chunks.get(key);
        if (existing != null) return existing;
        if (chunks.size() >= maximumChunks) throw new IllegalStateException("Native collision chunk-view budget exceeded");
        int originX = Math.multiplyExact(x, 16), originZ = Math.multiplyExact(z, 16);
        LevelChunkSection[] sections = new LevelChunkSection[sectionCount];
        for (int index = 0; index < sectionCount; index++) {
            int originY = minimumY + index * 16;
            var palette = RollbackNativeQueryShell.create(Native.PALETTE)
                    .query(value -> value.get(0), Native.AIR, args -> {
                        int packed = (int) args[0];
                        if (packed < 0 || packed >= 4_096) throw new IllegalArgumentException("Native section palette index");
                        return queries.blockState(originX + (packed & 15), originY + (packed >> 8), originZ + ((packed >> 4) & 15));
                    }).instance();
            LevelChunkSection section = RollbackNativeQueryShell.create(LevelChunkSection.class).constant(LevelChunkSection::hasOnlyAir, false).instance();
            // Conservative metadata forces native iteration to read current blocks;
            // stale cached air/special-block counts must not survive a terrain rewind.
            PaperRollbackPrivateAccess.initializeSection(section, palette);
            sections[index] = section;
        }
        LevelChunk result = RollbackNativeQueryShell.create(LevelChunk.class)
                .constant(LevelChunk::getSections, sections)
                .query(value -> value.getBlockState(BlockPos.ZERO), Native.AIR, args -> block((BlockPos) args[0]))
                .query(value -> value.getFluidState(BlockPos.ZERO), Native.FLUID, args -> block((BlockPos) args[0]).getFluidState())
                .instance();
        chunks.put(key, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<?> typedEntities(Object filter, Object box, Object predicate) {
        var type = (net.minecraft.world.level.entity.EntityTypeTest<Entity, ?>) filter;
        return entities(null, box, false, (Predicate<Entity>) value -> type.tryCast(value) != null && ((Predicate<Entity>) predicate).test(value));
    }

    @SuppressWarnings("unchecked")
    private List<?> entities(Object except, Object box, boolean hardOnly, Object predicate) {
        List<?> candidates = queries.entities(except, bounds(box), hardOnly);
        if (candidates.size() > 65_536) throw new IllegalStateException("Native collision entity budget exceeded");
        var selected = new ArrayList<Object>();
        for (Object entity : candidates) {
            if (((Entity) entity).level() != world) throw new IllegalArgumentException("Collision entity belongs to another world");
            if (predicate == null || ((Predicate<Object>) predicate).test(entity)) selected.add(entity);
        }
        return List.copyOf(selected);
    }

    @SuppressWarnings("unchecked")
    private static void outputEntities(Object target, List<?> entities) { ((List<Object>) target).addAll(entities); }

    private static Box bounds(Object nativeBox) {
        AABB box = (AABB) nativeBox;
        Box result = new Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        RollbackMovementSolver.requireBlockQuery(result);
        return result;
    }

    private void checkThread() {
        if (thread != Thread.currentThread()) throw new IllegalStateException("Native world query crossed threads");
    }
    @Override public Void captureRollbackState() { beginCheckpoint(); return null; }
    @Override public void restoreRollbackState(Void state) { checkThread(); }
    @Override public List<?> rollbackReferences() {
        checkThread();
        var references = new ArrayList<Object>(playerOrder.size() + 1);
        references.add(queries); references.addAll(playerOrder);
        if (combatState != null) references.add(combatState);
        if (roundEvents != null) references.add(roundEvents.round());
        if (scoreboards != null) references.add(scoreboards);
        return List.copyOf(references);
    }
}
