package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.earthbending.Catapult;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.BlockSource;
import com.projectkorra.projectkorra.util.BlockSource.BlockSourceType;
import com.projectkorra.projectkorra.util.BlockSourceInformation;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.TempFallingBlock;
import com.projectkorra.projectkorra.util.TempArmorStand;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.platform.mc.metadata.FixedMetadataValue;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody;
import java.util.*;

/** Per-participant delayed Catapult landings and block-source selections. */
public final class RollbackAbilityGlobals {
    private boolean enrolled;
    private Map<Entity, Map<String, List<FixedMetadataValue>>> actorMetadata = new LinkedHashMap<>();
    private Map<UUID, Map<String, Object>> participantMetadata = new TreeMap<>();
    private RollbackDigging mining = new RollbackDigging();
    public static RollbackDigging activeDigging() {
        var globals = RollbackDomain.abilityGlobals();
        return globals == null ? null : globals.mining;
    }
    public static RollbackDigging digging() {
        return Objects.requireNonNull(RollbackDomain.abilityGlobals(), "Missing private ability state").mining;
    }
    private RollbackAddonActors addonActors;
    public RollbackAddonActors addonActors() {
        if (addonActors == null) addonActors = new RollbackAddonActors();
        return addonActors;
    }
    private boolean runMaintenance;
    private com.projectkorra.projectkorra.event.WorldTimeEvent.Time previousWorldPhase;
    public static com.projectkorra.projectkorra.event.WorldTimeEvent.Time previousWorldPhase() {
        var globals = RollbackDomain.abilityGlobals();
        return globals == null ? null : globals.previousWorldPhase;
    }
    private long regenerationDelay, elementExpiryDelay, chiMaintenanceDelay;

    private RollbackParticipantStatuses statuses = new RollbackParticipantStatuses();
    private TempBlock.RollbackLayers layers;
    private RollbackArenaBlockIndices unleasedIndices;

    RollbackArenaBlockIndices blockIndices() {
        if (layers != null) return layers.abilityIndices();
        if (unleasedIndices == null) unleasedIndices = new RollbackArenaBlockIndices();
        return unleasedIndices;
    }
    private Map<FallingBlock, TempFallingBlock> falling = new LinkedHashMap<>();
    private Set<TempArmorStand> stands = new LinkedHashSet<>();
    private Set<UUID> landings = new LinkedHashSet<>();
    private Map<Player, HashMap<BlockSourceType, HashMap<ClickType, BlockSourceInformation>>> sources = new LinkedHashMap<>();

    public static RollbackAbilityGlobals capture(Collection<Player> participants) {
        if (RollbackDomain.active()) throw new IllegalStateException("Capture participant registries on the live server");
        var data = new RollbackAbilityGlobals();
        data.enrolled = true;
        var manager = com.projectkorra.projectkorra.BendingManager.getInstance();
        if (manager != null && !participants.isEmpty()) data.previousWorldPhase = manager.lastPhase(participants.iterator().next().getWorld());
        for (Player player : participants) {
            if (Catapult.isAwaitingLanding(player)) data.landings.add(player.getUniqueId());
            var owned = BlockSource.captureRollbackSources(player);
            if (owned != null) data.sources.put(player, owned);
        }
        data.statuses.capture(participants);
        return data;
    }

    public void captureMaintenance(RollbackLiveScheduler scheduler) {
        regenerationDelay = scheduler.managerDelay(com.projectkorra.projectkorra.util.RegenHandler.class, 20);
        elementExpiryDelay = scheduler.managerDelay(com.projectkorra.projectkorra.BendingManager.TempElementsRunnable.class, 20);
        chiMaintenanceDelay = scheduler.managerDelay(me.literka.util.Utils.Maintenance.class, 1);
        long standsDelay = scheduler.managerDelay(me.moros.hyperion.util.TempArmorStand.Manager.class, 1);
        long fallingDelay = scheduler.managerDelay(me.moros.hyperion.util.BendingFallingBlock.Manager.class, 5);
        if (standsDelay > 0 || fallingDelay > 0) addonActors().timerDelays(standsDelay, fallingDelay);
        runMaintenance = true;
    }

