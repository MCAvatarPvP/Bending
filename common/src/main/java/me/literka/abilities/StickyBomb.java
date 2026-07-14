package me.literka.abilities;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.SoundCategory;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.entity.ShulkerBullet;
import com.projectkorra.projectkorra.platform.mc.metadata.FixedMetadataValue;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.FallHandler;
import com.projectkorra.projectkorra.util.colliders.AABB;
import com.projectkorra.projectkorra.util.colliders.Ray;
import me.literka.ChiRework;
import me.literka.ModernChiAbility;
import me.literka.util.ParticleEffect;
import me.literka.util.Utils;

import java.util.*;

public class StickyBomb extends ModernChiAbility implements AddonAbility {

    public static Map<Player, List<Long>> cooldowns = new HashMap<>();
    public static Map<Player, Long> throwTime = new HashMap<>();

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    private long duration;
    private double damageSelf;
    private double damageSelfSticked;
    private double damageOthers;
    private double damageOthersSticked;
    private double speed;
    private double radius;
    private double explodePush;
    private boolean fallDmg;

    private Location location;
    private Location prevLocation;
    private ShulkerBullet shulkerBullet;
    private Entity targetEntity;
    private Location targetLoc;
    private ParticleEffect effect;
    private long time;
    private boolean stuck;

    public StickyBomb(Player player) {
        super(player);

        cooldowns.putIfAbsent(player, new ArrayList<>());
        int limit = ChiRework.config().getInt("Abilities.StickyBomb.Limit");
        Collection<StickyBomb> abilities = CoreAbility.getAbilities(player, StickyBomb.class);
        int bombCount = abilities.size() + cooldowns.get(player).size();
        if (bombCount >= limit) return;

        if (throwTime.containsKey(player) && System.currentTimeMillis() < throwTime.get(player)) return;
        long throwDelay = ChiRework.config().getLong("Abilities.StickyBomb.ThrowDelay");
        throwTime.put(player, System.currentTimeMillis() + throwDelay);

        cooldown = ChiRework.config().getLong("Abilities.StickyBomb.Cooldown");
        duration = ChiRework.config().getLong("Abilities.StickyBomb.Duration");
        damageSelf = ChiRework.config().getLong("Abilities.StickyBomb.Damage.Self");
        damageOthers = ChiRework.config().getLong("Abilities.StickyBomb.Damage.Others");
        damageSelfSticked = ChiRework.config().getLong("Abilities.StickyBomb.Damage.Sticked.Self");
        damageOthersSticked = ChiRework.config().getLong("Abilities.StickyBomb.Damage.Sticked.Others");
        speed = ChiRework.config().getDouble("Abilities.StickyBomb.Speed");
        radius = ChiRework.config().getDouble("Abilities.StickyBomb.Radius");
        explodePush = ChiRework.config().getDouble("Abilities.StickyBomb.ExplosionPush");
        fallDmg = ChiRework.config().getBoolean("Abilities.StickyBomb.FallDamage");

        location = player.getEyeLocation();
        Vector direction = location.getDirection().multiply(speed);

        shulkerBullet = location.getWorld().spawn(location, ShulkerBullet.class, sb -> {
            sb.setGravity(true);
            sb.setSilent(true);
            sb.setInvulnerable(true);
            sb.setPersistent(true);
            sb.setShooter(player);
            sb.setMetadata("stickybomb", new FixedMetadataValue(ChiRework.plugin, this));
            GeneralMethods.setVelocity(this, sb, direction);
        });

        effect = new ParticleEffect()
                .type(Particle.ELECTRIC_SPARK)
                .count(5)
                .offset(0.15625, 0.15625, 0.15625)
                .speed(0);

        start();
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }

        if (stuck) {
            location = targetEntity != null ? targetEntity.getLocation().add(targetLoc) : targetLoc.clone();
            if (duration != 0 && System.currentTimeMillis() >= time + duration) {
                explode();
                return;
            }

            effect.location(location).spawn();
            return;
        }

