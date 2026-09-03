package com.projectkorra.projectkorra.airbending;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.object.GliderColor;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.SoundCategory;
import com.projectkorra.projectkorra.platform.mc.entity.Display;
import com.projectkorra.projectkorra.platform.mc.entity.ItemDisplay;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.inventory.meta.SkullMeta;
import com.projectkorra.projectkorra.platform.mc.util.Transformation;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.state.AbilityCheckpointSync;
import com.projectkorra.projectkorra.util.ParticleUtil;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A momentum-preserving Air Nomad glider. Ordinary AirBlast self-propulsion
 * can be layered into the flight path without a second current subsystem.
 */
public class AirGlider extends AirAbility {
    private static final String ORANGE_TEXTURE = "https://textures.minecraft.net/texture/cbf7797a24a6af875f5c8271c5b8c425e19f372a415e0552fc247763f2859d1";
    private static final String YELLOW_TEXTURE = "https://textures.minecraft.net/texture/27bbd0b2911c96b5d87b2df76691a51b8b12c6fefd523146d8ac5ef1b8ee";
    private static final String WOOD_TEXTURE = "https://textures.minecraft.net/texture/45ac6e6c436d6e137d80482b888569b8181b8b3daa06c047f9751d32ebf8e4c1";
    /** The FIXED item context renders player heads at half scale. */
    private static final float FIXED_HEAD_SCALE = 2.0F;
    private static final float MIN_FOLDED_SCALE = 0.04F;
    private static final int MODEL_TELEPORT_DURATION = 3;

    /*
     * Exactly two sails exist in local model space (+Z is forward):
     *
     *   1. One large FRONT half-circle wing.
     *   2. One smaller REAR half-circle wing.
     *
     * Both straight diameter edges face forward and both circular arcs trail
     * toward negative local Z. There are no independent west/east fans.
     */
    private static final float REAR_FAN_START_ANGLE = (float) Math.toRadians(91.0);
    private static final float REAR_FAN_END_ANGLE = (float) Math.toRadians(269.0);

    private static final float MAIN_WING_PIVOT_Z = 0.58F;
    private static final float MAIN_WING_RADIUS = 2.02F;
    private static final int MAIN_WING_MEMBRANE_STRIPS = 10;
    private static final int MAIN_WING_SPOKE_INTERVALS = 8;
    private static final int MAIN_WING_RIM_SEGMENTS = 6;

    private static final float TAIL_WING_PIVOT_Z = -1.72F;
    private static final float TAIL_WING_RADIUS = 0.72F;
    private static final int TAIL_WING_MEMBRANE_STRIPS = 7;
    private static final int TAIL_WING_SPOKE_INTERVALS = 6;
    private static final int TAIL_WING_RIM_SEGMENTS = 5;

    private static final List<ModelPart> MODEL_PARTS = createModelParts();

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    private long crashCooldown;
    private double staminaMinimum;
    private double deployCost;
    @Attribute(Attribute.SPEED)
    private double poweredDrain;
    private double poweredTurnDrain;
    private double maximumVelocity;
    private int foldLockTicks;
    private int modelDeployTicks;
    private float modelOrientationSmoothing;
    private float maximumBankRadians;
    private double modelHeightOffset;
    private double modelForwardOffset;
    private double visualWingtipSpan;
    private double visualTrailLength;
    private double visualGustAirspeed;
    private int windSoundIntervalTicks;
    private AirGliderPhysics.Settings physics;
    private GliderColor gliderColor;
    private boolean requireItem;

    private State state;
    private int stateTicks;
    private int stallTicks;
    private int recoveryTicks;
    private boolean stalled;
    private boolean crashed;
    private boolean previousGlidingState;
    private boolean ownsGlidingState;
    private long transitionRevision;
    private float modelYaw;
    private float modelPitch;
    private float modelRoll;
    private float targetModelYaw;
    private float targetModelPitch;
    private float targetModelRoll;
    private float modelSpread;
    private float previousYaw;
    private Vector lastFlightVelocity;
    private final List<ItemDisplay> modelDisplays = new ArrayList<>(MODEL_PARTS.size());

    public AirGlider(final Player player) {
        super(player);
        if (player == null || hasAbility(player, AirGlider.class)) return;
        this.loadFields();
        if (!this.selectRequiredItem()) return;
        if (!this.bPlayer.canBend(this) || this.bPlayer.isOnCooldown(this)) return;
        if (GeneralMethods.isOnGround(player) || !this.consumeDeployCost()) return;

        final AirScooter scooter = CoreAbility.getAbility(player, AirScooter.class);
        if (scooter != null) scooter.remove();
        final AirSpout spout = CoreAbility.getAbility(player, AirSpout.class);
        if (spout != null) spout.remove();

        this.state = State.GLIDING;
        this.lastFlightVelocity = player.getVelocity().clone();
        this.previousYaw = player.getLocation().getYaw();
        this.updateModelOrientation(this.lastFlightVelocity);
        this.modelYaw = this.targetModelYaw;
        this.modelPitch = this.targetModelPitch;
        this.modelRoll = this.targetModelRoll;
        this.start();
        if (this.isStarted() && !this.isRemoved()) {
            this.previousGlidingState = player.isGliding();
            this.ownsGlidingState = true;
            player.setGliding(true);
            this.createDisplayModel();
            this.renderTransitionBurst();
            this.playGliderSound(Sound.ENTITY_BREEZE_CHARGE, 0.8F, 1.12F);
            this.playGliderSound(Sound.ITEM_TRIDENT_RIPTIDE_1, 0.42F, 1.36F);
        }
    }

