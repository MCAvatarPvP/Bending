package com.projectkorra.projectkorra.configuration;

import java.util.List;

/**
 * Platform-neutral configuration options mirroring the Bukkit calls the codebase used.
 */
public final class PKConfigurationOptions {
    private boolean copyDefaults;
    private String header;

    public PKConfigurationOptions copyDefaults(final boolean copyDefaults) {
        this.copyDefaults = copyDefaults;
        return this;
    }

    public boolean copyDefaults() {
        return this.copyDefaults;
    }

    public PKConfigurationOptions header(final String header) {
        this.header = header;
        return this;
    }

    public String header() {
        return this.header;
    }

    public PKConfigurationOptions setHeader(final List<String> headerLines) {
        this.header = String.join(System.lineSeparator(), headerLines);
        return this;
    }
}
