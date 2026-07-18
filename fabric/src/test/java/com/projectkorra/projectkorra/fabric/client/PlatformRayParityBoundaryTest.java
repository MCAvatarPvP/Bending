package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents Paper/Fabric source selection from using different hit shapes. */
class PlatformRayParityBoundaryTest {
    @Test
    void exactTargetRaysIgnorePassableOutlinesOnEveryPlatform() throws IOException {
        String client = source("fabric/src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricPredictionMC.java");
        String fabricServer = source("fabric/src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricMC.java");
        String paper = source("bukkit/src/main/java/com/projectkorra/projectkorra/platform/bukkit/BukkitMC.java");

        String clientTarget = method(client, "@Override public Block getTargetBlockExact", "public Block getTargetBlock(Set<Material>");
        String fabricTarget = method(fabricServer, "@Override public Block getTargetBlockExact", "public Block getTargetBlock(Set<Material>");
        String paperTarget = method(paper, "public Block getTargetBlockExact", "public Block getTargetBlock(Set<Material>");

        assertTrue(clientTarget.contains("RaycastContext.ShapeType.COLLIDER"));
        assertTrue(fabricTarget.contains("RaycastContext.ShapeType.COLLIDER"));
        assertFalse(clientTarget.contains("ShapeType.OUTLINE"));
        assertFalse(fabricTarget.contains("ShapeType.OUTLINE"));
        assertTrue(paperTarget.contains("FluidCollisionMode.NEVER, true"),
                "Fabric COLLIDER/NONE must retain Bukkit's ignore-passable/no-fluid contract");
        assertTrue(clientTarget.contains("paperDirection("));
        assertFalse(clientTarget.contains("Vec3d.fromPolar") || clientTarget.contains("getRotationVec"),
                "client source rays must use Bukkit's double-precision yaw/pitch conversion");
        assertTrue(client.contains("horizontal * Math.cos(yawRadians)).normalize()"),
                "Paper normalizes Location#getDirection before its stepped target-block ray");
    }

    @Test
    void steppedSourceRaysUseBukkitDirectionInsteadOfMinecraftFloatPolarMath() throws IOException {
        String client = source("fabric/src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricPredictionMC.java");
        String target = method(client, "public Block getTargetBlock(Set<Material>",
                "public List<Block> getLastTwoTargetBlocks");
        String lastTwo = method(client, "public List<Block> getLastTwoTargetBlocks",
                "@Override public boolean hasLineOfSight");

        assertTrue(target.contains("paperDirection(") && lastTwo.contains("paperDirection("));
        assertFalse(target.contains("Vec3d.fromPolar") || target.contains("getRotationVec"));
        assertFalse(lastTwo.contains("Vec3d.fromPolar") || lastTwo.contains("getRotationVec"));
        assertTrue(client.contains("final double yawRadians = Math.toRadians(yaw)")
                        && client.contains("final double pitchRadians = Math.toRadians(pitch)"),
                "the predicted ray must reproduce Bukkit Location#getDirection exactly at block boundaries");
        assertTrue(client.contains("horizontal * Math.cos(yawRadians)).normalize()"),
                "the final unit normalization is observable at exact block boundaries");
    }

    @Test
    void hashBackedBlockTraversalAndSolidityMatchPaper() throws IOException {
        String client = source("fabric/src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricPredictionMC.java");
        String paper = source("bukkit/src/main/java/com/projectkorra/projectkorra/platform/bukkit/BukkitMC.java");

        assertTrue(client.contains("return Objects.hash(pos.getX(), pos.getY(), pos.getZ())"));
        assertTrue(paper.contains("return Objects.hash(value.getX(), value.getY(), value.getZ())"));
        assertTrue(client.contains("@Override public int hashCode() { return 0; }"));
        assertTrue(paper.contains("public int hashCode() {\n            return 0;"));
        assertTrue(client.contains("@Override public boolean isSolid() { return getType().isSolid(); }"),
                "source and collision searches must use Bukkit material solidity, not a different native predicate");
    }

    private static String source(final String relative) throws IOException {
        Path path = Path.of(relative);
        if (!Files.exists(path) && relative.startsWith("fabric/")) path = Path.of(relative.substring(7));
        if (!Files.exists(path)) path = Path.of("..").resolve(relative);
        assertTrue(Files.exists(path), path.toString());
        return Files.readString(path);
    }

    private static String method(final String source, final String startMarker, final String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue(start >= 0 && end > start, startMarker);
        return source.substring(start, end);
    }
}
