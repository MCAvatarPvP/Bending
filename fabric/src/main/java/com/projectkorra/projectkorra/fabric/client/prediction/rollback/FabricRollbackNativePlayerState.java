package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.projectkorra.projectkorra.prediction.rollback.RollbackClock;
import com.projectkorra.projectkorra.prediction.rollback.RollbackMovementInput;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateGraph;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackLivingState;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayerState;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Box;
import net.minecraft.block.BlockState;
import net.minecraft.component.ComponentType;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.attribute.DefaultAttributeRegistry;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.IdentityHashMap;
import java.util.function.Function;

/**
 * In-process checkpoint of an owned native player, including transient movement,
 * tracked data, attributes, inventory and random state. Only frozen registry/type
 * metadata is external. Registry reloads are not permitted during the session.
 * Unknown mutable native/JDK state fails capture; it is never silently omitted.
 *
 * <p>This is not a network snapshot codec or a complete player tick adapter. The
 * native player must be constructed against the private world before replay and
 * imported/synchronized by the loader. Live player/world handles are rejected.</p>
 * Capture via the outer state graph so the world roster and terrain rewind too.
 */
public final class FabricRollbackNativePlayerState implements RollbackPlayerState.Source<FabricRollbackNativePlayerState.Checkpoint> {
    private static final class RegistryMetadata {
        static final Object DEFAULT_ATTRIBUTES = DefaultAttributeRegistry.get(EntityType.PLAYER);
        static final Map<Object, Boolean> ITEM_PROTOTYPES = itemPrototypes();
        private static Map<Object, Boolean> itemPrototypes() {
            var prototypes = new IdentityHashMap<Object, Boolean>();
            for (Item item : Registries.ITEM) prototypes.put(item.getComponents(), true);
            return java.util.Collections.unmodifiableMap(prototypes);
        }
    }
    private final Thread thread = Thread.currentThread();
    private final PlayerEntity player;
    private final FabricRollbackWorldAccess world;
    private final RollbackStateGraph graph;
    private boolean using;
    private FabricRollbackLivingAccess livingAccess;
    private FabricRollbackPlayerControls playerControls;
    // Paper has bukkitPickUpLoot; vanilla has no corresponding field. The private
    // spawned-item service must consult this captured policy when it is connected.
    private boolean pickupItems = true;
    private FabricRollbackPlayerContextData.Retained importedContext;
    private java.util.UUID importedExplosionCause;

    @SuppressWarnings("WrapperReferenceEquality") // The native replica must belong to this exact private world.
    public FabricRollbackNativePlayerState(PlayerEntity player, FabricRollbackWorldAccess world, int maximumObjects) {
        this.player = Objects.requireNonNull(player, "player");
        this.world = Objects.requireNonNull(world, "world");
        if (RollbackClock.active()) throw new IllegalStateException("Construct native player state before replay");
        if (player instanceof ServerPlayerEntity) throw new IllegalArgumentException("Server player requires a private connection/event adapter");
        if (player.getEntityWorld() != world.world()) throw new IllegalArgumentException("Native player belongs to another world");
        graph = new RollbackStateGraph(value -> otherEntity(value) || value == world.world()
                || value == RegistryMetadata.DEFAULT_ATTRIBUTES || RegistryMetadata.ITEM_PROTOTYPES.containsKey(value)
                || value instanceof EntityType<?> || value instanceof RegistryEntry.Reference<?>
                || value instanceof TrackedData<?> || value instanceof ComponentType<?>
                || value instanceof Item || value instanceof BlockState
                || value instanceof Codec<?> || value instanceof MapCodec<?>
                || value == ItemStack.EMPTY, field -> {
                    // Fastutil lazily retains entry/key/value views as caches. They
                    // read through the map, whose contents, backing arrays and default
                    // return value are captured. Traversing the caches would try to
                    // restore their membership independently from the owning map.
                    return !(field.getDeclaringClass().getName().startsWith("it.unimi.dsi.fastutil.")
                            && Map.class.isAssignableFrom(field.getDeclaringClass())
                            && Collection.class.isAssignableFrom(field.getType())
                            && Set.of("entries", "keys", "values").contains(field.getName()));
                }, maximumObjects);
        world.registerPlayer(player, this);
    }

    /** Native values must stay inside the adapter; outputs are detached logical values. */
    public <R> R use(Function<PlayerEntity, R> operation) {
        requireIdle();
        Objects.requireNonNull(operation, "operation");
        using = true;
        try { return FabricRollbackGliding.use(world, player, operation); }
        finally { using = false; }
    }

