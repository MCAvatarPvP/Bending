package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeMethods;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import io.papermc.paper.configuration.GlobalConfiguration;
import io.papermc.paper.configuration.WorldConfiguration;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.stats.Stat;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import org.objenesis.ObjenesisStd;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Private native player ticks, damage/resurrection and statistics. Unbound world/item and death/spawn paths require further adapters. */
public final class PaperRollbackCombatAccess {
    public sealed interface Output permits DamageOutput, StatusOutput, SoundOutput, TargetSoundOutput, WaypointOutput, PaperRollbackScoreboards.Change, PaperRollbackScoreboards.Assignment, PaperRollbackAdvancements.Announcement, PaperRollbackConnection.PacketOutput, PaperRollbackPacketData.Output { }
    public record Position(double x, double y, double z) { }
    public record DamageOutput(UUID entity, String type, UUID source, UUID attacker, Position position) implements Output { }
    public record StatusOutput(UUID entity, byte status) implements Output { }
    public record SoundOutput(UUID excludedSource, Position position, String sound, String category,
                              float volume, float pitch, long seed) implements Output { }
    public record TargetSoundOutput(UUID target, Position position, String sound, String category,
                                    float volume, float pitch, long seed) implements Output { }
    public enum WaypointAction { TRACK, UPDATE, UNTRACK }
    /** Membership/movement notification for the private locator view, not a delivered client packet. */
    public record WaypointOutput(UUID entity, Position position, boolean receiver, WaypointAction action) implements Output { }

    private final PaperRollbackWorldAccess world;
    private final PaperRollbackWorldAccess.Combat<?> state;
    private final RegistryAccess registries;
    private final Registry<DamageType> damageRegistry;
    private final DamageSources damageSources;
    private final MethodHandle damage, serverDamage;
    private final MethodHandle awardStatistic, resetStatistic;
    private final MethodHandle addEffect, removeEffect, setAir, setSwimming, movementStep, playerBodyTick;
    private final MethodHandle serverTick, serverBodyTick, serverJump;
    private final MethodHandle tryGlide, stopGlide;
    private final MethodHandle stopItemUse, equipmentChanges, playerImmobile, serverImmobile;
    private final GlobalConfiguration globalConfiguration;
    private final WorldConfiguration worldConfiguration;
    private final PaperRollbackNativeEvents events;

