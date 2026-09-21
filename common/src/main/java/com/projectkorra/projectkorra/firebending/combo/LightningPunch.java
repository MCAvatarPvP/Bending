package com.projectkorra.projectkorra.firebending.combo;

import com.jedk1.jedcore.ability.firebending.FirePunch;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.LightningAbility;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.ability.util.ComboManager;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.ability.util.ComboUtil;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.Color;
import com.projectkorra.projectkorra.platform.mc.GameMode;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.prediction.authority.AuthoritativeEffects;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.MovementHandler;
import com.projectkorra.projectkorra.util.ParticleUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A melee lightning fist with separate hit/miss cooldowns and no fire damage. */
public final class LightningPunch extends LightningAbility implements ComboAbility {
    private static final String CONFIG = "Abilities.Fire.LightningPunch.";
    private static final Color FLASH_COLOR = Color.fromRGB(105, 205, 255);
    private static final List<String> INPUT = List.of("LightningBurst:LEFT_CLICK", "FirePunch:SLOT_CHANGE");
    private static final Map<UUID, PendingHit> PENDING_HITS = new HashMap<>();
    @Attribute(Attribute.DAMAGE) private double damage;
    @Attribute(Attribute.COOLDOWN) private long cooldown;
    private long missCooldown;
    private long firePunchCooldown;
    private long stunDuration;
    private int missTicks;
    private LivingEntity target;
    private Location location;

    public LightningPunch(final Player player) {
        super(player);
        if (player == null || bPlayer == null || !isEnabled()
                || hasAbility(player, LightningPunch.class) || !canPrepare(bPlayer, this)) return;
        damage = Math.max(0, getConfig().getDouble(CONFIG + "Damage", 3));
        cooldown = Math.max(0, getConfig().getLong(CONFIG + "Cooldown", 4000));
        missCooldown = Math.max(0, getConfig().getLong(CONFIG + "MissCooldown", 1000));
        firePunchCooldown = Math.max(0, FirePunch.configuredCooldown(bPlayer));
        stunDuration = Math.max(0, getConfig().getLong("Abilities.Fire.Lightning.StunDuration", 1500));
        location = handLocation();
        start();
        if (!isStarted() || isRemoved()) return;
        // The finishing input may already have prepared the ordinary FirePunch.
        for (FirePunch punch : getAbilities(player, FirePunch.class)) punch.remove();
        final PendingHit pending = PENDING_HITS.remove(player.getUniqueId());
        if (pending != null && System.nanoTime() - pending.createdAt < 250_000_000L) queueHit(pending.target);
    }

    private static boolean canPrepare(final BendingPlayer bPlayer, final CoreAbility ability) {
        return !hasLightningBound(bPlayer) && !bPlayer.isOnInputCooldown("FirePunch")
                && bPlayer.canBendIgnoreBinds(ability);
    }

    private static boolean hasLightningBound(final BendingPlayer bPlayer) {
        return bPlayer.getAbilities().values().stream().anyMatch("Lightning"::equalsIgnoreCase);
    }

    /** Selecting FirePunch completes the combo without spending its first melee swing. */
    public static void select(final Player player, final int oneBasedSlot) {
        final BendingPlayer bending = BendingPlayer.getBendingPlayer(player);
        if (bending == null || hasAbility(player, LightningPunch.class) || !bending.canCurrentlyBendWithWeapons()) return;
        final String selected = bending.getAbilities().get(oneBasedSlot);
        if (isPendingFinisher(player, selected, ClickType.SLOT_CHANGE)) {
            // Only record a matching finisher; ordinary slot changes must not interrupt other combos.
            ComboManager.addComboAbility(player, selected, ClickType.SLOT_CHANGE);
        }
    }

    /** Custom click-based combinations still support attacks arriving before their arm swing. */
    public static boolean reserveFinisherHit(final Player player, final LivingEntity target) {
        final BendingPlayer bending = BendingPlayer.getBendingPlayer(player);
        if (bending == null || !isPendingFinisher(player, bending.getBoundAbilityName(), ClickType.LEFT_CLICK)) return false;
        final PendingHit pending = new PendingHit(target, System.nanoTime());
        PENDING_HITS.put(player.getUniqueId(), pending);
        Platform.scheduler().runLater(() -> PENDING_HITS.remove(player.getUniqueId(), pending), 2);
        return true;
    }

    private static boolean isPendingFinisher(final Player player, final String selected, final ClickType finishType) {
        final BendingPlayer bending = BendingPlayer.getBendingPlayer(player);
        final CoreAbility ability = getAbility(LightningPunch.class);
        final var combo = ComboManager.getComboAbility("LightningPunch");
        if (bending == null || ability == null || combo == null || !ability.isEnabled()
                || !"FirePunch".equalsIgnoreCase(selected)
                || !player.hasPermission("bending.ability.LightningPunch") || !canPrepare(bending, ability)) return false;
        final List<AbilityInformation> sequence = combo.getAbilities();
        if (sequence.size() < 2 || !"FirePunch".equalsIgnoreCase(sequence.getLast().getAbilityName())
                || !(sequence.getLast().getClickType() == finishType
                || finishType == ClickType.LEFT_CLICK && leftClick(sequence.getLast().getClickType()))) return false;
        final List<AbilityInformation> recent = ComboManager.getRecentlyUsedAbilities(player, sequence.size() - 1);
        if (recent.size() != sequence.size() - 1) return false;
        for (int i = 0; i < recent.size(); i++) {
            final AbilityInformation actual = recent.get(i), expected = sequence.get(i);
            if (!actual.getAbilityName().equalsIgnoreCase(expected.getAbilityName())
                    || !(actual.getClickType() == expected.getClickType()
                    || expected.getClickType() == ClickType.LEFT_CLICK && leftClick(actual.getClickType()))) return false;
        }
        return true;
    }

