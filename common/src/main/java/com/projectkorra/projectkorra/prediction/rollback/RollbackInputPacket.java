package com.projectkorra.projectkorra.prediction.rollback;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded client intent codec shared by both loaders. Carries no claimed player,
 * position, damage result or client-selected random seed. Channel registration and
 * the session-start handshake are supplied by the loaders separately.
 */
public record RollbackInputPacket(UUID session, long clientTick, RollbackMovementInput movement,
                                  boolean sprinting, List<Edge> actions) {
    public static final int VERSION = 4;
    public static final String CHANNEL = "projectkorra:rollback_input";
    public static final int MAXIMUM_BYTES = 47 + 18 * RollbackPlayerInput.MAXIMUM_ACTIONS;

    public record Edge(long sequence, RollbackInputActions.Kind kind, int slot, float yaw, float pitch) {
        public Edge {
            // The same shape validation as a simulation action, without accepting a wire seed.
            new RollbackInputActions.Action(sequence, 1, kind, slot);
            var look = new RollbackMovementInput(0, 0, false, yaw, pitch);
            yaw = look.yaw(); pitch = look.pitch();
        }
    }

    public RollbackInputPacket {
        Objects.requireNonNull(session, "session"); Objects.requireNonNull(movement, "movement");
        if (clientTick < 0) throw new IllegalArgumentException("Negative client tick");
        if (Objects.requireNonNull(actions, "actions").size() > RollbackPlayerInput.MAXIMUM_ACTIONS) throw new IllegalArgumentException("Action budget exceeded");
        actions = List.copyOf(actions);
        long previous = 0;
        for (var edge : actions) {
            if (edge.sequence() <= previous) throw new IllegalArgumentException("Action sequence order");
            previous = edge.sequence();
        }
    }

    /** The server assigns the session seed; both simulations derive identical per-action seeds. */
    public RollbackPlayerInput playerInput(UUID player, long sessionSeed) {
        Objects.requireNonNull(player, "player");
        return new RollbackPlayerInput(movement, sprinting, actions.stream().map(edge -> {
            long seed = sessionSeed ^ session.getMostSignificantBits() ^ Long.rotateLeft(session.getLeastSignificantBits(), 13)
                    ^ player.getMostSignificantBits() ^ Long.rotateLeft(player.getLeastSignificantBits(), 29)
                    ^ edge.sequence() * 0x9E3779B97F4A7C15L;
            seed = (seed ^ (seed >>> 30)) * 0xBF58476D1CE4E5B9L;
            seed = (seed ^ (seed >>> 27)) * 0x94D049BB133111EBL;
            seed = (seed ^ (seed >>> 31)) & Long.MAX_VALUE;
            return new RollbackPlayerInput.Edge(new RollbackInputActions.Action(edge.sequence(), seed == 0 ? 1 : seed, edge.kind(), edge.slot()), edge.yaw(), edge.pitch());
        }).toList());
    }

    public byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream(47 + 18 * actions.size());
            var output = new DataOutputStream(bytes);
            output.writeInt(VERSION); output.writeLong(session.getMostSignificantBits()); output.writeLong(session.getLeastSignificantBits());
            output.writeLong(clientTick);
            output.writeFloat(movement.strafe()); output.writeFloat(movement.forward()); output.writeBoolean(movement.jump());
            output.writeFloat(movement.yaw()); output.writeFloat(movement.pitch()); output.writeBoolean(sprinting);
            output.writeByte(actions.size());
            for (var edge : actions) {
                output.writeLong(edge.sequence()); output.writeByte(edge.kind().ordinal()); output.writeByte(edge.slot());
                output.writeFloat(edge.yaw()); output.writeFloat(edge.pitch());
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }

    public static RollbackInputPacket decode(byte[] data) {
        Objects.requireNonNull(data, "data");
        if (data.length > MAXIMUM_BYTES) throw new IllegalArgumentException("Input packet exceeds byte budget");
        try {
            var input = new DataInputStream(new ByteArrayInputStream(data));
            if (input.readInt() != VERSION) throw new IllegalArgumentException("Unsupported rollback input version");
            UUID session = new UUID(input.readLong(), input.readLong()); long tick = input.readLong();
            var movement = new RollbackMovementInput(input.readFloat(), input.readFloat(), bool(input), input.readFloat(), input.readFloat());
            boolean sprinting = bool(input);
            int count = input.readUnsignedByte();
            if (count > RollbackPlayerInput.MAXIMUM_ACTIONS) throw new IllegalArgumentException("Input action budget exceeded");
            var actions = new ArrayList<Edge>(count);
            var kinds = RollbackInputActions.Kind.values();
            for (int index = 0; index < count; index++) {
                long sequence = input.readLong(); int kind = input.readUnsignedByte();
                if (kind >= kinds.length) throw new IllegalArgumentException("Unknown input action");
                actions.add(new Edge(sequence, kinds[kind], input.readByte(), input.readFloat(), input.readFloat()));
            }
            if (input.available() != 0) throw new IllegalArgumentException("Trailing input data");
            return new RollbackInputPacket(session, tick, movement, sprinting, actions);
        } catch (IOException malformed) { throw new IllegalArgumentException("Truncated rollback input", malformed); }
    }

    private static boolean bool(DataInputStream input) throws IOException {
        int value = input.readUnsignedByte();
        if (value > 1) throw new IllegalArgumentException("Invalid input boolean");
        return value != 0;
    }
}