    PaperRollbackCombatAccess(PaperRollbackWorldAccess world, PaperRollbackWorldAccess.Combat<?> state) {
        this.world = world; this.state = state;
        registries = (RegistryAccess) Objects.requireNonNull(state.registryAccess(), "captured native registries");
        damageRegistry = registries.lookupOrThrow(Registries.DAMAGE_TYPE);
        damageSources = new DamageSources(registries);
        var allocator = new ObjenesisStd(false);
        globalConfiguration = allocator.newInstance(GlobalConfiguration.class);
        globalConfiguration.unsupportedSettings = allocator.newInstance(GlobalConfiguration.UnsupportedSettings.class);
        worldConfiguration = allocator.newInstance(WorldConfiguration.class);
        worldConfiguration.scoreboards = allocator.newInstance(WorldConfiguration.Scoreboards.class);
        worldConfiguration.collisions = allocator.newInstance(WorldConfiguration.Collisions.class);
        worldConfiguration.environment = allocator.newInstance(WorldConfiguration.Environment.class);
        worldConfiguration.tickRates = allocator.newInstance(WorldConfiguration.TickRates.class);
        worldConfiguration.entities = allocator.newInstance(WorldConfiguration.Entities.class);
        worldConfiguration.entities.behavior = allocator.newInstance(WorldConfiguration.Entities.Behavior.class);
        var builder = new RollbackNativeMethods();
        events = new PaperRollbackNativeEvents(world::event, world::ownsWrapper);
        events.bind(builder); events.bindDamageSources(builder, source -> requireSource((DamageSource) source));
        world.scoreboards().bind(builder, events.server());
        new PaperRollbackStatusEffects(registries).bind(builder);
        Set<String> livingBodies = Set.of("hurtServer", "handleEntityDamage", "actuallyHurt", "knockback", "hurtArmor", "hurtHelmet", "doHurtEquipment",
                "applyItemBlocking", "blockingItemEffects", "blockUsingItem", "blockedByItem", "stopUsingItem",
                "checkTotemDeathProtection", "addEffect", "removeEffect", "removeEffectNoUpdate", "removeAllEffects",
                "aiStep", "travel", "travelFallFlying", "handleFallFlyingCollisions", "updateFallFlying", "stopFallFlying", "pushEntities", "isImmobile", "jumpFromGround",
                "tick", "baseTick", "tickEffects", "detectEquipmentUpdates", "collectEquipmentChanges", "onBelowWorld", "heal");
        for (Method method : LivingEntity.class.getDeclaredMethods()) if (livingBodies.contains(method.getName())) builder.copy(method);
        for (Method method : Player.class.getDeclaredMethods()) {
            if (Set.of("hurtServer", "actuallyHurt", "causeFoodExhaustion", "hurtArmor", "hurtHelmet", "awardStat", "resetStat", "canHarmPlayer", "aiStep", "isImmobile",
                    "blockUsingItem", "tick", "updatePlayerPose", "updateSwimming", "detectEquipmentUpdates", "turtleHelmetTick",
                    "tryToStartFallFlying", "startFallFlying", "travel").contains(method.getName())) builder.copy(method);
        }
        for (Method method : Entity.class.getDeclaredMethods()) {
            if (Set.of("tick", "baseTick", "updateSwimming", "setPose", "hurt", "checkBelowWorld").contains(method.getName())) builder.copy(method);
        }
        // The native break callback mutates owned equipment and emits a captured
        // entity status through the private world.
        for (Method method : ItemStack.class.getDeclaredMethods()) {
            if (method.getName().equals("hurtAndBreak")) builder.copy(method);
        }
        // Shield wear/disable enters item-component and cooldown methods. Copy the
        // entire causal call path so its statistics, events and packets stay private.
        for (Method method : net.minecraft.world.item.component.BlocksAttacks.class.getDeclaredMethods()) {
            if (Set.of("hurtBlockingItem", "disable", "onBlocked").contains(method.getName())) builder.copy(method);
            else if ((method.getName().startsWith("lambda$disable$") || method.getName().startsWith("lambda$onBlocked$"))) builder.copyLambda(method);
        }
        for (Class<?> type : new Class<?>[]{net.minecraft.world.item.ItemCooldowns.class, net.minecraft.world.item.ServerItemCooldowns.class}) {
            for (Method method : type.getDeclaredMethods()) {
                if (Set.of("addCooldown", "onCooldownStarted").contains(method.getName())) builder.copy(method);
            }
        }
        try {
            var lookup = MethodHandles.lookup();
            builder.read(net.minecraft.world.level.Level.class.getDeclaredField("random"),
                    lookup.findVirtual(PaperRollbackCombatAccess.class, "worldRandom",
                            MethodType.methodType(RandomSource.class, net.minecraft.world.level.Level.class)).bindTo(this));
            builder.dispatch(Entity.class.getDeclaredMethod("hurtServer", ServerLevel.class, DamageSource.class, float.class));
            builder.dispatch(Entity.class.getDeclaredMethod("onBelowWorld"));
            // This final listener method cannot be intercepted by a query shell.
            // Bind the call from the copied mobility check to the owned session.
            builder.copy(ServerPlayer.class.getDeclaredMethod("isImmobile"));
            builder.copy(ServerPlayer.class.getDeclaredMethod("jumpFromGround"));
            Method serverTickMethod = ServerPlayer.class.getDeclaredMethod("tick");
            Method serverBodyMethod = ServerPlayer.class.getDeclaredMethod("doTick");
            builder.copy(serverTickMethod).copy(serverBodyMethod);
            builder.copy(ServerPlayer.class.getDeclaredMethod("updateScoreForCriteria", net.minecraft.world.scores.criteria.ObjectiveCriteria.class, int.class));
            for (Method method : ServerPlayer.class.getDeclaredMethods()) {
                if (method.getName().startsWith("lambda$updateScoreForCriteria$")) builder.copyLambda(method);
            }
            builder.copy(ServerPlayer.class.getDeclaredMethod("tickRegeneration"));
            builder.copy(net.minecraft.world.food.FoodData.class.getDeclaredMethod("tick", ServerPlayer.class));
            builder.replace(System.class.getMethod("nanoTime"),
                    lookup.findStatic(RollbackClock.class, "nanos", MethodType.methodType(long.class)));
            builder.replace(ServerGamePacketListenerImpl.class.getMethod("isDisconnected"),
                    lookup.findVirtual(PaperRollbackCombatAccess.class, "disconnected",
                            MethodType.methodType(boolean.class, ServerGamePacketListenerImpl.class)).bindTo(this));
            builder.replace(Math.class.getMethod("random"), lookup.findVirtual(PaperRollbackCombatAccess.class, "randomDouble", MethodType.methodType(double.class)).bindTo(this));
            builder.replace(GlobalConfiguration.class.getMethod("get"),
                    lookup.findVirtual(PaperRollbackCombatAccess.class, "configuration", MethodType.methodType(GlobalConfiguration.class)).bindTo(this));
            // Resurrection is native; actual death still stops before unaudited
            // drops, spawning or external death listeners can be reached.
            for (Class<?> owner : new Class<?>[]{LivingEntity.class, Player.class, ServerPlayer.class}) {
                builder.replace(owner.getDeclaredMethod("die", DamageSource.class),
                        lookup.findStatic(PaperRollbackCombatAccess.class, "unsupportedDeath",
                                MethodType.methodType(void.class, LivingEntity.class, DamageSource.class))
                                .asType(MethodType.methodType(void.class, owner, DamageSource.class)));
            }
            Method entry = Player.class.getDeclaredMethod("hurtServer", ServerLevel.class, DamageSource.class, float.class);
            Method serverEntry = ServerPlayer.class.getDeclaredMethod("hurtServer", ServerLevel.class, DamageSource.class, float.class);
            Method award = ServerPlayer.class.getDeclaredMethod("awardStat", Stat.class, int.class);
            Method reset = ServerPlayer.class.getDeclaredMethod("resetStat", Stat.class);
            Method add = LivingEntity.class.getDeclaredMethod("addEffect", net.minecraft.world.effect.MobEffectInstance.class,
                    org.bukkit.event.entity.EntityPotionEffectEvent.Cause.class);
            Method remove = LivingEntity.class.getDeclaredMethod("removeEffect", Holder.class,
                    org.bukkit.event.entity.EntityPotionEffectEvent.Cause.class);
            Method air = Entity.class.getDeclaredMethod("setAirSupply", int.class);
            Method swimming = Entity.class.getDeclaredMethod("setSwimming", boolean.class);
            Method movement = Player.class.getDeclaredMethod("aiStep");
            builder.before(movement, lookup.findStatic(PaperRollbackClientMovement.class, "apply", MethodType.methodType(void.class, Player.class)));
            Method bodyTick = Player.class.getDeclaredMethod("tick");
            builder.copy(ServerPlayer.class.getDeclaredMethod("pushEntities"));
            builder.copy(swimming);
            builder.copy(air);
            builder.copy(award).copy(reset).copy(serverEntry).copy(ServerPlayer.class.getMethod("canHarmPlayer", Player.class));
            Method stopUse = LivingEntity.class.getDeclaredMethod("stopUsingItem");
            Method equipment = Player.class.getDeclaredMethod("detectEquipmentUpdates");
            var copied = builder.build();
            playerImmobile = copied.get(Player.class.getDeclaredMethod("isImmobile"));
            serverImmobile = copied.get(ServerPlayer.class.getDeclaredMethod("isImmobile"));
            stopItemUse = copied.get(stopUse); equipmentChanges = copied.get(equipment);
            damage = copied.get(entry); serverDamage = copied.get(serverEntry); awardStatistic = copied.get(award); resetStatistic = copied.get(reset);
            addEffect = copied.get(add); removeEffect = copied.get(remove); setAir = copied.get(air);
            setSwimming = copied.get(swimming);
            movementStep = copied.get(movement);
            playerBodyTick = copied.get(bodyTick);
            serverTick = copied.get(serverTickMethod); serverBodyTick = copied.get(serverBodyMethod);
            serverJump = copied.get(ServerPlayer.class.getDeclaredMethod("jumpFromGround"));
            tryGlide = copied.get(Player.class.getDeclaredMethod("tryToStartFallFlying"));
            stopGlide = copied.get(LivingEntity.class.getDeclaredMethod("stopFallFlying"));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native Paper damage methods changed", failure); }
    }

    void bind(RollbackNativeQueryShell<ServerLevel> shell) {
        GameRules rules = RollbackNativeQueryShell.create(GameRules.class)
                .query(value -> value.get(GameRules.FIRE_DAMAGE), false, args -> state.rule(args[0])).instance();
        var subscribers = RollbackNativeQueryShell.create(net.minecraft.util.debug.ServerDebugSubscribers.class)
                .query(value -> value.hasAnySubscriberFor(net.minecraft.util.debug.DebugSubscriptions.ENTITY_BLOCK_INTERSECTIONS), false, args -> false).instance();
        // Replay advances at the engine's fixed 20 Hz; it has no debug subscribers.
        var tickRate = RollbackNativeQueryShell.create(net.minecraft.server.ServerTickRateManager.class)
                .query(net.minecraft.world.TickRateManager::runsNormally, true, args -> true).instance();
        MinecraftServer server = RollbackNativeQueryShell.create(MinecraftServer.class)
                .constant(MinecraftServer::debugSubscribers, subscribers)
                .constant(MinecraftServer::reloadableRegistries, new ReloadableServerRegistries.Holder(registries)).instance();
        var waypoints = RollbackNativeQueryShell.create(net.minecraft.server.waypoints.ServerWaypointManager.class)
                .outputQuery(value -> value.updateWaypoint(null), args -> waypoint((Entity) args[0], false, WaypointAction.UPDATE))
                .outputQuery(value -> value.updatePlayer(null), args -> waypoint((Entity) args[0], true, WaypointAction.UPDATE))
                .outputQuery(value -> value.trackWaypoint(null), args -> waypoint((Entity) args[0], false, WaypointAction.TRACK))
                .outputQuery(value -> value.untrackWaypoint(null), args -> waypoint((Entity) args[0], false, WaypointAction.UNTRACK))
                .outputQuery(value -> value.addPlayer(null), args -> waypoint((Entity) args[0], true, WaypointAction.TRACK))
                .outputQuery(value -> value.removePlayer(null), args -> waypoint((Entity) args[0], true, WaypointAction.UNTRACK)).instance();
        var bukkitWorld = RollbackNativeQueryShell.create(org.bukkit.craftbukkit.CraftWorld.class)
                .query(org.bukkit.craftbukkit.CraftWorld::getEnvironment, null, args -> state.worldPolicy().environment())
                .query(org.bukkit.craftbukkit.CraftWorld::isVoidDamageEnabled, false, args -> state.worldPolicy().voidDamageEnabled())
                .query(org.bukkit.craftbukkit.CraftWorld::getVoidDamageAmount, 0F, args -> state.worldPolicy().voidDamageAmount())
                .query(org.bukkit.craftbukkit.CraftWorld::getVoidDamageMinBuildHeightOffset, 0D, args -> state.worldPolicy().voidDamageHeightOffset())
                .instance();
        shell.constant(ServerLevel::registryAccess, registries)
                .query(ServerLevel::getLagCompensationTick, 0L, args -> state.time())
                .constant(ServerLevel::getWorld, bukkitWorld)
                .constant(ServerLevel::tickRateManager, tickRate)
                .nativeQuery(value -> value.getEntities(null, new net.minecraft.world.phys.AABB(0, 0, 0, 1, 1, 1)), java.util.List.of(), args -> ownedId((Entity) args[0]))
                .nativeQuery(value -> value.getPushableEntities(null, new net.minecraft.world.phys.AABB(0, 0, 0, 1, 1, 1)), java.util.List.of(), args -> ownedId((Entity) args[0]))
                .constant(ServerLevel::getWaypointManager, waypoints)
                .constant(ServerLevel::getScoreboard, world.scoreboards().nativeMain())
                .query(ServerLevel::paperConfig, null, args -> worldConfiguration())
                .constant(ServerLevel::getCraftServer, events.server())
                .constant(ServerLevel::damageSources, damageSources)
                .constant(ServerLevel::getGameRules, rules)
                .constant(ServerLevel::getServer, server)
                .query(ServerLevel::getGameTime, 0L, args -> state.time())
                .query(ServerLevel::getDifficulty, Difficulty.NORMAL, args -> state.difficulty())
                .query(ServerLevel::isPvpAllowed, false, args -> state.pvpAllowed())
                .query(ServerLevel::getRandom, null, args -> state.random())
                .outputQuery(value -> value.broadcastDamageEvent(null, null), args -> damageOutput((Entity) args[0], (DamageSource) args[1]))
                .outputQuery(value -> value.broadcastEntityEvent(null, (byte) 0), args -> state.output(new StatusOutput(ownedId((Entity) args[0]), (byte) args[1])))
                .outputQuery(value -> value.playSound(null, 0, 0, 0, SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1F, 1F), args -> sound(args, false))
                .outputQuery(value -> value.playSound(null, 0, 0, 0, Holder.direct(SoundEvents.PLAYER_HURT), SoundSource.PLAYERS, 1F, 1F), args -> sound(args, false))
                .nativeAction(value -> value.playSound(null, net.minecraft.core.BlockPos.ZERO, SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1F, 1F), args -> nullableOwnedId((Entity) args[0]))
                .outputQuery(value -> value.playSeededSound(null, 0, 0, 0, Holder.direct(SoundEvents.PLAYER_HURT), SoundSource.PLAYERS, 1F, 1F, 0L), args -> sound(args, true))
                .nativeAction(value -> value.gameEvent((Entity) null, GameEvent.ENTITY_DAMAGE, Vec3.ZERO), args -> nullableOwnedId((Entity) args[0]))
                .outputQuery(value -> value.gameEvent(GameEvent.ENTITY_DAMAGE, Vec3.ZERO, new GameEvent.Context(null, null)), args -> {
                    nullableOwnedId(((GameEvent.Context) args[2]).sourceEntity());
                    position((Vec3) args[1]);
                    state.gameEvent(args[0], args[1], args[2]);
                });
    }

    private void waypoint(Entity entity, boolean receiver, WaypointAction action) {
        UUID id = ownedId(entity);
        state.output(new WaypointOutput(id, position(entity.position()), receiver, action));
    }

    void potion(Player player, net.minecraft.world.effect.MobEffectInstance effect) {
        ownedId(player); requireEffect(effect.getEffect());
        try {
            boolean ignored = (boolean) addEffect.invokeExact((LivingEntity) player, effect, org.bukkit.event.entity.EntityPotionEffectEvent.Cause.PLUGIN);
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Private native potion application failed", failure); }
    }

    void removePotion(Player player, Holder<net.minecraft.world.effect.MobEffect> effect) {
        ownedId(player); requireEffect(effect);
        try {
            boolean ignored = (boolean) removeEffect.invokeExact((LivingEntity) player, (Holder) effect, org.bukkit.event.entity.EntityPotionEffectEvent.Cause.PLUGIN);
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Private native potion removal failed", failure); }
    }

    @SuppressWarnings("WrapperReferenceEquality")
    private void requireEffect(Holder<net.minecraft.world.effect.MobEffect> effect) {
        var key = effect.unwrapKey().orElseThrow(() -> new IllegalArgumentException("Unregistered native effect"));
        if (registries.lookupOrThrow(Registries.MOB_EFFECT).getOrThrow(key) != effect) throw new IllegalArgumentException("Foreign native effect");
    }

    void air(Player player, int amount) {
        ownedId(player);
        try { setAir.invokeExact((Entity) player, amount); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Private native air change failed", failure); }
    }

    void bindEvents(RollbackNativeMethods methods) { events.bind(methods); }
    void swimming(Player player, boolean value) {
        ownedId(player);
        try { setSwimming.invokeExact((Entity) player, value); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Private native swimming change failed", failure); }
    }
    boolean requestFlight(ServerPlayer player, boolean flying, boolean cancelled) {
        ownedId(player);
        var abilities = player.getAbilities();
        if (!abilities.mayfly) return false;
        if (abilities.flying == flying) return true;
        var event = new org.bukkit.event.player.PlayerToggleFlightEvent(player.getBukkitEntity(), flying);
        event.setCancelled(cancelled);
        if (!events.dispatch(event)) { player.onUpdateAbilities(); return false; }
        abilities.flying = flying;
        player.onUpdateAbilities();
        if (flying && player.onGround()) {
            prepareMovement();
            try { serverJump.invokeExact(player); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new IllegalStateException("Private native flight takeoff failed", failure); }
        }
        return true;
    }
    void stepMovement(Player player) {
        ownedId(player);
        prepareMovement();
        try { movementStep.invokeExact(player); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Private native movement phase failed", failure); }
    }
    boolean requestGlide(Player player) {
        ownedId(player);
        try {
            if (!(boolean) tryGlide.invokeExact(player)) stopGlide.invokeExact((LivingEntity) player);
            return player.isFallFlying();
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Private native glide input failed", failure); }
    }
    void tickPlayerBody(Player player) {
        ownedId(player);
        prepareMovement();
        player.setOldPosAndRot();
        player.tickCount++;
        player.totalEntityAge++;
        try { playerBodyTick.invokeExact(player); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Private native player body tick failed", failure); }
    }
    boolean selectSlot(Player player, int slot) {
        ownedId(player);
        if (slot < 0 || slot >= net.minecraft.world.entity.player.Inventory.getSelectionSize())
            throw new IllegalArgumentException("Selected slot outside hotbar");
        try {
            boolean immobile = player instanceof ServerPlayer server ? (boolean) serverImmobile.invokeExact(server)
                    : (boolean) playerImmobile.invokeExact(player);
            if (immobile) return false;
            int previous = player.getInventory().getSelectedSlot();
            if (previous == slot) return true;
            var event = new org.bukkit.event.player.PlayerItemHeldEvent((org.bukkit.entity.Player) player.getBukkitEntity(), previous, slot);
            state.event(event);
            if (event.isCancelled()) return false;
            if (player.getInventory().getSelectedSlot() != slot && player.getUsedItemHand() == net.minecraft.world.InteractionHand.MAIN_HAND)
                stopItemUse.invokeExact((LivingEntity) player);
            player.getInventory().setSelectedSlot(slot);
            if (state.updateEquipmentOnPlayerActions()) equipmentChanges.invokeExact(player);
            return true;
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Private native slot selection failed", failure); }
    }
    boolean swapHands(Player player) {
        ownedId(player);
        if (player.isSpectator()) return false;
        var originalMain = player.getMainHandItem(); var originalOff = player.getOffhandItem();
        var proposedMain = org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(originalOff);
        var proposedOff = org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(originalMain);
        var event = new org.bukkit.event.player.PlayerSwapHandItemsEvent(
                (org.bukkit.entity.Player) player.getBukkitEntity(), proposedMain.clone(), proposedOff.clone());
        state.event(event);
        if (event.isCancelled()) return false;
        // Decode both replacements before changing either slot. Unchanged items keep their owned aliases.
        var main = proposedMain.equals(event.getMainHandItem()) ? originalOff
                : org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getMainHandItem());
        var off = proposedOff.equals(event.getOffHandItem()) ? originalMain
                : org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getOffHandItem());
        player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, off);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, main);
        try {
            stopItemUse.invokeExact((LivingEntity) player);
            if (state.updateEquipmentOnPlayerActions()) equipmentChanges.invokeExact(player);
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Private native hand swap failed", failure); }
        return true;
    }
    void tickPlayer(ServerPlayer player) {
        ownedId(player);
        if (!player.connection.hasClientLoaded()) throw new IllegalStateException("Player must load before private ticking");
        prepareMovement();
        player.setOldPosAndRot();
        player.tickCount++;
        player.totalEntityAge++;
        try { serverTick.invokeExact(player); serverBodyTick.invokeExact(player); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Private native server player tick failed", failure); }
    }
    private void prepareMovement() {
        float walking = state.jumpExhaustion(false), sprinting = state.jumpExhaustion(true);
        if (!Float.isFinite(walking) || walking < 0 || !Float.isFinite(sprinting) || sprinting < 0) {
            throw new IllegalArgumentException("Captured jump exhaustion");
        }
        float regeneration = state.regenerationExhaustion();
        if (!Float.isFinite(regeneration) || regeneration < 0) throw new IllegalArgumentException("Captured regeneration exhaustion");
        world.world().spigotConfig.jumpWalkExhaustion = walking;
        world.world().spigotConfig.jumpSprintExhaustion = sprinting;
        world.world().spigotConfig.regenExhaustion = regeneration;
    }
    void bindAdvancementRewards(RollbackNativeMethods methods) {
        try {
            methods.copy(net.minecraft.advancements.AdvancementRewards.class.getMethod("grant", ServerPlayer.class));
            for (Class<?> type : new Class<?>[]{Player.class, ServerPlayer.class}) {
                methods.copy(type.getDeclaredMethod("giveExperiencePoints", int.class));
                methods.copy(type.getDeclaredMethod("giveExperienceLevels", int.class));
            }
            var soundType = MethodType.methodType(void.class, Player.class, double.class, double.class, double.class,
                    SoundEvent.class, SoundSource.class, float.class, float.class);
            methods.replace(Player.class.getDeclaredMethod("sendSoundEffect", soundType.parameterArray()),
                    MethodHandles.lookup().findVirtual(PaperRollbackCombatAccess.class, "playerSound", soundType).bindTo(this));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native advancement reward methods changed", failure); }
    }

    private void playerSound(Player from, double x, double y, double z, SoundEvent sound, SoundSource category, float volume, float pitch) {
        UUID source = ownedId(from);
        world.world().playSound(from, x, y, z, sound, category, volume, pitch);
        if (from instanceof ServerPlayer) {
            state.output(new TargetSoundOutput(source, checkedPosition(x, y, z), sound.location().toString(), category.getName(), volume, pitch, from.random.nextLong()));
        }
    }

    boolean damage(Object player, Object source, float amount) {
        Player target = (Player) player;
        DamageSource cause = (DamageSource) source;
        ownedId(target); requireSource(cause);
        if (!Float.isFinite(amount) || amount < 0) throw new IllegalArgumentException("Damage amount");
        try {
            return target instanceof ServerPlayer server
                    ? (boolean) serverDamage.invokeExact(server, (ServerLevel) world.world(), cause, amount)
                    : (boolean) damage.invokeExact(target, (ServerLevel) world.world(), cause, amount);
        }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Private native damage failed", failure); }
    }

    void statistic(Object player, Object statistic, int amount, boolean reset) {
        var target = (ServerPlayer) player;
        var stat = (Stat<?>) statistic;
        ownedId(target);
        PaperRollbackStatistics.requireStat(stat);
        try {
            if (reset) resetStatistic.invokeExact(target, stat);
            else awardStatistic.invokeExact(target, stat, amount);
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Private native player statistic failed", failure); }
    }

    private WorldConfiguration worldConfiguration() {
        worldConfiguration.tickRates.containerUpdate = state.containerUpdateRate();
        worldConfiguration.entities.behavior.parrotsAreUnaffectedByPlayerMovement = state.parrotsStayOnShoulder();
        worldConfiguration.environment.netherCeilingVoidDamageHeight = new io.papermc.paper.configuration.type.number.IntOr.Disabled(state.worldPolicy().netherCeilingHeight());
        worldConfiguration.scoreboards.allowNonPlayerEntitiesOnScoreboards = state.allowNonPlayerEntitiesOnScoreboards();
        worldConfiguration.collisions.allowPlayerCrammingDamage = state.allowPlayerCrammingDamage();
        int maximum = state.maximumEntityCollisions();
        if (maximum < 0 || maximum > 1_024) throw new IllegalArgumentException("Native entity collision budget");
        worldConfiguration.collisions.maxEntityCollisions = maximum;
        return worldConfiguration;
    }

    @SuppressWarnings("WrapperReferenceEquality")
    private void requireSource(DamageSource source) {
        Objects.requireNonNull(source, "native damage source");
        nullableOwnedId(source.getEntity()); nullableOwnedId(source.getDirectEntity()); nullableOwnedId(source.eventEntityDamager());
        if (source.eventBlockDamager() != null || source.causingBlockSnapshot() != null) {
            throw new IllegalArgumentException("Block damage source requires the private Bukkit block adapter");
        }
        var holder = source.typeHolder();
        var key = holder.unwrapKey().orElseThrow(() -> new IllegalArgumentException("Unregistered native damage type"));
        if (damageRegistry.getOrThrow(key) != holder) throw new IllegalArgumentException("Damage type belongs to another native registry");
        if (source.getSourcePosition() != null) position(source.getSourcePosition());
    }

    private void damageOutput(Entity target, DamageSource source) {
        UUID entity = ownedId(target); requireSource(source);
        var key = source.typeHolder().unwrapKey().orElseThrow();
        Vec3 storedPosition = source.sourcePositionRaw();
        state.output(new DamageOutput(entity, key.identifier().toString(), nullableOwnedId(source.getDirectEntity()),
                nullableOwnedId(source.getEntity()), storedPosition == null ? null : position(storedPosition)));
    }

    private void sound(Object[] args, boolean seeded) {
        SoundEvent event = args[4] instanceof Holder<?> ? (SoundEvent) ((Holder<?>) args[4]).value() : (SoundEvent) args[4];
        state.output(new SoundOutput(nullableOwnedId((Entity) args[0]), checkedPosition((double) args[1], (double) args[2], (double) args[3]),
                event.location().toString(), ((SoundSource) args[5]).getName(), (float) args[6], (float) args[7], seeded ? (long) args[8] : state.nextSoundSeed()));
    }

    private static Position position(Vec3 position) { return checkedPosition(position.x, position.y, position.z); }
    private static Position checkedPosition(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("Native combat position");
        return new Position(x, y, z);
    }
    private UUID nullableOwnedId(Entity entity) { return entity == null ? null : ownedId(entity); }
    @SuppressWarnings("WrapperReferenceEquality")
    private UUID ownedId(Entity entity) {
        if (!world.ownsPlayer(entity) || entity.level() != world.world()) throw new IllegalArgumentException("Native combat entity belongs to another simulation");
        return entity.getUUID();
    }
    private RandomSource worldRandom(net.minecraft.world.level.Level level) {
        if (level != world.world()) throw new IllegalArgumentException("Foreign combat world");
        return (RandomSource) state.random();
    }
    private double randomDouble() { return ((RandomSource) state.random()).nextDouble(); }
    private boolean disconnected(ServerGamePacketListenerImpl listener) {
        // getPlayer validates the private listener's ownership. A replica has no
        // socket; disconnecting a participant ends the enclosing session.
        ownedId(listener.getPlayer());
        return false;
    }
    private GlobalConfiguration configuration() {
        globalConfiguration.unsupportedSettings.skipVanillaDamageTickWhenShieldBlocked = state.skipVanillaDamageTickWhenShieldBlocked();
        globalConfiguration.unsupportedSettings.updateEquipmentOnPlayerActions = state.updateEquipmentOnPlayerActions();
        return globalConfiguration;
    }
    private static void unsupportedDeath(LivingEntity entity, DamageSource source) {
        throw new IllegalStateException("Lethal damage requires private death/spawn services");
    }
}
