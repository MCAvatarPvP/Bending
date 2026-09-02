package com.projectkorra.projectkorra.prediction.server.impl;

import com.projectkorra.projectkorra.prediction.protocol.PaperPredictionProtocol;
import com.projectkorra.projectkorra.prediction.snapshot.PaperPredictionSnapshot;
import com.projectkorra.projectkorra.prediction.snapshot.PaperRegionProtectionSnapshot;

import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.action.AbilityRemovalSync;
import com.projectkorra.projectkorra.prediction.action.NativeActionTagStream;
import com.projectkorra.projectkorra.prediction.action.PredictionActionSeed;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.prediction.authority.PredictionVisibility;
import com.projectkorra.projectkorra.prediction.authority.RegionProtectionAuthority;
import com.projectkorra.projectkorra.prediction.block.DirectBlockSync;
import com.projectkorra.projectkorra.prediction.block.TempBlockDeliveryTracker;
import com.projectkorra.projectkorra.prediction.block.TempBlockSync;
import com.projectkorra.projectkorra.prediction.block.TempFallingBlockSync;
import com.projectkorra.projectkorra.prediction.hit.ConfirmedHitEffects;
import com.projectkorra.projectkorra.prediction.hit.HitRewind;
import com.projectkorra.projectkorra.prediction.hit.HitRegistrationPolicy;
import com.projectkorra.projectkorra.prediction.movement.VelocitySync;
import com.projectkorra.projectkorra.prediction.state.AbilityCheckpointSync;
import com.projectkorra.projectkorra.prediction.state.AbilityStateSync;
import com.projectkorra.projectkorra.prediction.state.GlidingStateSync;
import com.projectkorra.projectkorra.prediction.state.CooldownSync;
import com.projectkorra.projectkorra.prediction.state.PlayerStatusSync;

import com.jedk1.jedcore.ability.passive.WallRun;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.ability.util.ComboManager;
import com.projectkorra.projectkorra.ability.util.MultiAbilityManager;
import com.projectkorra.projectkorra.ability.util.PassiveManager;
import com.projectkorra.projectkorra.firebending.FireBlastCharged;
import com.projectkorra.projectkorra.airbending.AirBurst;
import com.projectkorra.projectkorra.airbending.AirGlider;
import com.projectkorra.projectkorra.earthbending.EarthSmash;
import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.waterbending.passive.FastSwim;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import com.projectkorra.projectkorra.prediction.server.PaperPredictionServer;

public abstract class PaperPredictionSnapshots extends PaperPredictionDelivery {
    protected PaperPredictionSnapshots(final JavaPlugin plugin) {
        super(plugin);
    }