    void progressMaintenance(long tick) {
        if (runMaintenance) RollbackBendingMaintenance.run(tick, regenerationDelay, elementExpiryDelay);
        if (chiMaintenanceDelay > 0 && tick >= chiMaintenanceDelay) me.literka.util.Utils.tick();
        if (addonActors != null) addonActors.tick(tick);
    }

    RollbackParticipantStatuses participantStatuses() { return statuses; }

    /** An already-live temporary actor cannot become part of a newly captured native scene. */
    public void requireNoInitialActors(World world, RollbackBlockStore.Bounds bounds, Set<UUID> participants) {
        RollbackAddonActors.requireNoInitialActors(entity -> inside(world, bounds, entity), participants);
        for (var entry : TempFallingBlock.instances.entrySet()) {
            var ability = entry.getValue().getAbility();
            if (inside(world, bounds, entry.getKey()) || ability != null && ability.getPlayer() != null && participants.contains(ability.getPlayer().getUniqueId()))
                throw new IllegalStateException("Duel enrollment overlaps an existing temporary falling block");
        }
        for (var stand : TempArmorStand.getTempStands()) if (inside(world, bounds, stand.getArmorStand()))
            throw new IllegalStateException("Duel enrollment overlaps an existing temporary armor stand");
    }
    private static boolean inside(World world, RollbackBlockStore.Bounds bounds, Entity entity) {
        var loc = entity.getLocation();
        return loc.getWorld().handle() == world.handle() && bounds.contains(new RollbackBlockStore.Position(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
    }
    public static Map<FallingBlock, TempFallingBlock> falling(Map<FallingBlock, TempFallingBlock> live) {
        var scope = RollbackDomain.abilityGlobals();
        return scope == null ? live : scope.falling;
    }
    public static Set<TempArmorStand> stands(Set<TempArmorStand> live) {
        var scope = RollbackDomain.abilityGlobals();
        return scope == null ? live : scope.stands;
    }

    public void bindLayers(TempBlock.RollbackLayers captured) {
        if (layers != null) throw new IllegalStateException("Temporary layers already captured");
        layers = Objects.requireNonNull(captured);
    }
    public TempBlock.RollbackLayers capturedLayers() { return layers; }
    public static TempBlock.RollbackLayers layers() {
        var scope = RollbackDomain.abilityGlobals();
        return scope == null ? null : scope.layers;
    }
    RollbackAbilityGlobals portable(Collection<? extends Entity> actors, Collection<? extends Player> participants) {
        var copy = new RollbackAbilityGlobals();
        copy.runMaintenance = runMaintenance;
        copy.previousWorldPhase = previousWorldPhase;
        copy.regenerationDelay = regenerationDelay;
        copy.elementExpiryDelay = elementExpiryDelay;
        copy.chiMaintenanceDelay = chiMaintenanceDelay;
        copy.participantMetadata = RollbackParticipantMetadata.capture(participants);
        for (var actor : actors) {
            var metadata = RollbackEntityBody.logicalBody(actor).exportMetadata();
            if (!metadata.isEmpty()) copy.actorMetadata.put(actor, metadata);
        }
        copy.enrolled = enrolled; copy.landings = landings; copy.sources = sources; copy.statuses = statuses;
        copy.addonActors = addonActors;
        copy.mining = mining;
        copy.falling = falling; copy.stands = stands;
        copy.unleasedIndices = unleasedIndices;
        copy.layers = layers == null ? null : layers.portable();
        return copy;
    }
    void restoreParticipantMetadata(Map<UUID, Player> roster) {
        RollbackParticipantMetadata.apply(participantMetadata, roster);
        participantMetadata.clear();
    }

    public void install() {
        RollbackDomain.installAbilityGlobals(this);
        if (layers != null) layers.install();
    }

    /** A private tick may not advance a shared live timer that still lacks participant ownership. */
    static void requireIsolatedManagers() {
        var state = RollbackDomain.abilityGlobals();
        if (state != null && state.enrolled && (state.layers == null || state.layers.earth() == null))
            RollbackLiveRegistryEligibility.requireAvailable();
    }

    public static Set<UUID> landings(Set<UUID> live) {
        var current = RollbackDomain.abilityGlobals();
        return current == null ? live : current.landings;
    }

    public static Map<Player, HashMap<BlockSourceType, HashMap<ClickType, BlockSourceInformation>>> sources(
            Map<Player, HashMap<BlockSourceType, HashMap<ClickType, BlockSourceInformation>>> live) {
        var current = RollbackDomain.abilityGlobals();
        return current == null ? live : current.sources;
    }

    /** Resolve each key against the canonical roster before touching any live membership. */
    void validate(Map<UUID, Player> roster) {
        statuses.validate(roster);
        RollbackParticipantMetadata.validate(participantMetadata, roster);
        if (addonActors != null) addonActors.validateOwners(roster);
        if (!falling.isEmpty() || !stands.isEmpty()) {
            var world = roster.values().iterator().next().getWorld();
            for (var stand : stands) if (stand.getArmorStand() == null || stand.getArmorStand().getWorld().handle() != world.handle())
                throw new IllegalStateException("Temporary armor stand belongs to another world");
            for (var entry : falling.entrySet()) {
                var block = entry.getValue(); var caster = block.getAbility() == null ? null : block.getAbility().getPlayer();
                if (caster == null || !roster.containsKey(caster.getUniqueId()) || block.getFallingBlock().handle() != entry.getKey().handle()
                        || entry.getKey().getWorld().handle() != world.handle()) throw new IllegalStateException("Invalid private temporary falling block");
            }
        }
        if (!roster.keySet().containsAll(landings) || sources.size() > roster.size())
            throw new IllegalArgumentException("Ability registry owner outside duel");
        for (var entry : sources.entrySet()) {
            Player owner = roster.get(entry.getKey().getUniqueId());
            if (owner == null || owner.handle() != entry.getKey().handle() || entry.getValue().size() > BlockSourceType.values().length)
                throw new IllegalArgumentException("Block source belongs to another player");
            entry.getValue().forEach((type, clicks) -> {
                if (type == null || clicks == null || clicks.size() > ClickType.values().length) throw new IllegalArgumentException("Invalid source selections");
                clicks.forEach((click, info) -> {
                    if (click == null || info != null && (info.getPlayer() == null || info.getPlayer().handle() != owner.handle()
                            || info.getSourceType() != type || info.getClickType() != click || info.getBlock() == null))
                        throw new IllegalArgumentException("Invalid source selection");
                });
            });
        }
    }

    public void validateTemporaryActors(RollbackActorData data) {
        if (stands.isEmpty() && falling.isEmpty() && addonActors == null && actorMetadata.isEmpty()) return;
        var registered = new HashMap<UUID, com.projectkorra.projectkorra.platform.mc.entity.EntityType>();
        for (var row : data.actors()) registered.put(row.identity().uuid(), row.identity().type());
        for (var actor : actorMetadata.keySet()) {
            if (registered.get(actor.getUniqueId()) != actor.getType())
                throw new IllegalStateException("Metadata belongs to an actor outside the confirmed scene");
        }
        var referenced = new HashSet<UUID>();
        for (var stand : stands) requireTemporaryActor(stand.getArmorStand(), registered, referenced);
        for (var block : falling.keySet()) requireTemporaryActor(block, registered, referenced);
        if (addonActors != null) addonActors.validateActors(registered, referenced);
    }

    static void requireTemporaryActor(Entity entity, Map<UUID, com.projectkorra.projectkorra.platform.mc.entity.EntityType> registered, Set<UUID> referenced) {
        var expected = entity instanceof FallingBlock ? com.projectkorra.projectkorra.platform.mc.entity.EntityType.FALLING_BLOCK
                : com.projectkorra.projectkorra.platform.mc.entity.EntityType.ARMOR_STAND;
        if (entity == null || registered.get(entity.getUniqueId()) != expected || !referenced.add(entity.getUniqueId()))
            throw new IllegalStateException("Temporary bending actor is missing or duplicated in the confirmed scene");
    }

    public static void validateInstalledActors(RollbackActorData data) {
        var globals = RollbackDomain.abilityGlobals();
        if (globals != null) globals.validateTemporaryActors(data);
    }

    /** Restores gameplay-owned actor markers after native correction creates fresh logical bodies. */
    public static void repairActorMetadata(RollbackActorData data) {
        var globals = RollbackDomain.abilityGlobals();
        if (globals == null) return;
        globals.validateTemporaryActors(data);
        globals.restoreActorMetadata();
        globals.falling.values().forEach(TempFallingBlock::restoreMetadata);
        globals.stands.forEach(TempArmorStand::restoreMetadata);
        if (globals.addonActors != null)
            globals.addonActors.stands.values().forEach(me.moros.hyperion.util.TempArmorStand::restoreMetadata);
    }

    private void restoreActorMetadata() {
        actorMetadata.forEach((actor, keys) -> keys.forEach((key, values) -> {
            for (var value : values) ensureActorMetadata(actor, key, RollbackMetadataOwner.local(value.owner()), value.value());
        }));
        actorMetadata.clear();
    }

    public static void ensureActorMetadata(Entity entity, String key, Object owner, Object value) {
        if (entity instanceof com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.View
                && com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.logicalBody(entity).hasMetadata(key, owner, value)) return;
        entity.setMetadata(key, new com.projectkorra.projectkorra.platform.mc.metadata.FixedMetadataValue(owner, value));
    }

    void rebase(RollbackTimeShift time) {
        if (!time.active()) return;
        statuses.rebase(time);
        for (var kinds : sources.values()) for (var clicks : kinds.values()) for (var info : clicks.values()) {
            if (info != null) time.once(info, () -> info.setCreationTime(time.time(info.getCreationTime())));
        }
        falling.values().forEach(block -> block.rebase(time));
        if (addonActors != null) addonActors.rebase(time);
        if (layers != null) layers.rebase(time);
        if (unleasedIndices != null) unleasedIndices.rebase(time);
    }

    void merge(Map<UUID, Player> roster) {
        if (RollbackDomain.active() || RollbackClock.active()) throw new IllegalStateException("Restore participant registries outside replay");
        validate(roster);
        for (var entry : falling.entrySet()) {
            var previous = TempFallingBlock.instances.get(entry.getKey());
            if (previous != null && previous != entry.getValue()) throw new IllegalStateException("Temporary falling block already has a live owner");
        }
        if (!stands.isEmpty()) {
            var claims = new HashMap<UUID, TempArmorStand>();
            for (var stand : stands) claims.put(stand.getArmorStand().getUniqueId(), stand);
            for (var live : TempArmorStand.getTempStands()) {
                var claimant = claims.get(live.getArmorStand().getUniqueId());
                if (claimant != null && claimant != live) throw new IllegalStateException("Temporary armor stand already has a live owner");
            }
        }
        restoreActorMetadata();
        restoreParticipantMetadata(roster);
        if (addonActors != null) addonActors.merge();
        statuses.merge(roster);
        falling.values().forEach(TempFallingBlock::restoreMetadata);
        stands.forEach(TempArmorStand::restoreMetadata);
        TempFallingBlock.instances.putAll(falling);
        TempArmorStand.getTempStands().addAll(stands);
        for (var entry : roster.entrySet()) {
            Catapult.restoreRollbackLanding(entry.getKey(), landings.contains(entry.getKey()));
            BlockSource.restoreRollbackSources(entry.getValue(), sources.get(entry.getValue()));
        }
    }
}
