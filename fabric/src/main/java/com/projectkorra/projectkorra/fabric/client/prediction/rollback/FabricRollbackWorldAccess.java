package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell;
import com.projectkorra.projectkorra.prediction.rollback.RollbackClock;
import com.projectkorra.projectkorra.prediction.rollback.RollbackRound;
import com.projectkorra.projectkorra.prediction.rollback.RollbackDomain;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackMovementSolver;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.CollisionView;
import net.minecraft.world.World;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.debug.SubscriberTracker;
import net.minecraft.world.debug.DebugSubscriptionTypes;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeAccess;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.attribute.WorldEnvironmentAttributeAccess;
import net.minecraft.world.attribute.WeightedAttributeList;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.server.network.ServerWaypointHandler;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageSources;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.Difficulty;
import net.minecraft.world.event.GameEvent;
import net.minecraft.world.rule.GameRule;
import net.minecraft.world.rule.GameRules;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Audited private world boundary for native player movement and damage. Configured
 * reads use captured state, and native operations mutate owned players. Presentation
 * is detached provisional output; causal game events require private dispatch. Other
 * writes, chunk loading and unknown calls fail. This is not a complete native world.
 */
public final class FabricRollbackWorldAccess implements RollbackStateCell<Void> {
    public enum WaypointAction { TRACK, UPDATE, UNTRACK }
    /** Detached provisional presentation. Delivery belongs to the session's revision/finalization bridge. */
    public sealed interface Output permits DamageOutput, StatusOutput, SoundOutput, FabricRollbackPacketData.Tracked { }
    public record Position(double x, double y, double z) { }
    public record DamageOutput(UUID entity, String type, UUID source, UUID attacker, Position position) implements Output { }
    public record StatusOutput(UUID entity, byte status) implements Output { }
    public record SoundOutput(UUID excludedSource, Position position, String sound, String category,
                              float volume, float pitch, long seed) implements Output { }
    /** Includes logical terrain, entity eligibility and the captured border; no live-world fallback. */
    public interface Queries<S> extends CollisionView, RollbackStateCell<S> {
        boolean isChunkLoaded(int x, int z);
        EnvironmentAttributeAccess environmentAttributes();
        boolean isRaining();
        boolean isSkyVisible(BlockPos pos);
        BlockPos topPosition(Heightmap.Type type, BlockPos pos);
        RegistryEntry<Biome> biome(BlockPos pos);
        int seaLevel();
        List<? extends Entity> otherEntities(Entity except, Box bounds);
        Scoreboard scoreboard();
        /** Frozen authoritative registries, including reloadable loot/predicates; stable for the whole session. */
        DynamicRegistryManager.Immutable registries();
        long time();
        Difficulty difficulty();
        <T> T gameRule(GameRule<T> rule);
        /** Owned native world RNG; include its mutable state in rollbackReferences/checkpoints. */
        net.minecraft.util.math.random.Random random();
        /** A checkpointed native-compatible stream, never a wall-clock/random global. */
        long nextSoundSeed();
        void output(Output output);
        /** Dispatch to private captured listeners; this is causal simulation, not presentation. */
        void gameEvent(RegistryEntry<GameEvent> event, Vec3d position, GameEvent.Emitter emitter);
        /** Encode detached provisional output immediately; never retain the native entity or deliver live packets. */
        void waypoint(WaypointAction action, Entity entity);
        /** Captured authoritative flight-event policy; initial cancellation is from the shared bending handler. */
        default com.projectkorra.projectkorra.prediction.rollback.RollbackHandSwap swapHands(PlayerEntity player,
                com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemData main,
                com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemData off) {
            throw new UnsupportedOperationException("Private swap-event policy is not bound");
        }
        default boolean updateEquipmentOnActions() {
            throw new UnsupportedOperationException("Private equipment-update policy is not bound");
        }        default boolean flightAllowed(PlayerEntity player, boolean flying, boolean cancelled) {
            throw new UnsupportedOperationException("Private flight-event policy is not bound");
        }
        default boolean glideAllowed(PlayerEntity player, boolean gliding, boolean cancelled) {
            throw new UnsupportedOperationException("Private glide-event policy is not bound");
        }
    }

