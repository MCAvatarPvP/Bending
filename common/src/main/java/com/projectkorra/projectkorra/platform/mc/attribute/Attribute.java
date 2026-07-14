package com.projectkorra.projectkorra.platform.mc.attribute;

public class Attribute {
    public static final Attribute AVATAR_STATE_TOGGLE = new Attribute("AVATAR_STATE_TOGGLE");
    public static final Attribute CHARGE_DURATION = new Attribute("CHARGE_DURATION");
    public static final Attribute COOLDOWN = new Attribute("COOLDOWN");
    public static final Attribute DAMAGE = new Attribute("DAMAGE");
    public static final Attribute DURATION = new Attribute("DURATION");
    public static final Attribute FIRE_TICK = new Attribute("FIRE_TICK");
    public static final Attribute HEIGHT = new Attribute("HEIGHT");
    public static final Attribute KNOCKBACK = new Attribute("KNOCKBACK");
    public static final Attribute KNOCKUP = new Attribute("KNOCKUP");
    public static final Attribute MAX_HEALTH = new Attribute("MAX_HEALTH");
    public static final Attribute RADIUS = new Attribute("RADIUS");
    public static final Attribute RANGE = new Attribute("RANGE");
    public static final Attribute SELECT_RANGE = new Attribute("SELECT_RANGE");
    public static final Attribute SPEED = new Attribute("SPEED");
    public static final Attribute WIDTH = new Attribute("WIDTH");
    private final String name;
    public Attribute() {
        this.name = getClass().getSimpleName();
    }
    private Attribute(String name) {
        this.name = name;
    }

    public static Attribute valueOf(String name) {
        return new Attribute(name);
    }

    public String name() {
        return this.name;
    }

    @Override
    public String toString() {
        return this.name;
    }

    public Object handle() {
        return this;
    }
}
