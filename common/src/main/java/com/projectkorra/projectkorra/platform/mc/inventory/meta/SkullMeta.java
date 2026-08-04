package com.projectkorra.projectkorra.platform.mc.inventory.meta;

import java.util.UUID;

public class SkullMeta extends ItemMeta {
    private UUID profileId;
    private String texture = "";
    private String textureUrl = "";

    public UUID getProfileId() {
        return this.profileId;
    }

    public void setProfileId(final UUID profileId) {
        this.profileId = profileId;
    }

    public String getTexture() {
        return this.texture;
    }

    public void setTexture(final String texture) {
        this.texture = texture == null ? "" : texture;
    }

    public String getTextureUrl() {
        return this.textureUrl;
    }

    public void setTextureUrl(final String textureUrl) {
        this.textureUrl = textureUrl == null ? "" : textureUrl;
    }
}
