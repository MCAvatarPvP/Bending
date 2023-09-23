package com.projectkorra.projectkorra.object;

import com.projectkorra.projectkorra.configuration.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.permissions.Permission;

import java.util.ArrayList;
import java.util.List;

public class CosmeticColor {

	private static List<CosmeticColor> fireCosmeticColors = new ArrayList<>();
	private static List<CosmeticColor> airCosmeticColors = new ArrayList<>();

	private String name;
	private Particle.DustOptions color;

	public CosmeticColor(String name, Particle.DustOptions color) {
		this.name = name;
		this.color = color;
	}

	public void addFireColor() {
		fireCosmeticColors.add(this);
	}

	public void addAirColor() {
		airCosmeticColors.add(this);
	}

	public void remove() {
		fireCosmeticColors.remove(this);
	}

	public static boolean hasFireColor(String name) {
		for (CosmeticColor color : fireCosmeticColors) {
			if (name.equalsIgnoreCase(color.getName())) return true;
		}
		return false;
	}

	public static boolean hasAirColor(String name) {
		for (CosmeticColor color : airCosmeticColors) {
			if (name.equalsIgnoreCase(color.getName())) return true;
		}
		return false;
	}

	public static CosmeticColor getFireColor(String name) {
		for (CosmeticColor color : fireCosmeticColors) {
			if (name.equalsIgnoreCase(color.getName())) return color;
		}
		return null;
	}

	public static CosmeticColor getAirColor(String name) {
		for (CosmeticColor color : airCosmeticColors) {
			if (name.equalsIgnoreCase(color.getName())) return color;
		}
		return null;
	}

	public static void removeFireColor(String name) {
		for (CosmeticColor color : fireCosmeticColors) {
			if (color.getName().equalsIgnoreCase(name)) color.remove();
		}
	}

	public static void removeAirColor(String name) {
		for (CosmeticColor color : airCosmeticColors) {
			if (color.getName().equalsIgnoreCase(name)) color.remove();
		}
	}

	public static ArrayList<String> getFireNames() {
		ArrayList<String> names = new ArrayList<>();
		fireCosmeticColors.forEach(color -> names.add(color.getName()));

		return names;
	}

	public static ArrayList<String> getAirNames() {
		ArrayList<String> names = new ArrayList<>();
		airCosmeticColors.forEach(color -> names.add(color.getName()));

		return names;
	}

	public String getName() {
		return name;
	}

	public Particle.DustOptions getColor() {
		return color;
	}

	public static List<CosmeticColor> getFireColors() {
		return fireCosmeticColors;
	}

	public static List<CosmeticColor> getAirColors() {
		return airCosmeticColors;
	}

	public void setName(String name) {
		this.name = name;
	}

	public void setColor(Particle.DustOptions color) {
		this.color = color;
	}

	public static void reloadColors() {
		fireCosmeticColors.clear();
		airCosmeticColors.clear();
		loadColors();
	}

	public static void loadColors() {
		loadFireColors();
		loadAirColors();
	}

	private static void loadFireColors() {
		for (String s : ConfigManager.fireColorsConfig.get().getStringList("FireColors")) {
			String[] arg = s.split(", ");

			if (arg.length != 3) return;
			if (!arg[2].matches("-?\\d+(\\.\\d+)?")) return;

			String name = arg[0];
			String hex = arg[1].replace("#", "");
			float size = Float.parseFloat(arg[2]);

			if (hex.length() != 6) return;

			java.awt.Color clr = java.awt.Color.decode("#" + hex);
			new CosmeticColor(name, new Particle.DustOptions(org.bukkit.Color.fromRGB(clr.getRed(), clr.getGreen(), clr.getBlue()), size)).addFireColor();

			Permission perm = Bukkit.getPluginManager().getPermission("bending.firecolor." + name);
			if (perm == null) {
				perm = new Permission("bending.firecolor." + name);
				perm.addParent(Bukkit.getPluginManager().getPermission("bending.firecolor"), true);
				Bukkit.getPluginManager().addPermission(perm);
			}
		}
		new CosmeticColor("none", new Particle.DustOptions(org.bukkit.Color.fromRGB(0, 0, 0), 0)).addFireColor();
	}

	private static void loadAirColors() {
		for (String s : ConfigManager.airColorsConfig.get().getStringList("AirColors")) {
			String[] arg = s.split(", ");

			if (arg.length != 2) return;

			String name = arg[0];
			String hex = arg[1].replace("#", "");

			if (hex.length() != 6) return;

			java.awt.Color clr = java.awt.Color.decode("#" + hex);
			new CosmeticColor(name, new Particle.DustOptions(org.bukkit.Color.fromRGB(clr.getRed(), clr.getGreen(), clr.getBlue()), 0)).addAirColor();

			Permission perm = Bukkit.getPluginManager().getPermission("bending.aircolor." + name);
			if (perm == null) {
				perm = new Permission("bending.aircolor." + name);
				perm.addParent(Bukkit.getPluginManager().getPermission("bending.aircolor"), true);
				Bukkit.getPluginManager().addPermission(perm);
			}
		}
		new CosmeticColor("none", new Particle.DustOptions(org.bukkit.Color.fromRGB(0, 0, 0), 0)).addAirColor();
	}
}
