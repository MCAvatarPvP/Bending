package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.listener.CommonBlockBreak;
import com.projectkorra.projectkorra.platform.fabric.FabricMC;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.prediction.rollback.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayer;
import net.minecraft.block.Block;
import net.minecraft.block.OperatorBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.stat.Stats;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.rule.GameRules;

final class FabricRollbackDigBlocks implements RollbackDigging.Blocks {
    private final FabricRollbackNativeScene.Duel duel;
    private final RollbackPlayer player;
    private final PlayerEntity body;
    private final ServerWorld world;
    FabricRollbackDigBlocks(FabricRollbackNativeScene.Duel duel, RollbackPlayer player, PlayerEntity body) {
        this.duel = duel; this.player = player; this.body = body;
        world = (ServerWorld) body.getEntityWorld();
    }
    private static BlockPos nativePos(Position pos) { return new BlockPos(pos.x(), pos.y(), pos.z()); }
    @Override public boolean creative() { return body.isCreative(); }
    @Override public boolean mayBreak(Position position) {
        var pos = nativePos(position);
        var state = world.getBlockState(pos);
        var mode = GameMode.valueOf(player.getGameMode().name());
        return decision(pos, FabricMC.material(state).name()) != RollbackDuelInteractions.Decision.DENY && !body.isSpectator() && body.isAlive()
                && duel.round().active(player.getUniqueId()) && !state.isAir() && world.getWorldBorder().contains(pos)
                && !body.isBlockBreakingRestricted(world, pos, mode)
                && (!(state.getBlock() instanceof OperatorBlock) || body.isCreativeLevelTwoOp())
                && body.getMainHandStack().canMine(state, world, pos, body);
    }
    private RollbackDuelInteractions.Decision decision(BlockPos pos, String material) {
        var rules = duel.scene().services().settings().interactions();
        return creative() ? rules.creativeBreakDecision(material) : rules.breakDecision(pos.getX(), pos.getY(), pos.getZ(), material);
    }
    @Override public void attack(Position position) {
        var pos = nativePos(position);
        var state = world.getBlockState(pos);
        // Objective interactions and native-only hooks are delivered by the live match.
        if (decision(pos, FabricMC.material(state).name()) == RollbackDuelInteractions.Decision.ALLOW)
            state.onBlockBreakStart(world, pos, body);
    }
    @Override public float delta(Position position) {
        var pos = nativePos(position);
        return world.getBlockState(pos).calcBlockBreakingDelta(body, world, pos);
    }
    @Override public void destroy(Position position) {
        var pos = nativePos(position);
        var state = world.getBlockState(pos);
        String material = FabricMC.material(state).name();
        var logical = duel.scene().logical();
        switch (decision(pos, material)) {
            case DENY -> { return; }
            case LIVE_MATCH -> {
                duel.output().accept(new RollbackDuelInteractions.LiveBreak(player.getUniqueId(), pos.getX(), pos.getY(), pos.getZ(), material));
                return;
            }
            case ALLOW -> { }
        }
        if (CommonBlockBreak.cancelled(player, logical.getBlockAt(pos.getX(), pos.getY(), pos.getZ()))) return;
        var tile = world.getBlockEntity(pos);
        var block = state.getBlock();
        var breaking = block.onBreak(world, pos, state, body);
        boolean removed = world.removeBlock(pos, false);
        if (removed) block.onBroken(world, pos, breaking);
        if (creative()) return;
        var tool = body.getMainHandStack();
        var before = tool.copy();
        boolean harvest = body.canHarvest(breaking);
        tool.postMine(world, breaking, pos, body);
        if (!removed || !harvest) return;
        body.incrementStat(Stats.MINED.getOrCreateStat(block));
        body.addExhaustion(.005F);
        var loot = FabricRollbackLoot.generate(world, duel.scene().services().settings().policy(),
                () -> Block.getDroppedStacks(breaking, world, pos, tile, body, before));
        if (world.getGameRules().getValue(GameRules.DO_TILE_DROPS)) {
            for (var stack : loot) {
                var random = world.getRandom();
                var at = new Location(logical, pos.getX() + .25 + random.nextDouble() * .5,
                        pos.getY() + .25 + random.nextDouble() * .5 - net.minecraft.entity.EntityType.ITEM.getHeight() / 2,
                        pos.getZ() + .25 + random.nextDouble() * .5);
                logical.dropItem(at, duel.items().capture(stack)).setPickupDelay(10);
            }
        }
        breaking.onStacksDropped(world, pos, before, true);
    }
}
