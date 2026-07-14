package me.simplicitee.project.addons.ability.air;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Instrument;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Note;
import com.projectkorra.projectkorra.platform.mc.Note.Tone;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import me.simplicitee.project.addons.ProjectAddons;
import me.simplicitee.project.addons.util.SoundAbility;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class SonicWave extends SoundAbility implements AddonAbility {

    @Attribute(Attribute.WIDTH)
    private double width;
    @Attribute(Attribute.DURATION)
    private int duration;
    @Attribute("Nausea")
    private int amp;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.RANGE)
    private double maxRange;

    private Map<Vector, Location> parts;
    private double range;

    public SonicWave(Player player) {
        super(player);

        this.width = ProjectAddons.instance.getConfig(bPlayer).getDouble("Abilities.Air.SonicWave.Width");
        this.duration = ProjectAddons.instance.getConfig(bPlayer).getInt("Abilities.Air.SonicWave.Nausea.Duration");
        this.amp = ProjectAddons.instance.getConfig(bPlayer).getInt("Abilities.Air.SonicWave.Nausea.Power") + 1;
        this.cooldown = ProjectAddons.instance.getConfig(bPlayer).getLong("Abilities.Air.SonicWave.Cooldown");
        this.maxRange = ProjectAddons.instance.getConfig(bPlayer).getDouble("Abilities.Air.SonicWave.Range");
        this.range = 0;
        this.parts = new HashMap<>();

        launch();
        start();
        bPlayer.addCooldown(this);
    }

    @Override
    public void progress() {
        for (int j = 0; j < 10; j++) {
            if (!player.isOnline() || player.isDead()) {
                remove();
                return;
            }

            Iterator<Vector> iter = parts.keySet().iterator();
            while (iter.hasNext()) {
                Vector v = iter.next();
                Location loc = parts.get(v);
                loc.add(v);

                if (!loc.getBlock().isPassable()) {
                    iter.remove();
                    continue;
                }

                for (Player p : player.getWorld().getPlayers()) {
                    BendingPlayer bp = BendingPlayer.getBendingPlayer(p);
                    if (bp != null && bp.hasElement(ProjectAddons.instance.getSoundElement())) {
                        p.spawnParticle(Particle.WHITE_SMOKE, loc, 1, 0, 0, 0);
                    }
                    p.playNote(loc, Instrument.FLUTE, Note.sharp(2, Tone.F));
                }

                for (Entity e : GeneralMethods.getEntitiesAroundPoint(loc, 0.8)) {
                    if (e instanceof LivingEntity && e.getEntityId() != player.getEntityId()) {
                        ((LivingEntity) e).addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, duration, amp));
                    }
                }
            }

            if (parts.isEmpty()) {
                remove();
                return;
            }

            range += 0.2;

            if (range > maxRange) {
                remove();
                return;
            }
        }
    }

    private void launch() {
        Location origin = GeneralMethods.getMainHandLocation(player);
        for (double i = -this.width; i <= this.width; i += 2) {
            final double angle = Math.toRadians(i);
            final Vector direction = this.player.getEyeLocation().getDirection().clone();

            double x, z, vx, vz;
            x = direction.getX();
            z = direction.getZ();

            vx = x * Math.cos(angle) - z * Math.sin(angle);
            vz = x * Math.sin(angle) + z * Math.cos(angle);

            direction.setX(vx);
            direction.setZ(vz);

            this.parts.put(direction.normalize().multiply(0.2), origin.clone());
        }
    }

    @Override
    public void remove() {
        super.remove();
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
        return cooldown;
    }

    @Override
    public String getName() {
        return "SonicWave";
    }

    @Override
    public Location getLocation() {
        return null;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public String getAuthor() {
        return "Simplicitee";
    }

    @Override
    public String getVersion() {
        return ProjectAddons.instance.version();
    }

    @Override
    public String getDescription() {
        return "Create a blastwave of ultrasonic waves to cause nausea to any entities it hits! Soundbenders are able to see the sonic wave!";
    }

    @Override
    public String getInstructions() {
        return "left click";
    }

    @Override
    public boolean isEnabled() {
        return ProjectAddons.instance.getConfig(bPlayer).getBoolean("Abilities.Air.SonicWave.Enabled");
    }
}
