package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.mc.ChatColor;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Composes the bending action bar from the selected move, active cooldowns,
 * and extensible status providers.
 */
public final class ActionBarStatusManager {

    private static final String SEPARATOR = ChatColor.DARK_GRAY + " | " + ChatColor.RESET;
    private static final long STAMINA_DISPLAY_DURATION_MILLIS = 3000L;
    private static final Map<String, RegisteredProvider> PROVIDERS = new ConcurrentHashMap<>();
    private ActionBarStatusManager() {
    }

    /**
     * Registers or replaces a status provider owned by a plugin.
     * Higher priority providers are displayed first.
     */
    public static void registerProvider(final Plugin plugin, final String id, final int priority, final StatusProvider provider) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(provider, "provider");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Status provider id cannot be blank");
        }

        PROVIDERS.put(providerKey(plugin, id), new RegisteredProvider(plugin, id, priority, provider));
    }

    public static boolean unregisterProvider(final Plugin plugin, final String id) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(id, "id");
        return PROVIDERS.remove(providerKey(plugin, id)) != null;
    }

    public static void unregisterProviders(final Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        PROVIDERS.entrySet().removeIf(entry -> entry.getValue().plugin().equals(plugin));
    }

    public static Set<String> getProviderIds() {
        final Set<String> ids = new LinkedHashSet<>();
        PROVIDERS.values().stream()
                .sorted(Comparator.comparingInt(RegisteredProvider::priority).reversed())
                .forEach(provider -> ids.add(provider.plugin().getName() + ":" + provider.id()));
        return Set.copyOf(ids);
    }

    public static void display(final Player player) {
        display(player, player.getInventory().getHeldItemSlot() + 1, false);
    }

    public static void display(final Player player, final int slot) {
        display(player, slot, false);
    }

    public static void displayPersistent(final Player player) {
        display(player, player.getInventory().getHeldItemSlot() + 1, true);
    }

    private static void display(final Player player, final int slot, final boolean requirePersistentContent) {
        if (!ConfigManager.defaultConfig.get().getBoolean("Properties.BendingPreview")) {
            return;
        }

        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (bPlayer == null) {
            return;
        }

        final List<String> segments = new ArrayList<>();
        final String selectedName = bPlayer.getAbilities().get(slot);
        final CoreAbility selectedAbility = CoreAbility.getAbility(selectedName);
        final boolean selectedOnCooldown = selectedAbility != null && bPlayer.isOnCooldown(selectedAbility);
        bPlayer.setShowPreviewOnCooldown(selectedOnCooldown);

        if (selectedAbility != null) {
            segments.add(selectedAbility.getMovePreview(player));
        }

        final int baseSegmentCount = segments.size();
        addProviderStatuses(segments, player, bPlayer);
        final boolean staminaOnXPBar = ConfigManager.defaultConfig.get().getBoolean(
                "Abilities.Air.AirBlast.ShowStaminaOnXPBar", true);
        if (!staminaOnXPBar && bPlayer.shouldDisplayChangingAirStamina(STAMINA_DISPLAY_DURATION_MILLIS)) {
            addBuiltInStaminaStatus(segments, bPlayer);
        }

        final boolean detailed = bPlayer.isDetailedActionBarEnabled();
        if (!detailed) {
            if (!requirePersistentContent || selectedOnCooldown || segments.size() > baseSegmentCount) {
                ChatUtil.sendActionBar(String.join(SEPARATOR, segments), player);
            }
            return;
        }

        if (staminaOnXPBar) {
            addBuiltInStaminaStatus(segments, bPlayer);
        }
        addOtherCooldowns(segments, bPlayer, selectedAbility);
        if (requirePersistentContent && !selectedOnCooldown && segments.size() == baseSegmentCount) {
            return;
        }

        final int maxLength = Math.max(1, ConfigManager.defaultConfig.get().getInt("Properties.BendingPreviewMaxLength", 120));
        ChatUtil.sendActionBar(truncateLegacy(String.join(SEPARATOR, segments), maxLength), player);
    }

    private static void addBuiltInStaminaStatus(final List<String> segments, final BendingPlayer bPlayer) {
        if (!ConfigManager.defaultConfig.get().getBoolean("Properties.BendingPreviewShowStamina", true)
                || !bPlayer.hasElement(Element.AIR)) {
            return;
        }

        final double minimum = ConfigManager.defaultConfig.get().getDouble("Abilities.Air.AirBlast.DecayMinimum", 0.4);
        final double maximum = ConfigManager.defaultConfig.get().getDouble("Abilities.Air.AirBlast.MaxStamina", 1.2);
        final double range = Math.max(0.01, maximum - minimum);
        final double normalized = Math.max(0.0, Math.min(1.0, (bPlayer.getAirBlastDecay() - minimum) / range));
        segments.add(Element.AIR.getColor() + "Stamina " + Math.round(normalized * 100.0) + "%");
    }

    private static void addProviderStatuses(final List<String> segments, final Player player, final BendingPlayer bPlayer) {
        final List<RegisteredProvider> providers = new ArrayList<>(PROVIDERS.values());
        providers.sort(Comparator.comparingInt(RegisteredProvider::priority).reversed()
                .thenComparing(RegisteredProvider::id));

        for (final RegisteredProvider provider : providers) {
            try {
                final String status = provider.provider().provide(player, bPlayer);
                if (status != null && !status.isBlank()) {
                    segments.add(status);
                }
            } catch (final RuntimeException exception) {
                PROVIDERS.remove(providerKey(provider.plugin(), provider.id()), provider);
                provider.plugin().getLogger().warning("Disabled action-bar status provider '" + provider.id()
                        + "' after it threw " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            }
        }
    }

    private static void addOtherCooldowns(final List<String> segments, final BendingPlayer bPlayer,
                                          final CoreAbility selectedAbility) {
        final int maxCooldowns = Math.max(0, ConfigManager.defaultConfig.get().getInt("Properties.BendingPreviewMaxCooldowns", 3));
        if (maxCooldowns == 0) {
            return;
        }

        final long now = System.currentTimeMillis();
        bPlayer.getCooldowns().entrySet().stream()
                .filter(entry -> entry.getValue().getCooldown() > now)
                .map(entry -> new CooldownEntry(CoreAbility.getAbility(entry.getKey()), entry.getValue().getCooldown()))
                .filter(entry -> entry.ability() != null)
                .filter(entry -> selectedAbility == null || !entry.ability().getName().equalsIgnoreCase(selectedAbility.getName()))
                .sorted(Comparator.comparingLong(CooldownEntry::expiresAt))
                .limit(maxCooldowns)
                .forEach(entry -> segments.add(entry.ability().getElement().getColor() + entry.ability().getName()
                        + ChatColor.GRAY + " " + TimeUtil.formatTime(entry.expiresAt() - now)));
    }

    private static String truncateLegacy(final String message, final int maxVisibleLength) {
        final String plain = ChatColor.stripColor(message);
        if (plain == null || plain.length() <= maxVisibleLength) {
            return message;
        }

        final StringBuilder result = new StringBuilder();
        int visibleLength = 0;
        for (int index = 0; index < message.length() && visibleLength < maxVisibleLength - 1; index++) {
            final char character = message.charAt(index);
            result.append(character);
            if (character == ChatColor.COLOR_CHAR && index + 1 < message.length()) {
                result.append(message.charAt(++index));
            } else {
                visibleLength++;
            }
        }
        return result.append(ChatColor.RESET).append('…').toString();
    }

    private static String providerKey(final Plugin plugin, final String id) {
        return plugin.getName().toLowerCase() + ":" + id.toLowerCase();
    }

    @FunctionalInterface
    public interface StatusProvider {
        /**
         * @return a legacy-colored action-bar segment, or {@code null} to hide it
         */
        String provide(Player player, BendingPlayer bendingPlayer);
    }

    private record RegisteredProvider(Plugin plugin, String id, int priority, StatusProvider provider) {
    }

    private record CooldownEntry(CoreAbility ability, long expiresAt) {
    }
}
