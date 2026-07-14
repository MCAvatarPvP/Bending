package com.projectkorra.projectkorra.object;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.board.BendingBoardManager;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.configuration.PKConfiguration;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitRunnable;
import com.projectkorra.projectkorra.storage.DBConnection;
import com.projectkorra.projectkorra.storage.PresetRecord;
import com.projectkorra.projectkorra.storage.StorageException;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A savable association of abilities and hotbar slots, stored per player.
 *
 * @author kingbirdy
 *
 */
public class Preset {

    /**
     * ConcurrentHashMap that stores a list of every Player's {@link Preset
     * presets}, keyed to their UUID
     */
    public static Map<UUID, List<Preset>> presets = new ConcurrentHashMap<>();
    public static PKConfiguration config = ConfigManager.presetConfig.get();
    public static HashMap<String, ArrayList<String>> externalPresets = new HashMap<>();

    private final UUID uuid;
    private final HashMap<Integer, String> abilities;
    private final String name;

    /**
     * Creates a new {@link Preset}
     *
     * @param uuid      The UUID of the Player who the Preset belongs to
     * @param name      The name of the Preset
     * @param abilities A HashMap of the abilities to be saved in the Preset,
     *                  keyed to the slot they're bound to
     */
    public Preset(final UUID uuid, final String name, final HashMap<Integer, String> abilities) {
        this.uuid = uuid;
        this.name = name;
        this.abilities = abilities;
        if (!presets.containsKey(uuid)) {
            presets.put(uuid, new ArrayList<>());
        }
        presets.get(uuid).add(this);
    }

    /**
     * Unload a Player's Presets from those stored in memory.
     *
     * @param player The Player who's Presets should be unloaded
     */
    public static void unloadPreset(final Player player) {
        final UUID uuid = player.getUniqueId();
        presets.remove(uuid);
    }

    /**
     * Load a Player's Presets into memory.
     *
     * @param player The Player who's Presets should be loaded
     */
    public static void loadPresets(final Player player) {
        new BukkitRunnable() {
            @Override
            public void run() {
                final UUID uuid = player.getUniqueId();
                if (uuid == null) {
                    return;
                }
                try {
                    final List<PresetRecord> records = DBConnection.getAdapter().presets().load(uuid);
                    if (!records.isEmpty()) {
                        int i = 0;
                        for (final PresetRecord record : records) {
                            new Preset(uuid, record.getName(), new HashMap<>(record.getSlots()));
                            i++;
                        }
                        ProjectKorra.log.info("Loaded " + i + " presets for " + player.getName());
                    }
                } catch (final StorageException ex) {
                    ex.printStackTrace();
                }
            }
        }.runTaskAsynchronously(ProjectKorra.plugin);
    }

    /**
     * Reload a Player's Presets from those stored in memory.
     *
     * @param player The Player who's Presets should be unloaded
     */
    public static void reloadPreset(final Player player) {
        unloadPreset(player);
        loadPresets(player);
    }

    /**
     * Binds the abilities from a Preset for the given Player.
     *
     * @param player The Player the Preset should be bound for
     * @param preset The Preset that should be bound
     * @return True if all abilities were successfully bound, or false otherwise
     */
    public static boolean bindPreset(final Player player, final Preset preset) {
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (bPlayer == null) {
            return false;
        }

        final HashMap<Integer, String> abilities = new HashMap<>(preset.abilities);
        boolean boundAll = true;
        for (int i = 1; i <= 9; i++) {
            final CoreAbility coreAbil = CoreAbility.getAbility(abilities.get(i));
            if (coreAbil != null) {
                abilities.put(i, coreAbil.getName());
            }
            if (coreAbil != null && (!bPlayer.canBind(coreAbil) || !player.hasPermission("bending.element." + GeneralMethods.getParentElement(coreAbil.getElement()).getName()))) {
                abilities.remove(i);
                boundAll = false;
            }
        }
        bPlayer.setAbilities(abilities);
        BendingBoardManager.updateAllSlots(player);
        return boundAll;
    }

    /**
     * Checks if a Preset with a certain name exists for a given Player.
     *
     * @param player The player who's Presets should be checked
     * @param name   The name of the Preset to look for
     * @return true if the Preset exists, false otherwise
     */
    public static boolean presetExists(final Player player, final String name) {
        if (!presets.containsKey(player.getUniqueId())) {
            return false;
        }
        boolean exists = false;
        for (final Preset preset : presets.get(player.getUniqueId())) {
            if (preset.name.equalsIgnoreCase(name)) {
                exists = true;
            }
        }
        return exists;
    }

