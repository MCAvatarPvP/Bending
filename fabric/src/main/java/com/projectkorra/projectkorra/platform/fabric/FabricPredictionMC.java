package com.projectkorra.projectkorra.platform.fabric;

import com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime;
import com.projectkorra.projectkorra.fabric.client.PredictionClient;
import com.projectkorra.projectkorra.fabric.mixin.client.AreaEffectCloudEntityAccessor;
import com.projectkorra.projectkorra.platform.mc.Color;
import com.projectkorra.projectkorra.platform.mc.Difficulty;
import com.projectkorra.projectkorra.platform.mc.Effect;
import com.projectkorra.projectkorra.platform.mc.FluidCollisionMode;
import com.projectkorra.projectkorra.platform.mc.GameMode;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.SoundCategory;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Biome;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.BlockState;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.AreaEffectCloud;
import com.projectkorra.projectkorra.platform.mc.entity.ArmorStand;
import com.projectkorra.projectkorra.platform.mc.entity.Arrow;
import com.projectkorra.projectkorra.platform.mc.entity.BlockDisplay;
import com.projectkorra.projectkorra.platform.mc.entity.Display;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.EntityType;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.entity.Fireball;
import com.projectkorra.projectkorra.platform.mc.entity.Item;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.entity.Projectile;
import com.projectkorra.projectkorra.platform.mc.entity.ShulkerBullet;
import com.projectkorra.projectkorra.platform.mc.entity.Slime;
import com.projectkorra.projectkorra.platform.mc.entity.Snowball;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.inventory.PlayerInventory;
import com.projectkorra.projectkorra.platform.mc.metadata.MetadataValue;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.RayTraceResult;
import com.projectkorra.projectkorra.platform.mc.util.Transformation;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.PredictedContactSync;
import com.projectkorra.projectkorra.prediction.TempBlockSync;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.platform.model.PKAdapter;
import com.projectkorra.projectkorra.platform.model.PKBlock;
import com.projectkorra.projectkorra.platform.model.PKEntity;
import com.projectkorra.projectkorra.platform.model.PKLivingEntity;
import com.projectkorra.projectkorra.platform.model.PKLocation;
import com.projectkorra.projectkorra.platform.model.PKPlayer;
import com.projectkorra.projectkorra.platform.model.PKWorld;
import com.projectkorra.projectkorra.platform.model.PKVec3;
import java.lang.reflect.Field;
import java.util.HashSet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.mob.SlimeEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.entity.projectile.ShulkerBulletEntity;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.particle.TintedParticleEffect;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Client-backed common-model wrappers used by the exact prediction sandbox. */
public final class FabricPredictionMC {
    private static final Map<UUID, Map<String, List<MetadataValue>>> METADATA = new HashMap<>();
    private static final Map<UUID, ClientPlayerView> PLAYERS = new HashMap<>();
    private static ClientWorldView worldView;
    private static ClientWorld worldIdentity;

    private FabricPredictionMC() { }

    public static ClientWorldView world(ClientWorld world) {
        if (world == null) return null;
        if (world != worldIdentity) {
            worldIdentity = world;
            worldView = new ClientWorldView(world);
            PLAYERS.clear();
        }
        return worldView;
    }

    public static ClientPlayerView player(PlayerEntity player) {
        if (player == null) return null;
        ClientPlayerView view = PLAYERS.computeIfAbsent(player.getUuid(), ignored -> new ClientPlayerView(player));
        // Vanilla replaces player entity objects on respawn and can also do so
        // while rebuilding the client entity tracker. Keep the stable common
        // wrapper, but always point it at the currently tracked native entity.
        // Otherwise collision queries find the new player and then receive a
        // cached, removed handle until the client reconnects.
        view.rebind(player);
        return view;
    }

    public static Entity entity(net.minecraft.entity.Entity entity) {
        if (entity == null) return null;
        if (entity instanceof PlayerEntity player) return player(player);
        if (entity instanceof FallingBlockEntity falling) return new ClientFallingBlock(falling);
        if (entity instanceof ItemEntity item) return new ClientItem(item);
        if (entity instanceof ArmorStandEntity stand) return new ClientArmorStand(stand);
        if (entity instanceof DisplayEntity.BlockDisplayEntity display) return new ClientBlockDisplay(display);
        if (entity instanceof ShulkerBulletEntity bullet) return new ClientShulkerBullet(bullet);
        if (entity instanceof SmallFireballEntity fireball) return new ClientFireball(fireball);
        if (entity instanceof SnowballEntity snowball) return new ClientSnowball(snowball);
        if (entity instanceof AreaEffectCloudEntity cloud) return new ClientAreaEffectCloud(cloud);
        if (entity instanceof ArrowEntity arrow) return new ClientArrow(arrow);
        if (entity instanceof SlimeEntity slime) return new ClientSlime(slime);
        if (entity instanceof net.minecraft.entity.LivingEntity living) return new ClientLivingView(living);
        return new ClientEntityView(entity);
    }

    public static Location location(ClientWorld world, Vec3d pos, float yaw, float pitch) {
        Location location = new Location(world(world), pos.x, pos.y, pos.z);
        location.setYaw(yaw);
        location.setPitch(pitch);
        return location;
    }

    public static ClientBlockView block(ClientWorld world, BlockPos pos) {
        return new ClientBlockView(world, pos.toImmutable());
    }

    public static void clear() {
        PLAYERS.clear();
        METADATA.clear();
        worldIdentity = null;
        worldView = null;
    }

    public static final class Adapter implements PKAdapter {
        @Override public PKPlayer player(Object value) { return value instanceof PlayerEntity nativePlayer ? new PlayerAdapter(FabricPredictionMC.player(nativePlayer)) : null; }
        @Override public PKEntity entity(Object value) { return value instanceof net.minecraft.entity.Entity nativeEntity ? new EntityAdapter(FabricPredictionMC.entity(nativeEntity)) : null; }
        @Override public PKLivingEntity livingEntity(Object value) { return value instanceof net.minecraft.entity.LivingEntity nativeLiving ? new LivingAdapter((LivingEntity) FabricPredictionMC.entity(nativeLiving)) : null; }
        @Override public PKWorld world(Object value) { return value instanceof ClientWorld nativeWorld ? new WorldAdapter(FabricPredictionMC.world(nativeWorld)) : null; }
        @Override public PKBlock block(Object value) { return value instanceof ClientBlockRef ref ? new BlockAdapter(FabricPredictionMC.block(ref.world, ref.pos)) : null; }
        @Override public PKLocation location(Object value) { return value instanceof Location common ? new LocationAdapter(common) : null; }
        @Override public Object unwrap(Object value) { return value instanceof ClientBacked backed ? backed.nativeHandle() : value; }
    }

    private interface ClientBacked { Object nativeHandle(); }
    public record ClientBlockRef(ClientWorld world, BlockPos pos) { }

    public static final class ClientWorldView extends World implements ClientBacked {
        private final ClientWorld value;
        private ClientWorldView(ClientWorld value) { this.value = value; }
        @Override public String getName() { return value.getRegistryKey().getValue().toString(); }
        @Override public long getTime() { return value.getTimeOfDay(); }
        @Override public long getFullTime() { return value.getTime(); }
        @Override public int getMinHeight() { return value.getBottomY(); }
        @Override public int getMaxHeight() { return value.getTopYInclusive() + 1; }
        @Override public Object handle() { return value; }
        @Override public Object nativeHandle() { return value; }
        @Override public Block getBlockAt(int x, int y, int z) { return block(value, new BlockPos(x, y, z)); }
        @Override public boolean isChunkLoaded(int x, int z) { return value.isChunkLoaded(x, z); }
        @Override public Collection<Player> getPlayers() {
            List<Player> result = new ArrayList<>();
            value.getPlayers().forEach(player -> result.add(FabricPredictionMC.player(player)));
            return result;
        }
        @Override public List<Entity> getEntities() {
            List<Entity> result = new ArrayList<>();
            for (net.minecraft.entity.Entity nativeEntity : value.getEntities()) result.add(FabricPredictionMC.entity(nativeEntity));
            return result;
        }

        @Override
        public Collection<Entity> getNearbyEntities(BoundingBox box, Predicate<Entity> filter) {
            Box nativeBox = new Box(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ());
            List<Entity> result = new ArrayList<>();
            for (net.minecraft.entity.Entity nativeEntity : value.getOtherEntities(null, nativeBox, ignored -> true)) {
                Entity wrapped = FabricPredictionMC.entity(nativeEntity);
                if (wrapped != null && (filter == null || filter.test(wrapped))) result.add(wrapped);
            }
            ClientPlayerEntity local = MinecraftClient.getInstance().player;
            if (local != null && nativeBox.intersects(local.getBoundingBox())) {
                Entity wrapped = player(local);
                if ((filter == null || filter.test(wrapped)) && result.stream().noneMatch(entity -> entity.getUniqueId().equals(wrapped.getUniqueId()))) result.add(wrapped);
            }
            return result;
        }

