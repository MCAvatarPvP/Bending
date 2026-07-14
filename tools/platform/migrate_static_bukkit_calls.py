#!/usr/bin/env python3
"""
Mechanical migration helper for ProjectKorra's platform abstraction.

This intentionally handles only semantic-preserving replacements where the
facade exposes the same timing/event/player/plugin behavior. It leaves raw
Bukkit entity/world/block types alone for the object-model migration stage.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "src" / "com" / "projectkorra" / "projectkorra"
PLATFORM_IMPORT = "import com.projectkorra.projectkorra.platform.Platform;\n"

REPL = [
    ("Bukkit.getServer().getPluginManager().callEvent(", "Platform.events().call("),
    ("Bukkit.getPluginManager().callEvent(", "Platform.events().call("),
    ("Bukkit.getServer().getPluginManager().registerEvents(", "Platform.events().registerListener("),
    ("Bukkit.getPluginManager().registerEvents(", "Platform.events().registerListener("),
    ("Bukkit.getScheduler().runTaskLater(ProjectKorra.plugin,", "Platform.scheduler().runLater("),
    ("Bukkit.getScheduler().runTaskTimer(ProjectKorra.plugin,", "Platform.scheduler().runTimer("),
    ("Bukkit.getScheduler().runTaskTimerAsynchronously(ProjectKorra.plugin,", "Platform.scheduler().runTimerAsync("),
    ("Bukkit.getScheduler().scheduleSyncRepeatingTask(ProjectKorra.plugin,", "Platform.scheduler().scheduleRepeating("),
    ("Bukkit.getScheduler().cancelTask(", "Platform.scheduler().cancelTask("),
    ("Bukkit.getLogger()", "Platform.logger()"),
    ("Bukkit.isPrimaryThread()", "Platform.scheduler().isPrimaryThread()"),
    ("Bukkit.getOnlinePlayers()", "Platform.players().onlinePlayers()"),
    ("Bukkit.getServer().getOnlinePlayers()", "Platform.players().onlinePlayers()"),
    ("Bukkit.getWorlds()", "Platform.worlds().worlds()"),
    ("Bukkit.getServer().getWorlds()", "Platform.worlds().worlds()"),
    ("Bukkit.getPluginManager().isPluginEnabled(", "Platform.plugins().isPluginEnabled("),
    ("Bukkit.getPluginManager().getPlugin(", "Platform.plugins().getPlugin("),
    ("Bukkit.getPlayer(", "Platform.players().getPlayer("),
    ("Bukkit.getOfflinePlayer(", "Platform.players().getOfflinePlayer("),
]

changed = []
for path in SRC.rglob("*.java"):
    if "/platform/bukkit/" in path.as_posix() or "/platform/fabric/" in path.as_posix():
        continue
    text = path.read_text(errors="ignore")
    original = text
    for a, b in REPL:
        text = text.replace(a, b)
    if text != original:
        if PLATFORM_IMPORT not in text:
            # Add after package and existing blank line/import group.
            lines = text.splitlines(True)
            insert = 0
            for i, line in enumerate(lines):
                if line.startswith("import "):
                    insert = i
                    break
            else:
                for i, line in enumerate(lines):
                    if line.strip() == "":
                        insert = i + 1
                        break
            lines.insert(insert, PLATFORM_IMPORT)
            text = "".join(lines)
        path.write_text(text)
        changed.append(path.relative_to(ROOT))

for p in changed:
    print(p)
print(f"changed={len(changed)}")
