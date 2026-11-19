package com.projectkorra.projectkorra.ability.activation;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.chiblocking.Smokescreen;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.entity.Arrow;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.event.entity.ProjectileHitEvent;
import com.projectkorra.projectkorra.platform.mc.metadata.MetadataValue;
import com.projectkorra.projectkorra.chiblocking.util.DaggerProjectileOwner;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageByEntityEvent;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent.DamageCause;
import com.projectkorra.projectkorra.support.AbilityWorld;
import com.projectkorra.projectkorra.util.ClickType;
import me.literka.ChiListener;
import me.moros.hyperion.configuration.Config;
import me.moros.hyperion.configuration.ConfigManager;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SharedAbilityNameTest {
    private static final ObjenesisStd PROTOTYPES = new ObjenesisStd();

    @Test void eitherRegisteredSmokescreenReachesOnlyItsOwnConstructor() throws Exception {
        var handlers = AbilityActivationManager.class.getDeclaredField("HANDLERS");
        handlers.setAccessible(true);
        Object originalHandlers = handlers.get(null);
        var originalConfig = ConfigManager.config;
        try (var world = new AbilityWorld()) {
            var player = world.player(0, 64, 0);
            var profile = new Binding(player);
            var previous = BendingPlayer.getPlayers().put(player.getUniqueId(), profile);
            try {
                handlers.set(null, new HashMap<>());
                com.projectkorra.projectkorra.configuration.ConfigManager.defaultConfig.set("Abilities.Chi.Smokescreen.Enabled", false);
                CoreAbilityActivationBootstrap.registerChi();
                AddonAbilityActivationBootstrap.registerHyperionActions();
                ConfigManager.config = PROTOTYPES.newInstance(EmptyHyperionConfig.class);
                for (var type : List.of(Smokescreen.class, me.moros.hyperion.abilities.chiblocking.Smokescreen.class)) {
                    profile.selected = PROTOTYPES.newInstance(type);
                    profile.attempted.clear();
                    AbilityActivationManager.dispatchBoundAbility(new ActivationContext(player, profile, ClickType.LEFT_CLICK));
                    assertEquals(List.of(type), profile.attempted);
                }
            } finally {
                if (previous == null) BendingPlayer.getPlayers().remove(player.getUniqueId());
                else BendingPlayer.getPlayers().put(player.getUniqueId(), previous);
                handlers.set(null, originalHandlers);
                ConfigManager.config = originalConfig;
            }
        }
    }

    @Test void chiReworkOnlyProcessesAnAttackBoundToItsOwnStrike() throws Exception {
        try (var world = new AbilityWorld()) {
            var player = world.player(0, 64, 0);
            var target = world.player(1, 64, 0);
            var profile = new Binding(player);
            var previous = BendingPlayer.getPlayers().put(player.getUniqueId(), profile);
            try {
                var listener = new ChiListener();
                var event = new EntityDamageByEntityEvent(player, target, DamageCause.ENTITY_ATTACK, null, 1);
                for (var type : List.of(com.projectkorra.projectkorra.chiblocking.QuickStrike.class,
                        com.projectkorra.projectkorra.chiblocking.SwiftKick.class, com.projectkorra.projectkorra.chiblocking.RapidPunch.class,
                        me.literka.abilities.QuickStrike.class, me.literka.abilities.SwiftKick.class, me.literka.abilities.RapidPunch.class)) {
                    profile.selected = PROTOTYPES.newInstance(type);
                    profile.attempted.clear();
                    listener.onEntityHit(event);
                    boolean isRework = type.getPackageName().equals(me.literka.abilities.QuickStrike.class.getPackageName());
                    assertEquals(isRework ? List.of(type) : List.of(), profile.attempted);
                    assertFalse(event.isCancelled());
                }
            } finally {
                if (previous == null) BendingPlayer.getPlayers().remove(player.getUniqueId());
                else BendingPlayer.getPlayers().put(player.getUniqueId(), previous);
            }
        }
    }

    @Test void eachDaggerDamagesOnceThroughItsOriginalAbilityDespiteForeignMetadata() throws Exception {
        try (var world = new AbilityWorld()) {
            var caster = world.player(0, 64, 0);
            var target = world.player(1, 64, 0);
            for (CoreAbility ability : List.of(PROTOTYPES.newInstance(JedDagger.class), PROTOTYPES.newInstance(ReworkDagger.class))) {
                var owner = (DaggerProjectileOwner) ability;
                var casterField = CoreAbility.class.getDeclaredField("player");
                casterField.setAccessible(true);
                casterField.set(ability, caster);
                var arrowField = ability.getClass().getSuperclass().getDeclaredField("arrows");
                arrowField.setAccessible(true);
                arrowField.set(ability, new ArrayList<Arrow>());
                var arrow = new Arrow() {
                    @Override public Object getShooter() { return caster; }
                    @Override public boolean hasMetadata(String key) { return key.equals(DaggerProjectileOwner.METADATA); }
                    @Override public List<MetadataValue> getMetadata(String key) { return List.of(() -> "1", () -> ability); }
                };
                owner.getArrows().add(arrow);
                var hit = new ProjectileHitEvent();
                hit.setEntity(arrow);
                hit.setHitEntity(target);
                var listener = new ChiListener();
                listener.onProjectileHit(hit);
                assertTrue(hit.isCancelled());
                assertSame(target, ((TestDagger) ability).target());
                assertTrue(owner.getArrows().isEmpty());
                hit.setCancelled(false);
                listener.onProjectileHit(hit);
                assertFalse(hit.isCancelled());
            }
        }
    }

    private interface TestDagger { LivingEntity target(); }
    private static final class ReworkDagger extends me.literka.abilities.DaggerThrow implements TestDagger {
        private LivingEntity target;
        private ReworkDagger() { super(null); }
        @Override public void hitWithDagger(LivingEntity entity, Arrow arrow) { assertNull(target); target = entity; }
        @Override public LivingEntity target() { return target; }
    }
    private static final class JedDagger extends com.jedk1.jedcore.ability.chiblocking.DaggerThrow implements TestDagger {
        private LivingEntity target;
        private JedDagger() { super(null); }
        @Override public void hitWithDagger(LivingEntity entity, Arrow arrow) { assertNull(target); target = entity; }
        @Override public LivingEntity target() { return target; }
    }

    private static final class Binding extends BendingPlayer {
        private CoreAbility selected;
        private final List<Class<?>> attempted = new ArrayList<>();

        private Binding(Player player) { super(player); }
        @Override public CoreAbility getBoundAbility() { return selected; }
        @Override public String getBoundAbilityName() { return selected.getName(); }
        @Override public boolean isElementToggled(Element element) { return true; }
        @Override public boolean canCurrentlyBendWithWeapons() { return true; }
        @Override public boolean canBendIgnoreCooldowns(CoreAbility ability) { return ability == selected; }
        @Override public boolean canBend(CoreAbility ability) {
            attempted.add(ability.getClass());
            return false;
        }
    }

    private static final class EmptyHyperionConfig extends Config {
        private EmptyHyperionConfig() { super("config.yml"); }
        @Override public Config getConfig(BendingPlayer player) { return this; }
        @Override public boolean getBoolean(String path) { return false; }
    }
}
