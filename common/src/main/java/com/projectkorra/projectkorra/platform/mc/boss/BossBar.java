package com.projectkorra.projectkorra.platform.mc.boss;

import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

public class BossBar {
    private final Delegate delegate;
    private double progress;

    public BossBar() {
        this("", BarColor.WHITE, BarStyle.SOLID);
    }

    public BossBar(String title, BarColor color, BarStyle style) {
        this.delegate = Platform.isInstalled() ? Platform.bossBars().create(title, color, style) : null;
    }

    public void addPlayer(Player player) {
        if (delegate != null) delegate.addPlayer(player);
    }

    public void removePlayer(Player player) {
        if (delegate != null) delegate.removePlayer(player);
    }

    public void removeAll() {
        if (delegate != null) delegate.removeAll();
    }

    public double getProgress() {
        return progress;
    }

    public void setProgress(double progress) {
        this.progress = Math.max(0.0, Math.min(1.0, progress));
        if (delegate != null) delegate.setProgress(this.progress);
    }

    public void setTitle(String title) {
        if (delegate != null) delegate.setTitle(title);
    }

    public void setColor(BarColor color) {
        if (delegate != null) delegate.setColor(color);
    }

    public void setVisible(boolean visible) {
        if (delegate != null) delegate.setVisible(visible);
    }

    public interface Delegate {
        void addPlayer(Player player);

        void removePlayer(Player player);

        void removeAll();

        void setProgress(double progress);

        void setTitle(String title);

        void setColor(BarColor color);

        void setVisible(boolean visible);
    }
}