    @Override public Identity identity() {
        requireOwned();
        return new Identity(player.getUuid(), player.getId(), player.getGameProfile().name(),
                com.projectkorra.projectkorra.platform.mc.entity.EntityType.PLAYER);
    }

    private FabricRollbackLivingAccess livingAccess() {
        requireOwned();
        // Derived accessors retain only this owner and frozen registry metadata.
        if (livingAccess == null) livingAccess = new FabricRollbackLivingAccess(this, world, this::ownedPlayer);
        return livingAccess;
    }
    @Override public RollbackLivingState.Vitals readVitals() { return livingAccess().read(); }
    @Override public void writeVitals(RollbackLivingState.Vitals value) { livingAccess().write(value); }
    @Override public Double attributeValue(String name) { return livingAccess().attributeValue(name); }
    @Override public Map<String, Double> readAttributes() { return livingAccess().attributes(); }
    @Override public PotionEffect readPotion(PotionEffectType type) { return livingAccess().potion(type); }
    @Override public Collection<PotionEffect> readPotions() { return livingAccess().potions(); }
    @Override public boolean dead() { return !ownedPlayer().isAlive(); }
    private FabricRollbackPlayerControls playerControls() {
        requireOwned();
        if (playerControls == null) playerControls = new FabricRollbackPlayerControls(this);
        return playerControls;
    }
    @Override public RollbackPlayerState.Controls readControls() { return playerControls().read(); }
    @Override public void changeControls(RollbackPlayerState target, RollbackPlayerState.Control changed, RollbackPlayerState.Controls proposed) { playerControls().write(target, changed, proposed); }
    @Override public boolean requestFlight(boolean flying, boolean cancelled) {
        return use(player -> {
            var abilities = player.getAbilities();
            if (!abilities.allowFlying) return false;
            if (abilities.flying == flying) return true;
            if (!world.flightAllowed(player, flying, cancelled)) return false;
            abilities.flying = flying;
            player.sendAbilitiesUpdate();
            if (flying && player.isOnGround()) player.jump();
            return true;
        });
    }
    @Override public boolean requestGlide() {
        if (!(player instanceof FabricRollbackSimulatedPlayer)) throw new IllegalStateException("Glide input requires the private simulation body");
        return use(player -> { if (!player.checkGliding()) player.stopGliding(); return player.isGliding(); });
    }
    boolean pickupItems() { requireOwned(); return pickupItems; }
    void pickupItems(boolean value) { requireOwned(); pickupItems = value; }
    @Override public void damage(RollbackLivingState target, double amount, com.projectkorra.projectkorra.platform.mc.entity.Entity source) { livingAccess().damage(target, amount, source); }
    @Override public boolean addPotion(RollbackLivingState target, PotionEffect effect, boolean force) { return livingAccess().addPotion(target, effect, force); }
    @Override public void removePotion(RollbackLivingState target, PotionEffectType type) { livingAccess().removePotion(target, type); }
    @Override public void attribute(RollbackLivingState target, String name, double value) { livingAccess().attribute(target, name, value); }

    @Override public Kinematics readKinematics() {
        requireOwned();
        var box = player.getBoundingBox(); var velocity = player.getVelocity();
        double x = player.getX(), y = player.getY(), z = player.getZ();
        return new Kinematics(new Pose(x, y, z, player.getYaw(), player.getPitch()),
                new Motion(velocity.x, velocity.y, velocity.z),
                new Box(box.minX - x, box.minY - y, box.minZ - z, box.maxX - x, box.maxY - y, box.maxZ - z),
                player.getHeight(), player.isOnGround(), player.fallDistance, player.knockedBack);
    }

