package com.projectkorra.projectkorra.platform.fabric;

import com.projectkorra.projectkorra.platform.chat.ChatMessageType;
import com.projectkorra.projectkorra.platform.mc.Color;
import com.projectkorra.projectkorra.platform.mc.Effect;
import com.projectkorra.projectkorra.platform.mc.FluidCollisionMode;
import com.projectkorra.projectkorra.platform.mc.GameMode;
import com.projectkorra.projectkorra.platform.mc.Instrument;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Note;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.SoundCategory;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Biome;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.block.data.Levelled;
import com.projectkorra.projectkorra.platform.mc.block.data.type.Fire;
import com.projectkorra.projectkorra.platform.mc.block.data.type.Snow;
import com.projectkorra.projectkorra.platform.mc.command.CommandSender;
import com.projectkorra.projectkorra.platform.mc.entity.ArmorStand;
import com.projectkorra.projectkorra.platform.mc.entity.Arrow;
import com.projectkorra.projectkorra.platform.mc.entity.BlockDisplay;
import com.projectkorra.projectkorra.platform.mc.entity.Display;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.entity.Fireball;
import com.projectkorra.projectkorra.platform.mc.entity.Item;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.entity.Projectile;
import com.projectkorra.projectkorra.platform.mc.entity.ShulkerBullet;
import com.projectkorra.projectkorra.platform.mc.inventory.EntityEquipment;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.inventory.PlayerInventory;
import com.projectkorra.projectkorra.platform.mc.metadata.MetadataValue;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.platform.mc.scoreboard.DisplaySlot;
import com.projectkorra.projectkorra.platform.mc.scoreboard.Objective;
import com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard;
import com.projectkorra.projectkorra.platform.mc.scoreboard.Team;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.RayTraceResult;
import com.projectkorra.projectkorra.platform.mc.util.Transformation;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.state.AbilityStateSync;
import com.projectkorra.projectkorra.prediction.block.TempBlockSync;
import com.projectkorra.projectkorra.prediction.block.DirectBlockSync;
import com.projectkorra.projectkorra.prediction.movement.VelocitySync;
import com.projectkorra.projectkorra.util.TempBlock;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ShulkerBulletEntity;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExperienceBarUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreResetS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.TeamS2CPacket;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.EffectParticleEffect;
import net.minecraft.particle.ItemStackParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.particle.SimpleParticleType;
import net.minecraft.particle.TintedParticleEffect;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.Heightmap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.RaycastContext;

/**
 * Minecraft/Fabric native values exposed through the common Minecraft contract.
 */
public final class FabricMC {
    private static final Scoreboard EMPTY_SCOREBOARD =
            new Scoreboard();
    private static final Map<UUID, Scoreboard> PLAYER_SCOREBOARDS = new ConcurrentHashMap<>();
    private static final Map<UUID, Runnable> SCOREBOARD_LISTENERS = new ConcurrentHashMap<>();
    private static final Map<UUID, net.minecraft.scoreboard.Scoreboard> NATIVE_SCOREBOARDS = new ConcurrentHashMap<>();
    private static final Map<UUID, ScoreboardSnapshot> SCOREBOARD_SNAPSHOTS = new ConcurrentHashMap<>();
    private static final Set<UUID> PENDING_SCOREBOARD_SYNCS = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Player> PLAYER_VIEWS = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> SNEAK_OVERRIDES = new ConcurrentHashMap<>();
    private static final Set<UUID> SUPPRESSED_SELECTED_SLOT_PACKETS = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Boolean> PICKUP_OVERRIDES = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<String, List<MetadataValue>>> ENTITY_METADATA = new ConcurrentHashMap<>();
    private static final Map<ServerWorld, World> WORLDS = new ConcurrentHashMap<>();

    private FabricMC() {
    }

    private static void applyHitStatus(final Entity target, final Runnable commit) {
        commit.run();
    }

    public record BlockRef(ServerWorld world, BlockPos pos) {
    }

    public record LocationRef(ServerWorld world, Vec3d pos, float yaw, float pitch) {
    }

    private record TeamSnapshot(String prefix, String suffix, Set<String> entries) {
    }

    private record ObjectiveSnapshot(String title, Map<String, Integer> scores) {
    }

    private record ScoreboardSnapshot(Map<String, TeamSnapshot> teams,
                                      Map<String, ObjectiveSnapshot> objectives,
                                      String sidebarObjective) {
        private static final ScoreboardSnapshot EMPTY = new ScoreboardSnapshot(Map.of(), Map.of(), null);
    }

    public static Player player(ServerPlayerEntity value) {
        if (value == null) return null;
        Player cached = PLAYER_VIEWS.computeIfAbsent(value.getUuid(), ignored -> new PlayerView(value));
        if (cached instanceof PlayerView view) view.value = value;
        return cached;
    }

    public static LivingEntity living(net.minecraft.entity.LivingEntity value) {
        if (value instanceof ArmorStandEntity armorStand) return new ArmorStandView(armorStand);
        return value instanceof ServerPlayerEntity player ? player(player) : value == null ? null : new LivingView(value);
    }

    public static Entity entity(net.minecraft.entity.Entity value) {
        if (value == null) return null;
        if (value instanceof net.minecraft.entity.LivingEntity living) return living(living);
        if (value instanceof FallingBlockEntity falling) return new FallingBlockView(falling);
        if (value instanceof DisplayEntity.BlockDisplayEntity display) return new BlockDisplayView(display);
        if (value instanceof DisplayEntity display) return new DisplayView(display);
        if (value instanceof ArrowEntity arrow) return new ArrowView(arrow);
        if (value instanceof ShulkerBulletEntity bullet) return new ShulkerBulletView(bullet);
        if (value instanceof SmallFireballEntity fireball) return new FireballView(fireball);
        if (value instanceof ItemEntity item) return new DroppedItemView(item);
        return new EntityView(value);
    }

    public static World world(ServerWorld value) {
        return value == null ? null : WORLDS.computeIfAbsent(value, WorldView::new);
    }

    public static Block block(ServerWorld world, BlockPos pos) {
        return world == null || pos == null ? null : new BlockView(world, pos);
    }

    private static void setMetadata(UUID uuid, String key, MetadataValue metadata) {
        if (uuid == null || key == null || metadata == null) {
            return;
        }
        ENTITY_METADATA.computeIfAbsent(uuid, ignored -> new ConcurrentHashMap<>())
                .put(key, new ArrayList<>(List.of(metadata)));
    }

    private static boolean hasMetadata(UUID uuid, String key) {
        return getMetadata(uuid, key).size() > 0;
    }

    private static List<MetadataValue> getMetadata(UUID uuid, String key) {
        if (uuid == null || key == null) {
            return List.of();
        }
        Map<String, List<MetadataValue>> values = ENTITY_METADATA.get(uuid);
        if (values == null) {
            return List.of();
        }
        List<MetadataValue> metadata = values.get(key);
        return metadata == null ? List.of() : List.copyOf(metadata);
    }

    private static void removeMetadata(UUID uuid, String key) {
        if (uuid == null || key == null) {
            return;
        }
        Map<String, List<MetadataValue>> values = ENTITY_METADATA.get(uuid);
        if (values == null) {
            return;
        }
        values.remove(key);
        if (values.isEmpty()) {
            ENTITY_METADATA.remove(uuid);
        }
    }

    public static Location location(ServerWorld world, Vec3d pos) {
        return location(world, pos, 0, 0);
    }

    public static Location location(ServerWorld world, Vec3d pos, float yaw, float pitch) {
        return world == null || pos == null ? null : new LocationView(world, pos, yaw, pitch);
    }

    public static Vector vector(Vec3d value) {
        return new Vector(value.x, value.y, value.z);
    }

