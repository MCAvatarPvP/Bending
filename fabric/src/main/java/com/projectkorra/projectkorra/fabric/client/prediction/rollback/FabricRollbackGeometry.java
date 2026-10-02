package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.projectkorra.projectkorra.platform.fabric.FabricMC;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockRay;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorldQueries;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackMovementSolver;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Box;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Geometry;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;

import java.util.List;
import java.util.Objects;

/** Native client shapes evaluated against restored logical terrain, with the same bounds as Paper. */
public final class FabricRollbackGeometry implements RollbackWorldQueries.Geometry {
    @FunctionalInterface
    public interface BlockEntities {
        BlockEntity get(RollbackBlockStore terrain, Position position, BlockState state);
    }

    private final int minimumY, height;
    private final BlockEntities blockEntities;

    public FabricRollbackGeometry(int minimumY, int maximumY, BlockEntities blockEntities) {
        if (maximumY <= minimumY) throw new IllegalArgumentException("World height");
        this.minimumY = minimumY;
        this.height = Math.subtractExact(maximumY, minimumY);
        this.blockEntities = Objects.requireNonNull(blockEntities, "blockEntities");
    }

    public Geometry geometry(RollbackBlockStore terrain, Position position, BlockData data) {
        terrain.cell(position); // Validate owning thread and captured bounds before native queries.
        BlockState state = decode(data);
        BlockPos nativePosition = new BlockPos(position.x(), position.y(), position.z());
        BlockView view = view(terrain, nativePosition, state);
        VoxelShape collision = state.getCollisionShape(view, nativePosition);
        VoxelShape outline = state.getOutlineShape(view, nativePosition);
        // Match Paper's uncached native fluid-bounds path. FlowableFluid#getShape
        // retains a context-dependent global cache which cannot survive a rewind.
        net.minecraft.util.math.Box fluid = state.getFluidState().getCollisionBox(view, nativePosition);
        List<Box> fluids = fluid == null ? List.of() : List.of(box(fluid.offset(-position.x(), -position.y(), -position.z())));
        return new Geometry(state.getBlock().getDefaultState().blocksMovement(), state.isLiquid(), collision.isEmpty(),
                state.hasBlockEntity(), outline.isEmpty() ? null : box(outline.getBoundingBox()),
                collision.getBoundingBoxes().stream().map(FabricRollbackGeometry::box).toList(), fluids);
    }

    @Override public boolean motionBlockingHeight(BlockData data) {
        return net.minecraft.world.Heightmap.Type.MOTION_BLOCKING.getBlockPredicate().test(decode(data));
    }

