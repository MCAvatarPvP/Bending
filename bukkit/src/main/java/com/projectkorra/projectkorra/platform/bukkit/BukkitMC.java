package com.projectkorra.projectkorra.platform.bukkit;

import com.projectkorra.projectkorra.BukkitProjectKorraPlugin;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.chat.ChatMessageType;
import com.projectkorra.projectkorra.platform.mc.*;
import com.projectkorra.projectkorra.platform.mc.block.Biome;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.BlockState;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.block.data.Levelled;
import com.projectkorra.projectkorra.platform.mc.command.CommandSender;
import com.projectkorra.projectkorra.platform.mc.entity.*;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent;
import com.projectkorra.projectkorra.platform.mc.inventory.EntityEquipment;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.inventory.PlayerInventory;
import com.projectkorra.projectkorra.platform.mc.inventory.meta.ItemMeta;
import com.projectkorra.projectkorra.platform.mc.inventory.meta.LeatherArmorMeta;
import com.projectkorra.projectkorra.platform.mc.inventory.meta.PotionMeta;
import com.projectkorra.projectkorra.platform.mc.metadata.FixedMetadataValue;
import com.projectkorra.projectkorra.platform.mc.metadata.MetadataValue;
import com.projectkorra.projectkorra.platform.mc.potion.PotionData;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.platform.mc.potion.PotionType;
import com.projectkorra.projectkorra.platform.mc.scoreboard.DisplaySlot;
import com.projectkorra.projectkorra.platform.mc.scoreboard.Objective;
import com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard;
import com.projectkorra.projectkorra.platform.mc.scoreboard.Team;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.RayTraceResult;
import com.projectkorra.projectkorra.platform.mc.util.Transformation;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.CapturedInputPose;
import com.projectkorra.projectkorra.prediction.PaperPredictionServer;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scoreboard.Score;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Converts Bukkit values at the platform boundary into common API values.
 */
public final class BukkitMC {
    private static final Scoreboard EMPTY_SCOREBOARD =
            new Scoreboard();
    private static final Map<UUID, Scoreboard> PLAYER_SCOREBOARDS = new ConcurrentHashMap<>();
    private static final Map<UUID, Runnable> SCOREBOARD_LISTENERS = new ConcurrentHashMap<>();
    private static final Map<UUID, org.bukkit.scoreboard.Scoreboard> NATIVE_SCOREBOARDS = new ConcurrentHashMap<>();
    private static final Map<BlockKey, Block> BLOCKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Entity> ENTITIES = new ConcurrentHashMap<>();
    private static final Map<UUID, OfflinePlayer> OFFLINE_PLAYERS = new ConcurrentHashMap<>();
    private static final Map<UUID, World> WORLDS = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> SNEAK_OVERRIDES = new ConcurrentHashMap<>();
    private static final Map<UUID, CapturedInputPose> VIEW_OVERRIDES = new ConcurrentHashMap<>();

    private BukkitMC() {
    }

    private static void setScoreboard(org.bukkit.entity.Player player, Scoreboard board) {
        UUID uuid = player.getUniqueId();
        if (PLAYER_SCOREBOARDS.get(uuid) == board && NATIVE_SCOREBOARDS.containsKey(uuid)) {
            syncScoreboard(player, board);
            return;
        }
        Scoreboard previous = PLAYER_SCOREBOARDS.put(uuid, board);
        Runnable previousListener = SCOREBOARD_LISTENERS.remove(uuid);
        if (previous != null && previousListener != null) previous.removeChangeListener(previousListener);
        Runnable listener = () -> syncScoreboard(player, board);
        SCOREBOARD_LISTENERS.put(uuid, listener);
        board.addChangeListener(listener);
        org.bukkit.scoreboard.Scoreboard nativeBoard = Bukkit.getScoreboardManager().getNewScoreboard();
        NATIVE_SCOREBOARDS.put(uuid, nativeBoard);
        syncScoreboard(player, board);
        player.setScoreboard(nativeBoard);
    }

    private static void syncScoreboard(org.bukkit.entity.Player player, Scoreboard board) {
        org.bukkit.scoreboard.Scoreboard nativeBoard = NATIVE_SCOREBOARDS.get(player.getUniqueId());
        if (nativeBoard == null) return;
        Set<String> wantedTeams = new HashSet<>();
        for (Team team : board.getTeams()) {
            wantedTeams.add(team.getName());
            org.bukkit.scoreboard.Team nativeTeam = nativeBoard.getTeam(team.getName());
            if (nativeTeam == null) nativeTeam = nativeBoard.registerNewTeam(team.getName());
            if (!nativeTeam.getPrefix().equals(team.getPrefix())) nativeTeam.setPrefix(team.getPrefix());
            if (!nativeTeam.getSuffix().equals(team.getSuffix())) nativeTeam.setSuffix(team.getSuffix());
            for (String entry : Set.copyOf(nativeTeam.getEntries())) {
                if (!team.getEntries().contains(entry)) nativeTeam.removeEntry(entry);
            }
            for (String entry : team.getEntries()) if (!nativeTeam.hasEntry(entry)) nativeTeam.addEntry(entry);
        }
        for (org.bukkit.scoreboard.Team nativeTeam : Set.copyOf(nativeBoard.getTeams())) {
            if (!wantedTeams.contains(nativeTeam.getName())) nativeTeam.unregister();
        }

        Set<String> wantedObjectives = new HashSet<>();
        Set<String> wantedScores = new HashSet<>();
        for (Objective objective : board.getObjectives()) {
            wantedObjectives.add(objective.getName());
            org.bukkit.scoreboard.Objective nativeObjective = nativeBoard.getObjective(objective.getName());
            if (nativeObjective == null)
                nativeObjective = nativeBoard.registerNewObjective(objective.getName(), "dummy", objective.getTitle());
            else if (!nativeObjective.getDisplayName().equals(objective.getTitle()))
                nativeObjective.setDisplayName(objective.getTitle());
            if (objective.getDisplaySlot() == DisplaySlot.SIDEBAR) {
                nativeObjective.setDisplaySlot(org.bukkit.scoreboard.DisplaySlot.SIDEBAR);
            } else if (nativeObjective.getDisplaySlot() != null) {
                nativeObjective.setDisplaySlot(null);
            }
            for (var scoreEntry : objective.getScores().entrySet()) {
                wantedScores.add(objective.getName() + '\0' + scoreEntry.getKey());
                Score nativeScore = nativeObjective.getScore(scoreEntry.getKey());
                if (!nativeScore.isScoreSet() || nativeScore.getScore() != scoreEntry.getValue().getScore()) {
                    nativeScore.setScore(scoreEntry.getValue().getScore());
                }
            }
        }
        for (org.bukkit.scoreboard.Objective nativeObjective : Set.copyOf(nativeBoard.getObjectives())) {
            if (!wantedObjectives.contains(nativeObjective.getName())) {
                nativeObjective.unregister();
                continue;
            }
            for (String entry : Set.copyOf(nativeBoard.getEntries())) {
                Score score = nativeObjective.getScore(entry);
                if (score.isScoreSet() && !wantedScores.contains(nativeObjective.getName() + '\0' + entry))
                    score.resetScore();
            }
        }
    }

    public static void clearPlayerState(org.bukkit.entity.Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        Scoreboard board = PLAYER_SCOREBOARDS.remove(uuid);
        Runnable listener = SCOREBOARD_LISTENERS.remove(uuid);
        if (board != null && listener != null) {
            board.removeChangeListener(listener);
        }
        NATIVE_SCOREBOARDS.remove(uuid);
        ENTITIES.remove(uuid);
        SNEAK_OVERRIDES.remove(uuid);
        VIEW_OVERRIDES.remove(uuid);
    }

    public static void setSneakOverride(org.bukkit.entity.Player player, Boolean sneaking) {
        if (player == null) return;
        if (sneaking == null) SNEAK_OVERRIDES.remove(player.getUniqueId());
        else SNEAK_OVERRIDES.put(player.getUniqueId(), sneaking);
    }

    public static void setViewOverride(org.bukkit.entity.Player player, Double eyeX, Double eyeY, Double eyeZ,
                                       Float yaw, Float pitch) {
        if (player == null) return;
        if (eyeX == null || eyeY == null || eyeZ == null || yaw == null || pitch == null) {
            VIEW_OVERRIDES.remove(player.getUniqueId());
        } else {
            VIEW_OVERRIDES.put(player.getUniqueId(), new CapturedInputPose(eyeX, eyeY, eyeZ, yaw, pitch));
        }
    }

    private static org.bukkit.Location viewLocation(org.bukkit.entity.Player player, org.bukkit.Location location,
                                                    boolean eyeLocation) {
        CapturedInputPose override = VIEW_OVERRIDES.get(player.getUniqueId());
        if (override == null) {
            override = PaperPredictionServer.capturedEffectPose(player);
        }
        if (override == null) {
            org.bukkit.Location claimed = PaperPredictionServer.claimedEffectLocation(player);
            if (claimed == null) return location;
            if (eyeLocation) claimed.add(0, player.getEyeHeight(), 0);
            claimed.setYaw(location.getYaw());
            claimed.setPitch(location.getPitch());
            return claimed;
        }
        return applyViewOverride(location, override.eyeX(), override.eyeY(), override.eyeZ(),
                override.yaw(), override.pitch(), eyeLocation);
    }

    static org.bukkit.Location applyViewOverride(org.bukkit.Location location,
                                                 double eyeX, double eyeY, double eyeZ,
                                                 float yaw, float pitch, boolean eyeLocation) {
        org.bukkit.Location adjusted = location.clone();
        adjusted.setX(eyeX);
        adjusted.setZ(eyeZ);
        adjusted.setY(new CapturedInputPose(eyeX, eyeY, eyeZ, yaw, pitch).locationY(location.getY(), eyeLocation));
        adjusted.setYaw(yaw);
        adjusted.setPitch(pitch);
        return adjusted;
    }

    public static Material material(final org.bukkit.Material value) {
        if (value == null) return null;
        try {
            return Material.valueOf(value.name());
        } catch (IllegalArgumentException ignored) {
            return Material.AIR;
        }
    }

    public static org.bukkit.Material material(final Material value) {
        return value == null ? org.bukkit.Material.AIR : org.bukkit.Material.matchMaterial(value.name());
    }

    public static Vector vector(final org.bukkit.util.Vector value) {
        return new Vector(value.getX(), value.getY(), value.getZ());
    }

    public static org.bukkit.util.Vector vector(final Vector value) {
        return new org.bukkit.util.Vector(value.getX(), value.getY(), value.getZ());
    }

    public static Location location(final org.bukkit.Location value) {
        return value == null ? null : new LocationView(value);
    }

    public static World world(final org.bukkit.World value) {
        return value == null ? null : WORLDS.computeIfAbsent(value.getUID(), ignored -> new WorldView(value));
    }

    public static Block block(final org.bukkit.block.Block value) {
        return value == null ? null : BLOCKS.computeIfAbsent(BlockKey.of(value), ignored -> new BlockView(value));
    }

    public static Player player(final org.bukkit.entity.Player value) {
        return value == null ? null : (Player) ENTITIES.computeIfAbsent(value.getUniqueId(), ignored -> new PlayerView(value));
    }

    public static OfflinePlayer offline(final org.bukkit.OfflinePlayer value) {
        return value instanceof org.bukkit.entity.Player p ? player(p) : value == null ? null : OFFLINE_PLAYERS.computeIfAbsent(value.getUniqueId(), ignored -> new OfflineView(value));
    }

    public static LivingEntity living(final org.bukkit.entity.LivingEntity value) {
        if (value instanceof org.bukkit.entity.ArmorStand armorStand) return armorStand(armorStand);
        return value instanceof org.bukkit.entity.Player p ? player(p) : value == null ? null : (LivingEntity) ENTITIES.computeIfAbsent(value.getUniqueId(), ignored -> new LivingView(value));
    }

