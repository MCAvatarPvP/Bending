package com.projectkorra.projectkorra.prediction.rollback;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;

/** Stock item physics and inventory pickup with event and removal output owned by the duel. */
final class PaperRollbackItemEntity extends ItemEntity {
    private final PaperRollbackNativeScene scene;
    PaperRollbackItemEntity(PaperRollbackNativeScene scene) {
        super(EntityType.ITEM, scene.world().world());
        this.scene = scene;
    }
    @Override public void tick() { scene.itemLifecycle().tick(this); }
    @Override public void playerTouch(Player player) { scene.itemLifecycle().touch(this, player); }
}