    private AirGlider(final Player player, final PredictionState prediction) {
        super(player);
        if (player == null || prediction == null || hasAbility(player, AirGlider.class)) return;
        this.loadFields();
        final GliderColor predictedColor = GliderColor.getColor(prediction.gliderColor());
        if (predictedColor != null) this.gliderColor = predictedColor;
        this.state = prediction.state();
        this.stateTicks = Math.max(0, prediction.stateTicks());
        this.stalled = prediction.stalled();
        this.stallTicks = Math.max(0, prediction.stallTicks());
        this.recoveryTicks = Math.max(0, prediction.recoveryTicks());
        this.transitionRevision = Math.max(0L, prediction.transitionRevision());
        this.previousGlidingState = prediction.previousGlidingState();
        this.ownsGlidingState = true;
        this.lastFlightVelocity = prediction.lastFlightVelocity();
        this.previousYaw = player.getLocation().getYaw();
        this.updateModelOrientation(this.lastFlightVelocity);
        this.modelYaw = this.targetModelYaw;
        this.modelPitch = this.targetModelPitch;
        this.modelRoll = this.targetModelRoll;
        this.modelSpread = this.state == State.GLIDING ? 1.0F : 0.0F;
        this.start();
        if (this.isStarted() && !this.isRemoved()) {
            GeneralMethods.setVelocity(this, this.player, this.lastFlightVelocity.clone());
            this.player.setGliding(prediction.gliding());
            if (this.state == State.GLIDING) this.createDisplayModel();
        }
    }

    public static boolean toggleGlider(final Player player) {
        final AirGlider active = getAbility(player, AirGlider.class);
        if (active == null) {
            final AirGlider created = new AirGlider(player);
            return created.isStarted() && !created.isRemoved();
        }
        if (active.state == State.GLIDING) {
            active.changeState(State.FOLDED_DIVE);
            return true;
        }
        if (active.state == State.FOLDED_DIVE && active.stateTicks >= active.foldLockTicks
                && active.selectRequiredItem()
                && active.consumeDeployCost()) {
            active.changeState(State.GLIDING);
            return true;
        }
        return false;
    }

    private void loadFields() {
        final String path = "Abilities.Air.AirGlider.";
        final GliderColor selectedColor = this.bPlayer.getGliderColor();
        this.gliderColor = selectedColor == null ? null : GliderColor.getColor(selectedColor.getName());
        if (this.gliderColor == null) this.gliderColor = GliderColor.getDefault();
        this.requireItem = getConfig().getBoolean(path + "RequireItem", false);
        this.cooldown = getConfig().getLong(path + "Cooldown", 1500L);
        this.crashCooldown = getConfig().getLong(path + "CrashCooldown", 3000L);
        this.staminaMinimum = getConfig().getDouble("Abilities.Air.AirBlast.DecayMinimum", 0.2);
        this.deployCost = getConfig().getDouble(path + "DeployCost", 0.05);
        this.poweredDrain = getConfig().getDouble(path + "Glide.PoweredStaminaDrain", 0.0);
        this.poweredTurnDrain = getConfig().getDouble(path + "Glide.PoweredTurnStaminaDrain", 0.0);
        this.maximumVelocity = getConfig().getDouble(path + "Glide.MaximumVelocity", 1.55);
        this.foldLockTicks = Math.max(1, getConfig().getInt(path + "Glide.FoldLockTicks", 6));
        this.modelDeployTicks = Math.max(1, getConfig().getInt(path + "Model.Animation.DeployTicks", 8));
        this.modelOrientationSmoothing = (float) Math.max(0.05, Math.min(1.0,
                getConfig().getDouble(path + "Model.Animation.OrientationSmoothing", 0.30)));
        this.maximumBankRadians = (float) Math.toRadians(Math.max(0, Math.min(75,
                getConfig().getDouble(path + "Model.Animation.MaximumBankDegrees", 35.0))));
        this.modelHeightOffset = getConfig().getDouble(path + "Model.Position.HeightOffset", 0.82);
        this.modelForwardOffset = getConfig().getDouble(path + "Model.Position.ForwardOffset", 0.08);
        this.visualWingtipSpan = Math.max(0.25,
                getConfig().getDouble(path + "Visuals.WingtipSpan", 1.45));
        this.visualTrailLength = Math.max(0.0,
                getConfig().getDouble(path + "Visuals.TrailLength", 0.65));
        this.visualGustAirspeed = Math.max(0.1,
                getConfig().getDouble(path + "Visuals.GustAirspeed", 0.9));
        this.windSoundIntervalTicks = Math.max(5,
                getConfig().getInt(path + "Sound.WindIntervalTicks", 11));
        this.physics = new AirGliderPhysics.Settings(
                getConfig().getDouble(path + "Glide.StraightDrag", 1.008),
                getConfig().getDouble(path + "Glide.Gravity", 0.035),
                getConfig().getDouble(path + "Glide.MinimumAirspeed", 0.24),
                getConfig().getDouble(path + "Glide.FullLiftAirspeed", 0.70),
                getConfig().getDouble(path + "Glide.MaximumCoastLift", 0.035),
                getConfig().getDouble(path + "Glide.PoweredLift", 0.012),
                getConfig().getDouble(path + "Glide.CoastTurnDegrees", 9.0),
                getConfig().getDouble(path + "Glide.PoweredTurnDegrees", 13.0),
                getConfig().getDouble(path + "Glide.TurnDragPerDegree", 0.0012),
                getConfig().getDouble(path + "Glide.StallLiftFactor", 0.12),
                getConfig().getDouble(path + "Glide.HorizontalAcceleration", 0.022),
                getConfig().getDouble(path + "Glide.DiveAcceleration", 0.065),
                getConfig().getDouble(path + "Glide.DescentConversion", 0.55),
                this.maximumVelocity);
    }

