package hackathonpack.air;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.ClickType;
import hackathonpack.UtilityMethods;

import java.util.ArrayList;
import java.util.Arrays;

public class AirFlowReverse extends AbstractAirFlow implements AddonAbility, ComboAbility {
    private long cooldown;

    public AirFlowReverse(final Player player) {
        super(player);
        if (!this.bPlayer.canBendIgnoreBinds(this) || !ConfigManager.getConfig().getBoolean(path("Enable"))) return;
        this.cooldown = ConfigManager.getConfig().getLong(path("Cooldown"));
        setFlowFields(ConfigManager.getConfig().getLong(path("ChargeTime")), ConfigManager.getConfig().getLong(path("Duration")),
                ConfigManager.getConfig().getDouble(path("Range")), ConfigManager.getConfig().getInt(path("ParticlePerTick")),
                ConfigManager.getConfig().getDouble(path("ParticleSpawnAreaSize")));
        start();
    }

    private static String path(final String node) {
        return "ExtraAbilities.Hiro3.HackathonPack.Air.AirFlow.Reverse." + node;
    }

    @Override
    protected int sourceDistance() {
        return 12;
    }

    @Override
    protected double directionMultiplier() {
        return -1;
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
        return "AirFlowReverse";
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
        return new AirFlowReverse(player);
    }

    @Override
    public ArrayList<AbilityInformation> getCombination() {
        return UtilityMethods.getConfiguredCombination(path("Enable"), path("Combination"), new ArrayList<>(Arrays.asList(new AbilityInformation("AirBurst", ClickType.SHIFT_DOWN), new AbilityInformation("AirSuction", ClickType.LEFT_CLICK))));
    }

    @Override
    public String getDescription() {
        return "Create a flow of air that can carry entities towards you.";
    }

    @Override
    public String getInstructions() {
        return "AirBurst (Hold Sneak) -> AirSuction (Left Click)";
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
        ConfigManager.getConfig().addDefault(path("Cooldown"), 5000);
        ConfigManager.getConfig().addDefault(path("Duration"), 10000);
        ConfigManager.getConfig().addDefault(path("ChargeTime"), 500);
        ConfigManager.getConfig().addDefault(path("Range"), 20);
        ConfigManager.getConfig().addDefault(path("ParticlePerTick"), 1);
        ConfigManager.getConfig().addDefault(path("ParticleSpawnAreaSize"), 3);
        ConfigManager.getConfig().addDefault(path("Combination"), Arrays.asList("AirBurst: SHIFT_DOWN", "AirSuction: LEFT_CLICK"));
        ConfigManager.defaultConfig.save();
        ProjectKorra.log.info("Succesfully enabled " + getName() + " by " + getAuthor());
    }

    @Override
    public void stop() {
        ProjectKorra.log.info("Successfully disabled " + getName() + " by " + getAuthor());
        super.remove();
    }
}
