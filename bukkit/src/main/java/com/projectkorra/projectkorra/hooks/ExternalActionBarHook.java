package com.projectkorra.projectkorra.hooks;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.adventure.AdventureSerializer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerActionBar;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.ActionBarStatusManager;
import com.projectkorra.projectkorra.util.ChatUtil;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures action bars emitted outside ProjectKorra and contributes them to its
 * compositor, preventing multiple packet senders from racing for the display.
 */
public final class ExternalActionBarHook extends PacketListenerAbstract {

    private static final String PROVIDER_ID = "external-action-bar";
    private static final long STATUS_TTL_MILLIS = 1500L;

    private final ProjectKorra plugin;
    private final Map<UUID, CapturedStatus> statuses = new ConcurrentHashMap<>();

    private ExternalActionBarHook(final ProjectKorra plugin) {
        this.plugin = plugin;
    }

    public static ExternalActionBarHook register(final ProjectKorra plugin) {
        final ExternalActionBarHook hook = new ExternalActionBarHook(plugin);
        PacketEvents.getAPI().getEventManager().registerListener(hook);
        ActionBarStatusManager.registerProvider(plugin, PROVIDER_ID, 100, hook::provideStatus);
        ChatUtil.setActionBarSender(hook::sendInternal);
        plugin.getLogger().info("Enabled external action-bar packet merging.");
        return hook;
    }

    @Override
    public void onPacketSend(final PacketSendEvent event) {
        final String legacyText;
        if (event.getPacketType() == PacketType.Play.Server.ACTION_BAR) {
            final WrapperPlayServerActionBar packet = new WrapperPlayServerActionBar(event);
            legacyText = AdventureSerializer.toLegacyFormat(packet.getActionBarText());
        } else if (event.getPacketType() == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE) {
            final WrapperPlayServerSystemChatMessage packet = new WrapperPlayServerSystemChatMessage(event);
            if (!packet.isOverlay()) {
                return;
            }
            legacyText = AdventureSerializer.toLegacyFormat(packet.getMessage());
        } else {
            return;
        }

        final Object recipient = event.getPlayer();
        if (!(recipient instanceof Player player) || BendingPlayer.getBendingPlayer(player) == null
                || !ConfigManager.defaultConfig.get().getBoolean("Properties.BendingPreview")) {
            return;
        }
        event.setCancelled(true);
        if (legacyText != null && !legacyText.isBlank()) {
            this.statuses.put(player.getUniqueId(),
                    new CapturedStatus(legacyText, System.currentTimeMillis() + STATUS_TTL_MILLIS));
        }
    }

    private String provideStatus(final Player player,
                                 final BendingPlayer bendingPlayer) {
        final CapturedStatus status = this.statuses.get(player.getUniqueId());
        if (status == null) {
            return null;
        }
        if (status.expiresAt() <= System.currentTimeMillis()) {
            this.statuses.remove(player.getUniqueId(), status);
            return null;
        }
        return status.text();
    }

    public void stop() {
        ChatUtil.setActionBarSender(null);
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
        ActionBarStatusManager.unregisterProvider(this.plugin, PROVIDER_ID);
        this.statuses.clear();
    }

    private void sendInternal(final Player player, final String message) {
        final WrapperPlayServerActionBar packet = new WrapperPlayServerActionBar(
                AdventureSerializer.fromLegacyFormat(message));
        PacketEvents.getAPI().getPlayerManager().sendPacketSilently(player, packet);
    }

    private record CapturedStatus(String text, long expiresAt) {
    }
}
