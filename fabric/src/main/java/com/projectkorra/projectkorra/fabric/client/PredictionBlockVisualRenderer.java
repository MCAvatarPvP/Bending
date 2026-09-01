package com.projectkorra.projectkorra.fabric.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.projectkorra.projectkorra.fabric.client.prediction.block.ClientBlockVisualOverlay;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.BlockRenderLayer;
import net.minecraft.client.render.BlockRenderLayers;
import net.minecraft.client.render.LayeringTransform;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.OutputTarget;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.chunk.light.LightingProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws locally predicted non-air block overrides every frame.
 *
 * <p>A chunk rebuild is too slow for a launched block that advances every
 * client tick: by the time a worker reads one coordinate, prediction may have
 * already moved on. During Fabric's extraction phase this renderer copies
 * immutable block/light data and tessellates any fluid into an immutable mesh,
 * then submits both through the ordered world render queue. It therefore stays
 * independent of ClientWorld storage and of a particular terrain mesher
 * (including renderer replacements that invoke Fabric world-render events).</p>
 */
public final class PredictionBlockVisualRenderer {
    /**
     * Keeps foreground faces in front of an asynchronously-retired terrain
     * copy. The expansion is centered on the voxel and is too small to be
     * perceptible as a size change.
     */
    private static final float FOREGROUND_SCALE = 1.002F;
    private static final Direction[] LIGHT_SAMPLE_DIRECTIONS = Direction.values();
    private static final RenderLayer PREDICTED_SOLID_LAYER =
            RenderLayers.entitySolidZOffsetForward(
                    SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
    private static final RenderLayer PREDICTED_CUTOUT_LAYER = predictedModelLayer(
            "projectkorra_predicted_cutout", RenderPipelines.ENTITY_CUTOUT, false);
    private static final RenderLayer PREDICTED_TRANSLUCENT_LAYER = predictedModelLayer(
            "projectkorra_predicted_translucent",
            RenderPipelines.ENTITY_TRANSLUCENT, true);
    private static final RenderLayer PREDICTED_FLUID_LAYER = RenderLayer.of(
            "projectkorra_predicted_fluid",
            RenderSetup.builder(RenderPipelines.RENDERTYPE_TRANSLUCENT_MOVING_BLOCK)
                    .useLightmap()
                    .texture("Sampler0", SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE,
                            RenderLayers.BLOCK_SAMPLER)
                    .outputTarget(OutputTarget.ITEM_ENTITY_TARGET)
                    .layeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING_FORWARD)
                    .translucent()
                    .expectedBufferSize(786432)
                    .build());
    private static final RenderStateDataKey<List<RenderBlock>> FOREGROUND_BLOCKS =
            RenderStateDataKey.create(() -> "projectkorra:predicted_blocks");

    private PredictionBlockVisualRenderer() {
    }

    public static void initialize() {
        WorldRenderEvents.END_EXTRACTION.register(context -> {
            final List<ClientBlockVisualOverlay.VisualBlock> visuals =
                    ExactPredictionRuntime.foregroundBlocks(context.world());
            if (visuals.isEmpty()) {
                ((FabricRenderState) context.worldState()).setData(FOREGROUND_BLOCKS, null);
                return;
            }

            final List<RenderBlock> extracted = new ArrayList<>(visuals.size());
            final ComposedRenderView renderView = new ComposedRenderView(context.world());
            final MinecraftClient client = MinecraftClient.getInstance();
            final var blockRenderer = client.getBlockRenderManager();
            for (ClientBlockVisualOverlay.VisualBlock visual : visuals) {
                if (visual == null || visual.pos() == null || visual.state() == null
                        || visual.state().isAir()) continue;
                final int light = foregroundLight(
                        context.world(), visual.pos(), visual.state());
                final PredictionFluidMesh fluidMesh = visual.state().getFluidState().isEmpty()
                        ? null : PredictionFluidMesh.tessellate(
                        blockRenderer, renderView, visual.pos(), visual.state());
                final BlockStateModel model = visual.state().getRenderType()
                        == BlockRenderType.MODEL
                        ? blockRenderer.getModel(visual.state()) : null;
                final int tint = model == null ? 0xFFFFFF : client.getBlockColors()
                        .getColor(visual.state(), renderView, visual.pos(), 0);
                extracted.add(new RenderBlock(
                        visual.pos(), light, fluidMesh, model,
                        foregroundModelLayer(visual.state()),
                        (tint >> 16 & 0xFF) / 255.0F,
                        (tint >> 8 & 0xFF) / 255.0F,
                        (tint & 0xFF) / 255.0F));
            }
            ((FabricRenderState) context.worldState()).setData(
                    FOREGROUND_BLOCKS, List.copyOf(extracted));
        });

        WorldRenderEvents.BEFORE_ENTITIES.register(context -> {
            final List<RenderBlock> blocks = ((FabricRenderState) context.worldState())
                    .getData(FOREGROUND_BLOCKS);
            final MatrixStack matrices = context.matrices();
            if (blocks == null || blocks.isEmpty() || matrices == null) return;

            final Vec3d camera = context.worldState().cameraRenderState.pos;
            for (RenderBlock block : blocks) {
                matrices.push();
                matrices.translate(
                        block.pos.getX() - camera.x,
                        block.pos.getY() - camera.y,
                        block.pos.getZ() - camera.z);
                // ClientWorld intentionally retains the real block. Its old
                // terrain mesh can therefore overlap this visual until the
                // asynchronous rebuild lands (and again during handoff).
                // Put the foreground faces just ahead of that mesh so it can
                // never bleed through as a dark, coplanar patch.
                final float inset = (FOREGROUND_SCALE - 1.0F) * 0.5F;
                matrices.translate(-inset, -inset, -inset);
                matrices.scale(FOREGROUND_SCALE, FOREGROUND_SCALE, FOREGROUND_SCALE);
                if (block.model != null) {
                    context.commandQueue().submitBlockStateModel(
                            matrices, block.modelLayer, block.model,
                            block.red, block.green, block.blue, block.light,
                            OverlayTexture.DEFAULT_UV, 0);
                }
                if (block.fluidMesh != null && !block.fluidMesh.isEmpty()) {
                    context.commandQueue().submitCustom(
                            matrices, PREDICTED_FLUID_LAYER,
                            (matrix, vertices) -> block.fluidMesh.replay(vertices, matrix));
                }
                matrices.pop();
            }
        });
    }

