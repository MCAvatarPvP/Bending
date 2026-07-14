package com.projectkorra.projectkorra.platform.mc.scoreboard;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class Scoreboard {
    private final Map<String, Team> teams = new LinkedHashMap<>();
    private final Map<String, Objective> objectives = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public Team getTeam(String name) {
        return teams.get(name);
    }

    public Team registerNewTeam(String name) {
        Team team = new Team(this, name);
        teams.put(name, team);
        changed();
        return team;
    }

    public Objective registerNewObjective(String name, String criteria, String title) {
        Objective objective = new Objective(this, name, title);
        objectives.put(name, objective);
        changed();
        return objective;
    }

    public void resetScores(String entry) {
        boolean removed = false;
        for (Objective objective : objectives.values()) removed |= objective.removeScore(entry);
        if (removed) changed();
    }

    public void clearSlot(DisplaySlot slot) {
        objectives.values().forEach(objective -> {
            if (objective.getDisplaySlot() == slot) objective.setDisplaySlot(null);
        });
    }

    void removeTeam(String name) {
        teams.remove(name);
        changed();
    }

    void removeObjective(String name) {
        objectives.remove(name);
        changed();
    }

    void changed() {
        listeners.forEach(Runnable::run);
    }

    public void addChangeListener(Runnable listener) {
        if (listener != null) listeners.addIfAbsent(listener);
    }

    public void removeChangeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public Collection<Team> getTeams() {
        return List.copyOf(teams.values());
    }

    public Collection<Objective> getObjectives() {
        return List.copyOf(objectives.values());
    }

    public Set<String> getEntries() {
        Set<String> entries = new LinkedHashSet<>();
        teams.values().forEach(team -> entries.addAll(team.getEntries()));
        objectives.values().forEach(objective -> entries.addAll(objective.getScores().keySet()));
        return entries;
    }
}
