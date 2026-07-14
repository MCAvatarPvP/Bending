package com.projectkorra.projectkorra.platform.mc.scoreboard;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public class Team {
    private final String name;
    private final Set<String> entries = new LinkedHashSet<>();
    private Scoreboard scoreboard;
    private String prefix = "", suffix = "";

    public Team() {
        this(new Scoreboard(), "team");
    }

    public Team(Scoreboard scoreboard) {
        this(scoreboard, "team");
    }

    Team(Scoreboard scoreboard, String name) {
        this.scoreboard = scoreboard;
        this.name = name;
    }

    public void addEntry(String entry) {
        if (entries.add(entry)) changed();
    }

    public boolean hasEntry(String entry) {
        return entries.contains(entry);
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String value) {
        String next = value == null ? "" : value;
        if (!prefix.equals(next)) {
            prefix = next;
            changed();
        }
    }

    public String getSuffix() {
        return suffix;
    }

    public void setSuffix(String value) {
        String next = value == null ? "" : value;
        if (!suffix.equals(next)) {
            suffix = next;
            changed();
        }
    }

    public String getName() {
        return name;
    }

    public Set<String> getEntries() {
        return Collections.unmodifiableSet(entries);
    }

    public Scoreboard getScoreboard() {
        return scoreboard;
    }

    public void unregister() {
        Scoreboard owner = scoreboard;
        scoreboard = null;
        if (owner != null) owner.removeTeam(name);
    }

    private void changed() {
        if (scoreboard != null) scoreboard.changed();
    }
}