    /** Captured Paper event policy. Every causal callback remains in the private domain. */
    public interface DamagePolicy<S> extends RollbackStateCell<S> {
        void event(FabricRollbackDamageEvent event);
        void resetAttackCooldown(PlayerEntity attacker, PlayerEntity target);
        void exhaustion(PlayerEntity player, DamageSource source, float amount);
        void knockback(PlayerEntity player, DamageSource source, double strength, double x, double z);
        void death(PlayerEntity player, DamageSource source);
        /** Return -1 to cancel disabling entirely; otherwise the event-adjusted cooldown ticks. */
        int shieldDisable(PlayerEntity player, net.minecraft.entity.LivingEntity attacker, net.minecraft.item.ItemStack shield, int ticks);
        /** Return -1 to cancel only cooldown installation; shield use still stops. */
        int itemCooldown(PlayerEntity player, net.minecraft.item.ItemStack item, int ticks);
        boolean skipDamageTickWhenShieldBlocked();
    }
    private RollbackRound round;
    private DamagePolicy<?> damagePolicy;
    boolean hasRound() { checkThread(); return round != null; }
    DamagePolicy<?> damagePolicy() { checkThread(); return Objects.requireNonNull(damagePolicy, "Private damage policy"); }
    public void bindRound(RollbackRound round, DamagePolicy<?> policy) {
        checkThread(); Objects.requireNonNull(round); Objects.requireNonNull(policy);
        if (checkpointed || this.round != null || RollbackClock.active() || RollbackDomain.active())
            throw new IllegalStateException("Bind native round before checkpoint/replay");
        var ids = new java.util.HashSet<UUID>();
        for (var state : playerOrder) {
            if (!(state.ownedPlayer() instanceof FabricRollbackSimulatedPlayer)) throw new IllegalArgumentException("Round requires simulated player bodies");
            ids.add(state.identity().uuid());
        }
        if (round.tick() != 0 || !ids.equals(round.participants())) throw new IllegalArgumentException("Native round roster/tick differs");
        this.round = round; damagePolicy = policy; sealPlayers();
    }
    void damageEvent(FabricRollbackDamageEvent event) {
        checkThread();
        var victim = event.player();
        if (!ownsPlayer(victim)) throw new IllegalArgumentException("Foreign damage target");
        requireDamageSource(event.source());
        damagePolicy().event(event);
        double finalDamage = event.finalDamage();
        if (!Double.isFinite(finalDamage) || !Float.isFinite((float) finalDamage)) throw new IllegalArgumentException("Damage event overflow");
        UUID attacker = event.source().getAttacker() instanceof PlayerEntity player ? player.getUuid()
                : event.source().getSource() instanceof PlayerEntity player ? player.getUuid() : null;
        boolean totem = !event.cancelled() && RollbackRound.endsLife(victim.getHealth(), finalDamage, false)
                && (victim.getMainHandStack().isOf(net.minecraft.item.Items.TOTEM_OF_UNDYING)
                || victim.getOffHandStack().isOf(net.minecraft.item.Items.TOTEM_OF_UNDYING));
        switch (round.damage(victim.getUuid(), attacker, victim.getHealth(), finalDamage, totem, event.cancelled())) {
            case ALLOW -> { }
            case CANCEL -> event.cancelled(true);
            case DEFEAT -> { victim.setHealth(20); event.cancelled(true); }
        }
    }

    private final Queries<?> queries;
    boolean flightAllowed(PlayerEntity player, boolean flying, boolean cancelled) {
        if (!ownsPlayer(player)) throw new IllegalArgumentException("Foreign flight input player");
        return queries.flightAllowed(player, flying, cancelled || com.projectkorra.projectkorra.prediction.rollback.RollbackControlEvents.cancelFlight(player.getUuid()));
    }
    boolean glideAllowed(PlayerEntity player, boolean gliding) {
        if (!ownsPlayer(player)) throw new IllegalArgumentException("Foreign glide input player");
        return queries.glideAllowed(player, gliding, com.projectkorra.projectkorra.prediction.rollback.RollbackControlEvents.cancelGlide(player.getUuid()));
    }
    private final World world;
    private final FabricRollbackPacketData packetData;
    private final Thread thread = Thread.currentThread();
    private final Map<PlayerEntity, FabricRollbackNativePlayerState> players = new IdentityHashMap<>();
    private final List<FabricRollbackNativePlayerState> playerOrder = new ArrayList<>();
    private boolean playersSealed, checkpointed;

