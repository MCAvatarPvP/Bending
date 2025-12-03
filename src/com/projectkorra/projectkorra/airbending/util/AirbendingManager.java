package com.projectkorra.projectkorra.airbending.util;

import com.projectkorra.projectkorra.ProjectKorra;

//Not used after removing the static ORIGINS map from AirBlast
public class AirbendingManager implements Runnable {

	public ProjectKorra plugin;

	public AirbendingManager(final ProjectKorra plugin) {
		this.plugin = plugin;
	}

	@Override
	public void run() {}

}
