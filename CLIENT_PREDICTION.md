# Exact Fabric client prediction

This fork uses an authoritative-Paper-server, predicted-Fabric-client model. A compatible
Fabric client executes the **same ProjectKorra ability classes** as the Paper plugin;
it does not draw a generic replacement projectile. Constructors and `progress()`
therefore produce the same particles, sounds, source selection, temporary
blocks, falling blocks, display entities, ray traces, and velocity calls that
the ability produces in local play.

## Flow

1. The Fabric client advertises protocol 21 over vanilla `projectkorra:*` custom
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
5. Acceptance keeps the local simulation at that same execution pose. Rejection ends the
   predicted action and lets its local ability lifecycle clean up blocks, spawned
   visual entities, and still-current velocity changes.

## Why effects do not play twice

- During a predicted server ability tick, server particles and sounds are sent
  normally to every viewer except the initiating compatible client. That client
  already rendered the exact effect locally.
- Spawned item/armor-stand visuals, block displays, arrows, snowballs, fireballs,
  shulker bullets, area clouds, and scooter slimes begin
  as real client entities. When Paper's later spawn arrives, its server entity
  ID is aliased to that existing entity; the duplicate spawn and lagging
  movement packets are suppressed until Paper removes it.
- TempFallingBlocks use an owner-only receipt containing the exact action,
  spawn ordinal, ability, owner, and authoritative entity ID. Only the caster's
  matching predicted duplicate may be consumed; every other player's falling
  block always follows the normal vanilla spawn path.
- Ordinary predicted block mutations change the real `ClientWorld` state and use
  the normal bounded authority ledger. A common `TempBlock` is different: its world
  write is explicitly scoped and goes directly to `ClientWorld`, so it never enters
  the generic receipt, timeout, correction, or rollback path. Water and fluids still
  use real client block behavior rather than display-entity overlays.
- Temporary blocks are transactional stacks with stable layer IDs and monotonically
  increasing revisions. The registry is advanced before the physical world write,
  and the same revision is published before that write can emit a vanilla packet.
  A leaked vanilla packet is transport noise; it cannot audit, discard, or revert a
  live client TempBlock.
- The initiating client hides every physical server block update at an owned
  coordinate for the complete CREATE-to-REVERT lifecycle, independent of material,
  fluid level, neighbor shape, packet duplication, or arrival time. PacketEvents
  removes owned entries before delivery when available. Fabric enforces the same
  coordinate fence, including mixed section updates, when PacketEvents is absent.
  Unrelated entries in a mixed packet remain authoritative.
- Chunk snapshots are patched to the owner-visible state on Paper and reapply the
  merged local/remote layer view on Fabric. Join/re-handshake snapshots arm the coordinate fence;
  if no local layer exists because the physical block predates the client session,
  Fabric performs a one-time concealment to the server-computed underlying view.
  It never overwrites, retires, or rewinds a live client TempBlock.
- Apart from late-session concealment, revealing a server-computed underlay after the
  local stack has ended, and an explicit `DISCARD` authority handoff, lifecycle metadata
  is bookkeeping: it opens and closes coordinate fences and cannot manufacture vanilla
  receipts. A buried-layer reveal waits while any local TempBlock remains. An authority
  handoff discards local registry entries without invoking reverts or callbacks before
  showing the supplied real state.
  There are no TempBlock negative receipts,
  material-match confirmations, disagreement timeouts, rollback baselines, or desync
  markers. The server still restores its physical gameplay block when a layer expires,
  but that physical write is hidden from the owner; the client's own ordered lifecycle
  remains the visual answer.
- Per-session layer tracking guarantees that every delivered CREATE receives its
  REVERT even after leaving view distance. Stale or duplicate revisions are ignored,
  and a reverted layer cannot be resurrected by delayed metadata.
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
- PacketEvents is optional but recommended. When installed, all physical updates at
  an owned TempBlock coordinate are hidden for that layer's lifecycle, eliminating
  fluid/neighbor variants and the delayed one-frame flash. Unrelated coordinates in
  the same packet are preserved.
- The server runs Paper and installs `ProjectKorra-1.10.10-bukkit.jar` from this fork.
- Predicting players install `ProjectKorra-1.10.10-fabric.jar` in their Fabric client.
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
| Blocks | water/ice streams, RaiseEarth, Surge/Torrent, lava, overlapping temp blocks, rejection cleanup |
| Hits | stationary/moving players, edge-of-range, occlusion, 12+ tick latency, duplicate claims |
| State | bind/element changes, cooldown rejection, reconnect, config reload, missing client addon |
| Compatibility | Paper + modded Fabric initiator, unmodded initiator, mixed viewers, Paper restart/reload |

For production, also run the normal server movement/anti-cheat suite. Client
movement begins immediately by design, but the server remains responsible for
deciding whether received player movement is legal.
