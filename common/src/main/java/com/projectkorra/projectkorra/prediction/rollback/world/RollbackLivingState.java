package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.attribute.AttributeInstance;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.inventory.EntityEquipment;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Living-entity state exposed to existing abilities; the native adapter owns damage and effect rules. */
public final class RollbackLivingState implements RollbackStateCell<RollbackLivingState.State> {
    public record Vitals(double health, double absorption, double eyeHeight, int remainingAir, int maximumAir,
                         int noDamageTicks, int maximumNoDamageTicks, double lastDamage, boolean ai) {
        public Vitals {
            if (!Double.isFinite(health) || health < 0 || !Double.isFinite(absorption) || absorption < 0
                    || !Double.isFinite(eyeHeight) || eyeHeight < 0 || !Double.isFinite(lastDamage)
                    || maximumAir < 0 || maximumNoDamageTicks < 0) throw new IllegalArgumentException("Living entity vitals");
        }
    }

    public interface Rules {
        /** Apply native armor, immunity, damage events and resulting logical state/effects. */
        void damage(RollbackLivingState target, double amount, Entity source);
        /** Retain native merge/hidden-effect behavior in the captured physics state. */
        boolean addPotion(RollbackLivingState target, PotionEffect effect, boolean force);
        void removePotion(RollbackLivingState target, PotionEffectType type);
        /** Common AttributeInstance.setValue changes the native base; expose the resulting effective value. */
        void attribute(RollbackLivingState target, String name, double baseValue);
    }

    /** Native combat and movement share one owned replica and one checkpoint. */
    public interface Source<S> extends RollbackEntityBody.KinematicsSource<S>, Rules {
        @Override default double eyeHeight() { return readVitals().eyeHeight(); }
        Vitals readVitals();
        void writeVitals(Vitals value);
        Double attributeValue(String name);
        Map<String, Double> readAttributes();
        PotionEffect readPotion(PotionEffectType type);
        Collection<PotionEffect> readPotions();
        boolean dead();
    }

    public static final class State {
        private final RollbackLivingState owner;
        private final Vitals vitals;
        private final Map<String, Double> attributes;
        private final Map<String, PotionEffect> potions;
        private State(RollbackLivingState owner) {
            this.owner = owner;
            vitals = owner.vitals;
            attributes = Collections.unmodifiableMap(new LinkedHashMap<>(owner.attributes));
            potions = Collections.unmodifiableMap(new LinkedHashMap<>(owner.potions));
        }
    }

    private final RollbackEntityBody body;
    private final EntityEquipment equipment;
    private final Rules rules;
    private final Source<?> source;
    private final Map<String, Double> attributes = new LinkedHashMap<>();
    private final Map<String, PotionEffect> potions = new LinkedHashMap<>();
    // Stable handles contain only a key. State lives in the maps, so a retained
    // AttributeInstance reads the corrected value after a rewind.
    private final Map<String, AttributeView> attributeViews = new LinkedHashMap<>();
    private Vitals vitals;

    public RollbackLivingState(RollbackEntityBody body, Vitals vitals, Map<String, Double> attributes,
                               Collection<PotionEffect> potions, EntityEquipment equipment, Rules rules) {
        this(body, equipment, rules, null);
        this.vitals = Objects.requireNonNull(vitals, "vitals");
        replaceAttributes(attributes);
        replacePotions(potions);
        if (vitals.health == 0) body.liveness(body.valid(), true);
    }

    public static RollbackLivingState nativeBacked(RollbackEntityBody body, EntityEquipment equipment, Source<?> source) {
        Objects.requireNonNull(source, "source");
        if (body.kinematicsSource() != source) throw new IllegalArgumentException("Combat and movement must share the exact native backing");
        if (equipment instanceof RollbackEquipment view && view.inventory().nativeOwner() != source) {
            throw new IllegalArgumentException("Combat and equipment must share the exact native backing");
        }
        return new RollbackLivingState(body, equipment, source, source);
    }

