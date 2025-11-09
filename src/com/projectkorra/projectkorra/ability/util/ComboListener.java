package com.projectkorra.projectkorra.ability.util;

import com.projectkorra.projectkorra.util.ClickType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;

public class ComboListener implements Listener {

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPlayerInteraction(final PlayerInteractEvent event) {
		final Player player = event.getPlayer();
		ComboManager.addComboAbilityIfValid(player, ClickType.RIGHT_CLICK_BLOCK);
		ComboManager.addComboAbilityIfValid(player, ClickType.RIGHT_CLICK);
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPlayerInteractEntity(final PlayerInteractAtEntityEvent event) {
		ComboManager.addComboAbilityIfValid(event.getPlayer(), ClickType.RIGHT_CLICK_ENTITY);
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPlayerSneak(final PlayerToggleSneakEvent event) {
		final Player player = event.getPlayer();
		ComboManager.addComboAbilityIfValid(player, ClickType.SHIFT_DOWN);
		ComboManager.addComboAbilityIfValid(player, ClickType.SHIFT_UP);
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPlayerSwapItems(final PlayerSwapHandItemsEvent event) {
		ComboManager.addComboAbilityIfValid(event.getPlayer(), ClickType.OFFHAND_TRIGGER);
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPlayerSwing(final PlayerAnimationEvent event) {
		final Player player = event.getPlayer();
		ComboManager.addComboAbilityIfValid(player, ClickType.LEFT_CLICK_ENTITY);
		ComboManager.addComboAbilityIfValid(player, ClickType.LEFT_CLICK);
	}

}