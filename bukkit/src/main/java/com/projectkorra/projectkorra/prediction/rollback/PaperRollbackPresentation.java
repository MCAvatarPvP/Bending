package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.PKListener;
import com.projectkorra.projectkorra.listener.CommonAbilityLifecycleListener;
import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.Pose;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.UUID;

/** Publishes effects only after their originating frame has been confirmed. */
final class PaperRollbackPresentation {
    private final World world;
    private final JavaPlugin plugin;
    private final PKListener listener;
    private final PaperRollbackItemCodec items;
    private final PaperRollbackDirectPackets direct;
    private final java.util.Set<UUID> participants;
    PaperRollbackPresentation(World world, JavaPlugin plugin, PKListener listener, PaperRollbackItemCodec items, PaperRollbackDirectPackets direct, java.util.Set<UUID> participants) {
        this.participants = java.util.Set.copyOf(participants);
        this.world = world; this.plugin = plugin; this.listener = listener; this.items = items; this.direct = direct;
    }
    private Player viewer(UUID id) {
        var player = Bukkit.getPlayer(id);
        return player != null && player.getWorld() == world ? player : null;
    }
    private Location at(Pose pos) { return new Location(world, pos.x(), pos.y(), pos.z(), pos.yaw(), pos.pitch()); }
    private Location at(PaperRollbackCombatAccess.Position pos) { return new Location(world, pos.x(), pos.y(), pos.z()); }
    private org.bukkit.craftbukkit.entity.CraftEntity body(UUID id) {
        if (id == null) return null;
        var entity = world.getEntity(id);
        if (entity == null) entity = viewer(id);
        return (org.bukkit.craftbukkit.entity.CraftEntity) entity;
    }
    @SuppressWarnings("unchecked")
    private void tracked(UUID target, boolean includeSelf, net.minecraft.network.protocol.Packet<?> packet) {
        var entity = body(target);
        if (entity == null) return;
        var level = ((org.bukkit.craftbukkit.CraftWorld) world).getHandle();
        var clientbound = (net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>) packet;
        if (includeSelf) level.getChunkSource().sendToTrackingPlayersAndSelf(entity.getHandle(), clientbound);
        else level.getChunkSource().sendToTrackingPlayers(entity.getHandle(), clientbound);
    }
    private Object particleData(Object data) {
        return switch (data) {
            case null -> null;
            case RollbackArenaActions.BlockValue b -> BukkitMC.blockData(Bukkit.createBlockData(b.state()));
            case RollbackArenaActions.ColorValue c -> com.projectkorra.projectkorra.platform.mc.Color.fromRGB(c.rgb());
            case RollbackArenaActions.DustValue dust -> new com.projectkorra.projectkorra.platform.mc.Particle.DustOptions(
                    com.projectkorra.projectkorra.platform.mc.Color.fromRGB(dust.rgb()), dust.size());
            case RollbackArenaActions.SpellValue spell -> new com.projectkorra.projectkorra.platform.mc.Particle.Spell(
                    com.projectkorra.projectkorra.platform.mc.Color.fromRGB(spell.rgb()), spell.power());
            case com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemData item -> BukkitMC.item(items.decodeBukkit(item));
            default -> data;
        };
    }
    private boolean rendersParticles(Player viewer) {
        return participants.contains(viewer.getUniqueId()) && com.projectkorra.projectkorra.prediction.server.PaperPredictionServer
                .rendersReplicaParticles(viewer.getUniqueId());
    }
    void publish(RollbackEngine.Effect<Object> effect, long confirmed) {
        Object event = effect.value();
        if (event instanceof PaperRollbackConnection.PacketOutput || event instanceof PaperRollbackPacketData.Direct directPacket
                && !(directPacket.data() instanceof PaperRollbackPacketData.Advancements
                || directPacket.data() instanceof PaperRollbackPacketData.EntityStatus || directPacket.data() instanceof PaperRollbackPacketData.Animation)) {
            direct.deliver(new RollbackEngine.Effect<>(effect.tick(), effect.ordinal(), (PaperRollbackCombatAccess.Output) event), confirmed);
            return;
        }
        if (event instanceof PaperRollbackCombatAccess.SoundOutput sound) {
            var at = at(sound.position());
            double maximum = Math.max(16, 16 * sound.volume());
            var category = SoundCategory.valueOf(sound.category().toUpperCase(java.util.Locale.ROOT));
            for (var player : world.getPlayers()) {
                if (player.getUniqueId().equals(sound.excludedSource()) || player.getLocation().distanceSquared(at) > maximum * maximum) continue;
                player.playSound(at, sound.sound(), category, sound.volume(), sound.pitch(), sound.seed());
            }
        } else if (event instanceof PaperRollbackCombatAccess.TargetSoundOutput sound) {
            var player = viewer(sound.target());
            if (player != null) player.playSound(at(sound.position()), sound.sound(), SoundCategory.valueOf(
                    sound.category().toUpperCase(java.util.Locale.ROOT)), sound.volume(), sound.pitch(), sound.seed());
        } else if (event instanceof PaperRollbackCombatAccess.ParticleOutput visual) {
            var nativeType = org.bukkit.Registry.PARTICLE_TYPE.get(NamespacedKey.fromString(visual.type()));
            if (nativeType != null) world.spawnParticle(nativeType, at(visual.position()), visual.count(), visual.x(), visual.y(), visual.z(), visual.speed());
        } else if (event instanceof PaperRollbackCombatAccess.StatusOutput status) {
            var target = body(status.entity());
            if (target != null) tracked(status.entity(), true, new net.minecraft.network.protocol.game.ClientboundEntityEventPacket(target.getHandle(), status.status()));
        } else if (event instanceof PaperRollbackCombatAccess.DamageOutput damage) {
            var target = body(damage.entity()); if (target == null) return;
            var level = ((org.bukkit.craftbukkit.CraftWorld) world).getHandle();
            var type = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE)
                    .get(net.minecraft.resources.Identifier.parse(damage.type())).orElseThrow();
            var attacker = body(damage.attacker()); var source = body(damage.source());
            var origin = java.util.Optional.ofNullable(damage.position()).map(p -> new net.minecraft.world.phys.Vec3(p.x(), p.y(), p.z()));
            tracked(damage.entity(), true, new net.minecraft.network.protocol.game.ClientboundDamageEventPacket(
                    target.getEntityId(), type, attacker == null ? -1 : attacker.getEntityId(), source == null ? -1 : source.getEntityId(), origin));
        } else if (event instanceof PaperRollbackPacketData.Tracked tracked) {
            var entity = body(tracked.entity()); if (entity == null) return;
            var level = ((org.bukkit.craftbukkit.CraftWorld) world).getHandle();
            net.minecraft.network.protocol.Packet<?> packet = switch (tracked.data()) {
                case PaperRollbackPacketData.Animation a -> new net.minecraft.network.protocol.game.ClientboundAnimatePacket(entity.getHandle(), a.animation());
                case PaperRollbackPacketData.EntityStatus s -> new net.minecraft.network.protocol.game.ClientboundEntityEventPacket(entity.getHandle(), s.status());
                case PaperRollbackPacketData.Equipment e -> new PaperRollbackPacketData(level.registryAccess()).rebuildInventory(e, unused -> entity.getEntityId());
                default -> throw new IllegalArgumentException("Unsupported tracked native output");
            };
            tracked(tracked.entity(), tracked.includeSelf(), packet);
        } else if (event instanceof CommonAbilityLifecycleListener.Effect lifecycle) {
            listener.publishLifecycleEffect(lifecycle);
        } else if (event instanceof RollbackArenaActions.Message message) {
            var player = viewer(message.player());
            if (player != null) BukkitMC.player(player).spigot().sendMessage(message.type(),
                    com.projectkorra.projectkorra.platform.chat.TextComponent.fromLegacyText(message.message()));
        } else if (event instanceof RollbackArenaActions.ParticleOutput visual) {
            var particle = com.projectkorra.projectkorra.platform.mc.Particle.valueOf(visual.particle());
            var pos = BukkitMC.location(at(visual.position()));
            var payload = particleData(visual.data());
            if (visual.viewer() == null) {
                var origin = at(visual.position());
                // Match the native non-forced broadcast radius. Each opted-in replica draws its own copy.
                for (var player : world.getNearbyPlayers(origin, 32)) {
                    if (player.getLocation().distanceSquared(origin) < 32 * 32 && !rendersParticles(player))
                        BukkitMC.player(player).spawnParticle(particle, pos, visual.count(), visual.x(),
                                visual.y(), visual.z(), visual.extra(), payload, false);
                }
            } else {
                var player = viewer(visual.viewer());
                if (player != null && !rendersParticles(player)) BukkitMC.player(player).spawnParticle(particle, pos,
                        visual.count(), visual.x(), visual.y(), visual.z(), visual.extra(), payload, visual.force());
            }
        } else if (event instanceof RollbackArenaActions.EffectOutput visual) {
            Object data = visual.data();
            if (data instanceof com.projectkorra.projectkorra.platform.mc.Material material) data = BukkitMC.material(material);
            if (data instanceof com.projectkorra.projectkorra.platform.mc.block.BlockFace face) data = org.bukkit.block.BlockFace.valueOf(face.name());
            var type = org.bukkit.Effect.valueOf(visual.effect());
            if (data instanceof Integer integer) world.playEffect(at(visual.position()), type, integer.intValue(), visual.radius());
            else world.playEffect(at(visual.position()), type, data, visual.radius());
        } else if (event instanceof RollbackArenaActions.BlockOutput block) {
            var player = viewer(block.player());
            var pos = block.position();
            if (player != null) player.sendBlockChange(new Location(world, pos.x(), pos.y(), pos.z()),
                    Bukkit.createBlockData(block.data().state()));
        } else if (event instanceof RollbackArenaActions.DisplayName name) {
            var player = viewer(name.player());
            if (player != null) player.setDisplayName(name.name());
        } else if (event instanceof RollbackArenaActions.HideDisplay hide) {
            var player = viewer(hide.player());
            var display = world.getEntity(hide.display());
            if (player != null && display != null) player.hideEntity(plugin, display);
        } else if (event instanceof RollbackArenaActions.Lightning lightning) {
            world.strikeLightningEffect(at(lightning.position()));
        } else if (event instanceof RollbackArenaActions.SoundOutput sound) {
            var category = SoundCategory.valueOf(sound.category());
            var key = sound.named() ? sound.sound() : Sound.valueOf(sound.sound()).getKey().toString();
            var source = sound.source() == null ? null : world.getEntity(sound.source());
            var position = source == null ? at(sound.position()) : source.getLocation();
            if (sound.viewer() == null) world.playSound(position, key, category, sound.volume(), sound.pitch());
            else {
                var player = viewer(sound.viewer());
                if (player != null) player.playSound(position, key, category, sound.volume(), sound.pitch());
            }
        } else if (event instanceof RollbackArenaActions.NoteOutput note) {
            var player = viewer(note.player());
            if (player != null) player.playNote(at(note.position()), Instrument.valueOf(note.instrument().name()),
                    new Note(note.octave(), Note.Tone.valueOf(note.tone().name()), note.sharp()));
        }
    }
}
