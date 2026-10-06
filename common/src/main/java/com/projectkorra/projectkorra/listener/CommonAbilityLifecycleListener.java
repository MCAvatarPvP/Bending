package com.projectkorra.projectkorra.listener;

import com.projectkorra.projectkorra.*;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.event.*;
import com.projectkorra.projectkorra.platform.mc.ChatColor;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.event.EventHandler;
import com.projectkorra.projectkorra.platform.mc.event.EventPriority;
import com.projectkorra.projectkorra.util.Statistic;
import com.projectkorra.projectkorra.util.StatisticsMethods;
import java.util.Objects;
import java.util.UUID;

/** Gameplay stays in the shared graph; external effects use a locally bound destination. */
public final class CommonAbilityLifecycleListener {
    public sealed interface Effect permits Board, Death, ConsoleCommand { }
    public record Board(UUID player, String ability, int slot, boolean allSlots, boolean delayed) implements Effect {
        public Board { Objects.requireNonNull(player); Objects.requireNonNull(ability); }
    }
    public record Death(UUID entity, String ability, boolean player, String message) implements Effect {
        public Death { Objects.requireNonNull(entity); Objects.requireNonNull(ability); }
    }
    public record ConsoleCommand(String command) implements Effect {
        public ConsoleCommand { Objects.requireNonNull(command); }
    }
    /** Live adapters publish immediately; private sessions must bind their provisional output destination. */
    public interface Effects { void emit(Effect effect); }
    private final Effects effects;
    public CommonAbilityLifecycleListener(Effects effects) { this.effects = Objects.requireNonNull(effects); }

    public com.projectkorra.projectkorra.prediction.rollback.RollbackGraphCodec.Binding rollbackEffectsBinding() {
        return new com.projectkorra.projectkorra.prediction.rollback.RollbackGraphCodec.Binding(
                "core/ability-lifecycle-effects", Effects.class, effects);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onElementChange(PlayerChangeElementEvent event) {
        var offline = event.getTarget();
        if (!offline.isOnline()) return;
        var player = offline.getPlayer();
        var bending = BendingPlayer.getBendingPlayer(player);
        if (ConfigManager.languageConfig.get().getBoolean("Chat.Enable")) {
            if (bending == null) return;
            String prefix = bending.getElements().size() > 1 ? Element.AVATAR.getPrefix()
                    : event.getElement() != null ? event.getElement().getPrefix()
                    : ChatColor.WHITE + ChatColor.translateAlternateColorCodes('&',
                            ConfigManager.languageConfig.get().getString("Chat.Prefixes.Nonbender")) + " ";
            player.setDisplayName(player.getName());
            player.setDisplayName(prefix + ChatColor.RESET + player.getDisplayName());
        }
        elementChanged(player);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBendingSubElementChange(PlayerChangeSubElementEvent event) {
        if (!event.isTargetOnline()) return;
        var player = event.getTarget().getPlayer();
        if (BendingPlayer.getBendingPlayer(player) != null) elementChanged(player);
    }
    private void elementChanged(Player player) {
        CommonPlayerListenerCore.handleElementChanged(player, false);
        effects.emit(new Board(player.getUniqueId(), "", 0, true, false));
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBindChange(PlayerBindChangeEvent event) {
        if (!event.isOnline()) return;
        var player = event.getPlayer().getPlayer();
        if (player == null) return;
        effects.emit(new Board(player.getUniqueId(), event.isMultiAbility() ? "" : event.isBinding() ? event.getAbility() : "",
                event.getSlot(), event.isMultiAbility(), event.isMultiAbility()));
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerStanceChange(PlayerStanceChangeEvent event) {
        var player = event.getPlayer();
        if (player == null) return;
        if (!event.getOldStance().isEmpty()) effects.emit(new Board(player.getUniqueId(), event.getOldStance(), 0, false, false));
        if (!event.getNewStance().isEmpty()) effects.emit(new Board(player.getUniqueId(), event.getNewStance(), 0, false, false));
    }
    @EventHandler
    public void onAbilityStart(AbilityStartEvent event) {
        var player = event.getAbility().getPlayer();
        if (!player.hasPermission("bending.funny.abilstart")) return;
        String command = ConfigManager.getConfig().getString("Properties.FunnyCMD").replace("{player}", player.getName());
        if (command.isEmpty()) return;
        effects.emit(new ConsoleCommand(command));
        event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.NORMAL)
    public void onEntityBendingDeath(EntityBendingDeathEvent event) {
        var ability = event.getAbility();
        boolean player = event.getEntity() instanceof Player;
        String message = null;
        if (player && ConfigManager.languageConfig.get().getBoolean("DeathMessages.Enabled")) {
            if (ability == null) return;
            message = ability.getElement().getColor() + ability.getName();
        }
        if (ability != null) effects.emit(new Death(event.getEntity().getUniqueId(), ability.getName(), player, message));
        if (event.getAttacker() != null && ProjectKorra.isStatisticsEnabled()) {
            if (player) StatisticsMethods.addStatisticAbility(event.getAttacker().getUniqueId(),
                    CoreAbility.getAbility(ability.getName()), Statistic.PLAYER_KILLS, 1);
            StatisticsMethods.addStatisticAbility(event.getAttacker().getUniqueId(),
                    CoreAbility.getAbility(ability.getName()), Statistic.TOTAL_KILLS, 1);
        }
    }
}
