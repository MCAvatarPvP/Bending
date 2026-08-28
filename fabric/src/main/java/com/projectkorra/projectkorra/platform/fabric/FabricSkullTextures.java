package com.projectkorra.projectkorra.platform.fabric;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.projectkorra.projectkorra.platform.mc.inventory.meta.SkullMeta;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/** Loader-local conversion between common skull metadata and vanilla profiles. */
final class FabricSkullTextures {
    private FabricSkullTextures() {
    }

    /** Converts Bukkit-style skin URLs into the base64 profile payload vanilla expects. */
    static String profileValue(final SkullMeta meta) {
        if (meta == null) return "";
        if (!meta.getTexture().isBlank()) return meta.getTexture();
        if (meta.getTextureUrl().isBlank()) return "";
        final String escapedUrl = meta.getTextureUrl()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
        final String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + escapedUrl + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Builds the complete profile before handing it to Authlib's immutable record. */
    static GameProfile profile(final SkullMeta meta) {
        final String texture = profileValue(meta);
        if (texture.isBlank()) return null;

        final UUID profileId = meta.getProfileId() != null
                ? meta.getProfileId()
                : UUID.nameUUIDFromBytes(texture.getBytes(StandardCharsets.UTF_8));
        final Property property = new Property("textures", texture);
        final PropertyMap properties = new PropertyMap(
                ImmutableMultimap.of("textures", property));
        return new GameProfile(profileId, "ProjectKorra", properties);
    }
}
