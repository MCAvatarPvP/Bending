package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.chat.ChatMessageType;
import com.projectkorra.projectkorra.platform.mc.*;
import com.projectkorra.projectkorra.platform.mc.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.EntityType;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.entity.Projectile;
import com.projectkorra.projectkorra.platform.mc.inventory.MainHand;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static com.projectkorra.projectkorra.prediction.rollback.RollbackInventoryTest.*;
import static com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayerState.Flag.*;
import static org.junit.jupiter.api.Assertions.*;

class RollbackPlayerTest {
    @Test void playerApiCannotInheritSilentCommonStubs() throws Exception { assertOverrides(Player.class, RollbackPlayer.class); }

    @Test void heldItemsHealthMotionControlsAndScoreboardRewindFromOnePlayerRoot() {
        Rules rules = new Rules();
        RollbackPlayer player = player(new World(), 1, rules);
        player.getInventory().setItemInMainHand(item(Material.STONE, 3, "held"));
        var held = player.getEquipment().getItemInMainHand();
        var team = player.getScoreboard().registerNewTeam("duel");
        team.setPrefix("before");
        var before = capture(player);
        held.setAmount(1);
        player.getInventory().setHeldItemSlot(2);
        player.setHealth(7);
        player.setAbsorptionAmount(4);
        player.setVelocity(new Vector(3, 1, 0));
        player.teleport(new Location(player.getWorld(), 5, 2, 1));
        player.setRotation(30, 40);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setSneaking(true);
        player.setSprinting(true);
        player.setGliding(true);
        player.setGlowing(true);
        player.setCanPickupItems(false);
        player.setFlySpeed(0.3F);
        player.setExp(0.7F);
        player.setExhaustion(2);
        player.setDisplayName("changed");
        team.setPrefix("after");
        team.unregister();
        player.setScoreboard(new Scoreboard());
        before.restore();
        assertSame(held, player.getEquipment().getItemInMainHand());
        assertEquals(3, held.getAmount());
        assertEquals(20, player.getHealth());
        assertEquals(0, player.getAbsorptionAmount());
        assertEquals(0, player.getLocation().getX());
        assertEquals(0, player.getVelocity().getX());
        assertFalse(player.getAllowFlight());
        assertFalse(player.isFlying());
        assertFalse(player.isSneaking());
        assertFalse(player.isSprinting());
        assertFalse(player.isGliding());
        assertFalse(player.isGlowing());
        assertTrue(player.getCanPickupItems());
        assertEquals(0.1F, player.getFlySpeed());
        assertEquals(0, player.getExp());
        assertEquals(0, player.getExhaustion());
        assertEquals("player-1", player.getDisplayName());
        assertSame(team, player.getScoreboard().getTeam("duel"));
        assertEquals("before", team.getPrefix());
        assertSame(MainHand.LEFT, player.getMainHand());
        assertSame(player, player.getPlayer());
    }

    @Test void controlsAndQueriesGoThroughCapturedPolicyAndRejectLiveObjects() {
        Rules rules = new Rules();
        var world = RollbackWorldTest.world(Map.of("rayTraceBlocks", ignored -> null));
        var player = player(world, 1, rules);
        var opponent = player(world, 2, new Rules());
        world.entities().addAll(List.of(player, opponent));
        assertFalse(player.hasPermission("outside.permission"));
        assertTrue(player.hasPermission("fixture.allowed"));
        rules.allowFlight = false;
        player.setFlying(true);
        assertFalse(player.isFlying(), "the native control adapter can reject or change the requested state");
        rules.allowFlight = true;
        player.setFlying(true);
        assertTrue(player.isFlying());
        player.state().hidden(Set.of(opponent.getUniqueId()));
        var before = capture(player);
        player.state().hidden(Set.of());
        assertTrue(player.canSee(opponent));
        before.restore();
        assertFalse(player.canSee(opponent));
        assertTrue(player.hasLineOfSight(opponent));
        assertEquals(List.of(opponent), player.getNearbyEntities(2, 3, 4));
        assertThrows(IllegalArgumentException.class, () -> player.hasLineOfSight(new Entity()));
        assertThrows(IllegalArgumentException.class, () -> player.canSee(player(new World(), 3, rules)));
        assertThrows(IllegalArgumentException.class, () -> world.entities().add(new Entity()));
        assertThrows(IllegalArgumentException.class, () -> player.getNearbyEntities(Double.NaN, 1, 1));
        assertNull(player.getTargetBlockExact(10));
        assertThrows(IllegalArgumentException.class, () -> player.getTargetBlockExact(-1));
        assertThrows(IllegalArgumentException.class, () -> player.launchProjectile(Projectile.class));
    }

    @Test void playerOutputArgumentsAreDetachedBeforeTheyReachTheEffectBuffer() {
        Rules rules = new Rules();
        var player = player(new World(), 1, rules);
        Location position = player.getLocation();
        BlockData data = new BlockData(Material.OAK_SLAB);
        data.setExactState("minecraft:oak_slab[type=top]");
        player.sendBlockChange(position, data);
        position.setX(100);
        data.setExactState("minecraft:oak_slab[type=bottom]");
        assertEquals(new RollbackBlockStore.Position(0, 0, 0), rules.blockPosition);
        assertEquals("minecraft:oak_slab[type=top]", rules.blockData.getExactState());
        assertNotSame(data, rules.blockData);
        assertThrows(IllegalArgumentException.class, () -> player.sendBlockChange(new Location(new World(), 0, 0, 0), data));
    }

