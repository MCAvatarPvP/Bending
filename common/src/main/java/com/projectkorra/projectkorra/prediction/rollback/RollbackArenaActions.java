package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.chat.ChatMessageType;
import com.projectkorra.projectkorra.platform.mc.*;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.*;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.Motion;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.Pose;

import java.util.*;
import java.util.function.Function;

/** Native mutations and buffered ability output for an imported private arena. */
public final class RollbackArenaActions implements RollbackWorld.Actions, RollbackPlayerState.Rules, RollbackStateCell<Void> {
    /** Native operations must use the supplied private logical world and participate in its checkpoints. */
    public interface Native extends RollbackEntityBody.Rules {
        <T extends Entity> T spawn(RollbackWorld world, Pose pos, Class<T> type);
        Entity spawnEntity(RollbackWorld world, Pose pos, EntityType type);
        Item dropItem(RollbackWorld world, Pose pos, ItemStack item, boolean natural);
        FallingBlock fallingBlock(RollbackWorld world, Pose pos, BlockData data);
        Arrow arrow(RollbackWorld world, Pose pos, Motion direction, float speed, float spread);
        boolean explosion(RollbackWorld world, Pose pos, float power, boolean fire, boolean breakBlocks);
        <T extends Projectile> T launch(RollbackPlayerState player, Class<T> type);
    }

    public record SoundOutput(UUID viewer, Pose position, UUID source, String sound, boolean named,
                              String category, float volume, float pitch) { }
    public record EffectOutput(Pose position, String effect, Object data, int radius) { }
    public record ParticleOutput(UUID viewer, Pose position, String particle, int count,
                                 double x, double y, double z, double extra, Object data, boolean force) { }
    public record Lightning(Pose position, boolean flash) { }
    public record Message(UUID player, ChatMessageType type, String message) { }
    public record NoteOutput(UUID player, Pose position, Instrument instrument, int octave, Note.Tone tone, boolean sharp) { }
    public record BlockOutput(UUID player, RollbackBlockStore.Position position, BlockValue data) { }
    public record HideDisplay(UUID player, UUID display) { }
    public record DisplayName(UUID player, String name) { }
    public record BlockValue(Material material, String state) { }
    public record ColorValue(int rgb) { }
    public record DustValue(int rgb, float size) { }
    public record SpellValue(int rgb, float power) { }

    private final RollbackStepOutput<Object> outputs;
    private final Native nativeActions;
    private final RollbackTerrainCodec.BlockStates states;
    private final Function<ItemStack, RollbackItemData> encodeItem;

    public RollbackArenaActions(RollbackStepOutput<Object> outputs, Native nativeActions,
                               RollbackTerrainCodec.BlockStates states, Function<ItemStack, RollbackItemData> encodeItem) {
        this.outputs = Objects.requireNonNull(outputs);
        this.nativeActions = Objects.requireNonNull(nativeActions);
        this.states = Objects.requireNonNull(states);
        this.encodeItem = Objects.requireNonNull(encodeItem);
    }

    public Map<UUID, RollbackRosterViews.Services> players(RollbackPlayerAccess access) {
        var services = new TreeMap<UUID, RollbackRosterViews.Services>();
        access.players().forEach((id, entry) -> services.put(id,
                new RollbackRosterViews.Services(entry, new Scoreboard(), nativeActions, this)));
        return Collections.unmodifiableMap(services);
    }

    @Override public <T extends Entity> T spawn(RollbackWorld world, Pose pos, Class<T> type) {
        return nativeActions.spawn(world, pos, type);
    }
    @Override public Entity spawnEntity(RollbackWorld world, Pose pos, EntityType type) {
        return nativeActions.spawnEntity(world, pos, type);
    }
    @Override public Item dropItem(RollbackWorld world, Pose pos, ItemStack item, boolean natural) {
        return nativeActions.dropItem(world, pos, item, natural);
    }
    @Override public FallingBlock fallingBlock(RollbackWorld world, Pose pos, BlockData data) {
        return nativeActions.fallingBlock(world, pos, data);
    }
    @Override public Arrow arrow(RollbackWorld world, Pose pos, Motion direction, float speed, float spread) {
        return nativeActions.arrow(world, pos, direction, speed, spread);
    }
    @Override public boolean explosion(RollbackWorld world, Pose pos, float power, boolean fire, boolean breakBlocks) {
        return nativeActions.explosion(world, pos, power, fire, breakBlocks);
    }
    @Override public <T extends Projectile> T launch(RollbackPlayerState player, Class<T> type) {
        return nativeActions.launch(player, type);
    }
    @Override public RollbackPlayerState.Controls controls(RollbackPlayerState player, RollbackPlayerState.Controls proposed) {
        return proposed;
    }

