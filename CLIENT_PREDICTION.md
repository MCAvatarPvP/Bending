# Exact Fabric client prediction

This fork uses an authoritative-Paper-server, predicted-Fabric-client model. A compatible
Fabric client executes the **same ProjectKorra ability classes** as the Paper plugin;
it does not draw a generic replacement projectile. Constructors and `progress()`
therefore produce the same particles, sounds, source selection, temporary
blocks, falling blocks, display entities, ray traces, and velocity calls that
the ability produces in local play.

## Flow

1. The Fabric client advertises protocol 18 over vanilla `projectkorra:*` custom
   payload channels. Paper receives those channels through its plugin messaging
   API—Fabric Loader is not installed on the server. The server returns a random per-connection
   session ID, public configuration, ability validation metadata, binds,
   cooldown expiries, elements, and subelements.
2. Database/storage paths and secret-like keys are filtered before encoding.
   Config values are applied in memory and are never written into the client's
   normal server config. Public config sets larger than one Paper plugin message
   are sent as ordered chunks instead of being truncated.
3. A supported input is executed locally in the input frame, then sent with a
   monotonic sequence number. The normal vanilla input is still sent and the
   server consumes it as a duplicate.
4. The server validates the input eye pose against its player, then runs the real
   ability under that immutable input-time position and view. It enforces its own
   cooldown and source rules and replies with acceptance, the pose it executed,
   and cooldown expiry.
5. Acceptance keeps the local simulation at that same execution pose. Rejection removes the
   predicted ability and rolls back its blocks, spawned visual entities, and
   still-current velocity changes.

## Why effects do not play twice

- During a predicted server ability tick, server particles and sounds are sent
  normally to every viewer except the initiating compatible client. That client
  already rendered the exact effect locally.
- Spawned falling blocks, item/armor-stand visuals, block displays, arrows,
  snowballs, fireballs, shulker bullets, area clouds, and scooter slimes begin
  as real client entities. When Paper's later spawn arrives, its server entity
  ID is aliased to that existing entity; the duplicate spawn and lagging
  movement packets are suppressed until Paper removes it.
- A client block mutation changes the real `ClientWorld` block state. When the
  server later sends the same vanilla state, it confirms the ledger entry rather
  than creating a display-entity overlay. Water and other fluid states therefore
  use their real client fluid/block behavior.
- Temporary blocks are ordered owner layers with stable layer IDs and revisions.
  The owner sees their locally predicted layer, or the first non-owned layer/original
  block beneath it; the physical server TempBlock is not exposed to that owner.
  With PacketEvents, a receipt is armed before the physical world write and that
  exact owner update is suppressed. CREATE receipts remain active until their exact
  layer REVERT, covering duplicate fluid and neighbor packets from one world write;
  REVERT receipts retain only a short duplicate tail. Mixed packets keep every unrelated entry, and
  Fabric is explicitly told that no vanilla receipt will arrive. Without PacketEvents,
  Fabric performs bounded receipt-scoped suppression after delivery.
  A predicted CREATE is confirmed only when its action, coordinate, and complete
  block state all match. TempBlock lifecycle history is retained separately from
  disposable vanilla-packet echoes, so ordinary authority cannot erase prediction
  proof before delayed CREATE metadata arrives. This prevents predicted PhaseChange
  ICE from being misclassified as hidden and then "corrected" to underlying WATER.
  A suppressed REVERT is reconciled directly from ordered lifecycle metadata;
  a newer same-action CREATE is preserved only when it follows the exact confirmed
  CREATE/revert lifecycle at that coordinate. A delayed server CREATE also cannot
  repaint a provably newer state from the same action, which keeps moving WaterFlow,
  WaterSpoutWave, and EarthSmash lifecycles client-owned. That permission is scoped
  to the exact action and is cleared when a later action touches the coordinate, so
  confirmed PhaseChange ICE cannot lend its authority to a later predicted WATER.
  Chunk snapshots
  restore the resolved server-layer view, never an unrelated stale local mutation.
  When ordinary block authority wins, Fabric discards the corresponding local
  TempBlock stack without running its delayed revert or attachment callbacks.
  Metadata-only changes and join snapshots cannot create phantom receipts.
  Ordinary block authority and unrelated mixed chunk-delta entries remain
  authoritative. Ordered predicted entries inside a mixed delta are restored
  after vanilla applies the unrelated entries, so an older EarthBlast step
  cannot briefly repaint over the client's newer step.
  Per-session layer tracking guarantees that every delivered CREATE receives its
  REVERT even after leaving view distance. Server-absent client trail blocks use
  the measured input round-trip plus a jitter margin as a negative-receipt deadline,
  instead of remaining for an unconditional two seconds. Unresolved disagreement is
  forced back to the latest owner-visible server state at that deadline. Physical
  server TempBlock confirmation is tracked separately and can never replace that
  rollback baseline with temporary ICE, WATER, or EARTH. During the
  bounded window, a translucent amber block marker exposes the disagreement.
- The server binds an input action to each TempBlock layer at CREATE and retains
  that binding through REVERT. Reusing a long-lived ability instance cannot relabel
  older PhaseChange ICE as the newer input. On Fabric, a live local TempBlock owned
  by that exact action remains client-owned even if its source material representation
  differs, preventing catch-up metadata from deleting EarthSmash or WaterFlow.
