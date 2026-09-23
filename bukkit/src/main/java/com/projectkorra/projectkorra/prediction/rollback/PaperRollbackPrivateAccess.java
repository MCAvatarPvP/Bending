package com.projectkorra.projectkorra.prediction.rollback;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerSynchronizer;
import com.google.common.cache.LoadingCache;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.StatsCounter;
import net.minecraft.stats.Stat;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.Set;
import java.util.Map;
import java.time.Instant;
import net.minecraft.advancements.*;
import net.minecraft.advancements.criterion.SimpleCriterionTrigger;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.bukkit.craftbukkit.damage.CraftDamageSource;
import org.bukkit.craftbukkit.damage.CraftDamageType;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.objenesis.ObjenesisStd;
import org.spigotmc.SpigotWorldConfig;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/**
 * Fixed private/final-field access that paperweight cannot expose to Java source.
 * Member discovery happens once; typed calls below initialize owned replicas.
 * This never writes a live server object or changes native class definitions.
 */
final class PaperRollbackPrivateAccess {
    private static final MethodHandle CONFIG = setter(Level.class, "spigotConfig");
    private static final MethodHandle CHUNKS = setter(ServerLevel.class, "chunkSource");
    private static final MethodHandle STATES = setter(LevelChunkSection.class, "states");
    private static final MethodHandle SPECIAL = setter(LevelChunkSection.class, "specialCollidingBlocks");
    private static final MethodHandle BUKKIT = setter(Entity.class, "bukkitEntity");
    private static final MethodHandle RANDOM = setter(Entity.class, "random");
    private static final MethodHandle SOURCE = setter(CraftDamageSource.class, "damageSource");
    private static final MethodHandle TYPE = setter(CraftDamageSource.class, "damageType");
    private static final MethodHandle ENCHANTMENT_ORDER = getter(ItemEnchantments.class, "ENCHANTMENT_ORDER");
    private static final MethodHandle STATS = setter(StatsCounter.class, "stats");
    private static final MethodHandle DIRTY_STATS = setter(ServerStatsCounter.class, "dirty");
    private static final MethodHandle ADVANCEMENT_PLAYER_LIST = setter(PlayerAdvancements.class, "playerList");
    private static final MethodHandle ADVANCEMENT_PLAYER = setter(PlayerAdvancements.class, "player");
    private static final MethodHandle ADVANCEMENT_TREE = setter(PlayerAdvancements.class, "tree");
    private static final MethodHandle ADVANCEMENT_PROGRESS = setter(PlayerAdvancements.class, "progress");
    private static final MethodHandle ADVANCEMENT_VISIBLE = setter(PlayerAdvancements.class, "visible");
    private static final MethodHandle ADVANCEMENT_CHANGED = setter(PlayerAdvancements.class, "progressChanged");
    private static final MethodHandle ADVANCEMENT_ROOTS = setter(PlayerAdvancements.class, "rootsToUpdate");
    private static final MethodHandle ADVANCEMENT_LISTENERS = setter(PlayerAdvancements.class, "criterionData");
    private static final MethodHandle ADVANCEMENT_FIRST_PACKET = setter(PlayerAdvancements.class, "isFirstPacket");
    private static final MethodHandle ADVANCEMENT_FIRST_PACKET_READ = getter(PlayerAdvancements.class, "isFirstPacket");
    private static final MethodHandle CRITERION_OBTAINED = setter(CriterionProgress.class, "obtained");
    private static final MethodHandle HEALTH = setter(CraftPlayer.class, "health");
    private static final MethodHandle HEALTH_SCALE = setter(CraftPlayer.class, "healthScale");
    private static final MethodHandle EQUIPMENT_SANITIZED = getter(net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket.class, "sanitize");
    private static final MethodHandle CONTAINER_LISTENER = getter(ServerPlayer.class, "containerListener");
    private static final ObjenesisStd ALLOCATOR = new ObjenesisStd(false);

    private static final ClassValue<MethodHandle> CONTAINER_HASHES = new ClassValue<>() {
        @Override protected MethodHandle computeValue(Class<?> type) {
            // The actual native anonymous synchronizer class is compiler-named.
            // Discover its private cache once, retaining a typed accessor thereafter.
            return getter(type, "cache").asType(java.lang.invoke.MethodType.methodType(LoadingCache.class, ContainerSynchronizer.class));
        }
    };

    private PaperRollbackPrivateAccess() { }
    static net.minecraft.world.inventory.ContainerListener containerListener(ServerPlayer player) {
        try { return (net.minecraft.world.inventory.ContainerListener) CONTAINER_LISTENER.invokeExact(player); }
        catch (Throwable failure) { throw failed(failure); }
    }
    static boolean equipmentSanitized(net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket packet) {
        try { return (boolean) EQUIPMENT_SANITIZED.invokeExact(packet); }
        catch (Throwable failure) { throw failed(failure); }
    }

    static void initializeHealth(CraftPlayer player, double health) {
        try { HEALTH.invokeExact(player, health); HEALTH_SCALE.invokeExact(player, 20D); }
        catch (Throwable failure) { throw new IllegalStateException("Private player health initialization failed", failure); }
    }

