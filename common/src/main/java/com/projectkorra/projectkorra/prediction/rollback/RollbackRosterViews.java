package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.entity.EntityType;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard;
import com.projectkorra.projectkorra.prediction.rollback.world.*;

import java.util.*;

/** Connects an imported native cohort to the existing ability-facing player API. */
public final class RollbackRosterViews implements RollbackStateCell<Void> {
    /** Captured/imported player services, not defaults derived from the local client. */
    public record Services(RollbackPlayerState.Profile profile, Set<UUID> hidden, Scoreboard scoreboard,
                           RollbackEntityBody.Rules body, RollbackPlayerState.Rules player, RollbackPlayerAccess.Entry access) {
        /** For explicitly bound custom policy; a transferred duel uses the captured-access constructor below. */
        public Services(RollbackPlayerState.Profile profile, Set<UUID> hidden, Scoreboard scoreboard, RollbackEntityBody.Rules body, RollbackPlayerState.Rules player) {
            this(profile, hidden, scoreboard, body, player, null);
        }
        public Services(RollbackPlayerAccess.Entry access, Scoreboard scoreboard, RollbackEntityBody.Rules body, RollbackPlayerState.Rules player) {
            this(Objects.requireNonNull(access).profile(), access.hidden(), scoreboard, body, player, access);
        }
        public Services {
            Objects.requireNonNull(profile); hidden = Set.copyOf(hidden); Objects.requireNonNull(scoreboard);
            Objects.requireNonNull(body); Objects.requireNonNull(player);
            if (!profile.online()) throw new IllegalArgumentException("Duel participant is not online");
            if (access != null && (!profile.equals(access.profile()) || !hidden.equals(access.hidden()))) throw new IllegalArgumentException("Captured player access differs from view profile");
        }
    }
    /** Inventory must be a view over this exact owned player, never another captured copy. */
    public record Native(RollbackPlayerState.Source<?> source, RollbackInventory inventory) {
        public Native {
            Objects.requireNonNull(source); Objects.requireNonNull(inventory);
            if (inventory.nativeOwner() != source || source.identity().type() != EntityType.PLAYER) {
                throw new IllegalArgumentException("Native inventory/player ownership differs");
            }
        }
    }

    private final Thread thread = Thread.currentThread();
    private final RollbackWorld world;
    private final Map<UUID, RollbackPlayer> players;
    private final List<Object> rules;

    /** Native loader first verifies all bodies belong to its candidate private world. */
    public static RollbackRosterViews bind(RollbackWorld world, Set<UUID> expected, Map<UUID, Native> nativePlayers,
                                            Map<UUID, Services> services) {
        if (RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Bind the private roster before replay");
        Objects.requireNonNull(world); expected = Set.copyOf(expected);
        if (expected.size() < 2 || expected.size() > 128 || !expected.equals(nativePlayers.keySet()) || !expected.equals(services.keySet())) {
            throw new IllegalArgumentException("Views require the exact agreed duel roster");
        }
        if (!world.getPlayers().isEmpty()) throw new IllegalStateException("Private world already contains a player roster");
        var players = new TreeMap<UUID, RollbackPlayer>(); var rules = new ArrayList<Object>();
        var networkIds = new HashSet<Integer>(); var names = new HashSet<String>();
        for (UUID id : new TreeSet<>(expected)) {
            Native imported = Objects.requireNonNull(nativePlayers.get(id)); Services bindings = Objects.requireNonNull(services.get(id));
            var source = imported.source(); var identity = source.identity();
            if (!identity.uuid().equals(id) || !networkIds.add(identity.networkId()) || !names.add(identity.name().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Native roster identity differs");
            }
            var body = RollbackEntityBody.nativeBacked(identity, world, source, bindings.body());
            var living = RollbackLivingState.nativeBacked(body, new RollbackEquipment(imported.inventory()), source);
            var state = bindings.access() == null
                    ? RollbackPlayerState.nativeBacked(living, imported.inventory(), bindings.profile(), bindings.hidden(), bindings.scoreboard(), bindings.player(), source)
                    : RollbackPlayerState.nativeBacked(living, imported.inventory(), bindings.access(), bindings.scoreboard(), bindings.player(), source);
            players.put(id, new RollbackPlayer(state));
            rules.add(bindings.body()); rules.add(bindings.player());
        }
        // Late identity, scoreboard, membership or capacity failure cannot leave half a duel in the world.
        world.entities().addAll(players.values());
        return new RollbackRosterViews(world, players, rules);
    }
    private RollbackRosterViews(RollbackWorld world, Map<UUID, RollbackPlayer> players, List<Object> rules) {
        this.world = world; this.players = Collections.unmodifiableMap(new LinkedHashMap<>(players)); this.rules = List.copyOf(rules);
    }
    public Map<UUID, RollbackPlayer> players() { checkThread(); return players; }
    public RollbackWorld world() { checkThread(); return world; }

    /** Canonical private player/world bindings, including normalization for outgoing graph export. */
    public RollbackRosterBindings graphBindings() {
        checkThread(); return new RollbackRosterBindings(world, players.values());
    }

    /** Platform bindings for the portable bending graph; the source encoder uses the same IDs/contracts. */
    public List<RollbackGraphCodec.Binding> playerBindings() {
        checkThread();
        return players.entrySet().stream().map(entry -> new RollbackGraphCodec.Binding("player/" + entry.getKey(), Player.class, entry.getValue())).toList();
    }
    @Override public Void captureRollbackState() { checkThread(); return null; }
    @Override public void restoreRollbackState(Void state) { checkThread(); }
    @Override public Collection<?> rollbackReferences() {
        checkThread(); var roots = new ArrayList<Object>(rules); roots.add(world); roots.addAll(players.values()); return List.copyOf(roots);
    }
    private void checkThread() { if (thread != Thread.currentThread()) throw new IllegalStateException("Private roster views crossed threads"); }
}
