package hackathonpack;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.event.AbilityStartEvent;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.event.EventHandler;
import com.projectkorra.projectkorra.platform.mc.event.Listener;
import hackathonpack.air.AirSpray;

public final class HackathonPackListener implements Listener {
    @EventHandler
    public void onAbilityStart(final AbilityStartEvent event) {
        if (event.isCancelled() || event.getAbility() == null) {
            return;
        }

        final Player player = event.getAbility().getPlayer();
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (bPlayer == null) {
            return;
        }

        if (CoreAbility.hasAbility(player, AirSpray.class)) {
            final AirSpray airSpray = CoreAbility.getAbility(player, AirSpray.class);
            if (airSpray != null && airSpray.getTick() < airSpray.getMaximumTick()) {
                event.setCancelled(true);
            }
        }
    }
}
