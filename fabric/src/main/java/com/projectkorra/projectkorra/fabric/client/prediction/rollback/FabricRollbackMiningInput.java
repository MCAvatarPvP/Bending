package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket.Action;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import java.util.function.Consumer;
import java.util.function.Function;

/** Physical mining intent; obtains tool and break speed from the current private player. */
final class FabricRollbackMiningInput {
    record Material(ItemStack tool, float delta, boolean creative) {
        static final Material FROZEN = new Material(ItemStack.EMPTY, 0, false);
    }
    private final Function<BlockPos, Material> sample;
    private final Consumer<PlayerActionC2SPacket> send;
    private BlockPos target;
    private Direction face;
    private ItemStack tool = ItemStack.EMPTY;
    private float progress;
    private int cooldown;

    FabricRollbackMiningInput(Function<BlockPos, Material> sample, Consumer<PlayerActionC2SPacket> send) {
        this.sample = sample;
        this.send = send;
    }
    void hit(BlockPos pos, Direction side, boolean continued) {
        var sample = this.sample.apply(pos);
        if (continued && cooldown > 0) { cooldown--; return; }
        if (!pos.equals(target) || !ItemStack.areItemsAndComponentsEqual(sample.tool(), tool)) {
            cancel();
            send.accept(new PlayerActionC2SPacket(Action.START_DESTROY_BLOCK, pos, side));
            if (sample.creative() || sample.delta() >= 1F) {
                cooldown = 5;
                return;
            }
            target = pos.toImmutable(); face = side; tool = sample.tool().copy();
            return;
        }
        if (!continued) return;
        progress += sample.delta();
        if (progress >= 1F) {
            send.accept(new PlayerActionC2SPacket(Action.STOP_DESTROY_BLOCK, pos, side));
            reset(); cooldown = 5;
        }
    }
    void cancel() {
        if (target != null) send.accept(new PlayerActionC2SPacket(Action.ABORT_DESTROY_BLOCK, target, face));
        reset();
    }
    private void reset() { target = null; face = null; tool = ItemStack.EMPTY; progress = 0; }
}
