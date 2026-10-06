package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.prediction.rollback.RollbackClock;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.ConstantDynamic;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Copies explicitly audited native method bodies into private hidden nestmates.
 * Calls selected by the adapter resolve private replacements; original loaded
 * classes and ordinary gameplay are never modified. This is not a sandbox: every
 * uncopied call, field, constructor and bootstrap still needs a platform audit.
 * Virtual calls to copied bodies or explicit declarations retain dispatch among the configured methods.
 * An unconfigured override fails instead of running its original body. Explicit
 * replacements bind the selected call site; other calls still require an audit.
 * The caller must supply owned receivers/state and keep all handles private.
 */
public final class RollbackNativeMethods {
    private static final AtomicLong IDS = new AtomicLong();
    private static final Handle CLASS_DATA = new Handle(Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/MethodHandles", "classDataAt",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;I)Ljava/lang/Object;", false);
    private static final ClassValue<MethodHandles.Lookup> LOOKUPS = new ClassValue<>() {
        @Override protected MethodHandles.Lookup computeValue(Class<?> owner) {
            try {
                var access = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
                if (access.hasFullPrivilegeAccess()) return access;
                // Cross-loader privateLookupIn loses MODULE access. A helper in
                // the native module obtains a full lookup without an agent or a
                // reference from the native loader back into the plugin loader.
                String helper = Type.getInternalName(owner) + "$RollbackAccess$" + IDS.incrementAndGet();
                var writer = new ClassWriter(0);
                writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, helper, null, "java/lang/Object", null);
                var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "lookup",
                        "()Ljava/lang/invoke/MethodHandles$Lookup;", null, null);
                method.visitCode();
                method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodHandles", "lookup",
                        "()Ljava/lang/invoke/MethodHandles$Lookup;", false);
                method.visitInsn(Opcodes.ARETURN);
                method.visitMaxs(1, 0); method.visitEnd(); writer.visitEnd();
                Class<?> defined = access.defineClass(writer.toByteArray());
                var nativeLookup = (MethodHandles.Lookup) access.findStatic(defined, "lookup",
                        MethodType.methodType(MethodHandles.Lookup.class)).invokeExact();
                return MethodHandles.privateLookupIn(owner, nativeLookup);
            } catch (Throwable failure) {
                if (failure instanceof Error error) throw error;
                throw new IllegalStateException("Cannot obtain private native method access for " + owner.getName(), failure);
            }
        }
    };

    private final Thread thread = Thread.currentThread();
    private final Map<Method, MutableCallSite> copies = new LinkedHashMap<>();
    private final Map<Method, MethodHandle> replacements = new LinkedHashMap<>();
    private final Map<Method, MethodHandle> before = new LinkedHashMap<>();
    private final Map<Constructor<?>, MethodHandle> factories = new LinkedHashMap<>();
    private final Map<Field, MethodHandle> fieldReads = new LinkedHashMap<>();
    private final Set<Method> lambdaCopies = new HashSet<>();
    private final Set<Method> dispatchDeclarations = new java.util.LinkedHashSet<>();
    private boolean built;

    public RollbackNativeMethods copy(Method method) {
        configure();
        int modifiers = method.getModifiers();
        if (Modifier.isAbstract(modifiers) || Modifier.isNative(modifiers) || Modifier.isSynchronized(modifiers)) {
            throw new IllegalArgumentException("Native body cannot be copied: " + method);
        }
        if (replacements.containsKey(method) || dispatchDeclarations.contains(method) || copies.putIfAbsent(method, new MutableCallSite(type(method))) != null) {
            throw new IllegalArgumentException("Native method already configured: " + method);
        }
        return this;
    }

    public RollbackNativeMethods replace(Method method, MethodHandle replacement) {
        configure();
        Objects.requireNonNull(replacement, "replacement");
        if (!type(method).equals(replacement.type())) throw new IllegalArgumentException("Native replacement type: " + method);
        if (copies.containsKey(method) || dispatchDeclarations.contains(method) || replacements.putIfAbsent(method, replacement) != null) {
            throw new IllegalArgumentException("Native method already configured: " + method);
        }
        return this;
    }

    /** Prepend an owned-state phase to a copied body, preserving its native implementation and copied virtual/super call routes. */
    public RollbackNativeMethods before(Method method, MethodHandle phase) {
        configure(); Objects.requireNonNull(phase, "phase");
        if (!copies.containsKey(method) || !phase.type().equals(type(method).changeReturnType(void.class))) {
            throw new IllegalArgumentException("Native entry phase requires a copied body and matching void signature: " + method);
        }
        if (before.putIfAbsent(method, phase) != null) throw new IllegalArgumentException("Native entry phase already configured: " + method);
        return this;
    }

    /** Route a virtual declaration to an audited override, without authorizing its original body. */
    public RollbackNativeMethods dispatch(Method declaration) {
        configure();
        int access = declaration.getModifiers();
        if (Modifier.isStatic(access) || Modifier.isPrivate(access) || Modifier.isFinal(access)) {
            throw new IllegalArgumentException("Expected overridable native declaration: " + declaration);
        }
        if (copies.containsKey(declaration) || replacements.containsKey(declaration) || !dispatchDeclarations.add(declaration)) {
            throw new IllegalArgumentException("Native method already configured: " + declaration);
        }
        return this;
    }

    /** Explicitly audits a compiler-generated lambda body and its private call routes. */
    public RollbackNativeMethods copyLambda(Method method) {
        configure();
        if (!method.isSynthetic() || !Modifier.isPrivate(method.getModifiers()) || !method.getName().startsWith("lambda$")) {
            throw new IllegalArgumentException("Native lambda body is not private and compiler-generated: " + method);
        }
        copy(method);
        lambdaCopies.add(method);
        return this;
    }

    /**
     * Routes an audited field read to private state without changing the loaded
     * native field. Instance getters receive the declaring-class receiver. Writes
     * to a substituted field in copied code are rejected rather than reaching the
     * original field. Uncopied code still requires its own platform audit.
     */
    public RollbackNativeMethods read(Field field, MethodHandle getter) {
        configure();
        Objects.requireNonNull(field, "field");
        MethodType expected = MethodType.methodType(field.getType());
        if (!Modifier.isStatic(field.getModifiers())) expected = expected.insertParameterTypes(0, field.getDeclaringClass());
        if (!Objects.requireNonNull(getter, "getter").type().equals(expected)) throw new IllegalArgumentException("Native field getter type: " + field);
        if (fieldReads.putIfAbsent(field, getter) != null) throw new IllegalArgumentException("Native field already configured: " + field);
        return this;
    }

    /**
     * Replaces an audited NEW/DUP/argument-loads/constructor expression. Complex
     * allocations, aliases and constructor method references fail compilation;
     * they must not accidentally call an original constructor with live services.
     */
    public RollbackNativeMethods construct(Constructor<?> constructor, MethodHandle factory) {
        configure();
        var expected = MethodType.methodType(constructor.getDeclaringClass(), constructor.getParameterTypes());
        if (!Objects.requireNonNull(factory, "factory").type().equals(expected)) throw new IllegalArgumentException("Native factory type: " + constructor);
        if (factories.putIfAbsent(constructor, factory) != null) throw new IllegalArgumentException("Native constructor already configured: " + constructor);
        return this;
    }

    public Map<Method, MethodHandle> build() {
        configure(); built = true;
        Map<Method, MethodHandle> calls = new LinkedHashMap<>(replacements);
        copies.forEach((method, site) -> calls.put(method, site.dynamicInvoker()));
        Map<Method, MethodHandle> virtualCalls = new LinkedHashMap<>(calls);
        copies.keySet().stream().filter(method -> !Modifier.isStatic(method.getModifiers()) && !Modifier.isPrivate(method.getModifiers()))
                .forEach(method -> virtualCalls.put(method, virtual(method, calls)));
        dispatchDeclarations.forEach(method -> virtualCalls.put(method, virtual(method, calls)));
        Map<Method, MethodHandle> result = new LinkedHashMap<>();
        copies.forEach((method, site) -> {
            var body = compile(method, calls, virtualCalls, factories, fieldReads, lambdaCopies);
            result.put(method, before.containsKey(method) ? MethodHandles.foldArguments(body, before.get(method)) : body);
        });
        copies.forEach((method, site) -> site.setTarget(result.get(method)));
        MutableCallSite.syncAll(copies.values().toArray(MutableCallSite[]::new));
        return Map.copyOf(result);
    }

    private static MethodType type(Method method) {
        var type = MethodType.methodType(method.getReturnType(), method.getParameterTypes());
        return Modifier.isStatic(method.getModifiers()) ? type : type.insertParameterTypes(0, method.getDeclaringClass());
    }

    private void configure() {
        if (Thread.currentThread() != thread || RollbackClock.active() || built) throw new IllegalStateException("Native method configuration is closed or outside its bootstrap thread");
    }

    private static MethodHandle virtual(Method declaration, Map<Method, MethodHandle> calls) {
        var targets = new ClassValue<MethodHandle>() {
            @Override protected MethodHandle computeValue(Class<?> receiver) {
                Method selected = declaration;
                var hierarchy = new ArrayList<Class<?>>();
                for (Class<?> current = receiver; current != null && current != declaration.getDeclaringClass(); current = current.getSuperclass()) hierarchy.add(current);
                for (int index = hierarchy.size() - 1; index >= 0; index--) {
                    Class<?> current = hierarchy.get(index);
                    for (Method candidate : current.getDeclaredMethods()) {
                        int access = selected.getModifiers();
                        boolean visible = Modifier.isPublic(access) || Modifier.isProtected(access)
                                || (selected.getDeclaringClass().getClassLoader() == current.getClassLoader()
                                && selected.getDeclaringClass().getPackageName().equals(current.getPackageName()));
                        if (visible && !Modifier.isPrivate(candidate.getModifiers()) && !Modifier.isStatic(candidate.getModifiers())
                                && candidate.getName().equals(declaration.getName()) && candidate.getReturnType() == declaration.getReturnType()
                                && java.util.Arrays.equals(candidate.getParameterTypes(), declaration.getParameterTypes())) selected = candidate;
                    }
                }
                if (selected.equals(declaration) && declaration.getDeclaringClass().isInterface()) {
                    selected = interfaceDefault(declaration, receiver);
                }
                MethodHandle target = calls.get(selected);
                if (target == null) throw new IllegalArgumentException("Unbound native override: " + selected);
                return target.asType(type(declaration));
            }
        };
        try {
            MethodHandle select = MethodHandles.lookup().findStatic(RollbackNativeMethods.class, "virtualTarget",
                    MethodType.methodType(MethodHandle.class, ClassValue.class, Object.class)).bindTo(targets)
                    .asType(MethodType.methodType(MethodHandle.class, declaration.getDeclaringClass()));
            return MethodHandles.foldArguments(MethodHandles.exactInvoker(type(declaration)), select);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native virtual dispatch unavailable", failure); }
    }

    private static MethodHandle virtualTarget(ClassValue<MethodHandle> targets, Object receiver) { return targets.get(receiver.getClass()); }

    /** Class methods win; otherwise invokeinterface selects the most specific inherited default. */
    private static Method interfaceDefault(Method declaration, Class<?> receiver) {
        var interfaces = new java.util.HashSet<Class<?>>();
        for (Class<?> type = receiver; type != null; type = type.getSuperclass()) collectInterfaces(type, interfaces);
        var candidates = new ArrayList<Method>();
        for (Class<?> type : interfaces) {
            if (!declaration.getDeclaringClass().isAssignableFrom(type)) continue;
            for (Method method : type.getDeclaredMethods()) {
                if (!Modifier.isPrivate(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())
                        && method.getName().equals(declaration.getName()) && method.getReturnType() == declaration.getReturnType()
                        && java.util.Arrays.equals(method.getParameterTypes(), declaration.getParameterTypes())) candidates.add(method);
            }
        }
        var maximal = candidates.stream().filter(candidate -> candidates.stream().noneMatch(other ->
                !candidate.equals(other) && candidate.getDeclaringClass().isAssignableFrom(other.getDeclaringClass()))).toList();
        if (maximal.size() == 1) return maximal.getFirst();
        var defaults = maximal.stream().filter(Method::isDefault).toList();
        if (defaults.size() == 1) return defaults.getFirst();
        throw new IllegalArgumentException("Ambiguous or missing native interface implementation: " + declaration + " on " + receiver);
    }

    private static void collectInterfaces(Class<?> type, java.util.Set<Class<?>> interfaces) {
        for (Class<?> inherited : type.getInterfaces()) if (interfaces.add(inherited)) collectInterfaces(inherited, interfaces);
    }

    private static MethodHandle compile(Method method, Map<Method, MethodHandle> calls, Map<Method, MethodHandle> virtualCalls,
            Map<Constructor<?>, MethodHandle> factories, Map<Field, MethodHandle> fieldReads, Set<Method> lambdaCopies) {
        Class<?> owner = method.getDeclaringClass();
        String originalName = Type.getInternalName(owner);
        String copiedName = originalName + "$RollbackBody$" + IDS.incrementAndGet();
        var lookup = LOOKUPS.get(owner);
        byte[] original;
        try (var input = owner.getResourceAsStream("/" + originalName + ".class")) {
            if (input == null) throw new IllegalStateException("Native class bytes unavailable: " + owner);
            original = input.readAllBytes();
        } catch (IOException failure) { throw new IllegalStateException("Native class read failed", failure); }
        String descriptor = Type.getMethodDescriptor(method);
        var data = new ArrayList<MethodHandle>();
        var allocations = new java.util.HashSet<String>();
        factories.keySet().forEach(constructor -> allocations.add(Type.getInternalName(constructor.getDeclaringClass())));
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected ClassLoader getClassLoader() { return owner.getClassLoader(); }
        };
        writer.visit(new ClassReader(original).readShort(6), Opcodes.ACC_FINAL, copiedName, null, "java/lang/Object", null);
        int[] found = {0};
        // Preserve the original local layout, including its implicit receiver at
        // slot zero. Spill replacement arguments above every original local slot.
        int[] maximumLocals = {0};
        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (!name.equals(method.getName()) || !desc.equals(descriptor)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMaxs(int stack, int locals) { maximumLocals[0] = locals; }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (!name.equals(method.getName()) || !desc.equals(descriptor)) return null;
                found[0]++;
                var target = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "invoke", type(method).descriptorString(), null, exceptions);
                return new MethodVisitor(Opcodes.ASM9, target) {
                    private String allocating;
                    private boolean duplicated;
                    private void idle() {
                        if (allocating != null) throw new IllegalArgumentException("Unsupported native allocation expression in " + method + ": " + allocating);
                    }
                    private void argument() {
                        if (allocating != null && !duplicated) throw new IllegalArgumentException("Native allocation requires NEW/DUP: " + method);
                    }
                    @Override public void visitTypeInsn(int opcode, String type) {
                        idle();
                        if (opcode == Opcodes.NEW && allocations.contains(type)) { allocating = type; duplicated = false; return; }
                        super.visitTypeInsn(opcode, type);
                    }
                    @Override public void visitInsn(int opcode) {
                        if (allocating != null) {
                            if (opcode == Opcodes.DUP && !duplicated) { duplicated = true; return; }
                            argument();
                            if (opcode < Opcodes.ACONST_NULL || opcode > Opcodes.DCONST_1) idle();
                        }
                        super.visitInsn(opcode);
                    }
                    @Override public void visitVarInsn(int opcode, int local) {
                        argument();
                        if (allocating != null && (opcode < Opcodes.ILOAD || opcode > Opcodes.ALOAD)) idle();
                        super.visitVarInsn(opcode, local);
                    }
                    @Override public void visitIntInsn(int opcode, int operand) {
                        argument();
                        if (allocating != null && opcode != Opcodes.BIPUSH && opcode != Opcodes.SIPUSH) idle();
                        super.visitIntInsn(opcode, operand);
                    }
                    @Override public void visitLdcInsn(Object value) { argument(); super.visitLdcInsn(value); }
                    @Override public void visitFieldInsn(int opcode, String fieldOwner, String name, String descriptor) {
                        idle();
                        Class<?> target = load(fieldOwner, owner.getClassLoader());
                        var field = resolveField(target, name);
                        MethodHandle routed = fieldReads.get(field);
                        if (routed != null) {
                            if (opcode != Opcodes.GETFIELD && opcode != Opcodes.GETSTATIC) {
                                throw new IllegalArgumentException("Native body writes a substituted field: " + field);
                            }
                            MethodType invocation = MethodType.methodType(field.getType());
                            if (opcode == Opcodes.GETFIELD) invocation = invocation.insertParameterTypes(0, target);
                            invoke(routed.asType(invocation), invocation);
                            return;
                        }
                        if (!Modifier.isPublic(field.getModifiers()) && field.getDeclaringClass().getNestHost() != owner.getNestHost()) {
                            try {
                                var access = LOOKUPS.get(field.getDeclaringClass());
                                boolean write = opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC;
                                MethodHandle handle = write ? access.unreflectSetter(field) : access.unreflectGetter(field);
                                MethodType invocation = write ? MethodType.methodType(void.class, field.getType()) : MethodType.methodType(field.getType());
                                if (opcode == Opcodes.PUTFIELD || opcode == Opcodes.GETFIELD) invocation = invocation.insertParameterTypes(0, target);
                                invoke(handle.asType(invocation), invocation);
                                return;
                            } catch (IllegalAccessException failure) { throw new IllegalStateException("Native inherited field unavailable", failure); }
                        }
                        super.visitFieldInsn(opcode, fieldOwner, name, descriptor);
                    }
                    @Override public void visitJumpInsn(int opcode, net.bytebuddy.jar.asm.Label label) { idle(); super.visitJumpInsn(opcode, label); }
                    @Override public void visitLabel(net.bytebuddy.jar.asm.Label label) { idle(); super.visitLabel(label); }
                    @Override public void visitIincInsn(int local, int increment) { idle(); super.visitIincInsn(local, increment); }
                    @Override public void visitTableSwitchInsn(int min, int max, net.bytebuddy.jar.asm.Label dflt, net.bytebuddy.jar.asm.Label... labels) { idle(); super.visitTableSwitchInsn(min, max, dflt, labels); }
                    @Override public void visitLookupSwitchInsn(net.bytebuddy.jar.asm.Label dflt, int[] keys, net.bytebuddy.jar.asm.Label[] labels) { idle(); super.visitLookupSwitchInsn(dflt, keys, labels); }
                    @Override public void visitMultiANewArrayInsn(String desc, int dimensions) { idle(); super.visitMultiANewArrayInsn(desc, dimensions); }
                    @Override public void visitEnd() { idle(); super.visitEnd(); }
                    @Override public void visitMethodInsn(int opcode, String targetOwner, String targetName, String targetDescriptor, boolean isInterface) {
                        if (allocating != null) {
                            argument();
                            if (opcode != Opcodes.INVOKESPECIAL || !targetName.equals("<init>") || !allocating.equals(targetOwner)) idle();
                            try {
                                Class<?> constructed = load(targetOwner, owner.getClassLoader());
                                var signature = MethodType.fromMethodDescriptorString(targetDescriptor, owner.getClassLoader());
                                MethodHandle factory = factories.get(constructed.getDeclaredConstructor(signature.parameterArray()));
                                if (factory == null) throw new IllegalArgumentException("Unbound constructor for substituted allocation: " + targetOwner + targetDescriptor);
                                allocating = null;
                                invoke(factory, signature.changeReturnType(constructed));
                                return;
                            } catch (NoSuchMethodException failure) { throw new IllegalStateException("Native constructor unavailable", failure); }
                        }
                        if (!targetName.equals("<init>")) {
                            Method resolved = resolve(targetOwner, targetName, targetDescriptor, owner.getClassLoader());
                            MethodHandle replacement = (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE ? virtualCalls : calls).get(resolved);
                            MethodType invocation = MethodType.fromMethodDescriptorString(targetDescriptor, owner.getClassLoader());
                            if (opcode != Opcodes.INVOKESTATIC) invocation = invocation.insertParameterTypes(0, load(targetOwner, owner.getClassLoader()));
                            if (replacement == null && opcode == Opcodes.INVOKESPECIAL) {
                                try {
                                    replacement = lookup.findSpecial(load(targetOwner, owner.getClassLoader()), targetName,
                                            MethodType.fromMethodDescriptorString(targetDescriptor, owner.getClassLoader()), owner);
                                } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native super call unavailable", failure); }
                            }
                            if (replacement == null && !Modifier.isPublic(resolved.getModifiers())
                                    && resolved.getDeclaringClass().getNestHost() != owner.getNestHost()) {
                                try { replacement = LOOKUPS.get(resolved.getDeclaringClass()).unreflect(resolved); }
                                catch (IllegalAccessException failure) { throw new IllegalStateException("Native inherited method unavailable", failure); }
                            }
                            if (replacement != null) { invoke(replacement.asType(invocation), invocation); return; }
                        }
                        super.visitMethodInsn(opcode, targetOwner, targetName, targetDescriptor, isInterface);
                    }
                    @Override public void visitInvokeDynamicInsn(String name, String desc, Handle bootstrap, Object... arguments) {
                        idle();
                        // Capture the routed handle as the lambda's receiver.
                        // MethodHandle.invokeExact is directly accessible to the
                        // native loader; generated hidden bodies are not nameable
                        // from the VM's separate lambda implementation class.
                        arguments = arguments.clone();
                        MethodHandle lambda = null;
                        for (int index = 0; index < arguments.length; index++) {
                            Object argument = arguments[index];
                            if (argument instanceof Handle handle && handle.getTag() < Opcodes.H_INVOKEVIRTUAL
                                    && fieldReads.containsKey(resolveField(load(handle.getOwner(), owner.getClassLoader()), handle.getName()))) {
                                throw new IllegalArgumentException("Native bootstrap references a substituted field: " + handle);
                            }
                            if (argument instanceof Handle handle && handle.getName().equals("<init>") && allocations.contains(handle.getOwner())) {
                                throw new IllegalArgumentException("Native bootstrap references a substituted constructor: " + handle);
                            }
                            if (argument instanceof Handle handle && handle.getTag() >= Opcodes.H_INVOKEVIRTUAL && !handle.getName().equals("<init>")) {
                                Method callback = resolve(handle.getOwner(), handle.getName(), handle.getDesc(), owner.getClassLoader());
                                if (!calls.containsKey(callback)) continue;
                                if (index != 1 || !lambdaCopies.contains(callback) || bootstrap.getTag() != Opcodes.H_INVOKESTATIC
                                        || !bootstrap.getOwner().equals("java/lang/invoke/LambdaMetafactory") || !bootstrap.getName().equals("metafactory")) {
                                    throw new IllegalArgumentException("Native bootstrap references a substituted method: " + handle);
                                }
                                lambda = calls.get(callback);
                                arguments[index] = new Handle(Opcodes.H_INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact",
                                        lambda.type().descriptorString(), false);
                            }
                        }
                        if (lambda != null) {
                            Type[] captures = Type.getArgumentTypes(desc);
                            int[] locals = new int[captures.length];
                            int next = maximumLocals[0];
                            for (int i = 0; i < captures.length; i++) { locals[i] = next; next += captures[i].getSize(); }
                            for (int i = captures.length - 1; i >= 0; i--) super.visitVarInsn(captures[i].getOpcode(Opcodes.ISTORE), locals[i]);
                            int slot = data.size(); data.add(lambda);
                            super.visitLdcInsn(new ConstantDynamic("_", "Ljava/lang/invoke/MethodHandle;", CLASS_DATA, slot));
                            for (int i = 0; i < captures.length; i++) super.visitVarInsn(captures[i].getOpcode(Opcodes.ILOAD), locals[i]);
                            desc = MethodType.fromMethodDescriptorString(desc, owner.getClassLoader()).insertParameterTypes(0, MethodHandle.class).descriptorString();
                        }
                        super.visitInvokeDynamicInsn(name, desc, bootstrap, arguments);
                    }
                    private void invoke(MethodHandle handle, MethodType invocation) {
                        Type[] arguments = Type.getArgumentTypes(invocation.descriptorString());
                        int[] locals = new int[arguments.length];
                        int next = maximumLocals[0];
                        for (int i = 0; i < arguments.length; i++) { locals[i] = next; next += arguments[i].getSize(); }
                        for (int i = arguments.length - 1; i >= 0; i--) super.visitVarInsn(arguments[i].getOpcode(Opcodes.ISTORE), locals[i]);
                        int index = data.size(); data.add(handle);
                        super.visitLdcInsn(new ConstantDynamic("_", "Ljava/lang/invoke/MethodHandle;", CLASS_DATA, index));
                        for (int i = 0; i < arguments.length; i++) super.visitVarInsn(arguments[i].getOpcode(Opcodes.ILOAD), locals[i]);
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact", invocation.descriptorString(), false);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (found[0] != 1) throw new IllegalStateException("Native method body unavailable: " + method);
        writer.visitEnd();
        try {
            var hidden = lookup.defineHiddenClassWithClassData(writer.toByteArray(), List.copyOf(data), true, MethodHandles.Lookup.ClassOption.NESTMATE);
            return hidden.findStatic(hidden.lookupClass(), "invoke", type(method));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native body definition failed: " + method, failure); }
    }

    private static Class<?> load(String internalName, ClassLoader loader) {
        try { return Class.forName(internalName.replace('/', '.'), false, loader); }
        catch (ClassNotFoundException failure) { throw new IllegalStateException("Native call owner unavailable: " + internalName, failure); }
    }
    private static java.lang.reflect.Field resolveField(Class<?> owner, String name) {
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            try { return current.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { }
        }
        try { return owner.getField(name); }
        catch (NoSuchFieldException failure) { throw new IllegalStateException("Native field unavailable: " + owner + "." + name, failure); }
    }
    private static Method resolve(String owner, String name, String descriptor, ClassLoader loader) {
        Class<?> type = load(owner, loader);
        var signature = MethodType.fromMethodDescriptorString(descriptor, loader);
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && type(method).returnType() == signature.returnType()
                        && java.util.Arrays.equals(method.getParameterTypes(), signature.parameterArray())) return method;
            }
        }
        try { return type.getMethod(name, signature.parameterArray()); }
        catch (NoSuchMethodException failure) { throw new IllegalStateException("Native call method unavailable: " + owner + "." + name + descriptor, failure); }
    }
}
