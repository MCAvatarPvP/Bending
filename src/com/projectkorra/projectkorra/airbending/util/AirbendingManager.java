package com.projectkorra.projectkorra.airbending.util;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.airbending.AirBlast;
import org.bukkit.entity.Player;

public class AirbendingManager implements Runnable {

	public ProjectKorra plugin;

	public AirbendingManager(final ProjectKorra plugin) {
		this.plugin = plugin;
	}

	@Override
	public void run() {
		AirBlast.progressOrigins();
		AirAbility.checkFallDamage();
	}

}
