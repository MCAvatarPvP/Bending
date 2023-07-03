package com.projectkorra.projectkorra.earthbending;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.util.ParticleEffect;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class OldEarthGrab extends EarthAbility {

	private enum Type {
		SELF, OTHERS
	}

	private Type type;

	private Location center;
	@Attribute(Attribute.RADIUS)
	private double radius;
	@Attribute(Attribute.HEIGHT)
	private int height;
	@Attribute(Attribute.COOLDOWN)
	private long cooldown;
	private Set<Block> checked;

	private Vector direction;
	private double range;
	@Attribute(Attribute.RANGE)
	private double maxRange;
	private Location loc;

	public OldEarthGrab(Player player, Type type) {
		super(player);

		this.type = type;
		this.center = player.getLocation().clone().subtract(0, 1, 0);
		this.radius = getConfig().getDouble("Abilities.Earth.EarthDome.Radius");
		this.height = getConfig().getInt("Abilities.Earth.EarthDome.Height");
		this.cooldown = getConfig().getLong("Abilities.Earth.EarthDome.Cooldown");
		this.checked = new HashSet<>();

		if (type == Type.OTHERS) {
			this.loc = player.getLocation().clone();

			if (GeneralMethods.isRegionProtectedFromBuild(player, this.loc)) {
				return;
			}
			if (!isEarthbendable(this.loc.getBlock().getRelative(BlockFace.DOWN).getType(), true, true, true)) {
				return;
			}
			this.range = 0;
			this.direction = this.loc.getDirection().setY(0);
			this.maxRange = getConfig().getDouble("Abilities.Earth.EarthDome.Range");
		}

		this.start();
	}

	@Override
	public void progress() {
		if (type == Type.SELF) {
			run();
		} else {
			if (!this.player.isOnline() || this.player.isDead()) {
				this.remove(true);
				return;
			}
			if (this.range >= this.maxRange) {
				this.remove(true);
				return;
			}
			if (GeneralMethods.isRegionProtectedFromBuild(this.player, this.loc)) {
				this.remove(true);
				return;
			}

			this.range++;
			this.loc.add(this.direction.normalize());
			Block top = GeneralMethods.getTopBlock(this.loc, 2);

			while (!this.isEarthbendable(top)) {
				if (this.isTransparent(top)) {
					top = top.getRelative(BlockFace.DOWN);
				} else {
					this.remove(true);
					return;
				}
			}

			if (!this.isTransparent(top.getRelative(BlockFace.UP))) {
				this.remove(true);
				return;
			}

			this.loc.setY(top.getY() + 1);

			ParticleEffect.CRIT.display(this.loc, 9, 0.4, 0, 0.4, 0.001);
			ParticleEffect.BLOCK_DUST.display(this.loc, 7, 0.2, 0.1, 0.2, 0.001, this.loc.getBlock().getRelative(BlockFace.DOWN).getBlockData());

			for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(this.loc, 2)) {
				if (!(entity instanceof LivingEntity) || entity.getEntityId() == this.player.getEntityId()) {
					continue;
				}

				run();
				this.remove(false);
				return;
			}
		}
	}

	private void run() {
		for (int i = 0; i < 2; i++) {
			for (final Location check : this.getCircle(this.center, this.radius + i, 10)) {
				Block currBlock = check.getBlock();
				if (this.checked.contains(currBlock)) {
					continue;
				}

				currBlock = this.getAppropriateBlock(currBlock);
				if (currBlock == null) {
					continue;
				}

				new RaiseEarth(this.player, currBlock.getLocation(), Math.round(this.height - i));
				this.checked.add(currBlock);
			}

		}

		this.bPlayer.addCooldown("OldEarthGrab", this.getCooldown());
		this.remove();
	}

	public void remove(final boolean cooldown) {
		super.remove();
		if (cooldown) {
			this.bPlayer.addCooldown("EarthDome", getConfig().getLong("Abilities.Earth.EarthDome.Cooldown"));
		}
	}

	private Block getAppropriateBlock(final Block block) {
		if (!GeneralMethods.isSolid(block.getRelative(BlockFace.UP)) && GeneralMethods.isSolid(block)) {
			return block;
		}
		final Block top = GeneralMethods.getTopBlock(block.getLocation(), 2);
		if (GeneralMethods.isSolid(top.getRelative(BlockFace.UP))) {
			return null;
		}
		return top;
	}

	private List<Location> getCircle(final Location center, final double radius, double interval) {
		final List<Location> result = new ArrayList<>();
		interval = Math.toRadians(Math.abs(interval));
		for (double theta = 0; theta < 2 * Math.PI; theta += interval) {
			final double x = Math.cos(theta) * (radius + (Math.random() / 3.1));
			final double z = Math.sin(theta) * (radius + (Math.random() / 3.1));
			result.add(center.clone().add(x, 0, z));
		}
		return result;
	}

	@Override
	public boolean isSneakAbility() {
		return false;
	}

	@Override
	public boolean isHarmlessAbility() {
		return false;
	}

	@Override
	public long getCooldown() {
		return this.cooldown;
	}

	@Override
	public String getName() {
		return "OldEarthGrab";
	}

	@Override
	public Location getLocation() {
		return this.center;
	}

}