    @Override public void writeKinematics(Kinematics next) {
        requireOwned(); Objects.requireNonNull(next, "kinematics");
        var current = readKinematics();
        if (next.height() != current.height() || !next.bounds().equals(current.bounds())) {
            throw new IllegalArgumentException("Change player dimensions through native pose processing");
        }
        var p = next.pose(); var b = next.bounds(); var v = next.velocity();
        var box = new Box(p.x() + b.minX(), p.y() + b.minY(), p.z() + b.minZ(),
                p.x() + b.maxX(), p.y() + b.maxY(), p.z() + b.maxZ());
        boolean moved = p.x() != current.pose().x() || p.y() != current.pose().y() || p.z() != current.pose().z();
        if (moved) {
            player.setPosition(p.x(), p.y(), p.z());
            player.setBoundingBox(new net.minecraft.util.math.Box(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
        }
        if (p.yaw() != current.pose().yaw()) player.setYaw(p.yaw());
        if (p.pitch() != current.pose().pitch()) player.setPitch(p.pitch());
        if (!v.equals(current.velocity())) player.setVelocity(v.x(), v.y(), v.z());
        if (moved || next.onGround() != current.onGround()) player.setOnGround(next.onGround());
        player.fallDistance = next.fallDistance();
        // Yarn's knockedBack is Paper's hurtMarked (send a velocity update to
        // the owning client). velocityDirty is the separate hasImpulse flag.
        player.knockedBack = next.velocityChanged();
    }

    /** Native world entity tick, including age/previous-position updates and owned player passengers. */
    public void tick() {
        use(entity -> {
            ((net.minecraft.server.world.ServerWorld) world.world()).tickEntity(entity);
            return null;
        });
    }

    public void movementInput(RollbackMovementInput input) {
        Objects.requireNonNull(input, "input"); requireOwned();
        player.sidewaysSpeed = input.strafe(); player.forwardSpeed = input.forward(); player.upwardSpeed = 0;
        player.setJumping(input.jump()); player.setYaw(input.yaw()); player.setPitch(input.pitch());
    }

    /** Runs native damage rules; health, immunity, equipment and knockback remain in this world's checkpoint. */
    public boolean damage(net.minecraft.entity.damage.DamageSource source, float amount) {
        requireIdle();
        if (!Float.isFinite(amount) || amount < 0) throw new IllegalArgumentException("Damage amount");
        world.requireDamageSource(source);
        return use(entity -> entity.damage((net.minecraft.server.world.ServerWorld) world.world(), source, amount));
    }

    @Override public Checkpoint captureRollbackState() {
        requireIdle();
        world.sealPlayers();
        return new Checkpoint(this, graph.capture(List.of(player), List.of()));
    }

    @Override public void restoreRollbackState(Checkpoint checkpoint) {
        requireIdle();
        if (Objects.requireNonNull(checkpoint, "checkpoint").owner != this) throw new IllegalArgumentException("Native player checkpoint belongs to another replica");
        checkpoint.state.restore();
        pickupItems = checkpoint.pickupItems;
        importedContext = checkpoint.importedContext;
        importedExplosionCause = checkpoint.importedExplosionCause;
    }

    @Override public List<?> rollbackReferences() { requireIdle(); return List.of(world); }

    /** Retained logical item mirrors may outlive their inventory slot. */
    RollbackStateGraph.Snapshot captureItem(ItemStack item) {
        requireIdle();
        return graph.capture(List.of(item), List.of());
    }

    public static final class Checkpoint {
        private final FabricRollbackNativePlayerState owner;
        private final RollbackStateGraph.Snapshot state;
        private final boolean pickupItems;
        private final FabricRollbackPlayerContextData.Retained importedContext;
        private final java.util.UUID importedExplosionCause;
        private Checkpoint(FabricRollbackNativePlayerState owner, RollbackStateGraph.Snapshot state) {
            this.owner = owner;
            this.state = state;
            this.pickupItems = owner.pickupItems;
            this.importedContext = owner.importedContext;
            this.importedExplosionCause = owner.importedExplosionCause;
        }
    }

    @SuppressWarnings("WrapperReferenceEquality")
    private void requireIdle() {
        requireOwned();
        if (using) throw new IllegalStateException("Reentrant native player operation or checkpoint");
    }

    @SuppressWarnings("WrapperReferenceEquality")
    private void requireOwned() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Native player state crossed threads");
        if (player.getEntityWorld() != world.world()) throw new IllegalStateException("Native player ownership changed");
    }

    PlayerEntity ownedPlayer() { requireOwned(); return player; }
    FabricRollbackPlayerContextData.Retained importedContext() { requireIdle(); return importedContext; }
    void importedContext(FabricRollbackPlayerContextData.Retained value) { requireIdle(); importedContext = Objects.requireNonNull(value); }
    java.util.UUID importedExplosionCause() { requireIdle(); return importedExplosionCause; }
    void importedExplosionCause(java.util.UUID value) { requireIdle(); importedExplosionCause = value; }

    @SuppressWarnings("WrapperReferenceEquality")
    private boolean otherEntity(Object value) {
        if (value == player || !(value instanceof Entity entity)) return false;
        if (!world.ownsPlayer(entity)) throw new IllegalStateException("Native player references an unowned entity");
        // The outer world graph snapshots the complete roster; cross-player native
        // references are identity links to those independently owned state cells.
        return true;
    }
}
