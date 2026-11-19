package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.listener.CommonBlockBreak;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.gameevent.GameEvent;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import java.util.*;

/** Break policy and vanilla resource drops in an owned Paper arena. */
final class PaperRollbackMining implements RollbackDigging.Blocks {
    record Broken(int x, int y, int z, int state) {}
    private static final class Special {
        static final Set<String> MATERIALS = materials();
        private static Set<String> materials() {
            var special = new TreeSet<String>();
            var callbacks = Set.of("playerWillDestroy", "playerDestroy", "destroy", "attack", "spawnAfterBreak",
                    "getExpDrop", "affectNeighborsAfterRemoval");
            for (var block : BuiltInRegistries.BLOCK) {
                boolean live = block instanceof EntityBlock;
                for (Class<?> type = block.getClass(); !live && type != Block.class; type = type.getSuperclass())
                    live = Arrays.stream(type.getDeclaredMethods()).anyMatch(method -> callbacks.contains(method.getName()));
                if (live) special.add(CraftMagicNumbers.getMaterial(block).name());
            }
            return Set.copyOf(special);
        }
    }
    static Set<String> liveMaterials() { return Special.MATERIALS; }
    private final PaperRollbackNativeScene scene;
    private final PaperRollbackActors actors;
    private final ServerPlayer body;
    private final RollbackPlayer player;
    private final RollbackStep<Object> output;
    PaperRollbackMining(PaperRollbackNativeScene scene, PaperRollbackActors actors, ServerPlayer body, RollbackPlayer player, RollbackStep<Object> output) {
        this.scene = scene; this.actors = actors; this.body = body; this.player = player; this.output = output;
    }
    private static BlockPos nativePos(Position p) { return new BlockPos(p.x(), p.y(), p.z()); }
    private RollbackDuelInteractions.Decision decision(Position pos, String material) {
        var policy = scene.services().settings().interactions();
        return creative() ? policy.creativeBreakDecision(material) : policy.breakDecision(pos.x(), pos.y(), pos.z(), material);
    }
    @Override public boolean creative() { return body.isCreative(); }
    @Override public boolean mayBreak(Position pos) {
        if (!scene.logical().terrain().bounds().contains(pos) || body.isSpectator() || !body.isAlive()
                || !scene.world().activeParticipant(body.getUUID())) return false;
        var nativePos = nativePos(pos);
        var world = body.level();
        var block = world.getBlockState(nativePos);
        if (block.isAir() || !world.getWorldBorder().isWithinBounds(nativePos)
                || body.blockActionRestricted(world, nativePos, body.gameMode.getGameModeForPlayer())
                || !body.getMainHandItem().canDestroyBlock(block, world, nativePos, body)) return false;
        if (block.getBlock() instanceof net.minecraft.world.level.block.GameMasterBlock && !body.canUseGameMasterBlocks()) return false;
        return decision(pos, CraftMagicNumbers.getMaterial(block.getBlock()).name()) != RollbackDuelInteractions.Decision.DENY;
    }
    @Override public float delta(Position pos) {
        var at = nativePos(pos);
        return body.level().getBlockState(at).getDestroyProgress(body, body.level(), at);
    }
    @Override public void attack(Position pos) {
        var at = nativePos(pos);
        var state = body.level().getBlockState(at);
        if (!liveMaterials().contains(CraftMagicNumbers.getMaterial(state.getBlock()).name()))
            state.attack(body.level(), at, body);
    }
    @Override public void destroy(Position pos) {
        if (!mayBreak(pos)) return;
        var world = body.level();
        var at = nativePos(pos);
        var state = world.getBlockState(at);
        String material = CraftMagicNumbers.getMaterial(state.getBlock()).name();
        if (decision(pos, material) == RollbackDuelInteractions.Decision.LIVE_MATCH) {
            output.emit(new RollbackDuelInteractions.LiveBreak(body.getUUID(), pos.x(), pos.y(), pos.z(), material));
            return;
        }
        var block = scene.logical().getBlockAt(pos.x(), pos.y(), pos.z());
        if (CommonBlockBreak.cancelled(player, block)) return;
        var event = new org.bukkit.event.block.BlockBreakEvent(org.bukkit.craftbukkit.block.CraftBlock.at(world, at), body.getBukkitEntity());
        scene.world().event(event);
        if (event.isCancelled()) return;
        var tool = body.getMainHandItem();
        var original = tool.copy();
        boolean harvest = !creative() && body.hasCorrectToolForDrops(state);
        output.emit(new Broken(pos.x(), pos.y(), pos.z(), Block.getId(state)));
        world.gameEvent(GameEvent.BLOCK_DESTROY, at, new GameEvent.Context(body, state));
        if (!world.removeBlock(at, false)) return;
        state.getBlock().destroy(world, at, state);
        if (creative()) return;
        scene.world().mine(tool, state, at, body, harvest);
        if (harvest && event.isDropItems() && world.getGameRules().get(GameRules.BLOCK_DROPS)) {
            for (var drop : Block.getDrops(state, world, at, null, body, original))
                actors.dropBlockResource(at, drop);
        }
    }
}
