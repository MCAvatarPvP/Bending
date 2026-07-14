package com.projectkorra.projectkorra.platform.mc.util;

import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class Transformation {
    private final Vector3f translation;
    private final Quaternionf leftRotation;
    private final Vector3f scale;
    private final Quaternionf rightRotation;

    public Transformation() {
        this(new Vector3f(), new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf());
    }

    public Transformation(Vector3f translation, AxisAngle4f leftRotation, Vector3f scale, AxisAngle4f rightRotation) {
        this(translation, new Quaternionf(leftRotation), scale, new Quaternionf(rightRotation));
    }

    public Transformation(Vector3f translation, Quaternionf leftRotation, Vector3f scale, Quaternionf rightRotation) {
        this.translation = new Vector3f(translation);
        this.leftRotation = new Quaternionf(leftRotation);
        this.scale = new Vector3f(scale);
        this.rightRotation = new Quaternionf(rightRotation);
    }

    public Vector3f translation() {
        return new Vector3f(translation);
    }

    public Quaternionf leftRotation() {
        return new Quaternionf(leftRotation);
    }

    public Vector3f scale() {
        return new Vector3f(scale);
    }

    public Quaternionf rightRotation() {
        return new Quaternionf(rightRotation);
    }
}
