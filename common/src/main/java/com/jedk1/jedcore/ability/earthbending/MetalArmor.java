package com.jedk1.jedcore.ability.earthbending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.earthbending.EarthArmor;
import com.projectkorra.projectkorra.platform.mc.Color;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.inventory.meta.LeatherArmorMeta;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.util.TempArmor;
import com.projectkorra.projectkorra.util.TempPotionEffect;

import java.util.Arrays;
import java.util.List;

public class MetalArmor extends EarthAbility implements AddonAbility {

    private static final int GOLD_BLOCK_COLOR = 0xF2F204;
    private static final List<Integer> METAL_COLORS = Arrays.asList(
            0xa39d91, 0xf4f4f4, 0xa2a38f, 0xF2F204, 0xb75656, 0xfff4f4
    );

    private boolean useIronArmor;
    private int resistStrength;
    private int resistDuration;

    public MetalArmor(Player player) {
        super(player);

        if (bPlayer == null || !bPlayer.canBendIgnoreCooldowns(CoreAbility.getAbility(EarthArmor.class)) || !bPlayer.canMetalbend()) {
            return;
        }

        if (!CoreAbility.hasAbility(player, EarthArmor.class)) {
            return;
        }

        setFields();
        start();
    }

    private void setFields() {
        useIronArmor = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Earth.EarthArmor.UseIronArmor");
        resistStrength = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Earth.EarthArmor.Resistance.Strength");
        resistDuration = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Earth.EarthArmor.Resistance.Duration");
    }

    @Override
    public void progress() {
        if (player == null || !player.isOnline() || player.isDead()) {
            remove();
            return;
        }

        if (!CoreAbility.hasAbility(player, EarthArmor.class)) {
            remove();
            return;
        }

        EarthArmor ea = CoreAbility.getAbility(player, EarthArmor.class);
        if (!bPlayer.isToggled()) {
            remove();
            ea.remove();
            return;
        }

        if (ea.isFormed()) {
            if (isMetalHelmet()) {
                ItemStack[] armors = {new ItemStack(Material.CHAINMAIL_BOOTS, 1),
                        new ItemStack(Material.CHAINMAIL_LEGGINGS, 1),
                        new ItemStack(Material.CHAINMAIL_CHESTPLATE, 1),
                        new ItemStack(Material.CHAINMAIL_HELMET, 1)};

                if (useIronArmor) {
                    armors = new ItemStack[]{new ItemStack(Material.IRON_BOOTS, 1),
                            new ItemStack(Material.IRON_LEGGINGS, 1),
                            new ItemStack(Material.IRON_CHESTPLATE, 1),
                            new ItemStack(Material.IRON_HELMET, 1)};
                }

                if (useIronArmor && getHelmetColor().equals(Color.fromRGB(GOLD_BLOCK_COLOR))) {
                    armors = new ItemStack[]{new ItemStack(Material.GOLDEN_BOOTS, 1),
                            new ItemStack(Material.GOLDEN_LEGGINGS, 1),
                            new ItemStack(Material.GOLDEN_CHESTPLATE, 1),
                            new ItemStack(Material.GOLDEN_HELMET, 1)};
                }

                player.getInventory().setArmorContents(armors);
                PotionEffect resistance = new PotionEffect(PotionEffectType.RESISTANCE, resistDuration / 50, resistStrength - 1);
                new TempPotionEffect(player, resistance);
            }

            remove();
        }
    }

    private boolean isMetalHelmet() {
        Color color = getHelmetColor();

        return METAL_COLORS.contains(color.asRGB());
    }

    private Color getHelmetColor() {
        if (!TempArmor.hasTempArmor(player)) {
            return Color.BLACK;
        }

        ItemStack helm = player.getInventory().getHelmet();
        if (helm.getType() != Material.LEATHER_HELMET) {
            return Color.BLACK;
        }

        LeatherArmorMeta meta = (LeatherArmorMeta) helm.getItemMeta();
        if (meta == null) {
            return Color.BLACK;
        }

        return meta.getColor();
    }

    @Override
    public long getCooldown() {
        return 0;
    }

    @Override
    public Location getLocation() {
        return null;
    }

    @Override
    public String getName() {
        return "MetalArmor";
    }

    @Override
    public boolean isHiddenAbility() {
        return true;
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
    public String getAuthor() {
        return JedCore.dev;
    }

    @Override
    public String getVersion() {
        return JedCore.version;
    }

    @Override
    public String getDescription() {
        return null;
    }

    public boolean isUseIronArmor() {
        return useIronArmor;
    }

    public void setUseIronArmor(boolean useIronArmor) {
        this.useIronArmor = useIronArmor;
    }

    public int getResistStrength() {
        return resistStrength;
    }

    public void setResistStrength(int resistStrength) {
        this.resistStrength = resistStrength;
    }

    public int getResistDuration() {
        return resistDuration;
    }

    public void setResistDuration(int resistDuration) {
        this.resistDuration = resistDuration;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Earth.EarthArmor.Enabled");
    }
}