    /**
     * Gets a Preset for the specified Player.
     *
     * @param player The Player who's Preset should be gotten
     * @param name   The name of the Preset to get
     * @return The Preset, if it exists, or null otherwise
     */
    public static Preset getPreset(final Player player, final String name) {
        if (!presets.containsKey(player.getUniqueId())) {
            return null;
        }
        for (final Preset preset : presets.get(player.getUniqueId())) {
            if (preset.name.equalsIgnoreCase(name)) {
                return preset;
            }
        }
        return null;
    }

    public static void loadExternalPresets() {
        final HashMap<String, ArrayList<String>> presets = new HashMap<String, ArrayList<String>>();
        for (final String name : config.getKeys(false)) {
            if (!presets.containsKey(name)) {
                if (!config.getStringList(name).isEmpty() && config.getStringList(name).size() <= 9) {
                    presets.put(name.toLowerCase(), new ArrayList<>(config.getStringList(name)));
                }
            }
        }
        externalPresets = presets;
    }

    public static boolean externalPresetExists(final String name) {
        for (final String preset : externalPresets.keySet()) {
            if (name.equalsIgnoreCase(preset)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the contents of a Preset for the specified Player.
     *
     * @param player The Player who's Preset should be gotten
     * @param name   The name of the Preset who's contents should be gotten
     * @return HashMap of ability names keyed to hotbar slots, if the Preset
     * exists, or null otherwise
     */
    public static HashMap<Integer, String> getPresetContents(final Player player, final String name) {
        if (!presets.containsKey(player.getUniqueId())) {
            return null;
        }
        for (final Preset preset : presets.get(player.getUniqueId())) {
            if (preset.name.equalsIgnoreCase(name)) {
                return preset.abilities;
            }
        }
        return null;
    }

    public static boolean bindExternalPreset(final Player player, final String name) {
        boolean boundAll = true;
        int slot = 0;
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (bPlayer == null) {
            return false;
        }

        final HashMap<Integer, String> abilities = new HashMap<Integer, String>();

        if (externalPresetExists(name.toLowerCase())) {
            for (final String ability : externalPresets.get(name.toLowerCase())) {
                slot++;
                final CoreAbility coreAbil = CoreAbility.getAbility(ability);
                if (coreAbil != null) {
                    abilities.put(slot, coreAbil.getName());
                }
            }

            for (int i = 1; i <= 9; i++) {
                final CoreAbility coreAbil = CoreAbility.getAbility(abilities.get(i));
                if (coreAbil != null && (!bPlayer.canBind(coreAbil) || !player.hasPermission("bending.element." + GeneralMethods.getParentElement(coreAbil.getElement()).getName()))) {
                    abilities.remove(i);
                    boundAll = false;
                }
            }
            bPlayer.setAbilities(abilities);
            BendingBoardManager.updateAllSlots(player);
            return boundAll;
        }
        return false;
    }

    /**
     * Deletes the Preset from the database.
     */
    public CompletableFuture<Boolean> delete() {
        Preset instance = this;
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    DBConnection.getAdapter().presets().delete(uuid, name);
                    presets.get(uuid).remove(instance);
                    future.complete(true);
                } catch (final StorageException e) {
                    e.printStackTrace();
                    future.complete(false);
                }
            }
        }.runTaskAsynchronously(ProjectKorra.plugin);
        return future;
    }

    /**
     * Gets the name of the preset.
     *
     * @return The name of the preset
     */
    public String getName() {
        return this.name;
    }

    /**
     * Saves the Preset to the database async
     */
    public CompletableFuture<Boolean> save(final Player player) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    final Map<Integer, String> slots = new HashMap<>();
                    for (int i = 1; i <= 9; i++) {
                        slots.put(i, abilities.get(i));
                    }
                    DBConnection.getAdapter().presets().save(new PresetRecord(uuid, name, slots));
                    future.complete(true);
                } catch (final StorageException e) {
                    e.printStackTrace();
                    future.complete(false);
                }
            }
        }.runTaskAsynchronously(ProjectKorra.plugin);
        return future;
    }

    public HashMap<Integer, String> getAbilities() {
        return abilities;
    }

    public UUID getUUID() {
        return uuid;
    }
}
