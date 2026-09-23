package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeItems;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/** Ability views over the exact Paper bodies created by PaperRollbackRosterSeed. */
public final class PaperRollbackRosterViews {
    private PaperRollbackRosterViews() { }
    public static RollbackRosterViews bind(RollbackWorld logical, PaperRollbackWorldAccess world, Set<UUID> expected,
            Map<UUID, PaperRollbackNativePlayerState> players, RollbackNativeItems<ItemStack> items,
            Map<UUID, RollbackRosterViews.Services> services) {
        Objects.requireNonNull(world); Objects.requireNonNull(items).requireOwnerThread();
        if (!players.keySet().equals(expected) || !services.keySet().equals(expected) || world.playerCount() != expected.size()) throw new IllegalArgumentException("Incomplete private Paper roster");
        var bindings = new TreeMap<UUID, RollbackRosterViews.Native>();
        for (var entry : players.entrySet()) {
            var state = entry.getValue(); var body = state.ownedPlayer();
            if (!(body instanceof ServerPlayer player) || !body.getUUID().equals(entry.getKey()) || body.level() != world.world() || !world.ownsPlayer(body)) {
                throw new IllegalArgumentException("Foreign native Paper player");
            }
            var profile = services.get(entry.getKey()).profile();
            if (!profile.gameMode().equals(player.gameMode.getGameModeForPlayer().name()) || !profile.mainHand().equals(player.getMainArm().name())) {
                throw new IllegalArgumentException("Ability profile differs from imported native player");
            }
            bindings.put(entry.getKey(), new RollbackRosterViews.Native(state, PaperRollbackInventory.bind(state, items)));
        }
        return RollbackRosterViews.bind(logical, expected, bindings, services);
    }
}
