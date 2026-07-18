package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockState;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

import java.util.UUID;

public class Information {

    private static int ID = Integer.MIN_VALUE;
    private final int id;
    private String string;
    private int integer;
    private long time;
    private double value;
    private byte data;

    private Block block;
    private BlockState state;
    private Location location;
    private Material type;
    private Player player;
    /** Causal identity retained by long-lived moved-earth registries. */
    private UUID predictionOwner;
    private String predictionAbility;
    private long predictionActionSequence;

    public Information() {
        this.id = ID++;
        if (ID >= Integer.MAX_VALUE) {
            ID = Integer.MIN_VALUE;
        }
    }

    public Block getBlock() {
        return this.block;
    }

    public void setBlock(final Block block) {
        this.block = block;
    }

    public byte getData() {
        return this.data;
    }

    public void setData(final byte data) {
        this.data = data;
    }

    public double getDouble() {
        return this.value;
    }

    public void setDouble(final double value) {
        this.value = value;
    }

    public int getID() {
        return this.id;
    }

    public int getInteger() {
        return this.integer;
    }

    public void setInteger(final int integer) {
        this.integer = integer;
    }

    public Location getLocation() {
        return this.location;
    }

    public void setLocation(final Location location) {
        this.location = location;
    }

    public Player getPlayer() {
        return this.player;
    }

    public void setPlayer(final Player player) {
        this.player = player;
    }

    public BlockState getState() {
        return this.state;
    }

    public void setState(final BlockState state) {
        this.state = state;
    }

    public String getString() {
        return this.string;
    }

    public void setString(final String string) {
        this.string = string;
    }

    public long getTime() {
        return this.time;
    }

    public void setTime(final long time) {
        this.time = time;
    }

    public Material getType() {
        return this.type;
    }

    public void setType(final Material type) {
        this.type = type;
    }

    public UUID getPredictionOwner() {
        return this.predictionOwner;
    }

    public void setPredictionOwner(final UUID predictionOwner) {
        this.predictionOwner = predictionOwner;
    }

    public String getPredictionAbility() {
        return this.predictionAbility;
    }

    public void setPredictionAbility(final String predictionAbility) {
        this.predictionAbility = predictionAbility;
    }

    public long getPredictionActionSequence() {
        return this.predictionActionSequence;
    }

    public void setPredictionActionSequence(final long predictionActionSequence) {
        this.predictionActionSequence = predictionActionSequence;
    }
}
