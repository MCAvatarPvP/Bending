package com.projectkorra.projectkorra.platform;

import java.util.Collection;

/**
 * Server metadata and raw-handle escape hatch used while migrating legacy integrations.
 */
public interface PKServer {
    <S> S handle();

    String version();

    String minecraftVersion();

    String name();

    boolean onlineMode();

    int viewDistance();

    <P> Collection<P> plugins();

    <D> D createBlockData(Object material);
}
