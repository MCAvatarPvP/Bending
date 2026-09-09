package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.object.EarthCosmetic;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.metadata.FixedMetadataValue;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.block.TempFallingBlockSync;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class TempFallingBlock {
    public static ConcurrentHashMap<FallingBlock, TempFallingBlock> instances = new ConcurrentHashMap<>();

    private FallingBlock fallingblock;
    private CoreAbility ability;
    private long creation;
    private boolean expire;
    private Consumer<TempFallingBlock> onPlace;
    private final String metadataKey;

    public TempFallingBlock(Location location, BlockData data, Vector velocity, CoreAbility ability) {
        this(location, data, velocity, ability, false);
    }

    public TempFallingBlock(Location location, BlockData data, Vector velocity, CoreAbility ability, boolean expire) {
        BlockData blockData = data;
        EarthCosmetic cosmetic = ability.getBendingPlayer().getEarthCosmetic();
        if (EarthCosmetic.canReplace(cosmetic, data.getMaterial()) && ability.getElement() == Element.EARTH) {
            blockData = cosmetic.getMaterial().createBlockData();
        }
        final int predictionOrdinal = TempFallingBlockSync.prepare(ability, location, blockData);
        this.fallingblock = location.getWorld().spawnFallingBlock(location, blockData.clone());
        this.fallingblock.setVelocity(velocity);
        this.fallingblock.setDropItem(false);
        this.fallingblock.setHurtEntities(false);
        this.metadataKey = ability.getName().toLowerCase(java.util.Locale.ROOT);
        this.fallingblock.setMetadata(this.metadataKey, new FixedMetadataValue(ProjectKorra.plugin, this));
        this.ability = ability;
        this.creation = System.currentTimeMillis();
        this.expire = expire;
        instances.put(fallingblock, this);
        TempFallingBlockSync.publish(ability, fallingblock, predictionOrdinal);
    }

    public static void manage() {
        long time = System.currentTimeMillis();

        for (TempFallingBlock tfb : instances.values()) {
            FallingBlock fb = tfb.getFallingBlock();
            if (fb == null) {
                tfb.remove();
                continue;
            }

            if (!fb.isValid() || fb.isDead() || fb.isOnGround()) {
                try {
                    if (fb.isDead() || fb.isOnGround()) tfb.tryPlace();
                } finally {
                    tfb.remove();
                }
                continue;
            }

            if (tfb.canExpire() && time > tfb.getCreationTime() + 5000) {
                tfb.remove();
            } else if (time > tfb.getCreationTime() + 120000) { // Add a hard timeout for any abilities that misuse this.
                tfb.remove();
            }
        }
    }

    public static TempFallingBlock get(FallingBlock fallingblock) {
        if (isTempFallingBlock(fallingblock)) {
            return instances.get(fallingblock);
        }
        return null;
    }

    public static boolean isTempFallingBlock(FallingBlock fallingblock) {
        return instances.containsKey(fallingblock);
    }

    public static void removeFallingBlock(FallingBlock fallingblock) {
        final TempFallingBlock instance = instances.get(fallingblock);
        if (instance != null) instance.remove();
    }

    public static void removeAllFallingBlocks() {
        for (TempFallingBlock instance : List.copyOf(instances.values())) instance.remove();
    }

    /** Drops old-world wrappers after normal entity cleanup has been attempted. */
    public static void discardAll() {
        instances.clear();
    }

    public static List<TempFallingBlock> getFromAbility(CoreAbility ability) {
        List<TempFallingBlock> tfbs = new ArrayList<TempFallingBlock>();
        for (TempFallingBlock tfb : instances.values()) {
            if (tfb.getAbility().equals(ability)) {
                tfbs.add(tfb);
            }
        }
        return tfbs;
    }

    public void remove() {
        instances.remove(fallingblock, this);
        try {
            fallingblock.removeMetadata(this.metadataKey, ProjectKorra.plugin);
        } finally {
            this.onPlace = null;
            fallingblock.remove();
        }
    }

    public FallingBlock getFallingBlock() {
        return fallingblock;
    }

    public CoreAbility getAbility() {
        return ability;
    }

    public Material getMaterial() {
        return fallingblock.getBlockData().getMaterial();
    }

    public BlockData getMaterialData() {
        return fallingblock.getBlockData();
    }

    public BlockData getData() {
        return fallingblock.getBlockData();
    }

    public Location getLocation() {
        return fallingblock.getLocation();
    }

    public long getCreationTime() {
        return creation;
    }

    public boolean canExpire() {
        return expire;
    }

    public void tryPlace() {
        if (onPlace != null) {
            onPlace.accept(this);
        }
    }

    public Consumer<TempFallingBlock> getOnPlace() {
        return onPlace;
    }

    public void setOnPlace(Consumer<TempFallingBlock> onPlace) {
        this.onPlace = onPlace;
    }
}
