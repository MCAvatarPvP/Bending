package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.ArrayList;
import java.util.List;

/** Immutable collision geometry used while an authoritative hit is pending. */
public final class HitGeometry {
    private final List<Vector> contacts;
    private final double radius;

    public HitGeometry(final List<Vector> contacts, final double radius) {
        final List<Vector> valid = new ArrayList<>();
        if (contacts != null) {
            for (final Vector contact : contacts) {
                if (finite(contact)) valid.add(contact.clone());
            }
        }
        this.contacts = List.copyOf(valid);
        this.radius = Double.isFinite(radius) ? Math.max(0D, radius) : 0D;
    }

    public static HitGeometry point(final Vector contact) {
        return new HitGeometry(contact == null ? List.of() : List.of(contact), 0D);
    }

    /**
     * Captures every collision location exposed by an ability. CoreAbility's
     * contract defines the collision radius as the radius of each returned
     * location, so multi-stream abilities require no special-case registry.
     */
    public static HitGeometry capture(final CoreAbility ability, final Vector fallback) {
        final List<Vector> contacts = new ArrayList<>();
        double radius = 0D;
        if (ability != null) {
            try {
                final EntityHitboxProvider entityHitbox = ability instanceof EntityHitboxProvider provider
                        ? provider : null;
                radius = entityHitbox == null
                        ? ability.getCollisionRadius() : entityHitbox.getEntityHitRadius();
                final List<Location> locations = entityHitbox == null
                        ? ability.getLocations() : entityHitbox.getEntityHitLocations();
                if (locations != null) {
                    for (final Location location : locations) {
                        if (location != null) contacts.add(location.toVector());
                    }
                }
            } catch (final RuntimeException ignored) {
                // Addon abilities are allowed to expose transient locations;
                // retain the collision-time fallback if one disappears.
            }
        }
        if (contacts.isEmpty() && finite(fallback)) contacts.add(fallback.clone());
        return new HitGeometry(contacts, radius);
    }

    public List<Vector> contacts() {
        return contacts;
    }

    public double radius() {
        return radius;
    }

    public boolean isEmpty() {
        return contacts.isEmpty();
    }

    private static boolean finite(final Vector vector) {
        return vector != null && Double.isFinite(vector.getX())
                && Double.isFinite(vector.getY()) && Double.isFinite(vector.getZ());
    }
}
