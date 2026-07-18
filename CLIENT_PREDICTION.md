# Exact Fabric client prediction

This fork uses an authoritative-Paper-server, predicted-Fabric-client model. A compatible
Fabric client executes the **same ProjectKorra ability classes** as the Paper plugin;
it does not draw a generic replacement projectile. Constructors and `progress()`
therefore produce the same particles, sounds, source selection, temporary
blocks, falling blocks, display entities, ray traces, and velocity calls that
the ability produces in local play.

## Flow

1. The Fabric client advertises protocol 39 over vanilla `projectkorra:*` custom
   payload channels. Paper receives those channels through its plugin messaging
   API—Fabric Loader is not installed on the server. The server returns a random per-connection
   session ID, public configuration, ability validation metadata, binds,
   cooldown expiries, elements, and subelements.
2. Database/storage paths and secret-like keys are filtered before encoding.
   Config values are applied in memory and are never written into the client's
   normal server config. Public config sets larger than one Paper plugin message
   are sent as ordered chunks instead of being truncated.
3. After loading the common runtime, the client sends one `ClientReady` session
   barrier containing its supported ability catalog. It sends no positive input,
   pose, timing, or action packet which can schedule or authorize a cast. The
   sole per-event exception is a negative `InputVeto` when the local common
   runtime rejected that native event on cooldown; it can only suppress the
   player's own ProjectKorra callback.
4. The client predicts immediately before sending the ordinary vanilla packet.
   Paper/Fabric then invoke the real common input handler from their native event:
   arm swing for left click, interact for right click, player-input/toggle for
   sneak, and the ordinary swap-hands packet for the offhand combo trigger.
   Selected-slot packets run the same multiability/addon decision locally and on
   Paper, while a rejected Paper slot correction is always allowed back through.
   Drop-item and right-click-block swing suppression follow the legacy Bukkit
   listener order exactly; suppressed animation callbacks still occupy the same
   native receipt position on both endpoints without executing bending logic.
   Paper fires `PlayerToggleSneakEvent` before applying the packet's new shift
   flag. Both legacy input branching and aimed calculations therefore use the
   pre-toggle sneak state and eye height during that callback. Paper then runs
   Bukkit's scheduler before its world/player tick updates the entity pose: the
   first ProjectKorra progress pass sees the new shift flag with the old eye
   height. Fabric keeps those two states separate and commits the new
   crouch/standing eye height only after that matching local progress pass.
5. Both endpoints count those native events from the ready barrier. Immediately
   before executing the server handler, the server sends a `NativeAction` receipt
   containing its event ordinal, kind, selected slot, logical ability, and native
   pose. A client action is confirmed only when ordinal, kind, slot, and ability
   are identical. The server always uses its own player state; client state is
   never installed into Bukkit/Fabric gameplay.
6. Per-input reconciliation is bookkeeping only. It cannot restore a block
   snapshot, shorten a client TempBlock lifecycle, or reconstruct/remove a
   persistent movement ability. WaterSpout, AirSpout, scooter, jet, and flight
   complete their local input/duration/collision/environment lifecycle immediately;
   Paper's sequence-fenced active-state set is diagnostic only. A local simulation
   exception has a separate partial-construction cleanup path.

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
  Paper entity may be consumed; if the exact local alias has already retired,
  the server duplicate remains hidden instead of becoming a stuttering fallback.
  Every other player's falling block follows the normal vanilla spawn path. A local duplicate is
  matched only when its encoded block state and exact fixed-point spawn
  coordinates also agree; after matching, Paper movement packets are aliases
  of the client-owned entity rather than a second, stuttering visual.
- Permanent non-earth writes (for example FireBlast destruction and HeatControl
  extinguish) remain server-visible only, while their common code receives a
  same-pass read-after-write simulation. Earth movement writes use exact causal
  action/ability/ordinal receipts, never coordinate proximity. Their causal
  identity is retained in `Information` after the creating ability ends, so
  `MOVED_EARTH` and `TEMP_AIR_LOCATIONS` restore through the same client-owned
  lifecycle instead of handing a delayed trail back to Paper. The receipt marks
  that moved-earth lifecycle explicitly, so even an unmatched Paper write stays
  hidden; unrelated permanent Earth edits still require a known local cause. A common
  `TempBlock` is explicitly scoped and writes directly to `ClientWorld` for its
  complete local lifecycle.
