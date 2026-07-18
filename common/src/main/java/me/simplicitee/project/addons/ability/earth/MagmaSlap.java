package me.simplicitee.project.addons.ability.earth;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.LavaAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.PredictionDeterminism;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.TempFallingBlock;
import me.simplicitee.project.addons.ProjectAddons;
import me.simplicitee.project.addons.util.SoundEffect;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class MagmaSlap extends LavaAbility implements AddonAbility {

    @Attribute("Offset")
    private double offset;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute("Length")
    private int maxLength;
    @Attribute(Attribute.WIDTH)
    private int width;
    @Attribute("RevertTime")
    private long revertTime;

    private int length;
    private long next, last;
    private Location start, curr;
    private List<TempBlock> tempBlocks;
    private Set<Block> affected;
    private SoundEffect effect;
    private final Random gameplayRandom;

    public MagmaSlap(Player player) {
        super(player);
        this.gameplayRandom = PredictionDeterminism.random(player == null ? null : player.getUniqueId(),
                getClass().getName() + ":falling-block-velocity");
        if (!bPlayer.canBend(this)) {
            return;
        }

        if (hasAbility(player, MagmaSlap.class)) {
            return;
        }

        setFields();
        start();
    }

    public static boolean isBlock(FallingBlock fb) {
        return fb.hasMetadata("magmaslap");
    }

    private void setFields() {
        this.offset = ProjectAddons.instance.getConfig(bPlayer).getDouble("Abilities.Earth.MagmaSlap.Offset");
        this.damage = ProjectAddons.instance.getConfig(bPlayer).getDouble("Abilities.Earth.MagmaSlap.Damage");
        this.length = 0;
        this.maxLength = ProjectAddons.instance.getConfig(bPlayer).getInt("Abilities.Earth.MagmaSlap.Length");
        this.width = ProjectAddons.instance.getConfig(bPlayer).getInt("Abilities.Earth.MagmaSlap.Width");
        this.next = 50;
        this.last = 0;
        this.revertTime = ProjectAddons.instance.getConfig(bPlayer).getLong("Abilities.Earth.MagmaSlap.RevertTime");
        this.start = player.getLocation().subtract(0, 1, 0);
        this.start.setPitch(0);
        this.start.add(start.getDirection().multiply(offset));
        this.curr = start.clone();
        this.tempBlocks = new ArrayList<>();
        this.affected = new HashSet<>();
        this.effect = new SoundEffect(Sound.ENTITY_CREEPER_PRIMED, 0.9f, 1f);
    }

    @Override
    public long getCooldown() {
        return ProjectAddons.instance.getConfig(bPlayer).getLong("Abilities.Earth.MagmaSlap.Cooldown");
    }

    @Override
    public Location getLocation() {
        return null;
    }

    @Override
    public String getName() {
        return "MagmaSlap";
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    @Override
    public boolean isSneakAbility() {
        return false;
    }

    @Override
    public void progress() {
        if (!player.isOnline() || player.isDead()) {
            remove();
            return;
        }

        if (length > maxLength) {
            bPlayer.addCooldown(this);
            remove();
            return;
        }

        if (System.currentTimeMillis() < last + next) {
            return;
        }

        last = System.currentTimeMillis();
        length++;

        if (length % 4 == 0) {
            width++;
        }

        for (double i = -width; i <= width; i += 0.5) {
            Location check = curr.clone();
            if (i != 0) {
                Vector dir = GeneralMethods.getOrthogonalVector(check.getDirection(), 90, i);
                check.add(dir);
            }
            checkBlock(check.getBlock());
        }

        curr.add(start.getDirection().normalize());
    }

    private void checkBlock(Block b) {
        b = GeneralMethods.getTopBlock(b.getLocation(), 2);
        if (b.isPassable() && !b.isLiquid()) {
            tempBlocks.add(new TempBlock(b, Material.AIR));
            b = b.getRelative(BlockFace.DOWN);
        }

        if (affected.contains(b) || affected.contains(b.getRelative(BlockFace.UP)) || TempBlock.isTempBlock(b) || !isEarthbendable(b.getType(), true, true, true)) {
            return;
        }

        affected.add(b);
        TempBlock magmaTarget = new TempBlock(b, Material.AIR);
        tempBlocks.add(magmaTarget);

        TempFallingBlock tfb = new TempFallingBlock(b.getLocation().add(0.5, 0.7, 0.5),
                Material.MAGMA_BLOCK.createBlockData(), new Vector(0, this.gameplayRandom.nextDouble() * 0.3, 0), this);
        tfb.setOnPlace(ignored -> turnToTempBlock(magmaTarget));
        FallingBlock fb = tfb.getFallingBlock();

        for (Entity entity : GeneralMethods.getEntitiesAroundPoint(fb.getLocation(), 2)) {
            if (entity instanceof LivingEntity && entity.getEntityId() != player.getEntityId()) {
                DamageHandler.damageEntity(entity, damage, this);
                GeneralMethods.setVelocity(this, entity, fb.getVelocity().multiply(2.5));
            }
        }

        effect.play(fb.getLocation());
    }

    public void turnToTempBlock(Block b) {
        if (TempBlock.isTempBlock(b)) {
            turnToTempBlock(TempBlock.get(b));
        }
    }

    private void turnToTempBlock(TempBlock tb) {
        if (tb != null && tempBlocks.contains(tb)) {
            tb.setType(Material.MAGMA_BLOCK);
            tb.setRevertTime(revertTime);
        }
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
        return "A simple ability in a lavabender's arsenal, this allows them to create a small wave style attack of lava that throws enemies up into the air, leaving magma in it's trail.";
    }

    @Override
    public String getInstructions() {
        return "Click on your magmaslap bind";
    }

    @Override
    public boolean isEnabled() {
        return ProjectAddons.instance.getConfig(bPlayer).getBoolean("Abilities.Earth.MagmaSlap.Enabled");
    }
}
