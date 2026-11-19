package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.earthbending.Catapult;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.BlockSource;
import com.projectkorra.projectkorra.util.BlockSource.BlockSourceType;
import com.projectkorra.projectkorra.util.BlockSourceInformation;
import com.projectkorra.projectkorra.util.ClickType;
import java.util.*;

/** Per-participant delayed Catapult landings and block-source selections. */
public final class RollbackAbilityGlobals {
    private final Set<UUID> landings = new LinkedHashSet<>();
    private final Map<Player, HashMap<BlockSourceType, HashMap<ClickType, BlockSourceInformation>>> sources = new LinkedHashMap<>();

    public static RollbackAbilityGlobals capture(Collection<Player> participants) {
        if (RollbackDomain.active()) throw new IllegalStateException("Capture participant registries on the live server");
        var data = new RollbackAbilityGlobals();
        for (Player player : participants) {
            if (Catapult.isAwaitingLanding(player)) data.landings.add(player.getUniqueId());
            var owned = BlockSource.captureRollbackSources(player);
            if (owned != null) data.sources.put(player, owned);
        }
        return data;
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

    void merge(Map<UUID, Player> roster) {
        if (RollbackDomain.active() || RollbackClock.active()) throw new IllegalStateException("Restore participant registries outside replay");
        validate(roster);
        for (var entry : roster.entrySet()) {
            Catapult.restoreRollbackLanding(entry.getKey(), landings.contains(entry.getKey()));
            BlockSource.restoreRollbackSources(entry.getValue(), sources.get(entry.getValue()));
        }
    }
}
