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

    @Test void correctingChiStunAndFallProtectionRestoresAdditionsAndRemovesStaleParticipantMarkers() throws Exception {
        try (var fixtures = new com.projectkorra.projectkorra.support.AbilityWorld()) {
            var world = new World();
            var source = player(world, 1, new Rules());
            var replica = player(world, 1, new Rules());
            var outsider = player(world, 2, new Rules());
            var counter = new org.objenesis.ObjenesisStd().newInstance(me.literka.abilities.Counter.class);
            var stun = new com.projectkorra.projectkorra.util.MovementHandler(source, counter);
            var otherPlugin = new Object();
            var outsideMarker = new com.projectkorra.projectkorra.platform.mc.metadata.FixedMetadataValue(otherPlugin, "outside");
            var stop = com.projectkorra.projectkorra.util.MovementHandler.STOP_METADATA;
            var fall = com.projectkorra.projectkorra.util.FallHandler.STEP_METADATA;
            outsider.setMetadata(stop, outsideMarker);
            replica.setMetadata("other:marker", outsideMarker);
            var roster = Map.<UUID,Player>of(replica.getUniqueId(), replica);
            var codec = new RollbackGraphCodec(new RollbackGraphCodec.Catalog(List.of(RollbackAbilityGlobals.class,
                    RollbackParticipantStatuses.class, RollbackDigging.class, RollbackDigging.Mining.class, me.literka.abilities.Counter.class),
                    List.of(), List.of()), new RollbackGraphCodec.Limits(1024, 8192, 65536, 2048));
            try {
                stun.stopWithDuration(1000, "stunned");
                com.projectkorra.projectkorra.util.FallHandler.stopFall(source, false);
                var marked = codec.encode(List.of(new RollbackAbilityGlobals().portable(List.of(), List.of(source)), counter));
                stun.reset();
                com.projectkorra.projectkorra.util.FallHandler.removePlayer(source);
                var cleared = codec.encode(List.of(new RollbackAbilityGlobals().portable(List.of(), List.of(source))));
                var decoded = codec.decode(marked);
                ((RollbackAbilityGlobals) decoded.getFirst()).restoreParticipantMetadata(roster);
                assertTrue(com.projectkorra.projectkorra.util.MovementHandler.isStopped(replica));
                assertSame(decoded.get(1), replica.getMetadata(stop).getFirst().value());
                assertEquals(com.projectkorra.projectkorra.util.FallHandler.Step.MOVED, com.projectkorra.projectkorra.util.FallHandler.getStep(replica));
                var hit = new com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent(replica,
                        com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent.DamageCause.FALL, 3);
                com.projectkorra.projectkorra.listener.CommonDamageHandler.fall(hit);
                assertTrue(hit.isCancelled());
                assertFalse(com.projectkorra.projectkorra.util.FallHandler.contains(replica));
                // A newer provisional hit reinstalled protection. The confirmed marker-free graph must remove it.
                com.projectkorra.projectkorra.util.FallHandler.stopFall(replica);
                ((RollbackAbilityGlobals) codec.decode(cleared).getFirst()).restoreParticipantMetadata(roster);
                assertFalse(com.projectkorra.projectkorra.util.MovementHandler.isStopped(replica));
                hit.setCancelled(false);
                com.projectkorra.projectkorra.listener.CommonDamageHandler.fall(hit);
                assertFalse(hit.isCancelled());
                assertTrue(replica.body().hasMetadata("other:marker", otherPlugin, "outside"));
                assertTrue(outsider.body().hasMetadata(stop, otherPlugin, "outside"));
                // An outside plugin may install a value on the same key before live handoff.
                replica.setMetadata(fall, outsideMarker);
                ((RollbackAbilityGlobals) codec.decode(cleared).getFirst()).merge(roster);
                assertTrue(replica.body().hasMetadata(fall, otherPlugin, "outside"));
                assertThrows(IllegalStateException.class, () -> new RollbackAbilityGlobals().portable(List.of(), List.of(replica)));
            } finally { com.projectkorra.projectkorra.util.MovementHandler.handlers.remove(stun); }
        }
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
        @Override public void hideDisplay(RollbackPlayerState viewer, UUID display) { output.accept("hide " + display); }
        @Override public void displayName(RollbackPlayerState player, String name) { output.accept(name); }
        @Override public void scoreboard(RollbackPlayerState player, Scoreboard scoreboard) { output.accept("scoreboard"); }
    }
}
