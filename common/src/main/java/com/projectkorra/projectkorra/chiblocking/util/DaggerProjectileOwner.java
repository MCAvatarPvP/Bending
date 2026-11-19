package com.projectkorra.projectkorra.chiblocking.util;

import com.projectkorra.projectkorra.platform.mc.entity.Arrow;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import java.util.List;

/** Associates each thrown dagger with the ability instance and shooter that launched it. */
public interface DaggerProjectileOwner {
    String METADATA = "daggerthrow";
    Player getPlayer();
    List<Arrow> getArrows();
    void hitWithDagger(LivingEntity victim, Arrow arrow);

    static DaggerProjectileOwner owner(Arrow arrow) {
        if (!arrow.hasMetadata(METADATA) || !(arrow.getShooter() instanceof Player shooter)) return null;
        for (var entry : arrow.getMetadata(METADATA)) {
            if (entry.value() instanceof DaggerProjectileOwner owner && owner.getPlayer() != null
                    && owner.getPlayer().getUniqueId().equals(shooter.getUniqueId())
                    && owner.getArrows().stream().anyMatch(shot -> shot.getUniqueId().equals(arrow.getUniqueId()))) return owner;
        }
        return null;
    }
}
