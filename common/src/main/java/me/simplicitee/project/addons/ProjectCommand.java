package me.simplicitee.project.addons;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.ChatColor;
import com.projectkorra.projectkorra.platform.mc.command.Command;
import com.projectkorra.projectkorra.platform.mc.command.CommandExecutor;
import com.projectkorra.projectkorra.platform.mc.command.CommandSender;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

public class ProjectCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ProjectAddons.instance.version());
            return true;
        } else if (args.length == 1 && sender instanceof Player) {
            if (args[0].equalsIgnoreCase("active")) {
                for (CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
                    if (ability.getPlayer().getUniqueId() == ((Player) sender).getUniqueId()) {
                        sender.sendMessage(ability.getElement().getColor() + ability.getName() + ChatColor.WHITE + " : " + (System.currentTimeMillis() - ability.getStartTime()));
                    }
                }
            }
            return true;
        }

        return false;
    }
}
