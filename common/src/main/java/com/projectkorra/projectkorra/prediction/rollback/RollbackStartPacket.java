package com.projectkorra.projectkorra.prediction.rollback;

import java.io.*;
import java.util.Objects;
import java.util.UUID;

/** Small, direction-checked clock/commit messages over an already prepared snapshot. */
public final class RollbackStartPacket {
    public static final String CLIENT_CHANNEL = "projectkorra:rollback_start_c2s";
    public static final String SERVER_CHANNEL = "projectkorra:rollback_start_s2c";
    public static final int MAXIMUM_BYTES = 61;
    public enum Direction { CLIENT_TO_SERVER, SERVER_TO_CLIENT }
    public enum AbortReason { OPT_OUT, DISCONNECTED, INCOMPATIBLE, TIMEOUT, STATE_CHANGED, SERVER_FAILURE }
    public sealed interface Message permits RollbackStartNegotiation.Probe, RollbackStartNegotiation.ClockReply,
            RollbackStartNegotiation.Schedule, RollbackStartNegotiation.Scheduled, Commit, Abort {
        UUID session();
        UUID challenge();
    }
    public record Commit(RollbackStartNegotiation.Schedule schedule) implements Message {
        public Commit { Objects.requireNonNull(schedule); }
        @Override public UUID session() { return schedule.session(); }
        @Override public UUID challenge() { return schedule.challenge(); }
    }
    public record Abort(UUID session, UUID challenge, AbortReason reason) implements Message {
        public Abort { Objects.requireNonNull(session); Objects.requireNonNull(challenge); Objects.requireNonNull(reason); }
    }
    private RollbackStartPacket() { }

    public static void requireDirection(Message message, Direction direction) {
        Objects.requireNonNull(message); Objects.requireNonNull(direction);
        boolean allowed = message instanceof Abort || (direction == Direction.CLIENT_TO_SERVER
                ? message instanceof RollbackStartNegotiation.ClockReply || message instanceof RollbackStartNegotiation.Scheduled
                : message instanceof RollbackStartNegotiation.Probe || message instanceof RollbackStartNegotiation.Schedule || message instanceof Commit);
        if (!allowed) throw new IllegalArgumentException("Rollback start message has the wrong direction");
    }

    public static byte[] encode(Message message, Direction direction) {
        requireDirection(message, direction);
        try {
            var bytes = new ByteArrayOutputStream(MAXIMUM_BYTES);
            var output = new DataOutputStream(bytes);
            output.writeInt(RollbackStartNegotiation.VERSION);
            int kind = switch (message) {
                case RollbackStartNegotiation.Probe ignored -> 0;
                case RollbackStartNegotiation.ClockReply ignored -> 1;
                case RollbackStartNegotiation.Schedule ignored -> 2;
                case RollbackStartNegotiation.Scheduled ignored -> 3;
                case Commit ignored -> 4;
                case Abort ignored -> 5;
            };
            output.writeByte(kind);
            writeUuid(output, message.session()); writeUuid(output, message.challenge());
            switch (message) {
                case RollbackStartNegotiation.Probe ignored -> { }
                case RollbackStartNegotiation.ClockReply reply -> output.writeLong(reply.clientTick());
                case RollbackStartNegotiation.Schedule schedule -> writeSchedule(output, schedule);
                case RollbackStartNegotiation.Scheduled scheduled -> {
                    writeSchedule(output, scheduled.schedule()); output.writeLong(scheduled.receivedAtClientTick());
                }
                case Commit commit -> writeSchedule(output, commit.schedule());
                case Abort abort -> output.writeByte(abort.reason().ordinal());
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }

    public static Message decode(byte[] bytes, Direction direction) {
        Objects.requireNonNull(bytes);
        if (bytes.length > MAXIMUM_BYTES) throw new IllegalArgumentException("Rollback start byte budget");
        try {
            var input = new DataInputStream(new ByteArrayInputStream(bytes));
            if (input.readInt() != RollbackStartNegotiation.VERSION) throw new IllegalArgumentException("Unsupported rollback start version");
            int kind = input.readUnsignedByte();
            UUID session = new UUID(input.readLong(), input.readLong()), challenge = new UUID(input.readLong(), input.readLong());
            Message message = switch (kind) {
                case 0 -> new RollbackStartNegotiation.Probe(session, challenge);
                case 1 -> new RollbackStartNegotiation.ClockReply(session, challenge, input.readLong());
                case 2 -> readSchedule(input, session, challenge);
                case 3 -> new RollbackStartNegotiation.Scheduled(readSchedule(input, session, challenge), input.readLong());
                case 4 -> new Commit(readSchedule(input, session, challenge));
                case 5 -> {
                    int reason = input.readUnsignedByte();
                    if (reason >= AbortReason.values().length) throw new IllegalArgumentException("Unknown start abort reason");
                    yield new Abort(session, challenge, AbortReason.values()[reason]);
                }
                default -> throw new IllegalArgumentException("Unknown rollback start message");
            };
            requireDirection(message, direction);
            if (input.available() != 0) throw new IllegalArgumentException("Trailing rollback start data");
            return message;
        } catch (IOException truncated) { throw new IllegalArgumentException("Truncated rollback start message", truncated); }
    }
    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits()); output.writeLong(value.getLeastSignificantBits());
    }
    private static void writeSchedule(DataOutputStream output, RollbackStartNegotiation.Schedule value) throws IOException {
        output.writeLong(value.serverTick()); output.writeLong(value.clientTick());
    }
    private static RollbackStartNegotiation.Schedule readSchedule(DataInputStream input, UUID session, UUID challenge) throws IOException {
        return new RollbackStartNegotiation.Schedule(session, challenge, input.readLong(), input.readLong());
    }
}
