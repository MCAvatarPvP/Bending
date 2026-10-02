package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockRay;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorldQueries;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackMovementSolver;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Box;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Geometry;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.bukkit.craftbukkit.legacy.CraftLegacy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Native block/fluid geometry against captured terrain, including neighbor reads after restoration. */
public final class PaperRollbackGeometry implements RollbackWorldQueries.Geometry {
    @FunctionalInterface
    public interface BlockEntities {
        /** Returns the logical native block entity, or null for no entity. */
        Object get(RollbackBlockStore terrain, Position position, Object nativeState);
    }

    interface States {
        Object decode(BlockData data);
        boolean solid(BlockData data);
    }

    private final int minimumY, height;
    private final BlockEntities blockEntities;
    private final States states;

    public PaperRollbackGeometry(int minimumY, int maximumY, BlockEntities blockEntities) {
        this(minimumY, maximumY, blockEntities, new PaperRollbackBlockStates());
    }

    PaperRollbackGeometry(int minimumY, int maximumY, BlockEntities blockEntities, States states) {
        if (maximumY <= minimumY) throw new IllegalArgumentException("World height");
        this.minimumY = minimumY;
        this.height = Math.subtractExact(maximumY, minimumY);
        this.blockEntities = Objects.requireNonNull(blockEntities, "blockEntities");
        this.states = Objects.requireNonNull(states, "states");
    }

    public Geometry geometry(RollbackBlockStore terrain, Position position, BlockData data) {
        terrain.cell(position);
        BlockState state = decode(data);
        var getter = new TerrainView(terrain, position, state);
        var nativePosition = new BlockPos(position.x(), position.y(), position.z());
        VoxelShape collision = state.getCollisionShape(getter, nativePosition);
        VoxelShape outline = state.getShape(getter, nativePosition);
        // The native fluid-shape cache omits neighbor state. Evaluate uncached
        // bounds so a rewind or another world's geometry cannot poison the result.
        AABB volume = state.getFluidState().getAABB(getter, nativePosition);
        List<Box> fluidBoxes = volume == null ? List.of() : List.of(box(volume.move(-position.x(), -position.y(), -position.z())));
        return new Geometry(states.solid(data), state.liquid(), collision.isEmpty(), state.hasBlockEntity(),
                outline.isEmpty() ? null : box(outline.bounds()), collision.toAabbs().stream().map(PaperRollbackGeometry::box).toList(), fluidBoxes);
    }

    public byte legacyData(BlockData data) { return CraftLegacy.toLegacyData(decode(data)); }
    @Override public boolean motionBlockingHeight(BlockData data) { return Heightmap.Types.MOTION_BLOCKING.isOpaque().test(decode(data)); }

    /** Entity and border colliders are supplied separately by the movement adapter. */
    public List<Object> movementColliders(RollbackBlockStore terrain, Box bounds, Object context) {
        RollbackMovementSolver.requireBlockQuery(bounds);
        var view = new CollisionView(terrain);
        var iterator = new BlockCollisions<>(view, (CollisionContext) Objects.requireNonNull(context, "context"),
                new AABB(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ()),
                false, (position, shape) -> shape);
        List<Object> shapes = new ArrayList<>();
        while (iterator.hasNext()) {
            if (shapes.size() >= RollbackBlockRay.MAX_VISITED_BLOCKS) throw new IllegalStateException("Movement shape budget exceeded");
            shapes.add(iterator.next());
        }
        return List.copyOf(shapes);
    }

