package com.projectkorra.projectkorra.command;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.util.ComboManager;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.ability.util.ComboManager.ComboAbilityInfo;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.mc.ChatColor;
import com.projectkorra.projectkorra.platform.mc.command.CommandSender;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.ChatUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Executor for /bending combohelp. Extends {@link PKCommand}.
 */
public class ComboHelpCommand extends PKCommand {

    private final String invalidCombo;
    private final String noPermissionForCombo;
    private final String started;
    private final String stopped;

    public ComboHelpCommand() {
        super("combohelp", "/bending combohelp <Combo/Off>", ConfigManager.languageConfig.get().getString("Commands.ComboHelp.Description"), new String[]{"combohelp", "ch"});

        this.invalidCombo = ConfigManager.languageConfig.get().getString("Commands.ComboHelp.InvalidCombo");
        this.noPermissionForCombo = ConfigManager.languageConfig.get().getString("Commands.ComboHelp.NoPermissionForCombo");
        this.started = ConfigManager.languageConfig.get().getString("Commands.ComboHelp.Started");
        this.stopped = ConfigManager.languageConfig.get().getString("Commands.ComboHelp.Stopped");
    }

    @Override
    public void execute(final CommandSender sender, final List<String> args) {
        if (!this.hasPermission(sender) || !this.isPlayer(sender) || !this.correctLength(sender, args.size(), 1, 1)) {
            return;
        }

        final Player player = (Player) sender;
        final String requestedCombo = args.get(0);
        if (requestedCombo.equalsIgnoreCase("off") || requestedCombo.equalsIgnoreCase("stop")) {
            ComboManager.stopComboHelp(player);
            ChatUtil.sendBrandingMessage(sender, ChatColor.YELLOW + this.stopped);
            return;
        }

        final ComboAbilityInfo combo = ComboManager.getComboAbility(requestedCombo);
        if (combo == null || CoreAbility.getAbility(combo.getName()) == null) {
            ChatUtil.sendBrandingMessage(sender, ChatColor.RED + this.invalidCombo.replace("{combo}", requestedCombo));
            return;
        }

        if (!player.hasPermission("bending.ability." + combo.getName())) {
            ChatUtil.sendBrandingMessage(sender, ChatColor.RED + this.noPermissionForCombo.replace("{combo}", combo.getName()));
            return;
        }

        if (ComboManager.startComboHelp(player, combo)) {
            ChatUtil.sendBrandingMessage(sender, ChatColor.GREEN + this.started.replace("{combo}", combo.getName()));
        }
    }

    @Override
    protected List<String> getTabCompletion(final CommandSender sender, final List<String> args) {
        if (!args.isEmpty() || !sender.hasPermission("bending.command.combohelp")) {
            return new ArrayList<>();
        }

        final List<String> combos = new ArrayList<>();
        combos.add("off");
        final BendingPlayer bPlayer = sender instanceof Player ? BendingPlayer.getBendingPlayer((Player) sender) : null;
        for (final String comboName : ComboManager.getComboAbilities().keySet()) {
            final ComboAbilityInfo combo = ComboManager.getComboAbilities().get(comboName);
            if (sender.hasPermission("bending.ability." + comboName) && this.canBindComboAbilities(bPlayer, combo)) {
                combos.add(comboName);
            }
        }
        Collections.sort(combos);
        return combos;
    }

    private boolean canBindComboAbilities(final BendingPlayer bPlayer, final ComboAbilityInfo combo) {
        if (bPlayer == null || combo == null) {
            return false;
        }

        for (final AbilityInformation info : combo.getAbilities()) {
            if (!bPlayer.canBind(CoreAbility.getAbility(info.getAbilityName()))) {
                return false;
            }
        }
        return true;
    }
}