    public FabricRollbackWorldAccess(Queries<?> queries) {
        this.queries = Objects.requireNonNull(queries, "queries");
        var registries = Objects.requireNonNull(queries.registries(), "session registries");
        packetData = new FabricRollbackPacketData(registries);
        var damageSources = new DamageSources(registries);
        Box probeBox = new Box(0, 0, 0, 1, 1, 1);
        var environment = RollbackNativeQueryShell.create(WorldEnvironmentAttributeAccess.class)
                .query(value -> value.getAttributeValue(EnvironmentAttributes.FAST_LAVA_GAMEPLAY), false, this::environmentValue)
                .query(value -> value.getAttributeValue(EnvironmentAttributes.FAST_LAVA_GAMEPLAY, Vec3d.ZERO), false, this::environmentValue)
                .query(value -> value.getAttributeValue(EnvironmentAttributes.FAST_LAVA_GAMEPLAY, BlockPos.ORIGIN), false, this::environmentValue)
                .query(value -> value.getAttributeValue(EnvironmentAttributes.FAST_LAVA_GAMEPLAY, Vec3d.ZERO, null), false, this::environmentValue)
                .instance();
        // A private simulation has no debug subscribers. All other server calls
        // remain unbound; this never acquires the running integrated/live server.
        var subscribers = RollbackNativeQueryShell.create(SubscriberTracker.class)
                .query(value -> value.hasSubscriber(DebugSubscriptionTypes.ENTITY_BLOCK_INTERSECTIONS), false, args -> false).instance();
        var server = RollbackNativeQueryShell.create(MinecraftServer.class)
                .constant(MinecraftServer::getSubscriberTracker, subscribers)
                .constant(MinecraftServer::getReloadableRegistries, new net.minecraft.registry.ReloadableRegistries.Lookup(registries)).instance();
        var scoreboard = RollbackNativeQueryShell.create(ServerScoreboard.class)
                .query(value -> value.getScoreHolderTeam(""), null, args -> queries.scoreboard().getScoreHolderTeam((String) args[0])).instance();
        var waypoints = RollbackNativeQueryShell.create(ServerWaypointHandler.class)
                .outputQuery(value -> value.onTrack(null), args -> waypoint(WaypointAction.TRACK, args[0]))
                .outputQuery(value -> value.onUpdate(null), args -> waypoint(WaypointAction.UPDATE, args[0]))
                .outputQuery(value -> value.onUntrack(null), args -> waypoint(WaypointAction.UNTRACK, args[0])).instance();
        var gameRules = RollbackNativeQueryShell.create(GameRules.class)
                .query(value -> value.getValue(GameRules.FIRE_DAMAGE), false, args -> queries.gameRule((GameRule<?>) args[0])).instance();
        var chunks = RollbackNativeQueryShell.create(net.minecraft.server.world.ServerChunkManager.class)
                .outputQuery(value -> value.sendToOtherNearbyPlayers(null, null), args -> trackedPacket((Entity) args[0], (net.minecraft.network.packet.Packet<?>) args[1], false))
                .outputQuery(value -> value.sendToNearbyPlayers(null, null), args -> trackedPacket((Entity) args[0], (net.minecraft.network.packet.Packet<?>) args[1], true)).instance();
        world = RollbackNativeQueryShell.create(ServerWorld.class)
                .nativeAction(value -> ((com.projectkorra.projectkorra.fabric.mixin.client.WorldRollbackRandomAccess) value).rollback$random(null), args -> {
                    if (args[0] != queries.random()) throw new IllegalArgumentException("Foreign world RNG");
                })
                .constant(World::isClient, false)
                .constant(World::getEnvironmentAttributes, environment)
                .constant(World::getServer, server)
                .constant(ServerWorld::getScoreboard, scoreboard)
                .constant(ServerWorld::getWaypointHandler, waypoints)
                .constant(World::getRegistryManager, registries)
                .constant(World::getDamageSources, damageSources)
                .constant(ServerWorld::getGameRules, gameRules)
                .constant(ServerWorld::getChunkManager, chunks)
                .query(World::getTime, 0L, args -> queries.time())
                .query(World::getDifficulty, Difficulty.NORMAL, args -> queries.difficulty())
                .query(World::getRandom, null, args -> Objects.requireNonNull(queries.random(), "private world random"))
                .outputQuery(value -> value.sendEntityDamage(null, null), args -> entityDamage((Entity) args[0], (DamageSource) args[1]))
                .outputQuery(value -> value.sendEntityStatus(null, (byte) 0), args ->
                        queries.output(new StatusOutput(ownedId((Entity) args[0]), (byte) args[1])))
                .outputQuery(value -> value.playSound(null, 0, 0, 0, SoundEvents.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 1, 1),
                        args -> sound(args, false))
                .outputQuery(value -> value.playSound(null, 0, 0, 0, SoundEvents.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 1, 1, 0),
                        args -> sound(args, true))
                .outputQuery(value -> value.playSound(null, 0, 0, 0, net.minecraft.registry.Registries.SOUND_EVENT.getEntry(SoundEvents.ENTITY_PLAYER_HURT), SoundCategory.PLAYERS, 1, 1),
                        args -> sound(args, false))
                .outputQuery(value -> value.playSound(null, 0, 0, 0, net.minecraft.registry.Registries.SOUND_EVENT.getEntry(SoundEvents.ENTITY_PLAYER_HURT), SoundCategory.PLAYERS, 1, 1, 0),
                        args -> sound(args, true))
                .nativeAction(value -> value.emitGameEvent(null, GameEvent.ENTITY_DAMAGE, Vec3d.ZERO), args -> nullableOwnedId((Entity) args[0]))
                .nativeAction(value -> value.emitGameEvent(null, GameEvent.ENTITY_DAMAGE, BlockPos.ORIGIN), args -> nullableOwnedId((Entity) args[0]))
                .nativeAction(value -> value.emitGameEvent(GameEvent.ENTITY_DAMAGE, BlockPos.ORIGIN, GameEvent.Emitter.of((Entity) null)), args -> { })
                .outputQuery(value -> value.emitGameEvent(GameEvent.ENTITY_DAMAGE, Vec3d.ZERO, GameEvent.Emitter.of((Entity) null)), args -> {
                    var emitter = (GameEvent.Emitter) args[2];
                    nullableOwnedId(emitter.sourceEntity());
                    requirePosition((Vec3d) args[1]);
                    @SuppressWarnings("unchecked") var event = (RegistryEntry<GameEvent>) args[0];
                    queries.gameEvent(event, (Vec3d) args[1], emitter);
                })
                .nativeAction(value -> value.tickEntity(null), args -> requireTickTree((Entity) args[0]))
                // World's server-side body is empty. Sprinting still consumes the
                // player's native RNG before this call, exactly as it does on Paper.
                .nativeAction(value -> value.addParticleClient(net.minecraft.particle.ParticleTypes.SPLASH, 0, 0, 0, 0, 0, 0), args -> { })
                .query(World::isRaining, false, args -> queries.isRaining())
                .query(value -> value.isSkyVisible(BlockPos.ORIGIN), false, args -> queries.isSkyVisible((BlockPos) args[0]))
                .query(value -> value.getTopPosition(Heightmap.Type.MOTION_BLOCKING, BlockPos.ORIGIN), null,
                        args -> queries.topPosition((Heightmap.Type) args[0], (BlockPos) args[1]))
                .query(value -> value.getBiome(BlockPos.ORIGIN), null, args -> queries.biome((BlockPos) args[0]))
                .query(World::getSeaLevel, 0, args -> queries.seaLevel())
                .nativeQuery(value -> value.hasRain(BlockPos.ORIGIN), false, args -> { })
                .nativeQuery(value -> value.getPrecipitation(BlockPos.ORIGIN), null, args -> { })
                .query(value -> value.getPlayerAnyDimension(null), null, args -> importedPlayer((UUID) args[0]))
                .query(value -> value.getEntityAnyDimension(null), null, args -> importedPlayer((UUID) args[0]))
                .query(value -> value.getPlayerByUuid(null), null, args -> importedPlayer((UUID) args[0]))
                .query(value -> value.getOtherEntities(null, probeBox, entity -> true), List.of(), this::otherEntities)
                .nativeQuery(value -> value.getOtherEntities(null, probeBox), List.of(), args -> check((Box) args[1]))
                .nativeQuery(value -> value.getCrammedEntities(null, probeBox), List.of(), args -> check((Box) args[1]))
                .query(value -> value.getBlockState(BlockPos.ORIGIN), Blocks.AIR.getDefaultState(), args -> queries.getBlockState((BlockPos) args[0]))
                .query(value -> value.getFluidState(BlockPos.ORIGIN), Fluids.EMPTY.getDefaultState(), args -> queries.getFluidState((BlockPos) args[0]))
                .query(value -> value.getBlockEntity(BlockPos.ORIGIN), null, args -> queries.getBlockEntity((BlockPos) args[0]))
                .query(World::getHeight, 1, args -> queries.getHeight())
                .query(World::getBottomY, 0, args -> queries.getBottomY())
                .query(World::getTopYInclusive, 0, args -> Math.addExact(queries.getBottomY(), queries.getHeight()) - 1)
                .query(value -> value.isChunkLoaded(0, 0), false, args -> queries.isChunkLoaded((int) args[0], (int) args[1]))
                .nativeQuery(value -> value.isRegionLoaded(0, 0, 0, 0), false, args ->
                        checkRegion((int) args[0], (int) args[1], (int) args[2], (int) args[3]))
                .query(World::getWorldBorder, null, args -> queries.getWorldBorder())
                .query(value -> value.getChunkAsView(0, 0), null, args -> queries.getChunkAsView((int) args[0], (int) args[1]))
                .query(value -> value.getEntityCollisions(null, probeBox), List.of(), args -> {
                    check((Box) args[1]);
                    return List.copyOf(queries.getEntityCollisions((Entity) args[0], (Box) args[1]));
                })
                .nativeQuery(value -> value.getCollisions(null, probeBox), List.of(), args -> {
                    nullableOwnedId((Entity) args[0]); check((Box) args[1]);
                })
                .nativeQuery(value -> value.canCollide(null, probeBox), false, args -> {
                    nullableOwnedId((Entity) args[0]); check((Box) args[1]);
                })
                .query(value -> value.doesNotIntersectEntities(null, net.minecraft.util.shape.VoxelShapes.empty()), false,
                        args -> queries.doesNotIntersectEntities((Entity) args[0], (net.minecraft.util.shape.VoxelShape) args[1]))
                .query(value -> value.getBlockCollisions(null, probeBox), List.of(), args -> {
                    check((Box) args[1]);
                    return queries.getBlockCollisions((Entity) args[0], (Box) args[1]);
                })
                .query(value -> value.isSpaceEmpty(null, probeBox), false, args -> {
                    check((Box) args[1]);
                    return queries.isSpaceEmpty((Entity) args[0], (Box) args[1]);
                })
                .query(value -> value.findSupportingBlockPos(null, probeBox), Optional.empty(), args -> {
                    check((Box) args[1]);
                    return queries.findSupportingBlockPos((Entity) args[0], (Box) args[1]);
                })
                .instance();
        ((com.projectkorra.projectkorra.fabric.mixin.client.WorldRollbackRandomAccess) world)
                .rollback$random(Objects.requireNonNull(queries.random(), "private world random"));
    }