    @Override
    public void progress() {
        if (this.player.isDead() || !this.player.isOnline()
                || !this.player.getWorld().equals(this.getLocation().getWorld())
                || !this.bPlayer.canBendIgnoreBindsCooldowns(this)) {
            this.remove();
            return;
        }
        this.stateTicks++;
        switch (this.state) {
            case GLIDING -> this.progressGlide();
            case FOLDED_DIVE -> this.progressFoldedDive();
        }
    }

    private void progressGlide() {
        if (GeneralMethods.isOnGround(this.player)) {
            this.land();
            return;
        }
        if (GeneralMethods.isSolid(this.player.getEyeLocation().getBlock())) {
            this.crashed = true;
            this.remove();
            return;
        }
        // Vanilla can clear fall-flying when no elytra is equipped, so the
        // ability deliberately reasserts the state while the model is open.
        this.player.setGliding(true);

        final boolean exhausted = this.bPlayer.getAirBlastDecay() <= this.staminaMinimum + 1.0E-6;
        final boolean powered = this.player.isSprinting() && !exhausted;
        final Vector before = this.player.getVelocity().clone();
        final AirGliderPhysics.StepResult step = AirGliderPhysics.step(before,
                this.player.getEyeLocation().getDirection(), powered, exhausted, this.stalled, this.physics);
        final Vector next = step.velocity();
        this.updateStall(step);

        if (powered) {
            this.consumeRate(this.poweredDrain);
            if (before.angle(this.player.getEyeLocation().getDirection()) > Math.toRadians(5)) {
                this.consumeRate(this.poweredTurnDrain);
            }
        }

        GeneralMethods.setVelocity(this, this.player, next);
        this.lastFlightVelocity = next.clone();
        this.player.setFallDistance(0);
        this.updateModelOrientation(next);
        this.advanceModelAnimation(true);
        this.updateDisplayModel();
        this.renderFlightWake(next, false);
        this.playFlightWind(next, false);
    }

    private void updateStall(final AirGliderPhysics.StepResult step) {
        final boolean unsafe = step.airspeed() < this.physics.minimumAirspeed()
                || step.attackAngleDegrees() > 35.0;
        if (!this.stalled) {
            this.stallTicks = unsafe ? this.stallTicks + 1 : Math.max(0, this.stallTicks - 1);
            if (this.stallTicks >= 6) {
                this.stalled = true;
                this.recoveryTicks = 0;
                this.playGliderSound(Sound.ENTITY_BREEZE_HURT, 0.72F, 0.62F);
                this.publishTransition();
            }
            return;
        }
        final boolean recovering = step.airspeed() > 0.38
                && this.player.getEyeLocation().getDirection().getY() < -0.15;
        this.recoveryTicks = recovering ? this.recoveryTicks + 1 : 0;
        if (this.recoveryTicks >= 5) {
            this.stalled = false;
            this.stallTicks = 0;
            this.playGliderSound(Sound.ENTITY_BREEZE_CHARGE, 0.6F, 1.28F);
            this.publishTransition();
        }
    }

    private void progressFoldedDive() {
        if (GeneralMethods.isOnGround(this.player)) {
            this.land();
            return;
        }
        final Vector velocity = this.player.getVelocity().clone().multiply(0.998);
        velocity.setY(velocity.getY() - 0.06);
        GeneralMethods.setVelocity(this, this.player, velocity);
        this.lastFlightVelocity = velocity.clone();
        this.player.setGliding(false);
        this.updateModelOrientation(velocity);
        this.advanceModelAnimation(false);
        this.updateDisplayModel();
        this.renderFlightWake(velocity, true);
        this.playFlightWind(velocity, true);
        if (this.modelSpread <= 0.001F) this.destroyDisplayModel();
    }

    private void land() {
        final Vector velocity = this.lastFlightVelocity == null
                ? this.player.getVelocity().clone() : this.lastFlightVelocity.clone();
        final Vector horizontal = velocity.clone().setY(0);
        final Vector facing = this.player.getEyeLocation().getDirection().setY(0);
        final boolean aligned = horizontal.lengthSquared() < 1.0E-9 || facing.lengthSquared() < 1.0E-9
                || horizontal.angle(facing) <= Math.toRadians(25);
        final boolean safeVertical = velocity.getY() >= -0.65;
        final double retention = aligned && safeVertical ? 0.85 : 0.35;
        horizontal.multiply(retention).setY(velocity.getY());
        GeneralMethods.setVelocity(this, this.player, horizontal);
        this.destroyDisplayModel();
        this.crashed = velocity.getY() < -1.0 || velocity.length() > 0.90;
        if (!this.crashed) this.playGliderSound(Sound.ENTITY_BREEZE_LAND, 0.65F, 0.86F);
        this.remove();
    }

    private boolean consumeDeployCost() {
        if (this.bPlayer.getAirBlastDecay() <= this.staminaMinimum + this.deployCost) return false;
        this.bPlayer.increaseAirBlastDecay(this.deployCost, this.staminaMinimum);
        this.bPlayer.resetAirBlast();
        return true;
    }

    private void consumeRate(final double perSecond) {
        if (perSecond <= 0 || this.bPlayer.getAirBlastDecay() <= this.staminaMinimum) return;
        this.bPlayer.increaseAirBlastDecay(perSecond / 20.0, this.staminaMinimum);
        this.bPlayer.resetAirBlast();
    }

