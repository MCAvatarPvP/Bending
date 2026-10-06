package com.projectkorra.projectkorra.prediction.rollback;

import com.mojang.authlib.GameProfile;
import net.minecraft.advancements.*;
import net.minecraft.advancements.criterion.PlayerTrigger;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackAdvancementsNativeTest {
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }
    private static final Identifier ID = Identifier.parse("rollback:progress");

    @Test void realNativeTriggersRestoreCriteriaListenersExperienceSoundAndSimulationDates() throws Exception {
        onTickThread(() -> {
            var holder = definition(true, new AdvancementRewards(300, List.of(), List.of(), Optional.empty()), Optional.empty());
            var scene = new Scene(new PaperRollbackAdvancements.Seed(List.of(holder), Map.of()));
            scene.player.tickCount = 200;
            assertEquals(2, scene.player.getAdvancements().criterionData.size());
            var saved = scene.snapshot();
            try (var clock = RollbackClock.at(1_000, 10, 50_000_000)) {
                CriteriaTriggers.TICK.trigger(scene.player);
                assertEquals(Map.of("tick", Instant.ofEpochMilli(1_500)), scene.state.advancementProgress(ID.toString()));
                CriteriaTriggers.LOCATION.trigger(scene.player);
            }
            var progress = scene.state.advancementProgress(ID.toString());
            assertEquals(Map.of("tick", Instant.ofEpochMilli(1_500), "location", Instant.ofEpochMilli(1_500)), progress);
            assertTrue(scene.player.getAdvancements().criterionData.isEmpty());
            assertEquals(300, scene.player.totalExperience);
            assertEquals(300, scene.player.getScore());
            assertTrue(scene.combat.outputs.stream().anyMatch(value -> value instanceof PaperRollbackCombatAccess.TargetSoundOutput));
            assertTrue(scene.combat.events.contains("advancement:rollback:progress"));
            var outputs = List.copyOf(scene.combat.outputs);
            var events = List.copyOf(scene.combat.events);
            var complete = scene.snapshot();
            assertTrue(scene.state.revokeAdvancement(ID.toString(), "tick"));
            assertEquals(1, scene.player.getAdvancements().criterionData.size());
            complete.restore();
            assertTrue(scene.player.getAdvancements().criterionData.isEmpty());
            assertEquals(progress, scene.state.advancementProgress(ID.toString()));
            saved.restore();
            assertEquals(0, scene.player.totalExperience);
            assertTrue(scene.state.advancementProgress(ID.toString()).isEmpty());
            assertEquals(2, scene.player.getAdvancements().criterionData.size());
            assertTrue(scene.combat.events.isEmpty()); assertTrue(scene.combat.outputs.isEmpty());
            try (var clock = RollbackClock.at(1_000, 10, 50_000_000)) {
                CriteriaTriggers.TICK.trigger(scene.player); CriteriaTriggers.LOCATION.trigger(scene.player);
            }
            assertEquals(progress, scene.state.advancementProgress(ID.toString()));
            assertEquals(outputs, scene.combat.outputs); assertEquals(events, scene.combat.events);
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void cancelledCriterionRetainsItsListenerAndNeverGrantsRewardsOrCompletion() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(new PaperRollbackAdvancements.Seed(List.of(definition(false,
                    new AdvancementRewards(20, List.of(), List.of(), Optional.empty()), Optional.empty())), Map.of()));
            var saved = scene.snapshot();
            scene.combat.cancelAdvancements = true;
            try (var clock = RollbackClock.at(1_000, 1, 50_000_000)) {
                assertFalse(scene.state.awardAdvancement(ID.toString(), "tick"));
            }
            assertTrue(scene.state.advancementProgress(ID.toString()).isEmpty());
            assertEquals(0, scene.player.totalExperience);
            assertEquals(1, scene.player.getAdvancements().criterionData.size());
            assertFalse(scene.combat.events.contains("advancement:rollback:progress"));
            saved.restore();
            assertFalse(scene.combat.cancelAdvancements);
            assertThrows(IllegalStateException.class, () -> scene.state.awardAdvancement(ID.toString(), "tick"));
            assertTrue(scene.state.advancementProgress(ID.toString()).isEmpty());
            try (var clock = RollbackClock.at(1_000, 2, 50_000_000)) {
                assertTrue(scene.state.awardAdvancement(ID.toString(), "tick"));
                assertFalse(scene.state.awardAdvancement(ID.toString(), "tick"));
            }
            assertEquals(Map.of("tick", Instant.ofEpochMilli(1_100)), scene.state.advancementProgress(ID.toString()));
            assertEquals(20, scene.player.totalExperience);
            return null;
        });
    }

    @Test void importDetachesDisplayDataAndRetainsObtainedDatesWithoutRegrantingLoginRewards() throws Exception {
        onTickThread(() -> {
            var display = display(); display.setLocation(1.5F, 2.5F);
            var holder = definition(true, new AdvancementRewards(100, List.of(), List.of(), Optional.empty()), Optional.of(display));
            var scene = new Scene(new PaperRollbackAdvancements.Seed(List.of(holder), Map.of(ID, Map.of("tick", Instant.ofEpochMilli(42)))));
            var listener = scene.player.getAdvancements().criterionData.get(CriteriaTriggers.LOCATION).iterator().next();
            var copied = listener.advancement();
            assertNotSame(holder, copied); assertNotSame(display, copied.value().display().orElseThrow());
            display.setLocation(99, 100); display.getIcon().setCount(40);
            assertEquals(1.5F, copied.value().display().orElseThrow().getX());
            assertEquals(1, copied.value().display().orElseThrow().getIcon().getCount());
            assertEquals(0, scene.player.totalExperience);
            assertTrue(scene.combat.events.isEmpty());
            assertEquals(Map.of("tick", Instant.ofEpochMilli(42)), scene.state.advancementProgress(ID.toString()));
            assertFalse(scene.player.getAdvancements().criterionData.containsKey(CriteriaTriggers.TICK));
            var saved = scene.snapshot();
            copied.value().display().orElseThrow().setLocation(5, 6);
            saved.restore();
            assertEquals(1.5F, copied.value().display().orElseThrow().getX());
            assertEquals(99, display.getX());
            assertThrows(IllegalArgumentException.class, () -> scene.player.getAdvancements().getOrStartProgress(holder));
            return null;
        });
    }

    @Test void completionMessageUsesPrivateEventChangesAndCapturedGameRule() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(new PaperRollbackAdvancements.Seed(List.of(definition(false, AdvancementRewards.EMPTY, Optional.of(display()))), Map.of()));
            scene.combat.replaceAdvancementMessage = true;
            var saved = scene.snapshot();
            try (var clock = RollbackClock.at(1_000, 1, 50_000_000)) { CriteriaTriggers.TICK.trigger(scene.player); }
            var announcements = scene.combat.outputs.stream().filter(PaperRollbackAdvancements.Announcement.class::isInstance).toList();
            assertEquals(1, announcements.size());
            assertTrue(((PaperRollbackAdvancements.Announcement) announcements.getFirst()).component().contains("private advancement"));
            saved.restore();
            try (var clock = RollbackClock.at(1_000, 1, 50_000_000)) { CriteriaTriggers.TICK.trigger(scene.player); }
            assertEquals(announcements, scene.combat.outputs);
            saved.restore();
            scene.combat.replaceAdvancementMessage = false;
            try (var clock = RollbackClock.at(1_000, 1, 50_000_000)) { CriteriaTriggers.TICK.trigger(scene.player); }
            var original = (PaperRollbackAdvancements.Announcement) scene.combat.outputs.getFirst();
            assertTrue(original.component().contains("chat.type.advancement.task"));
            assertTrue(original.component().contains("Private advancement"));
            assertTrue(original.component().contains("rollback_adv"));
            assertNull(Bukkit.getServer());
            saved.restore();
            scene.combat.rules.put(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
            try (var clock = RollbackClock.at(1_000, 1, 50_000_000)) { CriteriaTriggers.TICK.trigger(scene.player); }
            assertTrue(scene.combat.outputs.isEmpty());
            assertTrue(scene.combat.events.contains("advancement:rollback:progress"));
            return null;
        });
    }

    @Test void unimplementedRecipeRewardFailsAndEntireNativeStepCanBeRestored() throws Exception {
        onTickThread(() -> {
            var recipe = ResourceKey.create(Registries.RECIPE, Identifier.parse("minecraft:crafting_table"));
            var scene = new Scene(new PaperRollbackAdvancements.Seed(List.of(definition(false,
                    new AdvancementRewards(5, List.of(), List.of(recipe), Optional.empty()), Optional.empty())), Map.of()));
            var saved = scene.snapshot();
            try (var clock = RollbackClock.at(1_000, 1, 50_000_000)) {
                var failure = assertThrows(IllegalStateException.class, () -> CriteriaTriggers.TICK.trigger(scene.player));
                assertTrue(failure.getMessage().contains("getRecipeManager"));
            }
            saved.restore();
            assertEquals(0, scene.player.totalExperience);
            assertTrue(scene.state.advancementProgress(ID.toString()).isEmpty());
            assertEquals(1, scene.player.getAdvancements().criterionData.size());
            assertTrue(scene.combat.events.isEmpty()); assertTrue(scene.combat.outputs.isEmpty());
            assertThrows(IllegalStateException.class, scene.player.getAdvancements()::save);
            scene.player.getAdvancements().flushDirty(scene.player, true);
            assertTrue(scene.combat.outputs.isEmpty());
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    private static DisplayInfo display() {
        return new DisplayInfo(new ItemStack(Items.STONE), Component.literal("Private advancement"), Component.literal("Description"),
                Optional.empty(), AdvancementType.TASK, true, true, false);
    }

    @Test void nativeFlushJournalsDetachedProgressAndRestoresTheFirstPacketFlag() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(new PaperRollbackAdvancements.Seed(
                    List.of(definition(false, AdvancementRewards.EMPTY, Optional.of(display()))), Map.of()));
            try (var clock = RollbackClock.at(1_000, 1, 50_000_000)) { CriteriaTriggers.TICK.trigger(scene.player); }
            var pending = scene.snapshot();
            scene.player.getAdvancements().flushDirty(scene.player, true);
            var updates = scene.combat.outputs.stream().filter(PaperRollbackPacketData.Direct.class::isInstance)
                    .map(PaperRollbackPacketData.Direct.class::cast).toList();
            assertEquals(1, updates.size());
            var update = (PaperRollbackPacketData.Advancements) updates.getFirst().data();
            assertTrue(update.reset()); assertTrue(update.show());
            assertEquals(ID.toString(), update.added().getFirst().id());
            assertEquals(List.of(new PaperRollbackPacketData.Criterion("tick", true, 1_050)), update.progress().get(ID.toString()));
            assertFalse(PaperRollbackPrivateAccess.advancementFirstPacket(scene.player.getAdvancements()));
            var expected = List.copyOf(scene.combat.outputs);
            scene.player.getAdvancements().flushDirty(scene.player, true);
            assertEquals(expected, scene.combat.outputs);
            pending.restore();
            assertTrue(PaperRollbackPrivateAccess.advancementFirstPacket(scene.player.getAdvancements()));
            scene.player.getAdvancements().flushDirty(scene.player, true);
            assertEquals(expected, scene.combat.outputs);
            assertNull(Bukkit.getServer());
            return null;
        });
    }
    private static AdvancementHolder definition(boolean twoCriteria, AdvancementRewards rewards, Optional<DisplayInfo> display) {
        var criterion = new PlayerTrigger.TriggerInstance(Optional.empty());
        Map<String, Criterion<?>> criteria = twoCriteria
                ? Map.of("tick", CriteriaTriggers.TICK.createCriterion(criterion), "location", CriteriaTriggers.LOCATION.createCriterion(criterion))
                : Map.of("tick", CriteriaTriggers.TICK.createCriterion(criterion));
        return new AdvancementHolder(ID, new Advancement(Optional.empty(), display, rewards, criteria, AdvancementRequirements.allOf(criteria.keySet()), false));
    }
    private static final class Scene {
        final PaperRollbackDamageNativeTest.Combat combat = new PaperRollbackDamageNativeTest.Combat();
        final PaperRollbackWorldAccess world = new PaperRollbackWorldAccess(new PaperRollbackWorldAccessNativeTest.Queries(), combat, 4);
        final PaperRollbackNativePlayerState state;
        final ServerPlayer player;
        Scene(PaperRollbackAdvancements.Seed advancements) {
            state = PaperRollbackNativePlayerState.serverPlayer(world, new GameProfile(new UUID(0, 97), "rollback_adv"),
                    ClientInformation.createDefault(), GameType.SURVIVAL, 93, 200_000, PaperRollbackStatistics.Seed.fresh(), advancements);
            player = state.use(value -> (ServerPlayer) value);
        }
        RollbackStateGraph.Snapshot snapshot() { return new RollbackStateGraph(value -> false, field -> true, 200_000).capture(List.of(state), List.of()); }
    }
}