        @Override
        public <T> void spawnParticle(Particle particle, Location location, int count, double ox, double oy, double oz, double extra, T data) {
            ParticleEffect effect = FabricMC.particle(particle, data);
            if (effect == null) return;
            int samples = count == 0 ? 1 : Math.max(1, count);
            for (int i = 0; i < samples; i++) {
                double x = location.getX() + (count == 0 ? 0 : value.random.nextGaussian() * ox);
                double y = location.getY() + (count == 0 ? 0 : value.random.nextGaussian() * oy);
                double z = location.getZ() + (count == 0 ? 0 : value.random.nextGaussian() * oz);
                double vx = count == 0 ? ox * extra : value.random.nextGaussian() * extra;
                double vy = count == 0 ? oy * extra : value.random.nextGaussian() * extra;
                double vz = count == 0 ? oz * extra : value.random.nextGaussian() * extra;
                value.addParticleClient(effect, x, y, z, vx, vy, vz);
            }
        }
        @Override public void spawnParticle(Particle particle, Location location, int count, double ox, double oy, double oz, double extra) { spawnParticle(particle, location, count, ox, oy, oz, extra, null); }
        @Override public void spawnParticle(Particle particle, Location location, int count, double ox, double oy, double oz) { spawnParticle(particle, location, count, ox, oy, oz, 0, null); }

        @Override public void playSound(Location location, Sound sound, float volume, float pitch) { playSound(location, sound, SoundCategory.MASTER, volume, pitch); }
        @Override public void playSound(Location location, Sound sound, SoundCategory category, float volume, float pitch) { playSound(location, sound == null ? null : sound.name(), volume, pitch, category); }
        @Override public void playSound(Location location, String sound, float volume, float pitch) { playSound(location, sound, volume, pitch, SoundCategory.MASTER); }
        private void playSound(Location location, String sound, float volume, float pitch, SoundCategory category) {
            if (sound == null || sound.isBlank()) return;
            Identifier id = FabricMC.soundIdentifier(sound);
            if (id == null) return;
            value.playSoundClient(location.getX(), location.getY(), location.getZ(), SoundEvent.of(id),
                    net.minecraft.sound.SoundCategory.valueOf(category.name()), volume, pitch, false);
        }
        @Override public void playEffect(Location location, Effect effect, int data) { playEffect(location, effect, Integer.valueOf(data)); }
        @Override public void playEffect(Location location, Effect effect, Object data) {
            if (effect == null) return;
            switch (effect.name()) {
                case "MOBSPAWNER_FLAMES" -> spawnParticle(Particle.FLAME, location, 18, .45, .45, .45, .01);
                case "SMOKE", "EXTINGUISH" -> spawnParticle(Particle.SMOKE, location, 10, .25, .25, .25, .02);
                case "STEP_SOUND" -> spawnParticle(Particle.BLOCK, location, 18, .35, .25, .35, .05,
                        data instanceof BlockData blockData ? blockData : location.getBlock().getBlockData());
                default -> { }
            }
        }
        @Override public void playEffect(Location location, Effect effect, int data, int radius) { playEffect(location, effect, data); }

        @Override
        public RayTraceResult rayTraceBlocks(Location origin, Vector direction, double range, FluidCollisionMode mode, boolean ignorePassable) {
            Vec3d start = nativeVector(origin.toVector());
            Vec3d end = start.add(nativeVector(direction).normalize().multiply(range));
            BlockHitResult hit = value.raycast(new RaycastContext(start, end,
                    ignorePassable ? RaycastContext.ShapeType.COLLIDER : RaycastContext.ShapeType.OUTLINE,
                    mode == FluidCollisionMode.NEVER ? RaycastContext.FluidHandling.NONE : RaycastContext.FluidHandling.ANY,
                    MinecraftClient.getInstance().player));
            return hit.getType() == HitResult.Type.MISS ? null : new RayTraceResult(commonVector(hit.getPos()));
        }

        @Override
        public RayTraceResult rayTrace(Location origin, Vector direction, double range, FluidCollisionMode mode,
                                       boolean ignorePassable, double raySize, Predicate<Entity> filter) {
            Vec3d start = nativeVector(origin.toVector());
            Vec3d end = start.add(nativeVector(direction).normalize().multiply(range));
            net.minecraft.entity.Entity except = origin.getWorld() == this && MinecraftClient.getInstance().player != null ? MinecraftClient.getInstance().player : null;
            Box search = new Box(start, end).expand(raySize + 1.0);
            EntityHitResult entityHit = ProjectileUtil.raycast(except, start, end, search,
                    nativeEntity -> {
                        Entity wrapped = FabricPredictionMC.entity(nativeEntity);
                        return wrapped != null && (filter == null || filter.test(wrapped));
                    }, range * range);
            RayTraceResult blockHit = rayTraceBlocks(origin, direction, range, mode, ignorePassable);
            if (entityHit == null) return blockHit;
            if (blockHit != null && blockHit.getHitPosition().distanceSquared(commonVector(start)) < entityHit.getPos().squaredDistanceTo(start)) return blockHit;
            return new RayTraceResult(commonVector(entityHit.getPos()), FabricPredictionMC.entity(entityHit.getEntity()));
        }

