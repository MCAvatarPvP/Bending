package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.platform.chat.ChatMessageType;
import com.projectkorra.projectkorra.platform.mc.*;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.entity.Projectile;
import com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerAccess;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Rewindable player-facing state; queries, native policy and presentation use session services. */
public final class RollbackPlayerState implements RollbackStateCell<RollbackPlayerState.State> {
    public enum Flag { FLYING, ALLOW_FLIGHT, SNEAKING, SPRINTING, GLIDING, SWIMMING, GLOWING, PICKUP_ITEMS }
    public enum Control { FLYING, ALLOW_FLIGHT, SNEAKING, SPRINTING, GLIDING, SWIMMING, GLOWING, PICKUP_ITEMS, FLY_SPEED, EXPERIENCE, EXHAUSTION }

    public record Controls(Set<Flag> flags, float flySpeed, float experience, float exhaustion) {
        public Controls {
            flags = Set.copyOf(flags);
            if (!Float.isFinite(flySpeed) || flySpeed < -1 || flySpeed > 1 || !Float.isFinite(experience)
                    || experience < 0 || experience > 1 || !Float.isFinite(exhaustion) || exhaustion < 0) {
                throw new IllegalArgumentException("Player controls");
            }
        }
        public boolean has(Flag flag) { return flags.contains(flag); }
        public Controls with(Flag flag, boolean enabled) {
            Set<Flag> next = EnumSet.noneOf(Flag.class);
            next.addAll(flags);
            if (enabled) next.add(flag); else next.remove(flag);
            return new Controls(next, flySpeed, experience, exhaustion);
        }
    }

    /** Captured connection/profile values; runtime changes must be recorded by the session. */
    public record Profile(String displayName, String gameMode, String mainHand, boolean online,
                          boolean operator, boolean playedBefore, int ping) {
        public Profile {
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(gameMode, "gameMode");
            if (!"LEFT".equals(mainHand) && !"RIGHT".equals(mainHand)) throw new IllegalArgumentException("Player main hand");
        }
    }

    /**
     * Mandatory session services. Read captured permissions/geometry, apply native control
     * policy and buffer detached outputs in the current RollbackStep. Implementations may
     * not read live player state or deliver network/UI side effects during replay. Any
     * mutable service state must be included among the domain's registered roots.
     */
    public interface Rules {
        /** Optional explicitly bound policy; transferred duels use their immutable captured decisions. */
        default boolean permission(RollbackPlayerState player, String permission) {
            throw new IllegalStateException("Player has no captured permission policy");
        }
        Controls controls(RollbackPlayerState player, Controls proposed);
        <T extends Projectile> T launch(RollbackPlayerState player, Class<T> type);
        void message(RollbackPlayerState player, ChatMessageType type, String message);
        void sound(RollbackPlayerState player, RollbackEntityBody.Pose position, Sound sound, float volume, float pitch);
        void note(RollbackPlayerState player, RollbackEntityBody.Pose position, Instrument instrument, Note note);
        void blockChange(RollbackPlayerState player, RollbackBlockStore.Position position, BlockData data);
        void particle(RollbackPlayerState player, Particle particle, RollbackEntityBody.Pose position,
                      int count, double x, double y, double z, double extra, Object data, boolean force);
        void displayName(RollbackPlayerState player, String name);
        void scoreboard(RollbackPlayerState player, Scoreboard scoreboard);
    }

    public static final class State {
        private final RollbackPlayerState owner;
        private final Controls controls;
        private final Profile profile;
        private final Set<UUID> hidden;
        private final Scoreboard scoreboard;
        private State(RollbackPlayerState owner) {
            this.owner = owner;
            controls = owner.controls;
            profile = owner.profile;
            hidden = owner.hidden;
            scoreboard = owner.scoreboard;
        }
    }

    /** Ability-facing controls and native movement use the same owned player. */
    public interface Source<S> extends RollbackLivingState.Source<S> {
        Controls readControls();
        void changeControls(RollbackPlayerState player, Control changed, Controls proposed);
        /** Input transition, including captured flight-event policy and native takeoff; distinct from an API setter. */
        boolean requestFlight(boolean flying, boolean cancelled);
        boolean requestGlide();
    }

    private final RollbackLivingState living;
    private final RollbackInventory inventory;
    private final Rules rules;
    private final Source<?> source;
    private final RollbackPlayerAccess.Entry capturedAccess;
    private Controls controls;
    private Profile profile;
    private Set<UUID> hidden;
    private Scoreboard scoreboard;

