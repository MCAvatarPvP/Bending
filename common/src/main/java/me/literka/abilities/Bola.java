package me.literka.abilities;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.configuration.PKConfigurationSection;
import com.projectkorra.projectkorra.platform.mc.Color;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import me.literka.ChiRework;
import me.literka.ModernChiAbility;
import me.literka.util.ParticleEffect;
import me.literka.util.Utils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Bola extends ModernChiAbility implements AddonAbility {

    private long cooldown;
    private double range;
    private double radius;
    private Map<String, Long> cooldowns;

    private Location origin;
    private Location location;
    private List<Vector> vectors;
    private Vector spinAxis;
    private ParticleEffect ballEffect;
    private ParticleEffect chainEffect;

    public Bola(Player player) {
        super(player);

        cooldown = ChiRework.config().getLong("Abilities.Bola.Cooldown");
        range = ChiRework.config().getDouble("Abilities.Bola.Range");
        radius = ChiRework.config().getDouble("Abilities.Bola.Radius");
        cooldowns = new HashMap<>();
        PKConfigurationSection section = ChiRework.config().getConfigurationSection("Abilities.Bola.BlockAbilities");
        for (String key : section.getKeys(false)) {
            cooldowns.put(key, ChiRework.config().getLong("Abilities.Bola.BlockAbilities." + key));
        }
        origin = player.getEyeLocation();
        location = origin.clone();
        ballEffect = new ParticleEffect().type(Particle.DUST)
                .count(7).offset(0.05, 0.05, 0.05).speed(0).data(new Particle.DustOptions(Color.fromRGB(0, 0, 0), 0.8f));
        chainEffect = new ParticleEffect().type(Particle.DUST)
                .count(1).offset(0, 0, 0).speed(0).data(new Particle.DustOptions(Color.fromRGB(71, 38, 4), 0.7f));

        Vector sideVec = Utils.getOrthogonalVector(location.getDirection(), location.getYaw(), 0, 1);
        Location l = location.clone();
        Location mid = l.clone().add(l.getDirection().multiply(radius / 2));
        Location right = mid.clone().add(sideVec);
        Location left = mid.clone().add(sideVec.multiply(-1));
        vectors = Utils.bezier(mid, l, left, right, l.clone().add(l.getDirection().multiply(radius)));
        l.setPitch(l.getPitch() + 90);
        spinAxis = l.getDirection();

        start();
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }

        if (origin.distanceSquared(location) > range * range) {
            bPlayer.addCooldown(this);
            remove();
            return;
        }

        location.add(location.getDirection());

        for (Entity e : GeneralMethods.getEntitiesAroundPoint(location, radius)) {
            if (!(e instanceof Player target)) continue;
            if (player.getUniqueId() == e.getUniqueId()) continue;

            BendingPlayer targetB = BendingPlayer.getBendingPlayer(target);
            if (targetB == null) continue;

            for (Map.Entry<String, Long> entry : cooldowns.entrySet()) {
                targetB.addCooldown(entry.getKey(), entry.getValue());
            }

            Utils.damage((LivingEntity) e, 0.01, this);

            Location loc = e.getLocation();
            loc.getWorld().playSound(loc, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1.2f);
            bPlayer.addCooldown(this);
            remove();
            return;
        }

        for (int i = 0; i < vectors.size(); i++) {
            Vector v = vectors.get(i);
            chainEffect.location(location.clone().add(v)).spawn();
            if (i == 0 || i == vectors.size() - 1) ballEffect.location(location.clone().add(v)).spawn();
            Vector temp = GeneralMethods.rotateVectorAroundVector(spinAxis, v, -30);
            v.setX(temp.getX());
            v.setY(temp.getY());
            v.setZ(temp.getZ());
        }
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
    public boolean isEnabled() {
        return ChiRework.config().getBoolean("Abilities.Bola.Enabled");
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public String getName() {
        return "Bola";
    }

    @Override
    public String getDescription() {
        return ChiRework.config().getString("Language.Bola.Description");
    }

    @Override
    public String getInstructions() {
        return ChiRework.config().getString("Language.Bola.Instructions");
    }

    @Override
    public Location getLocation() {
        return player.getLocation();
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public String getAuthor() {
        return ChiRework.authors;
    }

    @Override
    public String getVersion() {
        return ChiRework.name + " " + ChiRework.version;
    }
}