        @Override public boolean createExplosion(double x, double y, double z, float power, boolean fire, boolean breakBlocks) {
            spawnParticle(Particle.EXPLOSION, new Location(this, x, y, z), Math.max(1, (int) power), .2, .2, .2, .05);
            return true;
        }
        @Override public FallingBlock spawnFallingBlock(Location location, BlockData data) {
            FallingBlockEntity entity = new FallingBlockEntity(net.minecraft.entity.EntityType.FALLING_BLOCK, value);
            entity.refreshPositionAndAngles(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
            FabricMC.setFallingBlockState(entity, FabricMC.blockState(data));
            entity.dropItem = false;
            value.addEntity(entity);
            ExactPredictionRuntime.trackSpawn(entity);
            return new ClientFallingBlock(entity);
        }
        @Override public Item dropItem(Location location, ItemStack item) {
            ItemEntity entity = new ItemEntity(value, location.getX(), location.getY(), location.getZ(), FabricMC.itemStack(item));
            value.addEntity(entity);
            ExactPredictionRuntime.trackSpawn(entity);
            return new ClientItem(entity);
        }
        @Override public Item dropItemNaturally(Location location, ItemStack item) {
            return dropItem(location, item);
        }
        @Override public <T> T spawn(Location location, Class<T> type) {
            if (type == ArmorStand.class) {
                ArmorStandEntity entity = new ArmorStandEntity(
                        value, location.getX(), location.getY(), location.getZ());
                value.addEntity(entity);
                ExactPredictionRuntime.trackSpawn(entity);
                return type.cast(new ClientArmorStand(entity));
            }
            if (type == BlockDisplay.class) {
                DisplayEntity.BlockDisplayEntity entity = new DisplayEntity.BlockDisplayEntity(
                        net.minecraft.entity.EntityType.BLOCK_DISPLAY, value);
                entity.refreshPositionAndAngles(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
                value.addEntity(entity);
                ExactPredictionRuntime.trackSpawn(entity);
                return type.cast(new ClientBlockDisplay(entity));
            }
            if (type == ShulkerBullet.class) {
                ShulkerBulletEntity entity = new ShulkerBulletEntity(
                        net.minecraft.entity.EntityType.SHULKER_BULLET, value);
                entity.refreshPositionAndAngles(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
                value.addEntity(entity); ExactPredictionRuntime.trackSpawn(entity);
                return type.cast(new ClientShulkerBullet(entity));
            }
            if (type == Fireball.class) {
                SmallFireballEntity entity = new SmallFireballEntity(
                        net.minecraft.entity.EntityType.SMALL_FIREBALL, value);
                entity.refreshPositionAndAngles(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
                value.addEntity(entity); ExactPredictionRuntime.trackSpawn(entity);
                return type.cast(new ClientFireball(entity));
            }
            if (type == Snowball.class) {
                SnowballEntity entity = new SnowballEntity(
                        value, location.getX(), location.getY(), location.getZ(), new net.minecraft.item.ItemStack(Items.SNOWBALL));
                entity.refreshPositionAndAngles(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
                value.addEntity(entity); ExactPredictionRuntime.trackSpawn(entity);
                return type.cast(new ClientSnowball(entity));
            }
            if (type == AreaEffectCloud.class) {
                AreaEffectCloudEntity entity = new AreaEffectCloudEntity(
                        value, location.getX(), location.getY(), location.getZ());
                value.addEntity(entity); ExactPredictionRuntime.trackSpawn(entity);
                return type.cast(new ClientAreaEffectCloud(entity));
            }
            return super.spawn(location, type);
        }

        @Override
        public Entity spawnEntity(Location location, EntityType type) {
            if (type != EntityType.SLIME) return super.spawnEntity(location, type);
            SlimeEntity entity = new SlimeEntity(net.minecraft.entity.EntityType.SLIME, value);
            entity.refreshPositionAndAngles(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
            value.addEntity(entity); ExactPredictionRuntime.trackSpawn(entity);
            return new ClientSlime(entity);
        }

        @Override
        public Arrow spawnArrow(Location location, Vector direction, float speed, float spread) {
            ArrowEntity entity = new ArrowEntity(
                    value, location.getX(), location.getY(), location.getZ(),
                    new net.minecraft.item.ItemStack(Items.ARROW), null);
            Vector normalized = direction.clone().normalize();
            entity.setVelocity(normalized.getX(), normalized.getY(), normalized.getZ(), speed, spread);
            value.addEntity(entity); ExactPredictionRuntime.trackSpawn(entity);
            return new ClientArrow(entity);
        }
        @Override public void strikeLightningEffect(Location location, boolean flash) { spawnParticle(Particle.ELECTRIC_SPARK, location, 24, .4, 1, .4, .05); }
        @Override public boolean hasStorm() { return value.getLevelProperties().isRaining(); }
        @Override public Difficulty getDifficulty() { return Difficulty.valueOf(value.getDifficulty().name()); }
    }

    public static final class ClientBlockView extends Block implements ClientBacked {
        private final ClientWorld world;
        private final BlockPos pos;
        private ClientBlockView(ClientWorld world, BlockPos pos) { this.world = world; this.pos = pos; }
        private net.minecraft.block.BlockState nativeState() { return ExactPredictionRuntime.blockState(world, pos); }
        @Override public Material getType() { return FabricMC.material(nativeState()); }
        @Override public void setType(Material material) { setType(material, false); }
        @Override public void setType(Material material, boolean physics) { setBlockData(material.createBlockData(), physics); }
        @Override public Location getLocation() { return location(world, Vec3d.of(pos), 0, 0); }
        @Override public World getWorld() { return FabricPredictionMC.world(world); }
        @Override public Block getRelative(BlockFace face) { return getRelative(face, 1); }
        @Override public Block getRelative(BlockFace face, int distance) { int[] delta = faceDelta(face); return block(world, pos.add(delta[0] * distance, delta[1] * distance, delta[2] * distance)); }
        @Override public Block getRelative(int x, int y, int z) { return block(world, pos.add(x, y, z)); }
        @Override public BlockData getBlockData() { return FabricMC.blockData(nativeState()); }
        @Override public void setBlockData(BlockData data) { setBlockData(data, false); }
        @Override public void setBlockData(BlockData data, boolean physics) {
            prepareExternalWrite();
            ExactPredictionRuntime.setPredictedBlock(world, pos, FabricMC.blockState(data));
        }
        private void prepareExternalWrite() {
            if (TempBlockSync.currentWorldMutation() == null && TempBlock.isTempBlock(this)) {
                TempBlock.removeBlock(this);
            }
        }
        @Override public BlockState getState() { return new ClientBlockState(this, nativeState()); }
        @Override public int getX() { return pos.getX(); }
        @Override public int getY() { return pos.getY(); }
        @Override public int getZ() { return pos.getZ(); }
        @Override public boolean isLiquid() { return !nativeState().getFluidState().isEmpty(); }
        @Override public boolean isSolid() { return nativeState().isSolidBlock(world, pos); }
        @Override public boolean isPassable() { return nativeState().getCollisionShape(world, pos).isEmpty(); }
        @Override public boolean isEmpty() { return nativeState().isAir(); }
        @Override public byte getLightLevel() { return (byte) world.getLightLevel(pos); }
        @Override public boolean breakNaturally() { setType(Material.AIR); return true; }
        @Override public BoundingBox getBoundingBox() {
            VoxelShape shape = nativeState().getCollisionShape(world, pos);
            if (shape.isEmpty()) return new BoundingBox(new Vector(pos.getX(), pos.getY(), pos.getZ()), new Vector(pos.getX(), pos.getY(), pos.getZ()));
            Box box = shape.getBoundingBox().offset(pos);
            return commonBox(box);
        }
        @Override public BlockFace getFace(Block other) {
            if (!(other instanceof ClientBlockView block)) return BlockFace.SELF;
            int dx = block.pos.getX() - pos.getX(), dy = block.pos.getY() - pos.getY(), dz = block.pos.getZ() - pos.getZ();
            for (BlockFace face : BlockFace.values()) { int[] delta = faceDelta(face); if (delta[0] == dx && delta[1] == dy && delta[2] == dz) return face; }
            return null;
        }
        @Override public Biome getBiome() {
            String name = world.getBiome(pos).getKey().map(key -> key.getValue().getPath().toUpperCase(Locale.ROOT)).orElse("DESERT");
            try { return Biome.valueOf(name); } catch (IllegalArgumentException ignored) { return Biome.DESERT; }
        }
        @Override public Object handle() { return new ClientBlockRef(world, pos); }
        @Override public Object nativeHandle() { return handle(); }
        @Override public boolean equals(Object value) { return value instanceof ClientBlockView block && world == block.world && pos.equals(block.pos); }
        @Override public int hashCode() { return 31 * System.identityHashCode(world) + pos.hashCode(); }
    }

    private static final class ClientBlockState extends BlockState {
        private final ClientBlockView block;
        private final net.minecraft.block.BlockState state;
        private ClientBlockState(ClientBlockView block, net.minecraft.block.BlockState state) {
            this.block = block;
            this.state = state;
        }
        @Override public Material getType() { return FabricMC.material(state); }
        @Override public BlockData getBlockData() { return FabricMC.blockData(state); }
        @Override public Block getBlock() { return block; }
        @Override public Location getLocation() { return block.getLocation(); }
        @Override public boolean update(boolean force, boolean physics) {
            block.prepareExternalWrite();
            ExactPredictionRuntime.setPredictedBlock(block.world, block.pos, state);
            return true;
        }
        @Override public Object handle() { return block.handle(); }
        @Override public boolean hasBlockEntity() { return block.world.getBlockEntity(block.pos) != null; }
    }

    private static class ClientEntityView extends Entity implements ClientBacked {
        protected final net.minecraft.entity.Entity value;
        private ClientEntityView(net.minecraft.entity.Entity value) { this.value = value; }
        @Override public UUID getUniqueId() { return value.getUuid(); }
        @Override public Location getLocation() { return location((ClientWorld) value.getEntityWorld(), value.getEntityPos(), value.getYaw(), value.getPitch()); }
        @Override public World getWorld() { return world((ClientWorld) value.getEntityWorld()); }
        @Override public Vector getVelocity() { return commonVector(value.getVelocity()); }
        @Override public void setVelocity(Vector velocity) { ExactPredictionRuntime.setPredictedVelocity(value, nativeVector(velocity)); }
        @Override public boolean isDead() { return !value.isAlive(); }
        @Override public boolean isValid() { return !value.isRemoved(); }
        @Override public int getEntityId() { return value.getId(); }
        @Override public String getName() { return value.getName().getString(); }
        @Override public int getFireTicks() { return value.getFireTicks(); }
        @Override public void setFireTicks(int ticks) { value.setFireTicks(ticks); }
        @Override public boolean isOnGround() { return value.isOnGround(); }
        @Override public double getHeight() { return value.getHeight(); }
        @Override public float getFallDistance() { return (float) value.fallDistance; }
        @Override public void setFallDistance(float distance) { value.fallDistance = distance; }
        @Override public BoundingBox getBoundingBox() { return commonBox(value.getBoundingBox()); }
        @Override public boolean teleport(Location location) { value.setPosition(location.getX(), location.getY(), location.getZ()); value.setYaw(location.getYaw()); value.setPitch(location.getPitch()); return true; }
        @Override public void setMetadata(String key, MetadataValue metadata) { METADATA.computeIfAbsent(getUniqueId(), ignored -> new HashMap<>()).computeIfAbsent(key, ignored -> new ArrayList<>()).add(metadata); }
        @Override public boolean hasMetadata(String key) { return METADATA.getOrDefault(getUniqueId(), Map.of()).containsKey(key); }
        @Override public List<MetadataValue> getMetadata(String key) { return List.copyOf(METADATA.getOrDefault(getUniqueId(), Map.of()).getOrDefault(key, List.of())); }
        @Override public void removeMetadata(String key, Object owner) { Map<String, List<MetadataValue>> values = METADATA.get(getUniqueId()); if (values != null) values.remove(key); }
        @Override public Object handle() { return value; }
        @Override public Object nativeHandle() { return value; }
        @Override public boolean equals(Object other) { return other instanceof ClientEntityView view && value.getUuid().equals(view.value.getUuid()); }
        @Override public int hashCode() { return value.getUuid().hashCode(); }
    }

    private static class ClientLivingView extends LivingEntity implements ClientBacked {
        protected final net.minecraft.entity.LivingEntity value;
        private ClientLivingView(net.minecraft.entity.LivingEntity value) { this.value = value; }
        @Override public UUID getUniqueId() { return value.getUuid(); }
        @Override public Location getLocation() { return location((ClientWorld) value.getEntityWorld(), value.getEntityPos(), value.getYaw(), value.getPitch()); }
        @Override public Location getEyeLocation() { return location((ClientWorld) value.getEntityWorld(), value.getEyePos(), value.getYaw(), value.getPitch()); }
        @Override public World getWorld() { return world((ClientWorld) value.getEntityWorld()); }
        @Override public Vector getVelocity() { return commonVector(value.getVelocity()); }
        private boolean suppressRemoteMutation() {
            return !ExactPredictionRuntime.isPredictedOwned(value)
                    && PredictedContactSync.mark(AbilityExecutionContext.current(), this);
        }
        @Override public void setVelocity(Vector velocity) { if (!suppressRemoteMutation()) ExactPredictionRuntime.setPredictedVelocity(value, nativeVector(velocity)); }
        @Override public boolean isDead() { return !value.isAlive(); }
        @Override public boolean isValid() { return !value.isRemoved(); }
        @Override public int getEntityId() { return value.getId(); }
        @Override public String getName() { return value.getName().getString(); }
        @Override public int getFireTicks() { return value.getFireTicks(); }
        @Override public void setFireTicks(int ticks) { if (!suppressRemoteMutation()) value.setFireTicks(ticks); }
        @Override public boolean isOnGround() { return value.isOnGround(); }
        @Override public double getHeight() { return value.getHeight(); }
        @Override public float getFallDistance() { return (float) value.fallDistance; }
        @Override public void setFallDistance(float distance) { value.fallDistance = distance; }
        @Override public BoundingBox getBoundingBox() { return commonBox(value.getBoundingBox()); }
        @Override public double getHealth() { return value.getHealth(); }
        @Override public double getMaxHealth() { return value.getMaxHealth(); }
        @Override public void damage(double amount) { if (!suppressRemoteMutation()) ExactPredictionRuntime.claimDamage(value, amount); }
        @Override public void damage(double amount, Entity source) { if (!suppressRemoteMutation()) ExactPredictionRuntime.claimDamage(value, amount); }
        @Override public boolean hasPotionEffect(PotionEffectType type) { return value.hasStatusEffect(FabricMC.status(type)); }
        @Override public void addPotionEffect(PotionEffect effect) { if (!suppressRemoteMutation()) value.addStatusEffect(nativeEffect(effect)); }
        @Override public PotionEffect getPotionEffect(PotionEffectType type) { return commonEffect(value.getStatusEffect(FabricMC.status(type))); }
        @Override public void removePotionEffect(PotionEffectType type) { if (!suppressRemoteMutation()) value.removeStatusEffect(FabricMC.status(type)); }
        @Override public Collection<PotionEffect> getActivePotionEffects() { return value.getActiveStatusEffects().values().stream().map(FabricPredictionMC::commonEffect).toList(); }
        @Override public int getNoDamageTicks() { return value.timeUntilRegen; }
        @Override public void setNoDamageTicks(int ticks) { if (!suppressRemoteMutation()) value.timeUntilRegen = ticks; }
        @Override public int getRemainingAir() { return value.getAir(); }
        @Override public void setRemainingAir(int ticks) { if (!suppressRemoteMutation()) value.setAir(ticks); }
        @Override public void setMetadata(String key, MetadataValue metadata) { METADATA.computeIfAbsent(getUniqueId(), ignored -> new HashMap<>()).computeIfAbsent(key, ignored -> new ArrayList<>()).add(metadata); }
        @Override public boolean hasMetadata(String key) { return METADATA.getOrDefault(getUniqueId(), Map.of()).containsKey(key); }
        @Override public List<MetadataValue> getMetadata(String key) { return List.copyOf(METADATA.getOrDefault(getUniqueId(), Map.of()).getOrDefault(key, List.of())); }
        @Override public void removeMetadata(String key, Object owner) { Map<String, List<MetadataValue>> values = METADATA.get(getUniqueId()); if (values != null) values.remove(key); }
        @Override public Object handle() { return value; }
        @Override public Object nativeHandle() { return value; }
        @Override public boolean equals(Object other) { return other instanceof ClientLivingView view && value.getUuid().equals(view.value.getUuid()); }
        @Override public int hashCode() { return value.getUuid().hashCode(); }
    }

    public static final class ClientPlayerView extends Player implements ClientBacked {
        private PlayerEntity value;
        private final ClientInventory inventory;
        private ClientPlayerView(PlayerEntity value) { this.value = value; this.inventory = new ClientInventory(value); }
        private void rebind(PlayerEntity value) {
            if (this.value == value) return;
            this.value = value;
            this.inventory.rebind(value);
        }
        private boolean suppressRemoteMutation() {
            ClientPlayerEntity local = MinecraftClient.getInstance().player;
            return local != null && !local.getUuid().equals(value.getUuid())
                    && PredictedContactSync.mark(AbilityExecutionContext.current(), this);
        }
        @Override public UUID getUniqueId() { return value.getUuid(); }
        @Override public String getName() { return value.getName().getString(); }
        @Override public String getDisplayName() { return value.getDisplayName().getString(); }
        @Override public boolean hasPermission(String permission) { return true; }
        @Override public boolean isOnline() { return MinecraftClient.getInstance().world != null && MinecraftClient.getInstance().world.getPlayerByUuid(value.getUuid()) != null; }
        @Override public Location getLocation() {
            PredictionClient.ServerPose pose = serverPose();
            return pose == null
                    ? location((ClientWorld) value.getEntityWorld(), value.getEntityPos(), value.getYaw(), value.getPitch())
                    : location((ClientWorld) value.getEntityWorld(), new Vec3d(pose.x(), pose.y(), pose.z()), pose.yaw(), pose.pitch());
        }
        @Override public Location getEyeLocation() {
            PredictionClient.ServerPose pose = serverPose();
            return pose == null
                    ? location((ClientWorld) value.getEntityWorld(), value.getEyePos(), value.getYaw(), value.getPitch())
                    : location((ClientWorld) value.getEntityWorld(), pose.eyePos(), pose.yaw(), pose.pitch());
        }
        @Override public World getWorld() { return world((ClientWorld) value.getEntityWorld()); }
        @Override public Vector getVelocity() { return commonVector(value.getVelocity()); }
        @Override public void setVelocity(Vector velocity) { if (!suppressRemoteMutation()) ExactPredictionRuntime.setPredictedVelocity(value, nativeVector(velocity)); }
        @Override public PlayerInventory getInventory() { return inventory; }
        @Override public GameMode getGameMode() { return value.isSpectator() ? GameMode.SPECTATOR : GameMode.valueOf(value.isCreative() ? "CREATIVE" : "SURVIVAL"); }
        @Override public boolean isDead() { return !value.isAlive(); }
        @Override public boolean isValid() { return !value.isRemoved(); }
        @Override public int getEntityId() { return value.getId(); }
        @Override public int getFireTicks() { return value.getFireTicks(); }
        @Override public void setFireTicks(int ticks) { if (!suppressRemoteMutation()) value.setFireTicks(ticks); }
        @Override public boolean isOnGround() { return value.isOnGround(); }
        @Override public float getFallDistance() { return (float) value.fallDistance; }
        @Override public void setFallDistance(float distance) { value.fallDistance = distance; }
        @Override public BoundingBox getBoundingBox() { return commonBox(value.getBoundingBox()); }
        @Override public double getHealth() { return value.getHealth(); }
        @Override public double getMaxHealth() { return value.getMaxHealth(); }
        @Override public void damage(double amount) { if (!suppressRemoteMutation()) ExactPredictionRuntime.claimDamage(value, amount); }
        @Override public void damage(double amount, Entity source) { if (!suppressRemoteMutation()) ExactPredictionRuntime.claimDamage(value, amount); }
        @Override public boolean hasPotionEffect(PotionEffectType type) { return value.hasStatusEffect(FabricMC.status(type)); }
        @Override public void addPotionEffect(PotionEffect effect) { if (!suppressRemoteMutation()) value.addStatusEffect(nativeEffect(effect)); }
        @Override public PotionEffect getPotionEffect(PotionEffectType type) { return commonEffect(value.getStatusEffect(FabricMC.status(type))); }
        @Override public void removePotionEffect(PotionEffectType type) { if (!suppressRemoteMutation()) value.removeStatusEffect(FabricMC.status(type)); }
        @Override public Collection<PotionEffect> getActivePotionEffects() { return value.getActiveStatusEffects().values().stream().map(FabricPredictionMC::commonEffect).toList(); }
        @Override public boolean isSneaking() {
            MinecraftClient client = MinecraftClient.getInstance();
            return client.player != null && client.player.getUuid().equals(value.getUuid())
                    ? PredictionClient.serverVisibleSneaking(client) : value.isSneaking();
        }
        @Override public boolean isSprinting() { return value.isSprinting(); }
        @Override public void setSprinting(boolean sprinting) { if (!suppressRemoteMutation()) value.setSprinting(sprinting); }
        @Override public void setSneaking(boolean sneaking) { if (!suppressRemoteMutation()) value.setSneaking(sneaking); }
        @Override public boolean isFlying() { return value.getAbilities().flying; }
        @Override public void setFlying(boolean flying) { if (!suppressRemoteMutation()) { value.getAbilities().flying = flying; noteAbilityState(); } }
        @Override public boolean getAllowFlight() { return value.getAbilities().allowFlying; }
        @Override public void setAllowFlight(boolean allow) { value.getAbilities().allowFlying = allow; noteAbilityState(); }
        @Override public boolean isGliding() { return value.isGliding(); }
        @Override public void setGliding(boolean gliding) { if (!suppressRemoteMutation()) { if (gliding) value.startGliding(); else value.stopGliding(); } }
        @Override public float getFlySpeed() { return value.getAbilities().getFlySpeed() * 2; }
        @Override public void setFlySpeed(float speed) { value.getAbilities().setFlySpeed(speed / 2); noteAbilityState(); }
        @Override public float getExp() { return value.experienceProgress; }
        @Override public void setExp(float experience) {
            value.experienceProgress = Math.max(0.0F, Math.min(1.0F, experience));
            ExactPredictionRuntime.notePredictedExperience(value.experienceProgress, value.totalExperience, value.experienceLevel);
        }
        @Override public double getAbsorptionAmount() { return value.getAbsorptionAmount(); }
        @Override public void setAbsorptionAmount(double amount) { if (!suppressRemoteMutation()) value.setAbsorptionAmount((float) Math.max(0, amount)); }
        @Override public int getNoDamageTicks() { return value.timeUntilRegen; }
        @Override public void setNoDamageTicks(int ticks) { if (!suppressRemoteMutation()) value.timeUntilRegen = ticks; }
        @Override public int getRemainingAir() { return value.getAir(); }
        @Override public void setRemainingAir(int ticks) { if (!suppressRemoteMutation()) value.setAir(ticks); }
        @Override public int getPing() {
            return 0;
        }
        @Override public void sendMessage(String message) { }
        @Override public Block getTargetBlockExact(int range) {
            PredictionClient.ServerPose pose = serverPose();
            if (pose != null) {
                Vec3d origin = pose.eyePos();
                Vec3d end = origin.add(Vec3d.fromPolar(pose.pitch(), pose.yaw()).normalize().multiply(Math.max(0, range)));
                HitResult hit = value.getEntityWorld().raycast(new RaycastContext(origin, end, RaycastContext.ShapeType.OUTLINE,
                        RaycastContext.FluidHandling.NONE, value));
                return hit instanceof BlockHitResult blockHit ? block((ClientWorld) value.getEntityWorld(), blockHit.getBlockPos()) : null;
            }
            HitResult hit = value.raycast(range, 0, false);
            return hit instanceof BlockHitResult blockHit ? block((ClientWorld) value.getEntityWorld(), blockHit.getBlockPos()) : null;
        }
        @Override public Block getTargetBlock(Set<Material> transparent, int range) {
            Set<Material> passThrough = transparent == null
                    ? new HashSet<>() : new HashSet<>(transparent);
            passThrough.add(Material.AIR);
            passThrough.add(Material.CAVE_AIR);
            passThrough.add(Material.VOID_AIR);
            PredictionClient.ServerPose pose = serverPose();
            Vec3d origin = pose == null ? value.getEyePos() : pose.eyePos();
            Vec3d direction = pose == null ? value.getRotationVec(1.0F).normalize() : Vec3d.fromPolar(pose.pitch(), pose.yaw()).normalize();
            Block last = block((ClientWorld) value.getEntityWorld(), BlockPos.ofFloored(origin));
            for (double distance = 0.0; distance <= Math.max(0, range); distance += 0.2) {
                Block current = block((ClientWorld) value.getEntityWorld(), BlockPos.ofFloored(origin.add(direction.multiply(distance))));
                last = current;
                if (!passThrough.contains(current.getType())) return current;
            }
            return last;
        }
        @Override
        public List<Block> getLastTwoTargetBlocks(Set<Material> transparent, int range) {
            Set<Material> passThrough = transparent == null
                    ? new HashSet<>() : new HashSet<>(transparent);
            passThrough.add(Material.AIR);
            passThrough.add(Material.CAVE_AIR);
            passThrough.add(Material.VOID_AIR);
            PredictionClient.ServerPose pose = serverPose();
            Vec3d origin = pose == null ? value.getEyePos() : pose.eyePos();
            Vec3d direction = pose == null ? value.getRotationVec(1.0F).normalize() : Vec3d.fromPolar(pose.pitch(), pose.yaw()).normalize();
            Block previous = block((ClientWorld) value.getEntityWorld(), BlockPos.ofFloored(origin));
            Block current = previous;
            BlockPos lastPos = BlockPos.ofFloored(origin);
            for (double distance = 0.0; distance <= Math.max(0, range); distance += 0.2) {
                BlockPos pos = BlockPos.ofFloored(origin.add(direction.multiply(distance)));
                if (pos.equals(lastPos)) continue;
                lastPos = pos;
                previous = current;
                current = block((ClientWorld) value.getEntityWorld(), pos);
                if (!passThrough.contains(current.getType())) break;
            }
            return List.of(previous, current);
        }
        @Override public boolean hasLineOfSight(Entity entity) {
            Vec3d start = value.getEyePos(), end = nativeVector(entity.getBoundingBox().getCenter());
            return value.getEntityWorld().raycast(new RaycastContext(start, end, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, value)).getType() == HitResult.Type.MISS;
        }
        @Override public <T extends Projectile> T launchProjectile(Class<T> type) {
            if (type == Arrow.class) {
                ClientWorld world = (ClientWorld) value.getEntityWorld();
                ArrowEntity entity = new ArrowEntity(
                        world, value, new net.minecraft.item.ItemStack(Items.ARROW), null);
                entity.setVelocity(value, value.getPitch(), value.getYaw(), 0.0F, 3.0F, 1.0F);
                world.addEntity(entity);
                ExactPredictionRuntime.trackSpawn(entity);
                return type.cast(new ClientArrow(entity));
            }
            if (type == Snowball.class) {
                Location spawn = getEyeLocation();
                Snowball projectile = getWorld().spawn(spawn,
                        Snowball.class);
                projectile.setShooter(this);
                projectile.setVelocity(spawn.getDirection().multiply(1.5));
                return type.cast(projectile);
            }
            return super.launchProjectile(type);
        }
        @Override
        public <T> void spawnParticle(Particle particle, Location location, int count, double ox, double oy, double oz, double extra, T data, boolean force) {
            if (isLocalReceiver()) getWorld().spawnParticle(particle, location, count, ox, oy, oz, extra, data);
        }
        @Override
        public void spawnParticle(Particle particle, Location location, int count, double ox, double oy, double oz) {
            if (isLocalReceiver()) getWorld().spawnParticle(particle, location, count, ox, oy, oz);
        }
        @Override public void playSound(Location location, Sound sound, float volume, float pitch) {
            if (isLocalReceiver()) getWorld().playSound(location, sound, volume, pitch);
        }
        @Override public boolean teleport(Location location) {
            if (suppressRemoteMutation()) return false;
            value.setPosition(location.getX(), location.getY(), location.getZ());
            return true;
        }
        @Override public void setMetadata(String key, MetadataValue metadata) { METADATA.computeIfAbsent(getUniqueId(), ignored -> new HashMap<>()).computeIfAbsent(key, ignored -> new ArrayList<>()).add(metadata); }
        @Override public boolean hasMetadata(String key) { return METADATA.getOrDefault(getUniqueId(), Map.of()).containsKey(key); }
        @Override public List<MetadataValue> getMetadata(String key) { return List.copyOf(METADATA.getOrDefault(getUniqueId(), Map.of()).getOrDefault(key, List.of())); }
        @Override public void removeMetadata(String key, Object owner) { Map<String, List<MetadataValue>> values = METADATA.get(getUniqueId()); if (values != null) values.remove(key); }
        @Override public Object handle() { return value; }
        @Override public Object nativeHandle() { return value; }
        @Override public boolean equals(Object other) { return other instanceof ClientPlayerView view && value.getUuid().equals(view.value.getUuid()); }
        @Override public int hashCode() { return value.getUuid().hashCode(); }
        private void noteAbilityState() {
            ExactPredictionRuntime.notePredictedAbilityState(value.getAbilities().invulnerable, value.getAbilities().flying,
                    value.getAbilities().allowFlying, value.getAbilities().creativeMode,
                    value.getAbilities().getFlySpeed(), value.getAbilities().getWalkSpeed());
        }
        private PredictionClient.ServerPose serverPose() {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || !client.player.getUuid().equals(value.getUuid())) return null;
            PredictionClient.ServerPose execution = ExactPredictionRuntime.executionPose();
            return execution == null ? PredictionClient.serverVisiblePose(client) : execution;
        }
        private boolean isLocalReceiver() {
            ClientPlayerEntity local = MinecraftClient.getInstance().player;
            return local != null && local.getUuid().equals(value.getUuid());
        }
    }

    private static final class ClientInventory extends PlayerInventory {
        private PlayerEntity player;
        private int selectedSlot;
        private ClientInventory(PlayerEntity player) {
            this.player = player;
            this.selectedSlot = player.getInventory().getSelectedSlot();
        }
        private void rebind(PlayerEntity player) {
            this.player = player;
        }
        @Override public ItemStack getItemInMainHand() { return commonItem(player.getMainHandStack()); }
        @Override public ItemStack getItemInOffHand() { return commonItem(player.getOffHandStack()); }
        @Override public int getHeldItemSlot() {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null && client.player.getUuid().equals(player.getUuid())) {
                selectedSlot = PredictionClient.serverVisibleSelectedSlot(client);
            }
            return selectedSlot;
        }
        @Override public void setHeldItemSlot(int slot) {
            if (slot >= 0 && slot < 9) selectedSlot = slot;
        }
        @Override public ItemStack getItem(int slot) { return commonItem(player.getInventory().getStack(slot)); }
        @Override public int getSize() { return player.getInventory().size(); }
        @Override public ItemStack[] getContents() {
            ItemStack[] result = new ItemStack[getSize()];
            for (int i = 0; i < result.length; i++) result[i] = getItem(i);
            return result;
        }
    }

    private static final class ClientFallingBlock extends FallingBlock implements ClientBacked {
        private final FallingBlockEntity value;
        private ClientFallingBlock(FallingBlockEntity value) { this.value = value; }
        @Override public UUID getUniqueId() { return value.getUuid(); }
        @Override public Location getLocation() { return location((ClientWorld) value.getEntityWorld(), value.getEntityPos(), value.getYaw(), value.getPitch()); }
        @Override public World getWorld() { return world((ClientWorld) value.getEntityWorld()); }
        @Override public Vector getVelocity() { return commonVector(value.getVelocity()); }
        @Override public void setVelocity(Vector velocity) { ExactPredictionRuntime.setPredictedVelocity(value, nativeVector(velocity)); }
        @Override public void remove() { value.discard(); }
        @Override public boolean isDead() { return !value.isAlive(); }
        @Override public boolean isValid() { return !value.isRemoved(); }
        @Override public int getEntityId() { return value.getId(); }
        @Override public boolean isOnGround() { return value.isOnGround(); }
        @Override public BoundingBox getBoundingBox() { return commonBox(value.getBoundingBox()); }
        @Override public void setDropItem(boolean drop) { value.dropItem = drop; }
        @Override public BlockData getBlockData() {
            return FabricMC.blockData(value.getBlockState());
        }
        @Override public Object handle() { return value; }
        @Override public Object nativeHandle() { return value; }
    }

    private static final class ClientItem extends Item implements ClientBacked {
        private final ItemEntity value;
        private ClientItem(ItemEntity value) { this.value = value; }
        @Override public UUID getUniqueId() { return value.getUuid(); }
        @Override public Location getLocation() { return location((ClientWorld) value.getEntityWorld(), value.getEntityPos(), value.getYaw(), value.getPitch()); }
        @Override public World getWorld() { return world((ClientWorld) value.getEntityWorld()); }
        @Override public Vector getVelocity() { return commonVector(value.getVelocity()); }
        @Override public void setVelocity(Vector velocity) { ExactPredictionRuntime.setPredictedVelocity(value, nativeVector(velocity)); }
        @Override public void remove() { value.discard(); }
        @Override public boolean isDead() { return !value.isAlive(); }
        @Override public boolean isValid() { return !value.isRemoved(); }
        @Override public int getEntityId() { return value.getId(); }
        @Override public BoundingBox getBoundingBox() { return commonBox(value.getBoundingBox()); }
        @Override public ItemStack getItemStack() { return commonItem(value.getStack()); }
        @Override public void setItemStack(ItemStack item) { value.setStack(FabricMC.itemStack(item)); }
        @Override public void setPickupDelay(int ticks) { value.setPickupDelay(ticks); }
        @Override public Object handle() { return value; }
        @Override public Object nativeHandle() { return value; }
    }

    private static final class ClientArmorStand extends ArmorStand implements ClientBacked {
        private final ArmorStandEntity value;
        private ClientArmorStand(ArmorStandEntity value) { this.value = value; }
        @Override public UUID getUniqueId() { return value.getUuid(); }
        @Override public Location getLocation() { return location((ClientWorld) value.getEntityWorld(), value.getEntityPos(), value.getYaw(), value.getPitch()); }
        @Override public Location getEyeLocation() { return location((ClientWorld) value.getEntityWorld(), value.getEyePos(), value.getYaw(), value.getPitch()); }
        @Override public World getWorld() { return world((ClientWorld) value.getEntityWorld()); }
        @Override public Vector getVelocity() { return commonVector(value.getVelocity()); }
        @Override public void setVelocity(Vector velocity) { ExactPredictionRuntime.setPredictedVelocity(value, nativeVector(velocity)); }
        @Override public void remove() { value.discard(); }
        @Override public boolean isDead() { return !value.isAlive(); }
        @Override public boolean isValid() { return !value.isRemoved(); }
        @Override public int getEntityId() { return value.getId(); }
        @Override public boolean isMarker() { return value.isMarker(); }
        @Override public boolean isInvisible() { return value.isInvisible(); }
        @Override public void setVisible(boolean visible) { value.setInvisible(!visible); }
        @Override public void setHelmet(ItemStack item) { value.equipStack(EquipmentSlot.HEAD, FabricMC.itemStack(item)); }
        @Override public void setGravity(boolean gravity) { value.setNoGravity(!gravity); }
        @Override public void setSilent(boolean silent) { value.setSilent(silent); }
        @Override public void setInvulnerable(boolean invulnerable) { value.setInvulnerable(invulnerable); }
        @Override public BoundingBox getBoundingBox() { return commonBox(value.getBoundingBox()); }
        @Override public Object handle() { return value; }
        @Override public Object nativeHandle() { return value; }
    }

    private static final class ClientBlockDisplay extends BlockDisplay implements ClientBacked {
        private final DisplayEntity.BlockDisplayEntity value;
        private ClientBlockDisplay(DisplayEntity.BlockDisplayEntity value) { this.value = value; }
        @Override public UUID getUniqueId() { return value.getUuid(); }
        @Override public Location getLocation() { return location((ClientWorld) value.getEntityWorld(), value.getEntityPos(), value.getYaw(), value.getPitch()); }
        @Override public World getWorld() { return world((ClientWorld) value.getEntityWorld()); }
        @Override public Vector getVelocity() { return commonVector(value.getVelocity()); }
        @Override public void setVelocity(Vector velocity) { ExactPredictionRuntime.setPredictedVelocity(value, nativeVector(velocity)); }
        @Override public void remove() { value.discard(); }
        @Override public boolean isDead() { return !value.isAlive(); }
        @Override public boolean isValid() { return !value.isRemoved(); }
        @Override public int getEntityId() { return value.getId(); }
        @Override public boolean teleport(Location location) {
            if (location == null || location.getWorld() == null
                    || location.getWorld().handle() != value.getEntityWorld()) return false;
            value.refreshPositionAndAngles(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
            return true;
        }
        @Override public void setGravity(boolean gravity) { value.setNoGravity(!gravity); }
        @Override public void setSilent(boolean silent) { value.setSilent(silent); }
        @Override public void setBrightness(Display.Brightness brightness) {
            value.setBrightness(brightness == null ? null : new net.minecraft.entity.decoration.Brightness(brightness.blockLight(), brightness.skyLight()));
        }
        @Override public void setTransformation(Transformation transformation) {
            value.setTransformation(new AffineTransformation(transformation.translation(),
                    transformation.leftRotation(), transformation.scale(), transformation.rightRotation()));
        }
        @Override public void setBillboard(Display.Billboard billboard) {
            value.setBillboardMode(DisplayEntity.BillboardMode.valueOf(billboard.name()));
        }
        @Override public void setBlock(BlockData data) { value.setBlockState(FabricMC.blockState(data)); }
        @Override public void setShadowRadius(float shadowRadius) { value.setShadowRadius(shadowRadius); }
        @Override public void setShadowStrength(float shadowStrength) { value.setShadowStrength(shadowStrength); }
        @Override public void setInterpolationDelay(int delay) { value.setStartInterpolation(delay); }
        @Override public void setInterpolationDuration(int duration) { value.setInterpolationDuration(duration); }
        @Override public void setTeleportDuration(int duration) { value.setTeleportDuration(duration); }
        @Override public void setViewRange(float range) { value.setViewRange(range); }
        @Override public Object handle() { return value; }
        @Override public Object nativeHandle() { return value; }
    }

    private static final class ClientShulkerBullet extends ShulkerBullet implements ClientBacked {
        private final ShulkerBulletEntity value;
        private ClientShulkerBullet(ShulkerBulletEntity value) { this.value = value; }
        @Override public UUID getUniqueId() { return value.getUuid(); }
        @Override public Location getLocation() { return entityLocation(value); }
        @Override public World getWorld() { return world((ClientWorld) value.getEntityWorld()); }
        @Override public Vector getVelocity() { return commonVector(value.getVelocity()); }
        @Override public void setVelocity(Vector velocity) { ExactPredictionRuntime.setPredictedVelocity(value, nativeVector(velocity)); }
        @Override public void setShooter(Object shooter) { value.setOwner(nativeEntity(shooter)); }
        @Override public void setGravity(boolean gravity) { value.setNoGravity(!gravity); }
        @Override public void setSilent(boolean silent) { value.setSilent(silent); }
        @Override public void setInvulnerable(boolean invulnerable) { value.setInvulnerable(invulnerable); }
        @Override public void setPersistent(boolean persistent) { }
        @Override public void remove() { value.discard(); }
        @Override public boolean isDead() { return !value.isAlive(); }
        @Override public boolean isValid() { return !value.isRemoved(); }
        @Override public int getEntityId() { return value.getId(); }
        @Override public boolean isOnGround() { return value.isOnGround(); }
        @Override public BoundingBox getBoundingBox() { return commonBox(value.getBoundingBox()); }
        @Override public void setMetadata(String key, MetadataValue metadata) { metadata(value, key, metadata); }
        @Override public boolean hasMetadata(String key) { return FabricPredictionMC.hasMetadata(value, key); }
        @Override public List<MetadataValue> getMetadata(String key) { return metadata(value, key); }
        @Override public void removeMetadata(String key, Object owner) { FabricPredictionMC.removeMetadata(value, key); }
        @Override public Object handle() { return value; }
        @Override public Object nativeHandle() { return value; }
    }

    private static final class ClientFireball extends Fireball implements ClientBacked {
        private final SmallFireballEntity value;
        private ClientFireball(SmallFireballEntity value) { this.value = value; }
        @Override public UUID getUniqueId() { return value.getUuid(); }
        @Override public Location getLocation() { return entityLocation(value); }
        @Override public World getWorld() { return world((ClientWorld) value.getEntityWorld()); }
        @Override public Vector getVelocity() { return commonVector(value.getVelocity()); }
        @Override public void setVelocity(Vector velocity) { ExactPredictionRuntime.setPredictedVelocity(value, nativeVector(velocity)); }
        @Override public void setDirection(Vector direction) { setVelocity(direction); }
        @Override public void setShooter(Object shooter) { value.setOwner(nativeEntity(shooter)); }
        @Override public void setYield(float yield) { }
        @Override public void setIsIncendiary(boolean incendiary) { }
        @Override public void setGravity(boolean gravity) { value.setNoGravity(!gravity); }
        @Override public void setSilent(boolean silent) { value.setSilent(silent); }
        @Override public void setInvulnerable(boolean invulnerable) { value.setInvulnerable(invulnerable); }
        @Override public void remove() { value.discard(); }
        @Override public boolean isDead() { return !value.isAlive(); }
        @Override public boolean isValid() { return !value.isRemoved(); }
        @Override public int getEntityId() { return value.getId(); }
        @Override public BoundingBox getBoundingBox() { return commonBox(value.getBoundingBox()); }
        @Override public void setMetadata(String key, MetadataValue metadata) { metadata(value, key, metadata); }
        @Override public boolean hasMetadata(String key) { return FabricPredictionMC.hasMetadata(value, key); }
        @Override public List<MetadataValue> getMetadata(String key) { return metadata(value, key); }
        @Override public void removeMetadata(String key, Object owner) { FabricPredictionMC.removeMetadata(value, key); }
        @Override public Object handle() { return value; }
        @Override public Object nativeHandle() { return value; }
    }

    private static final class ClientSnowball extends Snowball implements ClientBacked {
        private final SnowballEntity value;
        private ClientSnowball(SnowballEntity value) { this.value = value; }
        @Override public UUID getUniqueId() { return value.getUuid(); }
        @Override public Location getLocation() { return entityLocation(value); }
        @Override public World getWorld() { return world((ClientWorld) value.getEntityWorld()); }
        @Override public Vector getVelocity() { return commonVector(value.getVelocity()); }
        @Override public void setVelocity(Vector velocity) { ExactPredictionRuntime.setPredictedVelocity(value, nativeVector(velocity)); }
        @Override public void setShooter(Object shooter) { value.setOwner(nativeEntity(shooter)); }
        @Override public void setGravity(boolean gravity) { value.setNoGravity(!gravity); }
        @Override public void setSilent(boolean silent) { value.setSilent(silent); }
        @Override public void setInvulnerable(boolean invulnerable) { value.setInvulnerable(invulnerable); }
        @Override public void remove() { value.discard(); }
        @Override public boolean isDead() { return !value.isAlive(); }
        @Override public boolean isValid() { return !value.isRemoved(); }
        @Override public int getEntityId() { return value.getId(); }
        @Override public BoundingBox getBoundingBox() { return commonBox(value.getBoundingBox()); }
        @Override public void setMetadata(String key, MetadataValue metadata) { metadata(value, key, metadata); }
        @Override public boolean hasMetadata(String key) { return FabricPredictionMC.hasMetadata(value, key); }
        @Override public List<MetadataValue> getMetadata(String key) { return metadata(value, key); }
        @Override public void removeMetadata(String key, Object owner) { FabricPredictionMC.removeMetadata(value, key); }
        @Override public Object handle() { return value; }
        @Override public Object nativeHandle() { return value; }
    }

    private static final class ClientAreaEffectCloud extends AreaEffectCloud implements ClientBacked {
        private final AreaEffectCloudEntity value;
        private ClientAreaEffectCloud(AreaEffectCloudEntity value) { this.value = value; }
        @Override public UUID getUniqueId() { return value.getUuid(); }
        @Override public Location getLocation() { return entityLocation(value); }
        @Override public World getWorld() { return world((ClientWorld) value.getEntityWorld()); }
        @Override public Vector getVelocity() { return commonVector(value.getVelocity()); }
        @Override public void setVelocity(Vector velocity) { ExactPredictionRuntime.setPredictedVelocity(value, nativeVector(velocity)); }
        @Override public void setParticle(Particle particle) { value.setParticleType(FabricMC.particle(particle, null)); }
        @Override public void setColor(Color color) {
            value.setParticleType(TintedParticleEffect.create(ParticleTypes.ENTITY_EFFECT,
                    0xFF000000 | color.asRGB()));
        }
        @Override public void setDuration(int duration) { value.setDuration(duration); }
        @Override public void setRadius(float radius) {
            value.getDataTracker().set(AreaEffectCloudEntityAccessor.projectkorra$radius(),
                    Math.max(0, Math.min(32, radius)));
        }
        @Override public void setSource(Entity entity) {
            net.minecraft.entity.Entity owner = nativeEntity(entity);
            value.setOwner(owner instanceof net.minecraft.entity.LivingEntity living ? living : null);
        }
        @Override public void setRadiusOnUse(float radius) { value.setRadiusOnUse(radius); }
        @Override public void setRadiusPerTick(float radius) { value.setRadiusGrowth(radius); }
        @Override public void setReapplicationDelay(int ticks) {
            ((AreaEffectCloudEntityAccessor) (Object) value)
                    .projectkorra$setReapplicationDelay(ticks);
        }
        @Override public void setWaitTime(int ticks) { value.setWaitTime(ticks); }
        @Override public void addCustomEffect(PotionEffect effect, boolean overwrite) {
            value.addEffect(new StatusEffectInstance(FabricMC.status(effect.getType()),
                    effect.getDuration(), effect.getAmplifier()));
        }
        @Override public void remove() { value.discard(); }
        @Override public boolean isDead() { return !value.isAlive(); }
        @Override public boolean isValid() { return !value.isRemoved(); }
        @Override public int getEntityId() { return value.getId(); }
        @Override public BoundingBox getBoundingBox() { return commonBox(value.getBoundingBox()); }
        @Override public Object handle() { return value; }
        @Override public Object nativeHandle() { return value; }
    }

    private static final class ClientArrow extends Arrow implements ClientBacked {
        private final ArrowEntity value;
        private ClientArrow(ArrowEntity value) { this.value = value; }
        @Override public UUID getUniqueId() { return value.getUuid(); }
        @Override public Location getLocation() { return entityLocation(value); }
        @Override public World getWorld() { return world((ClientWorld) value.getEntityWorld()); }
        @Override public Vector getVelocity() { return commonVector(value.getVelocity()); }
        @Override public void setVelocity(Vector velocity) { ExactPredictionRuntime.setPredictedVelocity(value, nativeVector(velocity)); }
        @Override public Object getShooter() { return entity(value.getOwner()); }
        @Override public void setShooter(Object shooter) { value.setOwner(nativeEntity(shooter)); }
        @Override public void setPickupStatus(PickupStatus status) {
            value.pickupType = PersistentProjectileEntity.PickupPermission.valueOf(status.name());
        }
        @Override public PickupStatus getPickupStatus() { return PickupStatus.valueOf(value.pickupType.name()); }
        @Override public void setDamage(int damage) { value.setDamage(damage); }
        @Override public void setCritical(boolean critical) { value.setCritical(critical); }
        @Override public void setGravity(boolean gravity) { value.setNoGravity(!gravity); }
        @Override public void setSilent(boolean silent) { value.setSilent(silent); }
        @Override public void setInvulnerable(boolean invulnerable) { value.setInvulnerable(invulnerable); }
        @Override public void remove() { value.discard(); }
        @Override public boolean isDead() { return !value.isAlive(); }
        @Override public boolean isValid() { return !value.isRemoved(); }
        @Override public int getEntityId() { return value.getId(); }
        @Override public boolean isOnGround() { return value.isOnGround(); }
        @Override public Block getAttachedBlock() {
            if (!isInGround()) return null;
            return block((ClientWorld) value.getEntityWorld(), value.getBlockPos());
        }
        @Override public BoundingBox getBoundingBox() { return commonBox(value.getBoundingBox()); }
        @Override public void setMetadata(String key, MetadataValue metadata) { metadata(value, key, metadata); }
        @Override public boolean hasMetadata(String key) { return FabricPredictionMC.hasMetadata(value, key); }
        @Override public List<MetadataValue> getMetadata(String key) { return metadata(value, key); }
        @Override public void removeMetadata(String key, Object owner) { FabricPredictionMC.removeMetadata(value, key); }
        @Override public Object handle() { return value; }
        @Override public Object nativeHandle() { return value; }
        private boolean isInGround() {
            try {
                Field field = PersistentProjectileEntity.class.getDeclaredField("inGroundTime");
                field.setAccessible(true);
                return field.getInt(value) > 0;
            } catch (ReflectiveOperationException ignored) {
                return false;
            }
        }
    }

    private static final class ClientSlime extends Slime implements ClientBacked {
        private final SlimeEntity value;
        private ClientSlime(SlimeEntity value) { this.value = value; }
        private boolean suppressRemoteMutation() {
            return !ExactPredictionRuntime.isPredictedOwned(value)
                    && PredictedContactSync.mark(AbilityExecutionContext.current(), this);
        }
        @Override public UUID getUniqueId() { return value.getUuid(); }
        @Override public Location getLocation() { return entityLocation(value); }
        @Override public Location getEyeLocation() { return location((ClientWorld) value.getEntityWorld(), value.getEyePos(), value.getYaw(), value.getPitch()); }
        @Override public World getWorld() { return world((ClientWorld) value.getEntityWorld()); }
        @Override public Vector getVelocity() { return commonVector(value.getVelocity()); }
        @Override public void setVelocity(Vector velocity) { if (!suppressRemoteMutation()) ExactPredictionRuntime.setPredictedVelocity(value, nativeVector(velocity)); }
        @Override public void setSize(int size) { value.setSize(size, true); }
        @Override public void addPotionEffect(PotionEffect effect) {
            if (!suppressRemoteMutation()) {
                value.addStatusEffect(new StatusEffectInstance(FabricMC.status(effect.getType()),
                        effect.getDuration(), effect.getAmplifier()));
            }
        }
        @Override public boolean addPassenger(Entity passenger) {
            net.minecraft.entity.Entity nativePassenger = nativeEntity(passenger);
            return nativePassenger != null && nativePassenger.startRiding(value, true, true);
        }
        @Override public void setSilent(boolean silent) { value.setSilent(silent); }
        @Override public void setInvulnerable(boolean invulnerable) { value.setInvulnerable(invulnerable); }
        @Override public void remove() { value.discard(); }
        @Override public boolean isDead() { return !value.isAlive(); }
        @Override public boolean isValid() { return !value.isRemoved(); }
        @Override public int getEntityId() { return value.getId(); }
        @Override public double getHealth() { return value.getHealth(); }
        @Override public double getMaxHealth() { return value.getMaxHealth(); }
        @Override public BoundingBox getBoundingBox() { return commonBox(value.getBoundingBox()); }
        @Override public Object handle() { return value; }
        @Override public Object nativeHandle() { return value; }
    }

    private record PlayerAdapter(ClientPlayerView delegate) implements PKPlayer {
        @Override public Object handle() { return delegate; }
        @Override public UUID uuid() { return delegate.getUniqueId(); }
        @Override public String name() { return delegate.getName(); }
        @Override public void sendMessage(String message) { delegate.sendMessage(message); }
        @Override public boolean hasPermission(String permission) { return delegate.hasPermission(permission); }
        @Override public boolean sneaking() { return delegate.isSneaking(); }
        @Override public boolean sprinting() { return delegate.isSprinting(); }
        @Override public PKLocation eyeLocation() { return new LocationAdapter(delegate.getEyeLocation()); }
        @Override public PKBlock targetBlock(int range) { Block block = delegate.getTargetBlockExact(range); return block instanceof ClientBlockView clientBlock ? new BlockAdapter(clientBlock) : null; }
        @Override public PKWorld world() { return new WorldAdapter((ClientWorldView) delegate.getWorld()); }
        @Override public PKLocation location() { return new LocationAdapter(delegate.getLocation()); }
        @Override public PKVec3 velocity() { Vector vector = delegate.getVelocity(); return new PKVec3(vector.getX(), vector.getY(), vector.getZ()); }
        @Override public void velocity(PKVec3 velocity) { delegate.setVelocity(new Vector(velocity.x(), velocity.y(), velocity.z())); }
        @Override public boolean valid() { return delegate.isValid(); }
        @Override public void remove() { delegate.remove(); }
        @Override public double health() { return delegate.getHealth(); }
        @Override public void damage(double amount, PKEntity source) { delegate.damage(amount, source == null ? null : (Entity) source.handle()); }
    }
    private record EntityAdapter(Entity delegate) implements PKEntity {
        @Override public Object handle() { return delegate; }
        @Override public UUID uuid() { return delegate.getUniqueId(); }
        @Override public PKWorld world() { return new WorldAdapter((ClientWorldView) delegate.getWorld()); }
        @Override public PKLocation location() { return new LocationAdapter(delegate.getLocation()); }
        @Override public PKVec3 velocity() { Vector vector = delegate.getVelocity(); return new PKVec3(vector.getX(), vector.getY(), vector.getZ()); }
        @Override public void velocity(PKVec3 velocity) { delegate.setVelocity(new Vector(velocity.x(), velocity.y(), velocity.z())); }
        @Override public boolean valid() { return delegate.isValid(); }
        @Override public void remove() { delegate.remove(); }
    }
    private record LivingAdapter(LivingEntity delegate) implements PKLivingEntity {
        @Override public Object handle() { return delegate; }
        @Override public UUID uuid() { return delegate.getUniqueId(); }
        @Override public PKWorld world() { return new WorldAdapter((ClientWorldView) delegate.getWorld()); }
        @Override public PKLocation location() { return new LocationAdapter(delegate.getLocation()); }
        @Override public PKVec3 velocity() { Vector vector = delegate.getVelocity(); return new PKVec3(vector.getX(), vector.getY(), vector.getZ()); }
        @Override public void velocity(PKVec3 velocity) { delegate.setVelocity(new Vector(velocity.x(), velocity.y(), velocity.z())); }
        @Override public boolean valid() { return delegate.isValid(); }
        @Override public void remove() { delegate.remove(); }
        @Override public double health() { return delegate.getHealth(); }
        @Override public void damage(double amount, PKEntity source) { delegate.damage(amount, source == null ? null : (Entity) source.handle()); }
    }
    private record WorldAdapter(ClientWorldView delegate) implements PKWorld {
        @Override public Object handle() { return delegate; }
        @Override public String name() { return delegate.getName(); }
        @Override public PKBlock blockAt(int x, int y, int z) { return new BlockAdapter((ClientBlockView) delegate.getBlockAt(x, y, z)); }
        @Override public void playSound(PKLocation location, String sound, float volume, float pitch) { delegate.playSound((Location) location.handle(), sound, volume, pitch); }
        @Override public void spawnParticle(String particle, PKLocation location, int count, double offsetX, double offsetY, double offsetZ, double extra) {
            delegate.spawnParticle(Particle.valueOf(particle), (Location) location.handle(), count, offsetX, offsetY, offsetZ, extra);
        }
    }
    private record BlockAdapter(ClientBlockView delegate) implements PKBlock {
        @Override public Object handle() { return delegate; }
        @Override public PKWorld world() { return new WorldAdapter((ClientWorldView) delegate.getWorld()); }
        @Override public int x() { return delegate.getX(); }
        @Override public int y() { return delegate.getY(); }
        @Override public int z() { return delegate.getZ(); }
        @Override public String materialKey() { return delegate.getType().name().toLowerCase(Locale.ROOT); }
        @Override public boolean liquid() { return delegate.isLiquid(); }
        @Override public boolean passable() { return delegate.isPassable(); }
        @Override public PKLocation location() { return new LocationAdapter(delegate.getLocation()); }
    }
    private record LocationAdapter(Location delegate) implements PKLocation {
        @Override public Object handle() { return delegate; }
        @Override public PKWorld world() { return new WorldAdapter((ClientWorldView) delegate.getWorld()); }
        @Override public double x() { return delegate.getX(); }
        @Override public double y() { return delegate.getY(); }
        @Override public double z() { return delegate.getZ(); }
        @Override public float yaw() { return delegate.getYaw(); }
        @Override public float pitch() { return delegate.getPitch(); }
        @Override public PKVec3 direction() { Vector vector = delegate.getDirection(); return new PKVec3(vector.getX(), vector.getY(), vector.getZ()); }
    }

    private static Vec3d nativeVector(Vector vector) { return new Vec3d(vector.getX(), vector.getY(), vector.getZ()); }
    private static Vector commonVector(Vec3d vector) { return new Vector(vector.x, vector.y, vector.z); }
    private static BoundingBox commonBox(Box box) { return new BoundingBox(new Vector(box.minX, box.minY, box.minZ), new Vector(box.maxX, box.maxY, box.maxZ)); }
    private static Location entityLocation(net.minecraft.entity.Entity entity) {
        return location((ClientWorld) entity.getEntityWorld(), entity.getEntityPos(), entity.getYaw(), entity.getPitch());
    }
    private static net.minecraft.entity.Entity nativeEntity(Object entity) {
        if (entity instanceof ClientBacked backed && backed.nativeHandle() instanceof net.minecraft.entity.Entity nativeEntity) return nativeEntity;
        return entity instanceof net.minecraft.entity.Entity nativeEntity ? nativeEntity : null;
    }
    private static void metadata(net.minecraft.entity.Entity entity, String key,
                                 MetadataValue value) {
        METADATA.computeIfAbsent(entity.getUuid(), ignored -> new HashMap<>()).computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
    }
    private static boolean hasMetadata(net.minecraft.entity.Entity entity, String key) {
        return METADATA.getOrDefault(entity.getUuid(), Map.of()).containsKey(key);
    }
    private static List<MetadataValue> metadata(net.minecraft.entity.Entity entity, String key) {
        return List.copyOf(METADATA.getOrDefault(entity.getUuid(), Map.of()).getOrDefault(key, List.of()));
    }
    private static void removeMetadata(net.minecraft.entity.Entity entity, String key) {
        Map<String, List<MetadataValue>> values = METADATA.get(entity.getUuid());
        if (values != null) values.remove(key);
    }
    private static StatusEffectInstance nativeEffect(
            PotionEffect effect) {
        return new StatusEffectInstance(FabricMC.status(effect.getType()),
                effect.getDuration(), effect.getAmplifier());
    }
    private static PotionEffect commonEffect(
            StatusEffectInstance effect) {
        if (effect == null) return null;
        String name = effect.getEffectType().getKey().map(key -> key.getValue().getPath().toUpperCase(Locale.ROOT)).orElse("SPEED");
        return new PotionEffect(
                PotionEffectType.valueOf(name),
                effect.getDuration(), effect.getAmplifier());
    }
    private static ItemStack commonItem(net.minecraft.item.ItemStack nativeStack) {
        if (nativeStack == null || nativeStack.isEmpty()) return new ItemStack(Material.AIR, 0);
        Identifier id = Registries.ITEM.getId(nativeStack.getItem());
        Material material;
        try { material = Material.valueOf(id.getPath().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ignored) { material = Material.AIR; }
        ItemStack result = new ItemStack(material, nativeStack.getCount());
        if (nativeStack.isDamageable()) result.setDurability((short) nativeStack.getDamage());
        return result;
    }

    private static int[] faceDelta(BlockFace face) {
        return switch (face) {
            case DOWN -> new int[]{0, -1, 0};
            case UP -> new int[]{0, 1, 0};
            case NORTH -> new int[]{0, 0, -1};
            case SOUTH -> new int[]{0, 0, 1};
            case EAST -> new int[]{1, 0, 0};
            case WEST -> new int[]{-1, 0, 0};
            case NORTH_EAST -> new int[]{1, 0, -1};
            case NORTH_WEST -> new int[]{-1, 0, -1};
            case SOUTH_EAST -> new int[]{1, 0, 1};
            case SOUTH_WEST -> new int[]{-1, 0, 1};
            default -> new int[]{0, 0, 0};
        };
    }
}