    public static Text legacyText(String value) {
        String input = value == null ? "" : value;
        MutableText result = Text.empty();
        Style style = Style.EMPTY;
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char current = input.charAt(i);
            if (current != '\u00a7' || i + 1 >= input.length()) {
                plain.append(current);
                continue;
            }
            if (!plain.isEmpty()) {
                result.append(Text.literal(plain.toString()).setStyle(style));
                plain.setLength(0);
            }
            char code = Character.toLowerCase(input.charAt(++i));
            if (code == 'x' && i + 12 < input.length()) {
                StringBuilder hex = new StringBuilder(6);
                int cursor = i + 1;
                boolean valid = true;
                for (int digit = 0; digit < 6; digit++) {
                    if (cursor + 1 >= input.length() || input.charAt(cursor) != '\u00a7') {
                        valid = false;
                        break;
                    }
                    hex.append(input.charAt(cursor + 1));
                    cursor += 2;
                }
                if (valid) {
                    try {
                        style = Style.EMPTY.withColor(Integer.parseInt(hex.toString(), 16));
                        i = cursor - 1;
                        continue;
                    } catch (NumberFormatException ignored) { }
                }
            }
            Formatting formatting = Formatting.byCode(code);
            if (formatting == Formatting.RESET) {
                style = Style.EMPTY;
            } else if (formatting != null && formatting.isColor()) {
                style = Style.EMPTY.withFormatting(formatting);
            } else if (formatting != null) {
                style = style.withFormatting(formatting);
            }
        }
        if (!plain.isEmpty()) result.append(Text.literal(plain.toString()).setStyle(style));
        return result;
    }

    private static void setScoreboard(ServerPlayerEntity player, Scoreboard board) {
        UUID uuid = player.getUuid();
        if (PLAYER_SCOREBOARDS.get(uuid) == board && NATIVE_SCOREBOARDS.containsKey(uuid)) {
            syncScoreboard(player, board);
            return;
        }
        Scoreboard previous = PLAYER_SCOREBOARDS.put(uuid, board);
        Runnable previousListener = SCOREBOARD_LISTENERS.remove(uuid);
        if (previous != null && previousListener != null) previous.removeChangeListener(previousListener);
        Runnable listener = () -> requestScoreboardSync(player, board);
        SCOREBOARD_LISTENERS.put(uuid, listener);
        board.addChangeListener(listener);
        syncScoreboard(player, board);
    }

    private static void requestScoreboardSync(ServerPlayerEntity player,
                                              Scoreboard board) {
        UUID uuid = player.getUuid();
        if (!PENDING_SCOREBOARD_SYNCS.add(uuid)) return;
        MinecraftServer server = player.getEntityWorld().getServer();
        if (server == null) {
            PENDING_SCOREBOARD_SYNCS.remove(uuid);
            return;
        }
        server.execute(() -> {
            PENDING_SCOREBOARD_SYNCS.remove(uuid);
            if (PLAYER_SCOREBOARDS.get(uuid) == board && !player.isRemoved()) syncScoreboard(player, board);
        });
    }

    public static void clearPlayerState(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        Scoreboard board = PLAYER_SCOREBOARDS.remove(uuid);
        Runnable listener = SCOREBOARD_LISTENERS.remove(uuid);
        if (board != null && listener != null) board.removeChangeListener(listener);
        NATIVE_SCOREBOARDS.remove(uuid);
        SCOREBOARD_SNAPSHOTS.remove(uuid);
        PENDING_SCOREBOARD_SYNCS.remove(uuid);
        PLAYER_VIEWS.remove(uuid);
        SNEAK_OVERRIDES.remove(uuid);
        SUPPRESSED_SELECTED_SLOT_PACKETS.remove(uuid);
    }

    public static void withSneakingOverride(ServerPlayerEntity player, boolean sneaking, Runnable action) {
        if (player == null || action == null) return;
        UUID uuid = player.getUuid();
        Boolean previous = SNEAK_OVERRIDES.put(uuid, sneaking);
        try {
            action.run();
        } finally {
            if (previous == null) {
                SNEAK_OVERRIDES.remove(uuid);
            } else {
                SNEAK_OVERRIDES.put(uuid, previous);
            }
        }
    }

    /**
     * Installs the validated prediction input state until the next validated
     * edge. Unlike {@link #withSneakingOverride(ServerPlayerEntity, boolean, Runnable)},
     * this state is intentionally visible to later ability progress ticks.
     */
    public static void withSelectedSlotPacketSuppressed(ServerPlayerEntity player, Runnable action) {
        if (player == null || action == null) return;
        UUID uuid = player.getUuid();
        boolean added = SUPPRESSED_SELECTED_SLOT_PACKETS.add(uuid);
        try {
            action.run();
        } finally {
            if (added) SUPPRESSED_SELECTED_SLOT_PACKETS.remove(uuid);
        }
    }

    private static void syncScoreboard(ServerPlayerEntity player, Scoreboard board) {
        UUID uuid = player.getUuid();
        net.minecraft.scoreboard.Scoreboard nativeBoard = NATIVE_SCOREBOARDS.computeIfAbsent(
                uuid, ignored -> new net.minecraft.scoreboard.Scoreboard());
        ScoreboardSnapshot previous = SCOREBOARD_SNAPSHOTS.getOrDefault(uuid, ScoreboardSnapshot.EMPTY);
        ScoreboardSnapshot desired = scoreboardSnapshot(board);

        for (Map.Entry<String, TeamSnapshot> entry : desired.teams().entrySet()) {
            String name = entry.getKey();
            TeamSnapshot wanted = entry.getValue();
            TeamSnapshot old = previous.teams().get(name);
            net.minecraft.scoreboard.Team nativeTeam = nativeBoard.getTeam(name);
            if (old == null || nativeTeam == null) {
                if (nativeTeam != null) nativeBoard.removeTeam(nativeTeam);
                nativeTeam = nativeBoard.addTeam(name);
                nativeTeam.setPrefix(legacyText(wanted.prefix()));
                nativeTeam.setSuffix(legacyText(wanted.suffix()));
                for (String playerName : wanted.entries()) nativeBoard.addScoreHolderToTeam(playerName, nativeTeam);
                player.networkHandler.sendPacket(TeamS2CPacket.updateTeam(nativeTeam, true));
                continue;
            }

            boolean informationChanged = !old.prefix().equals(wanted.prefix()) || !old.suffix().equals(wanted.suffix());
            if (informationChanged) {
                nativeTeam.setPrefix(legacyText(wanted.prefix()));
                nativeTeam.setSuffix(legacyText(wanted.suffix()));
                player.networkHandler.sendPacket(TeamS2CPacket.updateTeam(nativeTeam, false));
            }
            for (String playerName : old.entries()) {
                if (wanted.entries().contains(playerName)) continue;
                nativeBoard.removeScoreHolderFromTeam(playerName, nativeTeam);
                player.networkHandler.sendPacket(TeamS2CPacket.changePlayerTeam(
                        nativeTeam, playerName, TeamS2CPacket.Operation.REMOVE));
            }
            for (String playerName : wanted.entries()) {
                if (old.entries().contains(playerName)) continue;
                nativeBoard.addScoreHolderToTeam(playerName, nativeTeam);
                player.networkHandler.sendPacket(TeamS2CPacket.changePlayerTeam(
                        nativeTeam, playerName, TeamS2CPacket.Operation.ADD));
            }
        }
        for (String name : previous.teams().keySet()) {
            if (desired.teams().containsKey(name)) continue;
            net.minecraft.scoreboard.Team nativeTeam = nativeBoard.getTeam(name);
            if (nativeTeam == null) continue;
            player.networkHandler.sendPacket(TeamS2CPacket.updateRemovedTeam(nativeTeam));
            nativeBoard.removeTeam(nativeTeam);
        }

        for (Map.Entry<String, ObjectiveSnapshot> entry : desired.objectives().entrySet()) {
            String name = entry.getKey();
            ObjectiveSnapshot wanted = entry.getValue();
            ObjectiveSnapshot old = previous.objectives().get(name);
            ScoreboardObjective nativeObjective = nativeBoard.getNullableObjective(name);
            if (old == null || nativeObjective == null) {
                if (nativeObjective != null) nativeBoard.removeObjective(nativeObjective);
                nativeObjective = nativeBoard.addObjective(
                        name, ScoreboardCriterion.DUMMY, legacyText(wanted.title()),
                        ScoreboardCriterion.RenderType.INTEGER, false, null);
                player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(nativeObjective, 0));
                old = null;
            } else if (!old.title().equals(wanted.title())) {
                nativeObjective.setDisplayName(legacyText(wanted.title()));
                player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(nativeObjective, 2));
            }

            Map<String, Integer> oldScores = old == null ? Map.of() : old.scores();
            for (String scoreHolder : oldScores.keySet()) {
                if (!wanted.scores().containsKey(scoreHolder)) {
                    player.networkHandler.sendPacket(new ScoreboardScoreResetS2CPacket(
                            scoreHolder, name));
                }
            }
            for (Map.Entry<String, Integer> score : wanted.scores().entrySet()) {
                if (Objects.equals(oldScores.get(score.getKey()), score.getValue())) continue;
                player.networkHandler.sendPacket(new ScoreboardScoreUpdateS2CPacket(
                        score.getKey(), name, score.getValue(), Optional.empty(), Optional.empty()));
            }
        }

        if (!Objects.equals(previous.sidebarObjective(), desired.sidebarObjective())) {
            ScoreboardObjective sidebar = desired.sidebarObjective() == null
                    ? null : nativeBoard.getNullableObjective(desired.sidebarObjective());
            nativeBoard.setObjectiveSlot(ScoreboardDisplaySlot.SIDEBAR, sidebar);
            player.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(
                    ScoreboardDisplaySlot.SIDEBAR, sidebar));
        }
        for (String name : previous.objectives().keySet()) {
            if (desired.objectives().containsKey(name)) continue;
            ScoreboardObjective nativeObjective = nativeBoard.getNullableObjective(name);
            if (nativeObjective == null) continue;
            player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(nativeObjective, 1));
            nativeBoard.removeObjective(nativeObjective);
        }

        SCOREBOARD_SNAPSHOTS.put(uuid, desired);
    }

    private static ScoreboardSnapshot scoreboardSnapshot(
            Scoreboard board) {
        Map<String, TeamSnapshot> teams = new LinkedHashMap<>();
        for (Team team : board.getTeams()) {
            teams.put(team.getName(), new TeamSnapshot(team.getPrefix(), team.getSuffix(), Set.copyOf(team.getEntries())));
        }
        Map<String, ObjectiveSnapshot> objectives = new LinkedHashMap<>();
        String sidebarObjective = null;
        for (Objective objective : board.getObjectives()) {
            Map<String, Integer> scores = new LinkedHashMap<>();
            for (Map.Entry<String, Objective.Score> score
                    : objective.getScores().entrySet()) {
                scores.put(score.getKey(), score.getValue().getScore());
            }
            objectives.put(objective.getName(), new ObjectiveSnapshot(objective.getTitle(), Map.copyOf(scores)));
            if (objective.getDisplaySlot() == DisplaySlot.SIDEBAR) {
                sidebarObjective = objective.getName();
            }
        }
        return new ScoreboardSnapshot(Map.copyOf(teams), Map.copyOf(objectives), sidebarObjective);
    }

    public static Vec3d vector(Vector value) {
        return new Vec3d(value.getX(), value.getY(), value.getZ());
    }

    static ParticleEffect particle(Particle value, Object data) {
        if (value == null) return null;

        final String particleName = value.name();
        ParticleType<?> type = particleType(particleName);

        if (particleName.equals("DUST") && data instanceof Particle.DustOptions dust) {
            ParticleEffect effect = dustParticle(dust);
            if (effect != null) return effect;
        }
        if (particleName.equals("ENTITY_EFFECT") && data instanceof Color color) {
            return TintedParticleEffect.create(
                    ParticleTypes.ENTITY_EFFECT, opaqueRgb(color));
        }
        if (particleName.equals("EFFECT") && data instanceof Color color) {
            return EffectParticleEffect.of(
                    ParticleTypes.EFFECT, color.asRGB(), 1.0F);
        }
        if (particleName.equals("EFFECT") && data instanceof Particle.Spell spell) {
            return EffectParticleEffect.of(
                    ParticleTypes.EFFECT, spell.getColor().asRGB(), 1.0F);
        }
        if ((particleName.equals("ITEM") || particleName.equals("ITEM_CRACK")) && data instanceof ItemStack stack) {
            ParticleEffect effect = itemParticle(stack);
            if (effect != null) return effect;
        }
        if ((particleName.equals("BLOCK") || particleName.equals("BLOCK_CRACK") || particleName.equals("BLOCK_DUST") || particleName.equals("FALLING_DUST"))
                && data instanceof BlockData blockData) {
            ParticleEffect effect = blockStateParticle(type, blockData);
            if (effect != null) return effect;
        }
        if (type instanceof SimpleParticleType simple) return simple;
        return null;
    }

    private static ParticleType<?> particleType(String name) {
        for (String candidate : particleAliases(name)) {
            try {
                Identifier id = Identifier.ofVanilla(candidate);
                if (Registries.PARTICLE_TYPE.containsId(id)) {
                    return Registries.PARTICLE_TYPE.get(id);
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static List<String> particleAliases(String name) {
        if (name == null || name.isBlank()) return List.of();
        String key = name.trim().toUpperCase(Locale.ROOT);
        String lower = key.toLowerCase(Locale.ROOT);
        String vanilla = lower.replace(' ', '_');
        List<String> aliases = new ArrayList<>();
        switch (key) {
            case "BLOCK_CRACK", "BLOCK_DUST" -> aliases.add("block");
            case "CRIT_MAGIC", "MAGIC_CRIT" -> aliases.add("enchanted_hit");
            case "DRIP_LAVA" -> aliases.add("dripping_lava");
            case "DRIP_WATER" -> aliases.add("dripping_water");
            case "ENCHANTMENT_TABLE" -> aliases.add("enchant");
            case "EXPLOSION_HUGE", "HUGE_EXPLOSION" -> aliases.add("explosion_emitter");
            case "EXPLOSION_LARGE", "LARGE_EXPLODE" -> aliases.add("explosion");
            case "EXPLOSION_NORMAL", "EXPLODE" -> aliases.add("poof");
            case "FIREWORKS_SPARK" -> aliases.add("firework");
            case "MOB_APPEARANCE" -> aliases.add("elder_guardian");
            case "REDSTONE", "RED_DUST" -> aliases.add("dust");
            case "SLIME" -> aliases.add("item_slime");
            case "SMOKE_NORMAL", "SMOKE" -> aliases.add("smoke");
            case "SMOKE_LARGE", "LARGE_SMOKE" -> aliases.add("large_smoke");
            case "SNOW_SHOVEL" -> aliases.add("block");
            case "SNOWBALL", "SNOWBALL_PROOF" -> aliases.add("item_snowball");
            case "SPELL" -> aliases.add("effect");
            case "SPELL_INSTANT", "INSTANT_SPELL" -> aliases.add("instant_effect");
            case "SPELL_MOB", "MOB_SPELL", "SPELL_MOB_AMBIENT", "MOB_SPELL_AMBIENT" -> aliases.add("entity_effect");
            case "SPELL_WITCH", "WITCH_SPELL" -> aliases.add("witch");
            case "TOTEM" -> aliases.add("totem_of_undying");
            case "TOWN_AURA" -> aliases.add("mycelium");
            case "VILLAGER_ANGRY", "ANGRY_VILLAGER" -> aliases.add("angry_villager");
            case "VILLAGER_HAPPY", "HAPPY_VILLAGER" -> aliases.add("happy_villager");
            case "WATER_BUBBLE", "BUBBLE" -> aliases.add("bubble");
            case "WATER_DROP" -> aliases.add("rain");
            case "WATER_SPLASH", "SPLASH" -> aliases.add("splash");
            case "WATER_WAKE", "WAKE" -> aliases.add("fishing");
            case "COOL_AIR_PARTICLE" -> aliases.add("dust");
            case "ITEM_CRACK" -> aliases.add("item");
            default -> { }
        }
        aliases.add(vanilla);
        return aliases;
    }

    private static int opaqueRgb(Color color) {
        if (color == null) color = Color.WHITE;
        return 0xFF000000 | color.asRGB();
    }

    private static ParticleEffect dustParticle(Particle.DustOptions dust) {
        try {
            Color color = dust.getColor();
            return new DustParticleEffect(opaqueRgb(color), dust.getSize());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ParticleEffect itemParticle(ItemStack stack) {
        try {
            return new ItemStackParticleEffect(ParticleTypes.ITEM, itemStack(stack));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ParticleEffect blockStateParticle(ParticleType<?> type,
                                                                            BlockData data) {
        try {
            return new BlockStateParticleEffect(
                    (ParticleType<BlockStateParticleEffect>) type,
                    blockState(data));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static BlockState blockState(BlockData data) {
        if (data == null) data = new BlockData(Material.AIR);
        if (data.getClass() == BlockData.class && data.getExactState() != null) {
            final BlockState exact = blockState(data.getExactState());
            if (material(exact) == data.getMaterial()) return exact;
        }
        net.minecraft.block.Block block = material(data.getMaterial());
        BlockState state = block.getDefaultState();
        if (data instanceof Levelled levelled) {
            state = withBestLevel(state, levelled.getLevel());
            if (state.contains(Properties.WATERLOGGED)) {
                state = state.with(Properties.WATERLOGGED, levelled.isWaterlogged());
            }
        } else if (data instanceof Fire fire) {
            state = withFireFaces(state, fire);
        } else if (data instanceof Snow snow && state.contains(Properties.LAYERS)) {
            state = state.with(Properties.LAYERS, Math.max(1, Math.min(8, snow.getLayers())));
        }
        return state;
    }

    /**
     * Decodes the canonical vanilla form used by Bukkit and Fabric, for
     * example minecraft:oak_stairs[facing=east,half=top,...]. Unknown blocks,
     * properties and values fail closed to a valid registered/default state.
     */
    public static BlockState blockState(final String serialized) {
        if (serialized == null || serialized.isBlank()) {
            return material(Material.AIR).getDefaultState();
        }
        final String value = serialized.trim();
        final int bracket = value.indexOf('[');
        final String blockKey = bracket < 0 ? value : value.substring(0, bracket);
        final Identifier id = Identifier.tryParse(blockKey);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            return material(Material.AIR).getDefaultState();
        }

        final net.minecraft.block.Block block = Registries.BLOCK.get(id);
        BlockState state = block.getDefaultState();
        if (bracket < 0 || !value.endsWith("]")) return state;
        final String properties = value.substring(bracket + 1, value.length() - 1);
        if (properties.isBlank()) return state;
        for (String assignment : properties.split(",")) {
            final int separator = assignment.indexOf('=');
            if (separator <= 0 || separator == assignment.length() - 1) continue;
            final Property<?> property = block.getStateManager().getProperty(
                    assignment.substring(0, separator).trim());
            if (property == null) continue;
            state = withParsedProperty(state, property, assignment.substring(separator + 1).trim());
        }
        return state;
    }

    static void setFallingBlockState(FallingBlockEntity entity, BlockState state) {
        try {
            Field field = null;
            for (Field candidate : FallingBlockEntity.class.getDeclaredFields()) {
                if (Modifier.isStatic(candidate.getModifiers())) continue;
                if (candidate.getType() == BlockState.class) {
                    field = candidate;
                    break;
                }
            }
            if (field == null) throw new NoSuchFieldException("non-static BlockState");
            field.setAccessible(true);
            field.set(entity, state);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to set Fabric falling block state", ex);
        }
    }

    public static BlockData blockData(BlockState state) {
        BlockData data = material(state).createBlockData();
        if (data instanceof Levelled levelled) {
            OptionalInt level = readBestLevel(state);
            if (level.isPresent()) levelled.setLevel(level.getAsInt());
            if (state.contains(Properties.WATERLOGGED)) {
                levelled.setWaterlogged(state.get(Properties.WATERLOGGED));
            }
        } else if (data instanceof Fire fire) {
            readFireFaces(state, fire);
        } else if (data instanceof Snow snow && state.contains(Properties.LAYERS)) {
            snow.setLayers(state.get(Properties.LAYERS));
        } else if (data.getClass() == BlockData.class) {
            data.setExactState(serializeBlockState(state));
        }
        return data;
    }

    private static String serializeBlockState(final BlockState state) {
        final StringBuilder value = new StringBuilder(Registries.BLOCK.getId(state.getBlock()).toString());
        boolean first = true;
        for (Property<?> property : state.getProperties()) {
            value.append(first ? '[' : ',');
            first = false;
            value.append(property.getName()).append('=').append(propertyValueName(state, property));
        }
        if (!first) value.append(']');
        return value.toString();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyValueName(final BlockState state, final Property property) {
        return property.name(state.get(property));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState withParsedProperty(final BlockState state, final Property property,
                                                 final String value) {
        final Optional<? extends Comparable> parsed = property.parse(value);
        return parsed.isPresent() ? state.with(property, parsed.get()) : state;
    }

    private static BlockState withFireFaces(BlockState state, Fire fire) {
        state = withBooleanProperty(state, Properties.NORTH, fire.hasFace(BlockFace.NORTH));
        state = withBooleanProperty(state, Properties.EAST, fire.hasFace(BlockFace.EAST));
        state = withBooleanProperty(state, Properties.SOUTH, fire.hasFace(BlockFace.SOUTH));
        state = withBooleanProperty(state, Properties.WEST, fire.hasFace(BlockFace.WEST));
        state = withBooleanProperty(state, Properties.UP, fire.hasFace(BlockFace.UP));
        return state;
    }

    private static void readFireFaces(BlockState state, Fire fire) {
        fire.setFace(BlockFace.NORTH, getBooleanProperty(state, Properties.NORTH));
        fire.setFace(BlockFace.EAST, getBooleanProperty(state, Properties.EAST));
        fire.setFace(BlockFace.SOUTH, getBooleanProperty(state, Properties.SOUTH));
        fire.setFace(BlockFace.WEST, getBooleanProperty(state, Properties.WEST));
        fire.setFace(BlockFace.UP, getBooleanProperty(state, Properties.UP));
    }

    private static BlockState withBooleanProperty(BlockState state, BooleanProperty property, boolean value) {
        return state.contains(property) ? state.with(property, value) : state;
    }

    private static boolean getBooleanProperty(BlockState state, BooleanProperty property) {
        return state.contains(property) && state.get(property);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState withBestLevel(BlockState state, int requestedLevel) {
        for (Property<?> property : state.getProperties()) {
            if (!"level".equals(property.getName()) || !(property instanceof IntProperty intProperty)) continue;
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (Integer value : intProperty.getValues()) {
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            if (min <= max) {
                int clamped = clampLevel(requestedLevel, min, max);
                return state.with((Property) intProperty, clamped);
            }
        }
        return state;
    }

    private static OptionalInt readBestLevel(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (!"level".equals(property.getName()) || !(property instanceof IntProperty intProperty)) continue;
            return OptionalInt.of(state.get(intProperty));
        }
        return OptionalInt.empty();
    }

    private static int clampLevel(int level, int min, int max) {
        return Math.max(min, Math.min(max, level));
    }

    static void playConfigSound(ServerWorld world, double x, double y, double z, String sound, net.minecraft.sound.SoundCategory category, float volume, float pitch) {
        if (world == null || sound == null || sound.isBlank()) return;
        for (String candidate : configAlternatives(sound)) {
            try {
                Identifier id = soundIdentifier(candidate);
                if (id == null) continue;
                SoundEvent event = SoundEvent.of(id);
                world.playSound(null, x, y, z, event, category, volume, pitch);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    static List<String> configAlternatives(String raw) {
        if (raw == null) return List.of();
        String withoutQuotes = raw.trim();
        if ((withoutQuotes.startsWith("'") && withoutQuotes.endsWith("'")) || (withoutQuotes.startsWith("\"") && withoutQuotes.endsWith("\""))) {
            withoutQuotes = withoutQuotes.substring(1, withoutQuotes.length() - 1);
        }
        List<String> out = new ArrayList<>();
        for (String hashPart : withoutQuotes.split("#")) {
            for (String commaPart : hashPart.split(",")) {
                String candidate = commaPart.trim();
                if (!candidate.isEmpty()) out.add(candidate);
            }
        }
        return out;
    }

    static Identifier soundIdentifier(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if (!key.contains(":") && !key.contains(".")) {
            String[] parts = key.split("_");
            if (parts.length >= 2) {
                for (String candidate : bukkitSoundCandidates(parts)) {
                    Identifier id = Identifier.ofVanilla(candidate);
                    if (Registries.SOUND_EVENT.containsId(id)) {
                        return id;
                    }
                }
            }
            key = key.replace('_', '.');
        }
        if (key.indexOf(':') >= 0) return Identifier.of(key);
        return Identifier.ofVanilla(key);
    }

    private static List<String> bukkitSoundCandidates(String[] parts) {
        List<String> candidates = new ArrayList<>();
        if (parts.length == 0) {
            return candidates;
        }
        buildBukkitSoundCandidate(parts, 1, new StringBuilder(parts[0]), candidates);
        return candidates;
    }

    private static void buildBukkitSoundCandidate(String[] parts, int index, StringBuilder current, List<String> candidates) {
        if (index >= parts.length) {
            candidates.add(current.toString());
            return;
        }

        int length = current.length();
        current.append('.').append(parts[index]);
        buildBukkitSoundCandidate(parts, index + 1, current, candidates);
        current.setLength(length);

        current.append('_').append(parts[index]);
        buildBukkitSoundCandidate(parts, index + 1, current, candidates);
        current.setLength(length);
    }

    public static Material material(BlockState state) {
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        try {
            return Material.valueOf(id.getPath().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Material.AIR;
        }
    }

    public static net.minecraft.block.Block material(Material material) {
        Identifier id = Identifier.ofVanilla(material.name().toLowerCase(Locale.ROOT));
        return Registries.BLOCK.get(id);
    }

    private static ItemStack itemStack(net.minecraft.item.ItemStack value) {
        if (value == null || value.isEmpty()) return new ItemStack(Material.AIR, 0);
        Identifier id = Registries.ITEM.getId(value.getItem());
        Material material;
        try { material = Material.valueOf(id.getPath().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { material = Material.AIR; }
        FabricItemStack stack = new FabricItemStack(material, value.getCount());
        if (value.isDamageable()) stack.setDurability((short) value.getDamage());
        return stack;
    }

    static net.minecraft.item.ItemStack itemStack(ItemStack value) {
        if (value == null || value.getType() == null || value.getType() == Material.AIR || value.getAmount() <= 0) return net.minecraft.item.ItemStack.EMPTY;
        net.minecraft.item.Item item = Registries.ITEM.get(Identifier.ofVanilla(value.getType().name().toLowerCase(Locale.ROOT)));
        net.minecraft.item.ItemStack nativeStack = new net.minecraft.item.ItemStack(item, value.getAmount());
        if (value.getDurability() > 0 && nativeStack.isDamageable()) nativeStack.setDamage(value.getDurability());
        return nativeStack;
    }

    static RegistryEntry<StatusEffect> status(
            PotionEffectType type) {
        return switch (type.name()) {
            case "SPEED" -> StatusEffects.SPEED;
            case "SLOWNESS" -> StatusEffects.SLOWNESS;
            case "HASTE" -> StatusEffects.HASTE;
            case "MINING_FATIGUE" -> StatusEffects.MINING_FATIGUE;
            case "STRENGTH" -> StatusEffects.STRENGTH;
            case "INSTANT_HEALTH" -> StatusEffects.INSTANT_HEALTH;
            case "INSTANT_DAMAGE" -> StatusEffects.INSTANT_DAMAGE;
            case "JUMP_BOOST" -> StatusEffects.JUMP_BOOST;
            case "NAUSEA" -> StatusEffects.NAUSEA;
            case "REGENERATION" -> StatusEffects.REGENERATION;
            case "RESISTANCE" -> StatusEffects.RESISTANCE;
            case "FIRE_RESISTANCE" -> StatusEffects.FIRE_RESISTANCE;
            case "WATER_BREATHING" -> StatusEffects.WATER_BREATHING;
            case "INVISIBILITY" -> StatusEffects.INVISIBILITY;
            case "BLINDNESS" -> StatusEffects.BLINDNESS;
            case "NIGHT_VISION" -> StatusEffects.NIGHT_VISION;
            case "HUNGER" -> StatusEffects.HUNGER;
            case "WEAKNESS" -> StatusEffects.WEAKNESS;
            case "POISON" -> StatusEffects.POISON;
            case "WITHER" -> StatusEffects.WITHER;
            case "HEALTH_BOOST" -> StatusEffects.HEALTH_BOOST;
            case "ABSORPTION" -> StatusEffects.ABSORPTION;
            case "SATURATION" -> StatusEffects.SATURATION;
            case "GLOWING" -> StatusEffects.GLOWING;
            case "LEVITATION" -> StatusEffects.LEVITATION;
            case "SLOW_FALLING" -> StatusEffects.SLOW_FALLING;
            case "DOLPHINS_GRACE" -> StatusEffects.DOLPHINS_GRACE;
            default -> throw new IllegalArgumentException("Unsupported potion effect " + type.name());
        };
    }

    private static PotionEffect effect(
            PotionEffectType type,
            StatusEffectInstance nativeEffect) {
        return nativeEffect == null ? null : new PotionEffect(type, nativeEffect.getDuration(), nativeEffect.getAmplifier());
    }

    private static final class WorldView extends World {
        private final ServerWorld value;

        private WorldView(ServerWorld value) {
            this.value = value;
        }

        @Override
        public String getName() {
            return value.getRegistryKey().getValue().toString();
        }

        @Override
        public long getTime() {
            return value.getTimeOfDay();
        }

        @Override
        public long getFullTime() {
            return value.getTime();
        }

        @Override
        public int getMinHeight() {
            return value.getBottomY();
        }

        @Override
        public int getMaxHeight() {
            return value.getTopYInclusive() + 1;
        }

        @Override
        public Block getBlockAt(int x, int y, int z) {
            return block(value, new BlockPos(x, y, z));
        }

        @Override
        public boolean isChunkLoaded(int x, int z) {
            return value.isChunkLoaded(x, z);
        }

        @Override
        public Collection<Player> getPlayers() {
            return value.getPlayers().stream().map(FabricMC::player).toList();
        }

        @Override
        public List<Entity> getEntities() {
            Box bounds = new Box(-30_000_000, getMinHeight(), -30_000_000, 30_000_000, getMaxHeight(), 30_000_000);
            return value.getOtherEntities(null, bounds, entity -> true).stream().map(FabricMC::entity).toList();
        }

        @Override
        public Collection<Entity> getNearbyEntities(BoundingBox box,
                                                               Predicate<Entity> filter) {
            Box nativeBox = new Box(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ());
            Map<Integer, Entity> entities = new LinkedHashMap<>();
            for (net.minecraft.entity.Entity nativeEntity : value.getOtherEntities(null, nativeBox, entity -> true)) {
                Entity wrapped = FabricMC.entity(nativeEntity);
                if (wrapped != null && (filter == null || filter.test(wrapped))) {
                    entities.put(nativeEntity.getId(), wrapped);
                }
            }
            // Some Fabric/vanilla query paths treat players specially. Bukkit's
            // getNearbyEntities includes players, and RaiseEarth relies on that to
            // push players standing in the moving column. Add players explicitly.
            for (ServerPlayerEntity nativePlayer : value.getPlayers()) {
                if (!nativeBox.intersects(nativePlayer.getBoundingBox())) {
                    continue;
                }
                Entity wrapped = FabricMC.player(nativePlayer);
                if (filter == null || filter.test(wrapped)) {
                    entities.put(nativePlayer.getId(), wrapped);
                }
            }
            return entities.values();
        }

        @Override
        public Item dropItem(Location location, ItemStack item) {
            ItemEntity entity = new ItemEntity(value, location.getX(), location.getY(), location.getZ(), itemStack(item));
            value.spawnEntity(entity);
            return new DroppedItemView(entity);
        }

        @Override
        public Item dropItemNaturally(Location location, ItemStack item) {
            ItemEntity entity = new ItemEntity(
                    value,
                    location.getX() + value.random.nextDouble() * 0.5 + 0.25,
                    location.getY() + value.random.nextDouble() * 0.5 + 0.25,
                    location.getZ() + value.random.nextDouble() * 0.5 + 0.25,
                    itemStack(item));
            value.spawnEntity(entity);
            return new DroppedItemView(entity);
        }

        @Override
        public void playSound(Location location, Sound sound, float volume, float pitch) {
            playSound(location, sound, SoundCategory.MASTER, volume, pitch);
        }

        @Override
        public void playSound(Location location, String sound, float volume, float pitch) {
            FabricMC.playConfigSound(value, location.getX(), location.getY(), location.getZ(), sound, net.minecraft.sound.SoundCategory.MASTER, volume, pitch);
        }

        @Override
        public void playSound(Location location, Sound sound, SoundCategory category, float volume, float pitch) {
            FabricMC.playConfigSound(value, location.getX(), location.getY(), location.getZ(), sound == null ? null : sound.name(),
                    net.minecraft.sound.SoundCategory.valueOf(category.name()), volume, pitch);
        }

        @Override
        public void playEffect(Location location, Effect effect, int data) {
            playEffect(location, effect, Integer.valueOf(data));
        }

        @Override
        public void playEffect(Location location, Effect effect, Object data) {
            if (location == null || effect == null) return;
            String name = effect.name().toUpperCase(Locale.ROOT);
            switch (name) {
                case "EXTINGUISH" -> {
                    FabricMC.playConfigSound(value, location.getX(), location.getY(), location.getZ(), "block.fire.extinguish", net.minecraft.sound.SoundCategory.BLOCKS, 0.5F, 1.8F);
                    spawnParticle(Particle.SMOKE, location, 8, 0.25, 0.25, 0.25, 0.02);
                }
                case "GHAST_SHOOT" -> {
                    FabricMC.playConfigSound(value, location.getX(), location.getY(), location.getZ(), "entity.ghast.shoot", net.minecraft.sound.SoundCategory.HOSTILE, 0.8F, 1.0F);
                    spawnParticle(Particle.EXPLOSION, location, 2, 0.05, 0.05, 0.05, 0.0);
                }
                case "MOBSPAWNER_FLAMES" ->
                        spawnParticle(Particle.FLAME, location, 18, 0.45, 0.45, 0.45, 0.01);
                case "SMOKE" ->
                        spawnParticle(Particle.SMOKE, location, 10, 0.25, 0.25, 0.25, 0.02);
                case "STEP_SOUND" -> {
                    BlockData blockData = null;
                    if (data instanceof BlockData bd) {
                        blockData = bd;
                    } else if (data instanceof Material material) {
                        blockData = material.createBlockData();
                    }
                    if (blockData == null) {
                        blockData = location.getBlock().getBlockData();
                    }
                    spawnParticle(Particle.valueOf("BLOCK_CRACK"), location, 18, 0.35, 0.25, 0.35, 0.05, blockData);
                }
                default -> { }
            }
        }

        @Override
        public void strikeLightningEffect(Location location, boolean flash) {
            LightningEntity lightning = new LightningEntity(EntityType.LIGHTNING_BOLT, value);
            lightning.refreshPositionAfterTeleport(location.getX(), location.getY(), location.getZ());
            lightning.setCosmetic(true);
            value.spawnEntity(lightning);
        }

        @Override
        public void playEffect(Location location, Effect effect, int data, int radius) {
            playEffect(location, effect, Integer.valueOf(data));
        }

        @Override
        public <T> void spawnParticle(Particle particle, Location location, int count,
                                      double ox, double oy, double oz, double extra, T data) {
            try {
                ParticleEffect effect = FabricMC.particle(particle, data);
                if (effect == null) return;
                for (ServerPlayerEntity viewer : value.getPlayers()) {
                    value.spawnParticles(viewer, effect, false, false, location.getX(), location.getY(), location.getZ(), count, ox, oy, oz, extra);
                }
            } catch (Throwable ignored) { }
        }

        @Override
        public void spawnParticle(Particle particle, Location location, int count,
                                  double ox, double oy, double oz, double extra) {
            spawnParticle(particle, location, count, ox, oy, oz, extra, null);
        }

        @Override
        public void spawnParticle(Particle particle, Location location, int count,
                                  double ox, double oy, double oz) {
            spawnParticle(particle, location, count, ox, oy, oz, 0, null);
        }

        @Override
        public FallingBlock spawnFallingBlock(Location location, BlockData data) {
            FallingBlockEntity nativeBlock = new FallingBlockEntity(EntityType.FALLING_BLOCK, value);
            nativeBlock.refreshPositionAndAngles(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
            setFallingBlockState(nativeBlock, FabricMC.blockState(data));
            nativeBlock.dropItem = false;
            nativeBlock.setDestroyedOnLanding();
            value.spawnEntity(nativeBlock);
            return new FallingBlockView(nativeBlock);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T spawn(Location location, Class<T> type) {
            if (type == BlockDisplay.class) {
                DisplayEntity.BlockDisplayEntity display =
                        new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY, value);
                display.refreshPositionAndAngles(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
                value.spawnEntity(display);
                return (T) new BlockDisplayView(display);
            }
            if (type == ShulkerBullet.class) {
                ShulkerBulletEntity bullet =
                        new ShulkerBulletEntity(EntityType.SHULKER_BULLET, value);
                bullet.refreshPositionAndAngles(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
                value.spawnEntity(bullet);
                return (T) new ShulkerBulletView(bullet);
            }
            if (type == ArmorStand.class) {
                ArmorStandEntity armorStand =
                        new ArmorStandEntity(value, location.getX(), location.getY(), location.getZ());
                armorStand.refreshPositionAndAngles(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
                value.spawnEntity(armorStand);
                return (T) new ArmorStandView(armorStand);
            }
            if (type == Fireball.class) {
                SmallFireballEntity fireball =
                        new SmallFireballEntity(EntityType.SMALL_FIREBALL, value);
                fireball.refreshPositionAndAngles(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
                value.spawnEntity(fireball);
                return (T) new FireballView(fireball);
            }
            return super.spawn(location, type);
        }

        @Override
        public Arrow spawnArrow(Location location, Vector direction, float speed, float spread) {
            ArrowEntity arrow = new ArrowEntity(
                    value, location.getX(), location.getY(), location.getZ(),
                    new net.minecraft.item.ItemStack(Items.ARROW), null);
            Vector normalized = direction.clone().normalize();
            arrow.setVelocity(normalized.getX(), normalized.getY(), normalized.getZ(), speed, spread);
            value.spawnEntity(arrow);
            return new ArrowView(arrow);
        }

        @Override
        public RayTraceResult rayTraceBlocks(Location origin, Vector direction, double range, FluidCollisionMode mode, boolean ignorePassable) {
            Vec3d start = new Vec3d(origin.getX(), origin.getY(), origin.getZ());
            Vec3d end = start.add(FabricMC.vector(direction.clone().normalize().multiply(range)));
            RaycastContext.ShapeType shape = ignorePassable ? RaycastContext.ShapeType.COLLIDER : RaycastContext.ShapeType.OUTLINE;
            RaycastContext.FluidHandling fluid = mode != null && !mode.name().equals("NEVER") ? RaycastContext.FluidHandling.ANY : RaycastContext.FluidHandling.NONE;
            BlockHitResult hit = value.raycast(new RaycastContext(start, end, shape, fluid, ShapeContext.absent()));
            if (hit == null || hit.getType() == HitResult.Type.MISS) {
                return new RayTraceResult(FabricMC.vector(end));
            }
            return new RayTraceResult(FabricMC.vector(hit.getPos()));
        }

        @Override
        public RayTraceResult rayTrace(Location origin, Vector direction, double range, FluidCollisionMode mode, boolean ignorePassable, double raySize, Predicate<Entity> filter) {
            RayTraceResult blockHit = rayTraceBlocks(origin, direction, range, mode, ignorePassable);
            Vec3d start = new Vec3d(origin.getX(), origin.getY(), origin.getZ());
            Vec3d dir = FabricMC.vector(direction.clone().normalize());
            Vec3d end = start.add(dir.multiply(range));
            double maxDistanceSquared = blockHit == null ? range * range : start.squaredDistanceTo(FabricMC.vector(blockHit.getHitPosition()));
            Box searchBox = new Box(start, end).expand(raySize);
            Entity best = null;
            Vec3d bestHit = null;
            for (net.minecraft.entity.Entity nativeEntity : value.getOtherEntities(null, searchBox, nativeEntity -> true)) {
                Entity wrapped = FabricMC.entity(nativeEntity);
                if (wrapped == null || (filter != null && !filter.test(wrapped))) continue;
                Box expanded = nativeEntity.getBoundingBox().expand(raySize);
                Optional<Vec3d> hit = expanded.raycast(start, end);
                if (hit.isEmpty()) continue;
                double distanceSquared = start.squaredDistanceTo(hit.get());
                if (distanceSquared <= maxDistanceSquared) {
                    maxDistanceSquared = distanceSquared;
                    best = wrapped;
                    bestHit = hit.get();
                }
            }
            if (best != null) return new RayTraceResult(FabricMC.vector(bestHit), best);
            return blockHit;
        }

        @Override
        public boolean createExplosion(Location location, float power) {
            value.createExplosion(null, location.getX(), location.getY(), location.getZ(), power, net.minecraft.world.World.ExplosionSourceType.NONE);
            return true;
        }

        @Override
        public boolean hasStorm() {
            return value.isRaining();
        }

        @Override
        public Block getHighestBlockAt(Location location) {
            int x = location.getBlockX();
            int z = location.getBlockZ();
            return block(value, new BlockPos(x, value.getTopY(Heightmap.Type.WORLD_SURFACE, x, z), z));
        }

        @Override
        public double getTemperature(int x, int y, int z) {
            return value.getBiome(new BlockPos(x, y, z)).value().getTemperature();
        }

        @Override
        public double getHumidity(int x, int y, int z) {
            return 0.5D;
        }

        @Override
        public Object handle() {
            return value;
        }

        @Override public boolean equals(Object other) { return other instanceof WorldView view && value.getRegistryKey().equals(view.value.getRegistryKey()); }
        @Override public int hashCode() { return value.getRegistryKey().hashCode(); }
    }

    private static final class LocationView extends Location {
        private ServerWorld world;
        private Vec3d pos;
        private float yaw, pitch;

        private LocationView(ServerWorld world, Vec3d pos, float yaw, float pitch) {
            this.world = world;
            this.pos = pos;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        @Override
        public World getWorld() {
            return FabricMC.world(world);
        }

        @Override
        public double getX() {
            return pos.x;
        }

        @Override
        public double getY() {
            return pos.y;
        }

        @Override
        public double getZ() {
            return pos.z;
        }

        @Override
        public void setX(double x) {
            pos = new Vec3d(x, pos.y, pos.z);
        }

        @Override
        public void setY(double y) {
            pos = new Vec3d(pos.x, y, pos.z);
        }

        @Override
        public void setZ(double z) {
            pos = new Vec3d(pos.x, pos.y, z);
        }

        @Override
        public float getYaw() {
            return yaw;
        }

        @Override
        public float getPitch() {
            return pitch;
        }

        @Override
        public void setYaw(float yaw) {
            this.yaw = yaw;
        }

        @Override
        public Vector getDirection() {
            double yawRadians = Math.toRadians(yaw);
            double pitchRadians = Math.toRadians(pitch);
            double horizontal = Math.cos(pitchRadians);
            return new Vector(-horizontal * Math.sin(yawRadians), -Math.sin(pitchRadians), horizontal * Math.cos(yawRadians));
        }

        @Override
        public Location setDirection(Vector direction) {
            if (direction == null || direction.lengthSquared() == 0) return this;
            Vector normalized = direction.clone().normalize();
            this.pitch = (float) Math.toDegrees(Math.asin(-normalized.getY()));
            this.yaw = (float) Math.toDegrees(Math.atan2(-normalized.getX(), normalized.getZ()));
            return this;
        }

        @Override
        public void setPitch(float pitch) {
            this.pitch = pitch;
        }

        @Override
        public Block getBlock() {
            return block(world, BlockPos.ofFloored(pos));
        }

        @Override
        public Vector toVector() {
            return vector(pos);
        }

        @Override
        public Location add(double x, double y, double z) {
            pos = pos.add(x, y, z);
            return this;
        }

        @Override
        public Location subtract(double x, double y, double z) {
            pos = pos.subtract(x, y, z);
            return this;
        }

        @Override
        public double distanceSquared(Location other) {
            double dx = pos.x - other.getX(), dy = pos.y - other.getY(), dz = pos.z - other.getZ();
            return dx * dx + dy * dy + dz * dz;
        }

        @Override
        public Location clone() {
            return location(world, pos, yaw, pitch);
        }

        @Override
        public Object handle() {
            return new LocationRef(world, pos, yaw, pitch);
        }

        @Override public boolean equals(Object other) {
            return other instanceof LocationView view && world.getRegistryKey().equals(view.world.getRegistryKey()) && pos.equals(view.pos);
        }
        @Override public int hashCode() { return Objects.hash(world.getRegistryKey(), pos); }
    }

    private static final class BlockView extends Block {
        private final ServerWorld world;
        private final BlockPos pos;

        private BlockView(ServerWorld world, BlockPos pos) {
            this.world = world;
            this.pos = pos.toImmutable();
        }

        private BlockState state() {
            return world.getBlockState(pos);
        }

        @Override
        public Material getType() {
            return material(state());
        }

        @Override
        public void setType(Material type) {
            setBlockData(type.createBlockData(), true);
        }

        @Override
        public void setType(Material type, boolean physics) {
            setBlockData(type.createBlockData(), physics);
        }

        @Override
        public BlockData getBlockData() {
            return FabricMC.blockData(state());
        }

        @Override
        public void setBlockData(BlockData data) {
            setBlockData(data, true);
        }

        @Override
        public void setBlockData(BlockData data, boolean physics) {
            if (data == null) return;
            prepareExternalWrite(data);
            int flags = physics
                    ? net.minecraft.block.Block.NOTIFY_ALL
                    : net.minecraft.block.Block.NOTIFY_LISTENERS
                            | net.minecraft.block.Block.FORCE_STATE
                            | net.minecraft.block.Block.SKIP_BLOCK_ADDED_CALLBACK;
            BlockState nativeState = FabricMC.blockState(data);
            world.setBlockState(pos, nativeState, flags);
        }

        private void prepareExternalWrite(final BlockData replacementData) {
            if (TempBlockSync.currentWorldMutation() != null) return;
            if (TempBlock.isTempBlock(this)) TempBlock.removeBlockBeforeWrite(this, replacementData);
            DirectBlockSync.beforeWorldChange(this, replacementData);
        }

        @Override
        public com.projectkorra.projectkorra.platform.mc.block.BlockState getState() {
            return new BlockStateView(this, state());
        }

        @Override
        public Location getLocation() {
            return location(world, Vec3d.of(pos));
        }

        @Override
        public World getWorld() {
            return FabricMC.world(world);
        }

        @Override
        public Block getRelative(BlockFace face) {
            return getRelative(face, 1);
        }

        @Override
        public Block getRelative(BlockFace face, int distance) {
            return switch (face) {
                case UP -> block(world, pos.up(distance));
                case DOWN -> block(world, pos.down(distance));
                case NORTH -> block(world, pos.north(distance));
                case SOUTH -> block(world, pos.south(distance));
                case EAST -> block(world, pos.east(distance));
                case WEST -> block(world, pos.west(distance));
                default -> this;
            };
        }

        @Override
        public Block getRelative(int x, int y, int z) {
            return block(world, pos.add(x, y, z));
        }

        @Override
        public int getX() {
            return pos.getX();
        }

        @Override
        public int getY() {
            return pos.getY();
        }

        @Override
        public int getZ() {
            return pos.getZ();
        }

        @Override
        public boolean isLiquid() {
            return !state().getFluidState().isEmpty();
        }

        @Override
        public boolean isSolid() {
            return getType().isSolid();
        }

        @Override
        public boolean isPassable() {
            return state().getCollisionShape(world, pos).isEmpty();
        }

        @Override
        public boolean isEmpty() {
            return state().isAir();
        }

        @Override
        public BoundingBox getBoundingBox() {
            VoxelShape shape = state().getCollisionShape(world, pos);
            if (shape.isEmpty()) {
                shape = state().getOutlineShape(world, pos);
            }
            if (shape.isEmpty()) {
                return new BoundingBox(
                        new Vector(pos.getX(), pos.getY(), pos.getZ()),
                        new Vector(pos.getX(), pos.getY(), pos.getZ()));
            }
            Box box = shape.getBoundingBox().offset(pos);
            return new BoundingBox(
                    new Vector(box.minX, box.minY, box.minZ),
                    new Vector(box.maxX, box.maxY, box.maxZ));
        }

        @Override
        public byte getLightLevel() {
            return (byte) world.getLightLevel(pos);
        }

        @Override
        public Collection<ItemStack> getDrops() {
            BlockState nativeState = state();
            List<net.minecraft.item.ItemStack> nativeDrops =
                    net.minecraft.block.Block.getDroppedStacks(nativeState, world, pos, world.getBlockEntity(pos));
            return nativeDrops.stream().map(FabricMC::itemStack).toList();
        }

        @Override
        public boolean breakNaturally() {
            if (state().isAir()) return false;
            prepareExternalWrite(Material.AIR.createBlockData());
            return world.breakBlock(pos, true);
        }

        @Override
        public boolean breakNaturally(ItemStack item) {
            return breakNaturally();
        }

        @Override
        public byte getData() {
            FluidState fluid = state().getFluidState();
            if (!fluid.isEmpty()) return (byte) (fluid.isStill() ? 0 : 1);
            OptionalInt level = readBestLevel(state());
            return (byte) level.orElse(0);
        }

        @Override
        public BlockFace getFace(Block block) {
            if (!(block instanceof BlockView other)) return null;
            int dx = Integer.compare(other.pos.getX() - pos.getX(), 0);
            int dy = Integer.compare(other.pos.getY() - pos.getY(), 0);
            int dz = Integer.compare(other.pos.getZ() - pos.getZ(), 0);
            if (dy > 0 && dx == 0 && dz == 0) return BlockFace.UP;
            if (dy < 0 && dx == 0 && dz == 0) return BlockFace.DOWN;
            if (dz < 0 && dx == 0 && dy == 0) return BlockFace.NORTH;
            if (dz > 0 && dx == 0 && dy == 0) return BlockFace.SOUTH;
            if (dx > 0 && dy == 0 && dz == 0) return BlockFace.EAST;
            if (dx < 0 && dy == 0 && dz == 0) return BlockFace.WEST;
            return null;
        }

        @Override
        public Biome getBiome() {
            String path = world.getBiome(pos).getKey()
                    .map(key -> key.getValue().getPath().toUpperCase(Locale.ROOT))
                    .orElse("DESERT");
            try {
                return Biome.valueOf(path);
            } catch (IllegalArgumentException ignored) {
                return Biome.DESERT;
            }
        }

        @Override
        public Object handle() {
            return new BlockRef(world, pos);
        }

        @Override public boolean equals(Object other) {
            return other instanceof BlockView view && world.getRegistryKey().equals(view.world.getRegistryKey()) && pos.equals(view.pos);
        }
        @Override public int hashCode() { return Objects.hash(world.getRegistryKey(), pos); }
    }

    private static final class BlockStateView extends com.projectkorra.projectkorra.platform.mc.block.BlockState {
        private final BlockView block;
        private final BlockState state;

        private BlockStateView(BlockView block, BlockState state) {
            this.block = block;
            this.state = state;
        }

        @Override public Material getType() { return FabricMC.material(state); }
        @Override public BlockData getBlockData() { return FabricMC.blockData(state); }
        @Override public Block getBlock() { return block; }
        @Override public Location getLocation() { return block.getLocation(); }
        @Override public boolean update(boolean force, boolean physics) {
            block.prepareExternalWrite(FabricMC.blockData(state));
            int flags = physics
                    ? net.minecraft.block.Block.NOTIFY_ALL
                    : net.minecraft.block.Block.NOTIFY_LISTENERS
                            | net.minecraft.block.Block.FORCE_STATE
                            | net.minecraft.block.Block.SKIP_BLOCK_ADDED_CALLBACK;
            return block.world.setBlockState(block.pos, state, flags);
        }
        @Override public Object handle() { return block.handle(); }
        @Override public boolean hasBlockEntity() { return block.world.getBlockEntity(block.pos) != null; }
    }

    private static class EntityView extends Entity {
        protected final net.minecraft.entity.Entity value;

        private EntityView(net.minecraft.entity.Entity value) {
            this.value = value;
        }

        @Override
        public UUID getUniqueId() {
            return value.getUuid();
        }

        @Override
        public Location getLocation() {
            return location((ServerWorld) value.getEntityWorld(), value.getEntityPos(), value.getYaw(), value.getPitch());
        }

        @Override
        public World getWorld() {
            return world((ServerWorld) value.getEntityWorld());
        }

        @Override
        public Vector getVelocity() {
            return vector(value.getVelocity());
        }

        @Override
        public void setVelocity(Vector velocity) {
            value.setVelocity(vector(velocity));
            value.velocityDirty = true;
        }

        @Override public boolean isOnGround() { return value.isOnGround(); }
        @Override public double getHeight() { return value.getHeight(); }
        @Override public BoundingBox getBoundingBox() {
            Box box = value.getBoundingBox();
            return new BoundingBox(
                    new Vector(box.minX, box.minY, box.minZ), new Vector(box.maxX, box.maxY, box.maxZ));
        }

        @Override
        public boolean isDead() {
            return !value.isAlive();
        }

        @Override
        public boolean isValid() {
            return !value.isRemoved();
        }

        @Override
        public void remove() {
            value.discard();
        }

        @Override
        public boolean teleport(Location target) {
            value.requestTeleport(target.getX(), target.getY(), target.getZ());
            value.setYaw(target.getYaw());
            value.setPitch(target.getPitch());
            return true;
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
        public int getEntityId() {
            return value.getId();
        }

        @Override
        public String getName() {
            return value.getName().getString();
        }

        @Override
        public float getFallDistance() {
            return (float) value.fallDistance;
        }

        @Override
        public void setFallDistance(float distance) {
            value.fallDistance = distance;
        }

        @Override public void setMetadata(String key, MetadataValue metadata) { FabricMC.setMetadata(value.getUuid(), key, metadata); }
        @Override public boolean hasMetadata(String key) { return FabricMC.hasMetadata(value.getUuid(), key); }
        @Override public List<MetadataValue> getMetadata(String key) { return FabricMC.getMetadata(value.getUuid(), key); }
        @Override public void removeMetadata(String key, Object owner) { FabricMC.removeMetadata(value.getUuid(), key); }

        @Override
        public Object handle() {
            return value;
        }

        @Override public boolean equals(Object other) { return other instanceof EntityView view && value.getUuid().equals(view.value.getUuid()); }
        @Override public int hashCode() { return value.getUuid().hashCode(); }
    }

    private static class LivingView extends LivingEntity {
        protected final net.minecraft.entity.LivingEntity value;

        private LivingView(net.minecraft.entity.LivingEntity value) {
            this.value = value;
        }

        @Override
        public UUID getUniqueId() {
            return value.getUuid();
        }

        @Override
        public Location getLocation() {
            return location((ServerWorld) value.getEntityWorld(), value.getEntityPos(), value.getYaw(), value.getPitch());
        }

        @Override
        public Location getEyeLocation() {
            return location((ServerWorld) value.getEntityWorld(), value.getEyePos(), value.getYaw(), value.getPitch());
        }

        @Override
        public World getWorld() {
            return world((ServerWorld) value.getEntityWorld());
        }

        @Override
        public Vector getVelocity() {
            return vector(value.getVelocity());
        }

        @Override
        public void setVelocity(Vector velocity) {
            VelocitySync.applyDirect(AbilityExecutionContext.current(), this, velocity, () -> {
                value.setVelocity(vector(velocity));
                value.velocityDirty = true;
            });
        }

        @Override public boolean isOnGround() { return value.isOnGround(); }
        @Override public float getFallDistance() { return (float) value.fallDistance; }
        @Override public void setFallDistance(float distance) { value.fallDistance = distance; }
        @Override public BoundingBox getBoundingBox() {
            Box box = value.getBoundingBox();
            return new BoundingBox(
                    new Vector(box.minX, box.minY, box.minZ), new Vector(box.maxX, box.maxY, box.maxZ));
        }

        @Override
        public boolean isDead() {
            return !value.isAlive();
        }

        @Override public boolean isValid() { return !value.isRemoved(); }
        @Override public int getEntityId() { return value.getId(); }
        @Override public int getFireTicks() { return value.getFireTicks(); }
        @Override public void setFireTicks(int ticks) { applyHitStatus(this, () -> value.setFireTicks(ticks)); }
        @Override public String getName() { return value.getName().getString(); }

        @Override
        public double getHealth() {
            return value.getHealth();
        }

        @Override
        public void setHealth(double health) {
            value.setHealth((float) health);
        }

        @Override
        public double getMaxHealth() {
            return value.getMaxHealth();
        }

        @Override public void damage(double amount) { value.damage((ServerWorld) value.getEntityWorld(), value.getDamageSources().generic(), (float) amount); }
        @Override public void damage(double amount, Entity source) {
            DamageSource damageSource = source != null && source.handle() instanceof ServerPlayerEntity player
                    ? value.getDamageSources().playerAttack(player) : value.getDamageSources().generic();
            value.damage((ServerWorld) value.getEntityWorld(), damageSource, (float) amount);
        }
        @Override public int getNoDamageTicks() { return value.timeUntilRegen; }
        @Override public void setNoDamageTicks(int ticks) { applyHitStatus(this, () -> value.timeUntilRegen = ticks); }

        @Override public boolean hasPotionEffect(PotionEffectType type) { return value.hasStatusEffect(status(type)); }
        @Override public PotionEffect getPotionEffect(PotionEffectType type) { return effect(type, value.getStatusEffect(status(type))); }
        @Override public void addPotionEffect(PotionEffect effect) { applyHitStatus(this, () -> value.addStatusEffect(new StatusEffectInstance(status(effect.getType()), effect.getDuration(), effect.getAmplifier(), true, false))); }
        @Override public void addPotionEffects(Collection<PotionEffect> effects) { if (effects != null) effects.forEach(this::addPotionEffect); }
        @Override public boolean addPotionEffect(PotionEffect effect, boolean force) { addPotionEffect(effect); return true; }
        @Override public void removePotionEffect(PotionEffectType type) { applyHitStatus(this, () -> value.removeStatusEffect(status(type))); }
        @Override public int getRemainingAir() { return value.getAir(); }
        @Override public void setRemainingAir(int ticks) { applyHitStatus(this, () -> value.setAir(ticks)); }
        @Override public EntityEquipment getEquipment() { return new EquipmentView(value); }
        @Override public void setAI(boolean enabled) {
            if (value instanceof MobEntity mob) {
                mob.setAiDisabled(!enabled);
            }
        }
        @Override public void setMetadata(String key, MetadataValue metadata) { FabricMC.setMetadata(value.getUuid(), key, metadata); }
        @Override public boolean hasMetadata(String key) { return FabricMC.hasMetadata(value.getUuid(), key); }
        @Override public List<MetadataValue> getMetadata(String key) { return FabricMC.getMetadata(value.getUuid(), key); }
        @Override public void removeMetadata(String key, Object owner) { FabricMC.removeMetadata(value.getUuid(), key); }

        @Override
        public Object handle() {
            return value;
        }

        @Override public boolean equals(Object other) { return other instanceof LivingView view && value.getUuid().equals(view.value.getUuid()); }
        @Override public int hashCode() { return value.getUuid().hashCode(); }
    }

    private static final class ArmorStandView extends ArmorStand {
        private final ArmorStandEntity value;

        private ArmorStandView(ArmorStandEntity value) {
            this.value = value;
        }

        @Override public UUID getUniqueId() { return value.getUuid(); }
        @Override public Location getLocation() { return location((ServerWorld) value.getEntityWorld(), value.getEntityPos(), value.getYaw(), value.getPitch()); }
        @Override public Location getEyeLocation() { return location((ServerWorld) value.getEntityWorld(), value.getEyePos(), value.getYaw(), value.getPitch()); }
        @Override public World getWorld() { return world((ServerWorld) value.getEntityWorld()); }
        @Override public Vector getVelocity() { return vector(value.getVelocity()); }
        @Override public void setVelocity(Vector velocity) { value.setVelocity(vector(velocity)); value.velocityDirty = true; }
        @Override public boolean isDead() { return !value.isAlive(); }
        @Override public boolean isValid() { return !value.isRemoved(); }
        @Override public void remove() { value.discard(); }
        @Override public boolean teleport(Location target) {
            value.requestTeleport(target.getX(), target.getY(), target.getZ());
            value.setYaw(target.getYaw());
            value.setPitch(target.getPitch());
            return true;
        }
        @Override public int getEntityId() { return value.getId(); }
        @Override public String getName() { return value.getName().getString(); }
        @Override public int getFireTicks() { return value.getFireTicks(); }
        @Override public void setFireTicks(int ticks) { value.setFireTicks(ticks); }
        @Override public float getFallDistance() { return (float) value.fallDistance; }
        @Override public void setFallDistance(float distance) { value.fallDistance = distance; }
        @Override public void setSilent(boolean silent) { value.setSilent(silent); }
        @Override public void setInvulnerable(boolean invulnerable) { value.setInvulnerable(invulnerable); }
        @Override public void setGravity(boolean gravity) { value.setNoGravity(!gravity); }
        @Override public boolean isOnGround() { return value.isOnGround(); }
        @Override public double getHeight() { return value.getHeight(); }
        @Override public boolean isMarker() { return value.isMarker(); }
        @Override public boolean isInvisible() { return value.isInvisible(); }
        @Override public void setVisible(boolean visible) { value.setInvisible(!visible); }
        @Override public void setSmall(boolean small) { }
        @Override public void setHelmet(ItemStack item) { value.equipStack(EquipmentSlot.HEAD, itemStack(item)); }
        @Override public EntityEquipment getEquipment() { return new EquipmentView(value); }
        @Override public BoundingBox getBoundingBox() {
            Box box = value.getBoundingBox();
            return new BoundingBox(
                    new Vector(box.minX, box.minY, box.minZ), new Vector(box.maxX, box.maxY, box.maxZ));
        }
        @Override public void setMetadata(String key, MetadataValue metadata) { FabricMC.setMetadata(value.getUuid(), key, metadata); }
        @Override public boolean hasMetadata(String key) { return FabricMC.hasMetadata(value.getUuid(), key); }
        @Override public List<MetadataValue> getMetadata(String key) { return FabricMC.getMetadata(value.getUuid(), key); }
        @Override public void removeMetadata(String key, Object owner) { FabricMC.removeMetadata(value.getUuid(), key); }
        @Override public Object handle() { return value; }
        @Override public boolean equals(Object other) {
            return other instanceof Entity entity && value.getUuid().equals(entity.getUniqueId());
        }
        @Override public int hashCode() { return value.getUuid().hashCode(); }
    }

    private static final class DroppedItemView extends Item {
        private final ItemEntity value;

        private DroppedItemView(ItemEntity value) {
            this.value = value;
        }

        @Override public UUID getUniqueId() { return value.getUuid(); }
        @Override public Location getLocation() { return location((ServerWorld) value.getEntityWorld(), value.getEntityPos(), value.getYaw(), value.getPitch()); }
        @Override public World getWorld() { return world((ServerWorld) value.getEntityWorld()); }
        @Override public Vector getVelocity() { return vector(value.getVelocity()); }
        @Override public void setVelocity(Vector velocity) { value.setVelocity(vector(velocity)); value.velocityDirty = true; }
        @Override public boolean isDead() { return !value.isAlive(); }
        @Override public boolean isValid() { return !value.isRemoved(); }
        @Override public void remove() { value.discard(); }
        @Override public int getFireTicks() { return value.getFireTicks(); }
        @Override public void setFireTicks(int ticks) { value.setFireTicks(ticks); }
        @Override public boolean teleport(Location target) {
            value.refreshPositionAndAngles(target.getX(), target.getY(), target.getZ(), target.getYaw(), target.getPitch());
            return true;
        }
        @Override public int getEntityId() { return value.getId(); }
        @Override public String getName() { return value.getName().getString(); }
        @Override public float getFallDistance() { return (float) value.fallDistance; }
        @Override public void setFallDistance(float distance) { value.fallDistance = distance; }
        @Override public void setSilent(boolean silent) { value.setSilent(silent); }
        @Override public void setInvulnerable(boolean invulnerable) { value.setInvulnerable(invulnerable); }
        @Override public boolean isOnGround() { return value.isOnGround(); }
        @Override public double getHeight() { return value.getHeight(); }
        @Override public void setGravity(boolean gravity) { value.setNoGravity(!gravity); }
        @Override public Object handle() { return value; }
        @Override public void setMetadata(String key, MetadataValue metadata) { FabricMC.setMetadata(value.getUuid(), key, metadata); }
        @Override public boolean hasMetadata(String key) { return FabricMC.hasMetadata(value.getUuid(), key); }
        @Override public List<MetadataValue> getMetadata(String key) { return FabricMC.getMetadata(value.getUuid(), key); }
        @Override public void removeMetadata(String key, Object owner) { FabricMC.removeMetadata(value.getUuid(), key); }
        @Override public ItemStack getItemStack() { return itemStack(value.getStack()); }
        @Override public void setItemStack(ItemStack item) { value.setStack(itemStack(item)); }
        @Override public void setPickupDelay(int ticks) { value.setPickupDelay(ticks); }
        @Override public boolean equals(Object other) { return other instanceof DroppedItemView view && value.getUuid().equals(view.value.getUuid()); }
        @Override public int hashCode() { return value.getUuid().hashCode(); }
    }

    private static final class FallingBlockView extends FallingBlock {
        private final FallingBlockEntity value;

        private FallingBlockView(FallingBlockEntity value) {
            this.value = value;
        }

        @Override public UUID getUniqueId() { return value.getUuid(); }
        @Override public Location getLocation() { return location((ServerWorld) value.getEntityWorld(), value.getEntityPos(), value.getYaw(), value.getPitch()); }
        @Override public World getWorld() { return world((ServerWorld) value.getEntityWorld()); }
        @Override public Vector getVelocity() { return vector(value.getVelocity()); }
        @Override public void setVelocity(Vector velocity) { value.setVelocity(vector(velocity)); value.velocityDirty = true; }
        @Override public boolean isOnGround() { return value.isOnGround(); }
        @Override public BoundingBox getBoundingBox() {
            Box box = value.getBoundingBox();
            return new BoundingBox(
                    new Vector(box.minX, box.minY, box.minZ), new Vector(box.maxX, box.maxY, box.maxZ));
        }
        @Override public boolean isDead() { return !value.isAlive(); }
        @Override public boolean isValid() { return !value.isRemoved(); }
        @Override public void remove() { value.discard(); }
        @Override public int getEntityId() { return value.getId(); }
        @Override public String getName() { return value.getName().getString(); }
        @Override public void setDropItem(boolean drop) {
            value.dropItem = drop;
            if (!drop) value.setDestroyedOnLanding();
        }
        @Override public void setHurtEntities(boolean hurt) {
            try {
                value.getClass().getMethod("setHurtEntities", boolean.class).invoke(value, hurt);
            } catch (Throwable ignored) {
                try { value.getClass().getMethod("setHurtEntities", float.class, int.class).invoke(value, hurt ? 2.0F : 0.0F, hurt ? 40 : 0); }
                catch (Throwable ignoredAgain) { }
            }
        }
        @Override public BlockData getBlockData() { return FabricMC.blockData(value.getBlockState()); }
        @Override public void setMetadata(String key, MetadataValue metadata) { FabricMC.setMetadata(value.getUuid(), key, metadata); }
        @Override public boolean hasMetadata(String key) { return FabricMC.hasMetadata(value.getUuid(), key); }
        @Override public List<MetadataValue> getMetadata(String key) { return FabricMC.getMetadata(value.getUuid(), key); }
        @Override public void removeMetadata(String key, Object owner) { FabricMC.removeMetadata(value.getUuid(), key); }
        @Override public Object handle() { return value; }
        @Override public boolean equals(Object other) { return other instanceof FallingBlockView view && value.getUuid().equals(view.value.getUuid()); }
        @Override public int hashCode() { return value.getUuid().hashCode(); }
    }

    private static class DisplayView extends Display {
        protected final DisplayEntity value;

        private DisplayView(DisplayEntity value) {
            this.value = value;
        }

        @Override public UUID getUniqueId() { return value.getUuid(); }
        @Override public Location getLocation() { return location((ServerWorld) value.getEntityWorld(), value.getEntityPos(), value.getYaw(), value.getPitch()); }
        @Override public World getWorld() { return world((ServerWorld) value.getEntityWorld()); }
        @Override public Vector getVelocity() { return vector(value.getVelocity()); }
        @Override public void setVelocity(Vector velocity) { value.setVelocity(vector(velocity)); value.velocityDirty = true; }
        @Override public boolean isDead() { return !value.isAlive(); }
        @Override public boolean isValid() { return !value.isRemoved(); }
        @Override public void remove() { value.discard(); }
        @Override public boolean teleport(Location target) {
            if (value.getEntityWorld().isClient() && value.getTeleportDuration() > 0) {
                value.getInterpolator().setLerpDuration(value.getTeleportDuration());
                value.getInterpolator().refreshPositionAndAngles(
                        new Vec3d(target.getX(), target.getY(), target.getZ()),
                        target.getYaw(), target.getPitch());
            } else {
                value.refreshPositionAndAngles(target.getX(), target.getY(), target.getZ(),
                        target.getYaw(), target.getPitch());
            }
            return true;
        }
        @Override public int getEntityId() { return value.getId(); }
        @Override public String getName() { return value.getName().getString(); }
        @Override public boolean isOnGround() { return value.isOnGround(); }
        @Override public void setMetadata(String key, MetadataValue metadata) { FabricMC.setMetadata(value.getUuid(), key, metadata); }
        @Override public boolean hasMetadata(String key) { return FabricMC.hasMetadata(value.getUuid(), key); }
        @Override public List<MetadataValue> getMetadata(String key) { return FabricMC.getMetadata(value.getUuid(), key); }
        @Override public void removeMetadata(String key, Object owner) { FabricMC.removeMetadata(value.getUuid(), key); }
        @Override public void setSilent(boolean silent) { value.setSilent(silent); }
        @Override public void setGravity(boolean gravity) { value.setNoGravity(!gravity); }
        @Override public void setBrightness(Display.Brightness brightness) {
            value.setBrightness(brightness == null ? null : new net.minecraft.entity.decoration.Brightness(brightness.blockLight(), brightness.skyLight()));
        }
        @Override public void setTransformation(Transformation transformation) {
            value.setTransformation(new AffineTransformation(
                    transformation.translation(),
                    transformation.leftRotation(),
                    transformation.scale(),
                    transformation.rightRotation()));
        }
        @Override public void setBillboard(Display.Billboard billboard) {
            value.setBillboardMode(DisplayEntity.BillboardMode.valueOf(billboard.name()));
        }
        @Override public Object handle() { return value; }
        @Override public boolean equals(Object other) { return other instanceof DisplayView view && value.getUuid().equals(view.value.getUuid()); }
        @Override public int hashCode() { return value.getUuid().hashCode(); }
    }

    private static final class BlockDisplayView extends BlockDisplay {
        private final DisplayEntity.BlockDisplayEntity value;
        private final DisplayView display;

        private BlockDisplayView(DisplayEntity.BlockDisplayEntity value) {
            this.value = value;
            this.display = new DisplayView(value);
        }

        @Override public UUID getUniqueId() { return display.getUniqueId(); }
        @Override public Location getLocation() { return display.getLocation(); }
        @Override public World getWorld() { return display.getWorld(); }
        @Override public Vector getVelocity() { return display.getVelocity(); }
        @Override public void setVelocity(Vector velocity) { display.setVelocity(velocity); }
        @Override public boolean isDead() { return display.isDead(); }
        @Override public boolean isValid() { return display.isValid(); }
        @Override public void remove() { display.remove(); }
        @Override public boolean teleport(Location target) { return display.teleport(target); }
        @Override public int getEntityId() { return display.getEntityId(); }
        @Override public String getName() { return display.getName(); }
        @Override public boolean isOnGround() { return display.isOnGround(); }
        @Override public void setSilent(boolean silent) { display.setSilent(silent); }
        @Override public void setGravity(boolean gravity) { display.setGravity(gravity); }
        @Override public void setBrightness(Display.Brightness brightness) { display.setBrightness(brightness); }
        @Override public void setTransformation(Transformation transformation) { display.setTransformation(transformation); }
        @Override public void setBillboard(Display.Billboard billboard) { display.setBillboard(billboard); }
        @Override public void setBlock(BlockData data) { value.setBlockState(FabricMC.blockState(data)); }
        @Override public void setShadowRadius(float shadowRadius) { value.setShadowRadius(shadowRadius); }
        @Override public void setShadowStrength(float shadowStrength) { value.setShadowStrength(shadowStrength); }
        @Override public void setInterpolationDelay(int delay) { value.setStartInterpolation(delay); }
        @Override public void setInterpolationDuration(int duration) { value.setInterpolationDuration(duration); }
        @Override public void setTeleportDuration(int duration) { value.setTeleportDuration(duration); }
        @Override public void setViewRange(float range) { value.setViewRange(range); }
        @Override public Object handle() { return value; }
        @Override public boolean equals(Object other) { return other instanceof BlockDisplayView view && value.getUuid().equals(view.value.getUuid()); }
        @Override public int hashCode() { return value.getUuid().hashCode(); }
    }

    private static final class PlayerView extends Player {
        private ServerPlayerEntity value;

        private PlayerView(ServerPlayerEntity value) {
            this.value = value;
        }

        private void sendAbilitiesUpdate() {
            value.sendAbilitiesUpdate();
        }

        private void applyAbilityState(final AbilityStateSync.FlightState resultingState,
                                       final Runnable write) {
            AbilityStateSync.apply(AbilityExecutionContext.current(), this, resultingState, write);
        }

        @Override
        public UUID getUniqueId() {
            return value.getUuid();
        }

        @Override
        public String getName() {
            return value.getName().getString();
        }

        @Override
        public boolean isOnline() {
            return !value.isDisconnected();
        }

        @Override
        public void sendMessage(String message) {
            value.sendMessage(legacyText(message));
        }

        @Override
        public CommandSender.Spigot spigot() {
            return new CommandSender.Spigot((type, message) -> {
                Text text = legacyText(message);
                if (type == ChatMessageType.ACTION_BAR) {
                    value.networkHandler.sendPacket(new OverlayMessageS2CPacket(text));
                } else {
                    value.sendMessage(text);
                }
            });
        }

        @Override
        public boolean hasPermission(String permission) {
            if (value.getCommandSource().getPermissions().hasPermission(new Permission.Level(PermissionLevel.ADMINS))) return true;
            if (permission == null) return false;
            String normalized = permission.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("bending.ability.") || normalized.startsWith("bending.element.") || normalized.startsWith("bending.message.")) return true;
            if (Set.of("bending.air.passive", "bending.water.passive", "bending.earth.passive", "bending.fire.passive", "bending.chi.passive",
                    "bending.air.flightbending", "bending.air.spiritualprojection", "bending.water.bloodbending", "bending.water.healing",
                    "bending.water.icebending", "bending.water.plantbending", "bending.earth.metalbending", "bending.earth.lavabending",
                    "bending.earth.sandbending", "bending.fire.combustionbending", "bending.fire.lightningbending").contains(normalized)) return true;
            if (permission.startsWith("bending.command.preset.")) return true;
            if (Set.of("air", "earth", "fire", "water", "chi").stream()
                    .anyMatch(element -> permission.equalsIgnoreCase("bending.command.choose." + element))) return true;
            return Set.of("board", "bind", "display", "toggle", "copy", "choose", "version", "help", "clear", "who", "style",
                    "firecolor", "aircolor", "watercosmetic", "earthcosmetic", "viewdistance", "detailedactionbar", "oldscooter", "combohelp")
                    .stream().anyMatch(command -> permission.equalsIgnoreCase("bending.command." + command));
        }

        @Override
        public Location getLocation() {
            return location((ServerWorld) value.getEntityWorld(), value.getEntityPos(), value.getYaw(), value.getPitch());
        }

        @Override
        public Location getEyeLocation() {
            return location((ServerWorld) value.getEntityWorld(), value.getEyePos(), value.getYaw(), value.getPitch());
        }

        @Override
        public World getWorld() {
            return world((ServerWorld) value.getEntityWorld());
        }

        @Override
        public Vector getVelocity() {
            return vector(value.getVelocity());
        }

        @Override
        public void setVelocity(Vector velocity) {
            VelocitySync.applyDirect(AbilityExecutionContext.current(), this, velocity, () -> {
                value.setVelocity(vector(velocity));
                value.velocityDirty = true;
                value.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(value));
            });
        }

        @Override public boolean isOnGround() { return value.isOnGround(); }
        @Override public float getFallDistance() { return (float) value.fallDistance; }
        @Override public void setFallDistance(float distance) { value.fallDistance = distance; }
        @Override public BoundingBox getBoundingBox() {
            Box box = value.getBoundingBox();
            return new BoundingBox(
                    new Vector(box.minX, box.minY, box.minZ), new Vector(box.maxX, box.maxY, box.maxZ));
        }

        @Override
        public boolean isSneaking() {
            Boolean override = SNEAK_OVERRIDES.get(value.getUuid());
            return override != null ? override : value.isSneaking();
        }

        @Override
        public boolean isSprinting() {
            return value.isSprinting();
        }

        @Override public PlayerInventory getInventory() { return new InventoryView(value); }
        @Override public EntityEquipment getEquipment() { return new EquipmentView(value); }

        @Override public GameMode getGameMode() {
            return switch (value.getGameMode()) {
                case SURVIVAL -> GameMode.valueOf("SURVIVAL");
                case CREATIVE -> GameMode.CREATIVE;
                case ADVENTURE -> GameMode.valueOf("ADVENTURE");
                case SPECTATOR -> GameMode.SPECTATOR;
            };
        }

        @Override public void setFlying(boolean flying) {
            if (value.getAbilities().flying == flying) return;
            applyAbilityState(new AbilityStateSync.FlightState(flying, getAllowFlight(), getFlySpeed()),
                    () -> { value.getAbilities().flying = flying; sendAbilitiesUpdate(); });
        }
        @Override public boolean isFlying() { return value.getAbilities().flying; }
        @Override public void setAllowFlight(boolean allow) {
            if (value.getAbilities().allowFlying == allow) return;
            applyAbilityState(new AbilityStateSync.FlightState(isFlying(), allow, getFlySpeed()),
                    () -> { value.getAbilities().allowFlying = allow; sendAbilitiesUpdate(); });
        }
        @Override public boolean getAllowFlight() { return value.getAbilities().allowFlying; }
        @Override public float getFlySpeed() { return value.getAbilities().getFlySpeed() * 2.0F; }
        @Override public void setFlySpeed(float speed) {
            if (Float.compare(getFlySpeed(), speed) == 0) return;
            applyAbilityState(new AbilityStateSync.FlightState(isFlying(), getAllowFlight(), speed),
                    () -> { value.getAbilities().setFlySpeed(speed / 2.0F); sendAbilitiesUpdate(); });
        }
        @Override public boolean getCanPickupItems() { return PICKUP_OVERRIDES.getOrDefault(value.getUuid(), true); }
        @Override public void setCanPickupItems(boolean pickup) { PICKUP_OVERRIDES.put(value.getUuid(), pickup); }
        @Override public boolean isSwimming() { return value.isSwimming(); }
        @Override public boolean isOp() { return hasPermission("minecraft.command.op"); }
        @Override public int getPing() { return value.networkHandler.getLatency(); }
        @Override public int getEntityId() { return value.getId(); }
        @Override public boolean isValid() { return !value.isRemoved(); }
        @Override public int getFireTicks() { return value.getFireTicks(); }
        @Override public void setFireTicks(int ticks) { applyHitStatus(this, () -> value.setFireTicks(ticks)); }
        @Override public boolean teleport(Location target) {
            value.networkHandler.requestTeleport(target.getX(), target.getY(), target.getZ(), target.getYaw(), target.getPitch());
            return true;
        }
        @Override public void setSprinting(boolean sprinting) { value.setSprinting(sprinting); }
        @Override public void setSneaking(boolean sneaking) { value.setSneaking(sneaking); }
        @Override public void setRotation(float yaw, float pitch) { value.setYaw(yaw); value.setPitch(pitch); }
        @Override public boolean isGliding() { return value.isGliding(); }
        @Override public void setGliding(boolean gliding) {
            if (gliding) {
                value.startGliding();
            } else {
                value.stopGliding();
            }
        }
        @Override public float getExp() { return value.experienceProgress; }
        @Override public void setExp(float experience) {
            float clamped = Math.max(0.0F, Math.min(1.0F, experience));
            value.experienceProgress = clamped;
            value.networkHandler.sendPacket(new ExperienceBarUpdateS2CPacket(
                    clamped, value.totalExperience, value.experienceLevel));
        }
        @Override public boolean isGlowing() { return value.isGlowing(); }
        @Override public void setGlowing(boolean glowing) { value.setGlowing(glowing); }
        @Override public double getMaxHealth() { return value.getMaxHealth(); }
        @Override public double getHeight() { return value.getHeight(); }
        @Override public boolean hasLineOfSight(Entity entity) {
            if (entity == null || !(entity.handle() instanceof net.minecraft.entity.Entity nativeEntity)) return false;
            HitResult hit = value.raycast(value.distanceTo(nativeEntity) + 0.5, 0, false);
            return hit == null || hit.getType() == HitResult.Type.MISS
                    || hit.getPos().squaredDistanceTo(nativeEntity.getEntityPos()) < 2.25;
        }
        @Override public void damage(double amount) { value.damage((ServerWorld) value.getEntityWorld(), value.getDamageSources().generic(), (float) amount); }
        @Override public void damage(double amount, Entity source) {
            DamageSource damageSource = source != null && source.handle() instanceof ServerPlayerEntity player
                    ? value.getDamageSources().playerAttack(player) : value.getDamageSources().generic();
            value.damage((ServerWorld) value.getEntityWorld(), damageSource, (float) amount);
        }
        @Override public int getNoDamageTicks() { return value.timeUntilRegen; }
        @Override public void setNoDamageTicks(int ticks) { applyHitStatus(this, () -> value.timeUntilRegen = ticks); }
        @Override public boolean hasPotionEffect(PotionEffectType type) { return value.hasStatusEffect(status(type)); }
        @Override public PotionEffect getPotionEffect(PotionEffectType type) { return effect(type, value.getStatusEffect(status(type))); }
        @Override public void addPotionEffect(PotionEffect effect) { applyHitStatus(this, () -> value.addStatusEffect(new StatusEffectInstance(status(effect.getType()), effect.getDuration(), effect.getAmplifier(), true, false))); }
        @Override public void addPotionEffects(Collection<PotionEffect> effects) { if (effects != null) effects.forEach(this::addPotionEffect); }
        @Override public boolean addPotionEffect(PotionEffect effect, boolean force) { addPotionEffect(effect); return true; }
        @Override public void removePotionEffect(PotionEffectType type) { applyHitStatus(this, () -> value.removeStatusEffect(status(type))); }
        @Override public int getRemainingAir() { return value.getAir(); }
        @Override public void setRemainingAir(int ticks) { applyHitStatus(this, () -> value.setAir(ticks)); }
        @Override public Block getTargetBlockExact(int range) {
            Location eye = getEyeLocation();
            Vec3d origin = vector(eye.toVector());
            Vec3d end = origin.add(vector(eye.getDirection()).normalize().multiply(Math.max(0, range)));
            HitResult hit = value.getEntityWorld().raycast(new RaycastContext(origin, end,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, value));
            return hit instanceof BlockHitResult blockHit ? block((ServerWorld) value.getEntityWorld(), blockHit.getBlockPos()) : null;
        }
        @Override
        public Block getTargetBlock(Set<Material> transparent, int range) {
            Set<Material> passThrough = transparent == null
                    ? new HashSet<>() : new HashSet<>(transparent);
            passThrough.add(Material.AIR);
            passThrough.add(Material.CAVE_AIR);
            passThrough.add(Material.VOID_AIR);
            Location eye = getEyeLocation();
            Vec3d origin = vector(eye.toVector());
            Vec3d direction = vector(eye.getDirection()).normalize();
            Block last = block((ServerWorld) value.getEntityWorld(), BlockPos.ofFloored(origin));
            for (double distance = 0.0; distance <= Math.max(0, range); distance += 0.2) {
                Block current = block((ServerWorld) value.getEntityWorld(), BlockPos.ofFloored(origin.add(direction.multiply(distance))));
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
            Location eye = getEyeLocation();
            Vec3d origin = vector(eye.toVector());
            Vec3d direction = vector(eye.getDirection()).normalize();
            Block previous = block((ServerWorld) value.getEntityWorld(), BlockPos.ofFloored(origin));
            Block current = previous;
            BlockPos lastPos = BlockPos.ofFloored(origin);
            for (double distance = 0.0; distance <= Math.max(0, range); distance += 0.2) {
                BlockPos pos = BlockPos.ofFloored(origin.add(direction.multiply(distance)));
                if (pos.equals(lastPos)) continue;
                lastPos = pos;
                previous = current;
                current = block((ServerWorld) value.getEntityWorld(), pos);
                if (!passThrough.contains(current.getType())) break;
            }
            return List.of(previous, current);
        }

        @Override
        public List<Entity> getNearbyEntities(double x, double y, double z) {
            Box box = value.getBoundingBox().expand(x, y, z);
            Map<Integer, Entity> result = new LinkedHashMap<>();
            for (net.minecraft.entity.Entity nativeEntity : value.getEntityWorld().getOtherEntities(value, box, nativeEntity -> true)) {
                result.put(nativeEntity.getId(), FabricMC.entity(nativeEntity));
            }
            return result.values().stream().filter(Objects::nonNull).toList();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Projectile> T launchProjectile(Class<T> type) {
            if (type == Arrow.class) {
                ArrowEntity arrow = new ArrowEntity(
                        value.getEntityWorld(), value, new net.minecraft.item.ItemStack(Items.ARROW), null);
                arrow.setVelocity(value, value.getPitch(), value.getYaw(), 0.0F, 3.0F, 1.0F);
                value.getEntityWorld().spawnEntity(arrow);
                return (T) new ArrowView(arrow);
            }
            throw new IllegalArgumentException("Unsupported projectile type " + type.getName());
        }

        @Override
        public void playNote(Location location, Instrument instrument, Note note) {
            String sound = switch (instrument) {
                case BASS_DRUM -> "block.note_block.basedrum";
                case SNARE_DRUM -> "block.note_block.snare";
                case STICKS -> "block.note_block.hat";
                case BASS_GUITAR -> "block.note_block.bass";
                case FLUTE -> "block.note_block.flute";
                case BELL -> "block.note_block.bell";
                case GUITAR -> "block.note_block.guitar";
                case CHIME -> "block.note_block.chime";
                case XYLOPHONE -> "block.note_block.xylophone";
                default -> "block.note_block.harp";
            };
            int noteNumber = Math.max(0, Math.min(24, note.getOctave() * 12 + note.getTone().ordinal() + (note.isSharped() ? 1 : 0)));
            float pitch = (float) Math.pow(2.0D, (noteNumber - 12) / 12.0D);
            FabricMC.playConfigSound((ServerWorld) value.getEntityWorld(), location.getX(), location.getY(), location.getZ(), sound, net.minecraft.sound.SoundCategory.RECORDS, 3.0F, pitch);
        }

        @Override
        public void sendBlockChange(Location location, BlockData data) {
            value.networkHandler.sendPacket(new BlockUpdateS2CPacket(BlockPos.ofFloored(location.getX(), location.getY(), location.getZ()), FabricMC.blockState(data)));
        }

        @Override
        public <T> void spawnParticle(Particle particle, Location location, int count,
                                      double ox, double oy, double oz, double extra, T data, boolean force) {
            try {
                ParticleEffect effect = FabricMC.particle(particle, data);
                if (effect == null) return;
                value.getEntityWorld().spawnParticles(value, effect, force, false,
                        location.getX(), location.getY(), location.getZ(), count, ox, oy, oz, extra);
            } catch (Throwable ignored) { }
        }

        @Override
        public double getHealth() {
            return value.getHealth();
        }

        @Override
        public void setHealth(double health) {
            value.setHealth((float) health);
        }

        @Override
        public void setScoreboard(Scoreboard board) {
            FabricMC.setScoreboard(value, board == null ? EMPTY_SCOREBOARD : board);
        }

        @Override
        public Scoreboard getScoreboard() {
            return PLAYER_SCOREBOARDS.getOrDefault(value.getUuid(), EMPTY_SCOREBOARD);
        }

        @Override
        public boolean isDead() {
            return !value.isAlive();
        }

        @Override public void setMetadata(String key, MetadataValue metadata) { FabricMC.setMetadata(value.getUuid(), key, metadata); }
        @Override public boolean hasMetadata(String key) { return FabricMC.hasMetadata(value.getUuid(), key); }
        @Override public List<MetadataValue> getMetadata(String key) { return FabricMC.getMetadata(value.getUuid(), key); }
        @Override public void removeMetadata(String key, Object owner) { FabricMC.removeMetadata(value.getUuid(), key); }

        @Override
        public Object handle() {
            return value;
        }

        @Override public boolean equals(Object other) { return other instanceof PlayerView view && value.getUuid().equals(view.value.getUuid()); }
        @Override public int hashCode() { return value.getUuid().hashCode(); }
    }

    private static final class InventoryView extends PlayerInventory {
        private final ServerPlayerEntity player;
        private InventoryView(ServerPlayerEntity player) { this.player = player; }
        private net.minecraft.entity.player.PlayerInventory nativeInventory() { return player.getInventory(); }
        @Override public ItemStack getItemInMainHand() { return itemStack(player.getMainHandStack()); }
        @Override public ItemStack getItemInOffHand() { return itemStack(player.getOffHandStack()); }
        @Override public void setItemInMainHand(ItemStack item) { nativeInventory().setSelectedStack(itemStack(item)); }
        @Override public void setItemInOffHand(ItemStack item) { player.equipStack(EquipmentSlot.OFFHAND, itemStack(item)); }
        @Override public int getHeldItemSlot() { return nativeInventory().getSelectedSlot(); }
        @Override public void setHeldItemSlot(int slot) {
            if (slot < 0 || slot >= 9) return;
            nativeInventory().setSelectedSlot(slot);
            if (!SUPPRESSED_SELECTED_SLOT_PACKETS.contains(player.getUuid())) {
                player.networkHandler.sendPacket(new UpdateSelectedSlotS2CPacket(slot));
            }
        }
        @Override public int getSize() { return nativeInventory().size(); }
        @Override public ItemStack getItem(int slot) { return slot >= 0 && slot < getSize() ? itemStack(nativeInventory().getStack(slot)) : null; }
        @Override public void setItem(int slot, ItemStack item) { if (slot >= 0 && slot < getSize()) nativeInventory().setStack(slot, itemStack(item)); }
        @Override public void clear(int slot) { if (slot >= 0 && slot < getSize()) nativeInventory().setStack(slot, net.minecraft.item.ItemStack.EMPTY); }
        @Override public ItemStack[] getContents() {
            ItemStack[] result = new ItemStack[getSize()];
            for (int i = 0; i < result.length; i++) result[i] = getItem(i);
            return result;
        }
        @Override public void setContents(ItemStack[] contents) {
            for (int i = 0; i < getSize(); i++) setItem(i, i < contents.length ? contents[i] : null);
        }
        @Override public ItemStack[] getArmorContents() {
            return new ItemStack[] {
                    itemStack(player.getEquippedStack(EquipmentSlot.FEET)),
                    itemStack(player.getEquippedStack(EquipmentSlot.LEGS)),
                    itemStack(player.getEquippedStack(EquipmentSlot.CHEST)),
                    itemStack(player.getEquippedStack(EquipmentSlot.HEAD))
            };
        }
        @Override public void setArmorContents(ItemStack[] contents) {
            if (contents == null) return;
            if (contents.length > 0) player.equipStack(EquipmentSlot.FEET, itemStack(contents[0]));
            if (contents.length > 1) player.equipStack(EquipmentSlot.LEGS, itemStack(contents[1]));
            if (contents.length > 2) player.equipStack(EquipmentSlot.CHEST, itemStack(contents[2]));
            if (contents.length > 3) player.equipStack(EquipmentSlot.HEAD, itemStack(contents[3]));
        }
        @Override public ItemStack getHelmet() { return itemStack(player.getEquippedStack(EquipmentSlot.HEAD)); }
        @Override public ItemStack getChestplate() { return itemStack(player.getEquippedStack(EquipmentSlot.CHEST)); }
        @Override public ItemStack getLeggings() { return itemStack(player.getEquippedStack(EquipmentSlot.LEGS)); }
        @Override public ItemStack getBoots() { return itemStack(player.getEquippedStack(EquipmentSlot.FEET)); }
        @Override public int first(Material material) {
            for (int i = 0; i < getSize(); i++) if (getItem(i).getType() == material) return i;
            return -1;
        }
        @Override public boolean contains(Material material) { return first(material) >= 0; }
        @Override public boolean containsAtLeast(ItemStack item, int amount) {
            int found = 0;
            for (ItemStack stack : this) {
                if (stack != null && stack.isSimilar(item)) found += stack.getAmount();
                if (found >= amount) return true;
            }
            return false;
        }
        @Override public HashMap<Integer, ItemStack> addItem(ItemStack... items) {
            HashMap<Integer, ItemStack> leftover = new HashMap<>();
            for (int i = 0; i < items.length; i++) {
                net.minecraft.item.ItemStack nativeStack = itemStack(items[i]);
                if (!nativeInventory().insertStack(nativeStack) && !nativeStack.isEmpty()) leftover.put(i, itemStack(nativeStack));
            }
            return leftover;
        }
        @Override public Map<Integer, ItemStack> removeItem(ItemStack... items) {
            Map<Integer, ItemStack> leftover = new HashMap<>();
            for (int requestIndex = 0; requestIndex < items.length; requestIndex++) {
                ItemStack requested = items[requestIndex];
                int remaining = requested == null ? 0 : requested.getAmount();
                for (int slot = 0; slot < getSize() && remaining > 0; slot++) {
                    net.minecraft.item.ItemStack nativeStack = nativeInventory().getStack(slot);
                    ItemStack wrapped = itemStack(nativeStack);
                    if (wrapped == null || !wrapped.isSimilar(requested)) continue;
                    int removed = Math.min(remaining, nativeStack.getCount());
                    nativeStack.decrement(removed);
                    remaining -= removed;
                }
                if (remaining > 0) {
                    ItemStack copy = requested.clone();
                    copy.setAmount(remaining);
                    leftover.put(requestIndex, copy);
                }
            }
            return leftover;
        }
        @Override public Iterator<ItemStack> iterator() {
            return Arrays.asList(getContents()).iterator();
        }
    }

    private static final class EquipmentView extends EntityEquipment {
        private final net.minecraft.entity.LivingEntity value;

        private EquipmentView(net.minecraft.entity.LivingEntity value) {
            this.value = value;
        }

        @Override public ItemStack[] getArmorContents() {
            return new ItemStack[] {
                    itemStack(value.getEquippedStack(EquipmentSlot.FEET)),
                    itemStack(value.getEquippedStack(EquipmentSlot.LEGS)),
                    itemStack(value.getEquippedStack(EquipmentSlot.CHEST)),
                    itemStack(value.getEquippedStack(EquipmentSlot.HEAD))
            };
        }

        @Override public void setArmorContents(ItemStack[] contents) {
            if (contents == null) return;
            if (contents.length > 0) value.equipStack(EquipmentSlot.FEET, itemStack(contents[0]));
            if (contents.length > 1) value.equipStack(EquipmentSlot.LEGS, itemStack(contents[1]));
            if (contents.length > 2) value.equipStack(EquipmentSlot.CHEST, itemStack(contents[2]));
            if (contents.length > 3) value.equipStack(EquipmentSlot.HEAD, itemStack(contents[3]));
        }

        @Override public ItemStack getItemInMainHand() {
            return itemStack(value.getMainHandStack());
        }

        @Override public void setItemInMainHand(ItemStack item) {
            value.equipStack(EquipmentSlot.MAINHAND, itemStack(item));
        }

        @Override public ItemStack getHelmet() {
            return itemStack(value.getEquippedStack(EquipmentSlot.HEAD));
        }

        @Override public void setHelmet(ItemStack item) {
            value.equipStack(EquipmentSlot.HEAD, itemStack(item));
        }
    }

    private static final class ArrowView extends Arrow {
        private final ArrowEntity value;

        private ArrowView(ArrowEntity value) {
            this.value = value;
        }

        @Override public UUID getUniqueId() { return value.getUuid(); }
        @Override public Location getLocation() { return location((ServerWorld) value.getEntityWorld(), value.getEntityPos(), value.getYaw(), value.getPitch()); }
        @Override public World getWorld() { return world((ServerWorld) value.getEntityWorld()); }
        @Override public Vector getVelocity() { return vector(value.getVelocity()); }
        @Override public void setVelocity(Vector velocity) { value.setVelocity(FabricMC.vector(velocity)); value.velocityDirty = true; }
        @Override public boolean isDead() { return !value.isAlive(); }
        @Override public boolean isValid() { return !value.isRemoved(); }
        @Override public void remove() { value.discard(); }
        @Override public int getEntityId() { return value.getId(); }
        @Override public String getName() { return value.getName().getString(); }
        @Override public int getFireTicks() { return value.getFireTicks(); }
        @Override public void setFireTicks(int ticks) { value.setFireTicks(ticks); }
        @Override public float getFallDistance() { return (float) value.fallDistance; }
        @Override public void setFallDistance(float distance) { value.fallDistance = distance; }
        @Override public void setMetadata(String key, MetadataValue metadata) { FabricMC.setMetadata(value.getUuid(), key, metadata); }
        @Override public boolean hasMetadata(String key) { return FabricMC.hasMetadata(value.getUuid(), key); }
        @Override public List<MetadataValue> getMetadata(String key) { return FabricMC.getMetadata(value.getUuid(), key); }
        @Override public void removeMetadata(String key, Object owner) { FabricMC.removeMetadata(value.getUuid(), key); }
        @Override public boolean isOnGround() { return value.isOnGround(); }
        @Override public Object handle() { return value; }
        @Override public PickupStatus getPickupStatus() { return PickupStatus.valueOf(value.pickupType.name()); }
        @Override public void setPickupStatus(PickupStatus status) { value.pickupType = PersistentProjectileEntity.PickupPermission.valueOf(status.name()); }
        @Override public void setKnockbackStrength(int strength) { }
        @Override public void setDamage(int damage) { value.setDamage(damage); }
        @Override public void setBounce(boolean bounce) { }
        @Override public void setCritical(boolean critical) { value.setCritical(critical); }
        public Object getShooter() { return FabricMC.entity(value.getOwner()); }
        @Override public void setShooter(Object shooter) {
            if (shooter instanceof Entity entity && entity.handle() instanceof net.minecraft.entity.Entity nativeEntity) {
                value.setOwner(nativeEntity);
            } else if (shooter instanceof net.minecraft.entity.Entity nativeEntity) {
                value.setOwner(nativeEntity);
            }
        }
        @Override public Block getAttachedBlock() {
            return isInGround() ? block((ServerWorld) value.getEntityWorld(), value.getBlockPos()) : null;
        }
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

    private static final class ShulkerBulletView extends ShulkerBullet {
        private final ShulkerBulletEntity value;

        private ShulkerBulletView(ShulkerBulletEntity value) {
            this.value = value;
        }

        @Override public UUID getUniqueId() { return value.getUuid(); }
        @Override public Location getLocation() { return location((ServerWorld) value.getEntityWorld(), value.getEntityPos(), value.getYaw(), value.getPitch()); }
        @Override public World getWorld() { return world((ServerWorld) value.getEntityWorld()); }
        @Override public Vector getVelocity() { return vector(value.getVelocity()); }
        @Override public void setVelocity(Vector velocity) { value.setVelocity(FabricMC.vector(velocity)); value.velocityDirty = true; }
        @Override public boolean isDead() { return !value.isAlive(); }
        @Override public boolean isValid() { return !value.isRemoved(); }
        @Override public void remove() { value.discard(); }
        @Override public boolean teleport(Location target) {
            value.requestTeleport(target.getX(), target.getY(), target.getZ());
            value.setYaw(target.getYaw());
            value.setPitch(target.getPitch());
            return true;
        }
        @Override public int getEntityId() { return value.getId(); }
        @Override public String getName() { return value.getName().getString(); }
        @Override public int getFireTicks() { return value.getFireTicks(); }
        @Override public void setFireTicks(int ticks) { value.setFireTicks(ticks); }
        @Override public float getFallDistance() { return (float) value.fallDistance; }
        @Override public void setFallDistance(float distance) { value.fallDistance = distance; }
        @Override public void setSilent(boolean silent) { value.setSilent(silent); }
        @Override public void setInvulnerable(boolean invulnerable) { value.setInvulnerable(invulnerable); }
        @Override public void setGravity(boolean gravity) { value.setNoGravity(!gravity); }
        @Override public void setPersistent(boolean persistent) { }
        @Override public void setMetadata(String key, MetadataValue metadata) { FabricMC.setMetadata(value.getUuid(), key, metadata); }
        @Override public boolean hasMetadata(String key) { return FabricMC.hasMetadata(value.getUuid(), key); }
        @Override public List<MetadataValue> getMetadata(String key) { return FabricMC.getMetadata(value.getUuid(), key); }
        @Override public void removeMetadata(String key, Object owner) { FabricMC.removeMetadata(value.getUuid(), key); }
        @Override public boolean isOnGround() { return value.isOnGround(); }
        @Override public Object handle() { return value; }
        public Object getShooter() { return FabricMC.entity(value.getOwner()); }
        @Override public void setShooter(Object shooter) {
            if (shooter instanceof Entity entity && entity.handle() instanceof net.minecraft.entity.Entity nativeEntity) {
                value.setOwner(nativeEntity);
            } else if (shooter instanceof net.minecraft.entity.Entity nativeEntity) {
                value.setOwner(nativeEntity);
            }
        }
    }

    private static final class FireballView extends Fireball {
        private final SmallFireballEntity value;

        private FireballView(SmallFireballEntity value) {
            this.value = value;
        }

        @Override public UUID getUniqueId() { return value.getUuid(); }
        @Override public Location getLocation() { return location((ServerWorld) value.getEntityWorld(), value.getEntityPos(), value.getYaw(), value.getPitch()); }
        @Override public World getWorld() { return world((ServerWorld) value.getEntityWorld()); }
        @Override public Vector getVelocity() { return vector(value.getVelocity()); }
        @Override public void setVelocity(Vector velocity) { value.setVelocity(FabricMC.vector(velocity)); value.velocityDirty = true; }
        @Override public boolean isDead() { return !value.isAlive(); }
        @Override public boolean isValid() { return !value.isRemoved(); }
        @Override public void remove() { value.discard(); }
        @Override public int getEntityId() { return value.getId(); }
        @Override public String getName() { return value.getName().getString(); }
        @Override public int getFireTicks() { return value.getFireTicks(); }
        @Override public void setFireTicks(int ticks) { value.setFireTicks(ticks); }
        @Override public float getFallDistance() { return (float) value.fallDistance; }
        @Override public void setFallDistance(float distance) { value.fallDistance = distance; }
        @Override public void setSilent(boolean silent) { value.setSilent(silent); }
        @Override public void setInvulnerable(boolean invulnerable) { value.setInvulnerable(invulnerable); }
        @Override public void setGravity(boolean gravity) { value.setNoGravity(!gravity); }
        @Override public void setPersistent(boolean persistent) { }
        @Override public void setMetadata(String key, MetadataValue metadata) { FabricMC.setMetadata(value.getUuid(), key, metadata); }
        @Override public boolean hasMetadata(String key) { return FabricMC.hasMetadata(value.getUuid(), key); }
        @Override public List<MetadataValue> getMetadata(String key) { return FabricMC.getMetadata(value.getUuid(), key); }
        @Override public void removeMetadata(String key, Object owner) { FabricMC.removeMetadata(value.getUuid(), key); }
        @Override public boolean isOnGround() { return value.isOnGround(); }
        @Override public Object handle() { return value; }
        public Object getShooter() { return FabricMC.entity(value.getOwner()); }
        @Override public void setShooter(Object shooter) {
            if (shooter instanceof Entity entity && entity.handle() instanceof net.minecraft.entity.Entity nativeEntity) {
                value.setOwner(nativeEntity);
            } else if (shooter instanceof net.minecraft.entity.Entity nativeEntity) {
                value.setOwner(nativeEntity);
            }
        }
        @Override public void setDirection(Vector direction) { setVelocity(direction); }
        @Override public void setYield(float yield) { }
        @Override public void setIsIncendiary(boolean incendiary) { }
    }

    private static final class FabricItemStack extends ItemStack {
        private FabricItemStack(Material material, int amount) {
            super(material, amount);
        }
    }
}