- Temporary blocks are transactional stacks with stable layer IDs and monotonically
  increasing revisions. The registry is advanced before the physical world write,
  and the same revision is published before that write can emit a vanilla packet.
  A leaked vanilla packet is transport noise; it cannot audit, discard, or revert a
  live client TempBlock.
- Each predicted TempBlock CREATE receives a causal identity: action sequence, effect
  ability name, and a monotonic creation ordinal local to that action. Process-local
  layer IDs and running ticks remain lifecycle/debug data; they are never used to guess
  a cross-process match.
- Paper explicitly attributes each predicted server layer to its owning action before the
  physical write. Fabric hides every layer carrying that authoritative local-prediction
  ownership for its complete lifecycle; concealment does not depend on fragile agreement in
  per-layer counts, ordinals, coordinates, or whether the client happened to create an
  immediate first-frame layer. The client TempBlocks (including their absence at Paper-only
  coordinates) remain the visual answer for every accepted supported action. Exact semantic
  pairing is retained for lifecycle bookkeeping, never as permission for a server ghost to
  render. Remote and genuinely server-only layers remain normally visible.
- A matched client TempBlock owns its complete visible lifetime. Reconciliation, authoritative
  ability removal, and delayed Paper CREATE/CLOSE metadata do not shorten it or restore a
  captured snapshot. There is no metadata-rejection rollback path.
- Vanilla block updates, section updates, and chunk snapshots rebase the captured underlay
  beneath client TempBlocks. Every overlapping layer shares that rebased stack base, so closing
  layers in any order cannot reveal an obsolete snapshot. Updates never discard a live layer.
  If an authoritative direct write
  supersedes a semantically matched layer at a shifted coordinate, the ordered pre-write
  DISCARD closes that exact client layer and reveals its captured/rebased underlay; no guessed
  permanent edit is painted at the client's coordinate.
- A locally completed short-lived layer is retained as a semantic tombstone only so delayed
  CREATE/CLOSE metadata can find the lifecycle it describes. Until paired, an ended layer has
  no drawing authority: it cannot intercept a vanilla update, survive a chunk snapshot, or
  repaint a coordinate when its retention window expires. For overlapping stacks, the final
  close revision—not the newest-created layer—selects the resulting state. Every close of an
  action-hidden Paper layer installs a one-packet, exact-state fence even when semantic ordinals
  diverged; the matching physical restore is suppressed, but an unrelated later write at the
  same coordinate is never swallowed.
- Mixed section updates and chunk snapshots reapply active client layers and every server
  coordinate authoritatively owned by the local predicted action. Paired closed lifecycles
  retain their final client underlay while Paper closes its delayed physical copy. Remote,
  server-only, and join-time layers remain visible through ordinary chunk authority.
- Server-only TempBlock metadata remains queryable even when Fabric has no local common-layer
  handle, which preserves cross-player water rules. PhaseChange delegates Torrent ice to
  `Torrent.thaw(...)` (and retains the corresponding Surge, WaterArms, and WaterSpout thaw
  hooks), but it cannot freeze arbitrary temporary water. Remote Torrent ice is left to its
  ordered authoritative close instead of being replaced by a conflicting local water layer.
- Per-session layer tracking guarantees that every delivered CREATE receives its
  REVERT even after leaving view distance. Stale or duplicate revisions are ignored,
  and a reverted layer cannot be resurrected by delayed metadata.
- The server binds an input action and semantic identity to each TempBlock layer at
  CREATE and retains both through REVERT. Reusing a long-lived ability instance cannot
  relabel older PhaseChange ICE as the newer input.
- Server abilities are not backdated. Press and release both travel through the
  same vanilla connection, so their interval—and therefore Paper muscle memory—
  is preserved without importing a Fabric input clock. Existing-instance
  transitions are associated generically only when the native activation handler
  reports that it handled the event; a suppressed/no-op click cannot steal an
  older PhaseChange, EarthSmash, WaterArms, or combo lifecycle.
