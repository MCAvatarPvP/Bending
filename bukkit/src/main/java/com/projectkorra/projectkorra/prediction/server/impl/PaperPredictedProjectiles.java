package com.projectkorra.projectkorra.prediction.server.impl;

import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.protocol.PaperPredictionProtocol;
import com.projectkorra.projectkorra.prediction.rollback.RollbackDomain;
import com.projectkorra.projectkorra.prediction.server.PaperPredictionServer;
import com.projectkorra.projectkorra.prediction.server.impl.PaperPredictionState.Action;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Matches exact prediction launches and impacts to their authoritative native actors. */
public final class PaperPredictedProjectiles {
    private final PaperPredictionServer server;
    public PaperPredictedProjectiles(PaperPredictionServer server) { this.server = server; }

    private final Map<org.bukkit.entity.Entity, ProjectileLaunch> predictedActors = new WeakHashMap<>();
    private ProjectileLaunch currentImpact;

    private static final class ProjectileLaunch {
        final UUID session;
        final Action action;
        final int ordinal;
        boolean createdCloud, sent, removalPending;
        ProjectileLaunch(UUID session, Action action, int ordinal) {
            this.session = session; this.action = action; this.ordinal = ordinal;
        }
    }

    /** Identifies this action's exact native projectile before ordinary entity tracking publishes it. */
    public void spawned(Player owner, org.bukkit.entity.Entity projectile) {
        if (RollbackDomain.active()) return;
        var ownerId = owner.getUniqueId();
        var session = server.sessions.get(ownerId);
        if (session == null || (session.capabilities & PaperPredictionState.CAPABILITY_EXACT) == 0
                || server.rollbackInputs.blocksLegacy(ownerId)
                || !owner.getListeningPluginChannels().contains(PaperPredictionProtocol.PROJECTILE_OWNER)) return;
        var ability = AbilityExecutionContext.current();
        boolean owned = ability != null && ability.getPlayer() != null && ownerId.equals(ability.getPlayer().getUniqueId());
        var action = server.currentInputAction(ownerId);
        if (action == null && owned) action = server.actionForEffect(ability);
        if (action == null || !action.locallyPredicted || !ownerId.equals(action.owner)
                || predictedActors.containsKey(projectile)) return;
        var launch = new ProjectileLaunch(session.session, action, ++action.projectileOrdinal);
        predictedActors.put(projectile, launch);
        if (currentImpact != null && currentImpact.action == action
                && projectile instanceof org.bukkit.entity.AreaEffectCloud && projectile.isValid()) currentImpact.createdCloud = true;
        server.send(owner, PaperPredictionProtocol.PROJECTILE_OWNER, PaperPredictionProtocol.projectileOwner(
                session.session, action.sequence, launch.ordinal, ownerId, projectile.getEntityId(),
                projectile.getUniqueId(), owned ? ability.getName() : action.ability, projectile.getType().name()));
    }

    public void impact(org.bukkit.entity.Projectile projectile, Runnable callback) {
        var launch = predictedActors.get(projectile);
        var action = launch == null ? null : launch.action;
        var session = action == null ? null : server.sessions.get(action.owner);
        if (session == null || session.actions.get(action.sequence) != action || server.rollbackInputs.blocksLegacy(action.owner)) {
            callback.run();
            return;
        }
        // MetalCable's projectile can be reassigned by a subsequent input before impact.
        for (var meta : projectile.getMetadata(me.moros.hyperion.methods.CoreMethods.CABLE_KEY)) {
            if (meta.value() instanceof me.moros.hyperion.abilities.earthbending.MetalCable cable) {
                var transitioned = server.actionForEffect(cable);
                if (transitioned != null && transitioned.owner.equals(action.owner)) action = transitioned;
                break;
            }
        }
        var previous = currentImpact;
        currentImpact = launch;
        try { PaperPredictionUtilities.runWithOwnerAndSequence(action.owner, action.sequence, callback); }
        finally { currentImpact = previous; }
    }

    public void finish(org.bukkit.entity.Snowball ball) {
        var launch = predictedActors.get(ball);
        if (launch == null || launch.sent) return;
        launch.sent = true;
        var session = server.sessions.get(launch.action.owner);
        var owner = Bukkit.getPlayer(launch.action.owner);
        if (session == null || !session.session.equals(launch.session) || owner == null
                || !owner.getListeningPluginChannels().contains(PaperPredictionProtocol.PROJECTILE_IMPACT)) return;
        server.send(owner, PaperPredictionProtocol.PROJECTILE_IMPACT, PaperPredictionProtocol.projectileImpact(
                launch.session, launch.action.sequence, launch.ordinal, launch.action.owner, ball.getEntityId(), ball.getUniqueId(), launch.createdCloud));
    }

    public void removed(org.bukkit.entity.Snowball ball) {
        var launch = predictedActors.get(ball);
        if (launch == null || launch.sent || launch.removalPending) return;
        launch.removalPending = true;
        // Removal may run synchronously inside the collision callback before it spawns its cloud.
        Bukkit.getScheduler().runTask(server.plugin, () -> finish(ball));
    }

}
