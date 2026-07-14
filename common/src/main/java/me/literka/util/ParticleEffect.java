package me.literka.util;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.ParticleUtil;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class ParticleEffect {

    private Particle type;
    private Location location;
    private int count;
    private double offsetX, offsetY, offsetZ;
    private double velocityX, velocityY, velocityZ;
    private double speed;
    private Object data;
    private boolean force;
    private Consumer<ParticleEffect> beforeSpawn;

    public ParticleEffect() {
        this.type = null;
        this.location = null;
        this.count = 1;
        this.offsetX = 0;
        this.offsetY = 0;
        this.offsetZ = 0;
        this.velocityX = 0;
        this.velocityY = 0;
        this.velocityZ = 0;
        this.speed = 1;
        this.data = null;
        this.force = false;
        this.beforeSpawn = null;
    }

    public Particle getType() {
        return type;
    }

    public ParticleEffect type(Particle type) {
        this.type = type;
        return this;
    }

    public Location getLocation() {
        return location;
    }

    public ParticleEffect location(Location location) {
        this.location = location;
        return this;
    }

    public int getCount() {
        return count;
    }

    public ParticleEffect count(int count) {
        this.count = count;
        return this;
    }

    public Vector getOffset() {
        return new Vector(offsetX, offsetY, offsetZ);
    }

    public ParticleEffect offset(double x, double y, double z) {
        this.offsetX = x;
        this.offsetY = y;
        this.offsetZ = z;
        return this;
    }

    public double getOffsetX() {
        return offsetX;
    }

    public ParticleEffect offsetX(double offsetX) {
        this.offsetX = offsetX;
        return this;
    }

    public double getOffsetY() {
        return offsetY;
    }

    public ParticleEffect offsetY(double offsetY) {
        this.offsetY = offsetY;
        return this;
    }

    public double getOffsetZ() {
        return offsetZ;
    }

    public ParticleEffect offsetZ(double offsetZ) {
        this.offsetZ = offsetZ;
        return this;
    }

    public Vector getVelocity() {
        return new Vector(velocityX, velocityY, velocityZ);
    }

    public ParticleEffect velocity(Vector vector) {
        this.velocityX = vector.getX();
        this.velocityY = vector.getY();
        this.velocityZ = vector.getZ();
        return this;
    }

    public ParticleEffect velocity(double x, double y, double z) {
        this.velocityX = x;
        this.velocityY = y;
        this.velocityZ = z;
        return this;
    }

    public double getVelocityX() {
        return velocityX;
    }

    public ParticleEffect velocityX(double velocityX) {
        this.velocityX = velocityX;
        return this;
    }

    public double getVelocityY() {
        return velocityY;
    }

    public ParticleEffect velocityY(double velocityY) {
        this.velocityY = velocityY;
        return this;
    }

    public double getVelocityZ() {
        return velocityZ;
    }

    public ParticleEffect velocityZ(double velocityZ) {
        this.velocityZ = velocityZ;
        return this;
    }

    public double getSpeed() {
        return speed;
    }

    public ParticleEffect speed(double speed) {
        this.speed = speed;
        return this;
    }

    public Object getData() {
        return data;
    }

    public ParticleEffect data(Object data) {
        this.data = data;
        return this;
    }

    public boolean isForce() {
        return force;
    }

    public ParticleEffect force(boolean force) {
        this.force = force;
        return this;
    }

    public ParticleEffect beforeSpawn(Consumer<ParticleEffect> beforeSpawn) {
        this.beforeSpawn = beforeSpawn;
        return this;
    }

    public void spawn() {
        if (speed == 0 || (velocityX == 0 && velocityY == 0 && velocityZ == 0)) {
            ParticleUtil.spawn(type, location, count, offsetX, offsetY, offsetZ, speed, data);
            return;
        }

        spawnDirectional();
    }

    private void spawnDirectional() {
        double yVel = velocityY;
        if (type == Particle.WAX_ON || type == Particle.WAX_OFF) yVel *= 0.5;
        for (int i = 0; i < count; i++) {
            Location offsetted = offsettedLocation();
            if (beforeSpawn != null) beforeSpawn.accept(this);
            ParticleUtil.spawn(type, offsetted, 0, velocityX, yVel, velocityZ, speed, data);
        }
    }

    private Location offsettedLocation() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return location.clone().add(
                offsetX * random.nextGaussian(),
                offsetY * random.nextGaussian(),
                offsetZ * random.nextGaussian()
        );
    }
}