- Repeating TempBlocks keep their normal configured lifetime. TempBlock completion
  callbacks and manager-created child work run inside their owning ability/action context.
  Scheduled work captures both player and native action sequence on Paper, dedicated Fabric,
  and the predicting client, so delayed WaterSpoutWave trails and similar child effects cannot
  silently become server-only. Equal-tick tasks retain Bukkit task-ID order and a zero-delay
  task starts on the next scheduler heartbeat.
- Gameplay-affecting random selection uses the shared player/action seed, including delayed
  child abilities, block/source selection, temporary-fire lifetimes, falling-block paths, and
  hit geometry. Independent scopes prevent cosmetic random calls from shifting gameplay
  choices. Semantic matching remains the final guard when legacy control flow selects a nearby
  coordinate.
- Common block/location hashing, solidity checks, ability progression order, and Fabric ray
  collision mode match Paper. Those platform details are part of source selection: differing
  hashes, iteration order, or outline-only rays previously made identical inputs choose
  different EarthSmash, RaiseEarth, FireBlast, and water coordinates. The client also advances
  the full Paper manager pipeline in `GeneralMethods` order before addon progress; it does not
  invent a separate Fabric input or tick schedule.
- Direct earth movement consumes an action ordinal only when the complete block state actually
  changes. Bukkit/Minecraft sends no packet for an equal-state write, so creating a fence for
  one would let EarthBlast's redundant per-step write hide a later real restore and leave an
  air trail. Minecraft can also coalesce several same-tick writes at one coordinate into one
  final chunk-delta entry. Direct receipts therefore collapse by server tick and coordinate
  until an observable packet consumes them; an intermediate EarthBlast focus/source receipt
  cannot remain armed and swallow a later restore. Moving an earth record into
  `TEMP_AIR_LOCATIONS` is a single-owner handoff: it is
  retired from `MOVED_EARTH`, and restoration uses `Information`'s actual map ID rather than an
  iteration index. A coordinate therefore cannot be restored independently by both queues.
  If client and Paper ordinals diverge inside an otherwise known Earth lifecycle, the ordered
  Paper write remains hidden; exact matching is diagnostic bookkeeping, not permission for a
  server-side Earth trail to render.
- A suppressed remote-player mutation sends no network message and keeps health, velocity,
  flight, status, and hit registration server-owned, but it does not abort the rest of the common ability
  pass. Torrent rings, WaterFlow trails, Discharge branches, and other visuals can finish their
  current world pass when another player intersects them. A contact also cannot make removal
  wait permanently for authority: AirBurst rays and other terminal streams still end through
  their normal local range/duration rules.
- `RegenTempBlock` retains its legacy replacement semantics. Freezing WaterFlow consumes the
  moving WATER handle before creating timed ICE, so expiry restores the real captured block
  instead of uncovering a buried, never-expiring water layer.
- Cooldowns begin immediately in the common client runtime and expire through the same
  end-of-heartbeat `BendingManager` pass as Paper. A delayed Paper add/reconcile/snapshot cannot
  extend that locally predicted expiry. If a native input was rejected while that local cooldown
  was active, an ordered negative veto precedes the vanilla packet; Paper still receives and
  counts the native event but skips only its bending callback, so a 500 ms cooldown cannot be
  bypassed merely because a 1000 ms round trip delivered the packet after expiry. Missing vetoes
  fall back to ordinary server validation, and no client message can positively authorize a move.
  Paper also tracks which cooldown generations were self-predicted, so the delayed removal of an
  older server generation cannot clear a newer cooldown already running on the client.
- Every common-model `setVelocity` call reaches the native client entity
  immediately. Paper publishes owner/action/ordinal immediately ahead of its
  vanilla echo. Fabric consumes that causal receipt and the following velocity
  packet in connection order; it never estimates ownership by comparing vectors.
  A self-owned echo is suppressed, while externally owned and unowned vanilla
  velocity packets remain authoritative.