    @Override public void sound(RollbackWorld world, Pose pos, Entity source, String sound, boolean named,
                                String category, float volume, float pitch) {
        outputs.accept(new SoundOutput(null, pos, source == null ? null : source.getUniqueId(), sound, named, category, volume, pitch));
    }
    @Override public void effect(RollbackWorld world, Pose pos, Effect effect, Object data, int radius) {
        outputs.accept(new EffectOutput(pos, effect.name(), detach(data), radius));
    }
    @Override public void particle(RollbackWorld world, Pose pos, Particle particle, int count,
                                   double x, double y, double z, double extra, Object data) {
        outputs.accept(new ParticleOutput(null, pos, particle.name(), count, x, y, z, extra, detach(data), false));
    }
    @Override public void lightning(RollbackWorld world, Pose pos, boolean flash) {
        outputs.accept(new Lightning(pos, flash));
    }
    @Override public void message(RollbackPlayerState player, ChatMessageType type, String message) {
        outputs.accept(new Message(player.view().getUniqueId(), type, message));
    }
    @Override public void sound(RollbackPlayerState player, Pose pos, Sound sound, float volume, float pitch) {
        outputs.accept(new SoundOutput(player.view().getUniqueId(), pos, null, sound.name(), false, "MASTER", volume, pitch));
    }
    @Override public void note(RollbackPlayerState player, Pose pos, Instrument instrument, Note note) {
        outputs.accept(new NoteOutput(player.view().getUniqueId(), pos, instrument, note.getOctave(), note.getTone(), note.isSharped()));
    }
    @Override public void blockChange(RollbackPlayerState player, RollbackBlockStore.Position pos, BlockData data) {
        outputs.accept(new BlockOutput(player.view().getUniqueId(), pos, block(data)));
    }
    @Override public void particle(RollbackPlayerState player, Particle particle, Pose pos, int count,
                                   double x, double y, double z, double extra, Object data, boolean force) {
        outputs.accept(new ParticleOutput(player.view().getUniqueId(), pos, particle.name(), count, x, y, z, extra, detach(data), force));
    }
    @Override public void hideDisplay(RollbackPlayerState player, UUID display) {
        outputs.accept(new HideDisplay(player.view().getUniqueId(), display));
    }
    @Override public void displayName(RollbackPlayerState player, String name) {
        outputs.accept(new DisplayName(player.view().getUniqueId(), name));
    }
    @Override public void scoreboard(RollbackPlayerState player, Scoreboard scoreboard) {
        // RollbackPlayerState owns this logical reference. The server owns each participant's visible scoreboard.
    }

    private BlockValue block(BlockData data) { return new BlockValue(data.getMaterial(), states.encode(data)); }

    private Object detach(Object data) {
        if (data == null || data instanceof String || data instanceof Integer || data instanceof Byte || data instanceof Short
                || data instanceof Long || data instanceof Double || data instanceof Float || data instanceof Boolean
                || data instanceof BlockFace || data instanceof Material) return data;
        if (data instanceof BlockData value) return block(value);
        if (data instanceof ItemStack value) return Objects.requireNonNull(encodeItem.apply(value));
        if (data instanceof Color value) return new ColorValue(value.asRGB());
        if (data instanceof Particle.DustOptions value) return new DustValue(value.getColor().asRGB(), value.getSize());
        if (data instanceof Particle.Spell value) return new SpellValue(value.getColor().asRGB(), value.getPower());
        throw new IllegalArgumentException("Unsupported arena effect data " + data.getClass().getName());
    }

    @Override public Void captureRollbackState() { return null; }
    @Override public void restoreRollbackState(Void state) { }
    @Override public Collection<?> rollbackReferences() { return List.of(outputs, nativeActions); }
}
