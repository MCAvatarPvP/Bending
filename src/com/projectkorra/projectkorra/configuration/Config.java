package com.projectkorra.projectkorra.configuration;

import java.io.File;
import java.util.List;
import java.util.Map;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.CoreAbility;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import com.projectkorra.projectkorra.ProjectKorra;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A config utility class for Project Korra. To get the config itself use
 * {@link #get()}.
 */
public class Config extends YamlConfiguration {

	private final ProjectKorra plugin;
	private final File file;

	private BendingPlayer player;

	/**
	 * Creates a new {@link Config} with the file being the configuration file.
	 *
	 * @param file The file to create/load
	 */
	public Config(final File file) {
		this.plugin = ProjectKorra.plugin;
		this.file = new File(this.plugin.getDataFolder() + File.separator + file);
		loadConfiguration(this.file);
		this.reload();
	}

	/**
	 * Creates a file for the {@link FileConfiguration} object. If there are
	 * missing folders, this method will try to create them before create a file
	 * for the config.
	 */
	public void create() {
		if (!this.file.getParentFile().exists()) {
			try {
				this.file.getParentFile().mkdir();
				this.plugin.getLogger().info("Generating new directory for " + this.file.getName() + "!");
			} catch (final Exception e) {
				this.plugin.getLogger().info("Failed to generate directory!");
				e.printStackTrace();
			}
		}

		if (!this.file.exists()) {
			try {
				this.file.createNewFile();
				this.plugin.getLogger().info("Generating new " + this.file.getName() + "!");
			} catch (final Exception e) {
				this.plugin.getLogger().info("Failed to generate " + this.file.getName() + "!");
				e.printStackTrace();
			}
		}
	}

	/**
	 * Gets the {@link FileConfiguration} object from the {@link Config}.
	 *
	 * @return the file configuration object
	 */
	public FileConfiguration get() {
		return this;
	}

	public FileConfiguration get(BendingPlayer player) {
		this.player = player;
		return this;
	}

	/**
	 * Reloads the {@link FileConfiguration} object. If the config object does
	 * not exist it will run {@link #create()} first before loading the config.
	 */
	public void reload() {
		this.create();
		try {
			this.load(this.file);
		} catch (final Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Saves the {@link FileConfiguration} object.
	 * {@code config.options().copyDefaults(true)} is called before saving the
	 * config.
	 */
	public void save() {
		try {
			this.options().copyDefaults(true);
			this.save(this.file);
		} catch (final Exception e) {
			e.printStackTrace();
		}
	}

	private Config getConfig(String path) {
		if (player == null) return null;
		if (player.getStyle() == null) {
			this.player = null;
			return null;
		}

		Config c = player.getStyle().getConfig();
		if (c.contains(path)) {
			this.player = null;
			return c;
		}
		this.player = null;
		return null;
	}

	@Nullable
	@Override
	public Object get(@NotNull String path) {
		Config c = getConfig(path);
		return c != null ? c.getSuper(path) : super.get(path);
	}

	@Nullable
	@Override
	public Object get(@NotNull String path, @Nullable Object def) {
		Config c = getConfig(path);
		return c != null ? c.getSuper(path, def) : super.get(path, def);
	}

	@Nullable
	public Object getSuper(@NotNull String path) {
		return super.get(path);
	}

	@Nullable
	public Object getSuper(@NotNull String path, @Nullable Object def) {
		return super.get(path, def);
	}

	@Nullable
	@Override
	public String getString(@NotNull String path) {
		Config c = getConfig(path);
		return c != null ? c.getStringSuper(path) : super.getString(path);
	}

	@Nullable
	@Override
	public String getString(@NotNull String path, @Nullable String def) {
		Config c = getConfig(path);
		return c != null ? c.getStringSuper(path, def) : super.getString(path, def);
	}

	@Nullable
	public String getStringSuper(@NotNull String path) {
		return super.getString(path);
	}

	@Nullable
	public String getStringSuper(@NotNull String path, @Nullable String def) {
		return super.getString(path, def);
	}

	@Nullable
	@Override
	public ItemStack getItemStack(@NotNull String path) {
		Config c = getConfig(path);
		return c != null ? c.getItemStackSuper(path) : super.getItemStack(path);
	}

	@Nullable
	@Override
	public ItemStack getItemStack(@NotNull String path, @Nullable ItemStack def) {
		Config c = getConfig(path);
		return c != null ? c.getItemStackSuper(path, def) : super.getItemStack(path, def);
	}

	@Nullable
	public ItemStack getItemStackSuper(@NotNull String path) {
		return super.getItemStack(path);
	}

	@Nullable
	public ItemStack getItemStackSuper(@NotNull String path, @Nullable ItemStack def) {
		return super.getItemStack(path, def);
	}

	@Override
	public long getLong(@NotNull String path) {
		Config c = getConfig(path);
		return c != null ? c.getLongSuper(path) : super.getLong(path);
	}

	@Override
	public long getLong(@NotNull String path, long def) {
		Config c = getConfig(path);
		return c != null ? c.getLongSuper(path, def) : super.getLong(path, def);
	}

	public long getLongSuper(@NotNull String path) {
		return super.getLong(path);
	}

	public long getLongSuper(@NotNull String path, long def) {
		return super.getLong(path, def);
	}

	@Nullable
	@Override
	public List<?> getList(@NotNull String path) {
		Config c = getConfig(path);
		return c != null ? c.getListSuper(path) : super.getList(path);
	}

	@Nullable
	@Override
	public List<?> getList(@NotNull String path, @Nullable List<?> def) {
		Config c = getConfig(path);
		return c != null ? c.getListSuper(path, def) : super.getList(path, def);
	}

	@Nullable
	public List<?> getListSuper(@NotNull String path) {
		return super.getList(path);
	}

	@Nullable
	public List<?> getListSuper(@NotNull String path, @Nullable List<?> def) {
		return super.getList(path, def);
	}

	@Override
	public double getDouble(@NotNull String path) {
		Config c = getConfig(path);
		return c != null ? c.getDoubleSuper(path) : super.getDouble(path);
	}

	@Override
	public double getDouble(@NotNull String path, double def) {
		Config c = getConfig(path);
		return c != null ? c.getDoubleSuper(path, def) : super.getDouble(path, def);
	}

	public double getDoubleSuper(@NotNull String path) {
		return super.getDouble(path);
	}

	public double getDoubleSuper(@NotNull String path, double def) {
		return super.getDouble(path, def);
	}

	@Override
	public boolean getBoolean(@NotNull String path) {
		Config c = getConfig(path);
		return c != null ? c.getBooleanSuper(path) : super.getBoolean(path);
	}

	@Override
	public boolean getBoolean(@NotNull String path, boolean def) {
		Config c = getConfig(path);
		return c != null ? c.getBooleanSuper(path, def) : super.getBoolean(path, def);
	}

	public boolean getBooleanSuper(@NotNull String path) {
		return super.getBoolean(path);
	}

	public boolean getBooleanSuper(@NotNull String path, boolean def) {
		return super.getBoolean(path, def);
	}

	@Nullable
	@Override
	public Color getColor(@NotNull String path) {
		Config c = getConfig(path);
		return c != null ? c.getColorSuper(path) : super.getColor(path);
	}

	@Nullable
	@Override
	public Color getColor(@NotNull String path, @Nullable Color def) {
		Config c = getConfig(path);
		return c != null ? c.getColorSuper(path, def) : super.getColor(path, def);
	}

	@Nullable
	public Color getColorSuper(@NotNull String path) {
		return super.getColor(path);
	}

	@Nullable
	public Color getColorSuper(@NotNull String path, @Nullable Color def) {
		return super.getColor(path, def);
	}

	@Override
	public int getInt(@NotNull String path) {
		Config c = getConfig(path);
		return c != null ? c.getIntSuper(path) : super.getInt(path);
	}

	@Override
	public int getInt(@NotNull String path, int def) {
		Config c = getConfig(path);
		return c != null ? c.getIntSuper(path, def) : super.getInt(path, def);
	}

	public int getIntSuper(@NotNull String path) {
		return super.getInt(path);
	}

	public int getIntSuper(@NotNull String path, int def) {
		return super.getInt(path, def);
	}

	@Nullable
	@Override
	public Location getLocation(@NotNull String path) {
		Config c = getConfig(path);
		return c != null ? c.getLocationSuper(path) : super.getLocation(path);
	}

	@Nullable
	@Override
	public Location getLocation(@NotNull String path, @Nullable Location def) {
		Config c = getConfig(path);
		return c != null ? c.getLocationSuper(path, def) : super.getLocation(path, def);
	}

	@Nullable
	public Location getLocationSuper(@NotNull String path) {
		return super.getLocation(path);
	}

	@Nullable
	public Location getLocationSuper(@NotNull String path, @Nullable Location def) {
		return super.getLocation(path, def);
	}

	@Nullable
	public Vector getVector(@NotNull String path) {
		Config c = getConfig(path);
		return c != null ? c.getVectorSuper(path) : super.getVector(path);
	}

	@Nullable
	public Vector getVector(@NotNull String path, @Nullable Vector def) {
		Config c = getConfig(path);
		return c != null ? c.getVectorSuper(path, def) : super.getVector(path, def);
	}

	@Nullable
	public Vector getVectorSuper(@NotNull String path) {
		return super.getVector(path);
	}

	@Nullable
	public Vector getVectorSuper(@NotNull String path, @Nullable Vector def) {
		return super.getVector(path, def);
	}

	@NotNull
	@Override
	public List<Boolean> getBooleanList(@NotNull String path) {
		Config c = getConfig(path);
		return c != null ? c.getBooleanListSuper(path) : super.getBooleanList(path);
	}

	@NotNull
	public List<Boolean> getBooleanListSuper(@NotNull String path) {
		return super.getBooleanList(path);
	}

	@NotNull
	@Override
	public List<Byte> getByteList(@NotNull String path) {
		Config c = getConfig(path);
		return c != null ? c.getByteListSuper(path) : super.getByteList(path);
	}

	@NotNull
	public List<Byte> getByteListSuper(@NotNull String path) {
		return super.getByteList(path);
	}

	@NotNull
	@Override
	public List<Character> getCharacterList(@NotNull String path) {
		Config c = getConfig(path);
		return c != null ? c.getCharacterListSuper(path) : super.getCharacterList(path);
	}

	@NotNull
	public List<Character> getCharacterListSuper(@NotNull String path) {
		return super.getCharacterList(path);
	}

	@NotNull
	@Override
	public List<Long> getLongList(@NotNull String path) {
		Config c = getConfig(path);
		return c != null ? c.getLongListSuper(path) : super.getLongList(path);
	}

	@NotNull
	public List<Long> getLongListSuper(@NotNull String path) {
		return super.getLongList(path);
	}

	@NotNull
	@Override
	public List<Double> getDoubleList(@NotNull String path) {
		Config c = getConfig(path);
		return c != null ? c.getDoubleListSuper(path) : super.getDoubleList(path);
	}

	@NotNull
	public List<Double> getDoubleListSuper(@NotNull String path) {
		return super.getDoubleList(path);
	}

	@NotNull
	@Override
	public List<Float> getFloatList(@NotNull String path) {
		Config c = getConfig(path);
		return c != null ? c.getFloatListSuper(path) : super.getFloatList(path);
	}

	@NotNull
	public List<Float> getFloatListSuper(@NotNull String path) {
		return super.getFloatList(path);
	}

	@NotNull
	@Override
	public List<Integer> getIntegerList(@NotNull String path) {
		Config c = getConfig(path);
		return c != null ? c.getIntegerListSuper(path) : super.getIntegerList(path);
	}

	@NotNull
	public List<Integer> getIntegerListSuper(@NotNull String path) {
		return super.getIntegerList(path);
	}

	@NotNull
	@Override
	public List<Map<?, ?>> getMapList(@NotNull String path) {
		Config c = getConfig(path);
		return c != null ? c.getMapListSuper(path) : super.getMapList(path);
	}

	@NotNull
	public List<Map<?, ?>> getMapListSuper(@NotNull String path) {
		return super.getMapList(path);
	}

	@NotNull
	@Override
	public List<Short> getShortList(@NotNull String path) {
		Config c = getConfig(path);
		return c != null ? c.getShortListSuper(path) : super.getShortList(path);
	}

	@NotNull
	public List<Short> getShortListSuper(@NotNull String path) {
		return super.getShortList(path);
	}

	@NotNull
	@Override
	public List<String> getStringList(@NotNull String path) {
		Config c = getConfig(path);
		return c != null ? c.getStringListSuper(path) : super.getStringList(path);
	}

	@NotNull
	public List<String> getStringListSuper(@NotNull String path) {
		return super.getStringList(path);
	}

}
