package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.PKListener;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeItems;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Creates the private authoritative simulation for a negotiated Neptune duel. */
public final class PaperRollbackDuelFactory implements PaperRollbackDuels.SimulationFactory {
    private final JavaPlugin plugin;
    private final PKListener listener;

    public PaperRollbackDuelFactory(JavaPlugin plugin, PKListener listener) {
        PaperRollbackLegacyBlocks.values(); // Load the bundled table at plugin enable.
        this.plugin = plugin;
        this.listener = listener;
    }

    @Override public PaperRollbackDuels.Simulation open(RollbackDuelRuntime setup, PaperRollbackDuelSeed captured,
            ServerLevel source, Map<UUID, PaperRollbackRosterSeed.Services> services) {
        var data = captured.portable();
        var output = new RollbackStepOutput<Object>();
        var round = new RollbackRound(data.session(), data.sides());
        var registries = source.getServer().registryAccess();
        var items = new RollbackNativeItems<>(new PaperRollbackItems(registries));
        var codec = new PaperRollbackItemCodec(registries);
        var actors = new PaperRollbackActors(data.session(), 16_384, items);
        var actions = new RollbackArenaActions(output, actors, new PaperRollbackTerrainTransfer(),
                stack -> codec.encode(items.nativeCopy(stack)));
        var events = new PaperRollbackArenaEvents(output, data.world().settings().policy());
        var binds = new PaperRollbackNativeScene.Bindings(new PaperRollbackBlockRules(items), items, actions, registries, events);
        long time = RollbackTiming.start();
        var scene = new PaperRollbackNativeScene(data.world(), captured.nativeRoster(), round, registries,
                source.enabledFeatures(), binds, services, data.epochNanos(), 65_536, 16_384, 500_000);
        time = RollbackTiming.finish(time, "scene.nativeImport");
        actors.bind(scene);
        events.bind(scene);
        var views = PaperRollbackRosterViews.bind(scene.logical(), scene.world(), round.participants(), scene.roster(),
                items, actions.players(data.access()));
        var execution = new PaperRollbackExecution<>(views.players().values(), new PaperRollbackPhases(scene, actors), output);
        var identity = new RollbackPlatform.Identity("bukkit", plugin.getDataFolder().toPath(), plugin.getLogger());
        var match = setup.open(data, round, views, identity, new PaperRollbackServerBlocks(), items,
                execution, List.of(scene, actors), false);
        RollbackTiming.finish(time, "scene.gameplayImport");
        var corrections = PaperRollbackCorrections.forMatch(data, scene, execution, round, actors.registry(),
                match.graph(), RollbackStateCorrection.MAXIMUM_BYTES, 20);
        var ids = new java.util.TreeMap<UUID, Integer>();
        var peers = new java.util.ArrayList<RollbackSession.Peer>();
        data.roster().players().forEach((id, row) -> {
            var player = source.getServer().getPlayerList().getPlayer(id);
            if (player == null || player.level() != source || player.getId() != row.identity().entityId())
                throw new IllegalStateException("Live presentation roster changed");
            ids.put(id, player.getId());
            peers.add(new RollbackSession.Peer(id, player.connection, 0));
        });
        var direct = PaperRollbackDirectPackets.bind(peers, source.getWorld().getUID(), ids, registries);
        var presentation = new PaperRollbackPresentation(source.getWorld(), plugin, listener, codec, direct, ids.keySet());
        return new PaperRollbackDuels.Simulation(scene, match.graph(), match.combat(),
                update -> update.finalizedEffects().forEach(effect -> presentation.publish(effect, update.confirmed().tick())), corrections);
    }
}