- Newly-created authoritative abilities are backdated by the bounded input rewind.
  Charge and duration clocks therefore represent the same action age as the local
  prediction; EarthSmash release cannot fail merely because its server instance
  started one network trip later. EarthSmash grab, shoot, flight, sneak and no-op
  bound inputs are classified as transitions on the existing instance even though
  its constructor does not start a new ability. Reconciliation never performs a
  global EarthSmash handoff; rejection rolls back only effects created by that
  input, and an authoritative removal targets the matching instance/action.
- Gameplay-affecting random selection can use the shared action seed. PhaseChange
  melt uses this seed, so client and server traverse the same ice-block sequence
  even though the client begins that sequence earlier for prediction.
- Every common-model `setVelocity` call reaches the native client entity
  immediately. A later matching vanilla velocity packet is treated as an
  acknowledgement and is not applied a second time. A materially different
  vector remains authoritative and is applied normally.

## Hit registration and trust boundary

Client damage calls do not change health. They send a hit claim containing the
action sequence, client tick, target entity ID, and contact point. The server:

- rate-limits and session-validates claims;
- maps the client tick into a bounded 12-tick history window;
- checks finite coordinates, target state, rewound hit box, distance, and the
  real ability query's proximity to the claimed contact; and
- only exposes an accepted rewound player to the **real server ability's nearby
  entity query**. The real ability still decides whether and how much to damage.

Accepted claimed contact is provisional. Server-observed contacts without a
prediction claim use the same path, keyed by ability, target, and server tick;
their contact point is the defender's authoritative hit-box center at impact.
Damage, velocity, confirmed impact sounds, and server-only contact rewards such
as AirSwipe/AirSweep stamina regeneration are held in one decision until the defender
has received a minimum server-visible reaction budget plus their bounded round
trip and a small jitter allowance. Time between authoritative ability
acceptance and contact is deducted, so an already-telegraphed projectile gains
little or no additional delay while an unseen instant hit receives the full
budget. At the deadline the server commits every bundled effect, in its original
order, only when the claimed or server-observed contact is still inside the defender's
authoritative hit box. Moving the hit box clear drops every effect; pressing an
unrelated ability does not.

The defaults are under `Properties.Prediction.Reaction`: minimum visible time
is 200 ms, compensation is capped at 200 ms, jitter allowance is 25 ms, and
contact tolerance is 0.2 blocks.
This applies whether the attacker and defender are modded or unmodded. Direct
addon damage, direct `Entity#setVelocity` calls, potion effects, and custom
addon state are not provisional unless they use the common ProjectKorra effect
paths; bundled combat abilities are routed through those paths.

The server never accepts client health, cooldown, block, element, bind, source,
or final origin state as authority. The input pose is bounded against the native
player position, then supplied only as the immutable context in which the server
runs its own source-selection code.

## Addons and compatibility

The bundled JedCore, ProjectAddons, ChiRework, and Hyperion sources are started
on both logical sides and run their actual classes. Every `Config` created by a
bundled addon is automatically registered for public prediction sync. An addon
may also register a tighter server hit envelope through
`PredictionSnapshotBuilder.registerProfile`.

Projectile-backed addons must expose their projectile type on the prediction
platform and cannot rely exclusively on server-only projectile callbacks.
ChiRework RopeDart launches a tracked predicted arrow and performs target
attachment through a swept contact test in the ability runtime.

A third-party server-only addon cannot be predicted exactly because its code is
absent from the client. The client detects that the bound ability is not in its
local registry and uses the unchanged vanilla/server path. To gain zero-delay
prediction, ship the identical prediction-safe addon code on both sides. Addon
code must keep permanent world changes, inventory changes, commands, database
writes, and external I/O out of client execution; the client adapters already
make damage server-only.

## Compatibility and deployment

- Minecraft: 1.21.11
- Java: 21
- PacketEvents is optional but recommended. When installed, owned TempBlock packets
  are matched by coordinate and complete block state before network delivery,
  eliminating the delayed one-frame flash. Unrelated authority is never suppressed.
- The server runs Paper and installs `ProjectKorra-1.10.8-bukkit.jar` from this fork.
- Predicting players install `ProjectKorra-1.10.8-fabric.jar` in their Fabric client.
- The Paper server does **not** install Fabric Loader or the Fabric jar.
- Players without the mod use normal ProjectKorra input and normal latency.
- Fabric integrated-singleplayer prediction is disabled because the legacy common core
  uses one global `Platform` singleton for both logical sides. Singleplayer is
  already zero-latency; dedicated multiplayer is the prediction target.

## Verification matrix

Test with artificial 100/200/350 ms RTT and packet jitter:

| Area | Cases |
| --- | --- |
| Inputs | left/right click, block/entity right click, sneak press/release, combos, multiabilities |
| Movement | AirBlast self/target, AirScooter, spouts, jets, flight, knockback chains |
| Blocks | water/ice streams, RaiseEarth, Surge/Torrent, lava, overlapping temp blocks, rejection rollback |
| Hits | stationary/moving players, edge-of-range, occlusion, 12+ tick latency, duplicate claims |
| State | bind/element changes, cooldown rejection, reconnect, config reload, missing client addon |
| Compatibility | Paper + modded Fabric initiator, unmodded initiator, mixed viewers, Paper restart/reload |

For production, also run the normal server movement/anti-cheat suite. Client
movement begins immediately by design, but the server remains responsible for
deciding whether received player movement is legal.