- Ability flight-state writes use the same owner/action/ordinal fence. This keeps
  WaterSpout, AirSpout, scooter, jet, and flight echoes from reapplying an older
  local state. Constructor-time writes (notably AirScooter's initial allow/flying pair)
  inherit the native input action before an ability progress context exists. Because
  vanilla ability state is an absolute snapshot, multiple same-tick allow/flying
  writes collapse to the final ownership receipt when Minecraft emits only one packet;
  an intermediate receipt cannot steal the next correction. The acknowledged
  active-state snapshot is diagnostic and cannot override the client lifecycle, so an
  off-water/ground check performs its block and shared-flight cleanup as one transition.
  Unrelated
  gamemode or plugin ability packets carry no ownership receipt
  and therefore pass normally. During prediction lead, an unreceipted vanilla anti-flight
  correction may arrive before Paper has received WaterSpout/Scooter. While the local
  `FlightHandler` lease exists, only `flying`/`allowFlying` are preserved from that correction;
  invulnerability, creative mode, fly speed, and walk speed still update from the packet.
- `/pkprediction servertempblocks` toggles an owner-side diagnostic view of the
  physical Paper TempBlock stack. Normal play keeps the switch off and renders
  the client common TempBlock lifecycle as the answer.

## Hit registration and trust boundary

Client damage calls do not change health and do not send contact coordinates,
target IDs, or hit claims. Client contact can only suppress a remote mutation
while allowing the local visual/world pass to finish. Paper/Fabric's real
server ability tick and its native entity query are the only sources that can
register a hit. The server does not advertise or accept a client hit channel,
does not inject client-selected entities into collision queries, and does not
rewind a defender from client-provided contact data.

Once the native server collision query finds contact, damage, velocity, impact
sounds, status effects, and contact rewards commit immediately in normal server
order. There is no reaction window, defender recheck, ping compensation, or
deferred effect bundle. This behavior is identical whether either player is
modded or unmodded. Direct addon damage and `Entity#setVelocity` writes remain
server authoritative and never become client hit claims.

The server never accepts client health, positive cooldown authorization, block, element, bind,
source, input schedule, eye pose, or final origin state as authority. A negative cooldown veto
can only decline the sender's own bend for the matching native event. The server runs source
selection from the native Paper/Fabric player state present at the real vanilla
event, matching the pre-module-split Bukkit behavior.

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
- PacketEvents is not used for TempBlock concealment. Paper publishes action/layer ownership
  before each physical mutation; Fabric uses that ordered metadata to hide the complete
  locally predicted server lifecycle while retaining its client-side visual.
- The server runs Paper and installs `ProjectKorra-1.10.11-bukkit.jar` from this fork.
- Predicting players install `ProjectKorra-1.10.11-fabric.jar` in their Fabric client.
- The Paper server does **not** install Fabric Loader or the Fabric jar.
- Players without the mod use normal ProjectKorra input and normal latency.
- Fabric integrated-singleplayer prediction is disabled because the legacy common core
  uses one global `Platform` singleton for both logical sides. Singleplayer is
  already zero-latency; dedicated multiplayer is the prediction target.

## Verification matrix

Test with artificial 100/200/350 ms RTT and packet jitter:

| Area | Cases |
| --- | --- |
| Inputs | both-hand swings, left/right click, block/`INTERACT_AT` entity right click, tracked drop suppression, sneak press/release, slot rejection, swap hands, combos, multiabilities |
| Movement | AirBlast self/target, AirScooter, spouts, jets, flight, knockback chains |
| Blocks | water/ice streams, RaiseEarth, Surge/Torrent, lava, rapid/overlapping temp blocks, delayed CREATE/CLOSE metadata |
| Hits | stationary/moving players, edge-of-range, occlusion, 12+ tick latency, duplicate claims |
| State | bind/element changes, cooldown rejection, reconnect, config reload, missing client addon |
| Compatibility | Paper + modded Fabric initiator, unmodded initiator, mixed viewers, Paper restart/reload |

For production, also run the normal server movement/anti-cheat suite. Client
movement begins immediately by design, but the server remains responsible for
deciding whether received player movement is legal.
