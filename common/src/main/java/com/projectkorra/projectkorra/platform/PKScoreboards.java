package com.projectkorra.projectkorra.platform;

/**
 * Scoreboard facade.
 */
public interface PKScoreboards {
    <S> S newScoreboard();

    <S> S mainScoreboard();
}
