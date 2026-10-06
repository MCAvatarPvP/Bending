package com.projectkorra.projectkorra.prediction.rollback;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntity;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.lang.invoke.MethodHandles;

import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackServerDamageNativeTest {
    @Test void serverPlayerMotionViewRewindsNativeMovementAndLocatorNotifications() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            // Import an already active entity, rather than the constructor's first-tick state.
            var firstTick = MethodHandles.privateLookupIn(Entity.class, MethodHandles.lookup()).findSetter(Entity.class, "firstTick", boolean.class);
            try { firstTick.invokeExact((Entity) scene.target, false); }
            catch (Throwable failure) { throw new AssertionError(failure); }
            var logicalWorld = new World();
            var rules = new RollbackEntityBody.Rules() {
                @Override public RollbackEntityBody.Pose teleport(RollbackEntityBody body, RollbackEntityBody.Pose destination) { return destination; }
                @Override public boolean canMount(RollbackEntityBody vehicle, RollbackEntityBody passenger) { return false; }
            };
            var view = new RollbackEntity(RollbackEntityBody.nativeBacked(scene.targetState.identity(), logicalWorld, scene.targetState, rules));
            var saved = new RollbackStateGraph(value -> false, field -> true, 200_000).capture(List.of(view), List.of());
            var initial = view.body().kinematics();
            view.teleport(new Location(logicalWorld, 0.5, 1, 0.5));
            view.setVelocity(new Vector(0.1, 0, 0.1));
            scene.targetState.use(value -> { ((ServerPlayer) value).travel(new Vec3(0, 0, 1)); return null; });
            assertEquals(scene.target.getX(), view.getLocation().getX());
            assertEquals(scene.target.getZ(), view.getLocation().getZ());
            var outputs = List.copyOf(scene.combat.outputs);
            assertTrue(outputs.stream().anyMatch(output -> output instanceof PaperRollbackCombatAccess.WaypointOutput waypoint && waypoint.receiver()));
            assertTrue(outputs.stream().anyMatch(output -> output instanceof PaperRollbackCombatAccess.WaypointOutput waypoint && !waypoint.receiver()));
            saved.restore();
            assertEquals(initial, view.body().kinematics()); assertTrue(scene.combat.outputs.isEmpty());
            view.teleport(new Location(logicalWorld, 0.5, 1, 0.5));
            view.setVelocity(new Vector(0.1, 0, 0.1));
            scene.targetState.use(value -> { ((ServerPlayer) value).travel(new Vec3(0, 0, 1)); return null; });
            assertEquals(outputs, scene.combat.outputs);
            var foreign = new Scene();
            assertThrows(IllegalArgumentException.class, () -> scene.world.world().getWaypointManager().updatePlayer(foreign.target));
            assertEquals(outputs, scene.combat.outputs);
            assertNull(Bukkit.getServer());
            return null;
        });
    }
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }

    @Test void nativePlayerHitRestoresHealthStatisticsKnockbackAndWireOutputTogether() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var saved = scene.snapshot();
            assertTrue(scene.hit(6));
            scene.target.getBukkitEntity().sendHealthUpdate();
            assertEquals(14, scene.target.getHealth());
            assertEquals(60, scene.target.getStats().getValue(Stats.CUSTOM.get(Stats.DAMAGE_TAKEN)));
            assertSame(scene.attacker, scene.target.getLastHurtByMob());
            assertEquals(20, scene.target.invulnerableTime);
            var velocity = scene.target.getDeltaMovement();
            assertTrue(velocity.x > 0 && velocity.y > 0);
            var outputs = List.copyOf(scene.combat.outputs);
            var events = List.copyOf(scene.combat.events);
            var packets = scene.packets();
            assertTrue(packets.stream().anyMatch(ClientboundUpdateAttributesPacket.class::isInstance));
            assertTrue(packets.stream().anyMatch(ClientboundHurtAnimationPacket.class::isInstance));
            var health = packets.stream().filter(ClientboundSetHealthPacket.class::isInstance).map(ClientboundSetHealthPacket.class::cast).findFirst().orElseThrow();
            assertEquals(14, health.getHealth());
            scene.snapshot();
            assertFalse(scene.hit(4));
            assertEquals(outputs, scene.combat.outputs);
            saved.restore();
            assertEquals(20, scene.target.getHealth());
            assertEquals(0, scene.target.getStats().getValue(Stats.CUSTOM.get(Stats.DAMAGE_TAKEN)));
            assertTrue(scene.combat.outputs.isEmpty()); assertTrue(scene.combat.events.isEmpty());
            assertTrue(scene.hit(6));
            scene.target.getBukkitEntity().sendHealthUpdate();
            assertEquals(outputs, scene.combat.outputs); assertEquals(events, scene.combat.events);
            assertEquals(velocity, scene.target.getDeltaMovement());
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void clientLoadingPvpCrammingAndAssignedTeamsUseCapturedPolicy() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var alternate = scene.world.scoreboards().create(true);
            var team = alternate.use(board -> {
                var created = board.addPlayerTeam("allies");
                created.setAllowFriendlyFire(false);
                board.addPlayerToTeam(scene.target.getScoreboardName(), created);
                board.addPlayerToTeam(scene.attacker.getScoreboardName(), created);
                return created;
            });
            scene.combat.outputs.clear();
            var saved = scene.snapshot();
            scene.targetState.clientLoaded(false);
            assertFalse(scene.hit(6));
            assertTrue(scene.combat.outputs.isEmpty()); assertTrue(scene.combat.events.isEmpty());
            saved.restore();
            assertTrue(scene.target.connection.hasClientLoaded());
            scene.combat.pvp = false;
            assertFalse(scene.hit(6));
            saved.restore();
            scene.combat.cramming = false;
            assertFalse(scene.targetState.damage(scene.world.world().damageSources().cramming(), 6));
            saved.restore();
            scene.combat.cancel = true;
            assertFalse(scene.hit(6));
            assertEquals(20, scene.target.getHealth());
            assertTrue(scene.combat.outputs.isEmpty());
            assertFalse(scene.target.queueHealthUpdatePacket);
            saved.restore();
            // Paper checks the attacking player's assigned scoreboard.
            scene.world.scoreboards().assign(scene.attacker, alternate);
            assertFalse(scene.hit(6));
            assertEquals(20, scene.target.getHealth());
            assertTrue(scene.combat.events.isEmpty());
            team.setAllowFriendlyFire(true);
            assertTrue(scene.hit(6));
            saved.restore();
            assertFalse(team.isAllowFriendlyFire());
            assertTrue(scene.combat.outputs.isEmpty());
            assertTrue(scene.hit(6), "restoring the main-board assignment restores eligibility");
            return null;
        });
    }

    @Test void nativeScaledHealthAndQueuedHealthPacketsRestoreWithoutChangingCombatHealth() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var bukkit = scene.target.getBukkitEntity();
            var saved = scene.snapshot();
            bukkit.setHealthScale(40);
            assertTrue(bukkit.isHealthScaled());
            assertTrue(scene.hit(5));
            bukkit.sendHealthUpdate();
            assertEquals(15, scene.target.getHealth());
            assertEquals(30, bukkit.getScaledHealth());
            assertEquals(30, scene.target.getEntityData().get(LivingEntity.DATA_HEALTH_ID));
            var expected = List.copyOf(scene.combat.outputs);
            var scaled = scene.snapshot();
            bukkit.setHealthScaled(false);
            scene.target.setHealth(8);
            scaled.restore();
            assertEquals(40, bukkit.getHealthScale());
            assertEquals(30, bukkit.getScaledHealth());
            assertEquals(expected, scene.combat.outputs);
            scene.target.queueHealthUpdatePacket = true;
            bukkit.sendHealthUpdate();
            assertNotNull(scene.target.queuedHealthUpdatePacket);
            assertEquals(expected, scene.combat.outputs);
            saved.restore();
            assertFalse(scene.target.queueHealthUpdatePacket);
            assertNull(scene.target.queuedHealthUpdatePacket);
            assertFalse(bukkit.isHealthScaled()); assertEquals(20, bukkit.getHealthScale());
            assertEquals(20, scene.target.getHealth()); assertTrue(scene.combat.outputs.isEmpty());
            bukkit.setHealthScale(40); assertTrue(scene.hit(5)); bukkit.sendHealthUpdate();
            assertEquals(expected, scene.combat.outputs);
            return null;
        });
    }

    @Test void nativeTotemResurrectionRestoresInventoryEffectsAndPacketsAndUnadaptedDeathAborts() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var totem = new ItemStack(Items.TOTEM_OF_UNDYING);
            scene.target.setItemSlot(EquipmentSlot.OFFHAND, totem);
            var saved = scene.snapshot();
            assertTrue(scene.hit(100));
            assertEquals(1, scene.target.getHealth());
            assertTrue(totem.isEmpty());
            assertFalse(scene.target.getActiveEffects().isEmpty());
            var expected = List.copyOf(scene.combat.outputs);
            scene.snapshot();
            saved.restore();
            assertEquals(1, totem.getCount()); assertTrue(scene.target.getActiveEffects().isEmpty());
            assertEquals(20, scene.target.getHealth()); assertTrue(scene.combat.outputs.isEmpty());
            assertTrue(scene.hit(100)); assertEquals(expected, scene.combat.outputs);
            saved.restore();
            scene.target.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            var beforeDeath = scene.snapshot();
            assertThrows(IllegalStateException.class, () -> scene.hit(100));
            beforeDeath.restore();
            assertEquals(20, scene.target.getHealth()); assertTrue(scene.combat.outputs.isEmpty());
            assertFalse(scene.target.queueHealthUpdatePacket); assertTrue(scene.target.isAlive());
            return null;
        });
    }

    @Test void inventoryJournalRebuildsDetachedNativeCorrectionsAndPreservesSanitization() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var registry = (RegistryAccess) scene.combat.registryAccess();
            var codec = new PaperRollbackPacketData(registry);
            var stack = new ItemStack(Items.DIAMOND_SWORD); stack.setDamageValue(7);
            var item = new PaperRollbackItemCodec(registry).encode(stack);
            var empty = com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemData.EMPTY;
            var data = List.<PaperRollbackPacketData.Data>of(
                    new PaperRollbackPacketData.HeldSlot(3),
                    new PaperRollbackPacketData.Slot(0, 42, 5, item),
                    new PaperRollbackPacketData.Content(0, 43, List.of(item, empty), item),
                    new PaperRollbackPacketData.Cursor(item), new PaperRollbackPacketData.InventorySlot(5, item),
                    new PaperRollbackPacketData.Equipment(9, List.of(new PaperRollbackPacketData.Equipped("mainhand", item)), true),
                    new PaperRollbackPacketData.Equipment(9, List.of(new PaperRollbackPacketData.Equipped("offhand", empty)), false));
            for (var value : data) assertEquals(value, codec.capture(codec.rebuildInventory(value, id -> id)));
            var equipment = (ClientboundSetEquipmentPacket) codec.rebuildInventory(data.get(5), id -> id + 100);
            assertEquals(109, equipment.getEntity()); assertTrue(PaperRollbackPrivateAccess.equipmentSanitized(equipment));
            equipment.getSlots().getFirst().getSecond().setDamageValue(99);
            var rebuilt = (ClientboundSetEquipmentPacket) codec.rebuildInventory(data.get(5), id -> id);
            assertEquals(7, rebuilt.getSlots().getFirst().getSecond().getDamageValue());
            try (var clock = RollbackClock.at(1000, 1, 50_000_000)) {
                assertThrows(IllegalStateException.class, () -> codec.rebuildInventory(data.getFirst(), id -> id));
            }
            assertThrows(IllegalArgumentException.class, () -> codec.rebuildInventory(new PaperRollbackPacketData.Animation(9, 0), id -> id));
            assertTrue(scene.combat.outputs.isEmpty()); assertNull(Bukkit.getServer()); return null;
        });
    }

    @Test void packetJournalDecoderRejectsMismatchTrailingBytesAndReplayDelivery() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            scene.target.connection.send(new ClientboundSetHealthPacket(7, 8, 2));
            var output = (PaperRollbackConnection.PacketOutput) scene.combat.outputs.getFirst();
            var decoder = new PaperRollbackPacketDecoder((RegistryAccess) scene.combat.registryAccess());
            var health = (ClientboundSetHealthPacket) decoder.decode(output);
            assertEquals(7, health.getHealth()); assertEquals(8, health.getFood());
            assertNotSame(health, decoder.decode(output));
            assertThrows(IllegalArgumentException.class, () -> decoder.decode(new PaperRollbackConnection.PacketOutput(output.target(), "wrong", output.payload())));
            int[] unauditedId = {-1};
            GameProtocols.CLIENTBOUND_TEMPLATE.details().listPackets((type, id) -> {
                if (type == GamePacketTypes.CLIENTBOUND_CONTAINER_SET_CONTENT) unauditedId[0] = id;
            });
            assertTrue(unauditedId[0] >= 0);
            var header = Unpooled.buffer();
            try {
                net.minecraft.network.VarInt.write(header, unauditedId[0]);
                var headerBytes = new byte[header.readableBytes()]; header.readBytes(headerBytes);
                var invalid = new PaperRollbackConnection.PacketOutput(output.target(),
                        GamePacketTypes.CLIENTBOUND_CONTAINER_SET_CONTENT.id().toString(), Base64.getEncoder().encodeToString(headerBytes));
                assertEquals("Packet output ID/type is not audited", assertThrows(IllegalArgumentException.class,
                        () -> decoder.decode(invalid)).getMessage(), "Must reject the header before the absent inventory body can be decoded");
            } finally { header.release(); }
            var raw = Base64.getDecoder().decode(output.payload());
            var trailing = Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(raw, raw.length + 1));
            assertThrows(IllegalArgumentException.class, () -> decoder.decode(new PaperRollbackConnection.PacketOutput(output.target(), output.type(), trailing)));
            assertThrows(IllegalArgumentException.class, () -> decoder.decode(new PaperRollbackConnection.PacketOutput(output.target(), output.type(), "!")));
            assertThrows(IllegalArgumentException.class, () -> decoder.decode(new PaperRollbackConnection.PacketOutput(output.target(), output.type(), "A".repeat(1_398_105))));
            try (var clock = RollbackClock.at(1000, 1, 50_000_000)) {
                assertThrows(IllegalStateException.class, () -> decoder.decode(output));
            }
            assertEquals(1, scene.combat.outputs.size()); assertNull(Bukkit.getServer()); return null;
        });
    }

    @Test void packetsAreDetachedAtSendAndUnimplementedNetworkCallbacksFailBeforeOutput() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var slots = new ArrayList<Pair<EquipmentSlot, ItemStack>>();
            var stack = new ItemStack(Items.STONE, 4);
            slots.add(Pair.of(EquipmentSlot.MAINHAND, stack));
            scene.target.connection.send(new ClientboundSetEquipmentPacket(scene.target.getId(), slots));
            slots.clear(); stack.setCount(1);
            var equipmentOutput = (PaperRollbackPacketData.Direct) scene.combat.outputs.getFirst();
            assertEquals(scene.target.getUUID(), equipmentOutput.player());
            var equipment = (PaperRollbackPacketData.Equipment) equipmentOutput.data();
            assertEquals(4, new PaperRollbackItemCodec((RegistryAccess) scene.combat.registryAccess())
                    .decode(equipment.slots().getFirst().item()).getCount());
            assertFalse(equipment.sanitize()); // This constructor explicitly uses Paper's unsanitized mode.
            assertThrows(IllegalStateException.class, () -> scene.target.connection.send(
                    new ClientboundSystemChatPacket(net.minecraft.network.chat.Component.literal("unbound"), false)));
            assertEquals(1, scene.combat.outputs.size());
            scene.combat.outputs.clear();
            var attributes = new ClientboundUpdateAttributesPacket(scene.target.getId(), List.of(scene.target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)));
            scene.target.connection.send(attributes);
            attributes.getValues().clear();
            var packet = (ClientboundUpdateAttributesPacket) scene.packets().getFirst();
            assertEquals(1, packet.getValues().size());
            var saved = scene.snapshot();
            var health = new ClientboundSetHealthPacket(3, 5, 2);
            assertThrows(IllegalStateException.class, () -> scene.target.connection.send(health, future -> fail("no network completion during replay")));
            assertThrows(IllegalStateException.class, () -> scene.target.connection.disconnect(net.minecraft.network.chat.Component.literal("test")));
            assertEquals(1, scene.combat.outputs.size());
            scene.target.connection.send(health);
            saved.restore(); assertEquals(1, scene.combat.outputs.size());
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void lateInputReplaysNativeDamageAndFinalizesEachPacketExactlyOnce() throws Exception {
        onTickThread(() -> {
            var directSimulation = new DamageSimulation();
            var delayedSimulation = new DamageSimulation();
            var participant = directSimulation.scene.attacker.getUUID();
            var limits = new RollbackEngine.Limits(3, 1, 10, 50_000_000);
            var direct = new RollbackEngine<>(directSimulation, Map.of(participant, false), limits, 1_000);
            var delayed = new RollbackEngine<>(delayedSimulation, Map.of(participant, false), limits, 1_000);
            direct.submit(participant, 1, true);
            direct.advance(); delayed.advance(); direct.advance(); delayed.advance();
            assertEquals(14, directSimulation.scene.target.getHealth());
            assertEquals(20, delayedSimulation.scene.target.getHealth());
            delayed.submit(participant, 1, true);
            assertEquals(1, delayed.reconcile().replayedFrom());
            assertEquals(14, delayedSimulation.scene.target.getHealth());
            var delivered = new ArrayList<RollbackEngine.Effect<PaperRollbackCombatAccess.Output>>();
            for (int tick = 3; tick <= 6; tick++) {
                var onTime = direct.advance();
                var replayed = delayed.advance();
                assertEquals(onTime.finalizedEffects(), replayed.finalizedEffects());
                delivered.addAll(replayed.finalizedEffects());
            }
            assertFalse(delivered.isEmpty());
            assertEquals(delivered.size(), delivered.stream().map(effect -> effect.tick() + ":" + effect.ordinal()).distinct().count());
            assertTrue(delivered.stream().allMatch(effect -> effect.tick() == 1));
            assertTrue(delivered.stream().anyMatch(effect -> effect.value() instanceof PaperRollbackConnection.PacketOutput));
            return null;
        });
    }

    private static final class DamageSimulation implements RollbackSimulation<RollbackStateGraph.Snapshot, Boolean, PaperRollbackCombatAccess.Output> {
        final Scene scene = new Scene();
        @Override public RollbackStateGraph.Snapshot snapshot() { return scene.snapshot(); }
        @Override public void restore(RollbackStateGraph.Snapshot saved) { saved.restore(); }
        @Override public Boolean predict(UUID player, Boolean previous) { return false; }
        @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<PaperRollbackCombatAccess.Output> output) {
            scene.combat.outputs.clear();
            scene.combat.time = tick;
            if (inputs.get(scene.attacker.getUUID())) { scene.hit(6); scene.target.getBukkitEntity().sendHealthUpdate(); }
            scene.combat.outputs.forEach(output::emit);
        }
    }

    private static final class Scene {
        final PaperRollbackDamageNativeTest.Combat combat = new PaperRollbackDamageNativeTest.Combat();
        final PaperRollbackWorldAccess world = new PaperRollbackWorldAccess(new PaperRollbackWorldAccessNativeTest.Queries(), combat, 4);
        final PaperRollbackNativePlayerState targetState = player(91, "rollback_target", 103);
        final PaperRollbackNativePlayerState attackerState = player(92, "rollback_attacker", 107);
        final ServerPlayer target = targetState.use(value -> (ServerPlayer) value);
        final ServerPlayer attacker = attackerState.use(value -> (ServerPlayer) value);
        Scene() {
            target.setId(901); attacker.setId(902);
            target.setPos(1, 1, 0); attacker.setPos(0, 1, 0); target.setOnGround(true);
            // Native construction randomizes yaw before the owned RNG is installed.
            // Both replicas import the same initial pose, as a session must do.
            target.setYRot(0); attacker.setYRot(0); target.setXRot(0); attacker.setXRot(0);
        }
        PaperRollbackNativePlayerState player(long id, String name, long seed) {
            return PaperRollbackNativePlayerState.serverPlayer(world, new GameProfile(new UUID(0, id), name),
                    ClientInformation.createDefault(), GameType.SURVIVAL, seed, 200_000);
        }
        boolean hit(float amount) { return targetState.damage(world.world().damageSources().playerAttack(attacker), amount); }
        RollbackStateGraph.Snapshot snapshot() { return new RollbackStateGraph(value -> false, field -> true, 200_000).capture(List.of(targetState), List.of()); }
        List<Packet<?>> packets() {
            return combat.outputs.stream().filter(PaperRollbackConnection.PacketOutput.class::isInstance)
                    .map(PaperRollbackConnection.PacketOutput.class::cast).<Packet<?>>map(output -> {
                        assertEquals(target.getUUID(), output.target());
                        return new PaperRollbackPacketDecoder((RegistryAccess) combat.registryAccess()).decode(output);
                    }).toList();
        }
    }
}