    protected void requestSnapshotRebuild(boolean broadcastChanges) {
        if (!snapshotBuildRunning.compareAndSet(false, true)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<PaperPredictionProtocol.ConfigEntry> nextConfig;
            List<PaperPredictionProtocol.AbilityProfile> nextProfiles;
            try {
                nextConfig = PaperPredictionSnapshot.config();
                nextProfiles = PaperPredictionSnapshot.profiles();
            } catch (Throwable failure) {
                snapshotBuildRunning.set(false);
                plugin.getLogger().warning("Could not build prediction config snapshot asynchronously: " + failure.getMessage());
                return;
            }
            long nextEpoch = Integer.toUnsignedLong(31 * nextConfig.hashCode() + nextProfiles.hashCode());
            Bukkit.getScheduler().runTask(plugin, () -> {
                snapshotBuildRunning.set(false);
                if (PaperPredictionServer.activeInstance() != this) return;
                boolean first = !snapshotReady;
                boolean changed = nextEpoch != configEpoch;
                publicConfig = nextConfig;
                profiles = nextProfiles;
                configEpoch = nextEpoch;
                snapshotReady = true;
                if (first || broadcastChanges && changed) sessions.values().forEach(this::sendSnapshot);
            });
        });
    }

    protected void sendSnapshot(Session session) {
        Player player = Bukkit.getPlayer(session.player);
        if (player == null) return;
        BendingPlayer bending = BendingPlayer.getBendingPlayer(BukkitMC.player(player));
        Map<Integer, String> binds = PaperPredictionSnapshot.binds(bending);
        Map<String, Long> cooldowns = PaperPredictionSnapshot.cooldowns(bending);
        List<String> elements = PaperPredictionSnapshot.elements(bending), subs = PaperPredictionSnapshot.subElements(bending);
        List<String> permissions = predictionPermissions(player, session);
        double airBlastDecay = bending == null ? 1.0 : bending.getAirBlastDecay();
        boolean chiBlocked = bending != null && bending.isChiBlocked();
        PaperPredictionProtocol.PlayerCosmetics cosmetics = playerCosmetics(bending);
        RegionProtectionAuthority.Snapshot regionProtection =
                regionProtectionSnapshot(player, bending, binds, session);
        int digest = 31 * binds.hashCode() + cooldowns.hashCode();
        digest = 31 * digest + elements.hashCode();
        digest = 31 * digest + subs.hashCode();
        digest = 31 * digest + permissions.hashCode();
        digest = 31 * digest + Double.hashCode(airBlastDecay);
        digest = 31 * digest + Boolean.hashCode(chiBlocked);
        digest = 31 * digest + cosmetics.hashCode();
        session.stateDigest = 31 * digest + regionProtection.hashCode();
        List<PaperPredictionProtocol.ConfigEntry> config = publicConfig;
        List<PaperPredictionProtocol.AbilityProfile> profileSnapshot = profiles;
        long epoch = configEpoch;
        long serverTick = tick;
        long serverNow = System.currentTimeMillis();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<OutboundPayload> outbound = new ArrayList<>();
            byte[] payload = PaperPredictionProtocol.snapshot(session.session, serverTick, serverNow, epoch,
                    MAX_REWIND_TICKS, config, profileSnapshot, binds, cooldowns, elements, subs,
                    permissions, airBlastDecay, chiBlocked, cosmetics, regionProtection);
            if (payload.length > Messenger.MAX_MESSAGE_SIZE) {
                List<List<PaperPredictionProtocol.ConfigEntry>> chunks = configChunks(config, Messenger.MAX_MESSAGE_SIZE - 128);
                for (int i = 0; i < chunks.size(); i++) {
                    outbound.add(new OutboundPayload(PaperPredictionProtocol.CONFIG_CHUNK,
                            PaperPredictionProtocol.configChunk(session.session, epoch, i, chunks.size(), chunks.get(i))));
                }
                payload = PaperPredictionProtocol.snapshot(session.session, serverTick, serverNow, epoch,
                        MAX_REWIND_TICKS, List.of(), profileSnapshot, binds, cooldowns, elements, subs,
                        permissions, airBlastDecay, chiBlocked, cosmetics, regionProtection);
            }
            if (payload.length > Messenger.MAX_MESSAGE_SIZE) {
                int keep = profileSnapshot.size();
                while (payload.length > Messenger.MAX_MESSAGE_SIZE && keep > 0) {
                    keep /= 2;
                    payload = PaperPredictionProtocol.snapshot(session.session, serverTick, serverNow, epoch,
                            MAX_REWIND_TICKS, List.of(), profileSnapshot.subList(0, keep), binds, cooldowns,
                            elements, subs, permissions, airBlastDecay, chiBlocked, cosmetics, regionProtection);
                }
            }
            outbound.add(new OutboundPayload(PaperPredictionProtocol.SNAPSHOT, payload));
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player current = Bukkit.getPlayer(session.player);
                if (current == null || sessions.get(session.player) != session) return;
                for (OutboundPayload message : outbound) send(current, message.channel(), message.payload());
                sendWorldState(current, session);
                sendTempBlockSnapshot(current, session);
            });
        });
    }

    /**
     * Captures decisions, not permission-plugin internals. Registered feature
     * nodes (WaterSpout.Wave, WaterArms modes, Flight modes, and addons) are
     * evaluated through Bukkit exactly as the authoritative ability will see
     * them. Unknown nodes remain denied on the client instead of silently
     * taking a branch Paper may reject.
     */
    protected List<String> predictionPermissions(final Player player, final Session session) {
        if (player == null) return List.of();
        final Set<String> assignments = new HashSet<>();
        final List<String> effectiveNodes = new ArrayList<>();
        player.getEffectivePermissions().forEach(info -> {
            final String node = info.getPermission();
            if (node == null || node.isBlank()) return;
            final String normalized = node.toLowerCase(Locale.ROOT);
            assignments.add((info.getValue() ? "+" : "-") + normalized);
            effectiveNodes.add(normalized);
        });
        final PermissionContext context = new PermissionContext(permissionCandidateGeneration,
                player.isOp(), Set.copyOf(assignments));
        if (session != null && context.equals(session.permissionContext)) {
            return session.predictionPermissions;
        }

        final List<String> abilityNodes = new ArrayList<>();
        final List<String> otherNodes = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        final List<String> candidates = new ArrayList<>(permissionCandidates.size() + effectiveNodes.size());
        candidates.addAll(permissionCandidates);
        candidates.addAll(effectiveNodes);
        for (String node : candidates) {
            if (node == null || !node.regionMatches(true, 0, "bending.", 0, 8)
                    || node.indexOf('*') >= 0) continue;
            final String normalized = node.toLowerCase(Locale.ROOT);
            if (!seen.add(normalized) || !player.hasPermission(normalized)) continue;
            if (normalized.startsWith("bending.ability.")) abilityNodes.add(normalized);
            else otherNodes.add(normalized);
        }
        abilityNodes.sort(String::compareTo);
        otherNodes.sort(String::compareTo);
        final List<String> result = new ArrayList<>(Math.min(MAX_PREDICTION_PERMISSIONS,
                abilityNodes.size() + otherNodes.size()));
        for (String node : abilityNodes) {
            if (result.size() == MAX_PREDICTION_PERMISSIONS) break;
            result.add(node);
        }
        if (result.size() < MAX_PREDICTION_PERMISSIONS) {
            for (String node : otherNodes) {
                if (result.size() == MAX_PREDICTION_PERMISSIONS) break;
                result.add(node);
            }
        }
        final List<String> resolved = List.copyOf(result);
        if (session != null) {
            session.permissionContext = context;
            session.predictionPermissions = resolved;
        }
        return resolved;
    }

    protected void rebuildPermissionCandidates() {
        final Set<String> candidates = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (CoreAbility ability : CoreAbility.getAbilities()) {
            if (ability == null || ability.getName() == null || ability.getName().isBlank()) continue;
            candidates.add("bending.ability." + ability.getName());
        }
        candidates.addAll(expandPermissionCandidates(Bukkit.getPluginManager().getPermissions()));
        // WaterSpoutWave is a feature branch whose ability name is
        // intentionally also "WaterSpout". Keep its child node even if Bukkit
        // does not register plugin.yml children as standalone permissions.
        candidates.add("bending.ability.WaterSpout.Wave");
        final List<String> rebuilt = List.copyOf(candidates);
        if (!rebuilt.equals(permissionCandidates)) {
            permissionCandidates = rebuilt;
        }
        // Refresh custom permission-provider decisions periodically even when
        // Bukkit's registered node graph and effective attachments are stable.
        permissionCandidateGeneration++;
    }

    /**
     * Bukkit registers parent permissions from plugin.yml, but a child used by
     * ability code is not required to be registered as its own Permission.
     * Walk the complete parent graph so decisions such as
     * bending.ability.WaterSpout.Wave are synchronized even when they only
     * appear as a child of bending.water.
     */
    static Set<String> expandPermissionCandidates(
            final Collection<org.bukkit.permissions.Permission> registered) {
        if (registered == null || registered.isEmpty()) return Set.of();
        final Map<String, org.bukkit.permissions.Permission> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (org.bukkit.permissions.Permission permission : registered) {
            if (permission != null && permission.getName() != null && !permission.getName().isBlank()) {
                byName.put(permission.getName(), permission);
            }
        }
        final Set<String> result = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        final Deque<String> pending = new ArrayDeque<>(byName.keySet());
        while (!pending.isEmpty()) {
            final String node = pending.removeFirst();
            if (node == null || node.isBlank() || !result.add(node)) continue;
            final org.bukkit.permissions.Permission permission = byName.get(node);
            if (permission == null) continue;
            for (String child : permission.getChildren().keySet()) {
                if (child != null && !child.isBlank() && !result.contains(child)) pending.addLast(child);
            }
        }
        return Set.copyOf(result);
    }

    /**
     * A client can join or re-handshake while long-lived TempBlocks already
     * exist. Rebuild its complete in-range layer ledger after the normal
     * prediction snapshot so chunk packets can never leave invisible blocks.
     */
    protected void sendTempBlockSnapshot(final Player player, final Session session) {
        final WorldScope scope = refreshWorldScope(player, session);
        final Location location = player.getLocation();
        final String viewerWorld = scope.identity();
        final List<PaperPredictionProtocol.TempBlockOp> operations = new ArrayList<>();
        for (TempBlock layer : TempBlock.getActiveLayers()) {
            final Block block = layer.getBlock();
            if (block.getWorld() == null || !(block.getWorld().handle() instanceof World world)
                    || !PredictionVisibility.tracksBlock(viewerWorld, world.getUID().toString(),
                    location.getBlockX(), location.getBlockZ(), block.getX(), block.getZ(),
                    player.getClientViewDistance())) continue;
            final Action action = tempLayerActions.get(layer.getLayerId());
            final String effectAbility = tempLayerEffects.containsKey(layer.getLayerId())
                    ? tempLayerEffects.get(layer.getLayerId()).ability() : layer.getEffectAbility();
            final UUID predictedOwner = predictedTempBlockOwner(
                    layer.getOwnerId().orElse(null), action, effectAbility);
            final BlockData viewerData = predictedViewerData(block, session.player, block.getBlockData());
            operations.add(new PaperPredictionProtocol.TempBlockOp(
                    PaperPredictionProtocol.TempOperation.CREATE, worldKey(block.getWorld()),
                    block.getX(), block.getY(), block.getZ(), TempBlockSync.encode(layer.getBlockData()),
                    layer.getRevertTime(), action == null ? 0L : action.sequence,
                    effectAbility, layer.getAbility().map(CoreAbility::getPredictionState).orElse(""),
                    tempLayerEffects.containsKey(layer.getLayerId())
                            ? tempLayerEffects.get(layer.getLayerId()).step() : layer.getEffectStep(),
                    tempLayerEffects.containsKey(layer.getLayerId())
                            ? tempLayerEffects.get(layer.getLayerId()).ordinal() : layer.getEffectOrdinal(),
                    layer.getLayerId(), layer.getRevision(), predictedOwner,
                    TempBlockSync.encode(viewerData), false));
            session.tempLayers.markActive(layer.getLayerId());
        }
        sendTempBlockOperations(player, session, operations, true);
        session.tempBlockSnapshotInitialized = true;
        session.lastTempBlockSnapshotTick = tick;
        session.lastTempBlockSnapshotChunkX = location.getBlockX() >> 4;
        session.lastTempBlockSnapshotChunkZ = location.getBlockZ() >> 4;
        session.lastTempBlockSnapshotViewDistance = player.getClientViewDistance();
    }

    protected boolean shouldSendPeriodicTempBlockSnapshot(final Player player, final Session session) {
        if (!session.tempBlockSnapshotInitialized) return true;
        if (tick - session.lastTempBlockSnapshotTick >= TEMP_BLOCK_SNAPSHOT_SAFETY_TICKS) return true;
        final Location location = player.getLocation();
        return (location.getBlockX() >> 4) != session.lastTempBlockSnapshotChunkX
                || (location.getBlockZ() >> 4) != session.lastTempBlockSnapshotChunkZ
                || player.getClientViewDistance() != session.lastTempBlockSnapshotViewDistance;
    }

    protected void sendWorldState(final Player player, final Session session) {
        if (player == null || session == null || player.getWorld() == null) return;
        final WorldScope scope = refreshWorldScope(player, session);
        send(player, PaperPredictionProtocol.WORLD_STATE,
                PaperPredictionProtocol.worldState(session.session, scope.generation(), scope.identity()));
    }

    protected WorldScope refreshWorldScope(final Player player, final Session session) {
        final String identity = player.getWorld().getUID().toString();
        if (!identity.equals(session.worldIdentity)) {
            session.worldIdentity = identity;
            session.worldGeneration++;
            session.tempLayers.clear();
            session.tempBlockSnapshotInitialized = false;
        }
        return new WorldScope(session.worldGeneration, session.worldIdentity);
    }

    protected List<List<PaperPredictionProtocol.ConfigEntry>> configChunks(
            List<PaperPredictionProtocol.ConfigEntry> source, int budget) {
        List<PaperPredictionProtocol.ConfigEntry> fragments = new ArrayList<>();
        for (PaperPredictionProtocol.ConfigEntry entry : source)
            splitEntry(entry, Math.max(16_384, budget - 64), fragments);
        List<List<PaperPredictionProtocol.ConfigEntry>> chunks = new ArrayList<>();
        List<PaperPredictionProtocol.ConfigEntry> current = new ArrayList<>();
        int size = 32;
        for (PaperPredictionProtocol.ConfigEntry entry : fragments) {
            int entrySize = PaperPredictionProtocol.configEntrySize(entry);
            if (!current.isEmpty() && size + entrySize > budget) {
                chunks.add(List.copyOf(current));
                current.clear();
                size = 32;
            }
            current.add(entry);
            size += entrySize;
        }
        if (!current.isEmpty()) chunks.add(List.copyOf(current));
        return chunks;
    }

    protected void splitEntry(PaperPredictionProtocol.ConfigEntry entry, int budget,
                            List<PaperPredictionProtocol.ConfigEntry> output) {
        if (PaperPredictionProtocol.configEntrySize(entry) <= budget
                || entry.type() != PaperPredictionProtocol.ValueType.STRING_LIST || entry.values().size() <= 1) {
            output.add(entry);
            return;
        }
        List<String> part = new ArrayList<>();
        for (String value : entry.values()) {
            part.add(value);
            PaperPredictionProtocol.ConfigEntry candidate = new PaperPredictionProtocol.ConfigEntry(entry.path(), entry.type(), List.copyOf(part));
            if (PaperPredictionProtocol.configEntrySize(candidate) > budget && part.size() > 1) {
                String overflow = part.remove(part.size() - 1);
                output.add(new PaperPredictionProtocol.ConfigEntry(entry.path(), entry.type(), List.copyOf(part)));
                part.clear();
                part.add(overflow);
            }
        }
        if (!part.isEmpty())
            output.add(new PaperPredictionProtocol.ConfigEntry(entry.path(), entry.type(), List.copyOf(part)));
    }

    protected void reconcile(Player player, Session session, long sequence, boolean accepted, String reason,
                           String ability, Location origin, long cooldown, boolean inputHandled,
                           boolean comboRecorded, List<String> createdAbilities) {
        send(player, PaperPredictionProtocol.RECONCILE, PaperPredictionProtocol.reconcile(session.session, sequence, accepted,
                reason, tick, System.currentTimeMillis(), ability, origin.getX(), origin.getY(), origin.getZ(), cooldown,
                inputHandled, comboRecorded, createdAbilities));
        if (isPersistentFlightAbility(ability)) sendState(player, session, true);
    }

    protected static boolean isPersistentFlightAbility(final String ability) {
        return ability != null && (ability.equalsIgnoreCase("AirScooter")
                || ability.equalsIgnoreCase("AirSpout")
                || ability.equalsIgnoreCase("WaterSpout")
                || ability.equalsIgnoreCase("SandSpout")
                || ability.equalsIgnoreCase("AirGlider")
                || ability.equalsIgnoreCase("FireJet")
                || ability.equalsIgnoreCase("Flight"));
    }

    protected static ComboManager.AbilityInformation latestComboInput(
            final com.projectkorra.projectkorra.platform.mc.entity.Player player) {
        if (player == null) return null;
        final List<ComboManager.AbilityInformation> recent = ComboManager.getRecentlyUsedAbilities(player, 1);
        return recent.isEmpty() ? null : recent.get(recent.size() - 1);
    }

    protected void send(Player player, String channel, byte[] payload) {
        if (payload.length <= Messenger.MAX_MESSAGE_SIZE) player.sendPluginMessage(plugin, channel, payload);
    }

    protected Session valid(Player player, UUID session) {
        Session current = sessions.get(player.getUniqueId());
        return current != null && current.session.equals(session) ? current : null;
    }

}
