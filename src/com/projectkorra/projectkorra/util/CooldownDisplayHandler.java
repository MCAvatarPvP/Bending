package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.BendingPlayer;

public class CooldownDisplayHandler implements Runnable {

	@Override
	public void run() {
		for (BendingPlayer bPlayer : BendingPlayer.getPlayers().values()) {
			if (bPlayer.showPreviewOnCooldown()) {
				ChatUtil.displayMovePreview(bPlayer.getPlayer());
			}
		}
	}

}