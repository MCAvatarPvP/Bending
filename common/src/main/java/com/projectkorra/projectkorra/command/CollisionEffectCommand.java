package com.projectkorra.projectkorra.command;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.util.ElementalCollisionEffects;
import com.projectkorra.projectkorra.platform.mc.ChatColor;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.command.CommandSender;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.ChatUtil;

import java.util.Arrays;
import java.util.List;

public class CollisionEffectCommand extends PKCommand {

    private static final List<String> ELEMENTS = Arrays.asList("air", "water", "earth", "fire", "ice", "plant", "blood", "lava", "metal", "sand", "lightning", "combustion", "bluefire");

    public CollisionEffectCommand() {
        super("collisioneffect", "/bending collisioneffect <Element> <Element>", "Tests elemental collision particles and sounds.", new String[]{"collisioneffect", "ceffect", "colfx"});
    }

    private static boolean isSupported(final Element element) {
        return element == Element.AIR || element == Element.WATER || element == Element.EARTH || element == Element.FIRE ||
                element == Element.ICE || element == Element.PLANT || element == Element.BLOOD ||
                element == Element.LAVA || element == Element.METAL || element == Element.SAND ||
                element == Element.LIGHTNING || element == Element.COMBUSTION || element == Element.BLUE_FIRE;
    }

    private static Location getTestLocation(final Player player) {
        final Block target = player.getTargetBlockExact(20);
        if (target != null) {
            return target.getLocation().add(0.5D, 1.0D, 0.5D);
        }
        return player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(4.0D));
    }

    @Override
    public void execute(final CommandSender sender, final List<String> args) {
        if (!this.hasPermission(sender) || !this.isPlayer(sender)) {
            return;
        }
        if (!this.correctLength(sender, args.size(), 2, 2)) {
            return;
        }

        final Element first = Element.fromString(args.get(0));
        final Element second = Element.fromString(args.get(1));
        if (!isSupported(first) || !isSupported(second)) {
            ChatUtil.sendBrandingMessage(sender, ChatColor.RED + "Use two bending elements: air, water, earth, fire, or their subelements.");
            return;
        }

        final Player player = (Player) sender;
        final Location location = getTestLocation(player);
        ElementalCollisionEffects.play(location, first, second, BendingPlayer.getBendingPlayer(player), player.getEyeLocation().getDirection());
        ChatUtil.sendBrandingMessage(player, ChatColor.GREEN + "Played " + first.getName() + " + " + second.getName() + " collision effect.");
    }

    @Override
    protected List<String> getTabCompletion(final CommandSender sender, final List<String> args) {
        if (args.size() <= 1) {
            return ELEMENTS;
        }
        return Arrays.asList();
    }
}
