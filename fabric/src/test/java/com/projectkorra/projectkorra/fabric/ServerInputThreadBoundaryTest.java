package com.projectkorra.projectkorra.fabric;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Check the injection against actual Minecraft bytecode without booting a world. */
class ServerInputThreadBoundaryTest {
    @ParameterizedTest
    @ValueSource(strings = {"onHandSwing", "onClientCommand", "onPlayerInput", "onPlayerAction",
            "onPlayerInteractBlock", "onPlayerInteractItem", "onPlayerInteractEntity", "onUpdateSelectedSlot"})
    void bendingInputRunsOnlyAfterVanillaHasMovedThePacketOntoTheServerThread(String packetHandler) throws Exception {
        ClassNode mixin = read("com/projectkorra/projectkorra/fabric/mixin/ServerPlayNetworkHandlerMixin");
        AnnotationNode injection = mixin.methods.stream()
                .flatMap(method -> annotations(method).stream())
                .filter(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;"))
                .filter(annotation -> ((List<?>) value(annotation, "method")).contains(packetHandler))
                .findFirst().orElseThrow();
        assertNotEquals(0, value(injection, "require"), "missing input hooks must fail loudly");
        AnnotationNode at = (AnnotationNode) ((List<?>) value(injection, "at")).getFirst();
        assertEquals("INVOKE", value(at, "value"));
        assertEquals("AFTER", ((String[]) value(at, "shift"))[1],
                "before forceMainThread runs on the network thread and again on the server thread");

        String target = (String) value(at, "target");
        assertTrue(target.startsWith("Lnet/minecraft/network/NetworkThreadUtils;forceMainThread("));
        ClassNode vanilla = read("net/minecraft/server/network/ServerPlayNetworkHandler");
        MethodNode handler = vanilla.methods.stream().filter(method -> method.name.equals(packetHandler))
                .findFirst().orElseThrow();
        long matches = 0;
        for (var instruction : handler.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && target.equals("L" + call.owner + ";" + call.name + call.desc)) matches++;
        }
        assertEquals(1, matches, "the pinned Minecraft version must have exactly one matching thread handoff");
    }

    private static List<AnnotationNode> annotations(MethodNode method) {
        return method.visibleAnnotations == null ? List.of() : method.visibleAnnotations;
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values != null) {
            for (int i = 0; i < annotation.values.size(); i += 2) {
                if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
            }
        }
        return null;
    }

    private static ClassNode read(String name) throws IOException {
        try (var input = ServerInputThreadBoundaryTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, name);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }
}
