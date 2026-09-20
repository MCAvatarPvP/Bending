package me.moros.hyperion.abilities.earthbending;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.PKEventBus;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Arrow;
import com.projectkorra.projectkorra.platform.mc.metadata.FixedMetadataValue;
import com.projectkorra.projectkorra.platform.mc.metadata.MetadataValue;
import com.projectkorra.projectkorra.platform.mc.event.entity.ProjectileHitEvent;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.movement.VelocitySync;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.support.AbilityWorld;
import com.projectkorra.projectkorra.util.TempBlock;
import me.moros.hyperion.listeners.HyperionCommonListener;
import me.moros.hyperion.methods.CoreMethods;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetalCableTest {
    private final AbilityWorld world = new AbilityWorld();
    private final AbilityWorld.TestPlayer player = world.player(0, 64, 0);
    private final TestArrow arrow = new TestArrow();
    private Vector publishedVelocity;
    private CoreAbility velocityOwner;
    private final VelocitySync.Listener velocities = (ability, entity, velocity) -> {
        assertSame(player, entity);
        velocityOwner = (CoreAbility) ability;
        publishedVelocity = velocity;
    };

    @BeforeEach void setup() {
        ProjectKorraPlatform delegate = Platform.current();
        PKEventBus events = (PKEventBus) Proxy.newProxyInstance(PKEventBus.class.getClassLoader(),
                new Class<?>[]{PKEventBus.class}, (proxy, method, args) -> null);
        Platform.install((ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(),
                new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, args) ->
                        method.getName().equals("events") ? events : method.invoke(delegate, args)));
        BendingPlayer.getPlayers().put(player.getUniqueId(), new BendingPlayer(player) {
            @Override public boolean canBend(CoreAbility ability) { return false; }
            @Override public boolean canBendIgnoreCooldowns(CoreAbility ability) { return true; }
        });
        VelocitySync.install(velocities);
    }

    @AfterEach void cleanup() throws Exception {
        VelocitySync.clear(velocities);
        TempBlock.removeAll();
        RegionProtection.clearCache(player);
        BendingPlayer.getPlayers().remove(player.getUniqueId());
        world.close();
    }

    @Test void blockImpactKeepsOriginalProgressPositionAndStartsOwnedPull() throws Exception {
        MetalCable cable = cable();
        arrow.position = new Location(world, 10, 64, 0);
        Block block = world.getBlockAt(10, 64, 0);
        block.setType(Material.STONE);
        hit(cable, block);
        assertEquals(new Location(world, 1, 64, 0), cable.getLocation(),
                "MetalCable spawns its grabbed block at the last ability progress position");

        cable.progress();

        assertSame(cable, velocityOwner);
        assertEquals(0.8, publishedVelocity.getX(), 1e-9);
        assertEquals(0, publishedVelocity.getZ(), 1e-9);
    }

    @Test void pullUsesOriginalAnchorTimingWhileTeleportingToTheMovingTarget() throws Exception {
        MetalCable cable = cable();
        arrow.position = new Location(world, 10, 64, 0);
        var target = world.player(0, 64, 10);
        cable.setHitEntity(target);

        cable.progress();

        assertFalse(cable.isRemoved());
        assertEquals(target.getLocation(), arrow.position);
        assertEquals(0.8, publishedVelocity.getX(), 1e-9);
        assertEquals(0, publishedVelocity.getZ(), 1e-9);
        set(cable, "ticks", 1);
        cable.progress();
        assertEquals(0, publishedVelocity.getX(), 1e-9);
        assertEquals(0.8, publishedVelocity.getZ(), 1e-9);
    }

    @Test void entityHitKeepsNativeFlightUntilTheOriginalTeleportStep() throws Exception {
        MetalCable cable = cable();
        arrow.position = new Location(world, 10, 64, 0);
        arrow.velocity = new Vector(1.8, 0, 0);
        cable.setHitEntity(world.player(10, 64, 0));
        assertEquals(1.8, arrow.velocity.getX());
        cable.progress();
        assertEquals(0, arrow.velocity.lengthSquared());
    }

    @Test void grabbedBlockPullUsesTheOriginalAnchorDistanceAndSpeed() throws Exception {
        MetalCable cable = cable();
        player.sneaking = true;
        arrow.position = new Location(world, 10, 64, 0);
        Vector[] movement = {null};
        var block = new com.projectkorra.projectkorra.platform.mc.entity.FallingBlock() {
            @Override public Location getLocation() { return new Location(world, 8, 64, 0); }
            @Override public World getWorld() { return world; }
            @Override public void setVelocity(Vector velocity) { movement[0] = velocity.clone(); }
        };
        set(cable, "target", new MetalCable.CableTarget(block));
        set(cable, "hasHit", true);

        cable.progress();

        // Original cable: anchor distance 10 sets the hold point 5 blocks
        // along the player's view, despite the grabbed block being 8 away.
        assertEquals(-0.6783986432040704, movement[0].getX(), 1e-12);
        assertEquals(0.423999152002544, movement[0].getZ(), 1e-12);
        assertEquals(0.8, movement[0].length(), 1e-12);
        assertNull(publishedVelocity, "the grabbed block moves, not the sneaking caster");
        assertEquals(block.getLocation(), arrow.position);
    }

    @Test void impactEffectsInheritCableOwnershipAndRestoreTheOuterContext() {
        Block block = world.getBlockAt(10, 64, 0);
        block.setType(Material.STONE);
        MetalCable cable = new MetalCable(player) {
            @Override public boolean isEnabled() { return false; }
            @Override public void setHitBlock(Block hitBlock) {
                assertSame(this, AbilityExecutionContext.current());
                new TempBlock(hitBlock, Material.AIR.createBlockData());
            }
        };

        hit(cable, block);

        assertSame(cable, TempBlock.get(block).getAbility().orElseThrow());
        assertNull(AbilityExecutionContext.current());
    }

    private MetalCable cable() throws Exception {
        MetalCable cable = new MetalCable(player) {
            @Override public boolean isEnabled() { return false; }
        };
        set(cable, "cable", arrow);
        set(cable, "location", arrow.position.clone());
        set(cable, "origin", player.getLocation());
        set(cable, "range", 30);
        set(cable, "ticks", 1); // Skip the alternating cosmetic particle pass.
        return cable;
    }

    private void hit(MetalCable cable, Block block) {
        arrow.owner = cable;
        ProjectileHitEvent event = new ProjectileHitEvent();
        event.setEntity(arrow);
        event.setHitBlock(block);
        new HyperionCommonListener().onProjectileHit(event);
    }

    private static void set(MetalCable cable, String field, Object value) throws Exception {
        var declared = MetalCable.class.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(cable, value);
    }

    private final class TestArrow extends Arrow {
        private Location position = new Location(world, 1, 64, 0);
        private Vector velocity = new Vector();
        private MetalCable owner;
        @Override public Location getLocation() { return position.clone(); }
        @Override public World getWorld() { return world; }
        @Override public boolean teleport(Location location) {
            position = location.clone();
            velocity = new Vector();
            return true;
        }
        @Override public void setVelocity(Vector velocity) { this.velocity = velocity.clone(); }
        @Override public boolean hasMetadata(String key) { return CoreMethods.CABLE_KEY.equals(key); }
        @Override public List<MetadataValue> getMetadata(String key) {
            return List.of(new FixedMetadataValue(null, owner));
        }
    }
}
