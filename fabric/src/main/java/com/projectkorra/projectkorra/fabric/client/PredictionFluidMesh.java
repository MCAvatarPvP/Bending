package com.projectkorra.projectkorra.fabric.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.world.BlockRenderView;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable fluid geometry extracted from Minecraft's ordinary fluid renderer.
 *
 * <p>{@link BlockRenderManager#renderFluid} emits positions relative to the
 * containing chunk section. Capture normalizes those positions to the supplied
 * block's local origin, allowing the resulting mesh to be attached to a render
 * state and replayed later with the same block-to-camera matrix used by the
 * immediate predicted-block renderer.</p>
 *
 * <p>Tessellation must happen during world-render extraction while the supplied
 * {@link BlockRenderView} is safe to read. The returned mesh contains only
 * primitive vertex values and is consequently safe to replay during drawing.</p>
 */
@Environment(EnvType.CLIENT)
public final class PredictionFluidMesh {
    private static final PredictionFluidMesh EMPTY = new PredictionFluidMesh(List.of());

    private final List<Vertex> vertices;

    private PredictionFluidMesh(final List<Vertex> vertices) {
        this.vertices = List.copyOf(vertices);
    }

    /**
     * Tessellates the fluid contained in {@code state} using vanilla's fluid
     * renderer and the caller's composed view of neighboring blocks.
     *
     * @return immutable, block-local geometry, or an empty mesh when the state
     * has no fluid or vanilla emits no visible faces
     */
    public static PredictionFluidMesh tessellate(final BlockRenderManager renderer,
                                                  final BlockRenderView view,
                                                  final BlockPos pos,
                                                  final BlockState state) {
        Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(state, "state");

        final FluidState fluid = state.getFluidState();
        if (fluid.isEmpty()) return EMPTY;

        // FluidRenderer writes section-local coordinates (world coordinate &
        // 15). Normalize at capture time so replay needs only the caller's
        // ordinary block-local MatrixStack entry.
        final CapturingVertexConsumer capture = new CapturingVertexConsumer(
                pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        renderer.renderFluid(pos, view, capture, state, fluid);
        capture.finish();
        return capture.vertices.isEmpty()
                ? EMPTY : new PredictionFluidMesh(capture.vertices);
    }

    public boolean isEmpty() {
        return this.vertices.isEmpty();
    }

    public int vertexCount() {
        return this.vertices.size();
    }

    /**
     * Replays this mesh into a translucent-terrain consumer.
     *
     * <p>{@code matrix} should transform block-local coordinates into the
     * current render coordinate system. Position and normal transforms are
     * applied here; color, texture coordinates, and packed light values are
     * replayed exactly as vanilla emitted them.</p>
     */
    public void replay(final VertexConsumer consumer, final MatrixStack.Entry matrix) {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(matrix, "matrix");

        final Vector3f position = new Vector3f();
        final Vector3f normal = new Vector3f();
        for (Vertex vertex : this.vertices) {
            matrix.getPositionMatrix().transformPosition(
                    vertex.x, vertex.y, vertex.z, position);
            matrix.transformNormal(
                    vertex.normalX, vertex.normalY, vertex.normalZ, normal);
            consumer.vertex(position.x(), position.y(), position.z())
                    .color(vertex.argb)
                    .texture(vertex.u, vertex.v)
                    .light(vertex.light)
                    .normal(normal.x(), normal.y(), normal.z());
        }
    }

    private record Vertex(float x, float y, float z, int argb,
                          float u, float v, int light,
                          float normalX, float normalY, float normalZ) {
    }

    /** Captures the attribute order emitted by FluidRenderer#vertex. */
    private static final class CapturingVertexConsumer implements VertexConsumer {
        private final List<Vertex> vertices = new ArrayList<>();
        private final float originX;
        private final float originY;
        private final float originZ;

        private boolean open;
        private float x;
        private float y;
        private float z;
        private int argb;
        private float u;
        private float v;
        private int light;

        private CapturingVertexConsumer(final float originX, final float originY,
                                        final float originZ) {
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
        }

        @Override
        public VertexConsumer vertex(final float x, final float y, final float z) {
            if (this.open) {
                throw new IllegalStateException("Fluid renderer started a vertex before completing the previous one");
            }
            this.open = true;
            this.x = x - this.originX;
            this.y = y - this.originY;
            this.z = z - this.originZ;
            this.argb = 0xFFFFFFFF;
            this.u = 0.0F;
            this.v = 0.0F;
            this.light = 0;
            return this;
        }

        @Override
        public VertexConsumer color(final int red, final int green, final int blue,
                                    final int alpha) {
            requireOpen();
            this.argb = ColorHelper.getArgb(alpha, red, green, blue);
            return this;
        }

        @Override
        public VertexConsumer color(final int argb) {
            requireOpen();
            this.argb = argb;
            return this;
        }

        @Override
        public VertexConsumer texture(final float u, final float v) {
            requireOpen();
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer overlay(final int u, final int v) {
            // Fluids use POSITION_COLOR_TEXTURE_LIGHT_NORMAL and do not emit
            // overlay coordinates. Accepting the call keeps this capture
            // tolerant of a future vanilla implementation that supplies one.
            requireOpen();
            return this;
        }

        @Override
        public VertexConsumer light(final int u, final int v) {
            requireOpen();
            this.light = u & 0xFFFF | (v & 0xFFFF) << 16;
            return this;
        }

        @Override
        public VertexConsumer light(final int packedLight) {
            requireOpen();
            this.light = packedLight;
            return this;
        }

        @Override
        public VertexConsumer normal(final float x, final float y, final float z) {
            requireOpen();
            this.vertices.add(new Vertex(
                    this.x, this.y, this.z, this.argb, this.u, this.v, this.light,
                    x, y, z));
            this.open = false;
            return this;
        }

        @Override
        public VertexConsumer lineWidth(final float width) {
            requireOpen();
            return this;
        }

        private void requireOpen() {
            if (!this.open) {
                throw new IllegalStateException("Fluid renderer supplied an attribute without a vertex");
            }
        }

        private void finish() {
            if (this.open) {
                throw new IllegalStateException("Fluid renderer left an incomplete vertex");
            }
        }
    }
}