    private void changeState(final State next) {
        if (this.state == next) return;
        this.state = next;
        this.stateTicks = 0;
        if (next == State.GLIDING) {
            this.player.setGliding(true);
            this.createDisplayModel();
            this.renderTransitionBurst();
            this.playGliderSound(Sound.ENTITY_BREEZE_CHARGE, 0.72F, 1.18F);
        } else {
            this.player.setGliding(false);
            this.renderTransitionBurst();
            this.playGliderSound(Sound.ENTITY_BREEZE_INHALE, 0.68F, 0.72F);
        }
        this.publishTransition();
    }

    private void renderFlightWake(final Vector velocity, final boolean diving) {
        Vector direction = velocity == null ? new Vector() : velocity.clone();
        if (direction.lengthSquared() <= 1.0E-9) {
            direction = this.player.getEyeLocation().getDirection().clone();
        }
        if (direction.lengthSquared() <= 1.0E-9) return;
        direction.normalize();
        Vector right = new Vector(-direction.getZ(), 0, direction.getX());
        if (right.lengthSquared() <= 1.0E-9) {
            final Vector facing = this.player.getEyeLocation().getDirection().clone().setY(0);
            right = new Vector(-facing.getZ(), 0, facing.getX());
        }
        if (right.lengthSquared() <= 1.0E-9) return;
        right.normalize();

        final Location wake = this.player.getLocation().clone().add(0, 0.78, 0)
                .add(direction.clone().multiply(-this.visualTrailLength));
        final double span = diving ? Math.max(0.18, this.visualWingtipSpan * this.modelSpread)
                : this.visualWingtipSpan * Math.max(0.3, this.modelSpread);
        final Location left = wake.clone().add(right.clone().multiply(span));
        final Location rightTip = wake.clone().add(right.clone().multiply(-span));
        this.playAirbendingParticles(left, 1, 0.035, 0.035, 0.035, 0.0);
        this.playAirbendingParticles(rightTip, 1, 0.035, 0.035, 0.035, 0.0);
        if ((this.getRunningTicks() & 1L) == 0L) {
            this.playAirbendingParticles(wake, diving ? 2 : 1, 0.12, 0.08, 0.12, 0.01);
        }
        if (velocity != null && velocity.length() >= this.visualGustAirspeed
                && this.getRunningTicks() % 5L == 0L) {
            ParticleUtil.spawn(Particle.SMALL_GUST, wake, 1, 0.08, 0.05, 0.08, 0.0);
        }
    }

    private void renderTransitionBurst() {
        final Location center = this.player.getLocation().clone().add(0, 0.75, 0);
        this.playAirbendingParticles(center, 14, 0.75, 0.32, 0.75, 0.035);
        ParticleUtil.spawn(Particle.SMALL_GUST, center, 2, 0.35, 0.16, 0.35, 0.0);
    }

    private void playFlightWind(final Vector velocity, final boolean diving) {
        if (this.getRunningTicks() % this.windSoundIntervalTicks != 0L) return;
        final double speed = velocity == null ? 0.0 : velocity.length();
        final float pitch = (float) Math.max(0.55, Math.min(1.45,
                0.68 + speed * 0.38 + (diving ? -0.08 : 0.0)));
        final float volume = (float) Math.max(0.18, Math.min(0.5, 0.16 + speed * 0.18));
        this.playGliderSound(diving ? Sound.ENTITY_BREEZE_INHALE
                : Sound.ENTITY_BREEZE_IDLE_GROUND, volume, pitch);
    }

    private void playGliderSound(final Sound sound, final float volume, final float pitch) {
        if (sound == null || this.player == null || this.player.getWorld() == null
                || !getConfig().getBoolean("Properties.Air.PlaySound")) return;
        AbilityExecutionContext.run(this, () -> this.player.getWorld().playSound(
                this.player.getLocation(), sound, SoundCategory.MASTER, volume, pitch));
    }

    private void publishTransition() {
        this.transitionRevision++;
        AbilityCheckpointSync.publish(this);
    }

    public PredictionState capturePredictionState() {
        if (!this.isStarted() || this.isRemoved() || this.state == null) return null;
        final Vector velocity = this.lastFlightVelocity == null
                ? this.player.getVelocity().clone() : this.lastFlightVelocity.clone();
        return new PredictionState(this.state, this.stateTicks, this.stalled,
                this.stallTicks, this.recoveryTicks, this.transitionRevision,
                velocity.getX(), velocity.getY(), velocity.getZ(),
                this.player.isGliding(), this.previousGlidingState,
                this.gliderColor == null ? "classic" : this.gliderColor.getName());
    }

    public static AirGlider restorePredictionState(final Player player, final PredictionState prediction) {
        final AirGlider existing = getAbility(player, AirGlider.class);
        if (existing != null) {
            existing.applyPredictionState(prediction);
            return existing;
        }
        final AirGlider restored = new AirGlider(player, prediction);
        return restored.isStarted() && !restored.isRemoved() ? restored : null;
    }

    public void applyPredictionState(final PredictionState prediction) {
        if (prediction == null || this.isRemoved()
                || prediction.transitionRevision() < this.transitionRevision) return;
        final GliderColor authoritativeColor = GliderColor.getColor(prediction.gliderColor());
        final boolean colorChanged = authoritativeColor != null
                && (this.gliderColor == null
                || !authoritativeColor.getName().equals(this.gliderColor.getName()));
        if (colorChanged) {
            this.gliderColor = authoritativeColor;
            this.destroyDisplayModel();
        }
        this.state = prediction.state();
        this.stateTicks = Math.max(0, prediction.stateTicks());
        this.stalled = prediction.stalled();
        this.stallTicks = Math.max(0, prediction.stallTicks());
        this.recoveryTicks = Math.max(0, prediction.recoveryTicks());
        this.transitionRevision = prediction.transitionRevision();
        this.previousGlidingState = prediction.previousGlidingState();
        this.lastFlightVelocity = prediction.lastFlightVelocity();
        GeneralMethods.setVelocity(this, this.player, this.lastFlightVelocity.clone());
        this.player.setGliding(prediction.gliding());
        if (this.state == State.GLIDING) {
            this.createDisplayModel();
        } else if (this.modelSpread <= 0.001F) {
            this.destroyDisplayModel();
        }
    }