    private static RenderLayer predictedModelLayer(final String name,
                                                   final RenderPipeline pipeline,
                                                   final boolean translucent) {
        final RenderSetup.Builder setup = RenderSetup.builder(pipeline)
                .texture("Sampler0", SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE)
                .useLightmap()
                .useOverlay()
                .layeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING_FORWARD);
        if (translucent) setup.translucent();
        return RenderLayer.of(name, setup.build());
    }

    private static RenderLayer foregroundModelLayer(final BlockState state) {
        if (state == null) return PREDICTED_SOLID_LAYER;
        final BlockRenderLayer layer = BlockRenderLayers.getBlockLayer(state);
        if (layer == BlockRenderLayer.SOLID) return PREDICTED_SOLID_LAYER;
        if (layer == BlockRenderLayer.TRANSLUCENT) return PREDICTED_TRANSLUCENT_LAYER;
        return PREDICTED_CUTOUT_LAYER;
    }

    /**
     * A render-only replacement must not inherit light from the inside of the
     * authoritative block it covers. Opaque occupied cells commonly report
     * zero light, making an otherwise-correct prediction appear black. Use
     * the brightest sky and block channels touching the replacement's faces,
     * while retaining the predicted state's own luminance/emissive result.
     */
    private static int foregroundLight(final ClientWorld world,
                                       final BlockPos pos,
                                       final BlockState state) {
        final int local = WorldRenderer.getLightmapCoordinates(
                WorldRenderer.BrightnessGetter.DEFAULT, world, state, pos);
        int blockLight = LightmapTextureManager.getBlockLightCoordinates(local);
        int skyLight = LightmapTextureManager.getSkyLightCoordinates(local);

        // Most projectiles are in air and already have a valid light sample.
        // Only an opaque authoritative occupant can turn the replacement's
        // coordinate into the dark interior cell this path corrects.
        if (!world.getBlockState(pos).isOpaqueFullCube()) return local;

        for (Direction direction : LIGHT_SAMPLE_DIRECTIONS) {
            final int exposed = WorldRenderer.BrightnessGetter.DEFAULT
                    .packedBrightness(world, pos.offset(direction));
            blockLight = Math.max(blockLight,
                    LightmapTextureManager.getBlockLightCoordinates(exposed));
            skyLight = Math.max(skyLight,
                    LightmapTextureManager.getSkyLightCoordinates(exposed));
        }
        return LightmapTextureManager.pack(blockLight, skyLight);
    }

    private record RenderBlock(BlockPos pos, int light,
                               PredictionFluidMesh fluidMesh,
                               BlockStateModel model, RenderLayer modelLayer,
                               float red, float green, float blue) {
        private RenderBlock {
            pos = pos.toImmutable();
        }
    }

    /**
     * Extraction-only view used by vanilla fluid tessellation. Every neighbor
     * read uses the same logical TEMP-over-DIRECT composition as collision and
     * terrain, while lighting, biome tint, and block entities delegate to the
     * real client world. This view is never attached to the render state.
     */
    private record ComposedRenderView(ClientWorld world) implements BlockRenderView {
        @Override
        public BlockState getBlockState(final BlockPos pos) {
            final BlockState authoritative = world.getBlockState(pos);
            return ExactPredictionRuntime.composedVisualBlockState(
                    world, pos, authoritative);
        }

        @Override
        public FluidState getFluidState(final BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(final BlockPos pos) {
            return world.getBlockEntity(pos);
        }

        @Override
        public float getBrightness(final Direction direction, final boolean shaded) {
            return world.getBrightness(direction, shaded);
        }

        @Override
        public LightingProvider getLightingProvider() {
            return world.getLightingProvider();
        }

        @Override
        public int getColor(final BlockPos pos, final ColorResolver colorResolver) {
            return world.getColor(pos, colorResolver);
        }

        @Override
        public int getHeight() {
            return world.getHeight();
        }

        @Override
        public int getBottomY() {
            return world.getBottomY();
        }
    }
}
