package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemData;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.event.GameEvent;
import net.minecraft.world.event.listener.GameEventListener;

import java.util.*;

/** Native duel events run against the private scene. Only detached presentation enters the tick output journal. */
public final class FabricRollbackArenaEvents implements FabricRollbackWorldServices.Events<Void>,
        FabricRollbackWorldAccess.DamagePolicy<Void> {
    public record Waypoint(FabricRollbackWorldAccess.WaypointAction action, UUID entity) { }
    private final RollbackStepOutput<Object> output;
    private final RollbackWorldSettings.Policy policy;
    private FabricRollbackNativeScene scene;

    public FabricRollbackArenaEvents(RollbackStepOutput<Object> output, RollbackWorldSettings.Policy policy) {
        this.output = Objects.requireNonNull(output);
        this.policy = Objects.requireNonNull(policy);
    }

    void bind(FabricRollbackNativeScene scene) {
        if (this.scene != null) throw new IllegalStateException("Native events already belong to an arena");
        this.scene = Objects.requireNonNull(scene);
    }

    @Override public void output(FabricRollbackWorldAccess.Output message) { output.accept(message); }
    @Override public void waypoint(FabricRollbackWorldAccess.WaypointAction action, Entity entity) {
        output.accept(new Waypoint(action, entity.getUuid()));
    }
    @Override public boolean slotAllowed(UUID id, int previous, int selected, boolean cancelled) { return !cancelled; }
    @Override public boolean flightAllowed(UUID id, boolean flying, boolean cancelled) { return !cancelled; }
    @Override public boolean glideAllowed(UUID id, boolean gliding, boolean cancelled) { return !cancelled; }
    @Override public RollbackHandSwap swapHands(UUID id, RollbackItemData main, RollbackItemData off) {
        return new RollbackHandSwap(false, main, off);
    }

    @Override public void gameEvent(RegistryEntry<GameEvent> event, Vec3d position, GameEvent.Emitter emitter) {
        var server = (ServerWorld) scene.roster().world().world();
        var source = BlockPos.ofFloored(position);
        int radius = event.value().notificationRadius();
        var deferred = new ArrayList<GameEvent.Message>();
        for (var point : scene.logical().terrain().blockEntityPositions()) {
            if (!inSectionRange(point.x(), source.getX(), radius) || !inSectionRange(point.y(), source.getY(), radius)
                    || !inSectionRange(point.z(), source.getZ(), radius)) continue;
            var pos = new BlockPos(point.x(), point.y(), point.z());
            if (!(server.getBlockState(pos).getBlock() instanceof BlockEntityProvider provider)) continue;
            var tile = server.getBlockEntity(pos);
            if (tile == null) continue;
            var listener = provider.getGameEventListener(server, tile);
            if (listener == null) continue;
            var target = listener.getPositionSource().getPos(server);
            if (target.isEmpty() || BlockPos.ofFloored(target.get()).getSquaredDistance(source) > (double) listener.getRange() * listener.getRange()) continue;
            if (listener.getTriggerOrder() == GameEventListener.TriggerOrder.BY_DISTANCE) {
                deferred.add(new GameEvent.Message(event, position, emitter, listener, target.get()));
            } else {
                listener.listen(server, event, emitter, position);
            }
        }
        Collections.sort(deferred);
        for (var message : deferred) message.getListener().listen(server, event, emitter, position);
    }
    private static boolean inSectionRange(int value, int center, int radius) {
        return (value >> 4) >= ((center - radius) >> 4) && (value >> 4) <= ((center + radius) >> 4);
    }

    @Override public void event(FabricRollbackDamageEvent event) {
        if (!policy.pvp() && event.source().getAttacker() instanceof PlayerEntity attacker && attacker != event.player())
            event.cancelled(true);
    }
    boolean canHarmPlayer(PlayerEntity first, PlayerEntity second) {
        return policy.pvp() && scene.services().settings().interactions().teams().canHarm(first.getUuid(), second.getUuid());
    }
    @Override public void resetAttackCooldown(PlayerEntity attacker, PlayerEntity target) { attacker.resetTicksSinceLastAttack(); }
    @Override public void exhaustion(PlayerEntity player, DamageSource source, float amount) { player.addExhaustion(amount); }
    @Override public void knockback(PlayerEntity player, DamageSource source, double strength, double x, double z) {
        player.takeKnockback(strength, x, z);
    }
    @Override public void death(PlayerEntity player, DamageSource source) { player.onDeath(source); }
    @Override public int shieldDisable(PlayerEntity player, LivingEntity attacker, ItemStack shield, int ticks) { return ticks; }
    @Override public int itemCooldown(PlayerEntity player, ItemStack item, int ticks) { return ticks; }
    @Override public boolean disablePlayerCrits() { return policy.disablePlayerCrits(); }
    @Override public boolean disableSprintInterruptionOnAttack() { return policy.disableSprintInterruptionOnAttack(); }
    @Override public float combatExhaustion() { return policy.combatExhaustion(); }
    @Override public boolean skipDamageTickWhenShieldBlocked() { return policy.skipBlockedDamageTick(); }
    @Override public Void captureRollbackState() { return null; }
    @Override public void restoreRollbackState(Void unused) { }
    @Override public List<?> rollbackReferences() { return List.of(output); }
}
