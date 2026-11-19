package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeMethods;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.*;
import org.bukkit.craftbukkit.event.CraftEventFactory;

import java.lang.invoke.MethodHandle;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.Set;

/** The private copies route native block and fluid events through the enrolled world's plugin manager. */
final class PaperRollbackTerrainEvents {
    private static final Map<String, Set<String>> PRIVATE_CALLS = Map.of(
            "net/minecraft/server/level/ServerLevel", Set.of("getBlockState", "getFluidState", "setBlock", "setBlockAndUpdate",
                    "destroyBlock", "removeBlock", "scheduleTick", "getRandom", "hasNeighborSignal", "isRaining", "getGameTime"),
            "net/minecraft/world/level/block/state/BlockState", Set.of("is", "isAir", "getValue", "setValue", "cycle",
                    "hasProperty", "getFluidState", "getBlock"),
            "net/minecraft/core/BlockPos", Set.of("above", "below", "north", "south", "west", "east", "relative", "offset", "getX", "getY", "getZ"),
            "net/minecraft/util/RandomSource", Set.of("nextBoolean", "nextInt", "nextFloat", "nextDouble"),
            "java/lang/Boolean", Set.of("booleanValue", "valueOf"), "java/lang/Integer", Set.of("intValue", "valueOf"),
            "java/lang/Math", Set.of("min", "max", "floor", "ceil", "abs"));
    private static final Set<Method> PRIVATE_TICKS = discoverPrivateTicks();

    /** Accept leaf callbacks whose bytecode can access only immutable block properties and the private terrain services. */
    private static Set<Method> discoverPrivateTicks() {
        var entries = new HashSet<Method>();
        for (var block : BuiltInRegistries.BLOCK) {
            for (Class<?> type = block.getClass(); type != BlockBehaviour.class; type = type.getSuperclass()) {
                try {
                    var method = type.getDeclaredMethod("tick", BlockState.class, ServerLevel.class, BlockPos.class, RandomSource.class);
                    if (type != RedstoneLampBlock.class) entries.add(method);
                    break;
                } catch (NoSuchMethodException inherited) { /* Continue to the actual override. */ }
            }
        }
        entries.removeIf(method -> !isPrivateLeaf(method));
        return Set.copyOf(entries);
    }
    private static boolean isPrivateLeaf(Method callback) {
        var owner = callback.getDeclaringClass();
        var descriptor = Type.getMethodDescriptor(callback);
        boolean[] safe = {true}; boolean[] found = {false};
        try (var input = owner.getResourceAsStream("/" + Type.getInternalName(owner) + ".class")) {
            if (input == null) return false;
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    if (!name.equals(callback.getName()) || !desc.equals(descriptor)) return null;
                    found[0] = true;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String target, String method, String desc, boolean iface) {
                            if (!PRIVATE_CALLS.getOrDefault(target, Set.of()).contains(method)) safe[0] = false;
                        }
                        @Override public void visitFieldInsn(int opcode, String target, String field, String desc) {
                            // Registered blocks, fluids, directions and state properties are immutable definitions.
                            if (opcode != Opcodes.GETSTATIC || !(target.equals("net/minecraft/world/level/block/Blocks")
                                    || target.equals("net/minecraft/world/level/material/Fluids")
                                    || target.equals("net/minecraft/core/Direction")
                                    || desc.startsWith("Lnet/minecraft/world/level/block/state/properties/"))) safe[0] = false;
                        }
                        @Override public void visitInvokeDynamicInsn(String name, String desc, net.bytebuddy.jar.asm.Handle bootstrap, Object... args) {
                            safe[0] = false;
                        }
                        @Override public void visitTypeInsn(int opcode, String type) { if (opcode == Opcodes.NEW) safe[0] = false; }
                        @Override public void visitInsn(int opcode) {
                            if (opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE || opcode == Opcodes.MONITORENTER) safe[0] = false;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return found[0] && safe[0];
        } catch (IOException failure) { throw new IllegalStateException("Read private native tick " + callback, failure); }
    }
    private final MethodHandle blockTick, fluidTick;
    private final ServerLevel world;

    PaperRollbackTerrainEvents(PaperRollbackWorldAccess access) {
        world = access.world();
        var methods = new RollbackNativeMethods();
        access.bindEvents(methods);
        try {
            var block = BlockBehaviour.BlockStateBase.class.getMethod("tick", ServerLevel.class, BlockPos.class, RandomSource.class);
            var fluid = FluidState.class.getMethod("tick", ServerLevel.class, BlockPos.class, BlockState.class);
            methods.copy(block).copy(fluid);
            // Virtual dispatch rejects a native override until all of its external side effects are bound.
            copy(methods, BlockBehaviour.class, Set.of("tick"));
            copy(methods, RedstoneLampBlock.class, Set.of("tick"));
            for (Method leaf : PRIVATE_TICKS) methods.copy(leaf);
            copy(methods, Fluid.class, Set.of("tick"));
            copy(methods, FlowingFluid.class, Set.of("tick", "spread", "spreadToSides", "spreadTo", "beforeDestroyingBlock"));
            // Flow into a destructible block and lava replacements require additional private world effects.
            // Overridden callbacks reach the checked virtual dispatch in FlowingFluid.spreadTo.
            copy(methods, CraftEventFactory.class, Set.of("callRedstoneChange", "callFluidLevelChangeEvent"));
            var compiled = methods.build();
            blockTick = compiled.get(block);
            fluidTick = compiled.get(fluid);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Native terrain callback signatures changed", failure);
        }
    }
    private static void copy(RollbackNativeMethods methods, Class<?> source, Set<String> names) {
        for (var method : source.getDeclaredMethods()) if (names.contains(method.getName()) && !method.isBridge()) {
            if (java.lang.reflect.Modifier.isAbstract(method.getModifiers())) methods.dispatch(method);
            else methods.copy(method);
        }
    }
    void block(BlockState state, BlockPos position) {
        try { blockTick.invoke(state, world, position, world.getRandom()); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Private block tick failed", failure); }
    }
    void fluid(FluidState state, BlockPos position, BlockState block) {
        try { fluidTick.invoke(state, world, position, block); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Private fluid tick failed", failure); }
    }
}
