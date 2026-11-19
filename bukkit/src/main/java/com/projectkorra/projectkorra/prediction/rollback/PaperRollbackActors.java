package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.*;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.prediction.rollback.*;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.*;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ServerExplosion;

import java.lang.reflect.Method;
import java.util.*;

/** Native actors created by the existing bending APIs in one owned Paper scene. */
public final class PaperRollbackActors implements RollbackArenaActions.Native, RollbackStateCell<Void> {
    private static final Map<EntityType, Class<? extends Entity>> FACADES = Map.ofEntries(
            Map.entry(EntityType.BLOCK_DISPLAY, BlockDisplay.class), Map.entry(EntityType.ITEM_DISPLAY, ItemDisplay.class),
            Map.entry(EntityType.ARMOR_STAND, ArmorStand.class), Map.entry(EntityType.AREA_EFFECT_CLOUD, AreaEffectCloud.class),
            Map.entry(EntityType.ARROW, Arrow.class), Map.entry(EntityType.SMALL_FIREBALL, Fireball.class),
            Map.entry(EntityType.FALLING_BLOCK, FallingBlock.class), Map.entry(EntityType.ITEM, Item.class),
            Map.entry(EntityType.SHULKER_BULLET, ShulkerBullet.class), Map.entry(EntityType.SNOWBALL, Snowball.class),
            Map.entry(EntityType.TRIDENT, Trident.class), Map.entry(EntityType.SLIME, Slime.class),
            Map.entry(EntityType.TNT, TNTPrimed.class));
    private final RollbackActorRegistry registry;
    private final RollbackNativeItems<net.minecraft.world.item.ItemStack> items;
    private PaperRollbackNativeScene scene;
    public PaperRollbackActors(UUID session, int maximum, RollbackNativeItems<net.minecraft.world.item.ItemStack> items) {
        registry = new RollbackActorRegistry(session, maximum); this.items = Objects.requireNonNull(items);
    }
    void bind(PaperRollbackNativeScene scene) {
        if (this.scene != null) throw new IllegalStateException("Native actor owner already bound");
        this.scene = Objects.requireNonNull(scene);
        scene.world().bindActors(registry);
    }
    private ServerLevel server() { return scene.world().world(); }
    private void requireWorld(RollbackWorld target) { if (target != scene.logical()) throw new IllegalArgumentException("Foreign private actor world"); }
    public RollbackActorRegistry registry() { return registry; }
    List<net.minecraft.world.entity.Entity> active() { return registry.active().stream().map(a -> ((PaperRollbackActor) a).entity()).toList(); }
    net.minecraft.world.entity.Entity actor(UUID id) {
        var found = (PaperRollbackActor) registry.get(id);
        return found == null ? null : found.entity();
    }
    Entity logical(net.minecraft.world.entity.Entity e) {
        if (e == null) return null;
        var view = scene.logical().entities().get(e.getUUID());
        if (view == null || nativeEntity(view) != e) throw new IllegalArgumentException("Unowned actor reference");
        return view;
    }
    net.minecraft.world.entity.Entity nativeEntity(Entity view) {
        if (view == null) return null;
        var body = RollbackEntityBody.logicalBody(view);
        if (body.world() != scene.logical()) throw new IllegalArgumentException("Foreign native actor world");
        return switch (body.kinematicsSource()) {
            case PaperRollbackNativePlayerState player -> player.ownedPlayer();
            case PaperRollbackActor actor -> actor.entity();
            default -> throw new IllegalArgumentException("Unowned native actor");
        };
    }
    private static EntityType kind(Class<?> type) {
        return FACADES.entrySet().stream().filter(e -> e.getValue() == type).findFirst().orElseThrow(
                () -> new IllegalArgumentException("No concrete actor type for " + type)).getKey();
    }
    private <T extends Entity> PaperRollbackActor make(Identity id, Class<T> type) {
        var key = Identifier.withDefaultNamespace(id.type().name().toLowerCase(Locale.ROOT));
        var nativeType = BuiltInRegistries.ENTITY_TYPE.getOptional(key).orElseThrow();
        var entity = nativeType == net.minecraft.world.entity.EntityType.SNOWBALL ? new PaperRollbackSnowball(scene)
                : nativeType == net.minecraft.world.entity.EntityType.ARROW ? new PaperRollbackArrow(scene)
                : Objects.requireNonNull(nativeType.create(server(), EntitySpawnReason.TRIGGERED), "Cannot create private actor " + key);
        entity.setId(id.networkId()); entity.setUUID(id.uuid());
        entity.spawnReason = org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM;
        var craft = PaperRollbackPrivateAccess.actorWrapper(entity, server().getCraftServer(), org.bukkit.entity.EntityType.valueOf(id.type().name()));
        var random = new net.minecraft.world.level.levelgen.LegacyRandomSource(id.uuid().getLeastSignificantBits() ^ id.uuid().getMostSignificantBits());
        PaperRollbackPrivateAccess.initializePlayer(entity, craft, random);
        var delegate = PaperRollbackActorView.wrap(type, craft);
        return new PaperRollbackActor(scene, entity, id, this, type, (method, args) -> special(entity, craft, delegate, method, args));
    }
    private Object special(net.minecraft.world.entity.Entity e, org.bukkit.craftbukkit.entity.CraftEntity craft,
            Entity delegate, Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
            case "getEyeHeight": return (double) e.getEyeHeight();
            case "getEyeLocation": {
                var at = e.getEyePosition(); return new Pose(at.x, at.y, at.z, e.getYRot(), e.getXRot()).at(scene.logical());
            }
            case "setDamage": ((net.minecraft.world.entity.projectile.arrow.AbstractArrow) e).setBaseDamage((int) args[0]); return null;
            case "getPickupStatus": return Arrow.PickupStatus.valueOf(((net.minecraft.world.entity.projectile.arrow.AbstractArrow) e).pickup.name());
            case "setPickupStatus":
                ((net.minecraft.world.entity.projectile.arrow.AbstractArrow) e).pickup =
                        net.minecraft.world.entity.projectile.arrow.AbstractArrow.Pickup.valueOf(((Arrow.PickupStatus) args[0]).name());
                return null;
            case "isInBlock": return ((net.minecraft.world.entity.projectile.arrow.AbstractArrow) e).isInGround();
            case "isInWaterOrBubbleColumn": return e.isInWater() || e.level().getBlockState(e.blockPosition()).is(net.minecraft.world.level.block.Blocks.BUBBLE_COLUMN);
            case "setShooter": ((Projectile) e).setOwner(nativeEntity((Entity) args[0])); return null;
            case "getShooter": return logical(((Projectile) e).getOwner());
            case "setSize": ((org.bukkit.entity.Slime) craft).setSize((int) args[0]); return null;
            case "setItemStack": {
                var stack = items.nativeCopy((ItemStack) args[0]);
                if (e instanceof net.minecraft.world.entity.item.ItemEntity item) item.setItem(stack);
                else ((org.bukkit.entity.ItemDisplay) craft).setItemStack(org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(stack));
                return null;
            }
            case "getItemStack": {
                var stack = e instanceof net.minecraft.world.entity.item.ItemEntity item ? item.getItem()
                        : org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(((org.bukkit.entity.ItemDisplay) craft).getItemStack());
                return items.capture(stack);
            }
            case "setBlock": ((org.bukkit.entity.BlockDisplay) craft).setBlock(org.bukkit.craftbukkit.block.data.CraftBlockData.fromData(
                        new PaperRollbackBlockStates().decode((BlockData) args[0]))); return null;
            case "setTarget": ((net.minecraft.world.entity.projectile.ShulkerBullet)e).setTarget(nativeEntity((Entity) args[0])); return null;
            case "getTarget": return logical(((net.minecraft.world.entity.projectile.ShulkerBullet)e).getTarget());
            case "setSource": ((org.bukkit.entity.AreaEffectCloud)craft).setSource(
                        args[0] == null ? null : (org.bukkit.projectiles.ProjectileSource) nativeEntity((Entity) args[0]).getBukkitEntity()); return null;
            default: return method.invoke(delegate, args);
        }
    }
    private PaperRollbackActor at(RollbackWorld world, Pose pos, EntityType kind, Class<? extends Entity> type) {
        requireWorld(world); var id = registry.allocate(world, kind.name(), kind);
        var actor = make(id, type);
        actor.entity().snapTo(pos.x(), pos.y(), pos.z(), pos.yaw(), pos.pitch());
        return registry.own(actor);
    }
    @Override public <T extends Entity> T spawn(RollbackWorld world, Pose pos, Class<T> type) { return type.cast(at(world, pos, kind(type), type).view()); }
    @Override public Entity spawnEntity(RollbackWorld world, Pose pos, EntityType kind) {
        return at(world, pos, kind, FACADES.getOrDefault(kind, LivingEntity.class)).view();
    }
    @Override public Item dropItem(RollbackWorld world, Pose pos, ItemStack item, boolean natural) {
        var rand = server().getRandom();
        if (natural) pos = new Pose(pos.x() + rand.nextDouble() * .5 + .25, pos.y() + rand.nextDouble() * .5 + .25,
                pos.z() + rand.nextDouble() * .5 + .25, pos.yaw(), pos.pitch());
        var actor = at(world, pos, EntityType.ITEM, Item.class);
        var drop = (net.minecraft.world.entity.item.ItemEntity) actor.entity();
        drop.setItem(items.nativeCopy(item)); drop.setDefaultPickUpDelay();
        var random = drop.getRandom();
        drop.setDeltaMovement(random.nextDouble() * .2 - .1, .2, random.nextDouble() * .2 - .1);
        return (Item) actor.view();
    }
    /** The native drop input owns the same registered item body exposed to bending and restored on handoff. */
    void dropSelected(net.minecraft.server.level.ServerPlayer player, boolean wholeStack) {
        if (!scene.world().ownsPlayer(player)) throw new IllegalArgumentException("Foreign item dropper");
        if (player.isSpectator() || !player.isAlive()) return;
        var inventory = player.getInventory();
        var dropped = inventory.removeFromSelected(wholeStack);
        if (dropped.isEmpty()) return;
        var position = new Pose(player.getX(), player.getEyeY() - (double) 0.3F, player.getZ(), 0, 0);
        var actor = at(scene.logical(), position, EntityType.ITEM, Item.class);
        var item = (net.minecraft.world.entity.item.ItemEntity) actor.entity();
        item.setItem(dropped);
        item.setThrower(player);
        item.setPickUpDelay(40);
        var velocity = RollbackItemThrow.motion(player.getXRot(), player.getYRot(),
                net.minecraft.util.Mth::sin, net.minecraft.util.Mth::cos, player.getRandom()::nextFloat);
        item.setDeltaMovement(velocity.x(), velocity.y(), velocity.z());
        scene.logical().entities().add(actor.view());
        var event = new org.bukkit.event.player.PlayerDropItemEvent(player.getBukkitEntity(),
                (org.bukkit.entity.Item) item.getBukkitEntity());
        scene.world().event(event);
        if (event.isCancelled()) {
            var returned = item.getItem();
            if (inventory.getSelectedItem().isEmpty()) inventory.setItem(inventory.getSelectedSlot(), returned);
            else if (!inventory.add(inventory.getSelectedSlot(), returned))
                throw new IllegalStateException("Cancelled drop no longer fits the private inventory");
            actor.view().remove();
            return;
        }
        if (scene.services().settings().interactions().consumeDrop(
                org.bukkit.craftbukkit.util.CraftMagicNumbers.getMaterial(dropped.getItem()).name())) actor.view().remove();
        scene.world().statistic(player, net.minecraft.stats.Stats.ITEM_DROPPED.get(dropped.getItem()), dropped.getCount(), false);
        scene.world().statistic(player, net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.DROP), 1, false);
    }
    @Override public FallingBlock fallingBlock(RollbackWorld world, Pose pos, BlockData data) {
        var actor = at(world, pos, EntityType.FALLING_BLOCK, FallingBlock.class);
        var falling = (net.minecraft.world.entity.item.FallingBlockEntity) actor.entity();
        falling.blockState = new PaperRollbackBlockStates().decode(data);
        return (FallingBlock) actor.view();
    }
    @Override public Arrow arrow(RollbackWorld world, Pose pos, Motion direction, float speed, float spread) {
        var actor = at(world, pos, EntityType.ARROW, Arrow.class);
        ((Projectile) actor.entity()).shoot(direction.x(), direction.y(), direction.z(), speed, spread);
        return (Arrow) actor.view();
    }
    @Override public <T extends com.projectkorra.projectkorra.platform.mc.entity.Projectile> T launch(RollbackPlayerState player, Class<T> type) {
        var shooter = (net.minecraft.world.entity.player.Player) nativeEntity(player.view());
        var position = Pose.from(player.view().getEyeLocation());
        var actor = at(scene.logical(), position, kind(type), type);
        var shot = (Projectile) actor.entity();
        shot.setOwner(shooter);
        shot.shootFromRotation(shooter, shooter.getXRot(), shooter.getYRot(), 0, shot instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow ? 3F : 1.5F, 1);
        scene.logical().entities().add(actor.view());
        return type.cast(actor.view());
    }
    @Override public boolean explosion(RollbackWorld world, Pose pos, float power, boolean fire, boolean breakBlocks) {
        requireWorld(world);
        var blast = new ServerExplosion(server(), null, null, null, new Vec3(pos.x(), pos.y(), pos.z()), power, fire,
                breakBlocks ? Explosion.BlockInteraction.DESTROY : Explosion.BlockInteraction.KEEP);
        blast.explode(); return !blast.wasCanceled;
    }
    @Override public Pose teleport(RollbackEntityBody entity, Pose pos) {
        var bounds = scene.logical().terrain().bounds();
        var loc = pos.at(scene.logical());
        return bounds.contains(new RollbackBlockStore.Position(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) ? pos : null;
    }
    @Override public boolean canMount(RollbackEntityBody vehicle, RollbackEntityBody passenger) {
        return scene.world().mount(nativeEntity(passenger.view()), nativeEntity(vehicle.view()));
    }
    @Override public void unmount(RollbackEntityBody vehicle, RollbackEntityBody passenger) {
        var rider = nativeEntity(passenger.view());
        if (rider.getVehicle() != nativeEntity(vehicle.view())) throw new IllegalStateException("Private mount edge changed");
        scene.world().unmount(rider);
    }
    public void importState(RollbackActorData data) {
        registry.importState(scene.logical(), data, row -> {
            var actor = make(row.identity(), FACADES.getOrDefault(row.identity().type(), LivingEntity.class));
            actor.importState(row.state()); return actor;
        });
    }
    @Override public Void captureRollbackState() { return null; }
    @Override public void restoreRollbackState(Void state) { }
    @Override public List<?> rollbackReferences() { return List.of(registry); }
}
