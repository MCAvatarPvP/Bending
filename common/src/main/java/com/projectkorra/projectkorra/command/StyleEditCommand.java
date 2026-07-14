package com.projectkorra.projectkorra.command;

import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.object.Style;
import com.projectkorra.projectkorra.platform.mc.ChatColor;
import com.projectkorra.projectkorra.platform.mc.command.CommandSender;
import com.projectkorra.projectkorra.util.ChatUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class StyleEditCommand extends PKCommand {

    private final String createSuccess;
    private final String removeSuccess;
    private final String alreadyExists;
    private final String doesntExists;
    private final String invalidName;

    public StyleEditCommand() {
        super("styleedit", "/bending styleedit <create, remove, changeName> <name> <config>", ConfigManager.languageConfig.get().getString("Commands.StyleEdit.Description"), new String[]{"styleedit"});

        this.createSuccess = ConfigManager.languageConfig.get().getString("Commands.StyleEdit.CreateSuccess");
        this.removeSuccess = ConfigManager.languageConfig.get().getString("Commands.StyleEdit.RemoveSuccess");
        this.alreadyExists = ConfigManager.languageConfig.get().getString("Commands.StyleEdit.AlreadyExists");
        this.doesntExists = ConfigManager.languageConfig.get().getString("Commands.StyleEdit.DoesntExists");
        this.invalidName = ConfigManager.languageConfig.get().getString("Commands.StyleEdit.InvalidName");
    }

    @Override
    public void execute(CommandSender sender, List<String> args) {
        if (!hasPermission(sender)) {
            ChatUtil.sendBrandingMessage(sender, this.noPermissionMessage);
            return;
        }
        if (!this.correctLength(sender, args.size(), 2, 2)) return;

        if (args.get(0).equalsIgnoreCase("create")) {
            create(sender, args.get(1));
        } else if (args.get(0).equalsIgnoreCase("remove")) {
            remove(sender, args.get(1));
        }
    }

    private void create(final CommandSender sender, String name) {
        if (Style.hasStyle(name)) {
            ChatUtil.sendBrandingMessage(sender, ChatColor.RED + this.alreadyExists.replace("{style}", name));
            return;
        }
        if (name.contains(".")) {
            ChatUtil.sendBrandingMessage(sender, ChatColor.RED + this.invalidName.replace("{style}", name));
            return;
        }

        Config config = new Config(new File("Styles" + File.separator + name + ".yml"));
        config.save();
        new Style(name, config);
        ChatUtil.sendBrandingMessage(sender, ChatColor.GREEN + this.createSuccess.replace("{style}", name));
    }

    private void remove(final CommandSender sender, String name) {
        if (!Style.hasStyle(name)) {
            ChatUtil.sendBrandingMessage(sender, ChatColor.RED + this.doesntExists.replace("{style}", name));
            return;
        }

        Style.removeStyle(name);
        ChatUtil.sendBrandingMessage(sender, ChatColor.GREEN + this.removeSuccess.replace("{style}", name));
    }

    @Override
    protected List<String> getTabCompletion(final CommandSender sender, final List<String> args) {
        if (!sender.hasPermission("bending.command.styleedit")) return new ArrayList<>();
        if (args.isEmpty()) return Arrays.asList("create", "remove");
        else if (args.size() == 1 && args.get(0).equalsIgnoreCase("remove")) return Style.getNames();

        return new ArrayList<>();
    }
}
