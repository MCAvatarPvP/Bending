package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackRosterViews;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeItems;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld;
import net.minecraft.item.ItemStack;

import java.util.*;

/** Ability views over the exact Fabric bodies imported from the authoritative roster. */
public final class FabricRollbackRosterViews {
    private FabricRollbackRosterViews() { }
    public static RollbackRosterViews bind(RollbackWorld logical, FabricRollbackRoster roster, Set<UUID> expected,
            RollbackNativeItems<ItemStack> items, Map<UUID, RollbackRosterViews.Services> services) {
        Objects.requireNonNull(roster); Objects.requireNonNull(items).requireOwnerThread();
        var players = roster.players(); var world = roster.world();
        if (!players.keySet().equals(expected) || !services.keySet().equals(expected)) throw new IllegalArgumentException("Incomplete private Fabric roster");
        var bindings = new TreeMap<UUID, RollbackRosterViews.Native>();
        for (var entry : players.entrySet()) {
            var state = entry.getValue(); var body = state.ownedPlayer();
            if (!body.getUuid().equals(entry.getKey()) || body.getEntityWorld() != world.world() || !world.ownsPlayer(body)) {
                throw new IllegalArgumentException("Foreign native Fabric player");
            }
            var identity = roster.identity(entry.getKey()); var profile = services.get(entry.getKey()).profile();
            if (!profile.gameMode().equals(identity.mode().name()) || !profile.mainHand().equals(identity.client().mainHand().name())) {
                throw new IllegalArgumentException("Ability profile differs from authoritative player");
            }
            bindings.put(entry.getKey(), new RollbackRosterViews.Native(state, FabricRollbackInventory.bind(state, items)));
        }
        return RollbackRosterViews.bind(logical, expected, bindings, services);
    }
}
