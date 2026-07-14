package me.simplicitee.project.addons.ability.earth;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.event.Listener;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.ParticleEffect;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.TempFallingBlock;
import me.simplicitee.project.addons.ProjectAddons;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

public class EarthKick extends EarthAbility implements AddonAbility, Listener {

    @Attribute(Attribute.DAMAGE)
    public double damage;
    @Attribute("Blocks")
    public int maxBlocks;
    @Attribute("LavaMultiplier")
    public double lavaMult;
    @Attribute(Attribute.RANGE)
    public double range;
    public boolean iframes;

    public List<TempFallingBlock> kick;
    public long duration = 2500;
    private List<LivingEntity> hitEntities;

    public EarthKick(Player player) {
        super(player);

        if (getAbility(this.getClass()) == null) {
            return;
        }

        setFields();
        if (launchKick()) {
            bPlayer.addCooldown(this);
            start();
        }
    }

    public void setFields() {
        damage = ProjectAddons.instance.getConfig(bPlayer).getDouble("Abilities.Earth.EarthKick.Damage");
        maxBlocks = ProjectAddons.instance.getConfig(bPlayer).getInt("Abilities.Earth.EarthKick.MaxBlocks");
        lavaMult = ProjectAddons.instance.getConfig(bPlayer).getDouble("Abilities.Earth.EarthKick.LavaMultiplier");
        range = ProjectAddons.instance.getConfig(bPlayer).getDouble("Abilities.Earth.EarthKick.Range");
        iframes = ProjectAddons.instance.getConfig(bPlayer).getBoolean("Abilities.Earth.EarthKick.Iframes");
        kick = new ArrayList<>();
        hitEntities = new ArrayList<>();
    }

    @Override
    public Element getElement() {
        return Element.EARTH;
    }

    @Override
    public Location getLocation() {
        return null;
    }

    @Override
    public List<Location> getLocations() {
        List<Location> locs = new ArrayList<>();
        for (TempFallingBlock fb : kick) {
            locs.add(fb.getLocation());
        }
        return locs;
    }

    @Override
    public String getName() {
        return "EarthKick";
    }

    @Override
    public boolean isExplosiveAbility() {
        return false;
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    @Override
    public boolean isIgniteAbility() {
        return false;
    }

    @Override
    public boolean isSneakAbility() {
        return true;
    }

    @Override
    public void progress() {
        Iterator<TempFallingBlock> iter = kick.iterator();
        while (iter.hasNext()) {
            TempFallingBlock fb = iter.next();

            if (fb == null || fb.getFallingBlock().isDead()) {
                iter.remove();
                continue;
            }

            ParticleEffect.BLOCK_CRACK.display(fb.getLocation(), 3, 0.1, 0.1, 0.1, fb.getData());

            for (Entity e : GeneralMethods.getEntitiesAroundPoint(fb.getLocation(), 1.5)) {
                if (e instanceof LivingEntity && e.getEntityId() != player.getEntityId()) {
                    if (hitEntities.contains((LivingEntity) e)) continue;
                    hitEntities.add((LivingEntity) e);
                    DamageHandler.damageEntity(e, player, damage, this);
                    if (!iframes) ((LivingEntity) e).setNoDamageTicks(0);
                    iter.remove();
                    fb.remove();
                    break;
                }
            }
        }

        if (kick.isEmpty()) {
            remove();
            return;
        }

        if (System.currentTimeMillis() > getStartTime() + duration) {
            remove();
            return;
        }
    }

    public boolean launchKick() {
        Block b = player.getTargetBlock(getTransparentMaterialSet(), 3);
        BlockData data = b.getBlockData();

        if (TempBlock.isTempBlock(b) && !EarthAbility.isBendableEarthTempBlock(b)) {
            return false;
        }

        if (!EarthAbility.isEarthbendable(data.getMaterial(), bPlayer.canMetalbend(), bPlayer.canSandbend(), bPlayer.canLavabend())) {
            return false;
        }

        if (data.getMaterial() == Material.LAVA) {
            if (bPlayer.canLavabend()) {
                data = Material.MAGMA_BLOCK.createBlockData();
                damage *= lavaMult;
            } else {
                return false;
            }
        }

        for (int i = 0; i < maxBlocks; i++) {
            Location loc = player.getLocation().clone();
            loc.setPitch(0);
            loc.setYaw(loc.getYaw() + new Random().nextInt(25) - 12);
            Vector vec = loc.getDirection();
            vec.setY(Math.max(0.3, Math.random() / 2));
            vec.setX(vec.getX() / 1.2);
            vec.setZ(vec.getZ() / 1.2);
            vec.multiply(range);
            Location location = b.getLocation().clone().add(0.5, 1.2, 0.5);
            TempFallingBlock fb = new TempFallingBlock(location, data, vec, this);
            kick.add(fb);
        }

        playEarthbendingSound(player.getLocation());

        return true;
    }

    @Override
    public void remove() {
        super.remove();
        for (TempFallingBlock fb : kick) {
            fb.remove();
        }
    }

    @Override
    public long getCooldown() {
        return ProjectAddons.instance.getConfig(bPlayer).getLong("Abilities.Earth.EarthKick.Cooldown");
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
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public String getDescription() {
        return "Earthbenders can kick the earth in front of them and send shards flying towards their enemies.";
    }

    @Override
    public String getInstructions() {
        return "Sneak at earth in front of you";
    }

    @Override
    public boolean isEnabled() {
        return ProjectAddons.instance.getConfig(bPlayer).getBoolean("Abilities.Earth.EarthKick.Enabled");
    }
}
