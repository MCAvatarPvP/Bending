package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.HashSet;
import java.util.Set;

public class MovementHandler {

	public static Set<MovementHandler> handlers = new HashSet<>();

	private final LivingEntity entity;
	private ResetTask reset = null;
	private Location location;
	private final CoreAbility ability;
	private long duration;
	private String message;
	private final long startTime;

	public MovementHandler(LivingEntity entity, CoreAbility ability) {
		this.entity = entity;
		this.location = entity.getLocation();
		this.ability = ability;
		this.startTime = System.currentTimeMillis();
	}

	public void stopWithDuration(long duration, String message) {
		this.duration = duration;
		this.message = message;
		handlers.add(this);
		this.entity.setMetadata("movement:stop", new FixedMetadataValue(ProjectKorra.plugin, ability));
	}

	public void stop(String message) {
		this.duration = -1;
		this.message = message;
		handlers.add(this);
		this.entity.setMetadata("movement:stop", new FixedMetadataValue(ProjectKorra.plugin, ability));
	}

	private void tick() {
		if (duration != -1 && duration >= 0 && System.currentTimeMillis() > startTime + duration) {
			reset();
			return;
		}
		if (entity instanceof Player) {
			Player player = (Player) entity;
			Location loc = player.getLocation();
			if (loc.getX() == location.getX() && loc.getY() == location.getY() && loc.getZ() == location.getZ()) {
				return;
			}

			if (loc.getY() < location.getY()) location.setY(loc.getY());

			ActionBar.sendActionBar(message, player);
			if (loc.getX() != location.getX() || loc.getZ() != location.getZ() || loc.getY() > location.getY()) {
				player.teleport(location);
			}
		} else {
			if (!entity.isOnGround()) return;
			entity.setAI(false);
		}
	}

	public static void tickAll() {
		for (int i = 0; i < handlers.size(); i++) {
			((MovementHandler) handlers.toArray()[i]).tick();
		}
	}

	public void reset() {
		handlers.remove(this);
		if (!(this.entity instanceof Player)) {
			this.entity.setAI(true);
		}
		if (this.reset != null) {
			this.reset.run();
		}
		if (this.entity.hasMetadata("movement:stop")) {
			this.entity.removeMetadata("movement:stop", ProjectKorra.plugin);
		}
	}

	public CoreAbility getAbility() {
		return this.ability;
	}

	public LivingEntity getEntity() {
		return this.entity;
	}

	public void setResetTask(ResetTask reset) {
		this.reset = reset;
	}

	public interface ResetTask {
		void run();
	}

	public static boolean isStopped(Entity entity) {
		return entity.hasMetadata("movement:stop");
	}

	public static void resetAll() {
		for (MovementHandler handler : handlers) {
			handler.reset();
		}
	}

	public static MovementHandler getFromEntityAndAbility(Entity entity, CoreAbility ability) {
		for (final MovementHandler handler : handlers) {
			if (handler.getEntity().getEntityId() == entity.getEntityId() && handler.getAbility().equals(ability)) {
				return handler;
			}
		}

		return null;
	}

}