    @Test void lateInputRetractsItemConsumptionHealthAndMessagesOnTheSameTimeline() {
        Rules rules = new Rules();
        var player = player(new World(), 1, rules);
        player.getInventory().setItemInMainHand(item(Material.STONE, 2, "resource"));
        UUID owner = player.getUniqueId();
        var engine = new RollbackEngine<>(new RollbackSimulation<RollbackStateGraph.Snapshot, Boolean, String>() {
            @Override public RollbackStateGraph.Snapshot snapshot() { return capture(player); }
            @Override public void restore(RollbackStateGraph.Snapshot saved) { saved.restore(); }
            @Override public Boolean predict(UUID participant, Boolean previous) { return false; }
            @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<String> effects) {
                rules.output = effects::emit;
                try {
                    if (tick == 1 && !inputs.get(owner)) {
                        player.getInventory().removeItem(item(Material.STONE, 1, "resource"));
                        player.setHealth(12);
                        player.setFlying(true);
                        player.sendMessage("discarded branch");
                    } else if (tick == 1) {
                        player.sendMessage("corrected branch");
                    }
                } finally { rules.output = message -> fail("Output escaped the simulation step"); }
            }
        }, Map.of(owner, false), new RollbackEngine.Limits(2, 1, 10, 50_000_000), 0);
        engine.advance();
        engine.advance();
        assertEquals(12, player.getHealth());
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
        assertTrue(player.isFlying());
        assertEquals(RollbackEngine.Submission.ACCEPTED, engine.submit(owner, 1, true));
        engine.reconcile();
        assertEquals(20, player.getHealth());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertFalse(player.isFlying());
        var committed = engine.advance().finalizedEffects();
        assertEquals(List.of("corrected branch"), committed.stream().map(effect -> effect.value()).toList());
        assertTrue(engine.advance().finalizedEffects().isEmpty());
    }

    private static RollbackPlayer player(World world, int id, Rules rules) {
        var body = new RollbackEntityBody(new RollbackEntityBody.Identity(new UUID(0, id), id, "player-" + id, EntityType.PLAYER), world,
                new RollbackEntityBody.Kinematics(new RollbackEntityBody.Pose(0, 0, 0, 0, 0), new RollbackEntityBody.Motion(0, 0, 0),
                        new RollbackBlockStore.Box(-0.3, 0, -0.3, 0.3, 1.8, 0.3), 1.8, true, 0, false),
                new RollbackEntityBody.Rules() {
                    @Override public RollbackEntityBody.Pose teleport(RollbackEntityBody entity, RollbackEntityBody.Pose destination) { return destination; }
                    @Override public boolean canMount(RollbackEntityBody vehicle, RollbackEntityBody passenger) { return true; }
                });
        var inventory = inventory();
        var living = new RollbackLivingState(body, new RollbackLivingState.Vitals(20, 0, 1.62, 300, 300, 0, 20, 0, true),
                Map.of(Attribute.MAX_HEALTH.name(), 20D), List.of(), new RollbackEquipment(inventory), new RollbackLivingState.Rules() {
                    @Override public void damage(RollbackLivingState target, double amount, Entity source) { throw new UnsupportedOperationException("Native damage is outside this fixture"); }
                    @Override public boolean addPotion(RollbackLivingState target, PotionEffect effect, boolean force) { throw new UnsupportedOperationException(); }
                    @Override public void removePotion(RollbackLivingState target, PotionEffectType type) { throw new UnsupportedOperationException(); }
                    @Override public void attribute(RollbackLivingState target, String name, double value) { throw new UnsupportedOperationException(); }
                });
        return new RollbackPlayer(new RollbackPlayerState(living, inventory,
                new RollbackPlayerState.Controls(Set.of(PICKUP_ITEMS), 0.1F, 0, 0),
                new RollbackPlayerState.Profile("player-" + id, "SURVIVAL", "LEFT", true, false, true, 100),
                Set.of(), new Scoreboard(), rules));
    }

    /** Test policy and effect sink only, never a replacement for native physics or session services. */
    private static final class Rules implements RollbackPlayerState.Rules {
        boolean allowFlight = true;
        BlockData blockData;
        RollbackBlockStore.Position blockPosition;
        Consumer<String> output = message -> { };
        @Override public boolean permission(RollbackPlayerState player, String permission) { return permission.equals("fixture.allowed"); }
        @Override public RollbackPlayerState.Controls controls(RollbackPlayerState player, RollbackPlayerState.Controls proposed) {
            return allowFlight ? proposed : proposed.with(FLYING, false);
        }
        @Override public <T extends Projectile> T launch(RollbackPlayerState player, Class<T> type) { return type.cast(new Projectile()); }
        @Override public void message(RollbackPlayerState player, ChatMessageType type, String message) { output.accept(message); }
        @Override public void sound(RollbackPlayerState player, RollbackEntityBody.Pose position, Sound sound, float volume, float pitch) { throw new UnsupportedOperationException(); }
        @Override public void note(RollbackPlayerState player, RollbackEntityBody.Pose position, Instrument instrument, Note note) { throw new UnsupportedOperationException(); }
        @Override public void blockChange(RollbackPlayerState player, RollbackBlockStore.Position position, BlockData data) { blockPosition = position; blockData = data; }
        @Override public void particle(RollbackPlayerState player, Particle particle, RollbackEntityBody.Pose position, int count, double x, double y, double z, double extra, Object data, boolean force) { throw new UnsupportedOperationException(); }
        @Override public void displayName(RollbackPlayerState player, String name) { output.accept(name); }
        @Override public void scoreboard(RollbackPlayerState player, Scoreboard scoreboard) { output.accept("scoreboard"); }
    }
}