        prevLocation = location.clone();
        location = shulkerBullet.getLocation();
        if (shulkerBullet.isDead()) {
            remove();
        }
    }

    public boolean stickEntity(LivingEntity e, Location hitLocation) {
        if (!stuck && player.getEntityId() != e.getEntityId() && !e.hasMetadata("stickybomb")) {
            shulkerBullet.remove();
            targetEntity = e;
            targetLoc = hitLocation.clone().subtract(e.getLocation());
            stuck = true;

            Vector dir = hitLocation.toVector().subtract(prevLocation.toVector()).normalize();
            if (Utils.isFinite(dir)) {
                Ray ray = new Ray(prevLocation.subtract(dir), dir, dir.length() * 3);
                Location hit = ray.hitLocation(new AABB(e.getWorld(), e.getBoundingBox()).expand(0.3125));
                if (hit != null) targetLoc = hit.subtract(e.getLocation());
            }

            location.getWorld().playSound(location, Sound.BLOCK_HONEY_BLOCK_STEP, SoundCategory.MASTER, 0.5f, 1.7f);
            time = System.currentTimeMillis();
            location = e.getLocation().add(targetLoc);
            return true;
        }
        return false;
    }

    public boolean stickBlock(Block block, Location hitLocation) {
        if (stuck) return false;
        targetLoc = hitLocation.clone();
        stuck = true;

        Vector dir = hitLocation.toVector().subtract(prevLocation.toVector()).normalize();
        if (Utils.isFinite(dir)) {
            Ray ray = new Ray(prevLocation.subtract(dir), dir, dir.length() * 3);
            Location hit = ray.hitLocation(new AABB(block.getWorld(), block.getBoundingBox()).expand(0.3125));
            if (hit != null) targetLoc = hit;
        }

        shulkerBullet.teleport(targetLoc);
        location = targetLoc.clone();
        location.getWorld().playSound(location, Sound.BLOCK_HONEY_BLOCK_STEP, SoundCategory.MASTER, 0.5f, 1.7f);
        shulkerBullet.remove();
        time = System.currentTimeMillis();
        return true;
    }

    public void explode() {
        for (Entity e : GeneralMethods.getEntitiesAroundPoint(location, radius)) {
            if (e instanceof LivingEntity le) {
                double dmg;
                if (targetEntity == null) {
                    dmg = player.getUniqueId() == le.getUniqueId() ? damageSelf : damageOthers;
                } else {
                    dmg = player.getUniqueId() == le.getUniqueId() ? damageSelfSticked : damageOthersSticked;
                }

                Utils.damage(le, dmg, this);
                Vector vel = le.getEyeLocation().toVector().subtract(location.toVector()).normalize().multiply(explodePush);
                if (vel.lengthSquared() != 0) GeneralMethods.setVelocity(this, le, vel);
                if (le instanceof Player p && !fallDmg) FallHandler.stopFall(p);
            }
        }

        shulkerBullet.remove();
        location.getWorld().playSound(this.location, Sound.ENTITY_GENERIC_EXPLODE, 1, 1.5f);
        new ParticleEffect().type(Particle.EXPLOSION_EMITTER).location(location).count(1).speed(0).spawn();
        List<Long> list = cooldowns.get(player);
        if (list != null) list.add(System.currentTimeMillis() + getCooldown());
        remove();
    }

    @Override
    public String getMovePreview(Player player) {
        int limit = ChiRework.config().getInt("Abilities.StickyBomb.Limit");
        int bombs = cooldowns.getOrDefault(player, Collections.emptyList()).size() + CoreAbility.getAbilities(player, StickyBomb.class).size();

        int availableBombs = limit - bombs;
        if (availableBombs == limit) return super.getMovePreview(player);

        return getElement().getColor().toString() + availableBombs + "/" + limit + " - " + super.getMovePreview(player);
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
        return ChiRework.config().getBoolean("Abilities.StickyBomb.Enabled");
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public String getName() {
        return "StickyBomb";
    }

    @Override
    public String getDescription() {
        return ChiRework.config().getString("Language.StickyBomb.Description");
    }

    @Override
    public String getInstructions() {
        return ChiRework.config().getString("Language.StickyBomb.Instructions");
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
        return ChiRework.authors;
    }

    @Override
    public String getVersion() {
        return ChiRework.name + " " + ChiRework.version;
    }
}
