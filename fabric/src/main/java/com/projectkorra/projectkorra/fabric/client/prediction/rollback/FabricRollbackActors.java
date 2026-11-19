package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.platform.fabric.FabricMC;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.*;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.prediction.rollback.*;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.*;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.explosion.Explosion;
import net.minecraft.world.explosion.ExplosionImpl;

import java.lang.reflect.Method;
import java.util.*;

/** Native actors created by the existing bending APIs in one owned Fabric scene. */
public final class FabricRollbackActors implements RollbackArenaActions.Native, RollbackStateCell<Void> {
    private static final Map<EntityType, Class<? extends Entity>> FACADES = Map.ofEntries(
            Map.entry(EntityType.BLOCK_DISPLAY, BlockDisplay.class), Map.entry(EntityType.ITEM_DISPLAY, ItemDisplay.class),
            Map.entry(EntityType.ARMOR_STAND, ArmorStand.class), Map.entry(EntityType.AREA_EFFECT_CLOUD, AreaEffectCloud.class),
            Map.entry(EntityType.ARROW, Arrow.class), Map.entry(EntityType.SMALL_FIREBALL, Fireball.class),
            Map.entry(EntityType.FALLING_BLOCK, FallingBlock.class), Map.entry(EntityType.ITEM, Item.class),
            Map.entry(EntityType.SHULKER_BULLET, ShulkerBullet.class), Map.entry(EntityType.SNOWBALL, Snowball.class),
            Map.entry(EntityType.TRIDENT, Trident.class), Map.entry(EntityType.SLIME, Slime.class),
            Map.entry(EntityType.TNT, TNTPrimed.class));
    private final RollbackActorRegistry registry;
    private final RollbackNativeItems<net.minecraft.item.ItemStack> items;
    private FabricRollbackNativeScene scene;
    public FabricRollbackActors(UUID session, int maximum, RollbackNativeItems<net.minecraft.item.ItemStack> items) {
        registry = new RollbackActorRegistry(session, maximum); this.items = Objects.requireNonNull(items);
    }
    void bind(FabricRollbackNativeScene scene) {
        if (this.scene != null) throw new IllegalStateException("Native actor owner already bound");
        this.scene = Objects.requireNonNull(scene);
        scene.roster().world().bindActors(this);
    }
    private ServerWorld server() { return (ServerWorld) scene.roster().world().world(); }
    private void requireWorld(RollbackWorld target) { if (target != scene.logical()) throw new IllegalArgumentException("Foreign private actor world"); }
    public RollbackActorRegistry registry() { return registry; }
    List<net.minecraft.entity.Entity> active() { return registry.active().stream().map(a -> ((FabricRollbackActor) a).owned()).toList(); }
    net.minecraft.entity.Entity actor(UUID id) {
        var found = (FabricRollbackActor) registry.get(id);
        return found == null ? null : found.owned();
    }
    Entity logical(net.minecraft.entity.Entity e) {
        if (e == null) return null;
        var view = scene.logical().entities().get(e.getUuid());
        if (view == null || nativeEntity(view) != e) throw new IllegalArgumentException("Unowned actor reference");
        return view;
    }
    net.minecraft.entity.Entity nativeEntity(Entity view) {
        if (view == null) return null;
        var body = RollbackEntityBody.logicalBody(view);
        if (body.world() != scene.logical()) throw new IllegalArgumentException("Foreign native actor world");
        return switch (body.kinematicsSource()) {
            case FabricRollbackNativePlayerState player -> player.ownedPlayer();
            case FabricRollbackActor actor -> actor.owned();
            default -> throw new IllegalArgumentException("Unowned native actor");
        };
    }
    private static EntityType kind(Class<?> type) {
        return FACADES.entrySet().stream().filter(e -> e.getValue() == type).findFirst().orElseThrow(
                () -> new IllegalArgumentException("No concrete actor type for " + type)).getKey();
    }
    private <T extends Entity> FabricRollbackActor make(Identity id, Class<T> type) {
        var key = Identifier.ofVanilla(id.type().name().toLowerCase(Locale.ROOT));
        var nativeType = Registries.ENTITY_TYPE.getEntry(key).orElseThrow().value();
        var e = FabricRollbackActorAllocation.create(server(), nativeType, id);
        if (e instanceof FabricRollbackArrow arrow) arrow.rollback$arrowLifetime(scene.services().settings().arrowLifetime());
        if (e instanceof FabricRollbackItemPolicy item) item.rollback$itemRules(scene.services().settings().interactions().items());
        var delegate = FabricMC.entity(e);
        int lifespan = scene.services().settings().actorDespawnTime().getOrDefault(Registries.ENTITY_TYPE.getId(e.getType()).toString(), -1);
        return new FabricRollbackActor(e, id, scene.logical(), this, lifespan, type, (method, args) -> special(e, delegate, method, args));
    }
    private Object special(net.minecraft.entity.Entity e, Entity delegate, Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
            case "getEyeHeight": return (double) e.getEyeHeight(e.getPose());
            case "getEyeLocation": {
                var pos = e.getEyePos(); return new Pose(pos.x, pos.y, pos.z, e.getYaw(), e.getPitch()).at(scene.logical());
            }
            case "setShooter": ((ProjectileEntity) e).setOwner(nativeEntity((Entity) args[0])); return null;
            case "getShooter": return logical(((ProjectileEntity) e).getOwner());
            case "setSize": ((net.minecraft.entity.mob.SlimeEntity) e).setSize((int) args[0], true); return null;
            case "setItemStack": {
                var stack = items.nativeCopy((ItemStack) args[0]);
                if (e instanceof net.minecraft.entity.ItemEntity item) item.setStack(stack);
                else ((net.minecraft.entity.decoration.DisplayEntity.ItemDisplayEntity) e).setItemStack(stack);
                return null;
            }
            case "getItemStack": {
                var stack = e instanceof net.minecraft.entity.ItemEntity item ? item.getStack()
                        : ((net.minecraft.entity.decoration.DisplayEntity.ItemDisplayEntity) e).getItemStack();
                return items.capture(stack);
            }
            case "setHelmet": ((net.minecraft.entity.LivingEntity) e).equipStack(net.minecraft.entity.EquipmentSlot.HEAD, items.nativeCopy((ItemStack) args[0])); return null;
            case "setDamage": ((net.minecraft.entity.projectile.PersistentProjectileEntity) e).setDamage((int) args[0]); return null;
            case "setCritical": ((net.minecraft.entity.projectile.PersistentProjectileEntity)e).setCritical((boolean)args[0]); return null;
            case "setPickupStatus": ((net.minecraft.entity.projectile.PersistentProjectileEntity)e).pickupType =
                    net.minecraft.entity.projectile.PersistentProjectileEntity.PickupPermission.valueOf(((Arrow.PickupStatus)args[0]).name()); return null;
            case "getPickupStatus": return Arrow.PickupStatus.valueOf(((net.minecraft.entity.projectile.PersistentProjectileEntity)e).pickupType.name());
            case "isInBlock": return FabricMC.arrowInGround((net.minecraft.entity.projectile.PersistentProjectileEntity)e);
            case "isInWaterOrBubbleColumn": return FabricMC.inWaterOrBubble(e);
            case "getAttachedBlock": {
                var arrow = (net.minecraft.entity.projectile.PersistentProjectileEntity) e;
                var pos = e.getBlockPos();
                return FabricMC.arrowInGround(arrow) ? scene.logical().getBlockAt(pos.getX(), pos.getY(), pos.getZ()) : null;
            }
            case "setRadius": ((net.minecraft.entity.AreaEffectCloudEntity)e).setRadius((float)args[0]); return null;
            case "setParticle": ((net.minecraft.entity.AreaEffectCloudEntity)e).setParticleType(FabricMC.particle((com.projectkorra.projectkorra.platform.mc.Particle)args[0], null)); return null;
            case "setColor": FabricMC.cloudColor((net.minecraft.entity.AreaEffectCloudEntity)e, (com.projectkorra.projectkorra.platform.mc.Color)args[0]); return null;
            case "addCustomEffect": FabricMC.cloudEffect((net.minecraft.entity.AreaEffectCloudEntity)e, (com.projectkorra.projectkorra.platform.mc.potion.PotionEffect)args[0], (boolean)args[1]); return null;
            case "setReapplicationDelay": ((com.projectkorra.projectkorra.fabric.mixin.client.AreaEffectCloudEntityAccessor)e).projectkorra$setReapplicationDelay((int)args[0]); return null;
            case "setDuration": ((net.minecraft.entity.AreaEffectCloudEntity)e).setDuration((int)args[0]); return null;
            case "setWaitTime": ((net.minecraft.entity.AreaEffectCloudEntity)e).setWaitTime((int)args[0]); return null;
            case "setRadiusOnUse": ((net.minecraft.entity.AreaEffectCloudEntity)e).setRadiusOnUse((float)args[0]); return null;
            case "setRadiusPerTick": ((net.minecraft.entity.AreaEffectCloudEntity)e).setRadiusGrowth((float)args[0]); return null;
            case "setSource": ((net.minecraft.entity.AreaEffectCloudEntity)e).setOwner((net.minecraft.entity.LivingEntity) nativeEntity((Entity)args[0])); return null;
            default: return method.invoke(delegate, args);
        }
    }
    private FabricRollbackActor at(RollbackWorld world, Pose pos, EntityType kind, Class<? extends Entity> type) {
        requireWorld(world); var id = registry.allocate(world, kind.name(), kind);
        var actor = make(id, type);
        actor.owned().refreshPositionAndAngles(pos.x(), pos.y(), pos.z(), pos.yaw(), pos.pitch());
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
        var drop = (net.minecraft.entity.ItemEntity) actor.owned();
        drop.setStack(items.nativeCopy(item)); drop.setToDefaultPickupDelay();
        var random = drop.getRandom();
        drop.setVelocity(random.nextDouble() * .2 - .1, .2, random.nextDouble() * .2 - .1);
        return (Item) actor.view();
    }
    void dropSelected(net.minecraft.entity.player.PlayerEntity player, boolean all) {
        if (!scene.roster().world().ownsPlayer(player)) throw new IllegalArgumentException("Foreign item dropper");
        if (player.isSpectator() || !player.isAlive()) return;
        var stack = player.getInventory().dropSelectedItem(all);
        if (stack.isEmpty()) return;
        var position = new Pose(player.getX(), player.getEyeY() - (double) .3F, player.getZ(), 0, 0);
        var actor = at(scene.logical(), position, EntityType.ITEM, Item.class);
        var drop = (net.minecraft.entity.ItemEntity) actor.owned();
        drop.setStack(stack);
        drop.setThrower(player);
        drop.setPickupDelay(40);
        var velocity = RollbackItemThrow.motion(player.getPitch(), player.getYaw(),
                net.minecraft.util.math.MathHelper::sin, net.minecraft.util.math.MathHelper::cos, player.getRandom()::nextFloat);
        drop.setVelocity(velocity.x(), velocity.y(), velocity.z());
        scene.logical().entities().add(actor.view());
        if (scene.services().settings().interactions().consumeDrop(items.capture(stack).getType().name())) actor.view().remove();
        player.increaseStat(net.minecraft.stat.Stats.DROPPED.getOrCreateStat(stack.getItem()), stack.getCount());
        player.incrementStat(net.minecraft.stat.Stats.DROP);
    }

    @Override public FallingBlock fallingBlock(RollbackWorld world, Pose pos, BlockData data) {
        var actor = at(world, pos, EntityType.FALLING_BLOCK, FallingBlock.class);
        var falling = (net.minecraft.entity.FallingBlockEntity) actor.owned();
        FabricMC.setFallingBlockState(falling, FabricRollbackGeometry.decode(data));
        return (FallingBlock) actor.view();
    }
    @Override public Arrow arrow(RollbackWorld world, Pose pos, Motion direction, float speed, float spread) {
        var actor = at(world, pos, EntityType.ARROW, Arrow.class);
        ((ProjectileEntity) actor.owned()).setVelocity(direction.x(), direction.y(), direction.z(), speed, spread);
        return (Arrow) actor.view();
    }
    @Override public <T extends Projectile> T launch(RollbackPlayerState player, Class<T> type) {
        var shooter = (net.minecraft.entity.player.PlayerEntity) nativeEntity(player.view());
        var position = Pose.from(player.view().getEyeLocation());
        var actor = at(scene.logical(), position, kind(type), type);
        var shot = (ProjectileEntity) actor.owned();
        shot.setOwner(shooter);
        shot.setVelocity(shooter, shooter.getPitch(), shooter.getYaw(), 0, shot instanceof net.minecraft.entity.projectile.PersistentProjectileEntity ? 3F : 1.5F, 1);
        scene.logical().entities().add(actor.view());
        return type.cast(actor.view());
    }
    @Override public boolean explosion(RollbackWorld world, Pose pos, float power, boolean fire, boolean breakBlocks) {
        requireWorld(world);
        var blast = new ExplosionImpl(server(), null, null, null, new Vec3d(pos.x(), pos.y(), pos.z()), power, fire,
                breakBlocks ? Explosion.DestructionType.DESTROY : Explosion.DestructionType.KEEP);
        return FabricRollbackGliding.use(scene.roster().world(), () -> { blast.explode(); return true; });
    }
    @Override public Pose teleport(RollbackEntityBody entity, Pose pos) {
        var bounds = scene.logical().terrain().bounds();
        var loc = pos.at(scene.logical());
        return bounds.contains(new RollbackBlockStore.Position(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) ? pos : null;
    }
    @Override public boolean canMount(RollbackEntityBody vehicle, RollbackEntityBody passenger) {
        return nativeEntity(passenger.view()).startRiding(nativeEntity(vehicle.view()), true, true);
    }
    @Override public void unmount(RollbackEntityBody vehicle, RollbackEntityBody passenger) {
        var rider = nativeEntity(passenger.view());
        if (rider.getVehicle() == nativeEntity(vehicle.view())) rider.stopRiding();
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
