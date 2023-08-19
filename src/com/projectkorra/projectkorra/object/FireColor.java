package com.projectkorra.projectkorra.object;

import com.projectkorra.projectkorra.configuration.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.permissions.Permission;

import java.util.ArrayList;
import java.util.List;

public class FireColor {

	private static List<FireColor> fireColors = new ArrayList<>();

	private String name;
	private Particle.DustOptions color;

	public FireColor(String name, Particle.DustOptions color) {
		this.name = name;
		this.color = color;

		fireColors.add(this);
	}

	public void remove() {
		fireColors.remove(this);
	}

	public static boolean hasFireColor(String name) {
		for (FireColor color : fireColors) {
			if (name.equalsIgnoreCase(color.getName())) return true;
		}
		return false;
	}

	public static FireColor getFireColor(String name) {
		for (FireColor color : fireColors) {
			if (name.equalsIgnoreCase(color.getName())) return color;
		}
		return null;
	}

	public static void removeFireColor(String name) {
		for (FireColor color : fireColors) {
			if (color.getName().equalsIgnoreCase(name)) color.remove();
		}
	}

	public static ArrayList<String> getNames() {
		ArrayList<String> names = new ArrayList<>();
		fireColors.forEach(color -> names.add(color.getName()));

		return names;
	}

	public String getName() {
		return name;
	}

	public Particle.DustOptions getColor() {
		return color;
	}

	public static List<FireColor> getFireColors() {
		return fireColors;
	}

	public void setName(String name) {
		this.name = name;
	}

	public void setColor(Particle.DustOptions color) {
		this.color = color;
	}

	public static void reloadFireColors() {
		fireColors.clear();
		loadFireColors();
	}

	public static void loadFireColors() {
		for (String s : ConfigManager.fireColorsConfig.get().getStringList("FireColors")) {
			String[] arg = s.split(", ");

			if (arg.length != 3) return;
			if (!arg[2].matches("-?\\d+(\\.\\d+)?")) return;

			String name = arg[0];
			String hex = arg[1].replace("#", "");
			float size = Float.parseFloat(arg[2]);

			if (hex.length() != 6) return;

			java.awt.Color clr = java.awt.Color.decode("#" + hex);
			new FireColor(name, new Particle.DustOptions(Color.fromRGB(clr.getRed(), clr.getGreen(), clr.getBlue()), size));

			Permission perm = Bukkit.getPluginManager().getPermission("bending.firecolor." + name);
			if (perm == null) {
				perm = new Permission("bending.firecolor." + name);
				perm.addParent(Bukkit.getPluginManager().getPermission("bending.firecolor"), true);
				Bukkit.getPluginManager().addPermission(perm);
			}
		}
		new FireColor("none", new Particle.DustOptions(Color.fromRGB(0, 0, 0), 0));
	}
}
