package com.projectkorra.projectkorra.ability;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

public abstract class AvatarAbility extends ElementalAbility {

    public AvatarAbility(final Player player) {
        super(player);
    }

    public static void playAvatarSound(final Location loc) {
        final var avatarConfig = ConfigManager.avatarStateConfig.get();
        final var legacyConfig = ConfigManager.defaultConfig.get();
        if (avatarConfig.getBoolean("AvatarState.PlaySound", legacyConfig.getBoolean("Abilities.Avatar.AvatarState.PlaySound"))) {
            final float volume = (float) avatarConfig.getDouble("AvatarState.Sound.Volume", legacyConfig.getDouble("Abilities.Avatar.AvatarState.Sound.Volume"));
            final float pitch = (float) avatarConfig.getDouble("AvatarState.Sound.Pitch", legacyConfig.getDouble("Abilities.Avatar.AvatarState.Sound.Pitch"));

            Sound sound = Sound.BLOCK_BEACON_POWER_SELECT;
            String soundString = avatarConfig.getString("AvatarState.Sound.Sound", legacyConfig.getString("Abilities.Avatar.AvatarState.Sound.Sound"));

            try {
                sound = Sound.valueOf(soundString);
            } catch (final IllegalArgumentException exception) {
                ProjectKorra.log.warning("Your current value for 'AvatarState.Sound.Sound' is not valid.");
            } finally {
                loc.getWorld().playSound(loc, sound, volume, pitch);
            }
        }
    }

    @Override
    public boolean isIgniteAbility() {
        return false;
    }

    @Override
    public boolean isExplosiveAbility() {
        return false;
    }

    @Override
    public final Element getElement() {
        return Element.AVATAR;
    }

    /**
     * Determines whether the ability requires the user to be an avatar in order
     * to be able to use it. Set this to <code>false</code> for moves that should be
     * able to be used without players needing to have the avatar element
     */
    public boolean requireAvatar() {
        return true;
    }
}
