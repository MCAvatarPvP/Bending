package com.projectkorra.projectkorra.attribute;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.AvatarAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.configuration.PKConfiguration;
import com.projectkorra.projectkorra.configuration.PKConfigurationSection;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class AttributeCache {

    private Field field;
    private String attribute;
    private Map<Class<? extends Annotation>, Annotation> markers = new HashMap<>();
    private Map<CoreAbility, Object> initialValues = new HashMap<>();
    private Map<CoreAbility, Set<AttributeModification>> currentModifications = new HashMap<>();
    private Optional<AttributeModification> avatarStateModifier = Optional.empty();

    public AttributeCache(Field field, String attribute) {
        this.field = field;
        this.attribute = attribute;
    }

    @NotNull
    public Field getField() {
        return field;
    }

    @NotNull
    public String getAttribute() {
        return attribute;
    }

    /**
     * Check if the cache has a marker of the given class
     *
     * @param markerClass The class of the marker to check for
     * @return True if the cache has a marker of the given class
     */
    public boolean hasMarker(Class<? extends Annotation> markerClass) {
        return markers.containsKey(markerClass);
    }

    public void addMaker(@NotNull Annotation marker) {
        markers.put(marker.annotationType(), marker);
    }

    @Nullable
    public <T extends Annotation> T getMarker(Class<T> markerClass) {
        return (T) markers.get(markerClass);
    }

    public Map<CoreAbility, Object> getInitialValues() {
        return initialValues;
    }

    public Map<CoreAbility, Set<AttributeModification>> getCurrentModifications() {
        return currentModifications;
    }

    /**
     * Calculate the AvatarState equivalent of the attribute for the ability
     *
     * @param ability The ability to calculate the AvatarState modifier for
     */
    public void calculateAvatarStateModifier(CoreAbility ability) {
        if (ability instanceof AvatarAbility && ((AvatarAbility) ability).requireAvatar()) return;

        String configName = attribute;

        if (attribute.equals(Attribute.AVATAR_STATE_TOGGLE)) configName = "IsToggle";
        String elementName = ability.getElement().getName();
        if (ability.getElement() instanceof Element.SubElement) {
            elementName = ((Element.SubElement) ability.getElement()).getParentElement().getName();
        }
        Object configObject = findAvatarStateValue(ability, elementName, configName);

        if (configObject == null || configObject instanceof PKConfigurationSection) return;

        String stringObject = configObject.toString();

        if (configObject instanceof Boolean && field.getType() == Boolean.TYPE) {
            avatarStateModifier = Optional.of(AttributeModification.setter((Boolean) configObject, AttributeModification.PRIORITY_LOW, AttributeModification.AVATAR_STATE_FACTOR));
        } else if (configObject instanceof Number) {
            avatarStateModifier = Optional.of(AttributeModification.of(AttributeModifier.SET, (Number) configObject, AttributeModification.PRIORITY_LOW, AttributeModification.AVATAR_STATE_FACTOR));
        } else if (stringObject != null) {
            stringObject = AttributeUtil.normalizeLegacyConfigValue(stringObject);

            Pair<AttributeModifier, Number> parsed = AttributeUtil.getModification(stringObject);

            if (parsed == null) {
                ProjectKorra.log.severe("Failed to parse AvatarState modification for " + ability.getName() + " " + attribute + " with value " + stringObject);
                return;
            }
            avatarStateModifier = Optional.of(AttributeModification.of(parsed.getLeft(), parsed.getRight(), AttributeModification.PRIORITY_LOW, AttributeModification.AVATAR_STATE_FACTOR));
        }
    }

    public Optional<AttributeModification> getAvatarStateModifier() {
        return avatarStateModifier;
    }

    private Object findAvatarStateValue(CoreAbility ability, String elementName, String configName) {
        return fetchAbilityValue(ConfigManager.avatarStateConfig.get(), elementName, ability.getName(), configName);
    }

    private Object scanSection(PKConfigurationSection section, String configName) {
        if (section == null) {
            return null;
        }

        Map<String, Object> values = section.getValues(true);
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getValue() instanceof PKConfigurationSection) continue;

            String key = entry.getKey();
            if (key.equalsIgnoreCase(configName) || key.endsWith("." + configName)) {
                return entry.getValue();
            }
        }

        return null;
    }

    private Object fetchAbilityValue(PKConfiguration config, String elementName, String abilityName, String configName) {
        //Exact element/ability path
        String path = "Abilities." + elementName + "." + abilityName + "." + configName;
        Object value = config.get(path);
        if (value != null && !(value instanceof PKConfigurationSection)) {
            return value;
        }

        //Scan element/ability section for nested keys
        value = scanSection(config.getConfigurationSection("Abilities." + elementName + "." + abilityName), configName);
        if (value != null) {
            return value;
        }

        //Global ability section without element
        path = "Abilities." + abilityName + "." + configName;
        value = config.get(path);
        if (value != null && !(value instanceof PKConfigurationSection)) {
            return value;
        }

        value = scanSection(config.getConfigurationSection("Abilities." + abilityName), configName);
        if (value != null) {
            return value;
        }

        value = scanOtherElements(config, abilityName, configName);
        if (value != null) {
            return value;
        }

        return null;
    }

    private Object scanOtherElements(PKConfiguration config, String abilityName, String configName) {
        PKConfigurationSection abilitiesRoot = config.getConfigurationSection("Abilities");
        if (abilitiesRoot == null) return null;

        for (String elementKey : abilitiesRoot.getKeys(false)) {
            PKConfigurationSection elementSection = abilitiesRoot.getConfigurationSection(elementKey);
            if (elementSection == null) continue;

            Object value = elementSection.get("AvatarState." + configName);
            if (value != null && !(value instanceof PKConfigurationSection) && abilityName.equalsIgnoreCase("AvatarState")) {
                return value;
            }

            PKConfigurationSection abilitySection = elementSection.getConfigurationSection(abilityName);
            if (abilitySection != null) {
                Object direct = abilitySection.get(configName);
                if (direct != null && !(direct instanceof PKConfigurationSection)) return direct;

                Object nested = scanSection(abilitySection, configName);
                if (nested != null) return nested;
            }
        }

        return null;
    }
}