    /** Uses the native iterator with a caller-supplied context over detached entity state. */
    public List<VoxelShape> movementColliders(RollbackBlockStore terrain, Box bounds, ShapeContext context) {
        RollbackMovementSolver.requireBlockQuery(bounds);
        Objects.requireNonNull(context, "context");
        int[] reads = {0};
        var world = new net.minecraft.world.CollisionView() {
            @Override public BlockEntity getBlockEntity(BlockPos pos) { return blockEntities.get(terrain, key(pos), getBlockState(pos)); }
            @Override public BlockState getBlockState(BlockPos pos) {
                if (++reads[0] > RollbackBlockRay.MAX_VISITED_BLOCKS) throw new IllegalStateException("Movement block read budget exceeded");
                return decode(terrain.cell(key(pos)).data());
            }
            @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            @Override public int getHeight() { return height; }
            @Override public int getBottomY() { return minimumY; }
            @Override public BlockView getChunkAsView(int x, int z) { return this; }
            @Override public net.minecraft.world.border.WorldBorder getWorldBorder() { throw new IllegalStateException("Block collision query requested a live world border"); }
            @Override public List<VoxelShape> getEntityCollisions(net.minecraft.entity.Entity entity, net.minecraft.util.math.Box box) {
                throw new IllegalStateException("Block collision query requested native entity membership");
            }
        };
        var box = new net.minecraft.util.math.Box(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
        var iterator = new net.minecraft.world.BlockCollisionSpliterator<VoxelShape>(world, context, box, false, (position, shape) -> shape);
        List<VoxelShape> shapes = new java.util.ArrayList<>();
        while (iterator.hasNext()) {
            if (shapes.size() >= RollbackBlockRay.MAX_VISITED_BLOCKS) throw new IllegalStateException("Movement shape budget exceeded");
            shapes.add(iterator.next());
        }
        return List.copyOf(shapes);
    }

    /** Uses native voxel traversal and clipping, including multipart and interaction shapes. */
    public RollbackBlockRay.Hit rayTrace(RollbackBlockStore terrain, RollbackBlockRay ray) {
        return rayTrace(terrain, ray, ShapeContext.absent());
    }
    @Override public RollbackBlockRay.Hit rayTrace(RollbackBlockStore terrain, RollbackBlockRay ray,
                                                 com.projectkorra.projectkorra.platform.mc.entity.Entity viewer) {
        var body = com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.logicalBody(viewer);
        if (body.world() != terrain.world() || !(body.kinematicsSource() instanceof FabricRollbackNativePlayerState state)) {
            throw new IllegalArgumentException("Sight viewer is not an owned Fabric body in this arena");
        }
        return rayTrace(terrain, ray, ShapeContext.of(state.ownedPlayer()));
    }
    private RollbackBlockRay.Hit rayTrace(RollbackBlockStore terrain, RollbackBlockRay ray, ShapeContext collisionContext) {
        terrain.cell(ray.originBlock());
        Vec3d from = new Vec3d(ray.x(), ray.y(), ray.z());
        Vec3d to = new Vec3d(ray.endX(), ray.endY(), ray.endZ());
        BlockView view = view(terrain, null, null);
        RaycastContext context = new RaycastContext(from, to,
                ray.ignorePassable() ? RaycastContext.ShapeType.COLLIDER : RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, collisionContext);
        int[] visited = {0};
        BlockHitResult hit = BlockView.raycast(from, to, context, (query, position) -> {
            if (++visited[0] > RollbackBlockRay.MAX_VISITED_BLOCKS) throw new IllegalStateException("Terrain ray budget exceeded");
            BlockState state = view.getBlockState(position);
            BlockHitResult blockHit = view.raycastBlock(from, to, position, query.getBlockShape(state, view, position), state);
            BlockHitResult fluidHit = null;
            FluidState fluid = state.getFluidState();
            if (ray.fluids() == RollbackBlockRay.Fluids.ALWAYS
                    || ray.fluids() == RollbackBlockRay.Fluids.SOURCE_ONLY && fluid.isStill()) {
                net.minecraft.util.math.Box bounds = fluid.getCollisionBox(view, position);
                if (bounds != null) {
                    fluidHit = VoxelShapes.cuboid(bounds.offset(-position.getX(), -position.getY(), -position.getZ()))
                            .raycast(from, to, position);
                }
            }
            double blockDistance = blockHit == null ? Double.MAX_VALUE : from.squaredDistanceTo(blockHit.getPos());
            double fluidDistance = fluidHit == null ? Double.MAX_VALUE : from.squaredDistanceTo(fluidHit.getPos());
            return blockDistance <= fluidDistance ? blockHit : fluidHit;
        }, query -> null);
        if (hit == null) return null;
        Vec3d point = hit.getPos();
        return new RollbackBlockRay.Hit(key(hit.getBlockPos()), point.x, point.y, point.z,
                BlockFace.valueOf(hit.getSide().name()), hit.isInsideBlock());
    }

    private BlockView view(RollbackBlockStore terrain, BlockPos replaced, BlockState replacement) {
        return new BlockView() {
            @Override public BlockEntity getBlockEntity(BlockPos pos) {
                return blockEntities.get(terrain, key(pos), getBlockState(pos));
            }
            @Override public BlockState getBlockState(BlockPos pos) {
                return pos.equals(replaced) ? replacement : decode(terrain.cell(key(pos)).data());
            }
            @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            @Override public int getHeight() { return height; }
            @Override public int getBottomY() { return minimumY; }
        };
    }

    private static Position key(BlockPos position) { return new Position(position.getX(), position.getY(), position.getZ()); }
    private static Box box(net.minecraft.util.math.Box box) {
        return new Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    static BlockState decode(BlockData data) {
        if (data.getExactState() != null) {
            try {
                BlockState state = BlockArgumentParser.block(Registries.BLOCK, data.getExactState(), false).blockState();
                if (FabricMC.material(state).canonical() != data.getMaterial().canonical()) {
                    throw new IllegalArgumentException("Logical block material does not match its exact state");
                }
                return FabricMC.applyBlockDataProperties(state, data);
            } catch (CommandSyntaxException failure) {
                throw new IllegalArgumentException("Invalid logical block state: " + data.getExactState(), failure);
            }
        }
        return FabricMC.blockState(data);
    }
}