    public static Entity entity(final org.bukkit.entity.Entity value) {
        if (value instanceof org.bukkit.entity.Player p) return player(p);
        if (value instanceof org.bukkit.entity.LivingEntity living) return living(living);
        if (value instanceof org.bukkit.entity.FallingBlock falling) return falling(falling);
        if (value instanceof org.bukkit.entity.ShulkerBullet bullet) return shulkerBullet(bullet);
        if (value instanceof org.bukkit.entity.Arrow arrow) return arrow(arrow);
        if (value instanceof org.bukkit.entity.Item item) return itemEntity(item);
        return value == null ? null : ENTITIES.computeIfAbsent(value.getUniqueId(), ignored -> new EntityView(value));
    }

    public static FallingBlock falling(final org.bukkit.entity.FallingBlock value) {
        return value == null ? null : (FallingBlock) ENTITIES.computeIfAbsent(value.getUniqueId(), ignored -> new FallingView(value));
    }

    private static Arrow arrow(final org.bukkit.entity.Arrow value) {
        return value == null ? null : (Arrow) ENTITIES.computeIfAbsent(value.getUniqueId(), ignored -> new ArrowView(value));
    }

    private static ShulkerBullet shulkerBullet(final org.bukkit.entity.ShulkerBullet value) {
        return value == null ? null : (ShulkerBullet) ENTITIES.computeIfAbsent(value.getUniqueId(), ignored -> new ShulkerBulletView(value));
    }

    private static Item itemEntity(final org.bukkit.entity.Item value) {
        return value == null ? null : (Item) ENTITIES.computeIfAbsent(value.getUniqueId(), ignored -> new DroppedItemView(value));
    }

    private static ArmorStand armorStand(final org.bukkit.entity.ArmorStand value) {
        return value == null ? null : (ArmorStand) ENTITIES.computeIfAbsent(value.getUniqueId(), ignored -> new ArmorStandView(value));
    }

    public static ItemStack item(final org.bukkit.inventory.ItemStack value) {
        return value == null ? null : new ItemView(value);
    }

    public static List<ItemStack> items(final List<org.bukkit.inventory.ItemStack> values) {
        return new AbstractList<>() {
            @Override
            public ItemStack get(int index) {
                return item(values.get(index));
            }

            @Override
            public int size() {
                return values.size();
            }

            @Override
            public ItemStack set(int index, ItemStack value) {
                return item(values.set(index, itemHandle(value)));
            }

            @Override
            public void add(int index, ItemStack value) {
                values.add(index, itemHandle(value));
            }

            @Override
            public ItemStack remove(int index) {
                return item(values.remove(index));
            }
        };
    }

    public static org.bukkit.inventory.ItemStack itemHandle(final ItemStack value) {
        if (value instanceof ItemView view) return view.value;
        if (value == null || value.getType() == null || value.getType() == Material.AIR || value.getAmount() <= 0) {
            return null;
        }
        org.bukkit.inventory.ItemStack stack = new org.bukkit.inventory.ItemStack(material(value.getType()), value.getAmount());
        if (value.hasItemMeta()) {
            ItemMeta commonMeta = value.getItemMeta();
            org.bukkit.inventory.meta.ItemMeta nativeMeta = stack.getItemMeta();
            if (commonMeta instanceof LeatherArmorMeta leatherMeta
                    && nativeMeta instanceof org.bukkit.inventory.meta.LeatherArmorMeta nativeLeather) {
                nativeLeather.setColor(colorHandle(leatherMeta.getColor()));
                stack.setItemMeta(nativeLeather);
            } else if (commonMeta instanceof PotionMeta potionMeta
                    && nativeMeta instanceof org.bukkit.inventory.meta.PotionMeta nativePotion
                    && potionMeta.getBasePotionData() != null
                    && PotionType.WATER.equals(
                    potionMeta.getBasePotionData().getType())) {
                nativePotion.setBasePotionType(org.bukkit.potion.PotionType.WATER);
                stack.setItemMeta(nativePotion);
            }
        }
        return stack;
    }

    private static org.bukkit.potion.PotionEffect potionEffectHandle(final PotionEffect value) {
        org.bukkit.potion.PotionEffectType type = potionEffectTypeHandle(value.getType());
        if (type == null) type = org.bukkit.potion.PotionEffectType.SPEED;
        return new org.bukkit.potion.PotionEffect(type, value.getDuration(), value.getAmplifier());
    }

