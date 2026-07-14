package com.projectkorra.projectkorra.command;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.chat.BaseComponent;
import com.projectkorra.projectkorra.platform.chat.ClickEvent;
import com.projectkorra.projectkorra.platform.chat.TextComponent;
import com.projectkorra.projectkorra.platform.mc.ChatColor;
import com.projectkorra.projectkorra.platform.mc.command.CommandSender;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Executor for /bending debug. Extends {@link PKCommand}.
 */
public class DebugCommand extends PKCommand {

    public DebugCommand() {
        super("debug", "/bending debug", ConfigManager.languageConfig.get().getString("Commands.Debug.Description"), new String[]{"debug", "de"});
    }

    @Override
    public void execute(final CommandSender sender, final List<String> args) {
        if (!this.hasPermission(sender)) {
            return;
        } else if (args.size() != 0) {
            this.help(sender, false);
            return;
        }

        GeneralMethods.runDebug();
        final File debugFile = new File(ProjectKorra.plugin.getDataFolder(), "debug.txt");
        TextComponent message = new TextComponent("");
        ClickEvent click = new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, debugFile.getAbsolutePath());
        message.setClickEvent(click);
        List<BaseComponent> comps = Arrays.asList(TextComponent.fromLegacyText(ChatColor.GREEN + ConfigManager.languageConfig.get().getString("Commands.Debug.SuccessfullyExported")));
        comps.forEach(c -> c.setClickEvent(click));
        message.setExtra(comps);
        sender.spigot().sendMessage(message);
    }

    /**
     * Checks if the CommandSender has the permission 'bending.admin.debug'. If
     * not, it tells them they don't have permission.
     *
     * @return True if they have permission, false otherwise.
     */
    @Override
    public boolean hasPermission(final CommandSender sender) {
        if (!sender.hasPermission("bending.admin." + this.getName())) {
            sender.sendMessage(super.noPermissionMessage);
            return false;
        }
        return true;
    }
}
