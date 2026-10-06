package com.projectkorra.projectkorra.prediction.rollback;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.protocol.Packet;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import java.util.*;

/** Finalized direct packets only; authority publication and other effects remain owned by the match output adapter. */
public final class PaperRollbackDirectPackets {
    interface Audience {
        void validate();
        void send(UUID recipient, Packet<?> packet);
    }
    private final Thread owner = Thread.currentThread();
    private final Set<UUID> roster;
    private final Audience audience;
    private final PaperRollbackPacketDecoder decoder;
    private final PaperRollbackPacketData inventory;
    private long lastTick;
    private int lastOrdinal = -1;
    private boolean failed;

    /** Bind the exact negotiated connections and captured native player IDs before delivery. */
    public static PaperRollbackDirectPackets bind(Collection<RollbackSession.Peer> peers, UUID world,
            Map<UUID, Integer> nativeIds, RegistryAccess registries) {
        if (!Bukkit.isPrimaryThread() || RollbackDomain.active() || RollbackClock.active())
            throw new IllegalStateException("Bind direct delivery on the live server thread");
        Objects.requireNonNull(world);
        var pinned = new LinkedHashMap<UUID, RollbackSession.Peer>();
        for (var peer : peers) if (pinned.putIfAbsent(peer.player(), peer) != null)
            throw new IllegalArgumentException("Duplicate delivery peer");
        var ids = Map.copyOf(nativeIds);
        if (pinned.isEmpty() || !pinned.keySet().equals(ids.keySet())) throw new IllegalArgumentException("Delivery roster differs from native IDs");
        return new PaperRollbackDirectPackets(pinned.keySet(), registries, new Audience() {
            @Override public void validate() {
                for (var peer : pinned.values()) {
                    var player = Bukkit.getPlayer(peer.player());
                    if (!(player instanceof CraftPlayer craft) || !player.isOnline()
                            || !world.equals(player.getWorld().getUID()) || player.getEntityId() != ids.get(peer.player())
                            || craft.getHandle().connection != peer.connection())
                        throw new IllegalStateException("Direct packet recipient identity changed");
                }
            }
            @Override public void send(UUID recipient, Packet<?> packet) {
                ((net.minecraft.server.network.ServerGamePacketListenerImpl) pinned.get(recipient).connection()).send(packet);
            }
        });
    }

    PaperRollbackDirectPackets(Set<UUID> roster, RegistryAccess registries, Audience audience) {
        requireLive();
        this.roster = Set.copyOf(roster);
        if (this.roster.isEmpty()) throw new IllegalArgumentException("Empty delivery roster");
        this.audience = Objects.requireNonNull(audience);
        decoder = new PaperRollbackPacketDecoder(registries); inventory = new PaperRollbackPacketData(registries);
        audience.validate();
    }

    /** Call only after authority publication, with an effect from that update's finalizedEffects. */
    public void deliver(RollbackEngine.Effect<? extends PaperRollbackCombatAccess.Output> effect, long finalizedTick) {
        requireLive();
        if (failed) throw new IllegalStateException("Direct packet delivery previously failed");
        try {
            Objects.requireNonNull(effect);
            if (effect.tick() < 1 || effect.tick() > finalizedTick || effect.ordinal() < 0
                    || effect.tick() < lastTick || effect.tick() == lastTick && effect.ordinal() <= lastOrdinal)
                throw new IllegalArgumentException("Direct packet is provisional, repeated or out of order");
            audience.validate();
            UUID target;
            Packet<?> packet;
            if (effect.value() instanceof PaperRollbackConnection.PacketOutput value) {
                target = value.target();
                if (!roster.contains(target)) throw new IllegalArgumentException("Foreign packet recipient");
                packet = decoder.decode(value);
            } else if (effect.value() instanceof PaperRollbackPacketData.Direct value) {
                target = value.player();
                if (!roster.contains(target)) throw new IllegalArgumentException("Foreign packet recipient");
                packet = inventory.rebuildInventory(value.data(), id -> id);
            } else throw new IllegalArgumentException("Effect is not a supported direct packet");
            audience.validate();
            // Mark before the irreversible send; an exception must never permit retry.
            lastTick = effect.tick(); lastOrdinal = effect.ordinal();
            audience.send(target, packet);
        } catch (RuntimeException | Error failure) { failed = true; throw failure; }
    }

    private void requireLive() {
        if (Thread.currentThread() != owner || RollbackDomain.active() || RollbackClock.active())
            throw new IllegalStateException("Direct delivery requires the live owning thread");
    }
}