    // Only native adapters in this package may retain the shell. No world constructor ran.
    World world() { checkThread(); return world; }
    boolean swapHands(PlayerEntity player) {
        checkThread(); ownedId(player);
        if (player.isSpectator()) return false;
        var codec = new FabricRollbackItemCodec(queries.registries());
        var originalMain = player.getMainHandStack(); var originalOff = player.getOffHandStack();
        var proposedMain = codec.encode(originalOff); var proposedOff = codec.encode(originalMain);
        var result = Objects.requireNonNull(queries.swapHands(player, proposedMain, proposedOff));
        if (result.cancelled()) return false;
        var main = result.mainHand().equals(proposedMain) ? originalOff : codec.decode(result.mainHand());
        var off = result.offHand().equals(proposedOff) ? originalMain : codec.decode(result.offHand());
        boolean equipment = queries.updateEquipmentOnActions();
        player.setStackInHand(net.minecraft.util.Hand.OFF_HAND, off);
        player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, main);
        player.clearActiveItem();
        if (equipment) player.sendEquipmentChanges();
        return true;
    }
    boolean usesQueries(Queries<?> candidate) { checkThread(); return queries == candidate; }

    void registerPlayer(PlayerEntity player, FabricRollbackNativePlayerState state) {
        checkThread();
        if (playersSealed || RollbackClock.active()) throw new IllegalStateException("Native player roster is closed");
        if (players.containsKey(player)) throw new IllegalArgumentException("Native player is already registered");
        if (players.size() >= 128) throw new IllegalStateException("Native player roster budget exceeded");
        for (var stateEntry : playerOrder) {
            var existing = stateEntry.ownedPlayer();
            if (existing.getUuid().equals(player.getUuid()) || existing.getId() == player.getId()) {
                throw new IllegalArgumentException("Player import duplicates a private roster identity");
            }
        }
        players.put(player, state); playerOrder.add(state);
    }
    void sealPlayers() { checkThread(); playersSealed = true; }
    void beginCheckpoint() { sealPlayers(); checkpointed = true; }
    boolean ownsPlayer(Entity player) { checkThread(); return players.containsKey(player); }

    private PlayerEntity importedPlayer(UUID id) {
        checkThread();
        for (var state : playerOrder) {
            var player = state.ownedPlayer();
            if (player.getUuid().equals(id) && !player.isRemoved()) return player;
        }
        return null;
    }

    @SuppressWarnings("WrapperReferenceEquality") // Registry entries must come from this exact frozen session registry.
    void requireDamageSource(DamageSource source) {
        checkThread();
        Objects.requireNonNull(source, "damage source");
        nullableOwnedId(source.getSource());
        nullableOwnedId(source.getAttacker());
        if (source.getPosition() != null) requirePosition(source.getPosition());
        var type = source.getTypeRegistryEntry();
        var key = type.getKey().orElseThrow(() -> new IllegalArgumentException("Unregistered damage type"));
        if (world.getDamageSources().registry.getOrThrow(key) != type) throw new IllegalArgumentException("Damage type belongs to another registry");
    }

    private void entityDamage(Entity entity, DamageSource source) {
        UUID target = ownedId(entity);
        requireDamageSource(source);
        Vec3d position = source.getStoredPosition();
        queries.output(new DamageOutput(target, source.getTypeRegistryEntry().getKey().orElseThrow().getValue().toString(),
                nullableOwnedId(source.getSource()), nullableOwnedId(source.getAttacker()), position == null ? null : position(position)));
    }

    private void trackedPacket(Entity entity, net.minecraft.network.packet.Packet<?> packet, boolean includeSelf) {
        UUID id = ownedId(entity);
        var data = packetData.capture(Objects.requireNonNull(packet, "native packet"));
        if (data == null) throw new IllegalArgumentException("Native tracked packet requires a rollback adapter: " + packet.getClass().getName());
        if (data.entityId() != entity.getId()) throw new IllegalArgumentException("Tracked packet belongs to another entity");
        queries.output(new FabricRollbackPacketData.Tracked(id, includeSelf, data));
    }

    private void sound(Object[] args, boolean seeded) {
        UUID source = nullableOwnedId((Entity) args[0]);
        var position = new Vec3d((double) args[1], (double) args[2], (double) args[3]);
        requirePosition(position);
        SoundEvent event = args[4] instanceof SoundEvent sound ? sound : (SoundEvent) ((RegistryEntry<?>) args[4]).value();
        queries.output(new SoundOutput(source, position(position), event.id().toString(), ((SoundCategory) args[5]).getName(),
                (float) args[6], (float) args[7], seeded ? (long) args[8] : queries.nextSoundSeed()));
    }

    private static Position position(Vec3d position) { return new Position(position.x, position.y, position.z); }
    private static void requirePosition(Vec3d position) {
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)) throw new IllegalArgumentException("Native event position");
    }
    private UUID nullableOwnedId(Entity entity) { return entity == null ? null : ownedId(entity); }
    @SuppressWarnings("WrapperReferenceEquality")
    private UUID ownedId(Entity entity) {
        if (entity.getEntityWorld() != world || !ownsPlayer(entity)) throw new IllegalArgumentException("Event belongs to an unowned native entity");
        return entity.getUuid();
    }

    private static void check(Box box) {
        Objects.requireNonNull(box, "query bounds");
        RollbackMovementSolver.requireBlockQuery(new RollbackBlockStore.Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ));
    }

    private static void checkRegion(int minX, int minZ, int maxX, int maxZ) {
        if (maxX < minX || maxZ < minZ) throw new IllegalArgumentException("Inverted chunk query bounds");
        int firstX = minX >> 4, lastX = maxX >> 4, firstZ = minZ >> 4, lastZ = maxZ >> 4;
        long width = (long) lastX - firstX + 1, depth = (long) lastZ - firstZ + 1;
        if (width * depth > 65_536) throw new IllegalStateException("Native loaded-region query budget exceeded");
    }

    @SuppressWarnings("unchecked")
    private Object environmentValue(Object[] arguments) {
        var attribute = (EnvironmentAttribute<Object>) arguments[0];
        var access = Objects.requireNonNull(queries.environmentAttributes(), "captured environment attributes");
        if (arguments.length == 1) return access.getAttributeValue(attribute);
        if (arguments[1] instanceof BlockPos pos) return access.getAttributeValue(attribute, pos);
        var pos = (Vec3d) arguments[1];
        if (!Double.isFinite(pos.x) || !Double.isFinite(pos.y) || !Double.isFinite(pos.z)) throw new IllegalArgumentException("Environment query position");
        return access.getAttributeValue(attribute, pos, arguments.length == 3 ? (WeightedAttributeList) arguments[2] : null);
    }

    @SuppressWarnings({"unchecked", "WrapperReferenceEquality"})
    private List<Entity> otherEntities(Object[] arguments) {
        var except = (Entity) arguments[0]; var bounds = (Box) arguments[1];
        check(bounds);
        var predicate = (Predicate<? super Entity>) arguments[2];
        var candidates = queries.otherEntities(except, bounds);
        if (candidates.size() > 65_536) throw new IllegalStateException("Native entity query budget exceeded");
        var result = new ArrayList<Entity>();
        for (Entity entity : candidates) {
            if (entity.getEntityWorld() != world) throw new IllegalArgumentException("Native query entity belongs to another world");
            if (entity != except && entity.getBoundingBox().intersects(bounds) && predicate.test(entity)) result.add(entity);
        }
        return List.copyOf(result);
    }

    @SuppressWarnings("WrapperReferenceEquality")
    private void waypoint(WaypointAction action, Object value) {
        if (!(value instanceof Entity entity) || entity.getEntityWorld() != world || !ownsPlayer(entity)) {
            throw new IllegalArgumentException("Waypoint belongs to an unowned native entity");
        }
        queries.waypoint(action, entity);
    }

    @SuppressWarnings("WrapperReferenceEquality")
    private void requireTickTree(Entity root) {
        Objects.requireNonNull(root, "tick entity");
        if (root.hasVehicle()) throw new IllegalArgumentException("Tick passengers through their owning vehicle");
        var seen = new IdentityHashMap<Entity, Boolean>();
        var pending = new java.util.ArrayDeque<Entity>();
        pending.add(root);
        int count = 1;
        while (!pending.isEmpty()) {
            Entity entity = pending.removeFirst();
            if (entity.getEntityWorld() != world || !ownsPlayer(entity) || seen.put(entity, true) != null) {
                throw new IllegalArgumentException("Native tick contains an unowned entity or passenger cycle");
            }
            // ServerWorld.tickPassenger reads its native entityList for non-player
            // passengers. Those require the forthcoming typed-entity adapter.
            for (Entity passenger : entity.getPassengerList()) {
                if (passenger.getVehicle() != entity) throw new IllegalArgumentException("Broken native passenger relationship");
                if (++count > players.size()) throw new IllegalArgumentException("Native passenger tree exceeds the owned roster");
                pending.addLast(passenger);
            }
        }
    }

    @Override public Void captureRollbackState() { beginCheckpoint(); return null; }
    @Override public void restoreRollbackState(Void state) { checkThread(); }
    @Override public List<?> rollbackReferences() {
        checkThread();
        var references = new ArrayList<Object>(playerOrder.size() + 1);
        references.add(queries);
        if (round != null) { references.add(round); references.add(damagePolicy); }
        references.addAll(playerOrder);
        return List.copyOf(references);
    }

    private void checkThread() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Native world query crossed threads");
    }
}
