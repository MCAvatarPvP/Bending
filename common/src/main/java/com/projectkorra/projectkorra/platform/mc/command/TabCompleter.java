package com.projectkorra.projectkorra.platform.mc.command;

import java.util.List;

public interface TabCompleter {
    List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args);
}