    public RollbackPlayerState(RollbackLivingState living, RollbackInventory inventory, Controls controls,
                               Profile profile, Set<UUID> hidden, Scoreboard scoreboard, Rules rules) {
        this(living, inventory, Objects.requireNonNull(controls, "controls"), profile, hidden, scoreboard, rules, null);
    }

    public static RollbackPlayerState nativeBacked(RollbackLivingState living, RollbackInventory inventory,
                                                   Profile profile, Set<UUID> hidden, Scoreboard scoreboard,
                                                   Rules rules, Source<?> source) {
        Objects.requireNonNull(source, "source");
        if (living.body().kinematicsSource() != source || living.combatSource() != source || inventory.nativeOwner() != source) {
            throw new IllegalArgumentException("Player controls, movement and inventory must share the exact native backing");
        }
        return new RollbackPlayerState(living, inventory, null, profile, hidden, scoreboard, rules, source);
    }

    private RollbackPlayerState(RollbackLivingState living, RollbackInventory inventory, Controls controls,
                                Profile profile, Set<UUID> hidden, Scoreboard scoreboard, Rules rules, Source<?> source) {
        this(living, inventory, controls, profile, hidden, scoreboard, rules, source, null);
    }

    /** Both loaders use the server's captured permission decisions, not their local policy providers. */
    public static RollbackPlayerState nativeBacked(RollbackLivingState living, RollbackInventory inventory,
                                                   RollbackPlayerAccess.Entry access, Scoreboard scoreboard, Rules rules, Source<?> source) {
        Objects.requireNonNull(source, "source"); Objects.requireNonNull(access, "access");
        if (!access.player().equals(source.identity().uuid())) throw new IllegalArgumentException("Captured access belongs to another player");
        if (living.body().kinematicsSource() != source || living.combatSource() != source || inventory.nativeOwner() != source) {
            throw new IllegalArgumentException("Player controls, movement and inventory must share the exact native backing");
        }
        return new RollbackPlayerState(living, inventory, null, access.profile(), access.hidden(), scoreboard, rules, source, access);
    }

