package com.projectkorra.projectkorra.platform.mc.damage;

public final class DamageSource {
    private final DamageType type;

    private DamageSource(DamageType type) {
        this.type = type;
    }

    public static Builder builder(DamageType type) {
        return new Builder(type);
    }

    public DamageType type() {
        return type;
    }

    public static final class Builder {
        private final DamageType type;

        private Builder(DamageType type) {
            this.type = type;
        }

        public DamageSource build() {
            return new DamageSource(type);
        }
    }
}