    static void initializeConfig(Level level) {
        try { CONFIG.invokeExact(level, ALLOCATOR.newInstance(SpigotWorldConfig.class)); }
        catch (Throwable failure) { throw failed(failure); }
    }
    static void initializeChunks(ServerLevel level, ServerChunkCache chunks) {
        try { CHUNKS.invokeExact(level, chunks); }
        catch (Throwable failure) { throw failed(failure); }
    }
    static void initializeSection(LevelChunkSection section, PalettedContainer<BlockState> states) {
        try { STATES.invokeExact(section, states); SPECIAL.invokeExact(section, (short) 1); }
        catch (Throwable failure) { throw failed(failure); }
    }
    static void initializePlayer(Entity entity, CraftEntity wrapper, RandomSource random) {
        try { BUKKIT.invokeExact(entity, wrapper); RANDOM.invokeExact(entity, random); }
        catch (Throwable failure) { throw failed(failure); }
    }
    static Object enchantmentOrder() {
        try { return (java.util.Comparator<?>) ENCHANTMENT_ORDER.invokeExact(); }
        catch (Throwable failure) { throw failed(failure); }
    }
    static void initializeStatistics(ServerStatsCounter counter, Object2IntMap<Stat<?>> values, Set<Stat<?>> dirty) {
        try { STATS.invokeExact((StatsCounter) counter, values); DIRTY_STATS.invokeExact(counter, dirty); }
        catch (Throwable failure) { throw failed(failure); }
    }
    static void initializeAdvancements(PlayerAdvancements tracker, PlayerList players, AdvancementTree tree,
            Map<AdvancementHolder, AdvancementProgress> progress, Set<AdvancementHolder> visible, Set<AdvancementHolder> changed,
            Set<AdvancementNode> roots, Map<SimpleCriterionTrigger<?>, Set<CriterionTrigger.Listener<?>>> listeners) {
        try {
            ADVANCEMENT_PLAYER_LIST.invokeExact(tracker, players); ADVANCEMENT_TREE.invokeExact(tracker, tree);
            ADVANCEMENT_PROGRESS.invokeExact(tracker, progress); ADVANCEMENT_VISIBLE.invokeExact(tracker, visible);
            ADVANCEMENT_CHANGED.invokeExact(tracker, changed); ADVANCEMENT_ROOTS.invokeExact(tracker, roots);
            ADVANCEMENT_LISTENERS.invokeExact(tracker, listeners); ADVANCEMENT_FIRST_PACKET.invokeExact(tracker, true);
        } catch (Throwable failure) { throw failed(failure); }
    }
    static void advancementPlayer(PlayerAdvancements tracker, ServerPlayer player) {
        try { ADVANCEMENT_PLAYER.invokeExact(tracker, player); }
        catch (Throwable failure) { throw failed(failure); }
    }
    static boolean advancementFirstPacket(PlayerAdvancements tracker) {
        try { return (boolean) ADVANCEMENT_FIRST_PACKET_READ.invokeExact(tracker); }
        catch (Throwable failure) { throw failed(failure); }
    }
    static void advancementFirstPacket(PlayerAdvancements tracker, boolean value) {
        try { ADVANCEMENT_FIRST_PACKET.invokeExact(tracker, value); }
        catch (Throwable failure) { throw failed(failure); }
    }
    static void obtainCriterion(CriterionProgress criterion, Instant obtained) {
        try { CRITERION_OBTAINED.invokeExact(criterion, obtained); }
        catch (Throwable failure) { throw failed(failure); }
    }
    @SuppressWarnings("unchecked")
    static LoadingCache<TypedDataComponent<?>, Integer> containerHashes(ServerPlayer player) {
        ContainerSynchronizer synchronizer = player.containerSynchronizer;
        try { return (LoadingCache<TypedDataComponent<?>, Integer>) CONTAINER_HASHES.get(synchronizer.getClass()).invokeExact(synchronizer); }
        catch (Throwable failure) { throw failed(failure); }
    }
    static CraftDamageSource damageSource(DamageSource source) {
        CraftDamageSource wrapper = ALLOCATOR.newInstance(CraftDamageSource.class);
        org.bukkit.damage.DamageType type = new CraftDamageType(source.typeHolder());
        try { SOURCE.invokeExact(wrapper, source); TYPE.invokeExact(wrapper, type); return wrapper; }
        catch (Throwable failure) { throw failed(failure); }
    }

    private static MethodHandle setter(Class<?> owner, String name) {
        try {
            var field = owner.getDeclaredField(name);
            if (!field.trySetAccessible()) throw new IllegalStateException("Inaccessible private native field: " + field);
            // Accessible-field unreflection permits writes to these instance
            // finals during private replica construction, without Field.set calls.
            return MethodHandles.lookup().unreflectSetter(field);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native replica field changed: " + owner.getName() + "." + name, failure); }
    }
    private static MethodHandle getter(Class<?> owner, String name) {
        try { return MethodHandles.privateLookupIn(owner, MethodHandles.lookup()).unreflectGetter(owner.getDeclaredField(name)); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native metadata field changed: " + owner.getName() + "." + name, failure); }
    }
    private static RuntimeException failed(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException("Private native field access failed", failure);
    }
}