    /** Runs native traversal and clipping against logical terrain. */
    public RollbackBlockRay.Hit rayTrace(RollbackBlockStore terrain, RollbackBlockRay ray) {
        return rayTrace(terrain, ray, CollisionContext.empty());
    }
    @Override public RollbackBlockRay.Hit rayTrace(RollbackBlockStore terrain, RollbackBlockRay ray,
                                                 com.projectkorra.projectkorra.platform.mc.entity.Entity viewer) {
        var body = com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.logicalBody(viewer);
        if (body.world() != terrain.world() || !(body.kinematicsSource() instanceof PaperRollbackNativePlayerState state)) {
            throw new IllegalArgumentException("Sight viewer is not an owned Paper body in this arena");
        }
        return rayTrace(terrain, ray, CollisionContext.of(state.ownedPlayer()));
    }
    private RollbackBlockRay.Hit rayTrace(RollbackBlockStore terrain, RollbackBlockRay ray, CollisionContext collisionContext) {
        terrain.cell(ray.originBlock());
        var from = new Vec3(ray.x(), ray.y(), ray.z());
        var to = new Vec3(ray.endX(), ray.endY(), ray.endZ());
        var context = new ClipContext(from, to, ray.ignorePassable() ? ClipContext.Block.COLLIDER : ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, collisionContext);
        var getter = new TerrainView(terrain, null, null);
        int[] visited = {0};
        BlockHitResult hit = BlockGetter.traverseBlocks(from, to, context, (query, pos) -> {
            if (++visited[0] > RollbackBlockRay.MAX_VISITED_BLOCKS) throw new IllegalStateException("Terrain ray budget exceeded");
            BlockState state = getter.getBlockState(pos);
            VoxelShape shape = query.getBlockShape(state, getter, pos);
            BlockHitResult blockHit = getter.clipWithInteractionOverride(from, to, pos, shape, state);
            BlockHitResult fluidHit = null;
            FluidState fluid = state.getFluidState();
            if (ray.fluids() == RollbackBlockRay.Fluids.ALWAYS
                    || ray.fluids() == RollbackBlockRay.Fluids.SOURCE_ONLY && fluid.isSource()) {
                AABB bounds = fluid.getAABB(getter, pos);
                if (bounds != null) fluidHit = Shapes.create(bounds).move(-pos.getX(), -pos.getY(), -pos.getZ()).clip(from, to, pos);
            }
            double blockDistance = blockHit == null ? Double.MAX_VALUE : from.distanceToSqr(blockHit.getLocation());
            double fluidDistance = fluidHit == null ? Double.MAX_VALUE : from.distanceToSqr(fluidHit.getLocation());
            return blockDistance <= fluidDistance ? blockHit : fluidHit;
        }, ignored -> null);
        if (hit == null) return null;
        Vec3 point = hit.getLocation();
        return new RollbackBlockRay.Hit(position(hit.getBlockPos()), point.x, point.y, point.z,
                BlockFace.valueOf(hit.getDirection().name()), hit.isInside());
    }

    private BlockState decode(BlockData data) { return (BlockState) states.decode(data); }
    private static Position position(BlockPos value) { return new Position(value.getX(), value.getY(), value.getZ()); }
    private static Box box(AABB value) { return new Box(value.minX, value.minY, value.minZ, value.maxX, value.maxY, value.maxZ); }

    private class TerrainView implements BlockGetter {
        final RollbackBlockStore terrain;
        private final Position replaced;
        private final BlockState replacement;
        TerrainView(RollbackBlockStore terrain, Position replaced, BlockState replacement) {
            this.terrain = terrain; this.replaced = replaced; this.replacement = replacement;
        }
        @Override public int getMinY() { return minimumY; }
        @Override public int getHeight() { return height; }
        @Override public BlockState getBlockState(BlockPos pos) {
            Position key = position(pos);
            // Expose pending setBlockData geometry before installing its overlay.
            return key.equals(replaced) ? replacement : decode(terrain.cell(key).data());
        }
        @Override public BlockState getBlockStateIfLoaded(BlockPos pos) { return getBlockState(pos); }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public FluidState getFluidIfLoaded(BlockPos pos) { return getFluidState(pos); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return (BlockEntity) blockEntities.get(terrain, position(pos), getBlockState(pos)); }
    }

    private final class CollisionView extends TerrainView implements CollisionGetter {
        private int reads;
        CollisionView(RollbackBlockStore terrain) { super(terrain, null, null); }
        @Override public BlockState getBlockState(BlockPos pos) {
            if (++reads > RollbackBlockRay.MAX_VISITED_BLOCKS) throw new IllegalStateException("Movement block read budget exceeded");
            return super.getBlockState(pos);
        }
        @Override public BlockGetter getChunkForCollisions(int x, int z) { return this; }
        @Override public WorldBorder getWorldBorder() { throw new UnsupportedOperationException("Border colliders require the movement adapter"); }
        @Override public List<VoxelShape> getEntityCollisions(Entity entity, AABB bounds) {
            throw new UnsupportedOperationException("Entity colliders require the movement adapter");
        }
    }

}
