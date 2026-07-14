package com.projectkorra.projectkorra.platform.mc.scoreboard;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class Objective {
    private final Scoreboard scoreboard;
    private final String name;
    private final Map<String, Score> scores = new LinkedHashMap<>();
    private String title;
    private DisplaySlot displaySlot;

    Objective(Scoreboard scoreboard, String name, String title) {
        this.scoreboard = scoreboard;
        this.name = name;
        this.title = title == null ? "" : title;
    }

    public Score getScore(String entry) {
        return scores.computeIfAbsent(entry, key -> new Score(scoreboard));
    }

    public DisplaySlot getDisplaySlot() {
        return displaySlot;
    }

    public void setDisplaySlot(DisplaySlot slot) {
        if (displaySlot != slot) {
            displaySlot = slot;
            scoreboard.changed();
        }
    }

    public String getName() {
        return name;
    }

    public String getTitle() {
        return title;
    }

    public void setDisplayName(String title) {
        String next = title == null ? "" : title;
        if (!this.title.equals(next)) {
            this.title = next;
            scoreboard.changed();
        }
    }

    public Map<String, Score> getScores() {
        return Collections.unmodifiableMap(scores);
    }

    boolean removeScore(String entry) {
        return scores.remove(entry) != null;
    }

    public void unregister() {
        scoreboard.removeObjective(name);
    }

    public static final class Score {
        private final Scoreboard scoreboard;
        private int score;

        private Score(Scoreboard scoreboard) {
            this.scoreboard = scoreboard;
        }

        public int getScore() {
            return score;
        }

        public void setScore(int value) {
            if (score != value) {
                score = value;
                scoreboard.changed();
            }
        }
    }
}