    /**
     * Sparse checkpoints arrive several simulation ticks after they were
     * captured. Matching their counters or velocity would rewind a healthy
     * local flight once per network round trip; only transition identity is
     * safe to compare without a complete input-history replay.
     */
    public boolean confirmsPredictionTransition(final PredictionState prediction) {
        return prediction != null && this.state == prediction.state()
                && this.transitionRevision == prediction.transitionRevision()
                && this.gliderColor != null
                && this.gliderColor.getName().equals(prediction.gliderColor());
    }

    private boolean selectRequiredItem() {
        if (!this.requireItem) return true;
        final GliderColor itemColor = AirGliderItem.getHeldColor(this.player);
        if (itemColor == null) return false;
        this.gliderColor = itemColor;
        return true;
    }

    private void createDisplayModel() {
        if (!this.modelDisplays.isEmpty() || this.player.getWorld() == null) return;
        final Location center = this.modelCenter();
        final Quaternionf rotation = this.modelRotation();
        for (final ModelPart part : MODEL_PARTS) {
            final ItemDisplay display = this.player.getWorld().spawn(this.modelPartLocation(center, part, rotation), ItemDisplay.class);
            display.setItemStack(this.texturedHead(part.texture));
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            display.setPersistent(false);
            display.setInvulnerable(true);
            display.setGravity(false);
            display.setSilent(true);
            display.setBillboard(Display.Billboard.FIXED);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setShadowRadius(0);
            display.setShadowStrength(0);
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(Math.max(2, Math.min(10, this.modelDeployTicks / 2)));
            display.setTeleportDuration(MODEL_TELEPORT_DURATION);
            display.setViewRange(32);
            display.setTransformation(this.modelPartTransformation(part, rotation));
            this.modelDisplays.add(display);
        }
    }