    private RollbackLivingState(RollbackEntityBody body, EntityEquipment equipment, Rules rules, Source<?> source) {
        this.body = Objects.requireNonNull(body, "body");
        if (!(equipment instanceof RollbackStateCell<?>)) throw new IllegalArgumentException("Equipment must be a checkpointable logical view");
        this.equipment = equipment;
        this.rules = Objects.requireNonNull(rules, "rules");
        this.source = source;
    }

    public RollbackEntityBody body() { checkThread(); return body; }
    public RollbackStateCell<?> combatSource() { checkThread(); return source; }
    public LivingEntity view() {
        checkThread();
        if (!(body.view() instanceof LivingEntity living)) throw new IllegalStateException("Living state is not bound to a LivingEntity");
        return living;
    }
    public Vitals vitals() { checkThread(); return source == null ? vitals : source.readVitals(); }
    public void vitals(Vitals value) {
        checkThread();
        // Native capture may observe health above a newly reduced maximum until
        // native processing clamps it. Preserve that state; API setters validate below.
        Objects.requireNonNull(value, "vitals");
        if (source != null) source.writeVitals(value);
        else {
            vitals = value;
            if (value.health == 0) body.liveness(body.valid(), true);
        }
    }
    public boolean dead() { checkThread(); return body.dead() || (source != null && source.dead()); }
    public double maxHealth() { checkThread(); return attributeValue(Attribute.MAX_HEALTH.name()); }
    public void health(double value) {
        if (value > maxHealth()) throw new IllegalArgumentException("Health exceeds maximum");
        Vitals v = vitals();
        vitals(new Vitals(value, v.absorption, v.eyeHeight, v.remainingAir, v.maximumAir,
                v.noDamageTicks, v.maximumNoDamageTicks, v.lastDamage, v.ai));
    }
    public void absorption(double value) {
        Vitals v = vitals();
        vitals(new Vitals(v.health, value, v.eyeHeight, v.remainingAir, v.maximumAir,
                v.noDamageTicks, v.maximumNoDamageTicks, v.lastDamage, v.ai));
    }
    public void remainingAir(int value) {
        Vitals v = vitals();
        vitals(new Vitals(v.health, v.absorption, v.eyeHeight, value, v.maximumAir,
                v.noDamageTicks, v.maximumNoDamageTicks, v.lastDamage, v.ai));
    }
    public void noDamageTicks(int value) {
        Vitals v = vitals();
        vitals(new Vitals(v.health, v.absorption, v.eyeHeight, v.remainingAir, v.maximumAir,
                value, v.maximumNoDamageTicks, v.lastDamage, v.ai));
    }
    public void ai(boolean value) {
        Vitals v = vitals();
        vitals(new Vitals(v.health, v.absorption, v.eyeHeight, v.remainingAir, v.maximumAir,
                v.noDamageTicks, v.maximumNoDamageTicks, v.lastDamage, value));
    }
    public Location eyeLocation() { return body.location().add(0, vitals().eyeHeight, 0); }
    public EntityEquipment equipment() { checkThread(); return equipment; }
    public void damage(double amount, Entity source) {
        checkThread();
        if (!Double.isFinite(amount) || amount < 0) throw new IllegalArgumentException("Damage amount");
        if (source != null && RollbackEntityBody.logicalBody(source).world() != body.world()) {
            throw new IllegalArgumentException("Damage source belongs to another logical world");
        }
        rules.damage(this, amount, source);
    }

