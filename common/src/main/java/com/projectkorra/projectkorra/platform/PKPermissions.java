package com.projectkorra.projectkorra.platform;

/**
 * Permission registry facade.
 */
public interface PKPermissions {
    <P> P getPermission(String name);

    void addPermission(Object permission);
}