    private static boolean leftClick(final ClickType type) {
        return type == ClickType.LEFT_CLICK || type == ClickType.LEFT_CLICK_ENTITY;
    }

    /** Selecting the slot readies the fist; the first melee swing consumes it. */
    public static boolean swing(final Player player) {
        final LightningPunch punch = getAbility(player, LightningPunch.class);
        if (punch == null || punch.isRemoved()
                || !"FirePunch".equalsIgnoreCase(punch.bPlayer.getBoundAbilityName())) return false;
        punch.queueSwing();
        return true;
    }

    public static boolean punch(final Player player, final LivingEntity target) {
        final LightningPunch punch = getAbility(player, LightningPunch.class);
        if (punch == null || punch.isRemoved()) return false;
        if (!"FirePunch".equalsIgnoreCase(punch.bPlayer.getBoundAbilityName())) {
            punch.remove();
            return false;
        }
        punch.queueHit(target);
        // Consume the vanilla/FirePunch hit even when the target is protected.
        return true;
    }

    private void queueHit(final LivingEntity target) {
        queueSwing();
        if (validTarget(target)) this.target = target;
    }

    private void queueSwing() {
        // Attack and arm-swing events arrive in either order across loaders/prediction.
        // Give the matching entity attack time to arrive before deciding it missed.
        if (missTicks == 0) missTicks = 2;
        AbilityActivationManager.markHandled(this);
    }

    private boolean validTarget(final LivingEntity target) {
        if (target == null || target.isDead() || !target.isValid()
                || target.getUniqueId().equals(player.getUniqueId())
                || !target.getWorld().equals(player.getWorld())
                || target instanceof Player other && (!other.isOnline()
                || other.getGameMode() == GameMode.SPECTATOR || Commands.invincible.contains(other.getName()))) return false;
        return player.getEyeLocation().distanceSquared(target.getBoundingBox().getCenter().toLocation(target.getWorld())) <= 16
                && !RegionProtection.isRegionProtected(this, target.getLocation())
                && !GeneralMethods.isObstructed(player.getEyeLocation(), target.getEyeLocation());
    }

    @Override public void progress() {
        if (isRemoved()) return;
        if (!player.isOnline() || player.isDead() || !"FirePunch".equalsIgnoreCase(bPlayer.getBoundAbilityName())
                || !canPrepare(bPlayer, this)) {
            if (missTicks > 0) bPlayer.addCooldown(this, missCooldown);
            remove();
            return;
        }
        location = handLocation();
        if (target == null || !validTarget(target)) {
            target = null;
            if (missTicks > 0 && --missTicks == 0) {
                bPlayer.addCooldown(this, missCooldown);
                remove();
                return;
            }
            playLightningbendingParticle(location, 0, 0, 0);
            return;
        }
        final LivingEntity victim = target;
        target = null;
        location = FirePunch.getImpactLocation(player, victim);
        LightningPunchRing.play(this, victim, location);
        ParticleUtil.spawn(Particle.FLASH, location, 1, 0, 0, 0, 0, FLASH_COLOR);
        ParticleUtil.spawn(Particle.DUST, location, 10, 0.15, 0.15, 0.15, 0,
                new Particle.DustOptions(FLASH_COLOR, 1.25F));
        playLightningbendingSound(location);
        playLightningbendingHitSound(location);
        DamageHandler.damageEntity(victim, damage, this);
        AuthoritativeEffects.run(() -> {
            if (stunDuration > 0) new MovementHandler(victim, this)
                    .stopWithDuration(stunDuration, Element.LIGHTNING.getColor() + "* Electrocuted *");
        });
        bPlayer.addCooldown(this, cooldown);
        bPlayer.addCooldown("FirePunch", firePunchCooldown);
        remove();
    }

    private Location handLocation() {
        return GeneralMethods.getRightSide(player.getLocation(), 0.55).add(0, 1.2, 0)
                .add(player.getLocation().getDirection().multiply(0.8));
    }

    @Override public ArrayList<AbilityInformation> getCombination() {
        final List<String> configured = ConfigManager.getConfig().getStringList(CONFIG + "Combination");
        return ComboUtil.generateCombinationFromList(this, configured.isEmpty() ? INPUT : configured);
    }
    @Override public Object createNewComboInstance(final Player player) { return new LightningPunch(player); }
    @Override public String getName() { return "LightningPunch"; }
    @Override public Location getLocation() { return location; }
    @Override public long getCooldown() { return cooldown; }
    @Override public boolean isHarmlessAbility() { return false; }
    @Override public boolean isSneakAbility() { return false; }

    private record PendingHit(LivingEntity target, long createdAt) { }
}