    private ItemStack texturedHead(final Texture texture) {
        final String path = "Abilities.Air.AirGlider.Model.";
        final String fallback = switch (texture) {
            case ORANGE -> this.gliderColor == null ? ORANGE_TEXTURE : this.gliderColor.getOuterTexture();
            case YELLOW -> this.gliderColor == null ? YELLOW_TEXTURE : this.gliderColor.getInnerTexture();
            case WOOD -> WOOD_TEXTURE;
        };
        final String url = texture == Texture.WOOD
                ? getConfig().getString(path + texture.configKey, fallback) : fallback;
        final ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        final SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setProfileId(UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)));
        meta.setTextureUrl(url);
        item.setItemMeta(meta);
        return item;
    }

    private void updateDisplayModel() {
        if (this.modelDisplays.size() != MODEL_PARTS.size()) return;
        final Location center = this.modelCenter();
        final Quaternionf rotation = this.modelRotation();
        for (int index = 0; index < MODEL_PARTS.size(); index++) {
            final ItemDisplay display = this.modelDisplays.get(index);
            final ModelPart part = MODEL_PARTS.get(index);
            if (display != null && display.isValid()) {
                display.teleport(this.modelPartLocation(center, part, rotation));
                display.setTransformation(this.modelPartTransformation(part, rotation));
            }
        }
    }

    private Location modelCenter() {
        final Vector forward = this.player.getEyeLocation().getDirection().clone();
        if (forward.lengthSquared() > 1.0E-9) forward.normalize().multiply(this.modelForwardOffset);
        return this.player.getLocation().clone().add(0, this.modelHeightOffset, 0).add(forward);
    }

    private Location modelPartLocation(final Location center, final ModelPart part, final Quaternionf rotation) {
        final float spread = this.partSpread(part);
        final Vector3f offset = new Vector3f(
                lerp(part.foldedX, part.x, spread),
                part.y,
                lerp(part.foldedZ, part.z, spread));
        rotation.transform(offset);
        final Location location = center.clone().add(offset.x, offset.y, offset.z);
        location.setYaw(0);
        location.setPitch(0);
        return location;
    }

    private Transformation modelPartTransformation(final ModelPart part, final Quaternionf rotation) {
        final float spread = this.partSpread(part);
        final float visibleSpread = part.foldMode == FoldMode.FIXED
                ? 1.0F : Math.max(MIN_FOLDED_SCALE, spread);
        final float widthScale = part.foldMode == FoldMode.RADIAL ? visibleSpread : 1.0F;
        final float lengthScale = part.foldMode == FoldMode.TANGENTIAL ? visibleSpread : 1.0F;
        final float yaw = interpolateAngle(part.foldedYaw, part.yaw, spread);
        final Quaternionf partRotation = new Quaternionf(rotation)
                .rotateY(yaw)
                .rotateX(part.pitch)
                .rotateZ(part.roll);
        return new Transformation(new Vector3f(), partRotation,
                new Vector3f(part.width * FIXED_HEAD_SCALE * widthScale,
                        part.height * FIXED_HEAD_SCALE,
                        part.length * FIXED_HEAD_SCALE * lengthScale), new Quaternionf());
    }

    private float partSpread(final ModelPart part) {
        if (part.foldMode == FoldMode.FIXED) return 1.0F;
        return Math.max(0.0F, Math.min(1.0F, this.modelSpread));
    }

    private static float lerp(final float from, final float to, final float amount) {
        return from + (to - from) * amount;
    }

    private static float interpolateAngle(final float from, final float to, final float amount) {
        return from + normalizeRadians(to - from) * amount;
    }

    private void updateModelOrientation(final Vector velocity) {
        final Vector facing = velocity.lengthSquared() < 1.0E-9
                ? this.player.getEyeLocation().getDirection() : velocity.clone().normalize();
        final double horizontal = Math.hypot(facing.getX(), facing.getZ());
        this.targetModelYaw = (float) Math.atan2(facing.getX(), facing.getZ());
        this.targetModelPitch = (float) Math.atan2(-facing.getY(), horizontal);
        final float currentYaw = this.player.getLocation().getYaw();
        float delta = currentYaw - this.previousYaw;
        while (delta > 180) delta -= 360;
        while (delta < -180) delta += 360;
        this.previousYaw = currentYaw;
        this.targetModelRoll = Math.max(-this.maximumBankRadians,
                Math.min(this.maximumBankRadians, (float) Math.toRadians(-delta * 2.5)));
        if (this.stalled) this.targetModelRoll += (float) Math.sin(this.getRunningTicks() * 1.7) * 0.12F;
    }

    private void advanceModelAnimation(final boolean opening) {
        this.modelYaw = approachAngle(this.modelYaw, this.targetModelYaw, this.modelOrientationSmoothing);
        this.modelPitch += (this.targetModelPitch - this.modelPitch) * this.modelOrientationSmoothing;
        this.modelRoll += (this.targetModelRoll - this.modelRoll) * this.modelOrientationSmoothing;
        final float targetSpread = opening ? 1.0F : 0.0F;
        final float spreadStep = 1.0F / this.modelDeployTicks;
        if (this.modelSpread < targetSpread) {
            this.modelSpread = Math.min(targetSpread, this.modelSpread + spreadStep);
        } else if (this.modelSpread > targetSpread) {
            this.modelSpread = Math.max(targetSpread, this.modelSpread - spreadStep);
        }
    }

    private static float approachAngle(final float current, final float target, final float amount) {
        float delta = target - current;
        while (delta > Math.PI) delta -= (float) (Math.PI * 2.0);
        while (delta < -Math.PI) delta += (float) (Math.PI * 2.0);
        return current + delta * amount;
    }

    private Quaternionf modelRotation() {
        return new Quaternionf().rotateY(this.modelYaw).rotateX(this.modelPitch).rotateZ(this.modelRoll);
    }

    private void destroyDisplayModel() {
        for (final ItemDisplay display : this.modelDisplays) {
            if (display != null && display.isValid()) display.remove();
        }
        this.modelDisplays.clear();
    }

    @Override
    public void remove() {
        super.remove();
        if (!this.isRemoved()) return;
        this.destroyDisplayModel();
        if (this.ownsGlidingState) {
            this.player.setGliding(this.previousGlidingState);
            this.ownsGlidingState = false;
        }
        if (this.crashed) {
            this.renderTransitionBurst();
            this.playGliderSound(Sound.ENTITY_BREEZE_WIND_BURST, 1.0F, 0.52F);
            this.playGliderSound(Sound.ENTITY_GENERIC_BIG_FALL, 0.72F, 0.8F);
        }
        this.bPlayer.addCooldown(this, this.crashed ? this.crashCooldown : this.cooldown);
    }

    @Override
    public String getName() {
        return "AirGlider";
    }

    @Override
    public Location getLocation() {
        return this.player == null ? null : this.player.getLocation();
    }

    @Override
    public long getCooldown() {
        return this.cooldown;
    }

    @Override
    public boolean isSneakAbility() {
        return true;
    }

    @Override
    public boolean isHarmlessAbility() {
        return true;
    }

    public State getState() {
        return this.state;
    }

    public boolean isStalled() {
        return this.stalled;
    }

    public int getDisplayCount() {
        return this.modelDisplays.size();
    }

    public long getTransitionRevision() {
        return this.transitionRevision;
    }

    public enum State { GLIDING, FOLDED_DIVE }

    public record PredictionState(State state, int stateTicks, boolean stalled,
                                  int stallTicks, int recoveryTicks, long transitionRevision,
                                  double velocityX, double velocityY, double velocityZ,
                                  boolean gliding, boolean previousGlidingState,
                                  String gliderColor) {
        public PredictionState {
            if (state == null) state = State.GLIDING;
            if (gliderColor == null || gliderColor.isBlank()) gliderColor = "classic";
            if (!Double.isFinite(velocityX)) velocityX = 0.0;
            if (!Double.isFinite(velocityY)) velocityY = 0.0;
            if (!Double.isFinite(velocityZ)) velocityZ = 0.0;
        }

        public Vector lastFlightVelocity() {
            return new Vector(this.velocityX, this.velocityY, this.velocityZ);
        }
    }

    private static List<ModelPart> createModelParts() {
        final List<ModelPart> parts = new ArrayList<>();

        // Fabric first, then ribs and perimeter above it. These calls create
        // exactly two complete wings: one front half-disc and one rear half-disc.
        addRearFacingHalfDisc(parts,
                MAIN_WING_PIVOT_Z, MAIN_WING_RADIUS,
                MAIN_WING_MEMBRANE_STRIPS,
                MAIN_WING_SPOKE_INTERVALS,
                MAIN_WING_RIM_SEGMENTS,
                -0.020F, 0.050F);
        addRearFacingHalfDisc(parts,
                TAIL_WING_PIVOT_Z, TAIL_WING_RADIUS,
                TAIL_WING_MEMBRANE_STRIPS,
                TAIL_WING_SPOKE_INTERVALS,
                TAIL_WING_RIM_SEGMENTS,
                -0.016F, 0.048F);

        // Long center staff through both wings, projecting beyond the nose and
        // the tail as in the reference silhouette.
        parts.add(fixedPart(0.0F, 0.105F, -0.12F,
                0.10F, 0.10F, 5.10F,
                0.0F, 0.0F, 0.0F, Texture.WOOD));

        // Compact pivot caps. They do not form additional sail surfaces.
        parts.add(fixedPart(0.0F, 0.038F, MAIN_WING_PIVOT_Z,
                0.30F, 0.080F, 0.30F,
                0.0F, 0.0F, 0.0F, Texture.YELLOW));
        parts.add(fixedPart(0.0F, 0.040F, TAIL_WING_PIVOT_Z,
                0.18F, 0.072F, 0.18F,
                0.0F, 0.0F, 0.0F, Texture.WOOD));

        // Small triangular rider frame in front of the main wing. All orange
        // membrane remains behind MAIN_WING_PIVOT_Z.
        final float axleZ = MAIN_WING_PIVOT_Z + 0.10F;
        final float handBarZ = MAIN_WING_PIVOT_Z + 0.72F;
        parts.add(foldingPart(0.0F, 0.125F, axleZ,
                0.90F, 0.085F, 0.085F,
                0.0F, 0.0F, 0.0F,
                Texture.WOOD, FoldMode.RADIAL, MAIN_WING_PIVOT_Z));
        parts.add(foldingPart(0.0F, -0.030F, handBarZ,
                1.22F, 0.080F, 0.085F,
                0.0F, 0.0F, 0.0F,
                Texture.WOOD, FoldMode.RADIAL, MAIN_WING_PIVOT_Z));
        addBeam(parts,
                -0.61F, -0.030F, handBarZ,
                0.0F, 0.125F, axleZ,
                0.062F, Texture.WOOD, FoldMode.RADIAL, MAIN_WING_PIVOT_Z);
        addBeam(parts,
                0.61F, -0.030F, handBarZ,
                0.0F, 0.125F, axleZ,
                0.062F, Texture.WOOD, FoldMode.RADIAL, MAIN_WING_PIVOT_Z);

        return List.copyOf(parts);
    }

    /**
     * Builds one complete half-circle sail. Its membrane, ribs and curved rim
     * are all at z <= pivotZ, so its arc always faces the rear of the glider.
     */
    private static void addRearFacingHalfDisc(final List<ModelPart> parts,
                                              final float pivotZ,
                                              final float radius,
                                              final int membraneStrips,
                                              final int spokeIntervals,
                                              final int rimSegments,
                                              final float fabricY,
                                              final float fabricHeight) {
        addRearFacingMembrane(parts, pivotZ, radius,
                membraneStrips, fabricY, fabricHeight, Texture.ORANGE);

        final float frameY = 0.058F;

        // Straight diameter/leading edge. It is one continuous bar across the
        // wing rather than two fan bases pointing west and east.
        addBeam(parts,
                -radius, frameY, pivotZ,
                radius, frameY, pivotZ,
                0.058F, 0.072F,
                Texture.WOOD, FoldMode.TANGENTIAL, pivotZ);

        // Interior fan ribs. The diameter supplies the two outer boundaries.
        final float spokeStep = (REAR_FAN_END_ANGLE - REAR_FAN_START_ANGLE)
                / spokeIntervals;
        for (int index = 1; index < spokeIntervals; index++) {
            final float angle = REAR_FAN_START_ANGLE + spokeStep * index;
            addRadialPart(parts, angle,
                    0.075F, radius + 0.022F,
                    0.054F, frameY, 0.070F,
                    Texture.WOOD, FoldMode.RADIAL, pivotZ);
        }

        // Segmented chords form the single curved trailing perimeter.
        final float rimStep = (REAR_FAN_END_ANGLE - REAR_FAN_START_ANGLE)
                / rimSegments;
        for (int index = 0; index < rimSegments; index++) {
            final float angleA = REAR_FAN_START_ANGLE + rimStep * index;
            final float angleB = angleA + rimStep;
            addArcBeam(parts, pivotZ, radius,
                    angleA, angleB, frameY,
                    0.058F, 0.072F, Texture.WOOD);
        }
    }

    /**
     * Approximates a rear-facing half-disc with transverse strips. This gives
     * one continuous semicircular silhouette instead of two radial side fans.
     * The width tapers monotonically toward the rear-most point.
     */
    private static void addRearFacingMembrane(final List<ModelPart> parts,
                                              final float pivotZ,
                                              final float radius,
                                              final int stripCount,
                                              final float y,
                                              final float height,
                                              final Texture texture) {
        final float stripDepth = radius / stripCount;
        for (int index = 0; index < stripCount; index++) {
            final float frontDepth = stripDepth * index;
            final float rearDepth = stripDepth * (index + 1);

            // Neighboring strips overlap slightly, but the first strip never
            // crosses the leading edge and the last never crosses the arc tip.
            final float visibleFrontDepth = frontDepth - (index == 0 ? 0.0F : 0.006F);
            final float visibleRearDepth = rearDepth
                    + (index == stripCount - 1 ? 0.0F : 0.006F);
            final float centerDepth = (visibleFrontDepth + visibleRearDepth) * 0.5F;
            final float visibleDepth = visibleRearDepth - visibleFrontDepth;

            // Sample near each strip's rear edge so the rectangular piece stays
            // within the intended circular outline instead of bulging forward.
            final float widthSampleDepth = Math.min(radius * 0.998F,
                    frontDepth + stripDepth * 0.90F);
            final float halfWidth = (float) Math.sqrt(Math.max(0.0F,
                    radius * radius - widthSampleDepth * widthSampleDepth));
            final float width = Math.max(0.10F, halfWidth * 2.0F);
            final float z = pivotZ - centerDepth;
            final float stripY = y + ((index & 1) == 0 ? -0.0015F : 0.0015F);

            parts.add(foldingPart(0.0F, stripY, z,
                    width, height, visibleDepth,
                    0.0F, 0.0F, 0.0F,
                    texture, FoldMode.RADIAL, pivotZ));
        }
    }

    private static void addRadialPart(final List<ModelPart> parts,
                                      final float angle,
                                      final float innerRadius, final float outerRadius,
                                      final float width, final float y, final float height,
                                      final Texture texture, final FoldMode foldMode,
                                      final float pivotZ) {
        final float centerRadius = (innerRadius + outerRadius) * 0.5F;
        final float x = (float) Math.sin(angle) * centerRadius;
        final float z = pivotZ + (float) Math.cos(angle) * centerRadius;
        final float length = Math.max(0.01F, outerRadius - innerRadius);
        parts.add(foldingPart(x, y, z, width, height, length,
                0.0F, angle, 0.0F, texture, foldMode, pivotZ));
    }

    private static void addArcBeam(final List<ModelPart> parts,
                                   final float pivotZ, final float radius,
                                   final float angleA, final float angleB,
                                   final float y,
                                   final float thickness, final float height,
                                   final Texture texture) {
        final float x1 = (float) Math.sin(angleA) * radius;
        final float z1 = pivotZ + (float) Math.cos(angleA) * radius;
        final float x2 = (float) Math.sin(angleB) * radius;
        final float z2 = pivotZ + (float) Math.cos(angleB) * radius;
        addBeam(parts, x1, y, z1, x2, y, z2,
                thickness, height, texture, FoldMode.TANGENTIAL, pivotZ);
    }

    private static void addBeam(final List<ModelPart> parts,
                                final float x1, final float y1, final float z1,
                                final float x2, final float y2, final float z2,
                                final float thickness, final Texture texture,
                                final FoldMode foldMode, final float pivotZ) {
        addBeam(parts, x1, y1, z1, x2, y2, z2,
                thickness, thickness, texture, foldMode, pivotZ);
    }

    private static void addBeam(final List<ModelPart> parts,
                                final float x1, final float y1, final float z1,
                                final float x2, final float y2, final float z2,
                                final float width, final float height,
                                final Texture texture, final FoldMode foldMode,
                                final float pivotZ) {
        final float dx = x2 - x1;
        final float dy = y2 - y1;
        final float dz = z2 - z1;
        final float horizontal = (float) Math.hypot(dx, dz);
        final float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= 1.0E-5F) return;

        final float x = (x1 + x2) * 0.5F;
        final float y = (y1 + y2) * 0.5F;
        final float z = (z1 + z2) * 0.5F;
        final float yaw = (float) Math.atan2(dx, dz);
        final float pitch = (float) Math.atan2(-dy, horizontal);
        if (foldMode == FoldMode.FIXED) {
            parts.add(fixedPart(x, y, z, width, height, length,
                    pitch, yaw, 0.0F, texture));
        } else {
            parts.add(foldingPart(x, y, z, width, height, length,
                    pitch, yaw, 0.0F, texture, foldMode, pivotZ));
        }
    }

    private static ModelPart fixedPart(final float x, final float y, final float z,
                                       final float width, final float height, final float length,
                                       final float pitch, final float yaw, final float roll,
                                       final Texture texture) {
        final float normalizedYaw = normalizeRadians(yaw);
        return new ModelPart(x, y, z, width, height, length,
                pitch, normalizedYaw, roll,
                x, z, normalizedYaw, texture, FoldMode.FIXED);
    }

    private static ModelPart foldingPart(final float x, final float y, final float z,
                                         final float width, final float height, final float length,
                                         final float pitch, final float yaw, final float roll,
                                         final Texture texture, final FoldMode foldMode,
                                         final float pivotZ) {
        final float dz = z - pivotZ;
        final float radius = (float) Math.hypot(x, dz);
        final float axialDirection = dz >= 0.0F ? 1.0F : -1.0F;
        final float foldedZ = pivotZ + axialDirection * radius;
        final float foldedYaw = axialDirection > 0.0F
                ? 0.0F : (x >= 0.0F ? (float) Math.PI : (float) -Math.PI);
        final float alignedYaw = alignAxisToReference(yaw, foldedYaw);
        return new ModelPart(x, y, z, width, height, length,
                pitch, alignedYaw, roll,
                0.0F, foldedZ, foldedYaw, texture, foldMode);
    }

    private static float alignAxisToReference(final float angle, final float reference) {
        final float primary = normalizeRadians(angle);
        final float reversed = normalizeRadians(angle + (float) Math.PI);
        return Math.abs(normalizeRadians(primary - reference))
                <= Math.abs(normalizeRadians(reversed - reference)) ? primary : reversed;
    }

    private static float normalizeRadians(float angle) {
        while (angle > Math.PI) angle -= (float) (Math.PI * 2.0);
        while (angle < -Math.PI) angle += (float) (Math.PI * 2.0);
        return angle;
    }

    private enum Texture {
        ORANGE("OrangeTexture"), YELLOW("YellowTexture"), WOOD("WoodTexture");
        private final String configKey;
        Texture(final String configKey) { this.configKey = configKey; }
    }

    private enum FoldMode { FIXED, RADIAL, TANGENTIAL }

    private record ModelPart(float x, float y, float z,
                             float width, float height, float length,
                             float pitch, float yaw, float roll,
                             float foldedX, float foldedZ, float foldedYaw,
                             Texture texture, FoldMode foldMode) {
    }
}