    private RollbackPlayerState(RollbackLivingState living, RollbackInventory inventory, Controls controls,
                                Profile profile, Set<UUID> hidden, Scoreboard scoreboard, Rules rules, Source<?> source, RollbackPlayerAccess.Entry access) {
        this.living = Objects.requireNonNull(living, "living");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        if (!(living.equipment() instanceof RollbackEquipment equipment) || equipment.inventory() != inventory) {
            throw new IllegalArgumentException("Player equipment and inventory must share their slot storage");
        }
        this.controls = controls;
        this.source = source;
        capturedAccess = access;
        this.profile = Objects.requireNonNull(profile, "profile");
        this.hidden = Set.copyOf(hidden);
        requireLogicalScoreboard(scoreboard);
        this.scoreboard = scoreboard;
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    public RollbackLivingState living() { checkThread(); return living; }
    public RollbackInventory inventory() { checkThread(); return inventory; }
    public Player view() { return (Player) living.view(); }
    public Controls controls() { checkThread(); return source == null ? controls : source.readControls(); }
    /** Opaque ownership check for loader execution adapters; never a native player handle. */
    public Source<?> controlSource() { checkThread(); return source; }
    public boolean requestFlight(boolean flying, boolean cancelled) {
        checkThread();
        if (source == null) throw new IllegalStateException("Flight input requires an owned native player");
        return source.requestFlight(flying, cancelled);
    }
    public boolean requestGlide() {
        checkThread();
        if (source == null) throw new IllegalStateException("Glide input requires an owned native player");
        return source.requestGlide();
    }
    public Profile profile() { checkThread(); return profile; }
    /** Native state import bypasses API event dispatch; commands use changeControls. */
    public void controls(Controls value) {
        checkThread();
        if (source != null) throw new IllegalStateException("Import controls through the complete native player state");
        controls = Objects.requireNonNull(value, "controls");
    }
    public void profile(Profile value) { checkThread(); profile = Objects.requireNonNull(value, "profile"); }
    public void hidden(Set<UUID> hidden) { checkThread(); this.hidden = Set.copyOf(hidden); }
    public void flag(Flag flag, boolean value) { changeControls(controls().with(flag, value), Control.valueOf(flag.name())); }
    public void flySpeed(float value) { Controls c = controls(); changeControls(new Controls(c.flags, value, c.experience, c.exhaustion), Control.FLY_SPEED); }
    public void experience(float value) { Controls c = controls(); changeControls(new Controls(c.flags, c.flySpeed, value, c.exhaustion), Control.EXPERIENCE); }
    public void exhaustion(float value) { Controls c = controls(); changeControls(new Controls(c.flags, c.flySpeed, c.experience, value), Control.EXHAUSTION); }
    private void changeControls(Controls proposed, Control changed) {
        checkThread();
        if (source == null) controls = Objects.requireNonNull(rules.controls(this, proposed), "accepted controls");
        else source.changeControls(this, changed, proposed);
    }
    public boolean permission(String permission) {
        checkThread(); Objects.requireNonNull(permission, "permission");
        return capturedAccess == null ? rules.permission(this, permission) : capturedAccess.hasPermission(permission);
    }
    public boolean canSee(Player player) { requireLocal(player); return !hidden.contains(player.getUniqueId()); }
    public boolean lineOfSight(Entity entity) { requireLocal(entity); return RollbackPlayerQueries.lineOfSight(this, entity); }
    public Block exactTarget(int range) {
        checkThread();
        if (range < 0) throw new IllegalArgumentException("Target range");
        return RollbackPlayerQueries.exactTarget(this, range);
    }
    public Block targetBlock(Set<Material> transparent, int range) {
        checkThread();
        if (range < 0) throw new IllegalArgumentException("Target range");
        return RollbackPlayerQueries.targetBlock(this, transparent, range);
    }
    public List<Block> targetBlocks(Set<Material> transparent, int range) {
        checkThread();
        if (range < 0) throw new IllegalArgumentException("Target range");
        return RollbackPlayerQueries.targetBlocks(this, transparent, range);
    }
    public List<Entity> nearby(double x, double y, double z) {
        checkThread();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || x < 0 || y < 0 || z < 0) {
            throw new IllegalArgumentException("Nearby entity query");
        }
        return RollbackPlayerQueries.nearby(this, x, y, z);
    }
    public <T extends Projectile> T launch(Class<T> type) {
        checkThread();
        T projectile = type.cast(rules.launch(this, Objects.requireNonNull(type, "type")));
        requireLocal(projectile);
        return projectile;
    }
    public void message(ChatMessageType type, String value) { checkThread(); rules.message(this, type, value); }
    public void sound(Location location, Sound sound, float volume, float pitch) { rules.sound(this, position(location), sound, volume, pitch); }
    public void note(Location location, Instrument instrument, Note note) { rules.note(this, position(location), instrument, note); }
    public void blockChange(Location location, BlockData blockData) {
        position(location);
        BlockData copy = Objects.requireNonNull(blockData, "blockData").clone();
        if (copy == blockData) throw new IllegalArgumentException("Block data must be detached");
        copy.setExactState(blockData.getExactState());
        rules.blockChange(this, new RollbackBlockStore.Position(location.getBlockX(), location.getBlockY(), location.getBlockZ()), copy);
    }
    public void particle(Particle particle, Location location, int count, double x, double y, double z, double extra, Object data, boolean force) {
        rules.particle(this, particle, position(location), count, x, y, z, extra, data, force);
    }
    public void displayName(String value) {
        Profile p = profile();
        profile = new Profile(value, p.gameMode, p.mainHand, p.online, p.operator, p.playedBefore, p.ping);
        rules.displayName(this, value);
    }
    public Scoreboard scoreboard() { checkThread(); return scoreboard; }
    public void scoreboard(Scoreboard value) {
        checkThread();
        requireLogicalScoreboard(value);
        scoreboard = value;
        rules.scoreboard(this, value);
    }

    @Override public State captureRollbackState() { checkThread(); return new State(this); }
    @Override public void restoreRollbackState(State state) {
        checkThread();
        if (state.owner != this) throw new IllegalArgumentException("Player checkpoint belongs to another player");
        controls = state.controls; profile = state.profile; hidden = state.hidden; scoreboard = state.scoreboard;
    }
    @Override public Collection<?> rollbackReferences() { return List.of(living, inventory, scoreboard); }
    private void checkThread() { living.body().identity(); }
    private RollbackEntityBody.Pose position(Location location) {
        checkThread();
        if (Objects.requireNonNull(location, "location").getWorld() != living.body().world()) {
            throw new IllegalArgumentException("Player operation left the captured logical world");
        }
        return RollbackEntityBody.Pose.from(location);
    }
    private void requireLocal(Entity entity) {
        checkThread();
        if (RollbackEntityBody.logicalBody(entity).world() != living.body().world()) throw new IllegalArgumentException("Entity belongs to another logical world");
    }
    private static void requireLogicalScoreboard(Scoreboard scoreboard) {
        Objects.requireNonNull(scoreboard, "scoreboard");
        if (scoreboard.getClass() != Scoreboard.class && !(scoreboard instanceof RollbackStateCell<?>)) {
            throw new IllegalArgumentException("Scoreboard must contain logical state");
        }
    }
}
