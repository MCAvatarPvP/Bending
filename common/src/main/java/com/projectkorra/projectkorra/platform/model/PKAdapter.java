package com.projectkorra.projectkorra.platform.model;

/**
 * Converts platform-native objects into ProjectKorra wrappers.
 */
public interface PKAdapter {
    PKPlayer player(Object nativePlayer);

    PKEntity entity(Object nativeEntity);

    PKLivingEntity livingEntity(Object nativeLivingEntity);

    PKWorld world(Object nativeWorld);

    PKBlock block(Object nativeBlock);

    PKLocation location(Object nativeLocation);

    Object unwrap(Object maybeWrapped);
}