    /** Called by native capture/physics after updating base attributes and modifiers. */
    public void replaceAttributes(Map<String, Double> values) {
        checkThread();
        if (source != null) throw new IllegalStateException("Native attributes must be changed through their native rules");
        if (values.size() > 1_024) throw new IllegalArgumentException("Attribute budget");
        Map<String, Double> copy = new LinkedHashMap<>();
        values.forEach((name, value) -> {
            if (name == null || value == null || !Double.isFinite(value)) throw new IllegalArgumentException("Attribute value");
            copy.put(name, value);
        });
        Double maximum = copy.get(Attribute.MAX_HEALTH.name());
        if (maximum == null || maximum <= 0) throw new IllegalArgumentException("Maximum health attribute");
        attributes.clear();
        attributes.putAll(copy);
    }
    public AttributeInstance attribute(Attribute attribute) {
        checkThread();
        String key = Objects.requireNonNull(attribute, "attribute").name();
        if (attributeValue(key) == null) return null;
        if (!attributeViews.containsKey(key) && attributeViews.size() >= 1_024) throw new IllegalStateException("Attribute handle budget");
        return attributeViews.computeIfAbsent(key, AttributeView::new);
    }
    private Double attributeValue(String name) { return source == null ? attributes.get(name) : source.attributeValue(name); }
    public Map<String, Double> attributes() { checkThread(); return source == null ? Collections.unmodifiableMap(new LinkedHashMap<>(attributes)) : Map.copyOf(source.readAttributes()); }
    public boolean hasPotion(PotionEffectType type) { return potion(type) != null; }
    public PotionEffect potion(PotionEffectType type) { checkThread(); return source == null ? potions.get(type.name()) : source.readPotion(type); }
    public Collection<PotionEffect> potions() { checkThread(); return source == null ? List.copyOf(potions.values()) : List.copyOf(source.readPotions()); }
    public boolean addPotion(PotionEffect effect, boolean force) { checkThread(); return rules.addPotion(this, copyPotion(effect), force); }
    public void removePotion(PotionEffectType type) { checkThread(); rules.removePotion(this, Objects.requireNonNull(type, "type")); }
    /** Effective effects only. Native hidden-effect chains remain in body.nativeState(). */
    public void replacePotions(Collection<PotionEffect> effects) {
        checkThread();
        if (source != null) throw new IllegalStateException("Native effects must be changed through their native rules");
        if (effects.size() > 256) throw new IllegalArgumentException("Potion effect budget");
        Map<String, PotionEffect> copy = new LinkedHashMap<>();
        for (PotionEffect effect : effects) {
            PotionEffect value = copyPotion(effect);
            if (copy.put(value.getType().name(), value) != null) throw new IllegalArgumentException("Duplicate effective potion");
        }
        potions.clear();
        potions.putAll(copy);
    }

    @Override public State captureRollbackState() { checkThread(); return new State(this); }
    @Override public void restoreRollbackState(State state) {
        checkThread();
        if (state.owner != this) throw new IllegalArgumentException("Living checkpoint belongs to another entity");
        vitals = state.vitals;
        attributes.clear(); attributes.putAll(state.attributes);
        potions.clear(); potions.putAll(state.potions);
    }
    @Override public Collection<?> rollbackReferences() { return List.of(body, equipment); }
    private void checkThread() {
        body.identity();
        if (source != null && body.kinematicsSource() != source) throw new IllegalStateException("Native living backing changed");
    }
    private static PotionEffect copyPotion(PotionEffect effect) {
        Objects.requireNonNull(effect, "effect");
        PotionEffectType type = Objects.requireNonNull(effect.getType(), "potion type");
        // A captured descriptor cannot keep a native wrapper reachable from the checkpoint.
        if (type.getClass() != PotionEffectType.class) type = PotionEffectType.valueOf(type.name());
        return new PotionEffect(type, effect.getDuration(), effect.getAmplifier());
    }

    private final class AttributeView extends AttributeInstance implements RollbackStateCell<Void> {
        private final String key;
        private AttributeView(String key) { super(0); this.key = key; }
        @Override public double getValue() {
            checkThread();
            Double value = attributeValue(key);
            if (value == null) throw new IllegalStateException("Attribute is absent from this frame: " + key);
            return value;
        }
        @Override public void setValue(double value) {
            getValue();
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Attribute value");
            rules.attribute(RollbackLivingState.this, key, value);
        }
        @Override public Void captureRollbackState() { return null; }
        @Override public void restoreRollbackState(Void ignored) { }
        @Override public Collection<?> rollbackReferences() { return List.of(RollbackLivingState.this); }
    }
}
