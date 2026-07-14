package hackathonpack.air;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.ClickType;
import hackathonpack.UtilityMethods;

import java.util.*;

public class AirSpin extends AirAbility implements AddonAbility, ComboAbility {
    private long cooldown;
    private int range;
    private Map<LivingEntity, AirSpinBlast> affectedEntities;

    public AirSpin(final Player player) {
        super(player);
        if (this.bPlayer.isOnCooldown(this) || !ConfigManager.getConfig().getBoolean(path("Enable"))) return;
        setFields();
        if (!setAffectedEntities()) return;
        this.bPlayer.addCooldown(this);
        start();
    }

    private static String path(final String node) {
        return "ExtraAbilities.Hiro3.HackathonPack.Air.AirSpin." + node;
    }

    private void setFields() {
        this.cooldown = ConfigManager.getConfig().getLong(path("Cooldown"));
        this.range = ConfigManager.getConfig().getInt(path("Range"));
        this.affectedEntities = new HashMap<>();
    }

    @Override
    public void progress() {
        if (this.affectedEntities.isEmpty()) {
            remove();
            return;
        }
        for (final Iterator<Map.Entry<LivingEntity, AirSpinBlast>> it = this.affectedEntities.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<LivingEntity, AirSpinBlast> entry = it.next();
            if (!entry.getKey().isDead() && !entry.getValue().isReached()) {
                entry.getValue().update();
                entry.getValue().show();
            } else {
                it.remove();
            }
        }
    }

    private boolean setAffectedEntities() {
        for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(this.player.getLocation(), this.range)) {
            if (entity instanceof LivingEntity living && !entity.equals(this.player)) {
                final Vector dir = this.player.getLocation().getDirection();
                final Vector other = entity.getLocation().toVector().subtract(this.player.getLocation().toVector());
                final double denom = dir.length() * other.length();
                if (denom > 0 && Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dir.dot(other) / denom)))) < 60) {
                    this.affectedEntities.put(living, new AirSpinBlast(this.player, living));
                }
            }
        }
        return !this.affectedEntities.isEmpty();
    }

    @Override
    public long getCooldown() {
        return this.cooldown;
    }

    @Override
    public Location getLocation() {
        return null;
    }

    @Override
    public String getName() {
        return "AirSpin";
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
    public Object createNewComboInstance(final Player player) {
        return new AirSpin(player);
    }

    @Override
    public ArrayList<AbilityInformation> getCombination() {
        return UtilityMethods.getConfiguredCombination(path("Enable"), path("Combination"), new ArrayList<>(Arrays.asList(new AbilityInformation("AirBlast", ClickType.SHIFT_DOWN), new AbilityInformation("AirSuction", ClickType.SHIFT_UP), new AbilityInformation("AirSuction", ClickType.SHIFT_DOWN))));
    }

    @Override
    public String getDescription() {
        return "Moves the air around your enemies and makes them spin.";
    }

    @Override
    public String getInstructions() {
        return "AirBlast (Hold Sneak) -> AirSuction (Release Sneak) -> AirSuction (Tap Sneak)";
    }

    @Override
    public String getAuthor() {
        return "Hiro3";
    }

    @Override
    public String getVersion() {
        return UtilityMethods.getVersion();
    }

    @Override
    public void load() {
        ConfigManager.getConfig().addDefault(path("Enable"), true);
        ConfigManager.getConfig().addDefault(path("Cooldown"), 7000);
        ConfigManager.getConfig().addDefault(path("Range"), 12);
        ConfigManager.getConfig().addDefault(path("Combination"), Arrays.asList("AirBlast: SHIFT_DOWN", "AirSuction: SHIFT_UP", "AirSuction: SHIFT_DOWN"));
        ConfigManager.defaultConfig.save();
        ProjectKorra.log.info("Succesfully enabled " + getName() + " by " + getAuthor());
    }

    @Override
    public void stop() {
        ProjectKorra.log.info("Successfully disabled " + getName() + " by " + getAuthor());
        super.remove();
    }
}