    private static org.bukkit.potion.PotionEffectType potionEffectTypeHandle(final PotionEffectType value) {
        if (value == null) return null;
        final String key = value.name().trim().toUpperCase(Locale.ROOT);
        org.bukkit.potion.PotionEffectType type = org.bukkit.potion.PotionEffectType.getByName(key);
        if (type != null) return type;
        try {
            return org.bukkit.potion.PotionEffectType.getByKey(NamespacedKey.minecraft(key.toLowerCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static PotionEffect potionEffect(final org.bukkit.potion.PotionEffect value) {
        if (value == null) return null;
        return new PotionEffect(
                PotionEffectType.valueOf(value.getType().getName()),
                value.getDuration(),
                value.getAmplifier());
    }

    private static ItemStack[] items(final org.bukkit.inventory.ItemStack[] values) {
        return Arrays.stream(values).map(BukkitMC::item).toArray(ItemStack[]::new);
    }

    private static org.bukkit.inventory.ItemStack[] itemHandles(final ItemStack[] values) {
        return Arrays.stream(values).map(BukkitMC::itemHandle).toArray(org.bukkit.inventory.ItemStack[]::new);
    }

    public static org.bukkit.block.data.BlockData blockDataHandle(final BlockData value) {
        org.bukkit.Material material = material(value == null ? Material.AIR : value.getMaterial());
        org.bukkit.block.data.BlockData nativeData = material.createBlockData();
        if (value instanceof Levelled levelled) {
            if (nativeData instanceof org.bukkit.block.data.Levelled nativeLevelled) {
                nativeLevelled.setLevel(Math.max(0, Math.min(nativeLevelled.getMaximumLevel(), levelled.getLevel())));
            }
            if (nativeData instanceof Waterlogged waterlogged) {
                waterlogged.setWaterlogged(levelled.isWaterlogged());
            }
        }
        return nativeData;
    }

    public static BlockData blockData(final org.bukkit.block.data.BlockData value) {
        BlockData data = material(value.getMaterial()).createBlockData();
        if (data instanceof Levelled levelled) {
            if (value instanceof org.bukkit.block.data.Levelled nativeLevelled) {
                levelled.setLevel(nativeLevelled.getLevel());
            }
            if (value instanceof Waterlogged waterlogged) {
                levelled.setWaterlogged(waterlogged.isWaterlogged());
            }
        }
        return data;
    }

    private static org.bukkit.Color colorHandle(final Color color) {
        Color safe = color == null ? Color.WHITE : color;
        return org.bukkit.Color.fromRGB(safe.getRed(), safe.getGreen(), safe.getBlue());
    }

    private static Color color(final org.bukkit.Color color) {
        org.bukkit.Color safe = color == null ? org.bukkit.Color.WHITE : color;
        return Color.fromRGB(safe.getRed(), safe.getGreen(), safe.getBlue());
    }

    private static org.bukkit.Particle particleHandle(final Particle particle) {
        if (particle == null) return org.bukkit.Particle.CLOUD;
        final String key = particle.name().trim().toUpperCase(Locale.ROOT);
        try {
            return org.bukkit.Particle.valueOf(switch (key) {
                case "BLOCK_CRACK", "BLOCK_DUST" -> "BLOCK";
                case "CRIT_MAGIC", "MAGIC_CRIT" -> "ENCHANTED_HIT";
                case "DRIP_LAVA" -> "DRIPPING_LAVA";
                case "DRIP_WATER" -> "DRIPPING_WATER";
                case "ENCHANTMENT_TABLE" -> "ENCHANT";
                case "EXPLOSION_HUGE", "HUGE_EXPLOSION" -> "EXPLOSION_EMITTER";
                case "EXPLOSION_LARGE", "LARGE_EXPLODE" -> "EXPLOSION";
                case "EXPLOSION_NORMAL", "EXPLODE" -> "POOF";
                case "FIREWORKS_SPARK" -> "FIREWORK";
                case "REDSTONE", "RED_DUST" -> "DUST";
                case "SLIME" -> "ITEM_SLIME";
                case "SMOKE_NORMAL", "SMOKE" -> "SMOKE";
                case "SMOKE_LARGE", "LARGE_SMOKE" -> "LARGE_SMOKE";
                case "SNOW_SHOVEL" -> "BLOCK";
                case "SNOWBALL", "SNOWBALL_PROOF" -> "ITEM_SNOWBALL";
                case "SPELL" -> "EFFECT";
                case "SPELL_INSTANT", "INSTANT_SPELL" -> "INSTANT_EFFECT";
                case "SPELL_MOB", "MOB_SPELL", "SPELL_MOB_AMBIENT", "MOB_SPELL_AMBIENT" -> "ENTITY_EFFECT";
                case "SPELL_WITCH", "WITCH_SPELL" -> "WITCH";
                case "TOTEM" -> "TOTEM_OF_UNDYING";
                case "TOWN_AURA" -> "MYCELIUM";
                case "VILLAGER_ANGRY", "ANGRY_VILLAGER" -> "ANGRY_VILLAGER";
                case "VILLAGER_HAPPY", "HAPPY_VILLAGER" -> "HAPPY_VILLAGER";
                case "WATER_BUBBLE", "BUBBLE" -> "BUBBLE";
                case "WATER_DROP" -> "RAIN";
                case "WATER_SPLASH", "SPLASH" -> "SPLASH";
                case "WATER_WAKE", "WAKE" -> "FISHING";
                case "ITEM_CRACK" -> "ITEM";
                default -> key;
            });
        } catch (IllegalArgumentException ignored) {
            return org.bukkit.Particle.CLOUD;
        }
    }

    private static Object particleDataHandle(final Particle particle, final Object data) {
        final org.bukkit.Particle nativeParticle = particleHandle(particle);
        final Class<?> dataType = nativeParticle.getDataType();
        if (data instanceof Particle.DustOptions dust) {
            if (dataType == org.bukkit.Particle.DustOptions.class) {
                return new org.bukkit.Particle.DustOptions(colorHandle(dust.getColor()), dust.getSize());
            }
            if (dataType == org.bukkit.Color.class) {
                return colorHandle(dust.getColor());
            }
        }
        if (data instanceof Color color) {
            if (dataType == org.bukkit.Particle.Spell.class) {
                return new org.bukkit.Particle.Spell(colorHandle(color), 1.0F);
            }
            if (dataType == org.bukkit.Color.class) {
                return colorHandle(color);
            }
        }
        if (data instanceof Particle.Spell spell) {
            if (dataType == org.bukkit.Particle.Spell.class) {
                return new org.bukkit.Particle.Spell(colorHandle(spell.getColor()), spell.getPower());
            }
            if (dataType == org.bukkit.Color.class) {
                return colorHandle(spell.getColor());
            }
        }
        if (data instanceof BlockData blockData) {
            Object nativeData = blockDataHandle(blockData);
            if (dataType.isInstance(nativeData)) {
                return nativeData;
            }
        }
        if (data instanceof ItemStack stack) {
            Object nativeData = itemHandle(stack);
            if (dataType.isInstance(nativeData)) {
                return nativeData;
            }
        }
        if (data != null && dataType.isInstance(data)) {
            return data;
        }
        if (dataType == org.bukkit.Color.class) {
            return org.bukkit.Color.WHITE;
        }
        if (dataType == org.bukkit.Particle.Spell.class) {
            return new org.bukkit.Particle.Spell(org.bukkit.Color.WHITE, 1.0F);
        }
        return null;
    }

    public static EntityDamageEvent damageEvent(final org.bukkit.event.entity.EntityDamageEvent value) {
        return new DamageEventView(value);
    }

    public static org.bukkit.entity.Player playerHandle(final Player value) {
        return (org.bukkit.entity.Player) value.handle();
    }

    public static org.bukkit.entity.Entity entityHandle(final Entity value) {
        return (org.bukkit.entity.Entity) value.handle();
    }

    private static Object projectileSource(final ProjectileSource source) {
        if (source instanceof org.bukkit.entity.Player player) return player(player);
        if (source instanceof org.bukkit.entity.LivingEntity living) return living(living);
        if (source instanceof org.bukkit.entity.Entity entity) return entity(entity);
        return source;
    }

    public static org.bukkit.Location locationHandle(final Location value) {
        if (value == null) return null;
        if (value.handle() instanceof org.bukkit.Location location) return location;
        return new org.bukkit.Location(value.getWorld() == null ? null : (org.bukkit.World) value.getWorld().handle(), value.getX(), value.getY(), value.getZ(), value.getYaw(), value.getPitch());
    }

    private record BlockKey(UUID world, int x, int y, int z) {
        private static BlockKey of(final org.bukkit.block.Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    private static final class LocationView extends Location {
        private final org.bukkit.Location value;

        private LocationView(org.bukkit.Location value) {
            this.value = value;
        }

        @Override
        public World getWorld() {
            return world(value.getWorld());
        }

        @Override
        public double getX() {
            return value.getX();
        }

        @Override
        public void setX(double x) {
            value.setX(x);
        }

        @Override
        public double getY() {
            return value.getY();
        }

        @Override
        public void setY(double y) {
            value.setY(y);
        }

        @Override
        public double getZ() {
            return value.getZ();
        }

        @Override
        public void setZ(double z) {
            value.setZ(z);
        }

        @Override
        public float getYaw() {
            return value.getYaw();
        }

        @Override
        public void setYaw(float yaw) {
            value.setYaw(yaw);
        }

        @Override
        public float getPitch() {
            return value.getPitch();
        }

        @Override
        public void setPitch(float pitch) {
            value.setPitch(pitch);
        }

        @Override
        public Vector getDirection() {
            return vector(value.getDirection());
        }

        @Override
        public Block getBlock() {
            return block(value.getBlock());
        }

        @Override
        public Location add(double x, double y, double z) {
            value.add(x, y, z);
            return this;
        }

        @Override
        public Location subtract(double x, double y, double z) {
            value.subtract(x, y, z);
            return this;
        }

        @Override
        public Location multiply(double amount) {
            value.multiply(amount);
            return this;
        }

        @Override
        public Location setDirection(Vector direction) {
            value.setDirection(vector(direction));
            return this;
        }

        @Override
        public double distanceSquared(Location other) {
            return value.distanceSquared(locationHandle(other));
        }

        @Override
        public Vector toVector() {
            return vector(value.toVector());
        }

        @Override
        public Location clone() {
            return location(value.clone());
        }

        @Override
        public Object handle() {
            return value;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof LocationView view
                    && Double.compare(value.getX(), view.value.getX()) == 0
                    && Double.compare(value.getY(), view.value.getY()) == 0
                    && Double.compare(value.getZ(), view.value.getZ()) == 0
                    && Float.compare(value.getYaw(), view.value.getYaw()) == 0
                    && Float.compare(value.getPitch(), view.value.getPitch()) == 0
                    && value.getWorld().getUID().equals(view.value.getWorld().getUID());
        }

        @Override
        public int hashCode() {
            return Objects.hash(value.getWorld().getUID(), value.getX(), value.getY(), value.getZ(), value.getYaw(), value.getPitch());
        }
    }

    private static final class WorldView extends World {
        private final org.bukkit.World value;

        private WorldView(org.bukkit.World value) {
            this.value = value;
        }

        @Override
        public String getName() {
            return value.getName();
        }

        @Override
        public long getTime() {
            return value.getTime();
        }

        @Override
        public long getFullTime() {
            return value.getFullTime();
        }

        @Override
        public int getMinHeight() {
            return value.getMinHeight();
        }

        @Override
        public int getMaxHeight() {
            return value.getMaxHeight();
        }

        @Override
        public void playSound(Location location, Sound sound, float volume, float pitch) {
            org.bukkit.Location nativeLocation = locationHandle(location);
            org.bukkit.Sound nativeSound = org.bukkit.Sound.valueOf(sound.name());
            playForPredictionViewers(() -> value.playSound(nativeLocation, nativeSound, volume, pitch),
                    player -> player.playSound(nativeLocation, nativeSound, volume, pitch));
        }

        @Override
        public void playSound(Location location, String sound, float volume, float pitch) {
            org.bukkit.Location nativeLocation = locationHandle(location);
            playForPredictionViewers(() -> value.playSound(nativeLocation, sound, volume, pitch),
                    player -> player.playSound(nativeLocation, sound, volume, pitch));
        }

        @Override
        public void playSound(Location location, Sound sound, SoundCategory category, float volume, float pitch) {
            org.bukkit.Location nativeLocation = locationHandle(location);
            org.bukkit.Sound nativeSound = org.bukkit.Sound.valueOf(sound.name());
            org.bukkit.SoundCategory nativeCategory = org.bukkit.SoundCategory.valueOf(category.name());
            playForPredictionViewers(() -> value.playSound(nativeLocation, nativeSound, nativeCategory, volume, pitch),
                    player -> player.playSound(nativeLocation, nativeSound, nativeCategory, volume, pitch));
        }

        private void playForPredictionViewers(Runnable broadcast,
                                              Consumer<org.bukkit.entity.Player> sound) {
            org.bukkit.entity.Player excluded = PaperPredictionServer.predictedSoundEffectOwner();
            if (excluded == null || excluded.getWorld() != value) {
                broadcast.run();
                return;
            }
            for (org.bukkit.entity.Player viewer : value.getPlayers()) {
                if (viewer != excluded) sound.accept(viewer);
            }
        }

        @Override
        public <T> void spawnParticle(Particle particle, Location location, int count, double ox, double oy, double oz, double extra, T data) {
            org.bukkit.Location nativeLocation = locationHandle(location);
            org.bukkit.Particle nativeParticle = particleHandle(particle);
            Object nativeData = particleDataHandle(particle, data);
            org.bukkit.entity.Player excluded = PaperPredictionServer.predictedEffectOwner();
            if (excluded == null || excluded.getWorld() != value)
                value.spawnParticle(nativeParticle, nativeLocation, count, ox, oy, oz, extra, nativeData);
            else
                value.getPlayers().stream().filter(player -> player != excluded).forEach(player -> player.spawnParticle(nativeParticle, nativeLocation, count, ox, oy, oz, extra, nativeData));
        }

        @Override
        public void spawnParticle(Particle particle, Location location, int count, double ox, double oy, double oz, double extra) {
            org.bukkit.Location nativeLocation = locationHandle(location);
            org.bukkit.Particle nativeParticle = particleHandle(particle);
            org.bukkit.entity.Player excluded = PaperPredictionServer.predictedEffectOwner();
            if (excluded == null || excluded.getWorld() != value)
                value.spawnParticle(nativeParticle, nativeLocation, count, ox, oy, oz, extra);
            else
                value.getPlayers().stream().filter(player -> player != excluded).forEach(player -> player.spawnParticle(nativeParticle, nativeLocation, count, ox, oy, oz, extra));
        }

        @Override
        public void spawnParticle(Particle particle, Location location, int count, double ox, double oy, double oz) {
            org.bukkit.Location nativeLocation = locationHandle(location);
            org.bukkit.Particle nativeParticle = particleHandle(particle);
            org.bukkit.entity.Player excluded = PaperPredictionServer.predictedEffectOwner();
            if (excluded == null || excluded.getWorld() != value)
                value.spawnParticle(nativeParticle, nativeLocation, count, ox, oy, oz);
            else
                value.getPlayers().stream().filter(player -> player != excluded).forEach(player -> player.spawnParticle(nativeParticle, nativeLocation, count, ox, oy, oz));
        }

        @Override
        public Collection<Entity> getNearbyEntities(BoundingBox box, Predicate<Entity> filter) {
            org.bukkit.util.BoundingBox nativeBox = new org.bukkit.util.BoundingBox(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ());
            Map<UUID, Entity> result = new LinkedHashMap<>();
            value.getNearbyEntities(nativeBox).stream().map(BukkitMC::entity).filter(Objects::nonNull)
                    .filter(entity -> filter == null || filter.test(entity)).forEach(entity -> result.put(entity.getUniqueId(), entity));
            PaperPredictionServer.augmentNearbyPlayers(value, nativeBox,
                    AbilityExecutionContext.current(), result);
            return result.values();
        }

        @Override
        public Item dropItem(Location location, ItemStack item) {
            return itemEntity(value.dropItem(locationHandle(location), itemHandle(item)));
        }

        @Override
        public Item dropItemNaturally(Location location, ItemStack item) {
            return itemEntity(value.dropItemNaturally(locationHandle(location), itemHandle(item)));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T spawn(Location location, Class<T> type) {
            if (type == BlockDisplay.class) {
                org.bukkit.entity.BlockDisplay display = value.spawn(locationHandle(location), org.bukkit.entity.BlockDisplay.class);
                return (T) new BlockDisplayView(display);
            }
            if (type == AreaEffectCloud.class) {
                org.bukkit.entity.AreaEffectCloud cloud = value.spawn(locationHandle(location), org.bukkit.entity.AreaEffectCloud.class);
                return (T) new AreaEffectCloudView(cloud);
            }
            if (type == ShulkerBullet.class) {
                org.bukkit.entity.ShulkerBullet bullet = value.spawn(locationHandle(location), org.bukkit.entity.ShulkerBullet.class);
                return (T) shulkerBullet(bullet);
            }
            if (type == ArmorStand.class) {
                org.bukkit.entity.ArmorStand armorStand = value.spawn(locationHandle(location), org.bukkit.entity.ArmorStand.class);
                return (T) armorStand(armorStand);
            }
            return super.spawn(location, type);
        }

        @Override
        public FallingBlock spawnFallingBlock(Location location, BlockData data) {
            return falling(value.spawnFallingBlock(locationHandle(location), blockDataHandle(data)));
        }

        @Override
        public Arrow spawnArrow(Location location, Vector direction, float speed, float spread) {
            return arrow(value.spawnArrow(locationHandle(location), vector(direction), speed, spread));
        }

        @Override
        public void strikeLightningEffect(Location location, boolean flash) {
            value.strikeLightningEffect(locationHandle(location));
        }

        @Override
        public RayTraceResult rayTraceBlocks(Location origin, Vector direction, double range, FluidCollisionMode mode, boolean ignorePassable) {
            org.bukkit.FluidCollisionMode nativeMode = mode != null && !mode.name().equals("NEVER") ? org.bukkit.FluidCollisionMode.ALWAYS : org.bukkit.FluidCollisionMode.NEVER;
            org.bukkit.util.RayTraceResult hit = value.rayTraceBlocks(locationHandle(origin), vector(direction), range, nativeMode, ignorePassable);
            if (hit == null || hit.getHitPosition() == null) return null;
            return new RayTraceResult(vector(hit.getHitPosition()));
        }

        @Override
        public RayTraceResult rayTrace(Location origin, Vector direction, double range, FluidCollisionMode mode, boolean ignorePassable, double raySize, Predicate<Entity> filter) {
            org.bukkit.FluidCollisionMode nativeMode = mode != null && !mode.name().equals("NEVER") ? org.bukkit.FluidCollisionMode.ALWAYS : org.bukkit.FluidCollisionMode.NEVER;
            Predicate<org.bukkit.entity.Entity> nativeFilter = nativeEntity -> filter == null || filter.test(entity(nativeEntity));
            org.bukkit.util.RayTraceResult hit = value.rayTrace(locationHandle(origin), vector(direction), range, nativeMode, ignorePassable, raySize, nativeFilter);
            if (hit == null || hit.getHitPosition() == null) return null;
            return new RayTraceResult(vector(hit.getHitPosition()), entity(hit.getHitEntity()));
        }

        @Override
        public boolean createExplosion(Location location, float power) {
            return value.createExplosion(locationHandle(location), power);
        }

        @Override
        public boolean hasStorm() {
            return value.hasStorm();
        }

        @Override
        public Block getHighestBlockAt(Location location) {
            return block(value.getHighestBlockAt(locationHandle(location)));
        }

        @Override
        public double getTemperature(int x, int y, int z) {
            return value.getTemperature(x, y, z);
        }

        @Override
        public double getHumidity(int x, int y, int z) {
            return value.getHumidity(x, y, z);
        }

        @Override
        public Object handle() {
            return value;
        }

        @Override
        public Block getBlockAt(int x, int y, int z) {
            return block(value.getBlockAt(x, y, z));
        }

        @Override
        public boolean isChunkLoaded(int x, int z) {
            return value.isChunkLoaded(x, z);
        }

        @Override
        public Collection<Player> getPlayers() {
            return value.getPlayers().stream().map(BukkitMC::player).toList();
        }

        @Override
        public List<Entity> getEntities() {
            return value.getEntities().stream().map(BukkitMC::entity).toList();
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof World world)) {
                return false;
            }
            if (world.handle() instanceof org.bukkit.World nativeWorld) {
                return value.getUID().equals(nativeWorld.getUID());
            }
            return value.getName().equals(world.getName());
        }

        @Override
        public int hashCode() {
            return value.getUID().hashCode();
        }
    }

    private static final class BlockView extends Block {
        private final org.bukkit.block.Block value;

        private BlockView(org.bukkit.block.Block value) {
            this.value = value;
        }

        @Override
        public Material getType() {
            return material(value.getType());
        }

        @Override
        public void setType(Material type) {
            value.setType(material(type));
        }

        @Override
        public void setType(Material type, boolean physics) {
            value.setType(material(type), physics);
        }

        @Override
        public BlockData getBlockData() {
            return BukkitMC.blockData(value.getBlockData());
        }

        @Override
        public void setBlockData(BlockData data) {
            value.setBlockData(blockDataHandle(data));
        }

        @Override
        public void setBlockData(BlockData data, boolean physics) {
            value.setBlockData(blockDataHandle(data), physics);
        }

        @Override
        public BlockState getState() {
            return new BlockStateView(value.getState());
        }

        @Override
        public Location getLocation() {
            return location(value.getLocation());
        }

        @Override
        public World getWorld() {
            return world(value.getWorld());
        }

        @Override
        public Block getRelative(BlockFace face) {
            return block(value.getRelative(org.bukkit.block.BlockFace.valueOf(face.name())));
        }

        @Override
        public Block getRelative(BlockFace face, int distance) {
            return block(value.getRelative(org.bukkit.block.BlockFace.valueOf(face.name()), distance));
        }

        @Override
        public Block getRelative(int x, int y, int z) {
            return block(value.getRelative(x, y, z));
        }

        @Override
        public int getX() {
            return value.getX();
        }

        @Override
        public int getY() {
            return value.getY();
        }

        @Override
        public int getZ() {
            return value.getZ();
        }

        @Override
        public boolean isLiquid() {
            return value.isLiquid();
        }

        @Override
        public boolean isSolid() {
            return value.getType().isSolid();
        }

        @Override
        public boolean isPassable() {
            return value.isPassable();
        }

        @Override
        public boolean isEmpty() {
            return value.isEmpty();
        }

        @Override
        public byte getLightLevel() {
            return value.getLightLevel();
        }

        @Override
        public boolean breakNaturally() {
            return value.breakNaturally();
        }

        @Override
        public boolean breakNaturally(ItemStack item) {
            return value.breakNaturally(itemHandle(item));
        }

        @Override
        public Collection<ItemStack> getDrops() {
            return value.getDrops().stream().map(BukkitMC::item).toList();
        }

        @Override
        public BoundingBox getBoundingBox() {
            org.bukkit.util.BoundingBox b = value.getBoundingBox();
            return new BoundingBox(new Vector(b.getMinX(), b.getMinY(), b.getMinZ()), new Vector(b.getMaxX(), b.getMaxY(), b.getMaxZ()));
        }

        @Override
        @SuppressWarnings("deprecation")
        public byte getData() {
            return value.getData();
        }

        @Override
        public BlockFace getFace(Block block) {
            if (!(block instanceof BlockView other)) return null;
            org.bukkit.block.BlockFace face = value.getFace(other.value);
            return face == null ? null : BlockFace.valueOf(face.name());
        }

        @Override
        public Biome getBiome() {
            try {
                return Biome.valueOf(value.getBiome().name());
            } catch (IllegalArgumentException ex) {
                return Biome.DESERT;
            }
        }

        @Override
        public Object handle() {
            return value;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof BlockView view
                    && value.getX() == view.value.getX()
                    && value.getY() == view.value.getY()
                    && value.getZ() == view.value.getZ()
                    && value.getWorld().getUID().equals(view.value.getWorld().getUID());
        }

        @Override
        public int hashCode() {
            return Objects.hash(value.getWorld().getUID(), value.getX(), value.getY(), value.getZ());
        }
    }

    private static final class BlockStateView extends BlockState {
        private final org.bukkit.block.BlockState value;

        private BlockStateView(org.bukkit.block.BlockState value) {
            this.value = value;
        }

        @Override
        public Material getType() {
            return material(value.getType());
        }

        @Override
        public BlockData getBlockData() {
            return BukkitMC.blockData(value.getBlockData());
        }

        @Override
        public Block getBlock() {
            return block(value.getBlock());
        }

        @Override
        public Location getLocation() {
            return location(value.getLocation());
        }

        @Override
        public boolean update(boolean force, boolean physics) {
            return value.update(force, physics);
        }

        @Override
        public Object handle() {
            return value;
        }
    }

    private static class EntityView extends Entity {
        protected final org.bukkit.entity.Entity value;

        private EntityView(org.bukkit.entity.Entity value) {
            this.value = value;
        }

        @Override
        public UUID getUniqueId() {
            return value.getUniqueId();
        }

        @Override
        public Location getLocation() {
            org.bukkit.Location claimed = PaperPredictionServer.claimedEffectLocation(value);
            return location(claimed == null ? value.getLocation() : claimed);
        }

        @Override
        public World getWorld() {
            return world(value.getWorld());
        }

        @Override
        public Vector getVelocity() {
            return vector(value.getVelocity());
        }

        @Override
        public void setVelocity(Vector velocity) {
            value.setVelocity(vector(velocity));
        }

        @Override
        public boolean isDead() {
            return value.isDead();
        }

        @Override
        public boolean isValid() {
            return value.isValid();
        }

        @Override
        public void remove() {
            value.remove();
        }

        @Override
        public int getFireTicks() {
            return value.getFireTicks();
        }

        @Override
        public void setFireTicks(int ticks) {
            value.setFireTicks(ticks);
        }

        @Override
        public boolean teleport(Location target) {
            return value.teleport(locationHandle(target));
        }

        @Override
        public int getEntityId() {
            return value.getEntityId();
        }

        @Override
        public String getName() {
            return value.getName();
        }

        @Override
        public float getFallDistance() {
            return value.getFallDistance();
        }

        @Override
        public void setFallDistance(float distance) {
            value.setFallDistance(distance);
        }

        @Override
        public void setSilent(boolean silent) {
            value.setSilent(silent);
        }

        @Override
        public void setInvulnerable(boolean invulnerable) {
            value.setInvulnerable(invulnerable);
        }

        @Override
        public boolean isOnGround() {
            return value.isOnGround();
        }

        @Override
        public double getHeight() {
            return value.getHeight();
        }

        @Override
        public BoundingBox getBoundingBox() {
            org.bukkit.util.BoundingBox b = value.getBoundingBox();
            return new BoundingBox(new Vector(b.getMinX(), b.getMinY(), b.getMinZ()), new Vector(b.getMaxX(), b.getMaxY(), b.getMaxZ()));
        }

        @Override
        public Object handle() {
            return value;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Entity entity
                    && value.getUniqueId().equals(entity.getUniqueId());
        }

        @Override
        public int hashCode() {
            return value.getUniqueId().hashCode();
        }
    }

    private static class LivingView extends LivingEntity {
        protected final org.bukkit.entity.LivingEntity value;

        private LivingView(org.bukkit.entity.LivingEntity value) {
            this.value = value;
        }

        @Override
        public UUID getUniqueId() {
            return value.getUniqueId();
        }

        @Override
        public Object handle() {
            return value;
        }

        @Override
        public Location getLocation() {
            org.bukkit.Location claimed = PaperPredictionServer.claimedEffectLocation(value);
            return location(claimed == null ? value.getLocation() : claimed);
        }

        @Override
        public Location getEyeLocation() {
            return location(value.getEyeLocation());
        }

        @Override
        public double getHeight() {
            return value.getHeight();
        }

        @Override
        public World getWorld() {
            return world(value.getWorld());
        }

        @Override
        public Vector getVelocity() {
            return vector(value.getVelocity());
        }

        @Override
        public void setVelocity(Vector velocity) {
            value.setVelocity(vector(velocity));
        }

        @Override
        public boolean isDead() {
            return value.isDead();
        }

        @Override
        public boolean isValid() {
            return value.isValid();
        }

        @Override
        public void remove() {
            value.remove();
        }

        @Override
        public int getFireTicks() {
            return value.getFireTicks();
        }

        @Override
        public void setFireTicks(int ticks) {
            value.setFireTicks(ticks);
        }

        @Override
        public boolean teleport(Location target) {
            return value.teleport(locationHandle(target));
        }

        @Override
        public int getEntityId() {
            return value.getEntityId();
        }

        @Override
        public String getName() {
            return value.getName();
        }

        @Override
        public float getFallDistance() {
            return value.getFallDistance();
        }

        @Override
        public void setFallDistance(float distance) {
            value.setFallDistance(distance);
        }

        @Override
        public void setSilent(boolean silent) {
            value.setSilent(silent);
        }

        @Override
        public void setInvulnerable(boolean invulnerable) {
            value.setInvulnerable(invulnerable);
        }

        @Override
        public boolean isOnGround() {
            return value.isOnGround();
        }

        @Override
        public boolean addPassenger(Entity entity) {
            return value.addPassenger(entityHandle(entity));
        }

        @Override
        public List<Entity> getPassengers() {
            return value.getPassengers().stream().map(BukkitMC::entity).toList();
        }

        @Override
        public BoundingBox getBoundingBox() {
            org.bukkit.util.BoundingBox b = value.getBoundingBox();
            return new BoundingBox(new Vector(b.getMinX(), b.getMinY(), b.getMinZ()), new Vector(b.getMaxX(), b.getMaxY(), b.getMaxZ()));
        }

        @Override
        public double getHealth() {
            return value.getHealth();
        }

        @Override
        public void setHealth(double health) {
            value.setHealth(health);
        }

        @Override
        public double getMaxHealth() {
            return value.getMaxHealth();
        }

        @Override
        public void damage(double damage) {
            value.damage(damage);
        }

        @Override
        public void damage(double damage, Entity source) {
            value.damage(damage, entityHandle(source));
        }

        @Override
        public int getNoDamageTicks() {
            return value.getNoDamageTicks();
        }

        @Override
        public void setNoDamageTicks(int ticks) {
            value.setNoDamageTicks(ticks);
        }

        @Override
        public int getMaximumNoDamageTicks() {
            return value.getMaximumNoDamageTicks();
        }

        @Override
        public double getLastDamage() {
            return value.getLastDamage();
        }

        @Override
        public boolean hasPotionEffect(PotionEffectType type) {
            final org.bukkit.potion.PotionEffectType nativeType = potionEffectTypeHandle(type);
            return nativeType != null && value.hasPotionEffect(nativeType);
        }

        @Override
        public void addPotionEffect(PotionEffect effect) {
            value.addPotionEffect(potionEffectHandle(effect));
        }

        @Override
        public void addPotionEffects(Collection<PotionEffect> effects) {
            value.addPotionEffects(effects.stream().map(BukkitMC::potionEffectHandle).toList());
        }

        @Override
        public boolean addPotionEffect(PotionEffect effect, boolean force) {
            return value.addPotionEffect(potionEffectHandle(effect), force);
        }

        @Override
        public PotionEffect getPotionEffect(PotionEffectType type) {
            final org.bukkit.potion.PotionEffectType nativeType = potionEffectTypeHandle(type);
            return nativeType == null ? null : potionEffect(value.getPotionEffect(nativeType));
        }

        @Override
        public void removePotionEffect(PotionEffectType type) {
            final org.bukkit.potion.PotionEffectType nativeType = potionEffectTypeHandle(type);
            if (nativeType != null) value.removePotionEffect(nativeType);
        }

        @Override
        public Collection<PotionEffect> getActivePotionEffects() {
            return value.getActivePotionEffects().stream().map(BukkitMC::potionEffect).toList();
        }

        @Override
        public int getRemainingAir() {
            return value.getRemainingAir();
        }

        @Override
        public void setRemainingAir(int ticks) {
            value.setRemainingAir(ticks);
        }

        @Override
        public void setAI(boolean enabled) {
            value.setAI(enabled);
        }

        @Override
        public EntityEquipment getEquipment() {
            return new EquipmentView(value.getEquipment());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Entity entity
                    && value.getUniqueId().equals(entity.getUniqueId());
        }

        @Override
        public int hashCode() {
            return value.getUniqueId().hashCode();
        }
    }

    private static final class ArmorStandView extends ArmorStand {
        private final org.bukkit.entity.ArmorStand value;

        private ArmorStandView(org.bukkit.entity.ArmorStand value) {
            this.value = value;
        }

        @Override
        public UUID getUniqueId() {
            return value.getUniqueId();
        }

        @Override
        public Object handle() {
            return value;
        }

        @Override
        public Location getLocation() {
            return location(value.getLocation());
        }

        @Override
        public Location getEyeLocation() {
            return location(value.getEyeLocation());
        }

        @Override
        public World getWorld() {
            return world(value.getWorld());
        }

        @Override
        public Vector getVelocity() {
            return vector(value.getVelocity());
        }

        @Override
        public void setVelocity(Vector velocity) {
            value.setVelocity(vector(velocity));
        }

        @Override
        public boolean isDead() {
            return value.isDead();
        }

        @Override
        public boolean isValid() {
            return value.isValid();
        }

        @Override
        public void remove() {
            value.remove();
        }

        @Override
        public boolean teleport(Location target) {
            return value.teleport(locationHandle(target));
        }

        @Override
        public int getEntityId() {
            return value.getEntityId();
        }

        @Override
        public String getName() {
            return value.getName();
        }

        @Override
        public int getFireTicks() {
            return value.getFireTicks();
        }

        @Override
        public void setFireTicks(int ticks) {
            value.setFireTicks(ticks);
        }

        @Override
        public float getFallDistance() {
            return value.getFallDistance();
        }

        @Override
        public void setFallDistance(float distance) {
            value.setFallDistance(distance);
        }

        @Override
        public void setSilent(boolean silent) {
            value.setSilent(silent);
        }

        @Override
        public void setInvulnerable(boolean invulnerable) {
            value.setInvulnerable(invulnerable);
        }

        @Override
        public void setGravity(boolean gravity) {
            value.setGravity(gravity);
        }

        @Override
        public boolean isOnGround() {
            return value.isOnGround();
        }

        @Override
        public double getHeight() {
            return value.getHeight();
        }

        @Override
        public boolean isMarker() {
            return value.isMarker();
        }

        @Override
        public boolean isInvisible() {
            return value.isInvisible();
        }

        @Override
        public void setVisible(boolean visible) {
            value.setVisible(visible);
        }

        @Override
        public void setSmall(boolean small) {
            value.setSmall(small);
        }

        @Override
        public void setHelmet(ItemStack item) {
            value.getEquipment().setHelmet(itemHandle(item));
        }

        @Override
        public EntityEquipment getEquipment() {
            return new EquipmentView(value.getEquipment());
        }

        @Override
        public void setMetadata(String key, MetadataValue metadata) {
            value.setMetadata(key, new org.bukkit.metadata.FixedMetadataValue(Bukkit.getPluginManager().getPlugin("ProjectKorra"), metadata.value()));
        }

        @Override
        public boolean hasMetadata(String key) {
            return value.hasMetadata(key);
        }

        @Override
        public List<MetadataValue> getMetadata(String key) {
            return value.getMetadata(key).stream()
                    .map(metadata -> new FixedMetadataValue(metadata.getOwningPlugin(), metadata.value()))
                    .map(metadata -> (MetadataValue) metadata)
                    .toList();
        }

        @Override
        public void removeMetadata(String key, Object plugin) {
            value.removeMetadata(key, Bukkit.getPluginManager().getPlugin("ProjectKorra"));
        }

        @Override
        public BoundingBox getBoundingBox() {
            org.bukkit.util.BoundingBox b = value.getBoundingBox();
            return new BoundingBox(new Vector(b.getMinX(), b.getMinY(), b.getMinZ()), new Vector(b.getMaxX(), b.getMaxY(), b.getMaxZ()));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Entity entity && value.getUniqueId().equals(entity.getUniqueId());
        }

        @Override
        public int hashCode() {
            return value.getUniqueId().hashCode();
        }
    }

    private static final class DroppedItemView extends Item {
        private final org.bukkit.entity.Item value;

        private DroppedItemView(org.bukkit.entity.Item value) {
            this.value = value;
        }

        @Override
        public UUID getUniqueId() {
            return value.getUniqueId();
        }

        @Override
        public Object handle() {
            return value;
        }

        @Override
        public Location getLocation() {
            return location(value.getLocation());
        }

        @Override
        public World getWorld() {
            return world(value.getWorld());
        }

        @Override
        public Vector getVelocity() {
            return vector(value.getVelocity());
        }

        @Override
        public void setVelocity(Vector velocity) {
            value.setVelocity(vector(velocity));
        }

        @Override
        public boolean isDead() {
            return value.isDead();
        }

        @Override
        public boolean isValid() {
            return value.isValid();
        }

        @Override
        public void remove() {
            value.remove();
        }

        @Override
        public int getFireTicks() {
            return value.getFireTicks();
        }

        @Override
        public void setFireTicks(int ticks) {
            value.setFireTicks(ticks);
        }

        @Override
        public boolean teleport(Location target) {
            return value.teleport(locationHandle(target));
        }

        @Override
        public int getEntityId() {
            return value.getEntityId();
        }

        @Override
        public String getName() {
            return value.getName();
        }

        @Override
        public float getFallDistance() {
            return value.getFallDistance();
        }

        @Override
        public void setFallDistance(float distance) {
            value.setFallDistance(distance);
        }

        @Override
        public void setSilent(boolean silent) {
            value.setSilent(silent);
        }

        @Override
        public void setInvulnerable(boolean invulnerable) {
            value.setInvulnerable(invulnerable);
        }

        @Override
        public boolean isOnGround() {
            return value.isOnGround();
        }

        @Override
        public double getHeight() {
            return value.getHeight();
        }

        @Override
        public void setGravity(boolean gravity) {
            value.setGravity(gravity);
        }

        @Override
        public boolean hasMetadata(String key) {
            return value.hasMetadata(key);
        }

        @Override
        public void setMetadata(String key, MetadataValue metadata) {
            value.setMetadata(key, new org.bukkit.metadata.FixedMetadataValue(Bukkit.getPluginManager().getPlugin("ProjectKorra"), metadata.value()));
        }

        @Override
        public List<MetadataValue> getMetadata(String key) {
            return value.getMetadata(key).stream()
                    .map(metadata -> new FixedMetadataValue(metadata.getOwningPlugin(), metadata.value()))
                    .map(metadata -> (MetadataValue) metadata)
                    .toList();
        }

        @Override
        public void removeMetadata(String key, Object plugin) {
            value.removeMetadata(key, Bukkit.getPluginManager().getPlugin("ProjectKorra"));
        }

        @Override
        public ItemStack getItemStack() {
            return item(value.getItemStack());
        }

        @Override
        public void setItemStack(ItemStack item) {
            value.setItemStack(itemHandle(item));
        }

        @Override
        public void setPickupDelay(int ticks) {
            value.setPickupDelay(ticks);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Entity entity && value.getUniqueId().equals(entity.getUniqueId());
        }

        @Override
        public int hashCode() {
            return value.getUniqueId().hashCode();
        }
    }

    private static final class PlayerView extends Player {
        private final org.bukkit.entity.Player value;

        private PlayerView(org.bukkit.entity.Player value) {
            this.value = value;
        }

        @Override
        public Object handle() {
            return value;
        }

        @Override
        public String getName() {
            return value.getName();
        }

        @Override
        public UUID getUniqueId() {
            return value.getUniqueId();
        }

        @Override
        public Location getLocation() {
            return location(viewLocation(value, value.getLocation(), false));
        }

        @Override
        public Location getEyeLocation() {
            return location(viewLocation(value, value.getEyeLocation(), true));
        }

        @Override
        public World getWorld() {
            return world(value.getWorld());
        }

        @Override
        public Vector getVelocity() {
            return vector(value.getVelocity());
        }

        @Override
        public void setVelocity(Vector velocity) {
            value.setVelocity(vector(velocity));
        }

        @Override
        public boolean isValid() {
            return value.isValid();
        }

        @Override
        public void remove() {
            value.remove();
        }

        @Override
        public int getFireTicks() {
            return value.getFireTicks();
        }

        @Override
        public void setFireTicks(int ticks) {
            value.setFireTicks(ticks);
        }

        @Override
        public boolean teleport(Location target) {
            return value.teleport(locationHandle(target));
        }

        @Override
        public int getEntityId() {
            return value.getEntityId();
        }

        @Override
        public float getFallDistance() {
            return value.getFallDistance();
        }

        @Override
        public void setFallDistance(float distance) {
            value.setFallDistance(distance);
        }

        @Override
        public void setSilent(boolean silent) {
            value.setSilent(silent);
        }

        @Override
        public void setInvulnerable(boolean invulnerable) {
            value.setInvulnerable(invulnerable);
        }

        @Override
        public boolean isOnGround() {
            return value.isOnGround();
        }

        @Override
        public boolean addPassenger(Entity entity) {
            return value.addPassenger(entityHandle(entity));
        }

        @Override
        public List<Entity> getPassengers() {
            return value.getPassengers().stream().map(BukkitMC::entity).toList();
        }

        @Override
        public BoundingBox getBoundingBox() {
            org.bukkit.util.BoundingBox b = value.getBoundingBox();
            return new BoundingBox(new Vector(b.getMinX(), b.getMinY(), b.getMinZ()), new Vector(b.getMaxX(), b.getMaxY(), b.getMaxZ()));
        }

        @Override
        public Block getTargetBlockExact(int range) {
            org.bukkit.Location origin = viewLocation(value, value.getEyeLocation(), true);
            org.bukkit.util.RayTraceResult result = value.getWorld().rayTraceBlocks(origin,
                    origin.getDirection(), range, org.bukkit.FluidCollisionMode.NEVER, true);
            return result == null ? null : block(result.getHitBlock());
        }

        @Override
        public Block getTargetBlock(Set<Material> transparent, int range) {
            Set<Material> passThrough = transparent == null
                    ? new HashSet<>() : new HashSet<>(transparent);
            passThrough.add(Material.AIR);
            passThrough.add(Material.CAVE_AIR);
            passThrough.add(Material.VOID_AIR);

            org.bukkit.Location origin = viewLocation(value, value.getEyeLocation(), true);
            org.bukkit.util.Vector direction = origin.getDirection().normalize();
            org.bukkit.block.Block last = origin.getBlock();
            for (double distance = 0.0; distance <= Math.max(0, range); distance += 0.2) {
                org.bukkit.block.Block current = origin.clone().add(direction.clone().multiply(distance)).getBlock();
                last = current;
                if (!passThrough.contains(BukkitMC.material(current.getType()))) {
                    return block(current);
                }
            }
            return block(last);
        }

        @Override
        public void sendBlockChange(Location location, BlockData data) {
            value.sendBlockChange(locationHandle(location), blockDataHandle(data));
        }

        @Override
        public <T> void spawnParticle(Particle particle, Location location, int count, double ox, double oy, double oz, double extra, T data, boolean force) {
            if (value == PaperPredictionServer.predictedEffectOwner()) return;
            value.spawnParticle(particleHandle(particle), locationHandle(location), count, ox, oy, oz, extra, particleDataHandle(particle, data), force);
        }

        @Override
        public void playSound(Location location, Sound sound, float volume, float pitch) {
            if (value == PaperPredictionServer.predictedSoundEffectOwner()) return;
            value.playSound(locationHandle(location), org.bukkit.Sound.valueOf(sound.name()), volume, pitch);
        }

        @Override
        public boolean hasPermission(String permission) {
            return value.hasPermission(permission);
        }

        @Override
        public void sendMessage(String message) {
            value.sendMessage(message);
        }

        @Override
        public CommandSender.Spigot spigot() {
            return new CommandSender.Spigot((type, message) -> {
                if (type == ChatMessageType.ACTION_BAR) {
                    value.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                            TextComponent.fromLegacyText(message));
                } else {
                    value.sendMessage(message);
                }
            });
        }

        @Override
        public Scoreboard getScoreboard() {
            return PLAYER_SCOREBOARDS.getOrDefault(value.getUniqueId(), EMPTY_SCOREBOARD);
        }

        @Override
        public void setScoreboard(Scoreboard board) {
            BukkitMC.setScoreboard(value, board == null ? EMPTY_SCOREBOARD : board);
        }

        @Override
        public boolean isOnline() {
            return value.isOnline();
        }

        @Override
        public GameMode getGameMode() {
            return GameMode.valueOf(value.getGameMode().name());
        }

        @Override
        public boolean isSneaking() {
            return SNEAK_OVERRIDES.getOrDefault(value.getUniqueId(), value.isSneaking());
        }

        @Override
        public void setSneaking(boolean state) {
            value.setSneaking(state);
        }

        @Override
        public boolean isSprinting() {
            return value.isSprinting();
        }

        @Override
        public void setSprinting(boolean state) {
            value.setSprinting(state);
        }

        @Override
        public boolean isFlying() {
            return value.isFlying();
        }

        @Override
        public void setFlying(boolean state) {
            value.setFlying(state);
        }

        @Override
        public boolean getAllowFlight() {
            return value.getAllowFlight();
        }

        @Override
        public void setAllowFlight(boolean state) {
            value.setAllowFlight(state);
        }

        @Override
        public float getFlySpeed() {
            return value.getFlySpeed();
        }

        @Override
        public void setFlySpeed(float speed) {
            value.setFlySpeed(speed);
        }

        @Override
        public boolean isGliding() {
            return value.isGliding();
        }

        @Override
        public void setGliding(boolean gliding) {
            value.setGliding(gliding);
        }

        @Override
        public boolean getCanPickupItems() {
            return value.getCanPickupItems();
        }

        @Override
        public void setCanPickupItems(boolean pickup) {
            value.setCanPickupItems(pickup);
        }

        @Override
        public boolean isSwimming() {
            return value.isSwimming();
        }

        @Override
        public float getExp() {
            return value.getExp();
        }

        @Override
        public void setExp(float exp) {
            value.setExp(Math.max(0F, Math.min(1F, exp)));
        }

        @Override
        public double getAbsorptionAmount() {
            return value.getAbsorptionAmount();
        }

        @Override
        public void setAbsorptionAmount(double amount) {
            value.setAbsorptionAmount(amount);
        }

        @Override
        public boolean isOp() {
            return value.isOp();
        }

        @Override
        public boolean isGlowing() {
            return value.isGlowing();
        }

        @Override
        public void setGlowing(boolean glowing) {
            value.setGlowing(glowing);
        }

        @Override
        public boolean canSee(Player player) {
            return player != null && player.handle() instanceof org.bukkit.entity.Player nativePlayer && value.canSee(nativePlayer);
        }

        @Override
        public boolean hasLineOfSight(Entity entity) {
            return entity != null && entity.handle() instanceof org.bukkit.entity.Entity nativeEntity && value.hasLineOfSight(nativeEntity);
        }

        @Override
        public String getDisplayName() {
            return value.getDisplayName();
        }

        @Override
        public void setDisplayName(String name) {
            value.setDisplayName(name);
        }

        @Override
        public void playNote(Location location, Instrument instrument, Note note) {
            org.bukkit.Note.Tone tone = org.bukkit.Note.Tone.valueOf(note.getTone().name());
            value.playNote(locationHandle(location), org.bukkit.Instrument.valueOf(instrument.name()), new org.bukkit.Note(note.getOctave(), tone, note.isSharped()));
        }

        @Override
        public List<Block> getLastTwoTargetBlocks(Set<Material> transparent, int range) {
            Set<Material> passThrough = transparent == null
                    ? new HashSet<>() : new HashSet<>(transparent);
            passThrough.add(Material.AIR);
            passThrough.add(Material.CAVE_AIR);
            passThrough.add(Material.VOID_AIR);

            org.bukkit.Location origin = viewLocation(value, value.getEyeLocation(), true);
            org.bukkit.util.Vector direction = origin.getDirection().normalize();
            org.bukkit.block.Block previous = origin.getBlock();
            org.bukkit.block.Block current = previous;
            org.bukkit.block.Block lastBlock = previous;
            for (double distance = 0.0; distance <= Math.max(0, range); distance += 0.2) {
                org.bukkit.block.Block next = origin.clone().add(direction.clone().multiply(distance)).getBlock();
                if (next.equals(lastBlock)) {
                    continue;
                }
                lastBlock = next;
                previous = current;
                current = next;
                if (!passThrough.contains(BukkitMC.material(current.getType()))) {
                    break;
                }
            }
            return List.of(block(previous), block(current));
        }

        @Override
        public List<Entity> getNearbyEntities(double x, double y, double z) {
            return value.getNearbyEntities(x, y, z).stream().map(BukkitMC::entity).toList();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Projectile> T launchProjectile(Class<T> type) {
            if (type == Arrow.class) {
                return (T) arrow(value.launchProjectile(org.bukkit.entity.Arrow.class));
            }
            org.bukkit.entity.Projectile projectile = value.launchProjectile(org.bukkit.entity.Projectile.class);
            return (T) entity(projectile);
        }

        @Override
        public float getExhaustion() {
            return value.getExhaustion();
        }

        @Override
        public void setExhaustion(float exhaustion) {
            value.setExhaustion(exhaustion);
        }

        @Override
        public double getHealth() {
            return value.getHealth();
        }

        @Override
        public void setHealth(double health) {
            value.setHealth(health);
        }

        @Override
        public double getMaxHealth() {
            return value.getMaxHealth();
        }

        @Override
        public void damage(double damage) {
            value.damage(damage);
        }

        @Override
        public void damage(double damage, Entity source) {
            value.damage(damage, entityHandle(source));
        }

        @Override
        public int getNoDamageTicks() {
            return value.getNoDamageTicks();
        }

        @Override
        public void setNoDamageTicks(int ticks) {
            value.setNoDamageTicks(ticks);
        }

        @Override
        public int getMaximumNoDamageTicks() {
            return value.getMaximumNoDamageTicks();
        }

        @Override
        public double getLastDamage() {
            return value.getLastDamage();
        }

        @Override
        public boolean hasPotionEffect(PotionEffectType type) {
            final org.bukkit.potion.PotionEffectType nativeType = potionEffectTypeHandle(type);
            return nativeType != null && value.hasPotionEffect(nativeType);
        }

        @Override
        public void addPotionEffect(PotionEffect effect) {
            value.addPotionEffect(potionEffectHandle(effect));
        }

        @Override
        public void addPotionEffects(Collection<PotionEffect> effects) {
            value.addPotionEffects(effects.stream().map(BukkitMC::potionEffectHandle).toList());
        }

        @Override
        public boolean addPotionEffect(PotionEffect effect, boolean force) {
            return value.addPotionEffect(potionEffectHandle(effect), force);
        }

        @Override
        public PotionEffect getPotionEffect(PotionEffectType type) {
            final org.bukkit.potion.PotionEffectType nativeType = potionEffectTypeHandle(type);
            return nativeType == null ? null : potionEffect(value.getPotionEffect(nativeType));
        }

        @Override
        public void removePotionEffect(PotionEffectType type) {
            final org.bukkit.potion.PotionEffectType nativeType = potionEffectTypeHandle(type);
            if (nativeType != null) value.removePotionEffect(nativeType);
        }

        @Override
        public Collection<PotionEffect> getActivePotionEffects() {
            return value.getActivePotionEffects().stream().map(BukkitMC::potionEffect).toList();
        }

        @Override
        public int getRemainingAir() {
            return value.getRemainingAir();
        }

        @Override
        public void setRemainingAir(int ticks) {
            value.setRemainingAir(ticks);
        }

        @Override
        public boolean isDead() {
            return value.isDead();
        }

        @Override
        public PlayerInventory getInventory() {
            return new InventoryView(value.getInventory());
        }

        @Override
        public EntityEquipment getEquipment() {
            return new EquipmentView(value.getEquipment());
        }

        @Override
        public void setMetadata(String key, MetadataValue metadata) {
            value.setMetadata(key, new org.bukkit.metadata.FixedMetadataValue(Platform.pluginHandle(Plugin.class), metadata.value()));
        }

        @Override
        public boolean hasMetadata(String key) {
            return value.hasMetadata(key);
        }

        @Override
        public List<MetadataValue> getMetadata(String key) {
            return value.getMetadata(key).stream().map(NativeMetadata::new).map(v -> (MetadataValue) v).toList();
        }

        @Override
        public void removeMetadata(String key, Object owner) {
            value.removeMetadata(key, Platform.pluginHandle(Plugin.class));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Entity entity
                    && value.getUniqueId().equals(entity.getUniqueId());
        }

        @Override
        public int hashCode() {
            return value.getUniqueId().hashCode();
        }
    }

    private static final class InventoryView extends PlayerInventory {
        private final org.bukkit.inventory.PlayerInventory value;

        private InventoryView(org.bukkit.inventory.PlayerInventory value) {
            this.value = value;
        }

        @Override
        public ItemStack getItemInMainHand() {
            return item(value.getItemInMainHand());
        }

        @Override
        public void setItemInMainHand(ItemStack stack) {
            value.setItemInMainHand(itemHandle(stack));
        }

        @Override
        public ItemStack getItemInOffHand() {
            return item(value.getItemInOffHand());
        }

        @Override
        public void setItemInOffHand(ItemStack stack) {
            value.setItemInOffHand(itemHandle(stack));
        }

        @Override
        public int getHeldItemSlot() {
            return value.getHeldItemSlot();
        }

        @Override
        public void setHeldItemSlot(int slot) {
            value.setHeldItemSlot(slot);
        }

        @Override
        public boolean containsAtLeast(ItemStack stack, int amount) {
            return value.containsAtLeast(itemHandle(stack), amount);
        }

        @Override
        public Map<Integer, ItemStack> removeItem(ItemStack... stacks) {
            Map<Integer, org.bukkit.inventory.ItemStack> result = value.removeItem(itemHandles(stacks));
            Map<Integer, ItemStack> converted = new HashMap<>();
            result.forEach((k, v) -> converted.put(k, item(v)));
            return converted;
        }

        @Override
        public ItemStack[] getContents() {
            return items(value.getContents());
        }

        @Override
        public void setContents(ItemStack[] stacks) {
            value.setContents(itemHandles(stacks));
        }

        @Override
        public ItemStack[] getArmorContents() {
            return items(value.getArmorContents());
        }

        @Override
        public void setArmorContents(ItemStack[] stacks) {
            value.setArmorContents(itemHandles(stacks));
        }

        @Override
        public ItemStack getHelmet() {
            return item(value.getHelmet());
        }

        @Override
        public ItemStack getChestplate() {
            return item(value.getChestplate());
        }

        @Override
        public ItemStack getLeggings() {
            return item(value.getLeggings());
        }

        @Override
        public ItemStack getBoots() {
            return item(value.getBoots());
        }

        @Override
        public HashMap<Integer, ItemStack> addItem(ItemStack... stacks) {
            HashMap<Integer, ItemStack> converted = new HashMap<>();
            value.addItem(itemHandles(stacks)).forEach((k, v) -> converted.put(k, item(v)));
            return converted;
        }

        @Override
        public void clear(int slot) {
            value.clear(slot);
        }

        @Override
        public boolean contains(Material type) {
            return value.contains(material(type));
        }

        @Override
        public int first(Material type) {
            return value.first(material(type));
        }

        @Override
        public ItemStack getItem(int slot) {
            return item(value.getItem(slot));
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            value.setItem(slot, itemHandle(stack));
        }

        @Override
        public int getSize() {
            return value.getSize();
        }

        @Override
        public Iterator<ItemStack> iterator() {
            return Arrays.stream(value.getContents()).map(BukkitMC::item).iterator();
        }
    }

    private static final class EquipmentView extends EntityEquipment {
        private final org.bukkit.inventory.EntityEquipment value;

        private EquipmentView(org.bukkit.inventory.EntityEquipment value) {
            this.value = value;
        }

        @Override
        public ItemStack[] getArmorContents() {
            return items(value.getArmorContents());
        }

        @Override
        public void setArmorContents(ItemStack[] stacks) {
            value.setArmorContents(itemHandles(stacks));
        }

        @Override
        public ItemStack getItemInMainHand() {
            return item(value.getItemInMainHand());
        }

        @Override
        public void setItemInMainHand(ItemStack item) {
            value.setItemInMainHand(itemHandle(item));
        }
    }

    private record NativeMetadata(
            org.bukkit.metadata.MetadataValue nativeValue) implements MetadataValue {
        @Override
        public Object value() {
            return nativeValue.value();
        }
    }

    private static final class OfflineView implements OfflinePlayer {
        private final org.bukkit.OfflinePlayer value;

        private OfflineView(org.bukkit.OfflinePlayer value) {
            this.value = value;
        }

        @Override
        public UUID getUniqueId() {
            return value.getUniqueId();
        }

        @Override
        public String getName() {
            return value.getName();
        }

        @Override
        public boolean isOnline() {
            return value.isOnline();
        }

        @Override
        public boolean hasPlayedBefore() {
            return value.hasPlayedBefore();
        }

        @Override
        public Player getPlayer() {
            return player(value.getPlayer());
        }

        @Override
        public Object handle() {
            return value;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof OfflinePlayer offline
                    && value.getUniqueId().equals(offline.getUniqueId());
        }

        @Override
        public int hashCode() {
            return value.getUniqueId().hashCode();
        }
    }

    private static final class FallingView extends FallingBlock {
        private final org.bukkit.entity.FallingBlock value;

        private FallingView(org.bukkit.entity.FallingBlock value) {
            this.value = value;
        }

        @Override
        public Object handle() {
            return value;
        }

        @Override
        public UUID getUniqueId() {
            return value.getUniqueId();
        }

        @Override
        public Location getLocation() {
            return location(value.getLocation());
        }

        @Override
        public World getWorld() {
            return world(value.getWorld());
        }

        @Override
        public Vector getVelocity() {
            return vector(value.getVelocity());
        }

        @Override
        public void setVelocity(Vector velocity) {
            value.setVelocity(vector(velocity));
        }

        @Override
        public boolean isDead() {
            return value.isDead();
        }

        @Override
        public boolean isValid() {
            return value.isValid();
        }

        @Override
        public boolean isOnGround() {
            return value.isOnGround();
        }

        @Override
        public int getEntityId() {
            return value.getEntityId();
        }

        @Override
        public String getName() {
            return value.getName();
        }

        @Override
        public BlockData getBlockData() {
            return BukkitMC.blockData(value.getBlockData());
        }

        @Override
        public BoundingBox getBoundingBox() {
            org.bukkit.util.BoundingBox b = value.getBoundingBox();
            return new BoundingBox(new Vector(b.getMinX(), b.getMinY(), b.getMinZ()), new Vector(b.getMaxX(), b.getMaxY(), b.getMaxZ()));
        }

        @Override
        public void setDropItem(boolean drop) {
            value.setDropItem(drop);
        }

        @Override
        public void setHurtEntities(boolean hurt) {
            value.setHurtEntities(hurt);
        }

        @Override
        public void remove() {
            value.remove();
        }

        @Override
        public void setMetadata(String key, MetadataValue metadata) {
            value.setMetadata(key, new org.bukkit.metadata.FixedMetadataValue(Platform.pluginHandle(Plugin.class), metadata.value()));
        }

        @Override
        public boolean hasMetadata(String key) {
            return value.hasMetadata(key);
        }

        @Override
        public List<MetadataValue> getMetadata(String key) {
            return value.getMetadata(key).stream().map(NativeMetadata::new).map(v -> (MetadataValue) v).toList();
        }

        @Override
        public void removeMetadata(String key, Object owner) {
            value.removeMetadata(key, Platform.pluginHandle(Plugin.class));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof FallingView view && value.getUniqueId().equals(view.value.getUniqueId());
        }

        @Override
        public int hashCode() {
            return value.getUniqueId().hashCode();
        }
    }

    private static class DisplayView extends Display {
        protected final org.bukkit.entity.Display value;

        private DisplayView(org.bukkit.entity.Display value) {
            this.value = value;
        }

        @Override
        public Object handle() {
            return value;
        }

        @Override
        public UUID getUniqueId() {
            return value.getUniqueId();
        }

        @Override
        public Location getLocation() {
            return location(value.getLocation());
        }

        @Override
        public World getWorld() {
            return world(value.getWorld());
        }

        @Override
        public Vector getVelocity() {
            return vector(value.getVelocity());
        }

        @Override
        public void setVelocity(Vector velocity) {
            value.setVelocity(vector(velocity));
        }

        @Override
        public boolean isDead() {
            return value.isDead();
        }

        @Override
        public boolean isValid() {
            return value.isValid();
        }

        @Override
        public void remove() {
            value.remove();
        }

        @Override
        public boolean teleport(Location target) {
            return value.teleport(locationHandle(target));
        }

        @Override
        public int getEntityId() {
            return value.getEntityId();
        }

        @Override
        public String getName() {
            return value.getName();
        }

        @Override
        public boolean isOnGround() {
            return value.isOnGround();
        }

        @Override
        public void setSilent(boolean silent) {
            value.setSilent(silent);
        }

        @Override
        public void setGravity(boolean gravity) {
            value.setGravity(gravity);
        }

        @Override
        public void setPersistent(boolean persistent) {
            value.setPersistent(persistent);
        }

        @Override
        public void setBrightness(Display.Brightness brightness) {
            value.setBrightness(brightness == null ? null : new org.bukkit.entity.Display.Brightness(brightness.blockLight(), brightness.skyLight()));
        }

        @Override
        public void setTransformation(Transformation transformation) {
            value.setTransformation(new org.bukkit.util.Transformation(
                    transformation.translation(),
                    transformation.leftRotation(),
                    transformation.scale(),
                    transformation.rightRotation()));
        }

        @Override
        public void setBillboard(Display.Billboard billboard) {
            value.setBillboard(org.bukkit.entity.Display.Billboard.valueOf(billboard.name()));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof DisplayView view && value.getUniqueId().equals(view.value.getUniqueId());
        }

        @Override
        public int hashCode() {
            return value.getUniqueId().hashCode();
        }
    }

    private static final class BlockDisplayView extends BlockDisplay {
        private final org.bukkit.entity.BlockDisplay value;
        private final DisplayView display;

        private BlockDisplayView(org.bukkit.entity.BlockDisplay value) {
            this.value = value;
            this.display = new DisplayView(value);
        }

        @Override
        public Object handle() {
            return value;
        }

        @Override
        public UUID getUniqueId() {
            return display.getUniqueId();
        }

        @Override
        public Location getLocation() {
            return display.getLocation();
        }

        @Override
        public World getWorld() {
            return display.getWorld();
        }

        @Override
        public Vector getVelocity() {
            return display.getVelocity();
        }

        @Override
        public void setVelocity(Vector velocity) {
            display.setVelocity(velocity);
        }

        @Override
        public boolean isDead() {
            return display.isDead();
        }

        @Override
        public boolean isValid() {
            return display.isValid();
        }

        @Override
        public void remove() {
            display.remove();
        }

        @Override
        public boolean teleport(Location target) {
            return display.teleport(target);
        }

        @Override
        public int getEntityId() {
            return display.getEntityId();
        }

        @Override
        public String getName() {
            return display.getName();
        }

        @Override
        public boolean isOnGround() {
            return display.isOnGround();
        }

        @Override
        public void setSilent(boolean silent) {
            display.setSilent(silent);
        }

        @Override
        public void setGravity(boolean gravity) {
            display.setGravity(gravity);
        }

        @Override
        public void setPersistent(boolean persistent) {
            display.setPersistent(persistent);
        }

        @Override
        public void setBrightness(Display.Brightness brightness) {
            display.setBrightness(brightness);
        }

        @Override
        public void setTransformation(Transformation transformation) {
            display.setTransformation(transformation);
        }

        @Override
        public void setBillboard(Display.Billboard billboard) {
            display.setBillboard(billboard);
        }

        @Override
        public void setBlock(BlockData data) {
            value.setBlock(blockDataHandle(data));
        }

        @Override
        public void setShadowRadius(float shadowRadius) {
            value.setShadowRadius(shadowRadius);
        }

        @Override
        public void setShadowStrength(float shadowStrength) {
            value.setShadowStrength(shadowStrength);
        }

        @Override
        public void setInterpolationDelay(int delay) {
            value.setInterpolationDelay(delay);
        }

        @Override
        public void setInterpolationDuration(int duration) {
            value.setInterpolationDuration(duration);
        }

        @Override
        public void setTeleportDuration(int duration) {
            value.setTeleportDuration(duration);
        }

        @Override
        public void setViewRange(float range) {
            value.setViewRange(range);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof BlockDisplayView view && value.getUniqueId().equals(view.value.getUniqueId());
        }

        @Override
        public int hashCode() {
            return value.getUniqueId().hashCode();
        }
    }

    private static final class AreaEffectCloudView extends AreaEffectCloud {
        private final org.bukkit.entity.AreaEffectCloud value;

        private AreaEffectCloudView(org.bukkit.entity.AreaEffectCloud value) {
            this.value = value;
        }

        @Override
        public Object handle() {
            return value;
        }

        @Override
        public UUID getUniqueId() {
            return value.getUniqueId();
        }

        @Override
        public Location getLocation() {
            return location(value.getLocation());
        }

        @Override
        public World getWorld() {
            return world(value.getWorld());
        }

        @Override
        public Vector getVelocity() {
            return vector(value.getVelocity());
        }

        @Override
        public void setVelocity(Vector velocity) {
            value.setVelocity(vector(velocity));
        }

        @Override
        public boolean isDead() {
            return value.isDead();
        }

        @Override
        public boolean isValid() {
            return value.isValid();
        }

        @Override
        public void remove() {
            value.remove();
        }

        @Override
        public int getEntityId() {
            return value.getEntityId();
        }

        @Override
        public String getName() {
            return value.getName();
        }

        @Override
        public boolean isOnGround() {
            return value.isOnGround();
        }

        @Override
        public void setSource(Entity entity) {
            if (entity != null && entity.handle() instanceof ProjectileSource source) {
                value.setSource(source);
            }
        }

        @Override
        public void setDuration(int duration) {
            value.setDuration(duration);
        }

        @Override
        public void setRadius(float radius) {
            value.setRadius(radius);
        }

        @Override
        public void setColor(Color color) {
            value.setColor(BukkitMC.colorHandle(color));
        }

        @Override
        public void setWaitTime(int ticks) {
            value.setWaitTime(ticks);
        }

        @Override
        public void setReapplicationDelay(int ticks) {
            value.setReapplicationDelay(ticks);
        }

        @Override
        public void addCustomEffect(PotionEffect effect, boolean overwrite) {
            value.addCustomEffect(potionEffectHandle(effect), overwrite);
        }
    }

    private static final class ShulkerBulletView extends ShulkerBullet {
        private final org.bukkit.entity.ShulkerBullet value;

        private ShulkerBulletView(org.bukkit.entity.ShulkerBullet value) {
            this.value = value;
        }

        @Override
        public Object handle() {
            return value;
        }

        @Override
        public UUID getUniqueId() {
            return value.getUniqueId();
        }

        @Override
        public Location getLocation() {
            return location(value.getLocation());
        }

        @Override
        public World getWorld() {
            return world(value.getWorld());
        }

        @Override
        public Vector getVelocity() {
            return vector(value.getVelocity());
        }

        @Override
        public void setVelocity(Vector velocity) {
            value.setVelocity(vector(velocity));
        }

        @Override
        public boolean isDead() {
            return value.isDead();
        }

        @Override
        public boolean isValid() {
            return value.isValid();
        }

        @Override
        public void remove() {
            value.remove();
        }

        @Override
        public boolean teleport(Location target) {
            return value.teleport(locationHandle(target));
        }

        @Override
        public int getEntityId() {
            return value.getEntityId();
        }

        @Override
        public String getName() {
            return value.getName();
        }

        @Override
        public int getFireTicks() {
            return value.getFireTicks();
        }

        @Override
        public void setFireTicks(int ticks) {
            value.setFireTicks(ticks);
        }

        @Override
        public float getFallDistance() {
            return value.getFallDistance();
        }

        @Override
        public void setFallDistance(float distance) {
            value.setFallDistance(distance);
        }

        @Override
        public boolean isOnGround() {
            return value.isOnGround();
        }

        @Override
        public void setSilent(boolean silent) {
            value.setSilent(silent);
        }

        @Override
        public void setInvulnerable(boolean invulnerable) {
            value.setInvulnerable(invulnerable);
        }

        @Override
        public void setGravity(boolean gravity) {
            value.setGravity(gravity);
        }

        @Override
        public void setPersistent(boolean persistent) {
            value.setPersistent(persistent);
        }

        public Object getShooter() {
            return projectileSource(value.getShooter());
        }

        @Override
        public void setShooter(Object shooter) {
            if (shooter instanceof ProjectileSource source) {
                value.setShooter(source);
            } else if (shooter instanceof Entity entity && entity.handle() instanceof ProjectileSource source) {
                value.setShooter(source);
            }
        }

        @Override
        public void setMetadata(String key, MetadataValue metadata) {
            value.setMetadata(key, new org.bukkit.metadata.FixedMetadataValue(Platform.pluginHandle(Plugin.class), metadata.value()));
        }

        @Override
        public boolean hasMetadata(String key) {
            return value.hasMetadata(key);
        }

        @Override
        public List<MetadataValue> getMetadata(String key) {
            return value.getMetadata(key).stream().map(NativeMetadata::new).map(v -> (MetadataValue) v).toList();
        }

        @Override
        public void removeMetadata(String key, Object owner) {
            value.removeMetadata(key, JavaPlugin.getPlugin(BukkitProjectKorraPlugin.class));
        }

        @Override
        public BoundingBox getBoundingBox() {
            org.bukkit.util.BoundingBox b = value.getBoundingBox();
            return new BoundingBox(
                    new Vector(b.getMinX(), b.getMinY(), b.getMinZ()),
                    new Vector(b.getMaxX(), b.getMaxY(), b.getMaxZ()));
        }
    }

    private static final class ArrowView extends Arrow {
        private final org.bukkit.entity.Arrow value;

        private ArrowView(org.bukkit.entity.Arrow value) {
            this.value = value;
        }

        @Override
        public Object handle() {
            return value;
        }

        @Override
        public UUID getUniqueId() {
            return value.getUniqueId();
        }

        @Override
        public Location getLocation() {
            return location(value.getLocation());
        }

        @Override
        public World getWorld() {
            return world(value.getWorld());
        }

        @Override
        public Vector getVelocity() {
            return vector(value.getVelocity());
        }

        @Override
        public void setVelocity(Vector velocity) {
            value.setVelocity(vector(velocity));
        }

        @Override
        public boolean isDead() {
            return value.isDead();
        }

        @Override
        public boolean isValid() {
            return value.isValid();
        }

        @Override
        public void remove() {
            value.remove();
        }

        @Override
        public int getEntityId() {
            return value.getEntityId();
        }

        @Override
        public String getName() {
            return value.getName();
        }

        @Override
        public int getFireTicks() {
            return value.getFireTicks();
        }

        @Override
        public void setFireTicks(int ticks) {
            value.setFireTicks(ticks);
        }

        @Override
        public float getFallDistance() {
            return value.getFallDistance();
        }

        @Override
        public void setFallDistance(float distance) {
            value.setFallDistance(distance);
        }

        @Override
        public boolean isOnGround() {
            return value.isOnGround();
        }

        @Override
        public Arrow.PickupStatus getPickupStatus() {
            return Arrow.PickupStatus.valueOf(value.getPickupStatus().name());
        }

        @Override
        public void setPickupStatus(Arrow.PickupStatus status) {
            value.setPickupStatus(AbstractArrow.PickupStatus.valueOf(status.name()));
        }

        @Override
        public void setKnockbackStrength(int strength) {
            value.setKnockbackStrength(strength);
        }

        @Override
        public void setDamage(int damage) {
            value.setDamage(damage);
        }

        @Override
        public void setBounce(boolean bounce) {
            value.setBounce(bounce);
        }

        @Override
        public void setCritical(boolean critical) {
            value.setCritical(critical);
        }

        @Override
        public Object getShooter() {
            return projectileSource(value.getShooter());
        }

        @Override
        public void setShooter(Object shooter) {
            if (shooter instanceof ProjectileSource source) {
                value.setShooter(source);
            } else if (shooter instanceof Entity entity && entity.handle() instanceof ProjectileSource source) {
                value.setShooter(source);
            }
        }

        @Override
        public Block getAttachedBlock() {
            return block(value.getAttachedBlock());
        }

        @Override
        public void setMetadata(String key, MetadataValue metadata) {
            value.setMetadata(key, new org.bukkit.metadata.FixedMetadataValue(Platform.pluginHandle(Plugin.class), metadata.value()));
        }

        @Override
        public boolean hasMetadata(String key) {
            return value.hasMetadata(key);
        }

        @Override
        public List<MetadataValue> getMetadata(String key) {
            return value.getMetadata(key).stream().map(NativeMetadata::new).map(v -> (MetadataValue) v).toList();
        }

        @Override
        public void removeMetadata(String key, Object owner) {
            value.removeMetadata(key, JavaPlugin.getPlugin(BukkitProjectKorraPlugin.class));
        }

        @Override
        public BoundingBox getBoundingBox() {
            org.bukkit.util.BoundingBox b = value.getBoundingBox();
            return new BoundingBox(
                    new Vector(b.getMinX(), b.getMinY(), b.getMinZ()),
                    new Vector(b.getMaxX(), b.getMaxY(), b.getMaxZ()));
        }
    }

    private static final class ItemView extends ItemStack {
        private final org.bukkit.inventory.ItemStack value;

        private ItemView(org.bukkit.inventory.ItemStack value) {
            this.value = value;
        }

        @Override
        public Material getType() {
            return material(value.getType());
        }

        @Override
        public void setType(Material type) {
            value.setType(material(type));
        }

        @Override
        public int getAmount() {
            return value.getAmount();
        }

        @Override
        public void setAmount(int amount) {
            value.setAmount(amount);
        }

        @Override
        public boolean hasItemMeta() {
            return value.hasItemMeta();
        }

        @Override
        public ItemMeta getItemMeta() {
            org.bukkit.inventory.meta.ItemMeta nativeMeta = value.getItemMeta();
            if (nativeMeta instanceof org.bukkit.inventory.meta.PotionMeta nativePotion) {
                PotionMeta meta =
                        new PotionMeta();
                org.bukkit.potion.PotionType nativeType = nativePotion.getBasePotionType();
                if (nativeType != null) {
                    meta.setBasePotionData(new PotionData(
                            PotionType.valueOf(
                                    nativeType.getKey().getKey().toUpperCase(Locale.ROOT))));
                }
                if (nativePotion.hasDisplayName()) meta.setDisplayName(nativePotion.getDisplayName());
                if (nativePotion.hasLore()) meta.setLore(nativePotion.getLore());
                return meta;
            }
            if (nativeMeta instanceof org.bukkit.inventory.meta.LeatherArmorMeta nativeLeather) {
                LeatherArmorMeta meta = new LeatherArmorMeta();
                meta.setColor(BukkitMC.color(nativeLeather.getColor()));
                if (nativeLeather.hasDisplayName()) meta.setDisplayName(nativeLeather.getDisplayName());
                if (nativeLeather.hasLore()) meta.setLore(nativeLeather.getLore());
                return meta;
            }
            ItemMeta meta = new ItemMeta();
            if (nativeMeta != null) {
                if (nativeMeta.hasDisplayName()) meta.setDisplayName(nativeMeta.getDisplayName());
                if (nativeMeta.hasLore()) meta.setLore(nativeMeta.getLore());
            }
            return meta;
        }

        @Override
        public boolean setItemMeta(ItemMeta meta) {
            org.bukkit.inventory.meta.ItemMeta nativeMeta = value.getItemMeta();
            if (nativeMeta == null) {
                return false;
            }
            nativeMeta.setDisplayName(meta.getDisplayName());
            nativeMeta.setLore(meta.getLore());
            if (meta instanceof LeatherArmorMeta leather
                    && nativeMeta instanceof org.bukkit.inventory.meta.LeatherArmorMeta nativeLeather) {
                nativeLeather.setColor(colorHandle(leather.getColor()));
                nativeMeta = nativeLeather;
            } else if (meta instanceof PotionMeta potion
                    && nativeMeta instanceof org.bukkit.inventory.meta.PotionMeta nativePotion
                    && potion.getBasePotionData() != null
                    && PotionType.WATER.equals(
                    potion.getBasePotionData().getType())) {
                nativePotion.setBasePotionType(org.bukkit.potion.PotionType.WATER);
                nativeMeta = nativePotion;
            }
            return value.setItemMeta(nativeMeta);
        }

        @Override
        public short getDurability() {
            org.bukkit.inventory.meta.ItemMeta meta = value.getItemMeta();
            if (meta instanceof Damageable damageable) {
                return (short) damageable.getDamage();
            }
            return 0;
        }

        @Override
        public void setDurability(short durability) {
            org.bukkit.inventory.meta.ItemMeta meta = value.getItemMeta();
            if (meta instanceof Damageable damageable) {
                damageable.setDamage(durability);
                value.setItemMeta(meta);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static final class DamageEventView extends EntityDamageEvent {
        private final org.bukkit.event.entity.EntityDamageEvent value;

        private DamageEventView(org.bukkit.event.entity.EntityDamageEvent value) {
            this.value = value;
            setEntity(entity(value.getEntity()));
        }

        private static org.bukkit.event.entity.EntityDamageEvent.DamageModifier nativeModifier(DamageModifier modifier) {
            return switch (modifier) {
                case ARMOR -> org.bukkit.event.entity.EntityDamageEvent.DamageModifier.ARMOR;
                case MAGIC -> org.bukkit.event.entity.EntityDamageEvent.DamageModifier.MAGIC;
                default -> org.bukkit.event.entity.EntityDamageEvent.DamageModifier.BASE;
            };
        }

        @Override
        public double getDamage() {
            return value.getDamage();
        }

        @Override
        public void setDamage(double damage) {
            value.setDamage(damage);
        }

        @Override
        public double getDamage(DamageModifier modifier) {
            return value.isApplicable(nativeModifier(modifier)) ? value.getDamage(nativeModifier(modifier)) : 0;
        }

        @Override
        public void setDamage(DamageModifier modifier, double damage) {
            if (value.isApplicable(nativeModifier(modifier))) value.setDamage(nativeModifier(modifier), damage);
        }
    }
}
