# Combat rollback implementation

This is an implementation in progress, not an enabled gameplay feature yet.

The requested result is generic rollback for ProjectKorra combat, connected to
Neptune duels and the predicting Fabric client. There must be no list of selected
abilities, alternate hard-coded FireBlast/AirShield implementations, or attacker-only
historical target boxes standing in for replay. Existing ability code, dynamic speed,
movement, defences, collisions and their consequences must run on a consistent timeline.
Vanilla adapters cover behavior reached by those eligible bending duels; general Minecraft
PvP rollback is not a separate deliverable. Supporting every vanilla death/drop/advancement
edge case is not a prerequisite for integrating Neptune's existing match rules.

Scoreboard displays remain server-owned presentation. They do not need to be captured,
transferred to clients, or rewound with combat, and scoreboard import is not a prerequisite
for the Neptune runtime. Existing scoreboard adapter experiments below do not expand this
scope. Focus startup work on movement, abilities, collisions, damage, knockback and the
state that actually determines those outcomes.

## Server compatibility

The user requires stock Paper with plugins only. A custom Paper patch or mandatory
server fork is not an acceptable integration dependency. Native ownership must be
implemented by the plugin and verified against the packaged Paper version.

## Completion requirements

- Capture and restore all causally relevant simulation state, including mutable ability
  instances and registries, movement, cooldowns, timers, scheduled work, RNG state,
  provisional damage/velocity and changing arena collision geometry.
- Authenticate input streams, map client ticks to server-owned time, bound lateness and
  future input, preserve accepted inputs, and reject stale sessions and rewritten inputs.
- Predict missing input without repeating one-shot actions. Restore before the earliest
  changed tick and replay all affected simulation steps, including speed/steering changes.
- Separate provisional simulation from committed live effects. Replay cannot duplicate
  damage, knockback, block changes, external events, particles, sounds or match results.
- Reconcile both clients from authoritative revisions, acknowledge accepted input and
  distinguish the provisional tick from the finalized tick.
- During rollback, resolve hits from the same replayed tick as movement, projectiles,
  defences and terrain. Do not layer legacy historical hit claims or ping-based ability
  timing offsets onto that simulation. Disable artificial latency equalization for
  the whole enrolled duel; preserve the existing optional path for non-rollback fights.
- Integrate duel lifecycle, per-player opt-out, supported client negotiation, disconnect,
  death, world changes and plugin shutdown. Preserve normal gameplay outside rollback.
- Activate only inside an eligible active Neptune duel after every participant has
  negotiated the compatible mod/protocol and opted in. Mixed mod/unmodded fights,
  unsupported versions, opt-outs, public arenas and gameplay outside those duels use
  the existing gameplay path. Session teardown must not leave one participant running
  rollback against another on the normal path. Verify each eligibility/fallback case.
- Build Paper, Fabric and Neptune artifacts and validate the actual integration, including
  delayed/out-of-order input, dynamic projectiles, defences, world changes and rollback
  consequences. Pure engine tests alone do not prove the feature complete.

## Current foundation

### Installed gameplay catalog

Both artifacts now package the same generated common gameplay class inventory.
`RollbackGameplayCatalog` builds the portable schema from these local resources;
wire input cannot introduce class names. Addons can supply the same local inventory
resource. The inventory covers the common artifact rather than a selected ability list.
Unsupported object forms remain explicit symbols and still require bindings or
projections when reached by capture; the catalog does not silently omit their state.

Enum schema discovery reads local bytecode without initializing unrelated gameplay
classes. Empty enum maps use their own declared key type, including zero-constant
enums, through a fixed read-only JDK field accessor. This avoids trying every enum's
constants and triggering platform access during catalog creation or capture. Tests
cover broad inventory construction, stable ordering, and empty-map round trips without
unrelated initialization. Production bindings, full state roots and bootstrap installation
remain required before this catalog enables a duel.

Attribute caches now expose production metadata bindings, and
`CoreAbility.rollbackAttributeBindings()` enumerates the complete registered set,
including definitions for future activations. Their stable schema includes the local
field declaration, attribute key and canonical annotation values (including nested
annotations, arrays, class and enum values), so different client definitions reject
portable transfer. Mutable per-instance attribute values remain copied graph state.
The bending-state transfer test now uses the installed gameplay catalog and this
binding API instead of accessing private metadata through reflection. Production
bootstrap still needs to collect these bindings alongside player/world/service bindings.

### Shared combat event handlers

The existing Paper handlers for ability velocity, ability damage, attribute scaling
and horizontal collision damage now live in `CommonAbilityCombatListener`. Common
server reload and Fabric prediction startup register that shared listener. Paper's
old callable methods delegate to it without duplicate event registration. Priorities,
cancellation behavior and the existing ability-specific gameplay rules are retained;
these are ordinary gameplay rules, not rollback enrollment restrictions.

A portable graph test captures/imports the real listener through the installed catalog
and verifies falling-block velocity cancellation, null-ability handling and skipping
already-cancelled events. Lifecycle presentation and external effects use the explicit boundary described
below. These shared handlers alone do not install a live rollback session.

The remaining common lifecycle handlers now live in `CommonAbilityLifecycleListener`.
Element/passive changes and ability-start cancellation remain shared gameplay;
board refreshes, console commands and death presentation become immutable effect
values. Its explicit effect-service binding lets private import replace the live
Paper publisher with the session's provisional output destination. Paper registers
the shared listener and retains an adapter for ordinary live publication; that
adapter rejects calls during replay or off the server thread. Death presentation
carries names/UUIDs rather than retaining mutable ability/player objects.

Tests import the actual listener, replay late input, verify cancellation and effect
replacement, and publish only finalized output once. This is an effect boundary,
not an installed production publisher: match bootstrap must supply the binding,
include it in its output encoding and deliver confirmed effects. Scoreboard state
itself is still server-owned and is not transferred or made a new completion gate.

### Canonical roster graph bindings

`RollbackRosterBindings` provides the player/world binding IDs used by private
roster views and normalizes repeated loader wrappers before graph capture. A
player's UUID, native handle, online state and native world must still match the
enrolled identities; a same-UUID replacement is not accepted. The graph codec's
normalizer can return only explicitly registered binding objects and composes with
existing task/attribute projections. Bootstrap can obtain the private adapter from
`RollbackRosterViews.graphBindings()` and must supply the matching source adapter.

Tests cover round-trip alias preservation, foreign/replaced/disconnected identities
and the actual BukkitMC player/offline wrapper entrypoints. Bukkit's world wrapper
cache now replaces its cached entry when the native world instance changes, while
leaving previously captured views untouched; a same-UUID world replacement therefore
fails roster capture. Other unsupported native views still need
their own portable graph handling as part of production bootstrap assembly.

Paper's graph value-view adapter now snapshots its exact native Location wrappers
into ordinary common Locations. Each capture creates a fresh alias table: wrappers
sharing one mutable native location become one private location, but a later capture
reads current coordinates again. Arbitrary addon subclasses are not flattened and
raw native locations still require an explicit adapter. Existing task/attribute
projections retain precedence over the loader's value projections.

Common Location hashing now uses a constant world component, matching Paper's
existing wrapper rule, so separate private world identities do not reorder location
keys. Location equality also compares exact world handles and is symmetric between
Paper wrappers and restored common values, preserving live map lookups after teardown.
A Paper adapter test checks copied coordinates/orientation, shared backing
aliases, source isolation, hash-map iteration, worldless locations and addon state.
Bootstrap still needs to supply this adapter when constructing its source codec.

### Production assembly audit

The current native execution test is not a production assembly template. Its
`PaperRollbackExecutionNativeTest.Fixture` implements execution services with
fixture actions, an output list and synthetic world ticks. No production
`RollbackPlayerExecution.Services` implementation was found in the Paper rollback
package. Similarly, `PaperRollbackWorldServicesNativeTest.Callbacks` is the only
combined spatial/events implementation found there; its sky query always returns
true. Do not install these fixture behaviors or advertise bootstrap support from them.

Shared player execution now owns a stable `RollbackStepOutput` destination. It binds
the current provisional buffer before service startup and closes it after service/control
cleanup even when the tick fails. The destination rejects emissions outside its exact
clock scope, nested binding and checkpoints during an open tick. Both native execution
fixtures now forward their effects through it, with late-input replay and failure cleanup
coverage. Native producers still need production event/output encoding and service
installation; the router does not by itself assemble those policies.
Before assembling the production match bootstrap, provide native action remainder
handling and complete event bindings over the captured world.
Both native world-service adapters now accept the replay tick explicitly. Repeated
calls for that tick are inert, skipped/backward ticks reject, and the last accepted
tick rewinds with game time. Daylight advances with the captured native advance_time
rule, including midnight rollover, while a disabled rule keeps it fixed. Tests on
both loaders restore world conditions and tick identity together. Production service
startup must call this before scheduled work and player simulation; it must not tick
player bodies again in the remaining world phase. `PaperRollbackBorder` and `PaperRollbackEnvironment` already provide
owned border/environment components. `PaperRollbackSpatial` now combines these with
private native lighting for the production spatial binding: `PaperRollbackWorldQueries.Services` requires private sky lighting, while
`RollbackBlockStore.Cell` carries a single generic light level. The world seed now
also carries distinct native light layers, but these initial values alone do not
prove correct sky visibility after terrain changes.
`PaperRollbackSpatial` exposes all of those owned components to the checkpoint graph
and checks sky visibility using the native sky layer. Its native integration test changes
terrain, weather and border size, then restores them from the spatial root alone. The
complete match bootstrap still needs to install this service with real execution/event
bindings; an always-visible answer or a motion-blocking heightmap is insufficient.
### October 5, 2026 integration update

The Paper live ownership coordinator now acquires lifecycle, scheduled callback,
common gameplay and native player gates as one roster. It waits for every connection
handoff before exposing captured tasks or allowing private simulation. Startup abort
resumes original callbacks; once simulation starts, restoration requires outgoing
bindings. Both paths stage callbacks until native restoration, common release and
lifecycle release succeed. Native failures retain task/common ownership and expose
packet-drain cleanup retry. Native fixture coverage includes outsiders, failed restore,
retry, partial common acquisition failure and lifecycle shutdown before readiness.
This coordinator does not yet assemble terrain capture, graph catalogs, client transfer
or the complete production match bootstrap, and does not enable real rollback duels.
Owned duel capture now obtains task bindings directly from that coordinator instead
of trusting callers to add them. It verifies the exact owned roster and readiness
before/after native and graph capture, rejects duplicate task roots and nested capture,
and prevents simulation/teardown during capture. A failed capture permanently blocks
simulation on that owner while preserving its gates for startup abort. The unowned
capture API remains for isolated capture callers; production bootstrap must use the
owned path. Complete production catalogs and bootstrap installation remain outstanding.
Scheduled callback selection now has a generic reference-based resolver. It recognizes
captured player/bending-player/ability ownership, participant UUIDs and explicit
participant-only service identities, including untagged lambdas and cyclic container
state. It reevaluates references on every selection and rejects a callback shared with
an outside player. Shared loader infrastructure needs an explicit traversal boundary;
unknown opaque state fails selection. Global service tickers still need their own live
roster gates, and this resolver does not discover work hidden exclusively in static
registries. The native roster integration fixture now uses this resolver instead of
callback identity selection. Production infrastructure boundaries/service roots remain
to be assembled with the full bootstrap; this does not enable duels by itself.
Native sky and block light now have a separate bounded `RollbackLightSeed` snapshot
and strict binary codec. `PaperRollbackLightCapture` preflights loaded chunks and
reads each native light layer directly without using combined brightness or loading
terrain. Tests distinguish sunlight from emitted block light, check transfer detachment
and budgets, and reject missing chunks. World-seed version 2 now carries these layers inside the duel bootstrap payload.
Paper world capture supplies them automatically; the shared client/server decoder
requires exact matching terrain bounds. Version 1 remains readable for legacy
captures, but requireLight rejects absent lighting instead of inventing values.
The immutable seed initializes the detached lighting adapter described below; it does
not itself propagate changes.
A native lighting feasibility test now runs Paper's actual BlockStarLightEngine and
SkyStarLightEngine against detached ProtoChunk sections and a strict private Level
query shell. Adding/removing glowstone propagates/retracts block light; adding/removing
a roof reduces/restores sky light. No live world or server light queue is used. Native
height section maxima are inclusive. Sky queries must use StarLight's reader because
null/empty nibble sections can represent extruded sunlight; reading a nibble directly
can incorrectly return zero.
`PaperRollbackLighting` now owns detached native sections and both light layers,
propagates batched terrain changes synchronously, and checkpoints sections, nibble
states and emptiness maps together. Restoration stages all replacement allocations
before publishing chunk state. Mutations require a captured horizontal chunk halo;
queries outside captured terrain and foreign checkpoints are rejected. Native tests
verify cross-chunk emitted light, sunlight occlusion, repeated restore and an alternate
terrain timeline. Production capture must provide complete vertical world sections
and a horizontal halo. The logical block store now notifies derived-state rules on
every replacement, including writes with physics disabled, before invoking physics.
Paper's lighting rules adapter propagates that change and supplies current combined
brightness using captured sky darkness. The store exposes stateful rules to the
checkpoint graph, which reaches the native light state; a native integration test
restores the graph from the store root alone and verifies both terrain and light.
The production spatial service now composes lighting, environment and border. Complete
world service installation and Fabric parity remain missing. Paper environment now
exposes the native sky-darkness calculation from captured dimension attributes. The
spatial integration test supplies it to block lighting and verifies night brightness,
unchanged open-sky visibility and restoration to daytime; this adapter does not yet enable live duels.
`FabricRollbackLighting` now uses the native client LightingProvider over detached
ProtoChunks. It imports separate sky/block layers, propagates mutations with a captured
horizontal halo, and restores copied chunk sections plus light arrays into a newly
settled private engine before publishing it. Captured sections remain allocated even
when all-air: vanilla empty-section extrusion otherwise replaces imported sky values.
Its logical block rules participate in the checkpoint graph just like Paper's adapter.
Native tests cover the same source/roof/cross-chunk/alternate-timeline cases as Paper,
plus preservation of distinct imported layer values and graph-root restoration. The
Fabric artifact builds. This is component coverage, not proof of complete lighting
parity in arbitrary arenas; client spatial service installation and the complete duel
bootstrap remain required.
`FabricRollbackSpatial` now composes the owned client lighting, environment and border
and exposes them to the checkpoint graph. Its sky query rejects another terrain store.
The existing native team-query dependency is supplied as private state; this does not
transfer or publish scoreboard displays. Fabric environment exposes captured sky
darkness, and its spatial integration test now covers night brightness, persistent sky
visibility, terrain occlusion and restoration of weather/border/daytime together.
Focused native-client tests and the remapped artifact build pass. Installation in the
complete negotiated client factory remains outstanding.
World capture now expands the requested arena to complete horizontal chunks plus one
chunk of light-propagation margin, and to the native world's full build height. The
expanded volume is checked against the configured cell budget before capture. Existing
loaded-chunk and storage limits still apply; capture never loads missing chunks. Bounds
tests cover negative coordinates, exact chunk edges, world height, budget and integer
overflow. This supplies lighting-compatible terrain to the owned duel capture path;
it does not install either runtime bootstrap.

Live scheduler restoration/replacement now accepts a synchronous whole-state commit.
Native callback submissions are staged while dispatch remains gated; only a successful
external commit activates callbacks and publishes replacement handles. Failed state
restoration cancels staged submissions and retains the frozen task lease for retry.
Reentrant scheduler mutation is rejected during the commit. Tests invoke queued
callbacks inside the commit to prove they are inert, then cover failed commit, retry,
unbound replacement handles and activation exactly once. Existing no-argument task
restoration retains its behavior. The production owner must use this boundary to
coordinate common/native/terrain restoration; that full owner is not assembled yet.

The common velocity interception now checks live rollback ownership before either
publishing a receipt or entering any direct-write/commit shortcut. Paper's wrappers
also reject direct health, damage, velocity and fire writes to reserved live players;
shared status callbacks skip invulnerability, potion and air mutations. Private
rollback domains and outsiders retain their writes, and release restores the normal
path. Executable wrapper/common tests cover blocked writes, boolean status results,
commit/predicted-remote scopes, private output routing, outsiders and release. This
covers ProjectKorra's platform entry points, not arbitrary third-party native setters;
complete source-body ownership and production bootstrap assembly remain required.

Paper prediction startup now installs `PaperRollbackLifecycle`, with an explicit
whole-roster reservation API for the pending bootstrap/runtime owner. Commands,
teleports, mounts/dismounts and kicks request whole-duel teardown before continuing;
failed or incomplete teardown cancels cancellable events and retains ownership.
Quit/world-change events also request teardown. Shutdown attempts every reserved
duel and retains failed reservations for retry. Unreserved players and events inside
private simulation do not trigger teardown. Tests cover whole-roster release,
outside players, cancelled mutation, two affected duels, replay bypass and failed
shutdown retry. The production bootstrap still must reserve this listener and
release its reservation only after native/common/terrain restoration succeeds.
After native handoff, the lifecycle lease can suspend live native effects for its
whole roster: damage, healing, potion changes, combustion, food and air events are
cancelled on the original bodies. Reservation alone does not suppress effects,
private simulation bypasses this guard, and release restores ordinary events.
Tests exercise each event family, both roster members, outsiders, replay and release.
The production bootstrap must arm this guard before state capture. Native mutations
that bypass events (including direct velocity/health setters), death fallback and
live listener integration remain to be completed and verified; installing the empty
listener enables no duels.

`PaperRollbackNativeOwnership` now composes the world tick gate and every roster
connection gate behind one preparation/cleanup owner. Acquisition failures retain
acquired components for cleanup. Startup polls every participant's network/server
queue handoff and exposes readiness only for the full roster. Native world, passenger
and removal changes invalidate readiness. Restoration runs before any connection is
released; a failed final world-gate release reinstalls detached connection facades.
Such a failed release requires a fresh packet handoff before cleanup can be retried,
and this owner cannot resume simulation once cleanup starts. Native fixtures cover
two-player readiness, outsider world ticking, failed restoration, recovered facades,
stale queued input on retry, partial acquisition and idempotent release. This owner
still needs common/task/terrain ownership and lifecycle event interception, and is
not yet installed by a production bootstrap.

A stock-Paper connection tick lease now installs a native listener subclass facade
through a compare-and-set on the Connection's listener reference. Tick calls run
maintenance on the original listener; permitted virtual calls delegate to that same
original and no native listener constructor runs. Player.connection remains original.
The facade preserves the native type/field contracts needed by Paper, checks final
method compatibility, and restores the exact original listener after successful cleanup.
A native probe exercises actual Connection.tick dispatch, original state/packet-send
delegation, failure retention, foreign replacement rejection and resumed ticking.
The fixture has no socket and does not establish live networking correctness.
Incoming vanilla movement/combat input is discarded while suspended, including on
network threads. Only audited acknowledgement/keepalive traffic and the three exact
rollback input/bootstrap/start channels pass directly. Other packets (including
commands, inventory and other plugin payloads) require whole-session teardown before
native dispatch. Off-thread teardown packets queue against the facade for ownership
revalidation. Failed or incomplete teardown prevents native dispatch. Tests cover
movement suppression, exact channel policy, failed cleanup, and command resumption.
The lease now offers a polled network-event-loop barrier followed by a targeted
PacketProcessor queue handoff. Original-listener packets queued before the barrier
are removed as one batch and routed through the facade; duplicate gameplay input
is discarded even if a preceding command ends ownership or throws. Other players'
queue entries are untouched. A real PacketProcessor and embedded Netty channel test
covers waiting for the barrier, late original-listener enqueue, control delivery,
outsider preservation, teardown failure and stale movement after successful teardown.
The barrier also posts a marker to Paper's server task queue, behind commands
submitted by earlier network callbacks. Readiness waits for that marker as well as
the network checkpoint and packet drainage. A controlled server queue verifies that
readiness stays false before those older tasks and the marker finish. Retained
facades from a previous session route through a newer session's gate, so old queued
callbacks cannot bypass its movement/command/tick ownership; a native regression
checks two consecutive leases. This is not a live socket test. Arbitrary asynchronous
plugin/chat chains, disconnect cleanup and whole-roster composition still require
integration before startup; the gate is not installed in production yet.

The connection maintenance component mirrors Paper's paused tick branch without
calling its native `tickPlayer`/`doTick`: pending block acknowledgements, keepalive,
all four spam throttlers and idle timeout remain active. It invokes the existing
listener's keepalive method rather than creating another keepalive state. A native
probe verifies keepalive dispatch, one-time acknowledgement, throttler progress,
unchanged native player values/items, disconnected short-circuiting and thread/replay
boundaries. The lease above uses this helper; production lifecycle composition
is still missing, and the helper alone does not suspend a live connection's player tick.

A plugin-owned world tick gate now wraps Paper's entity tick list. It skips reserved
native player identities while delegating membership changes and preserving outsider
iteration order. Disjoint rosters share the wrapper; overlapping acquisition rejects
before reservation, chunk removal/re-addition cannot re-enable an owned player, and
failed cleanup retains the complete gate. The final release restores the exact original
list. A native regression verifies these cases against Paper's real EntityTickList.
Acquisition requires no active passengers and a live tick boundary; loader interception
of riding/world changes and the separate connection `doTick` path remain required.
This component is not installed into live gameplay yet.

Native ownership audit: the local Paper development bundle calls `ServerPlayer.tick`
from the world's entity tick list and `ServerPlayer.doTick` from
`ServerGamePacketListenerImpl.tickPlayer`. Client-load state gates movement and some
damage but does not suspend these ticks. Freezing only inputs, cancelling movement,
or toggling client-load state is insufficient. Paper's `EntityTickList` supports
individual removal/reinsertion, but connection tick interception still needs an
implementation preserving keepalive, chat, disconnect and protocol transitions.
Any listener handoff must account for already queued packets and restore original
listener identity; it must not drop normal connection handling or freeze outsiders.
No native freeze implementation has been enabled from this audit.

Paper now stages intrinsic roster components as one prepared commit: scalar values,
tracked state, attributes/effects, inventory aliases/cooldowns, controls/contact caches,
and combat references. Every participant is decoded before any writes, destination
identities/world/connections are rechecked, and completed writes are not repeated on
retry. The native test rejects malformed data for a later participant without touching
earlier players, rejects a changed destination identity, verifies native state and
cross-player references after commit, and checks idempotence without events/packets.
This is a component commit, not complete live restoration: the loader must still hold
native ownership, update spatial tracking, resynchronize clients, and restore world,
RNG/services and bending state before releasing the entire roster.

The combat runtime now exposes a detached export boundary at a settled replay tick.
A loader can encode native bodies, terrain and bending state in one installed private
scope, with the payload tagged by tick, confirmed tick and revision. Export rejects
unreconciled corrections without failing the session or silently advancing/publishing
it. The runtime tests exercise late-input replay before export, private registry access,
live registry restoration, immutable output bytes and encoder failure. Loader payload
assembly, failed-session recovery and native restoration remain to be integrated.

Portable graph catalogs now normalize Paper's Commons/SnakeYAML package relocations
when ordering types and hashing schemas, while retaining each loader's own classes.
The canonical names survive Shadow's string rewriting. Tests transfer cyclic objects,
typed arrays, class symbols and external bindings in both directions; a separate run
against the freshly shaded Paper jar verifies actual relocated MutablePair instances.
Common, Paper and Fabric tests and both packaged builds pass. Production catalog/root
assembly and the loader bootstrap providers remain required before live duels can start.

The shared activation dispatcher, bending/passive checks and combo mutation paths now
honor live roster ownership. An owned player's live inputs cannot invoke registered
handlers or combo constructors, schedule/consume combo clicks, or edit recent inputs.
History queries and periodic cleanup preserve owned entries while the live bending roster
is retained. The same registered callbacks and combo constructors execute inside the
private domain. A regression exercises live rejection, private activation, preserved live
history and resumed activation after release. Loader input interception and external
listener/constructor paths still need integration; this is not complete native ownership.

Live roster gates now also cover movement restrictions, queued potion effects,
temporary-armor expiry and horizontal collision-damage trackers. Movement restrictions
and collision trackers also honor an enrolled source player. The movement-handler tick
uses a stable snapshot so expiring one handler does not skip another. A behavioral
regression runs the real shared status updaters with enrolled and unrelated players,
verifies deferred potion/armor/stun changes and tracker callbacks, then verifies normal
progress resumes after release. Native entities/terrain, activation, external listeners
and remaining service paths still need ownership integration before startup is enabled.

A common live-roster ownership lease now suspends enrolled ability progression,
collision participation, cooldown updates, flight expiry and temporary-element expiry.
It is independent of element/ability type and stays outside replay checkpoints. Private
domains continue normally. Overlapping rosters reject before acquisition; restoration
failure retains the entire reservation, and successful idempotent cleanup releases it.
Expiry queues defer owned entries while processing unrelated players behind them. Tests
exercise both queues, real ability progress/collision exclusion, private replay, overlapping
ownership and failed cleanup. Production startup still must acquire this lease and bind
native movement/input, scheduled callbacks and the remaining shared service update paths;
these common gates alone do not establish full native ownership.

Common live restoration now exposes a combined prepared commit for player registries,
ability indices, attribute caches and shared managers. It validates every component before
writing any live registry and reuses idempotent component commits for cleanup retries. The
portable and in-process graph regression now includes a real statistics manager through
late-input replay, export, native-handle rebinding and the combined commit. A changed
manager catalog or attribute definition leaves player and ability registries untouched;
unrelated players keep their manager entries and object identities. The loader still must
retain ownership while restoring native bodies, terrain, listeners and scheduled work;
this common commit does not by itself enable production startup.

Work continues in the attached `rollback-integration` worktree because the primary
checkout is on `master` with separate local gameplay changes. The rollback branch and
push destination remain `wip/rollback-2026-09-23` on MCAvatarPvP/Bending.

The private scheduler now distinguishes per-domain service timers from transferable
ability work. Both kinds execute and rewind normally, but outgoing task export excludes
service timers and projects any retained service handle as inactive. The private flight
manager uses this path for expiry cleanup; its existing live counterpart already owns
that timer. Ordinary callbacks still transfer with their state and reserved IDs. Tests
exercise real flight expiry through late-input replay and export, plus mixed service and
gameplay timers. This prevents a duplicate cleanup timer during manager restoration;
current participant manager state now merges into the existing live managers. Flight and
statistics restoration preserves shared manager/container identities, copies outgoing
participant entries with the ability graph, and retains unrelated players. Every manager
is validated before any commit; changed manager ownership or statistic definitions reject
the merge. Repeated commits do not overwrite subsequent live progress or start service
timers. Tests cover late-input replay followed by restoration, shared references, outsider
preservation and rejection before mutation. The complete production handoff remains to
be integrated.

Rollback contact authority is element-independent through every public hit-policy entry
point, including direct class/element resolution. Regressions cover all main elements,
subelements, an addon element and absent metadata. `AirFireCombat` is absent from this
branch; the shared rollback domain determines contact time. The existing element-specific
policy outside enrolled rollback sessions remains unchanged.


### October 2, 2026 integration update

Restored bending graphs now prepare a participant-only live ability-index commit using
the original registry and the owned ID reservation. It validates roster, retained/new
ability identity, canonical cache bindings, unrelated ID overlap and unchanged original
membership before mutation. Initial commit requires matching simulation/live ability ticks;
it does not reset the global tick or ID allocator. All three active-instance indices are
updated in place without activation/removal hooks, preserving unrelated per-player maps
and class sets. Repeated commit remains inert across later live ticks. Integration tests
cover actual outgoing graph/player/attribute/ability restoration, removed and replay-created
instances, unchanged outsiders, mismatched ticks and stale ownership rejection. Production
ownership still must freeze live progression and align the replay tick before committing,
and manager/native/service restoration and startup assembly remain outstanding.


Live-restoration decoding now rebinds shared attribute caches and their entry maps to
canonical live identities, while carrying outgoing participant values in separate detached
maps. The local second copy uses the already negotiated codec layouts/bindings and preserves
ability/listener/task aliases. Attribute commit validates current cache ownership and
definitions before replacing only participant entries; unrelated abilities retain their
cache/map identities and values. Repeated commit is inert. Integration regressions cover
aliases held directly by abilities, unchanged live values before commit, changed cache
ownership rejection, successful participant merge and preserved outsiders. This prevents
whole-cache replacement during ability restoration. Live ability indices, managers and
native bodies still need to be committed within the retained ownership handoff.


Ability construction now honors a transferable ID bound. Live startup can reserve a
roster-specific range, then include the source-only reservation in bending capture roots;
the capture stores its next ID/limit in the transferred ability registry and omits the
live reservation object. Ordinary live ability creation skips the reserved range. Domain
checkpoints rewind allocation and exhaustion fails without recycling IDs; outgoing graph
export keeps the bound for restoration. Reservation capture rejects another roster or
participant abilities created after reservation. Tests use real ability construction to
verify disjoint live/private allocation and rewind, and transfer the bound through both
bending import paths and live-restoration decoding. The production owner still must
reserve before capture and validate the bound when committing restored ability indices.


Outgoing bending graphs now have a separate live-restoration decoder. It checks the exact
original live roster and native body identities while keeping private-domain decoding
strictly bound to private players. The detached result retains aliases among restored
players, abilities, listeners and task callbacks, and exposes a prepared participant-only
player-registry commit. Integration tests export actual reconciled state, decode it onto
live bodies without constructor/activation or registry effects, then commit player state
and expiry while preserving outsiders. Wrong private/live destination bindings are
rejected. Ability/manager/service commits and native state still need to join the retained
ownership handoff before startup or gameplay can be enabled.


`RollbackBendingState.exportState` now captures the installed domain's current ability,
manager and temporary-element registries rather than reusing startup membership. It
replaces initial task/listener roots with the settled scheduler export and current event
registrations, copying all roots together to preserve aliases. Codec export is explicitly
allowed only inside the owning domain between simulation ticks; ordinary encode/decode
retain their bootstrap-only boundary. Integration tests export after late-input replay,
then add/remove abilities, change listeners, update player/expiry state and decode the
outgoing graph outside the domain. They verify current membership and aliases, inert
completed-task handles, no activation/constructor rerun and unchanged live state. Live
ability/manager registry commits and full native restoration still need integration.


Player registry restoration now has a prepared roster-only commit. It requires the exact
owned participant set, checks the copied players are rebound to their original live body
handles, validates temporary-element ownership, and verifies both online/offline registry
identities before any write. Commit replaces only those participants and their temporary
element entries, preserving unrelated players and expiry entries. It runs on the live
main thread, invokes no loading/persistence/activation hooks, and is idempotent while the
restored roster remains current. A regression covers stale ownership, unchanged outsiders,
updated player state/expiry, foreign body rejection and replay-scope rejection. The complete
outgoing graph must still be copied and abilities/managers/native bodies restored under the
same retained gameplay gates before production startup can be enabled.


A reserved live scheduler lease can now replace frozen startup callbacks with task
bindings copied from the final replay state. It validates original/reserved IDs and
unbound handles before native scheduling, stages every callback behind an execution
gate, and binds/releases the batch only after all submissions succeed. Failed native
submission or cancellation retains retryable cleanup and cannot resume stale startup
work. Original and abandoned dispatches remain inert. Restored tasks execute on the
main thread with their captured ability/action/seed context. Shutdown/discard cannot
resurrect a pending replacement. Tests cover the real live/private/live graph transfer,
new replay-created tasks, self-cancellation, partial failure and foreign IDs. The outer
production owner still must copy and commit the full restored gameplay graph and keep
whole-roster gates closed through this task replacement; no provider is installed yet.


Live scheduler handles now use monotonic logical IDs independent of native backend IDs;
unknown logical cancellation cannot reach unrelated native tasks. A startup lease can
reserve a bounded ID range for new replay tasks. Other live scheduling skips that range,
and aborted reservations are not reused. The transferred task bindings carry both the
next ID and exclusive limit, so client/server import, rewind and outgoing task export
retain the same bound. Exhaustion fails before adding work. Reservation tests exercise
wire transfer, replay rewind/export and continued unrelated live scheduling. Production
startup must use the capacity-taking freeze overload and keep the lease through final
restoration; unreserved freeze remains available for abort-only task capture.


The private scheduler can now export remaining work at a settled replay tick, including
updated callback state, relative deadlines, deterministic context, handle aliases and
reserved IDs. The export is copied with the outgoing gameplay graph and does not retire
the source before destination preparation succeeds. Regression tests cover a callback
that has already executed, a second transfer of imported handles, inactive handles and
rejection during callback execution. This is the task-state export needed for final
ownership restoration; committing the full restored gameplay graph remains outstanding.


Paper's platform scheduler now tracks callbacks through `RollbackLiveScheduler`.
A preparation receives its cleanup lease before freezing selected gameplay work;
selection uses captured work/context rather than an element or ability-name list.
Freeze rejects callbacks already executing, retains scheduling order and relative
remaining delays, and projects pending handles into the portable bending graph.
Completed/cancelled handles become inert private handles and their IDs stay reserved.
Private replay can self-cancel and rewind copied callbacks without reaching native tasks.
Aborted startup restores original contextual callbacks with stable logical handles;
stale queued dispatches cannot execute after restoration. Partial cancellation and
rescheduling failures retain ownership for cleanup retries. Paper now routes its
platform scheduling calls through this tracker. The compatibility server scheduler
also preserves compiler callback descriptors for lambdas/method references in every
asynchronous, delayed and repeating scheduling overload; existing Runnable objects
keep the same dispatch path. `callSync`, direct external Bukkit
scheduling, complete cohort task selection and running-session task restoration still
need production ownership integration; this does not install a bootstrap provider.


`RollbackRound` now lives in the shared ProjectKorra module, so Paper and Fabric
can use the same provisional defeat, team-survival and attacker-attribution rules.
It implements `RollbackStateCell`: domain checkpoints rewind provisional results
without capturing finalized delivery receipts. Paper's bound native round events use
this shared type; its ordinary damage rule remains available without ProjectKorra,
and a parity test checks that both lethal-hit thresholds agree. The existing native
Neptune contract exercises this shared model against actual private Paper damage.
`RollbackCombatRuntime.createMatch` and `createMatchReplica` now advance the round
before inputs/native/bending execution and include it in every checkpoint. The server's
`deliverConfirmedDefeats` uses the engine's confirmed frontier outside all replay scopes,
rejects client delivery and reentrant engine operations, and stops further simulation if
a live callback fails. Tests replay a late defence through the actual collision loop to
retract a defeat, then verify confirmed results cannot be delivered twice.

`PaperRollbackWorldAccess.bindRound` derives the exact roster from its owned native
players before checkpointing. Native damage dispatch now runs the bound terminal round
rule after the captured modifier/cancellation dispatcher. The world automatically
checkpoints the round; the native contract no longer installs a test-only terminal
callback or manually checkpoints round state. This covers nonlethal/lethal damage,
cancellation, totems, attribution, foreign players and late-input defeat retraction.
Round binding seals the native roster against later player imports. Roster sealing by
spatial queries still permits round binding until the first world or player checkpoint;
checkpoint creation permanently closes that setup boundary. Native contract regressions
cover both assembly orders and reject binding after either checkpoint path.
Fabric's native damage-event equivalence, complete production runtime assembly,
and ownership handoff still need integration before play.
Validation of this connection passed 616 common tests, 178 native Paper tests and
11 native Neptune contract tests; the roster-binding follow-up reran both native
suites and rebuilt Paper. Paper and Fabric jars also built for the initial connection.

`RollbackServerRuntime` now owns the negotiated authority loop and input reservation.
It advances combat, publishes the whole-roster authority update, then delivers finalized
outputs and confirmed results outside replay. Failed publication skips those deliveries;
failed output stops further ticks without retrying a partial external mutation.
`PaperRollbackMatchRuntime` connects that loop to Paper's existing ingress and transport,
and routes `deliverConfirmedDefeats` to the callback in the Neptune bootstrap request.
The native bootstrap still must construct this runtime with the imported combat domain
and its real output/restoration owner; no provider is installed yet.

Neptune queues that callback during runtime ticks. Once the tick returns, a confirmed
defeat retires the entire rollback session and restores native ownership before applying
ordinary match deaths with captured attacker attribution. This prevents match cleanup
from reentering replay and preserves the whole-roster fallback on death. Provisional or
retracted defeats, failed ticks, and failed restoration never invoke live deaths. Partial
match callback failures cannot repeat already applied results. Shutdown now retains
input gates after failed native restoration until the owner explicitly finishes cleanup.
The authority loop passed the complete common/Bukkit suites and Neptune native contract;
Neptune also passed tests covering result order, replay guards and cleanup failure.

Neptune's saved branch is now checked out separately at
`build/neptune-rollback`, preserving the main checkout's local changes. Build this
checkout with `-PprojectKorraJar=<absolute path to the 1.10.30 Bukkit jar>`; its default
sibling dependency has also been updated to 1.10.30. The isolated checkout is retained
for the remaining runtime and match-lifecycle integration. After moving the round
model, 614 common tests, 348 Fabric tests, 44 Neptune plugin tests and 8 native
Neptune damage contract tests passed. Paper, Fabric and Neptune artifacts built;
these are component/integration checks, not a live duel validation.

The rollback checkpoint has been updated with `master` at `035d9ecb` (version
1.10.30). Ordinary Air/Fire combat uses current server positions for both modded
and unmodded targets. Inside a rollback domain, both ability and target policy
entry points still use the restored simulation tick for every element; leaving
the domain restores the ordinary rules. `RollbackHitRegistrationTest` covers
that transition alongside the normal hit-policy suite.

Verification passed with Java 21 and the offline Gradle cache: `:common:test`
(605 tests), `:bukkit:test` (186), `:fabric:test` (348), and `:bukkit:nativeTest`
(178), with no failures or skips. `:bukkit:shadowJar` and `:fabric:remapJar`
produced the 1.10.30 artifacts. Neptune's contract suite and live duels were not
run during this update.

This merge also brings in master's collision effects and AirSweep hit sound fixes.
It does not install the missing production bootstrap provider or Fabric importer.
The next integration work remains complete runtime assembly, listener import,
native ownership handoff/restoration and client presentation before live duel testing.

The sibling Neptune checkout currently contains separate uncommitted work on
`main`; it was not switched or modified during this merge. Resume Neptune integration
from its saved `wip/rollback-2026-09-23` branch in an isolated checkout and build it
against the 1.10.30 Paper jar using `-PprojectKorraJar=<absolute jar path>`.

Neptune's `RollbackMatchService` now monitors active solo/team rounds and calls the
actual `PaperPredictionServer.beginRollbackStart` once the complete roster has been
prepared. It owns the equalization suspension, waits for both queues to drain, checks
the match/world/roster/preferences, and bounds client preparation. Round/end/reset,
disconnect, world change and the saved Combat Prediction toggle stop the whole session
before releasing equalization. Failed native cleanup retains the reservation. Shutdown
stops rollback before shutting down latency hooks. Lifecycle tests cover delayed
preparation, opt-out, disconnect, timeout, partial failure and reentrant teardown.

**This still does not enable rollback in real matches.** `PaperRollbackMatchBootstrap`
defines the native capture/transfer/ownership boundary; its complete implementation has
not been installed. Neptune leaves ordinary gameplay active when that provider or a
compatible whole roster is unavailable. The next integration work is implementing that
provider and client preparation from the complete shared state. No server was started
or artifact deployed. The updated Neptune branch defaults to the sibling 1.10.30 Paper artifact;
build its `:bukkit:shadowJar` first, or set `-PprojectKorraJar` to a compatible jar.

Neptune's bootstrap request now carries the translated match arena's chunk-aligned
terrain bounds, including a neighboring chunk and the world's full build height.
The lifecycle rechecks those bounds during preparation and play. Oversized or invalid
captures keep ordinary gameplay. `PaperRollbackWorldSeed` captures terrain, native
rules/policy, RNG seeds, day time, border and weather at the same live tick; no chunks
are loaded or generated. `PaperRollbackDuelSeed` combines that world with the exact
Neptune native/bending roster, configuration and the existing dynamic bending graph.
The capture is read-only: its preparation owner must still freeze gameplay and keep
it quiescent until handoff. The class is not yet called by an installed provider.

`RollbackBootstrapData` transports these components as one bounded snapshot, including
session/challenge/match/round, sides and the two simulation clock epochs. It rejects
different roster/world capture ticks and rejects incompatible gameplay definition
hashes before native terrain decoding. Its fingerprint covers all actual state bytes.
Both loaders must still validate their complete local service/catalog bindings and
import the graph before acknowledging readiness. A Paper-produced reference combining
native terrain, roster and rule fixtures is decoded and re-encoded identically by
Fabric; this proves format compatibility, not a live duel or complete runtime import.
`RollbackBootstrapPacket` now transfers the snapshot in 24 KiB pieces, with bounded
assembly, exact lengths, duplicate/rewrite checks, a deadline and a hash of the whole
payload. Paper's registered bootstrap channels feed `RollbackBootstrapServerEndpoint`;
the server sends bounded batches round-robin and pins each receipt to its original
connection/world. Only the entire imported roster can produce the actual prepared
peers for the existing start negotiation. `PaperPredictionServer.beginRollbackBootstrap`
returns a cleanup handle before sending anything. The preparation owner polls that
handle and hands its acknowledged peers to `beginRollbackStart`.

Fabric's registered receiver bounds the transfer, rejects incompatible/unavailable
importers, suspends legacy prediction and freezes ordinary local native input/ticking
while preparing. A loader-owned `FabricRollbackBootstraps.Factory` must construct the
complete native replica. The receiver installs that runtime into `FabricRollbackStarts`
before sending Ready; receiving all bytes alone does not acknowledge preparation.
Disconnect, opt-out, timeout and import failures clean up, and failed native cleanup
retains the freeze until repaired. Tests cover real native freeze leases on partial
import failures, reentrant teardown and cleanup repair. A wire test takes both import
receipts into the existing unequal-latency clock/commit barrier and runs its real session.

This is still not enabled in matches: neither the production server bootstrap provider
nor the complete Fabric importer is installed. Production platform/service/catalog
construction, full native ownership handoff/restoration and live presentation/results
remain necessary. Transport and component tests do not establish a working duel.

`RollbackPlatform` now supplies the private whole-roster player lookup, arena world
listing, captured-chunk callbacks, scheduler and rewindable `RollbackEventBus` on either
loader. The domain checkpoints this platform automatically. Event dispatch uses cached
method handles, stable method/registration order and the existing common event priorities
and cancellation rules. Registration, removal and listener-owned mutable state rewind
together. The complete common bending-loop tests now use these owned services instead of
an empty world list/no-op event bus; delayed defence registration cancels the actual
`AbilityCollisionEvent` when replayed. Native registry, plugin, permission,
presentation and model-adapter bindings are still mandatory importer services, and the
common listener capture/import is now connected to `PaperRollbackDuelSeed` and
`RollbackBendingState.install`. `PKEventBus.commonRegistrations` exports exact handler
metadata in source registration order on Paper and both Fabric platforms. The seed
includes `RollbackEventBindings` in the same portable graph as active abilities, so
listeners that reference those abilities resolve to their imported instances. Private
installation validates handler definitions and budgets before publishing any handlers.
Native-only event hooks still use the native adapters; unsupported listener definitions
or missing catalog/owner bindings fail import rather than silently dropping rules.

The imported-bending regression now transfers a live collision listener with an active
ability through both in-process and portable graph import. Late input removes the
collision and rewinds the copied listener count while the source stays untouched.
Loader and common tests also check registration order, ownership removal, duplicate
registrations, metadata mismatch, and atomic failure. The production graph catalog must
include `RollbackEventBindings`, `PKEventBus.Registration`, all relevant listener types,
and private replacements for lifecycle owner tokens. All 1,320 common, Bukkit, Fabric,
and native Paper tests pass after this change; both 1.10.30 artifacts rebuild. Full
native/runtime assembly and live-duel verification remain unfinished.

`PaperRollbackRosterViews` and `FabricRollbackRosterViews` now bind the imported native
cohort to the ability API through `RollbackRosterViews`. Movement, health, controls,
equipment and inventory share each exact owned native player. Both loaders check roster
ownership and profile mode/main hand; common construction publishes the cohort atomically
after every view validates. The owned platform can retain these views and their mutable
policies as checkpoint roots. Native tests exercise Paper capture/import and the shared
Paper-to-Fabric roster fixture through these views, including damage/health, motion,
item mutation, restoration and late import failure. Captured player access now travels
with the seed as described below; event/output services still require production bindings;
this constructor does not invent local defaults or install the match bootstrap provider.

`RollbackPlayerAccess` carries each participant's server profile, visibility within the
roster and resolved permission decisions, including denials. Paper captures registered
nodes and their children, effective nodes, the generic ability/passive nodes and any
additional nodes declared by the gameplay catalog. No permission-provider internals,
scoreboard displays or grant-list truncation are involved. An unqueried permission fails
explicitly; neither endpoint guesses from operator status or wildcard strings. Addons
that construct unregistered permission nodes must declare those nodes before capture.
The version-2 duel seed validates this component against native roster IDs, game modes
and main hands. Both loaders' player views can bind the captured policy directly; the
Fabric regression imports the actual Paper-produced bootstrap fixture and verifies grants,
denials and visibility alongside native state restoration. Production service/catalog
construction and ownership handoff are still needed before this starts a real duel.

Player targeting no longer requires importer-supplied query callbacks. `RollbackPlayerState`
reads exact block targets, legacy material targets and nearby entities from its registered
private arena. Sight uses current eye endpoints, the native viewer collision context and
vanilla's distance limit; Paper and Fabric geometry both provide that context for owned
player bodies. Nonliving sight targets must expose an exact native eye height rather than
guessing one from their bounding box. Imported-roster tests on both loaders exercise lower/
upper slab occlusion, vertical target movement, native body bounds and restoration. These
queries do not read live positions or legacy interpolation history. Runtime construction,
ownership handoff and presentation/results remain outstanding; this does not activate a duel.

`RollbackConfiguration` captures every registered public prediction configuration into
a bounded typed snapshot, preserving defaults, explicit values, aliases and iteration
order. Both replicas prepare private views from the same bytes. The data fingerprint
includes actual values and config implementation contracts. Domain entry routes existing
static and ability-held config references to those views; reloads outside the domain
cannot change replay. Native file writes, config mutation and registration are rejected
inside replay, and mutable values returned by reads cannot alter the captured seed.
Graph bindings retain addon config types. Existing JedCore/Hyperion reader behavior is
preserved; style support is deprecated and is not a prerequisite for further work.
Tests cover portable bindings and the complete bending loop replaying late input after
the live speed setting changes. Production bootstrap still must transfer this component
and combine its fingerprint with all the other gameplay definitions and native seeds.

The Bukkit module now uses `paperweight-userdev` and the 1.21.11 development bundle.
Its output declares the Mojang mapping namespace. Public native geometry, movement,
item, inventory, player and world calls use compiled Java types; the generic
string-dispatch `PaperNativeAccess` helper has been removed. Block/shape reads use
typed BlockGetter implementations. The player collision context is an ordinary
native Player subclass, and the item metadata table is generated from Paper's
ItemType declarations during the build rather than discovered on the server.

`PaperRollbackPrivateAccess` isolates the remaining private/final-field setup using
cached, typed method handles. Field discovery happens once; simulation calls do not
use Field.get/set or Method.invoke in these Paper adapters. Native body extraction
and callback discovery still inspect metadata at bootstrap. The shared generic
state graph caches class layouts and binds selected field accessors once with
ClassValue, preserving addon unloading and per-graph field selection. Capture and
restore use exact method handles, including primitive fields and static roots;
final references remain checked rather than overwritten. The native query shell
also caches its handler setter and spreads native arguments once at binding time.
Server event dispatch and optional model-hitbox queries use bound method handles;
supported optional plugin APIs and player-count metrics use direct calls.

Neptune also uses paperweight. Its connection/keepalive adapter and packet-based
scoreboards use typed native APIs, its command map uses Paper's public API, and
the unused reflective temp-block hook is removed. Latency queues and eligibility
remain unchanged. Paperweight does not make private fields public or remove the
need for dynamic addon, command, event or native-body metadata discovery. These
bootstrap operations and optional class-presence probes remain intentional.

`prediction.rollback.RollbackEngine` owns bounded input history, prediction, batched
replay, fixed simulation time and an irreversible frontier. It is independent of the
abilities and the platform. `RollbackSimulation` is the capture/restore/step contract;
its snapshots and inputs must be detached immutable values. `RollbackStep` buffers
immutable effect descriptions. The runtime adapter and transport are still required.

`RollbackStateGraph` captures mutable ability graphs without enumerating individual
ability implementations. It preserves identities, shared references, cycles, removed
instances, collection membership and mutable hash keys. `RollbackStateCell` supplies
an explicit checkpoint contract for opaque services and declares the mutable objects
those services reference. Unsupported mutable JDK/native state fails capture.

`RollbackStateTransfer` now creates independently owned common gameplay graphs for
session import. It binds field layouts once and initializes ordinary instance fields,
including finals, on unpublished copies without running ability constructors or load
hooks. All roots share one identity map: bending players, abilities, parent/child and
stance references, locations, cooldowns and caller-supplied services retain their
relationships. Explicit replacements map platform handles to private adapters; caller
approved gameplay classes are traversed generically, without an ability list. Object,
reference and graph-resolution work are bounded. Indexed collections are populated
after their key state, with nested index dependencies checked before insertion.

This transfer is synchronous, in-process import, not a network codec or a replacement
for checkpoints. Timestamps and ability tick counters retain the source epoch. Native
handles and opaque state cells require replacements. Records, hidden callback classes,
backed collection views, collection subclasses and unhandled container implementations
also require adapters; import rejects these rather than retaining live objects or losing
their behavior. Immutable JDK lists are supported, but this is not yet coverage of every
reachable addon/service shape. The loader still needs a complete replacement policy,
frozen metadata/configuration and all shared service roots before live bootstrap.

`RollbackGraphCodec` supplies the portable form of that common object graph. Its
explicit catalog binds shared application layouts, class/enum symbols and named local
services. The wire carries indices into this catalog and a SHA-256 schema fingerprint;
it cannot request class loading or reflective members. The catalog is a compatibility
contract for shared code and addons, not a selection of abilities to simulate. Production
catalog discovery and frozen definition/content agreement still need bootstrap wiring.
Native players/worlds and opaque metadata resolve to the receiving loader's named bindings.
Fields (including ordinary finals), cycles, shared references, mutable keys, comparator
state, enum collection types, linked-map access order and distinct boxed identities survive
transfer. The decoder reuses the in-process importer's cached field access and graph/index
dependency resolver. No ability constructors, activation or load hooks run.

The binary parser bounds wire bytes, strings, nodes, references/array elements and graph
resolution work. It checks the complete detached payload, root reachability, field/array
reference types, container structure, enum indices and strict UTF-8/booleans before allocating
application objects. Unknown shapes still require explicit projections/bindings; records,
hidden closures, backed views and arbitrary collection subclasses are not serialized as
their base type. Empty enum collections require a registered enum with at least one constant.
Collection capacity/load-factor tuning is normalized; both replicas must decode the same
bytes. This does not prove deterministic iteration of every identity-hashed addon container,
or replace session code/content agreement and divergence detection.

`RollbackBendingState.encode/decode` now transfers the roster, active ability registry,
collision rules, temporary-element entries, attribute caches, managers and additional
services in that single graph. Decode checks the expected session roster and private player
bindings before returning an installable state. The existing dynamic-collision regression
also runs through actual wire encoding/decoding with separate player/world/metadata bindings,
then installs and rewinds the received graph. Source players, caches, expiry queues and
live collision scheduler remain unchanged. This component is not registered as a live
bootstrap channel: integrating portable native player state, complete service/catalog
registration, session assembly and scheduled start/presentation still remain.

`RollbackBendingState` transfers the selected roster together with the existing active
ability registry, collision rules and extra service roots. Its guarded bootstrap installs
copied online/offline bending player entries, participant temporary-element expiries,
active ability indices/ID counter/current tick, attribute caches, supported managers and the private collision manager. It
does not replay activation/removal, database or load events. Collision spatial caches
are rebuilt by normal detection; the live collision scheduler task stays outside replay.
Temporary-element ordering now uses an explicit stateless comparator so snapshots need
not traverse an opaque JDK comparator closure. Additional gameplay registries (including
addon static state), existing ability scheduled tasks,
configuration and platform services remain part of the loader bootstrap work.

Manager import uses an explicit service contract: each registered manager supplies
roster-scoped source projections before the graph transfer. Unknown managers reject
bootstrap until they provide that contract; they are never silently omitted. Ability
and service references resolve to the same private manager and nested entries. The
manager registry is swapped with the rest of the domain. Installation recreates private
tasks without invoking normal activation/listener registration. Flight grants preserve
their original owner/source, captured flight flags, timestamps and cleanup entries;
the existing cleanup runs on the private scheduler once per simulation tick. A named
comparator preserves its existing duration ordering. Foreign flight sources require a
platform replacement and cannot silently retain live players. A late-input regression
rewinds expiry and player flight flags while preserving unrelated live grants.

ProjectKorra statistics import copies participant counters/deltas and the key catalogs.
The live persistence timer is not installed; database load/save, uncaptured lookups and
unknown key creation reject replay before storage access. Known counters rewind with
the ability graph. Finalized statistics publishing and client catalog delivery remain
integration work, as do other services, manager listener policy and existing ability tasks.

Attribute caches now separate field/annotation definition metadata from mutable per-ability
values and modifier sets. Import retains definitions for future activations and copies
participant/ownerless entries in the weak instance maps; other players' entries stay out
of the private graph. Explicit source projections preserve aliases held by ability fields
or other service roots. Ordinary object-identity hash keys do not depend on their mutable
children, allowing an ability to reference the same cache in which it is a key. Modifier
sets use a named priority comparator with the existing equal-priority behavior. Attribute
recalculation, removal and rewind are tested against imported entries while live entries
and unrelated players remain unchanged. Definition annotations must remain immutable;
the loader's complete configuration/metadata policy is still required.

Import regressions cover real BendingPlayer fields, shared cooldowns, bindings, stance
and constructor ancestry, aliases/final fields, cycles, mutable hash keys, platform
replacement failures and resource/thread guards. Another fixture imports already-active
arbitrary ability classes into a domain, runs the normal progress/collision registries,
then accepts a late vertical dodge: the accelerating projectile's collision and removal
notification disappear on replay while the live players, unrelated abilities and expiry
queue remain unchanged. That fixture isolates native particle/physics services; it does
not enable a live duel or establish complete loader bootstrap coverage.

## Hit registration and latency policy

The current non-rollback path accepts client contact evidence and uses both players'
Bukkit pings to select a historical target box (`HitRewind`/`PaperPredictionInput`).
`PaperPredictionServer.augmentNearbyPlayers` can then add that target to a later real
ability query. This is different from replaying the projectile, target movement,
defences and world together; layering it onto rollback could resurrect a hit that the
replayed dodge or defence avoided. Artificial equalization also contributes to those
Bukkit ping values and therefore to the legacy rewind allowance.

Inside a rollback domain, `HitRegistrationPolicy` now selects `SIMULATION_CURRENT`
independently of element or the legacy mod flag. The Paper historical-query adapter
rejects calls from that domain. Existing `PredictedContactSync` permits changes only to
private logical targets, and `PredictionTiming` already disables transport-age offsets.
`RollbackPlayer.getPing()` now reports zero effective gameplay latency: inputs execute
on their mapped simulation tick, so shared lag compensators and ability timing code must
not compensate transport delay again. Measured RTT remains in the captured profile and
transport; live players and tab ping are not changed by this private API view. A regression
uses the existing `AbilityLagCompensator` to verify a stale overlapping collider cannot
produce a replay hit, a current collider still does, and the normal path retains rewind.

Neptune now supplies `LatencyEqualizationService.suspendForRollback(match, session)`:
it reserves the full active round roster, disables both directions, and acknowledges
queue release on every original connection. Its lease blocks the planner and
`shouldInflatePing`; reconnects invalidate readiness and partial startup failure keeps
the whole roster suspended until teardown. Tests use the actual packet handler and
Netty embedded event loops for ordered two-way draining, with failure, stale-completion,
reconnect and shutdown cases. This is a transport barrier, not client delivery or proof
that the main thread processed the released inputs. Neptune's round service requests
this lease only after the complete native bootstrap provider accepts the roster; that
provider is not yet installed.

The duel lifecycle waits for that barrier without blocking the server thread, then must
recheck it on the main thread, and negotiate the start tick with a bounded timeout. Legacy
hit claims and normal input/ability execution must be excluded for those enrolled players
outside the replay scope too. The ingress gate below now excludes legacy prediction traffic
and routed bending inputs; the loader must still suspend live native movement/ticking and
existing abilities before enrollment. Teardown/fallback must restore one consistent mode for the whole match. Do not disable
equalization globally before this integration. The server should use measured latency to
bound input acceptance/history, not to inflate pings or backdate individual hitboxes.
Rollback corrects late-input consequences; it does not eliminate information transit time
or guarantee identical visual reaction windows. Any later input-buffer/presentation-delay
tuning should be explicit and distinct from packet equalization.

`RollbackScheduler` implements the existing platform scheduler contract. Pending tasks,
task IDs, cancellations, callback state and captured action/seed context can be restored.
Zero-delay work waits for the next tick; callback failures and resource limits abort the
step. `Platform.using(...)` supplies thread-scoped simulation services. `RollbackDomain`
swaps registered shared state for one session and restores outside state on both success
and failure. All shared roots and logical platform adapters must be supplied before use;
it does not automatically make unregistered globals or asynchronous plugins safe.

`RollbackCombatRuntime` now connects the engine/domain, private scheduler and complete
`BendingManager.run()` loop. It anchors manager initialization to tick zero, runs due
callbacks before UUID-ordered input, invokes the mandatory native world adapter and
then progresses abilities, collisions, cooldowns and temporary-state cleanup. Every
engine mutation enters the domain and every step releases its output binding, including
failures. The manager, scheduler and execution adapter are checkpoint roots. Gameplay
and addon registry roots still require explicit registration during bootstrap.

`RollbackInputActions` dispatches one-shot bending edges through `CommonInputHandler`
under their deterministic action/seed context. Accepted sneak/slot transitions update
logical players. Native movement, item use, hand swapping and melee remain the native
adapter's responsibility; this helper is not an authenticated input protocol. The
runtime's input predictor must omit one-shot actions from missing frames.

Full-tick regression coverage now compares late input against an on-time run through
the real input handler, registered dynamic activation, scheduled callbacks, accelerating
ability progress, collision removal and cooldown expiry. Finalized effects occur once
and outside registries are restored between sessions. The native world tick in this
test is explicitly a fixture. No Paper/Fabric runtime bootstrap or Neptune session is
enabled by this runner; complete native simulation and network reconciliation remain.

`RollbackPlayerInput` now combines bounded continuous movement/sprint intent with an
ordered, immutable action list. Every edge preserves its own aim; the final look is
restored before movement, so turning after a click does not change that activation.
Missing frames retain held controls and omit one-shot actions. The transport must bind
authenticated connections and negotiate clock anchors before the session starts.

`RollbackInputPacket` supplies a shared, versioned, bounded binary input codec. It carries
no claimed player identity, position, damage result or client-selected random seed. The
maximum frame is 1,199 bytes; malformed versions, booleans, enum values, action shapes,
non-finite movement, truncation and trailing bytes are rejected. Action seeds derive from
the server-assigned session seed, session/player identity and action ordinal, independent
of network arrival order. Both simulations can derive them from the negotiated start data.

`RollbackSession` now owns input admission around the engine or domain-backed runtime via
`RollbackTimeline`. It identifies senders by exact authenticated connection identity and
requires the full simulation roster. A fixed negotiated client tick anchor maps input to
the server-owned timeline; ticks are rejected outside the rollback/future window without
clamping. Accepted frames cannot be rewritten. Action sequences must remain ordered across
both earlier and later accepted frames, including out-of-order arrival and empty frames.
Finalized sequence frontiers prevent old actions from being reused after receipt pruning.

Receipt state stays outside snapshots, so simulation replay cannot forget accepted network
input. Acknowledgements distinguish provisional head, finalized tick, correction revision
and the exact received tick set (which can contain gaps). Receipts are bounded by the input
window and pruned on finalization. Closing or a simulation failure closes the whole session
and releases connection references. Loader teardown must still switch every participant back
to normal gameplay together. The session itself does not perform the start handshake,
import a live fight or enable a Neptune match.

`RollbackIngress` now reserves a complete negotiated roster and authenticates input by
exact connection identity before decoding. Its per-peer packet-work budget does not
rewind. Accepted payloads feed the existing `RollbackSession`; stale sessions and rewritten
inputs retain the session's rejection rules. Stop/failure closes input for every peer but
keeps legacy gameplay blocked until the owner explicitly finishes whole-session teardown.
Overlapping rosters reject before mutation, and an old registration cannot release a new
one. Tests route late packets through the real session/engine and cover malformed input,
budgets, disconnects, callback failure, overlap and stale teardown.

Paper registers `projectkorra:rollback_input`, verifies native connection identity and
captured world identity, and supplies `enrollRollback` for the future lifecycle handoff.
It retires enrolled players' legacy prediction subscriptions/action evidence and gates
legacy claims (including claims targeting an enrolled defender), action tags/vetoes,
ready/reset messages, routed bending callbacks and legacy outgoing prediction payloads.
Reset, world change, disconnect and shutdown stop the whole registered session. A runtime
boundary test verifies those guarded entry points avoid live player access and preserve
ordinary input outside the roster. Fabric registers the same C2S payload with the common
codec; a native-buffer test checks exact byte parity and malformed/oversized rejection.

No live lifecycle calls enrollment or constructs the prepared Fabric input owner yet. The
live compatible-client/preference handshake, full private bootstrap, native movement/ability
takeover, correction presentation, and coordinated normal-mode restoration remain required.
The start and input-revision adapters below provide explicit APIs for that future owner.
Registering an input channel or
constructing a session does not establish those prerequisites or enable a live duel.

`RollbackStartNegotiation` and `RollbackClientStart` now implement the clock/start barrier
over an already prepared private snapshot. Admission requires exact authenticated peers,
matching protocol/content identities and opt-in. A supplied readiness check must revalidate
the duel and Neptune's drained latency lease throughout negotiation. The server measures
probe round trips, chooses a future server tick and corresponding client anchors, then
waits for every peer to acknowledge that exact schedule before committing. Clients start
only on their agreed tick after receiving commit; missing/late commit stays inactive.
Timeout, changed readiness, connection identity, conflicting replies and tick overflow
reject the whole attempt. Session IDs and challenges prevent stale control messages from
changing a newer negotiation. Running-session teardown belongs to ingress/runtime, not
the completed start barrier.

The clock estimate uses the round-trip midpoint with tick quantization and roughly
symmetric transit assumed. It is not an exact physical input timestamp or a guarantee of
identical reaction windows. The loader still needs pacing/drift handling. Preparation must freeze/import the
complete simulation consistently; these clock helpers do not perform native takeover.

`RollbackStartPacket` supplies direction-checked probe/reply/schedule/ack/commit/abort
messages bounded to 61 bytes. Fabric's typed payload codecs share those exact bytes. The content
identity is a validated SHA-256 string supplied by bootstrap; canonical snapshot/catalog
hash generation and transfer are still required. Tests simulate unequal RTTs and client
clock offsets, construct the existing input session on the chosen server tick, then replay
a late input through ingress. A Neptune contract test uses the real two-way packet queues:
start is rejected until both drain, and connection loss invalidates the complete attempt.

`RollbackStartServerEndpoint` and `RollbackStartClientEndpoint` now drive that exchange
through Paper's registered plugin channels and Fabric's typed receiver. They retain control
ownership after runtime handoff: a client missing commit at its local start tick sends an
abort that closes the whole server session even if the server has already started. This
does not guarantee simultaneous failure detection; cancellation itself takes network time.
The runtime must keep startup consequences provisional until coordinated readiness is known.
Both endpoints check readiness while running, bound incoming control messages per tick,
reject stale identities, start once and advance only on sequential loader ticks. Failed
cleanup retains ownership; only the owner may release it after repairing teardown.

The Paper adapter binds each original native connection and world; hello/reset, disable,
connection loss and shutdown stop its complete roster. The Fabric adapter binds the native
connection, player and world, prevents cancellation from leaking onto a replacement server,
and holds legacy runtime retries/ticks while it owns a prepared or active session. Its reset
path stops the private runtime, and an unprepared client rejects probes. Explicit bootstrap
entry points require ready Paper prediction peers and an already suspended legacy client
runtime respectively. Ordinary clients do not enter rollback merely by receiving a probe.

Queued-wire tests cover unequal RTT/clock offsets, active-session cancellation after lost
commit, readiness loss, replaced connections, partial sends, stale/malformed traffic,
timeouts, runtime errors and cleanup failures. These tests use a small simulation to verify
session orchestration, not a complete live fight. Neptune now calls the start API after
its bootstrap provider returns a prepared roster; that provider is not installed, and
no Fabric bootstrap invokes prepare yet. Full native/ability takeover, canonical private
state transfer, complete native client/server parity, live correction presentation and
coordinated normal-mode restoration are still required. Neptune's saved preference UI
is now connected to whole-round teardown.

`RollbackSession.publish()` now emits an ordered, detached input/revision stream for the
entire roster. Each publication includes contiguous simulated input frames (including the
earliest correction), the authoritative head/finalization ticks, and per-player accepted
input receipts. The owner must publish after each server tick; attempting to publish history
that has already been pruned fails the session instead of silently omitting corrections.

`RollbackClientReplica` applies that stream to the same combat runtime in replica mode.
Local input remains predicted until acknowledged or until the server finalizes its tick
without accepting it. Remote authoritative inputs replace predictions and replay all
affected movement, collisions, damage and knockback. Accepted inputs cannot be rewritten,
publication gaps and reversed history fail, and old sessions are ignored. Client history
never finalizes just because enough local ticks have passed: only server confirmation
releases irreversible effects. Exhausting unconfirmed capacity requires pacing rather
than discarding history or committing a predicted hit.

`RollbackAuthorityCodec` bounds a publication to 1 MiB and 201 input frames. The common
chunk envelope stays below Paper's plugin-message limit; Fabric's typed payload uses the
same bytes without an additional prefix. Assembly is atomic, bounded, session-scoped and
timed out if incomplete. Missing publications, changed parts and malformed input stop the
session. Paper's explicit `publishRollback` checks every original connection/world before
sending the same update to all peers. Fabric delivers complete updates to the prepared
owner, queues at most eight until the negotiated start, and stops on correction failure.
Failed cleanup continues to hold ownership while refusing further simulation updates.

These are input/revision updates, not full native state snapshots. They require matching
initial private state and deterministic client/server execution. Canonical bootstrap and
state transfer, divergence detection/repair, pacing/drift handling and rendered corrections
remain outstanding. No live duel calls these APIs yet. Native Fabric tests now route
encoded authority chunks through assembly into the replica: a late defender jump retracts
a predicted hit/knockback, while an unreceived local shot remains provisional until the
server finalizes its tick and then is removed. Finalized effects match the authority and
duplicate publications cannot apply them again.

`RollbackClientRuntime` now implements the negotiated runtime owner rather than leaving
input collection/advancement entirely to a callback. It samples held movement once per
client tick, assigns ordered action identities outside replay state, preserves each
action's packet-time aim, derives the shared action seeds, and proposes the same input
that it sends on the C2S channel. Local simulation and provisional output run before send;
receiving a correction never resends a click. The fixed negotiated tick anchor cannot
move to make a late input appear current. Full unconfirmed history waits for authority;
exceeding the bounded input window or skipping/reversing the client clock stops the
session. This is bounded pacing, not adaptive clock-drift correction.

`FabricRollbackClientRuntime` supplies actual native held input and typed packet edges.
`prepareRollbackClient` binds it to the existing start/authority endpoint and the captured
connection's registered input channel. The native send hook diverts owned gameplay input
before legacy prediction or vanilla writes, while unprepared connections and chat/control
traffic use their ordinary paths. The collector does not inspect ability names or targets'
claimed positions: activation continues through the generic existing handlers. Native
movement coordinates are not used as authoritative simulation input. Sneak edges are
deduplicated and block-interaction follow-up swings/item packets retain the existing
bending suppression rule. Failed cleanup keeps the input gate until explicit repair.

The collector currently represents the bending input protocol, not every native action.
Unrepresented melee/offhand item interactions, item drops/digging, inventory operations,
flight requests and vehicle input stop the prepared session through its cleanup callback;
they must not leak into a separate live simulation. Binding the applicable native action
services is still required before enabling duels. Packet interception alone does not freeze
vanilla player/world ticking or undo pre-send local mutations. The pre-mutation action
boundaries below cover native interaction entries and the roster-aware local player tick;
complete control/movement parity, inventory shortcuts and inbound state ownership still
need their session bindings.
Output receives provisional and finalized
updates. The player-motion presentation described below can now consume those updates;
full effect delivery and live duel startup remain unconnected.

Tests now start the actual input owner through the clock handshake, preserve input identity
across late corrections, wait at capacity without committing speculative effects, and stop
on clock, send, presentation or cleanup failure. Fabric native combat regression inputs
begin at the transformed native interactItem entry, run the generic collector/owner, and return as encoded
server corrections that retract damage/knockback. Normal unowned packet routing and typed
input semantics are covered separately. No live duel invokes `prepareRollbackClient` yet.

`FabricRollbackStarts.prepare` now acquires an exact player/world/interaction-manager
lease for actions which would otherwise mutate the visible client before packet capture.
Native item/block/entity interaction and local swing/drop entries submit typed input before
their ordinary bodies execute. Slot synchronization precedes each captured action, and
duplicate slot packets do not repeat slot-change edges. Native block-use follow-up feedback
retains the existing bending click suppression. Inventory/container, creative, digging and
other unrepresented interactions stop the prepared session before their native mutation;
this does not implement their missing replay services or ordinary item-use remainder.
Block-breaking entry wraps the existing prediction hook, so that hook cannot run first.

Successful runtime cleanup releases this lease. Failed cleanup retains it until the existing
explicit finishStop repair boundary, and an invocation that already consumed input never
falls through to native mutation even if its failure callback releases the lease. Foreign
managers/players and unprepared clients keep their ordinary behavior. Native tests execute
the transformed interaction manager and ClientPlayerEntity methods without a game window,
verify item/cursor/entity state remains unchanged, preserve action aim/slot ordering, and
cover stale connections, thread violations, failed cleanup and replacement ownership. The
dynamic projectile regression uses that same entry before predicting and retracting native
damage/knockback. No live duel activates this lease or the rollback runtime.

The roster-aware client preparation now also supplies `FabricRollbackClientControls`
and acquires `FabricRollbackNativeTick`. The exact captured ClientPlayerEntity's tick is
wrapped: its physical Input.tick runs once, while ordinary local movement, item ticking,
movement-packet generation and client-player tick effects do not run. The private combat
runtime still advances at the existing end-of-client-tick boundary. A missing/repeated or
skipped input frame stops the session rather than reusing stale input. Current mouse aim
is read independently; authority correction never polls the keyboard again. The generic
roster-free preparation API still requires its caller to supply native ticking ownership.

Sprint key and configured double-tap timing remain physical input state outside rollback.
An owned read-only native client-player probe runs Minecraft's original start/stop sprint
predicates against the latest private body's hunger, effects, pose, water/contact state,
abilities and active item. Its method bindings are compiled and its narrow private predicate
access is declared in the access widener; no live client player or movement tick is run.
Physical sprint intent survives a wait for authority, while changes from the private body
can revoke it. Borrowed input/item references are cleared after each sampling scope.

Native tests execute the transformed player tick and verify single sampling, unchanged
visible-player age, independent mouse aim, current-frame input transport, double-tap/sprint
eligibility, and stale/failed cleanup ownership. The dynamic projectile test now runs that
tick owner and the pre-mutation action entry before the actual private combat tick, then
retracts damage/knockback through encoded authority without resampling physical input.

The owned Paper server player and `FabricRollbackSimulatedPlayer` now replace the base
input phase with `RollbackMovementFactors`. The shared calculation applies the native
client's 0.98 decay, active item-use multiplier, crouching/crawling attribute and directional
normalization in the original float-operation order. Each platform reads those factors
from its private native player; native travel and collisions still execute normally. The
base decay is not applied a second time. Fabric combat execution requires this player
type so a base PlayerEntity cannot silently omit the client input factors.

The actual `ClientPlayerEntity.applyMovementSpeedFactors` method supplies the reference:
576 cases compare exact float bits across zero/diagonal inputs, active item use, passengers
and slow movement. Paper checks the same native-client reference data through its own
policy adapter. Full native player ticks on both platforms verify reduced displacement
and exact position/velocity reproduction after restoring item use and pose. These tests
cover the input phase and rewind, not complete client/server movement parity.

`FabricRollbackAutoJump` now observes the local private body's native tick and invokes the
actual client auto-jump method immediately after native movement. Its probe reads private
player state; shape contexts and collision membership retain the actual owned entity.
The private world exposes the native combined block/entity collision query with ownership
and geometry bounds checked before entry. The existing client auto-jump preference controls
this observation. Ordinary client auto-jump calls retain their original receiver.

Only the final successful simulated head contributes a pending automatic jump. The next
physical input frame consumes it once and sends the ordinary movement jump bit to the
server. Authority replay recomputes the geometry decision without polling the keyboard,
rewriting a sent input or repeating a jump while waiting on an unchanged head. The query
does not move the player or tick a visible entity. Observation is restricted to the exact
owned body, and its scope closes on failure. Stopping the runtime clears pending input.

Native geometry tests cover a full block, a low ceiling, a walkable slab, crouching and a
disabled preference. Runtime tests verify both the transmitted jump and resulting native
vertical motion, then remove the obstacle through encoded authority input and verify that
the unsent automatic jump is retracted without extra input sampling or packets. Failed and
foreign simulation cannot publish a jump. These are private-world tests, not a live duel.

The private Paper and Fabric players now also run the client push-out-of-blocks phase
before native player movement. `RollbackPushOutOfBlocks` preserves the native four-corner
order, nearest-open-side selection, tie order and immediate horizontal velocity writes.
Both adapters query native suffocation collision shapes over the player's current full
height, with the native contracted column bounds; vertical velocity remains intact.
No-clip skips this phase. This uses the current replayed terrain, including temporary walls.

Fabric invokes it before PlayerEntity.tickMovement. Paper prepends it to the copied
Player.aiStep body, retaining the existing private native event routes and ServerPlayer
tick. `RollbackNativeMethods.before` composes a validated bootstrap handle into the copied
entry, including virtual and super calls; it does not modify ordinary loaded native code.
Tests verify that entry ordering and exception handling preserve the native body boundary.

The actual native client push-out method verifies shared velocity writes and collision-query
order across 2,048 cases. Native tests on both loaders check full blocks (including head-only
obstructions), glass, slabs and no-clip, then tick the owned player and rewind its terrain,
health, position and velocity together. These tests do not establish complete native parity.

`PaperRollbackClientMovement` and `FabricRollbackClientMovement` now include the native
vertical input phase after block escape and before Player movement. `RollbackVerticalInput`
applies sneak descent in water only when the native player is affected by fluids, then adds
the flight jump/sneak force using the current native flight speed and original float operation
order. Opposing jump/sneak inputs cancel the flight force. Native travel, collision, gravity
and damping still perform the resulting 3D movement; no alternative movement solver is used.
The existing native checkpoint retains flight permission, flight state, speed and momentum.

The actual ClientPlayerEntity.tickMovement body supplies 384 reference cases for the input
forces. The test substitutes isolated reads/services and stops at the mount/base-travel
boundary, without running a real client. It compares the ordered velocity writes against
the shared phase and publishes its native values for Paper's policy adapter test. Both
loaders also tick real private players through ascent, descent and neutral flight, then
revoke/restore flight and reproduce the prior motion. Actual captured-water tests verify
sneak descent and exact momentum restoration through the native fluid tick.

Flight-mode requests now use one-shot FLIGHT_START/FLIGHT_STOP edges. The physical input
owner samples the native seven-tick double-jump window, excludes automatic jumps, honors
swimming/flight permission, and retains toggle intent while simulation waits for authority.
An advanced or corrected native body resynchronizes that intent, including a denied request
that left the flight flag unchanged. Sampling never changes the private player's flight or
velocity. Input, authority and start negotiation versions are now 3 so older peers cannot
enroll without support for these actions.

Native control events share the existing PKListener flight/glide restrictions through
CommonInputHandler and a scoped RollbackControlEvents roster. The scope spans native input,
movement and the bending loop, and is released even if execution fails. A control event in
a rollback domain without that roster fails; native-only import/component operations have
no installed bending rules. Native adapters check permission and duplicate state, dispatch captured
flight policy, and apply accepted flight/takeoff to the owned player. Paper constructs the
real PlayerToggleFlightEvent and runs its native jump body through private event routing;
Fabric requires the imported event-policy callback (unbound policy fails explicitly).
Landing requests flight stop after native movement and respects cancellation. API flight
setters retain their separate semantics. Tests cover denied and accepted takeoff, restoration
of native motion/events, ordinary listener policy, transport edges, landing cancellation,
and late input matching the on-time native combat head. These fixtures do not establish
complete external-plugin policy import or Paper/Fabric event parity.

Gliding uses one-shot GLIDE_START input. Physical sampling reads Minecraft's native
canGlide equipment/contact/effect predicate without changing the player; flight toggles
suppress the same-frame glide request, and pending intent survives authority waits. Native
ability-command packets carry the same action. Paper replays try/start/stop gliding and
the travel/climbing/wall-damage call chain with private event dispatch. Fabric's owned
player adds Paper's cancellation points around native start, stop and eligibility loss;
native glide travel, equipment wear, RNG and game-event cadence remain in their original
bodies. Its event adapter is scoped to the private world and rejects foreign bodies or
unscoped mutations. Normal players do not use that subclass or event scope.

Tests cover native glide entry/cancellation, equipment loss, climbable blocks, wall-impact
damage and exact motion restoration on both loaders. Both combat runtimes replay late glide
input to the on-time 3D head, and native automatic stops respect the existing bending flight
restriction without leaking its registry. Physical-input and transport tests verify no
premature mutation or repeated action. Full external policy import and broader native parity
remain separate requirements; these tests do not enable a live duel.

Mount controls remain unbound. Other client movement phases, input state at the
start/stop boundary, inbound/ordinary world ownership, full bootstrap and normal-mode restoration still need
completion. No live duel invokes the prepared tick owner yet.

The roster-aware `prepareRollbackClient` overload now installs `FabricRollbackPresentation`
around the separate effect sink. Each completed update captures detached player motion,
rotations, dimensions, pose, limb animation and hurt/death appearance from the exact
provisional head. It checks head/revision and the agreed native roster before publishing.
Late authority replaces this view after replay; it does not blend a discarded hit into
the corrected branch. A stale head, failed output or session teardown releases the view.

`FabricRollbackPlayerRenderer` binds that view to the exact world, connection/player
context, UUID roster and native entity IDs. Native world render extraction consumes it
after model-state extraction and before shadow extraction, so the renderer uses the
predicted XYZ position and rotations while retaining the ordinary skin/cosmetic pipeline.
Frustum bounds cover the previous/current predicted poses; chunk-readiness uses the
predicted position, and presentation light is sampled there. The bridge never moves a
live client entity or modifies its server interpolation. A scoped world-render hook keeps
inventory previews and other UI passes unchanged, including when extraction throws.
The lease rejects competing sessions, foreign entities/worlds, reversed revisions and
changed identities, and clears when its captured client context stops matching.

Native tests execute the actual transformed render-extraction method and prove the
predicted state reaches shadow extraction, corrections replace the displayed pose,
and the visible source entity remains unchanged. The existing dynamic collision test
now routes its real input/encoded authority corrections through this presentation:
provisional native damage becomes visible and a late dodge retracts its hurt appearance
and displayed movement. Cleanup, stale updates, foreign rosters and ordinary UI/world
boundaries have additional tests. These are headless extraction tests, not graphical
verification of a live duel.

The prepared roster-aware presentation now binds the exact main game camera before its
first publication. Camera updates consume detached native lastX/Y/Z history separately
from the model's lastRenderX/Y/Z history, so the camera follows predicted and corrected
movement without moving the visible entity. Local mouse aim remains immediate; a spectated
roster member instead uses the captured native camera rotation (including head yaw).
Eye height interpolates between consecutive published poses, with acknowledgement-only
updates retaining that transition and revised/skipped heads discarding the old transition.
This is pose interpolation, not the vanilla camera's exponential eye-height smoothing.
Captured scale/camera distance feeds native third-person clipping after the predicted eye
position is installed. Front view and native collision offsets remain native; old live
vehicle interpolation cannot offset the captured world-space position a second time.

Headless tests execute the transformed native Camera.update: predicted first-person
position, corrected crouch height, immediate aim, spectated head rotation, foreign camera/
world/entity identity, stale connection and exception cleanup are covered. Four third-person
cases compare both the eight native clip rays and the resulting pose against an ordinary
camera at the same eye position, with front/back view and obstructed/unobstructed rays.
No game window, live server or live duel was used for these checks.

This is the player motion/pose and camera path, not complete presentation ownership.
Native player/world ticking and input takeover, HUD, equipment/item-use and remaining model
animations, hurt-camera effects, predicted ability/entity/block visuals and finalized effect
delivery still need their session bindings. Camera collision currently queries the visible
client world; predicted terrain presentation remains outstanding. The complete native
world/bootstrap and client Paper policy parity also remain prerequisites for enabling live duels.

`RollbackPlayerExecution` connects those frames to the owned native players and the
existing input handlers inside `RollbackCombatRuntime`. Paper and Fabric now share this
driver; their subclasses supply native movement input and player ticks. They require movement, controls,
combat and inventory to share the same native source, applies every participant once,
ticks players in UUID order, and then runs the supplied remaining-world service before
the bending manager. Action sequence frontiers rewind with player state; repeated or
backward actions reject before activation. Native action remainders receive the common
handler's cancellation and the same deterministic action context. Private time, effect
routing and remaining native interactions are mandatory session services, not live fallbacks.

Native integration tests now run full ServerPlayer ticks and native damage in the same
runtime as dynamic activation, accelerating ability progression and the existing collision
manager. A provisional hit damages the defender; a late sideways dodge or jump replays
the actual movement, retracts that hit/knockback and matches on-time state and finalized
output frames. Tests also cover distinct per-action aim, no repeated clicks on predicted
frames, duplicate action rejection, mixed-world/source rejection and cleanup on failure.
The abilities, captured arena and service policy are explicit fixtures. This verifies the
combined execution path, not a live FireBlast duel or a complete production bootstrap.
The late-dodge/jump integration scenarios now enter through encoded/decoded input packets
and the authenticated session boundary, with distinct client tick anchors. Replaying keeps
previous receipts; an attempted input rewrite is rejected without aborting the duel.

`FabricRollbackExecution` now runs its owned native `PlayerEntity` bodies through that
same complete bending tick. Fabric regressions likewise retract provisional damage and
knockback after a late strafe/jump, preserve per-action aim and accepted input identity,
and reject repeated clicks or mixed native sources. A shared 42-frame Paper reference
covers both players over seven ticks for a hit, strafing dodge and jumping dodge. Both
loaders check exact position, velocity, bounds, height, contact/fall state, velocity-update
flag, health and age against the same file. This exposed and fixed Fabric's mapping of
`velocityChanged`: Paper's `hurtMarked` corresponds to Yarn's `knockedBack`, not the
separate `velocityDirty`/`hasImpulse` field. Sprint particle calls now execute the native
server world's empty body, preserving preceding native random consumption.

Those scenarios verify the exercised movement/damage path, not all Paper/Fabric policy.
Fabric still uses native player bodies rather than the complete private ServerPlayer
maintenance implemented on Paper. Inventory maintenance, statistics/advancements and
captured Paper event/configuration behavior still need matching client services. The
adapter is not wired into a live client session, and does not replace bootstrap/state
transfer, native input takeover or correction presentation.

`PredictionServices` now isolates the existing prediction listeners within each domain.
Removal, checkpoints, cooldowns, player status, flight/gliding, velocity, direct blocks,
TempBlocks, falling-block ownership and contact hooks resolve private bindings rather
than invoking the globally installed Paper/Fabric listeners. Bindings are explicit:
unconfigured hooks abort, and disabling a hook requires a declared alternative output
path. Bound mutable service state participates in the domain snapshot. Private execution
cannot install or clear global hooks, and scoped publication failures propagate instead
of being logged and discarded. Existing gameplay outside the scope keeps its listeners.

Inside a domain, both loaders may resolve provisional combat state. The old local-only
client contact suppression is bypassed only for logical entity views; leaked native
targets/owners are rejected. Legacy input cooldown veto/leniency and duration age
compensation do not alter the already replayed timeline. Region snapshot lookup also
uses a scoped binding. This does not supply the remaining live region/plugin policy.
Regression coverage runs real ability progression, registered collisions, DamageHandler
and GeneralMethods velocity dispatch with a predicting-client listener installed outside
the domain. Late defence input retracts health, velocity, removal and private receipts
together, while live listeners remain untouched. The final native damage rule in this
test is explicitly a fixture; native damage behavior and full runtime root/service
registration are still required before enabling a duel.

Time and random calls in the shared core and bundled addons now use `RollbackClock`
and `RollbackRandom`. Outside simulation, clocks use ordinary system time. Explicitly
seeded streams retain Java Random sequences, including Gaussian caching. During replay,
simulation time, random stream state, shared random choices and generated gameplay IDs
are repeatable. Native monotonic timers retain a separate anchor. Diagnostic timing and
server metrics continue to use real time. External addon code requires the same clock,
random and state contracts; direct wall-clock/native access is not transparently rewritten.

Regression coverage includes the real `CoreAbility.progressAll()` and
`CollisionManager.detectCollisions()` pipeline with dynamically registered classes,
changing speeds/radii, collision callbacks and removal. Additional tests cover delayed
and out-of-order input, irreversible effects, scheduler replay, state graph aliasing and
interleaved session isolation. These are not live server/client acceptance tests.

`prediction.rollback.world.RollbackBlockStore` now provides stable logical `Block`
views over a captured arena seed and rewindable changes. It retains typed block data,
opaque block-entity snapshots, saved `BlockState` values and deterministic change order.
Shape and physics callbacks read provisional neighbor cells. Out-of-bounds reads abort
instead of consulting the live world. Real `TempBlock` stacking, expiry and restoration
are covered against this store, including aborting a failed replay callback.

`PaperRollbackTerrainCapture` now imports a bounded region through typed native reads
on the server tick thread, before replay. It preflights already-loaded chunks and a
biome-query halo; no chunk generation/loading or block-entity creation is requested.
Existing block entities serialize through their native codec, while packed block-entity
tags are read without unpacking them into the live world. Capture includes exact block
properties, detached block-entity bytes, light, temperature/humidity, and both smoothed
and noise-biome registry keys. The common biome projection retains existing BukkitMC
semantics; native keys are preserved independently. Cell/chunk/storage/tile budgets and
complete-region validation reject partial or oversized captures.

`RollbackTerrainSeed` stores the immutable region as a palette plus a dense index array.
Repeated cells share a detached value; a separate overlay still handles all provisional
changes and rewind. Both RollbackBlockStore and RollbackWorld accept this seed without
expanding it into a map entry for every coordinate. Native capture tests use the production
entry point with read-only world fixtures and real native states/entities. They verify
source mutation cannot alter the seed, packed tiles are not unpacked, missing chunks/tiles
abort, and imported slab geometry restores after provisional removal. Palette identity now
includes mutable common block properties, so two cells with the same exact base but
different level, snow or fire-face edits cannot be merged. Palette comparison covers the
detached facade types produced by the native capture adapters.

`RollbackTerrainCodec` supplies the shared arena component of initial state transfer.
It preserves region bounds, the complete Y/Z/X cell order, material and full native state,
opaque block-entity bytes, both biome keys, light and climate values. Palette runs compact
uniform regions; separate wire, cell, palette, decoded-storage, string and tile budgets bound
work and allocation. It validates the entire detached payload, including run totals/order,
strict UTF-8 and trailing data, before calling native decoders or allocating the region index.
Native registry decoding rejects missing blocks and material/state mismatches.

Paper and Fabric terrain adapters apply mutable common properties over the exact native
base and reconstruct the typed common facade on import. The shared Paper-generated
`terrain.base64` fixture is read by both loaders. It verifies a top slab's collision shape,
fire age/faces, existing and packed chest NBT, biome keys and restored provisional geometry.
The cross-loader check exposed and fixed Fabric discarding the exact base for typed data:
its rollback decoder now preserves unexposed properties such as fire age while applying
facade edits. Ordinary Fabric block-data conversion retains its existing entry-point behavior.

This is a portable terrain component, not a complete session bootstrap. Full world
conditions/policy, native tile evolution, player/bending state transport, scheduled bootstrap
chunk delivery, content negotiation and live startup still require integration. No network
receiver installs this terrain into a live duel yet.

`PaperRollbackPlayerSeed` now captures intrinsic native player state and constructs a
fresh owned ServerPlayer from it. It imports actual position/rotation/collision boxes,
pose/eye height, velocity, collision/fluid history, movement/jump timers, damage immunity,
health/scaling, attributes (permanent and transient modifiers), potion effects including
hidden effects, food/regeneration timers, controls, experience, and native item cooldowns.
Tracked entity data is detached through native serializers. The fixed private-field
bridge resolves MethodHandles once at bootstrap; per-capture operations do no reflective
field discovery. No live connection, world, mutable player, RNG or item reference enters
the seed. A supplied session seed initializes the replica's private RNG.

Inventory/equipment and ender-chest items use the existing full component codec with an
aggregate byte budget. The seed preserves item pop timers and reference sharing between
slots, the active-use item, and native equipment caches. Importing the same seed twice
creates independent mutable objects. Nanosecond combat timers are rebased to the session
clock; Paper's zero last-jump timestamp is rebased too so a zero-based session still allows
the first sprint-jump impulse. Absolute world-tick values require matching world/player
capture ticks. Duplicate UUID/entity IDs reject before player construction. The replica
is explicitly marked valid for Paper's private movement tick without spawning it.

The native suite verifies source mutation cannot change captured components, hidden
effects or modifiers; active-use aliases refer to the copied inventory; crouching retains
the real collision box/eye height; continuing native movement matches the source; and
rewinding repeats both movement and captured outputs without touching the source. It also
covers the zero-clock first jump, duplicate identity, wrong tick/thread and replay guards.

The player's native value-field component now has a shared `RollbackPlayerValues` wire
format. `PaperRollbackPlayerSeed.values()` exports all 149 fields already captured in
the entity/living/player/server/food/walk/health groups. Scalars, exact vectors/boxes,
block/chunk positions, nullable references, optional supporting blocks, enums and entity
dimensions (including every attachment point) use detached common values. The codec
bounds field count, wire bytes, names and attachment counts; rejects nonfinite values,
invalid types/booleans/order and trailing data; and retains exact float/double values.

Paper can stage and apply this component to an owned replica. Fabric's fixed semantic
key table binds 117 corresponding native fields once through the 1.21.11 intermediary
mappings, then uses cached handles. Every expected key/type and required nonnull field
is checked before native application. The other 32 Paper/server fields are explicitly
returned as retained values for the still-required policy/maintenance adapter. They are
not silently renamed to unrelated client fields, and retaining them is not implementation
of their simulation behavior. No packet can select a reflection member or target a live
player. The mapping table comes from the paired Mojang/Yarn 1.21.11 mappings, with Paper
extension types checked against the paperweight development bundle.

The shared `player-values.base64` fixture comes from production Paper seed capture. Both
loaders verify byte-identical value transfer; Fabric applies it to a real owned player,
checks motion/damage timers and both velocity flags, then verifies native checkpoint
restore returns the entire component to the same bytes. Attachment maps are immutable
native values rather than backed collection views, so they remain checkpoint-compatible.
The nullable-field contract also accepts a null swing hand, which occurs on a native
player that is not swinging.

`RollbackPlayerVitals` transfers the player's complete native tracked data, potion effects
(including hidden effect chains), and allocated attributes with their permanent/temporary
modifier distinction. The Paper seed uses this same component for its private imports.
Both adapters stage the complete component before applying it to an owned player: tracked
field IDs and expected serializers are checked before decoding their values, effect NBT
has byte/allocation/depth budgets, and every attribute registry key must resolve. Import
initializes entries directly without normal pose/effect/plugin callbacks. Fabric preserves
Paper's registered attributes that are outside the default player attribute supplier.

The shared `player-vitals.base64` fixture comes from a real Paper seed. Fabric compares
tracked bytes, attribute values and decoded effect NBT, then checks that mutations and
checkpoint restore return its entire captured component to the original bytes. NBT compound
key ordering can differ between loaders, so cross-loader effect equality is structural.
Malformed serializers, effects and unknown attributes reject before the native target is
modified. Repeated imports produce independent mutable effects and attribute instances.

`RollbackPlayerItems` now transports the full native component data for inventory and
ender-chest stacks, pop timers, selected slot, the inventory stack limit, active-use/last
main-hand/spin-attack item references, equipment caches, and native cooldown groups with
their original start/end ticks and current counter. An identity table preserves shared
nonempty stacks across those roots and keeps equal but distinct stacks separate. Item
data uses the existing native codec (including its canonical empty-slot representation),
with aggregate item bytes, object/root counts and wire-size bounds. Unknown roots and
malformed native data reject before modifying the owned target.

The Paper player seed uses this component for its own imports. Fabric uses native
inventory APIs and fixed accessors; its package-private cooldown record constructor and
fields are bound once through the fixed intermediary schema. No normal item-use,
equipment or cooldown callbacks run during import. Paper's custom inventory stack limit
is applied only to the private inventory; ordinary Fabric inventories keep the vanilla
limit of 64. The limit participates in the existing native checkpoint.

The actual Paper `player-items.base64` fixture covers shared inventory/ender-chest/use
references, two identical but distinct swords, equipment caches, armor, custom names,
damage, pop timers, a custom inventory limit and both item/default and custom cooldown
groups. Fabric compares native item equality and every alias/root, checks 80% remaining
cooldown without restarting it, and restores the complete component after item mutations,
inventory-limit changes and cooldown expiry. Malformed items, slots and layout changes
reject before writes; repeated imports create independent native items.

`RollbackPlayerContext` transports the remaining detached control/contact component:
native ability flags and flight/walk speeds, the last received seven input flags, known
client movement, entity tags, Paper collision-exemption UUIDs, fluid-height and eye-fluid
tag caches, piston movement deltas, and relative nanosecond jump/eating timestamps.
The codec bounds collections, UTF-8 strings and total bytes; validates flags, finite
values and identifier syntax; and rejects duplicate/out-of-order entries and trailing
data. Its optional eating offset preserves the native inactive `-1` sentinel. Jump time
zero is a real timestamp and is rebased, preserving the zero-clock first sprint jump.
Overflow or an active eating timestamp rebasing to `-1` rejects before writes, and full
Paper player construction checks these clocks before registering a native body.

`PaperRollbackPlayerContextData` and `FabricRollbackPlayerContextData` import this data;
the existing `*RollbackPlayerContext` collision-query adapters retain their separate role.
Paper's seed uses the new component for its own imports. Fabric directly imports native
abilities, tags, fluid caches and piston deltas. Its ServerPlayer-only input/movement,
collision policy and rebased Paper timers are explicitly retained in the owned state and
restored by the native checkpoint; this does not yet implement their Paper simulation
behavior. The policy/maintenance adapter must consume and evolve them before live use.
No import invokes normal ability-update, movement, fluid-detection or plugin callbacks.

The shared `player-context.base64` fixture is a real Paper capture. Both loaders verify
identical bytes, actual native flight/fluid state and complete checkpoint restoration;
Fabric also mutates and restores the retained policy alongside native fields. Tests cover
Unicode tags, custom fluid-tag caches, independent imports, clock overflow and sentinel
collisions without partial updates. The existing zero-clock sprint-jump test still passes.

These are components of full native player transport. Service seeds, registry agreement
and remaining Paper-specific simulation behavior must accompany them
before live bootstrap. Cross-component item aliases also require the complete session
assembly. No live duel invokes this transport or activates the prepared player-motion renderer yet.

`PaperRollbackRosterSeed` captures a cohort at one world tick and instantiates it in two
phases: construct all owned players, then bind their native combat relationships.
`PaperRollbackCombatSeed` detaches last-attacker/last-target references, explosion causes,
kinetic contact cooldowns, native combat history and its timeout counters. Damage sources
retain their registry type, direct/causing/event-damager identity, optional stored position,
known Bukkit cause and critical flag. Shared damage-source references remain shared within
one imported player; each imported cohort owns independent mutable instances. No source
entity or live DamageSource survives in the seed. Private UUID lookups resolve only owned,
nonremoved players and never consult the live server or other dimensions.

Before creating any player, roster import checks exact service membership, capacity,
existing identities, world tick, all referenced UUIDs and native damage types. A missing
combat participant aborts without leaving a partially populated roster. Native tests
capture actual player damage in both directions, mutate the source, and verify kill
credit, contact cooldowns, shared damage-source identity, historical position/cause/critical
metadata and combat-history expiry on the copies. A stronger hit uses the imported damage
immunity state; rewinding repeats its health and output changes without affecting the
source players. Repeated imports and uncached EntityReference lookup are also covered.

`RollbackPlayerCombatData` now carries that combat component across loaders as a bounded,
versioned value format. UUID links target the imported roster, and source indices preserve
shared damage-source identity within each player's history, including distinct sources
with equal values. `PaperRollbackCombatSeed` and `FabricRollbackPlayerCombatData` validate
the full supplied roster and stage all damage types and references before applying any
player's history. A missing participant, mixed private worlds, duplicate native identity
or invalid damage type on a later player rejects without changing earlier players.

The shared `player-combat-a.base64` and `player-combat-b.base64` fixtures are captures of
actual Paper damage. Both loaders import/re-export identical bytes. Fabric tests resolve
uncached attacker UUIDs only through its private roster, preserve source aliases and contact
cooldowns, apply another native hit, then restore the imported history and health through
a checkpoint. Removed and unowned players never resolve; duplicate roster identities are
rejected. Native combat timeout expiry is also exercised. Fabric retains Paper's known
damage cause, critical flag, event damager and explosion cause, but the corresponding
Paper event-policy behavior is still pending. Carrying those values does not implement it.

`RollbackRosterData` combines identities, values, vitals, item graphs, context and combat
history for the complete player cohort at one world tick. Its bounded versioned format
preserves profile properties (including signatures and duplicate multimap entries), native
entity IDs, game modes and client settings. It includes the agreed private RNG seeds used
by both native imports; these initialize a new simulation and do not serialize a running
native RNG. The existing native checkpoints capture and rewind the running RNG separately.
The complete payload has a 16 MiB budget as well as each component's independent limits.

`PaperRollbackRosterSeed.portable` uses the same services/seed membership as its server
import. Paper's private copies now preserve the captured GameProfile properties too.
`FabricRollbackRoster.instantiate` checks the agreed session membership, world tick,
value schema and rebased clocks, constructs every native player, applies their components,
then rebinds combat relationships and seals the roster. It creates a new candidate private
world and only returns the completed cohort; failed native decoding cannot publish a
partly populated roster or mutate an existing one. The supplied world services must also
be private. Client preferences and Paper-only fields remain explicit imported metadata.

The actual Paper `player-roster.base64` capture is imported by both loaders. It covers
profiles, distinct game modes, shared inventory/ender-chest item identity and damage
history. Fabric checks all components against the capture, then rewinds native damage,
position/velocity, item mutation and RNG together. NBT compound ordering is normalized
only when comparing across loaders; each loader's checkpoint returns its exact bytes.
This connects the intrinsic player components, not the full session: service/world data,
bending-graph bindings, Paper maintenance/policy, network delivery and presentation remain.

This remains player/native-combat import, not complete live player/session bootstrap.
Statistics and advancement seeds are supplied separately. Non-player combat sources,
block/custom damage sources, mounts, pending world interactions, active location effects
and open menu transactions still require their world/entity import paths and currently
reject rather than being cleared. Source Bukkit last-damage event history is not yet
imported; its plugin modifier callbacks need an explicit policy instead of retaining live
closures. Import failures discard the candidate private world. ProjectKorra bending/ability
state, logical profile/permissions, full world services, client initial-state transfer and
Neptune startup still need integration; no live duel invokes these importers yet.

Paper's default geometry decoder now uses the native block registry directly. Common
mutable properties are applied over the exact captured base, preserving other native
properties (such as fire age when changing a fire face). It needs no live Bukkit factory
and validates material/state agreement. The capture regression uses this production decoder
with Bukkit uninstalled, alongside the existing native geometry tests.

`PaperRollbackGeometry` and `FabricRollbackGeometry` query the native block shape
implementations through logical block getters. Native tests cover slabs, stairs and
neighbor-dependent fluid volumes. Both adapters use the native uncached fluid bounds:
the flowing-fluid shape cache otherwise retains a height calculated before rollback.
Native ray traversal and clipping also read the logical terrain, preserving outline vs
collision shapes, multipart geometry, source-only fluid selection, hit faces and rays
starting inside blocks. Queries have a traversal budget and abort if they reach terrain
outside the captured region. Paper and Fabric fixtures exercise the same rays before
and after terrain restoration. These methods are ready for the logical World adapter;
they do not yet replace queries made by live world wrappers.
These shape adapters do not implement native block physics, player movement, damage,
block-entity evolution or world/entity effect delivery; those remain integration work.

`FabricRollbackWorldAccess` provides an audited native `ServerWorld` boundary for a
private `CollisionView`: terrain/fluid reads, collision shapes, space checks, supporting
blocks, weather, environment attributes and the captured border delegate to supplied
checkpointed queries. Audited native precipitation and loaded-region methods retain
their actual implementations. Query implementations must supply captured terrain,
entity candidates, scoreboard state and environment conditions without live fallbacks.
Unbound calls still fail; this is not a complete native world.
The native query shell binds argument-taking methods through compiled probes so Fabric
remapping preserves method identity without string-based native names. JVM descriptor
matching also intercepts synthetic covariant methods whose remapped bridge flag is
missing. Interface and concrete descriptors share the actual override's binding.
Explicitly audited native bodies may run after a platform guard; their virtual
dependencies remain guarded, while direct fields and static calls still need an audit.

`PaperRollbackWorldAccess` supplies the corresponding private `ServerLevel` read
boundary. Paper's final loaded-block methods and Moonrise's direct chunk-section
palette reads delegate to current logical terrain. Derived section views retain
conservative collision metadata so terrain rewinds cannot leave stale air/shape
optimizations. Entity queries preserve the caller predicate and reject foreign-world
entities; the native query shell also supports void queries filling a result list.
The native suite checks restored palette/loaded-block reads and real `Player.travel()`
ground friction/gravity on an isolated TickThread, without starting a server.

`PaperRollbackNativePlayerState` now owns a private CraftPlayer identity wrapper, so
native horizontal contact can perform its Bukkit player/vehicle type check without
acquiring a live CraftServer. Unknown wrapper calls still fail. The replica uses its
own native RNG instead of Paper's shared entity RNG. Native tests restore transient
movement, inventory components, effect state, attributes and Gaussian RNG state, and
compare on-time versus delayed movement input through actual native wall contact and
finalized outputs. Effect state is seeded as imported state in this fixture; native
Bukkit potion application/event dispatch is not covered by that checkpoint test.

`RollbackNativeMethods` can now execute selected native method bodies in private
hidden classes with explicit call substitutions. Original loaded classes are not
changed and no agent is installed. Native private fields, helper calls, ordinary
lambdas, exception handling and super calls retain their implementations. A separate
native class loader need not see the plugin classes. Substituted method references
inside bootstrap arguments are rejected rather than silently bypassing the route.
Audited canonical constructor expressions can use private factories. Copied virtual
calls select configured overrides, reject unknown overrides, and preserve exact
super calls; inherited protected fields retain native access through cached handles.
Explicit dispatch declarations also route calls through abstract/native base APIs
to their audited overrides. Neither an unbound override nor an uncopied base body is
authorized by that declaration. Paper uses this for Entity-level damage and void
callbacks, so ticking cannot bypass the private LivingEntity/Player damage route.
This is an audited adapter mechanism, not automatic isolation of arbitrary methods:
uncopied calls, constructors, static state and concrete replica overrides still need
review and explicit services.

`PaperRollbackNativeEvents` uses this mechanism for private damage, resurrection and
potion-event delivery, knockback and exhaustion factories. Native knockback tests preserve Bukkit/Paper event
ordering, handler-modified velocity, cancellation and rewind of player/listener state
without installing a Bukkit server. Foreign event entities/sources and handler failures
abort. The private player wrapper now supplies detached velocity reads and checkpointed
last-damage-event storage.

`PaperRollbackCombatAccess` now executes the actual nonlethal Player/LivingEntity
damage bodies with private native damage-source construction, captured game rules,
registry lookups, randomness and provisional sound/status/damage output. Native
equipment wear and break callbacks retain their original ordering and event route.
Tests restore health, immunity, knockback, attacker references, Bukkit damage cause,
armor protection and durability together. Late damage cancellation retracts the hit;
late equipment cancellation restores a broken item and retracts its break status
while preserving the hit. Both histories produce matching finalized output.

The native death-protection path now runs too, including hand selection, item
consumption, Paper's cancellable resurrection event and its no-item default when a
private listener uncancels the event. `PaperRollbackStatusEffects` binds the real
death-protection component and native consume-effect dispatch: applying/removing/
clearing effects, native attribute changes, probability rolls and sound requests.
It converts Bukkit potion views against the captured registry while preserving
Paper's native effect event construction and cancellation. This applies to component
data on any item, without selecting abilities or identifying totem items in adapter code.
The private CraftServer identity is never initialized or published; its audited
plugin-manager getter is redirected to the same private event route.

Native tests restore consumed items, effect instances, absorption/attribute modifiers,
events and status/sound output together. Late potion cancellation retracts provisional
effects and modifiers while keeping the successful resurrection and matching on-time
finalized output. Tests also cover component effects on an ordinary item, clearing
existing effects, event cancellation/uncancellation and invulnerability-bypassing damage.
Copied interface calls now honor inherited defaults and reject unbound overrides.

Deaths/drops/spawns, random-teleport consume effects, block-source event adapters
and live plugin event integration remain unfinished.
Unaudited consume overrides and actual deaths abort; callers must restore the failed
step through the engine/domain. Native player/effect ticking is described below;
rollback duels are not enabled yet.

`PaperRollbackAttributes` now supplies Bukkit attribute access for private native
players, including the health-attribute queries used by death events. Reads, base
changes and permanent/transient modifiers delegate to CraftBukkit's native attribute
implementation. Retained views resolve the restored instance on each operation;
rewinding before a lazily created attribute cannot leave a listener writing into a
discarded instance. Views participate as state cells referencing their owning player,
and reject cross-thread access or foreign registry holders. Native tests cover those
cases and restoration through a listener retaining the view as its checkpoint root.

Neptune's normal duel path intercepts lethal damage in `MatchListener.onDamage`, calls
`Match.onDeath`, resets health and cancels vanilla damage. That decision must be simulated
privately and its match side effects finalized later. A complete vanilla death/drop system
is not a prerequisite for that path. When a supported duel configuration actually reaches
vanilla death (including the existing totem exception), it must use `ServerPlayer.die`;
the base `Player.die` path is not a substitute for Paper's event and connection behavior.

Neptune now has `RollbackRound`, a detached round-state adapter for provisional defeats,
attacker credit, eliminated participants and surviving sides. Its lethal-hit predicate is
shared with the ordinary match listener; no ability names or projectile rules are involved.
Checkpoints can retract a provisional defeat, while the irreversible confirmed frontier
delivers each defeat once. Restoring a contradictory finalized branch, publishing reentrantly,
or retrying after a partial delivery failure is rejected. Tests cover late-hit retraction,
team elimination, attribution, cancellation/totem handling and delivery failure.

`PaperRollbackRoundEvents`, installed through `PaperRollbackWorldAccess.bindRound`,
connects private Bukkit damage events to that round state after
captured damage modifiers and cancellation. It checks exact replica identity for victims
and causing/direct players, reads the current private hand items for the ordinary totem
exception, and applies Neptune's health reset/cancellation to a provisional defeat.
It is not a global listener and never calls live `Match.onDeath`. The native Bukkit player
adapter supplies the two hand reads and Paper's positive-health reset with its original
validation; zero health still rejects the unaudited vanilla death path. Inventory, health,
queued packets and round decisions restore together through the session checkpoint.

Cross-project tests use freshly compiled Neptune classes with the native Paper test
runtime. They cover a reversible lethal hit, upstream damage modifiers/cancellation,
both totem hands, foreign replicas, a late correction retracting a defeat and a retained
defeat finalizing once even after subsequent replay. The collision outcome in those tests
is an explicit fixture, not proof of complete 3D movement or ability replay. Neither adapter
creates a live session yet; runtime bootstrap and confirmed-result delivery remain required.

`PaperRollbackNativePlayerState.serverPlayer` now runs the real ServerPlayer constructor
against owned constructor-service boundaries. No live server, player list, statistics
file or socket connection is constructed or retained. The private CraftPlayer health bridge
now runs native scaling and update methods, and snapshots restore native inventory,
food, client options, game-mode state and RNG alongside health. Paper's container
component-hash cache is derived state: its exact owned identity is excluded from graph
traversal and its entries are cleared on restore, including hashes of discarded mutable
components. The accessor for that private native cache is discovered once and called
through a cached typed MethodHandle. Immutable locale and Adventure decoration metadata
are retained by identity; other player fields still participate in capture.

Native tests cover actual ServerPlayer construction/rewind, repeated cache restoration,
foreign checkpoints, closed rosters, and rejection of a foreign connection. Text-filtering
and other unimplemented service actions fail explicitly.

`PaperRollbackConnection` now supplies an owned ServerGamePacketListenerImpl boundary.
Audited combat packets are immediately encoded with the captured registry into detached,
bounded outputs; no packet or mutable attribute collection is retained and no socket is
opened. The compiled play-protocol codec is bound once per private connection. Health,
attributes, hurt animation, effects, sound, velocity, experience, damage and combat
entry/exit codecs are audited. Other packets fail before encoding: item codecs still
reach Paper's live-server sanitizer, so they cannot be treated as private automatically.
Disconnects, teleports, arbitrary protocol changes and completion callbacks still require
separate lifecycle/finalization adapters. These outputs are not sent to real clients yet.

The damage entry now runs the actual ServerPlayer.hurtServer body, including native
client-loaded, PvP, team and cramming checks; health-update queuing; statistics and
advancement triggers; knockback and totem resurrection. Client-loaded state, captured
world policy, Bukkit health/scale fields, native queued packets and emitted outputs rewind
together. Actual deaths remain explicitly rejected, with the engine responsible for
restoring the failed step. Native team reads use audited replacements over private final
CraftScoreboard identity tokens, never a global scoreboard manager.

Tests cover two-player hits, cancelled damage, friendly-fire and per-player board
assignment, client loading, PvP/cramming policy, scaled health, queued health updates,
totem inventory/effects, discarded packet output, native wire decoding and foreign or
unimplemented connection operations. Late-input engine replay is compared against an
on-time replica with the same imported pose and IDs, including exactly-once finalized
packet effects. Complete state import, death/drop services, remaining world/item
tick services, network delivery and the live session remain required.

`PaperRollbackStatistics` supplies the private ServerStatsCounter used by those players.
It runs Paper's actual increment/event/cancellation and set-value bodies, with imported
counter values, forced values and the disable-saving policy captured at construction.
Audited direct field reads in copied native methods can now resolve private getters;
the original loaded fields remain unchanged, and copied writes to substituted fields
are rejected. This keeps later global Spigot configuration changes out of replay.
Counters and pending-update membership rewind with their owning player, without native
statistics-file construction or disk writes. Native event factories retain Paper's
high-frequency event exclusions, value saturation and custom/item/block/entity mapping;
entity-type conversion uses frozen registry/enum metadata without a global Bukkit lookup.
The private dispatcher validates PlayerEvent owners as well as EntityEvent owners.

Tests cover cancellation and replay, imported/forced/disabled values, all four statistic
categories, saturation, pending-update restoration, foreign players, cross-thread calls
and rejected saving/network delivery. Statistic packet delivery and finalized persistence
remain unimplemented.

`PaperRollbackAdvancements` owns each ServerPlayer's actual native tracker, imported
criterion dates, advancement tree, progress, pending visibility changes and listener
membership. Definitions are detached through native registry-aware codecs; mutable
display data remains captured. It runs Paper's grant/revoke/listener bodies and uses
simulation time for criterion dates. Native criterion cancellation and completion events
use the same private dispatcher as combat. Retained Bukkit progress views refer back
to the owning scene, and only definitions from that player's captured catalogue are accepted.

Native reward bodies now execute experience grants, including level-up sound output,
against owned players. Completion announcements preserve event edits and the captured
game rule, serialize with the session registry, and buffer detached messages. Explicitly
audited synthetic lambda bodies in copied native methods route through captured typed
handles, including when native classes cannot see the plugin loader. Ordinary method
references to substituted methods still fail unless separately supported; serializable
lambda factories are not accepted by this route.

Tests exercise real native tick/location triggers, multiple criteria, cancellation,
import without repeated rewards, deterministic dates, experience, level-up sound,
original/edited/suppressed announcements, mutable display isolation and identical replay.
An unsupported recipe reward aborts and the entire player/advancement/output checkpoint
can be restored. General predicate services, loot/recipe/function rewards, login-time
automatic grants, visibility packets, persistence and live event delivery remain required.
No live server or global Bukkit registry is accessed by the covered advancement paths.

`PaperRollbackScoreboards` now owns native ServerScoreboard instances and tracks their
objectives, scores, display slots, teams and criterion-registration order. It runs the
native base storage operations while buffering detached objective/score/team/display
updates instead of invoking live scoreboard recipients or saving world data. The public
board view refers back to its owning world; retaining that view as the sole snapshot
root still restores player statistics, boards and buffered outputs together. Boards
created on discarded branches become invalid, including when their numeric ID is reused.
Canonical output sorts team member names and encodes native packed display data against
the session registry. Shared frozen native metadata keeps populated score hover data
out of live registry traversal while retaining mutable display components in snapshots.

The private native method routes now execute ServerPlayer.awardStat/resetStat with
Paper's ordering: a cancelled statistic increment still updates matching tracked score
objectives, and an explicit reset updates both without an increment event. Untracked
boards remain unaffected. Native entity team queries see the private main board, and the
relevant Paper scoreboard setting is supplied by the checkpointed combat service.
Tests cover those operations, populated-score snapshots, teams, display slots, objective
removal, registration restoration, discarded boards, foreign objects and threads, and
replay producing identical buffered updates. Native PvP now respects captured per-player
board assignments and team flags; assignments emit detached changes and rewind with the
world. Full scoreboard recipient synchronization,
waypoint updates, full Bukkit scoreboard API routing, initial-state transport and finalized
network/persistence delivery remain required.

Both native world adapters keep a fixed private player roster, sealed by the first
checkpoint. The outer state graph captures every player's state cell and logical
terrain, while native cross-player combat references preserve identity. Foreign or
unregistered native entity references fail capture. Cross-player regressions restore
attacker links and health together. Registry-owned item component prototypes and default
attribute definitions remain frozen external metadata; mutable item patches, attribute
instances and their children are captured. Unsupported graph state reports its referring
fields to make native adapter failures diagnosable.

Paper scenarios now cover both base Player movement and owned ServerPlayer movement/damage.
They do not provide a complete connected-player tick. Arbitrary ServerPlayer instances
cannot be adopted. The Fabric shell now retains
ServerWorld type checks, but unbound server-only services still
fail. Complete world simulation, ground-contact effects, authoritative damage, typed
spawned entities, native state import and the full private Bukkit event bridge remain open.

`FabricRollbackNativePlayerState` checkpoints an owned native player against that
private world. It retains transient native fields, tracked values, item components,
status effects, attribute modifiers and native RNG state. Registry/type metadata is
frozen for the session. Fastutil's derived map-view caches are excluded while their
owning map, backing storage and default return values are captured; independent
unsupported collection views still fail capture. The shared graph also supports
primitive optionals, immutable entry subclasses and Guava immutable containers without
discarding their mutable children.

`PaperRollbackWorldQueries` supplies the server counterpart over the captured logical
world. It binds exactly the complete owning native roster, decodes current block/fluid
state, resolves captured biome keys and loaded regions, and uses native heightmap
predicates. Collision/intersection queries run Paper's `EntityGetter`/`CollisionGetter`
implementations, including Moonrise's hard-collision eligibility and contact tolerance.
Native world/chunk queries and bending therefore observe the same restored terrain.
Bounded caches use immutable cells and cannot retain every past terrain edit.
Native chunk lookup checks the restored loaded-chunk set before consulting its derived
view cache. Unloading a captured chunk makes native loaded block/fluid reads return null;
restoring it reuses the view with the restored terrain. Unknown chunk coordinates still
reject, including after the cache has been populated.

Native tests import a captured cohort into this adapter, change walls, positions,
weather and time, then restore them together. Full ServerPlayer ticks reproduce their
movement and equipment journal after rewind. Partial/foreign rosters, uncaptured reads,
cross-thread access and unbound startup reject. Removed-body query filtering is tested
by staging that native state; the separate native removal lifecycle remains unbound.
Sky lighting, environment attributes, border and block-entity services remain mandatory
private checkpointed inputs, alongside the server's separate combat/event services.

`RollbackWorldSettings` now supplies a bounded, versioned startup component for native
gamerules, game time, sea level, difficulty, world/sound RNG seeds and the Paper/Spigot
policy already consumed by the private combat adapter. `PaperRollbackWorldSettings`
captures through compiled native/Paper APIs before replay; it retains no source world.
Rules retain their registry names and Boolean/integer types, including raw integer
values allowed by the server. Both native service constructors require an exact match
with the agreed feature set's rules, so missing, unknown or differently typed rules
cannot quietly fall back to local defaults.

`PaperRollbackWorldServices` supplies those rules and combat policies to both native
query and combat adapters; `FabricRollbackWorldServices` supplies the client rule,
clock and RNG services while retaining Paper-only policy for the remaining client
maintenance adapters. Construction checks initial logical dimension/difficulty; later
difficulty changes follow the checkpointed logical world. The owner advances game time
once per world tick, separately from day time. Clock, both random streams, logical
world and mandatory spatial/event providers participate in the same checkpoint.
Rules and configuration are frozen for this session; external live changes are not
sampled during replay and still require a session transition or authoritative input.

Both imported-roster query regressions now run native ticks through these production
services using an actual Paper rule capture. Native rule/policy transfer, replayed
clock/RNG/output and incompatible rule sets have separate tests. Environment attributes
and border state now use the production adapters described below. Sky lighting, event
dispatch and full world bootstrap remain required; their test providers are explicit fixture
implementations, not live-service fallbacks.

`RollbackEnvironmentData` carries the registered dimension-type key, whether weather
layers apply, and native rain/effective-thunder gradients in a bounded startup component.
`PaperRollbackEnvironment.capture` reads these through compiled native APIs before replay.
`PaperRollbackEnvironment` and `FabricRollbackEnvironment` then rebuild their native
attribute evaluators using frozen dimension/biome/timeline definitions, the logical
world's day time and its captured noise-biome cells. Positional reads cannot escape the
captured terrain. Explicit weighted biome interpolation is immutable and belongs to its
environment owner; foreign interpolators and nonpositive/nonfinite weights reject.

Time and weather changes invalidate the native time caches. Rewind restores the weather
inputs alongside logical time/terrain and reconstructs the derived evaluator, so cached
attributes cannot retain a discarded future. Native Paper tests compare every registered
attribute against its ordinary default-layer builder. A shared Paper reference checks
all 36 primitive-valued attributes on both loaders across three dimensions, day/night,
dry/wet conditions and direct/weighted biome reads (864 values). Separate regressions
restore time, weather and noise-biome changes together. Both imported-roster query tests
now supply these environment adapters through their production world services.

This component evaluates supplied environment state; it does not yet advance weather
transitions, import custom registry definitions or compute sky visibility/light.
Those inputs and the startup component's live transport still need
session bootstrap wiring. Registry/content agreement remains a prerequisite.

`RollbackBorderData` separately captures the native border settings and exact active
resize: original endpoints/duration, remaining ticks, current size and previous size.
`PaperRollbackBorder` and `FabricRollbackBorder` restore the existing native geometry
and resize calculation. They do not restart a partly completed curve from its current
size, which would lose its previous-tick bounds and can change floating-point results.
Their checkpoints also restore the last logical tick; duplicate logical ticks do not
advance twice. The Paper adapter advances the private native extent directly, avoiding
Paper's real-server-tick guard when replay needs several steps in one server tick.
Fixed private-member handles are prepared at setup on Paper; a narrow Fabric access
widener supplies compiled access to the corresponding native fields. Neither changes
ordinary border ticking or attaches a private border to a live world/listener.

Both world-service clocks now require their spatial provider to advance alongside them.
The imported-roster query tests use these production borders, replacing their previous
stationary-only fixture snapshots. Native Paper supplies a mid-resize startup fixture
and twelve frames through completion; both loaders compare size, speed, status, bounds
at three interpolation fractions, containment and distance. Tests also restore edited
settings, collision geometry and border/world clocks together, reject foreign snapshots
and threads, and prove several Paper replay steps can run within one real server tick.
Live startup transport, authoritative external border edits and provisional border
event/visual delivery still need session wiring. These adapters do not authorize live
Bukkit border callbacks during replay.

The cross-loader RNG reference exposed a real difference in the installed runtime:
Paper's compiled `BitRandomSource.nextDouble()` performs float multiplication before
widening, while Fabric's runtime retains double precision. This was also checked in
the Paperclip runtime jar, not only the development source jar. The private Fabric
world and newly imported roster now use `FabricRollbackPaperRandom`, which preserves
Paper's draws, cached Gaussian sequence and ordinary/positional/name/seed forks. Native
Paper reference vectors include zero, positive and extreme signed seeds. In-memory
native checkpoints restore these streams; ordinary entities retain their normal RNG.
These seeds initialize the new session streams, not arbitrary running RNG state transfer.

`FabricRollbackWorldQueries` now supplies a production spatial adapter over the same
`RollbackWorld` terrain and the imported native roster. Block/fluid reads, native
heightmap predicates, captured biome keys, loaded-chunk availability, weather and
difficulty follow restored logical state. Native entity queries use a once-bound owned
roster in UUID order, excluding removed bodies. Entity collision queries use Paper's
per-axis empty-box rule and contracted contact bounds. Placement/intersection queries
use Paper's single-box path and native voxel occupancy with the shared
`RollbackVoxelIntersections` kernel. Reads outside the captured region and
foreign entity arguments fail; unbound rosters cannot be checkpointed or queried.
Native block states are cached by immutable cell identity with a fixed size bound, so
terrain edits and restores cannot reuse stale geometry or retain every past mutation.

World time remains distinct from day time. Environment attributes, native rules/RNG,
scoreboards, border, sky lighting, block entities and causal/output handling are required
private checkpointed services. Sky visibility is not approximated from a motion-blocking
heightmap. Returned native block entities must match the captured position/state and
private world. The adapter supplies spatial behavior, not a complete world-service
bootstrap or Paper policy/maintenance parity.
Paper's actual Moonrise implementation verifies the shared voxel kernel over 15,000
random queries plus exact contact boundaries, offsets, empty shapes and hollow volumes.
Both loaders check the same grazing/flat hard-collider cases and restore placement
eligibility and player positions through their production query adapters. The native
world shells now route explicit entity-collision and placement queries to those adapters.
This matches query geometry for the current player roster, not every Paper entity policy:
typed nonplayer admission and Moonrise hard-collider classification still need an adapter
before expanding the native roster to arbitrary spawned entities.

Native regression coverage executes `PlayerEntity.travel()` against captured surfaces,
verifies native gravity/friction and terrain restoration, and replays late movement
input from a native player checkpoint. It also restores health, modified attributes,
active effects, item counts/components and the RNG sequence.

Fabric can additionally execute the actual `ServerWorld.tickEntity()` body for owned
base players, including native age advancement and movement. The complete passenger
tree must belong to the fixed player roster before the tick starts. Locator updates
are detached provisional callbacks, and the private server has no debug subscribers.
Tests compare on-time and delayed inputs across base-player ticks and restore their
timers, movement and provisional locator journal together. Weather/environment queries
also read restored conditions. This is a clear-air base-player fixture, not connected
ServerPlayer behavior, complete world ticking or a Paper/Fabric parity claim. Native
state delivery, hunger and server-player event behavior, damage/block physics,
typed spawned entities and output delivery remain required before live duels can use it.

The imported Paper roster now also executes native player ticks through the production
spatial adapter. Tests mutate walls, player positions, weather, time and loaded chunks,
then restore them together; movement after replay matches the first execution. This
exercised native equipment maintenance that earlier empty-inventory movement fixtures
did not reach. A private chunk-manager adapter now journals equipment, entity-status
and animation broadcasts, preserving whether the source player belongs to the audience.
`FabricRollbackPacketData` detaches item components immediately, bounds each payload,
validates entity ownership and rejects unadapted packets. It never sends a live packet.
Equipment changes and their journal rewind together, and repeated native ticks reproduce
the same movement and output. Final/provisional delivery and Paper/Fabric output mapping
still belong to the pending presentation bridge.

The Fabric base-player adapter now invokes native `PlayerEntity.damage()` with owned
sources and the session's frozen damage/enchantment/loot registries. Native health,
absorption, armor and effect reduction, immunity timers, equipment durability, attacker
references and knockback participate in the existing player/world checkpoint. Damage
notifications, entity statuses and sounds become detached provisional records, including
rewindable sound seeds. Native game events use a mandatory private dispatch callback;
they are causal world input and cannot be discarded as presentation. World time,
difficulty, game rules and RNG resolve captured session state.

Native tests restore ordinary and equipped/enchantment-bearing hits, verify real data-pack
damage tags and restored fire-damage rules, and retract a hit and its provisional output
when late native invulnerability input changes the result. The event fixture has no
causal block listeners. This verifies nonlethal base-player damage, not connected
ServerPlayer events, Paper damage parity, lethal drops/spawns or a complete private
game-event dispatcher. Logical player synchronization and the live combat runtime still
need to connect this adapter before an actual duel can use it.

Registered activation handlers and reflected combo constructors now propagate failures
during a rollback step. Ordinary gameplay retains its existing log-and-continue policy.
Regression coverage checks that a failed action cannot finalize already buffered effects,
and that late one-shot input replays the existing handler registry without repeating on
later predicted ticks. This is still engine-level coverage, not the client input protocol.

`RollbackEntityBody`, `RollbackLivingState` and their `Entity`/`LivingEntity` views now
provide logical storage for position/rotation, velocity, pose-dependent hitboxes, fire,
fall state, liveness, health, absorption, immunity timers, attributes, effective potions,
metadata and passenger links. Mutable metadata payloads and damage events participate
in the state graph; metadata owner objects are identity keys. Native supplemental state
is an opaque detached byte snapshot, whose actual capture/restore codec remains required.
Native damage, potion merging, attribute modifiers and teleport/mount policy are explicit
callbacks; there is no alternate damage formula or physics implementation in these views.
Equipment must be supplied as a checkpointable logical view. Native entity capture,
movement/damage policy and typed spawned-entity adapters remain required.

`RollbackEntityBody.nativeBacked` now shares its movement storage directly with a private
Paper/Fabric player state cell. Ability-facing location, rotation, velocity and box reads
observe native physics immediately; logical velocity and accepted teleport writes update
that same native replica. The outer checkpoint reaches native transient state and terrain
through the logical body, instead of maintaining a second movement copy to synchronize.
Native fall distance stays double precision internally; only the common float API narrows
it. Movement writes preserve native dimensions, and identity changes or arbitrary hitbox
replacement are rejected. Private native callbacks can use the logical view while reentrant
native steps and mid-step checkpoints remain forbidden.

Both native suites now restore motion/terrain through a logical entity registry and compare
late movement against on-time native travel through the logical view. Paper also covers
an owned ServerPlayer after its first tick: movement-triggered locator updates become
detached provisional notifications and rewind with the player. Other locator operations
and delivery remain unimplemented. These are movement-storage integration tests, not full
native ticks or whole-fight acceptance. Native pose/control policy and the live runtime
still need to connect their adapters; combat and equipment backing are described below.

`RollbackLivingState.nativeBacked` now requires that combat and movement share the exact
same native source. Paper implements this source over its owned player: health, absorption,
eye height, air, immunity, last damage, attributes, potion views and liveness are read from
native state. Retained logical attribute handles see restored native modifiers immediately.
Logical damage resolves an exact private attacker backing and invokes the existing native
damage adapter. A same-UUID player from another private world is rejected. Positive health,
immunity and absorption writes update the native replica; derived vitals cannot be replaced
and zero-health API writes still require the pending private death route. Player setAI keeps
the ordinary Bukkit no-op behavior.

Logical potion mutations run Paper's copied add/remove bodies with PLUGIN cause, native
merging/hidden effects and private cancellable events. The ordinary Bukkit constructor's
ambient/particle/icon defaults and ignored force argument are preserved. Air changes run
the actual Entity.setAirSupply body with owned entity/server event routing, including
handler-adjusted values and cancellation. All those changes rewind through the logical
view's existing native checkpoint; the accessor cache retains no separate combat state.

Native tests now invoke damage/potions/attributes through the common LivingEntity API,
restore from retained attribute handles, and compare late logical attacks with on-time
native outputs. The Neptune contract suite additionally routes a logical lethal hit through
native Paper damage and the provisional defeat bridge, then restores health and the round
together. Equipment access in the Neptune bridge fixture is deliberately unused. The
Paper/Fabric logical combat fixtures now bind native inventory and equipment as described
below. Full native input/tick execution and complete ability runtime wiring remain unfinished.
This does not enable live duels.

Fabric now implements the same logical combat source over its owned native player.
Damage, effect application/removal and attribute changes use native behavior; logical
health, immunity, air, eye height, effects, effective attributes and liveness read that
replica directly. A read-only, remapped mixin accessor exposes native lastDamageTaken
instead of the old common API's zero fallback. The supported vanilla damage method has
a fixed 20-tick immunity reset; the source reports that native value, verified against
a real hit, rather than claiming support for Paper's mutable invulnerableDuration.
Potion descriptor presentation flags match the Paper rollback source, while merging
still follows the native method and the common force argument remains ignored.

Fabric tests exercise logical damage/knockback, retained attribute restoration, native
effect merging and modifiers, vitals and liveness, foreign-source rejection and late
logical attacks yielding identical native output to on-time execution. Its native player
and terrain remain reachable from the logical view's checkpoint. These checks do not
prove Paper/Fabric rule or external-event parity: authoritative policy import, private
event reconciliation, full native input/tick execution and live runtime wiring remain.

`PaperRollbackInventory.bind` and `FabricRollbackInventory.bind` now expose the private
native player's slots through the existing `RollbackInventory`/`RollbackEquipment` API.
Selection, slot writes, stacking, consumption and equipment reads use that same inventory.
The living-state factory rejects a logical equipment view bound to a different native
player. Imported/detached inventories remain available for capture and query contexts.

Inventory reads return logical mirrors of owned native stacks; native durability changes
and common amount/component edits are visible through every mirror. Non-air type changes
and metadata writes preserve native stack identity. Paper uses its typed native setter;
Fabric uses remapped item/component accessors on private stacks to support the same
in-place behavior. Setting a mirror's type to AIR detaches it as CraftItemStack does.
Slot assignment and cloning copy items, and an empty-hand view cannot mutate the native
EMPTY singleton. A retained mirror continues to reference its old item after slot
replacement, rather than following the replacement item.

Mirrors checkpoint their native stack in place using the player's existing native graph
boundary. Their references also reach the source inventory/player/world, so restoring
from a retained item alone restores the fight state. Removed-but-retained stacks are
still captured; no permanent cache retains every item ever observed. Native snapshots
and mirror snapshots can overlap and restore the same object to the same state. Tests
cover multiple mirrors, native armor wear, component/type changes, zero-count revival,
replaced slots, native selection/stacking, source/thread rejection and late input that
retracts health loss, knockback, armor wear and outputs together. These fixtures exercise
input correction through RollbackEngine; they do not substitute for whole-fight movement
and dynamic ability collision acceptance tests.

Paper's inventory stack-limit changes rewind with its native fields. Fabric exposes its
native fixed limit and rejects attempts to change it; custom Paper policy still needs
authoritative import. Inventory packet delivery and complete native equipment paths remain
part of the pending full tick and reconciliation integration. No live inventory is bound.

`RollbackPlayerState.nativeBacked` now requires the same source for combat, movement,
inventory and controls. The ordinary player API reads native flight, sneaking, sprinting,
gliding, swimming, glowing, experience and exhaustion state through that source. There
is no parallel mutable copy of native controls. Native state imports must use the native
checkpoint/import path; replacing the old detached Controls record is rejected.

Control commands retain the property/setter identity, not just differences between two
flag sets. This preserves explicit glowing tags when a potion already supplies visible
glow, native sprint attribute modifiers, Paper's fly-speed scaling, experience-update
invalidation and flight permission rules. Disabling permission stops current flight;
enabling flight without permission fails before mutation. Each setter changes only its
own state, so unrelated state changed by a callback is not overwritten by a stale record.
Paper's native onUpdateAbilities produces a buffered, audited primitive abilities packet
through the private connection. Swimming runs the copied native Entity.setSwimming and
CraftEventFactory.callToggleSwimEvent bodies with private cancellable dispatch.

Fabric uses typed native setters and remapped accessors for the gliding flag and hunger
exhaustion. Its pickup permission is explicit checkpointed session state because vanilla
has no Paper bukkitPickUpLoot field; the future native item-pickup service must consult it.
This does not claim that pickup interactions or external Paper event policies already run
on the client. Profile/game-mode import and those native policies remain session work.

Native tests exercise real RollbackPlayer views, control/attribute restoration, flight
validation and buffered packets, swimming cancellation on Paper, repeated glow setters,
source/thread rejection and late sprint/jump input producing matching native movement.
`RollbackMovementInput` supplies bounded strafe/forward intent, jump and view angles;
native input damping and travel still run in the platform. Paper now executes the native
Player/LivingEntity `aiStep` movement phase against the private world, including the
private jump event, native jump statistics and captured walk/sprint exhaustion policy.
The jump cooldown's monotonic clock uses the replay clock. The copied mobility check
validates the owned listener without consulting a real socket; active-world validity
must be imported with the player state. Tests also cancel a jump, restore the frame,
and repeat the accepted jump with the same motion, exhaustion and event sequence.

`PaperRollbackNativePlayerState.tickPlayerBody` now wraps movement in the native
Player/LivingEntity/Entity tick bodies, with previous pose and age advanced once by
the private driver. Tests replay crouching and its resized collision box, view angles,
hurt timers, potion durations/expiration, periodic healing, burning and captured void
damage. Native effect-tick cancellation still decrements duration without applying
the cancelled health change. Health/food effect bodies use private event/damage
routes; raid/village effects remain unbound rather than consulting live world services.

The tick reads captured weather/biome/heightmap inputs, loaded-region availability,
environment attributes, entity-collision limits, void policy and equipment-update
configuration. Loaded-region queries reject terrain outside the captured bounds.
Server-side Level.addParticle runs its audited empty native body; this does not suppress
the separate broadcast-particle API. Private debug subscriptions are empty and replay
advances at the fixed normal tick rate. Fabric exercises its private base-player tick.

`PaperRollbackNativePlayerState.tick` now executes the surrounding native ServerPlayer
tick/doTick phases through private services. Server invulnerability, food/exhaustion,
regeneration, statistics, score updates, inventory synchronization and advancement
flushing participate in rewind. Captured container cadence, regeneration exhaustion and
shoulder-parrot policy replace live settings. The driver advances previous pose and age
once; it does not apply the network listener's last-received-position reset to simulated
movement. Unloaded clients are rejected before menu initialization or player mutation.

The private inventory menu retains native slot listeners and synchronizer semantics.
Its events use the private dispatcher and native owned inventory view. Menu initialization,
remote slot tracking and the advancement tracker's first-packet flag rewind with state;
restoring an initialized menu reproduces slot deltas without another full inventory sync.
The component-hash cache is derived state and is cleared on restore. External menus and
unbound world/item interactions still fail rather than reaching live server services.

Item-bearing inventory/equipment packets and advancement updates become detached,
bounded server-internal records. Equipment records preserve Paper's sanitization flag;
advancement records retain display coordinates and criterion timestamps. They are not
client wire messages: final delivery still needs native packet rebuilding and the normal
audience/sanitization rules. Locator actions likewise remain buffered output. Native tests
verify initial and incremental inventory synchronization, equipment data independence,
advancement flushing, and late sprint/jump input matching state, events and output frames
through full private server-player ticks.

Complete world/item services, full state import, negotiated transport wiring, Fabric
server-player maintenance/policy parity and production bootstrap remain required before live rollback can be
enabled. Paper movement and the bending loop now share the execution path described above;
the tests do not prove Paper/Fabric movement parity in every environment or working live duels.

`RollbackEntityRegistry` checkpoints membership and performs spatial queries against
current logical boxes in stable UUID order. Restoring a previous frame restores removed
views and invalidates spawned views from discarded branches, including replacement UUIDs.
Tests retract health changes, knockback and projectile removal together, restore metadata
and passenger graphs, and reject inherited API stubs, live entity arguments, foreign
checkpoints and cross-thread mutations. The damage policy used by these tests is explicitly
a fixture; native Paper/Fabric damage and movement still need integration and verification.

`RollbackInventory` and `RollbackEquipment` share checkpointed slot storage, including
held-slot selection, retained item references and native equipment slots beyond the
common API. Paper and Fabric discover their layouts from the native equipment maps.
Storage searches, stacking and consumption exclude equipment slots and honor native
component similarity and stack limits through a mandatory `RollbackItems` adapter.
The common tests use an explicit item fixture. Paper and Fabric native capture adapters
now detach all 43 slots, selected slot and the actual inventory stack limit, with the
component-backed item implementations described below. Native tests restore retained
item references without changing the source inventory; Paper also tests the Bukkit
inventory bridge. The native inventory limits can differ between platforms, so the
authoritative session seed must supply the server's limit to both simulations.

`RollbackPlayerState` and `RollbackPlayer` expose the existing Player API over that
inventory and logical body/living state. Controls, profile values, visibility and logical
scoreboard references rewind with health and movement. Permissions, targeting, native
control policy, projectile creation and output delivery must come from session services.
Tests verify complete Player API overrides and late input retracting item consumption,
health, flight and buffered messages together. Their policy/effect sinks are fixtures;
this does not implement native player movement or a live client/server session.

`PaperRollbackItemCodec` and `FabricRollbackItemCodec` now capture full persistent native
item/component data into detached `RollbackItemData`. They use the native ItemStack codec
and the supplied registry's serialization context, with shared binary NBT framing. Both
decode the same checked-in fixture covering an enchantment registry reference, styled
text, opaque nested data and removal of a default component. Native tests verify detached
copies, the Paper inventory-item bridge, strict unknown-component rejection, bounded NBT
allocation/depth and rejection of text that cannot be encoded without loss. Encoding
also checks native equality after decoding, rejecting components that serialization
would silently omit. The session must supply compatible stable registries. Native
transient item animation/owner state is outside this persistent component format.

`RollbackNativeItems` now supplies logical ItemStack and typed ItemMeta views. Each
metadata view retains its complete native baseline and an immutable set of explicit
edits; applying metadata preserves components not exposed by the common API. Item and
metadata checkpoints retain identity, and copied metadata is applied explicitly rather
than sharing live item state. The native access contract requires private owned stacks,
complete import, zero-count preservation and no live inventory or profile lookup.

`PaperRollbackItems` and `FabricRollbackItems` implement that contract with native component operations and
the full item codec for imports/exports. Tests cover production inventory stacking,
metadata-sensitive equality, zero-count aliases that can be revived, styled names,
opaque persistent data, potion/color/profile edits and context/thread rejection.
Changing a skin URL preserves other texture-payload fields and makes no profile lookup.
`PaperRollbackItemCatalog` derives metadata categories from Paper's declared ItemType
schema; a native test checks coverage of the entire item registry. The Fabric adapter
requires this authoritative schema so both sides expose the same metadata subclasses.
Transport of this catalog remains required. Paper's adapter calls native component
operations without using live inventory mutations or profile resolution. The shared
fixtures also check Paper's legacy text projection, including nested styles and newlines.

`RollbackItemScope` lets ordinary `new ItemStack(...)` calls in existing ability code
use the native logical factory. Base items created before the scope use native equality
while in the scope, and logical backing survives scope exit. Normal construction outside
a scope retains existing behavior. Native tests cover constructor dispatch, cloning,
nested contexts and state restoration through a common ItemStack reference. The
item-aware `RollbackDomain.create` overload installs this scope during bootstrap and
every domain call, including failure cleanup and outside-state restoration. A native
test verifies domain isolation and item identity restoration. No live duel creates that
domain yet. Native platform subclasses retain their own backing and cannot be imported
implicitly as common item projections.

`RollbackWorld` now overrides the complete common World API. Terrain and nearby-entity
queries use its bounded block store and logical registry; player lists never consult
the global platform. Its state graph references restore terrain, entity membership,
movement, time, weather and loaded-chunk membership together. Spawn callbacks return
logical views, which the world registers before the ability's initialization callback.
Discarded spawns are invalidated on restoration. Query and action services can be
installed separately. Native spawning, explosion behavior and output encoding remain
mandatory services; this class does not
supply alternate native physics or silently inherit stub behavior. Query results and
entity arguments are checked for world membership, and returned ray positions are
detached. Tests exercise restored queries, dynamically dispatched spawning, effect
retraction, foreign-view rejection and API coverage using explicit service fixtures.
The complete native world service implementations and live session wiring remain open.

`RollbackWorldQueries` now connects this World view to Paper/Fabric native block-ray
adapters and the native motion-blocking heightmap predicate. Height queries read the
restored column with a work budget, return the first free Y as Paper does, and abort
when they encounter uncaptured cells. Captured terrain can include native air outside
build height for boundary and neighbor queries. The combined ray query limits entity
search to the block hit and selects among current logical boxes in stable registry
order. `RollbackBoxRay` supplies Paper-compatible box expansion and ray math on both
platforms; Fabric's native box helper has different endpoint/inside-origin semantics.
A native Paper suite compares the shared math against the actual API over randomized
and boundary cases. `RollbackWorldRay` retains direction at zero distance; inside-box
entity queries preserve Paper's exit-face behavior, including exits past the nominal
range. Native world fixtures on both platforms restore slab occlusion and entity pose
together and verify filtering, ray size, heightmap changes and zero-distance rays.
This completes those query services, not native movement, entity ticking or live combat.

`RollbackMovementSolver` now performs collision/step selection through native backends.
Paper calls its Moonrise collision solver and actual native step-height collector;
Fabric invokes Minecraft's private pure helpers through remapped mixin accessors.
`PaperRollbackGeometry.movementColliders` and its Fabric counterpart use the native
block iterator against restored logical terrain, preserving voxel shapes and a supplied
collision context. Sweeps, collider counts, step candidates and clipping work are bounded.
Initial entity colliders are retained during step search, and native shapes remain
ephemeral query values. Native suites cover walls, floors, step height, ceilings,
airborne state, changed terrain and late terrain input replaying a variable movement
path with matching finalized effects. Resource/thread guards have common coverage.

This solver returns displacement and collision flags; it does not implement full entity
travel/ticking. The solid-block native fixtures explicitly use an empty entity context.
Live use still requires contexts for all relevant entity types, native entity
collision eligibility, captured border behavior, controls, gravity/friction, fluids,
fall/block effects and velocity updates. Those cannot be replaced by this clipping step,
and no duel starts it yet. The Fabric accessor exposes helpers without injecting into
normal movement execution.

`PaperRollbackPlayerContext` and `FabricRollbackPlayerContext` now supply native player
contexts for block movement queries. Each owns a real native Player instance that is
never spawned or ticked. Before every query, its position/box, rotation, descent flag,
fall distance, game mode, selected slot and all inventory/equipment slots are refreshed
from the logical player. Item components are detached; no live player, connection or
world is retained. The context is a derived query value, not authoritative state. Its
state-cell contract snapshots the logical owner instead of traversing the native cache.
Construction occurs outside replay, so rewinds reuse the replica without allocating
native entity IDs again. Unknown world calls, inactive players, reentrant queries and
cross-thread use fail. Native tests exercise powder snow, scaffolding, equipment and
selected-hand restoration, and late descent input correcting a previously blocked path.

The constructor-only native world shell uses Byte Buddy/Objenesis without an attached
agent. It allows the audited client/server flag getter and rejects other intercepted
world methods. Paper additionally supplies blank constructor-only activation metadata.
This shell is deliberately not a native simulation world; final methods and direct fields
are not intercepted. The current contexts cover vanilla player block-shape queries, not
full native pose/travel/effects or contexts for every spawned entity type. Native tags
and registries must stay compatible and stable for a session. Fabric's native test loads
the bundled vanilla tags rather than substituting collision rules. The live session,
full physics, authoritative damage and client/server reconciliation remain unfinished.

Verification commands (Java 21):

```text
./gradlew.bat --offline :common:test :bukkit:test :fabric:test
./gradlew.bat --offline :bukkit:nativeTest
```

For the optional Neptune contract suite, first run `:Plugin:classes` in Neptune, then
run the following in ProjectKorra (using the actual Neptune checkout path):

```text
./gradlew.bat --offline :bukkit:neptuneTest -PneptuneClasses=C:/path/to/Neptune/Plugin/build/classes/java/main
```

The Paper native suite uses the same paperweight development bundle as compilation.
It initializes registries in a test JVM without a preexisting run directory and
does not start a server or modify worlds/plugins. Fabric's native tests use the
official `fabric-loader-junit` integration, required for Minecraft access transformations.

The engine finalizes a tick only after its allowed rollback window. The bridge must
deliver returned effects with session/tick/ordinal identity and abort on delivery failure.
It must never execute live Bukkit mutations while replaying. Clients may display
provisional results and correct them, while external plugin events and match results
belong to finalized state.

## Existing integration constraints

The current collision pipeline is already dynamic. `BendingManager.run()` advances
`CoreAbility.progressAll()` and then calls `CollisionManager.detectCollisions()`.
The manager enumerates registered class pairs, queries each live ability's
`getLocations()` and `getCollisionRadius()`, publishes `AbilityCollisionEvent`, and
invokes both abilities' `handleCollision()` methods. Activation is also dispatched
through the existing dynamic `AbilityActivationManager`. Rollback must execute this
same pipeline with restored state. It must not create a new projectile/defence engine
or replace the existing collision registration and dispatch rules.

The shared runtime currently has mutable static registries. Paper wrappers perform direct world/entity mutation;
Fabric wrappers run local prediction against a different world adapter. These boundaries
must be adapted before rollback can be enabled. Restoring only locations or only the
ability instances cannot account for knockback, ability cancellation or temporary walls.

Remaining integration work includes complete shared-service root registration and bootstrap,
full native client/server parity, live input takeover, provisional presentation and finalized
external effect delivery, authoritative state transfer/divergence repair, pacing, and the
complete native bootstrap behind Neptune's lifecycle connection. Nothing currently starts
a rollback session in a live duel.

Ability names may be used in regression scenarios, not to select which gameplay gets
rollback. Normal live transport delays and hit registration remain unchanged; the private
domain hit-registration policy described above applies only during rollback execution.


### Private native round damage parity

The private Fabric player now supports Paper's damage-modifier/event ordering when
bound to a complete round roster and captured damage policy. Cancellation and the
shared round decision run before equipment wear, absorption, immunity, knockback,
and health changes. The imported Paper immunity duration is checkpointed on the
private client body. Binding seals the roster before checkpoint capture.

A shared twenty-case fixture compares actual patched Paper and Fabric results
for health, mitigation, event rescaling, immunity, equipment wear, velocity, shield
blocking/cancellation, totem survival and round outcomes. Paper's copied shield
call paths retain private statistics, cooldown events and sounds, and route direct
world RNG reads to captured state. Fabric initializes only its privately allocated
world RNG for the native component's direct reads. Tests also rewind late defence
and retract a provisional defeat. Production damage policy bindings, complete
native maintenance parity and live bootstrap remain required; these tests do not
enable live duels.


Paper's existing environment, player/passive, protected-fall and entity-hit handlers
now delegate to `CommonDamageHandler`, retaining their native listener priorities
and common-event forwarding boundaries. Both native damage adapters expose live
common event views for base/modifier edits and cancellation; Paper's view preserves
the native cause. The shared fixture exercises Fabric damage through this view,
and native tests check bidirectional damage/cancellation. Captured native listener
ordering and production policy dispatch still need assembly before live replay.

Shield hits also route knockback, shield-disable cancellation and item-cooldown
cancellation through captured client policy. Cancelling shield disable keeps the
shield raised; cancelling only cooldown still stops item use, matching Paper.
The private Paper connection captures the identifier/duration cooldown packet.
Both native suites verify shield wear, active use and cooldown restoration after
an axe hit; the client repeats the hit after rewind.


### Pending task transfer

`RollbackTaskBindings` carries frozen pending callbacks in the same portable graph
as abilities and common event handlers. Its source capture projects native task
handles to portable handles, preserving references held by callbacks and abilities
without transferring a live scheduler. Bending-state bootstrap installs the copied
batch into the private scheduler. Relative deadlines, repetition, original ordering,
legacy IDs, ability context, action and random seed survive transfer. New task IDs
start beyond the imported range; equal-deadline order is independent of legacy IDs.

Import validates the complete batch before changing scheduler membership or binding
handles. Callback self-cancellation, completion, mutable callback state and ordering
counters rewind together. Tests exercise both direct copy and portable decoding via
the actual bending-state bootstrap, then late-input replay with the imported task.
The live scheduler adapter must still enumerate/freeze the selected pending tasks,
supply them in original scheduling order, and restore live ownership on teardown.
The portable callback representation below covers newly compiled scheduler lambdas;
other opaque callbacks still require explicit adapters. This handoff does not enable
a live session.


`PKScheduler` now prefers the serializable `PKRunnable` functional target for lambda
and method-reference call sites, delegating unchanged to each loader's existing
Runnable implementation. This exposes the compiler's `SerializedLambda` descriptor;
no Java object serialization is used. Bending-state capture projects these hidden
implementations into `RollbackCallback` data containing the registered capturing
class, compiler descriptor and graph-copied arguments. The compiler-generated local
factory validates/reconstructs a short-lived callable from those private references.
It never loads an arbitrary class named by wire data or caches a source lambda.

Nested callbacks retain their functional interface, receiver aliases and cycles.
Tests cover actual scheduler overload selection, lambda captures, method references,
nested capturing classes, replay restoration and both bending bootstrap transfer
modes. Missing catalog classes and ordinary undescribed hidden callbacks fail import.
Existing Runnable objects remain source/binary compatible; precompiled call sites
using opaque hidden Runnables need a rebuild or an explicit callback adapter.

Temporary terrain's existing `RevertTask` interface uses the same describable
contract, including nested references from scheduled work. The carrier retains
that interface to preserve typed captures. Task import validates the compiler
factory and captured argument compatibility before committing any task or handle;
a bad late callback leaves the whole scheduler batch uninstalled.

### Portable block references

Known Paper block views and private terrain views now transfer as world/coordinate
references. Imported reads, neighboring-block lookups and mutations resolve through
the rebound world's current terrain, so a retained reference follows terrain rewind.
The graph preserves aliases without copying live native block handles. Known loader
and private block views share symmetric coordinate equality and stable hashes; addon
Block subclasses do not implicitly opt into that identity contract or lose their fields.

Tests exercise Paper wrapper transfer without live terrain access, map lookup against
fresh wrappers, private-world isolation, terrain mutation/rewind and return transfer.
`PaperRollbackGraphViews` composes the common `RollbackGraphViews` projection for
private graphs. Production bootstrap still needs to install these factories. Block
snapshots and other unsupported native values remain separate transfer work; this
change does not enable a live rollback match.

### Portable block snapshots

Placed Paper block-state wrappers and private terrain snapshots now project to
`RollbackBlockSnapshot`. This saves detached block data and bounded block-entity NBT,
with a rebound block reference. Update honors the force/type check and physics flag;
private restoration changes saved block contents while retaining current environment
values. Snapshot export does not read the current live block to reconstruct old contents.
Exact native state strings survive typed facade cloning. Addon snapshot classes retain
their ordinary graph handling rather than being flattened as loader wrappers.

The native Paper chest test transfers a real saved chest after its source inventory
changes, restores the original items into private terrain, and verifies aliases and
source isolation. Common tests cover forced/non-forced updates, repeat restoration
after rewind, and mutable data isolation. Paper live restoration reconstructs a native
snapshot and uses the existing synchronization boundary, with replay/thread guards;
actual live-server teardown restoration remains unverified. Unplaced native snapshots
and Paper block entities with snapshotting disabled are explicitly rejected. Production
bootstrap wiring and two-client duel validation remain required.

### Gameplay codec assembly

`RollbackGameplayGraph` assembles the installed schema with canonical roster bindings,
captured configuration views, registered attribute metadata, lifecycle output bindings,
and explicit loader/addon service bindings. Core binding collisions reject assembly.
The live/private side selects configuration identities without transferring native config
internals. Loader projections remain explicit: Paper uses `PaperRollbackGraphViews`,
and outgoing private graphs use `RollbackGraphViews`.

Owned Paper duel capture now accepts `GraphSetup` and builds this codec from the same
configuration data included in its bootstrap payload, inside the ownership capture
boundary. The existing explicit-codec entry point remains available. Tests verify roster,
service and effect rebinding, reserved binding rejection, and configuration isolation plus
return transfer. The complete match factory and client runtime still need installation;
this assembly entry point alone does not enable a duel.

The shared graph assembly now also provides an uninstalled import result containing
the prepared configuration, decoded bending state and matching export codec. It checks
the negotiated roster against canonical bindings before decoding. Fabric's roster-view
adapter connects the received bootstrap payload to this import using locally registered
configuration sources. Tests transfer real BendingPlayer state into private bodies,
verify detached cooldown maps, retain the required domain roots, and reject mismatched
rosters and malformed graphs. Native domain construction, complete services and runtime
factory installation are still required; this entry point does not acknowledge a client
bootstrap or start gameplay on its own.

### Execution world-time binding

Paper and Fabric execution now offer a constructor that binds their native world
services through `RollbackTimedExecutionServices`. It advances world time before
native phase setup, scheduled callbacks and player input, and checks that the clock
belongs to the players' private world. The clock and native phases remain checkpoint
roots. Partial begin failures still receive cleanup; snapshots are refused during an
active phase and nested clocks cannot run that phase's actions.

The shared test verifies time ordering, identical outputs after rewind, and cleanup
following a failed native begin. Native action/world remainder services are still
required; this binding does not provide those missing operations or install a runtime.
A hit-policy test now restores its temporary ability-definition registration, eliminating
an order-dependent unregistered-test-class failure in full gameplay import tests.

### Native hand-swing input

Accepted swing actions now run the native hand-animation method after the common
bending handler, and cancelled swings produce no native animation. The input stream
retains off-hand intent as `OFF_HAND_SWING`; input, authority and start protocol versions
are now 4 so older peers cannot silently interpret the extended action set. Both hand
kinds retain the existing common swing-handler semantics. This animation phase does
not stand in for native melee attack intent, which remains separate work.

Paper detaches animation tracking packets into immutable entity/action data, matching
Fabric's existing packet route. Native tests cover both hands, native animation cadence,
state/output rewind, and the full execution path with on-time versus late accepted or
cancelled off-hand input. Missing frames still omit one-shot actions. Remaining native
item, swap and attack operations, runtime installation and live duel validation are not
completed by this change.


### Native hand-swap input

Accepted `SWAP_HANDS` edges now execute through the owned native player on both
loaders after the common bending input handler. Paper dispatches its private swap
event with proposed post-swap stacks, honors cancellation/replacements, preserves
unchanged item aliases, ends active item use and applies the captured immediate
equipment-update policy. Fabric routes the detached swap result through the owned
world services; an unbound event policy fails explicitly. These actions are no
longer delegated to the generic native remainder callback.

Native tests verify inventory aliases, equipment output rewind, active-item-use
stopping/restoration and Paper event cancellation. Both execution fixtures also compare late-input replay against an
on-time swap, including complete tick effects and resulting inventory. Event
replacement parity, further active-use edge cases and live two-client validation still
need verification. Runtime factory installation and remaining native actions are
still incomplete; this does not establish end-to-end rollback completion.


### Captured material properties

Duel bootstrap version 3 includes the server's solidity result for every common
Material. The immutable `RollbackMaterials` service implements the private platform's
material facade without retaining a live registry or recomputing Paper properties
on Fabric. Names, order, count, flags and payload bounds are checked on import;
missing or incompatible definitions reject the seed. The canonical Paper/Fabric
bootstrap fixture includes this section and exercises multipart client import.

This supplies the material binding for runtime assembly. Other platform bindings,
concrete runtime factories and live duel validation remain incomplete.


### Imported match runtime construction

`RollbackCombatRuntime.createImportedMatch` now connects a decoded gameplay graph
to authoritative or replica match construction. It validates the exact private
player identities against the platform and round before installing anything, adds
mandatory bending shared fields and imported roots, and uses the seed's private
configuration. Bending registries, imported event registrations and captured tasks
install inside domain bootstrap before loader-specific completion work runs.

Tests construct both runtime modes from encoded/decoded gameplay state and verify
configuration isolation, outside registry restoration, roster rejection and failed
startup restoration. They cover startup and private-state export, not native duel
advancement. Native factories still need complete services, action/output routing,
ownership handoff and teardown before this can be enabled for live Neptune rounds.

Startup coverage deliberately suppresses day/night gameplay in its fixture. An
unresolved integration requirement is importing and isolating shared gameplay
caches such as ElementalAbility's transparent/bendable material sets: class
initialization must not derive them from a replica's placeholder platform and
leak those results outside the domain. The runtime constructor does not claim
that its mandatory bending roots replace the loader/addon shared-state audit.


### Elemental material cache isolation

Transparent and bendable-material sets are now mandatory gameplay graph roots.
Import assigns their private set identities inside the domain, preserving aliases
held by existing abilities; shared-field checkpoints restore the outside set
identities and values after every domain call. Both in-process and portable
transfer tests cover late input replacing cache mutations and verify that those
mutations never reach live caches. Settled export carries current private values;
restored ability references rebind to the live server's current material policy
without publishing a duel's cache changes globally.

This resolves the specific ElementalAbility cache gap noted above. Class startup
still needs a valid loader platform/configuration, and other shared/static gameplay
state remains part of the broader runtime audit. Native runtime installation and
live two-client validation are still outstanding.


### Combo registry import and replay

The bending graph now includes combo definitions and metadata plus the enrolled
players' recent input histories, pending one-tick triggers and combo-help sessions.
Capture filters player-owned maps by the exact roster names/UUIDs. Projection
preserves ability-held aliases to the filtered registries, and installation swaps
the private registry identities with the domain's shared-field checkpoints.

Both transfer paths have a late-input history test: corrected replay removes the
predicted combo history, retains the ability's history-list alias, and leaves live
and unrelated-player histories unchanged. This is startup/replay coverage. The
settled combo-state merge back into the live roster is not implemented yet, so the
new registry does not establish complete duel teardown or enable live sessions.


### Settled combo-state restoration

Common teardown now prepares a read-only combo merge alongside manager and ability
restoration. It reconnects registry and definition aliases to the live server,
then commits outgoing histories, pending triggers and help-session entries only
for enrolled roster names/UUIDs. Unrelated entries remain untouched. Registry
replacement, changed selected entry identities and changed selected input contents
reject the merge before common registry writes. Successful cleanup is idempotent
and does not overwrite input received after the first commit.

The gameplay transfer tests cover restored history/trigger aliases, definition
rebinding, conflicting history replacement and mutation, outsider preservation and
retry after newer live input. Combo-help task restart still depends on the separate
captured-task restoration path and needs live integration validation. This closes
the basic combo merge gap above, not the full native runtime or two-client test gate.

### Portable activation callbacks

ActivationHandler now exposes a serializable lambda contract. The existing graph
projection records compiler descriptors and copied captures for activation lambdas
as well as scheduled callbacks; invocation preserves context mutation and the
handled boolean. Descriptors resolve only through negotiated local compiler
factories, with contract validation and no retained source lambda. Scheduler
imports reject activation descriptors before binding any task.

Annotated handlers now hold their ability receiver and method identity rather than
a reflection Method captured inside a lambda. Invocation resolves an installed
method and requires its activation annotation. Tests cover copied mutable receivers,
nested callbacks, rewind, context mutation, handled results and rejection of an
unannotated target. This supplies portable handlers; importing and isolating the
activation registration maps is still outstanding, as are full runtime wiring and
live two-client validation.

### Private activation registration state

The mandatory bending graph now carries bound, multi-ability and global activation
registrations together with discovery membership and their captured object aliases.
Installation validates portable callbacks before swapping private registry identities.
Discovery uses a concurrent map directly rather than an unsupported backed key-set
view. Private handled-input tracking has its own checkpointed stack, so replay does
not mark an outer live input as handled; capture rejects an unfinished input boundary.

Both transfer modes now exercise the real imported registry during late-input replay.
Corrected input replaces predicted captured-state mutations and dynamic registrations,
while ability-held callback aliases remain shared with the private registry. Live
registrations and captured ability state remain unchanged. This establishes import
and replay isolation, not the settled handler merge back into live registrations;
that lifecycle integration, native runtime assembly and two-client testing remain
required before live rollback can be enabled.

### Participant activation restoration

Settled common teardown now merges participant-owned activation callbacks in bound,
multi-ability and global lists. Ownership is resolved from captured references using
the same traversal as scheduled work, without ability-name filters. Restored callbacks
share the restored ability graph; aliases to existing registration maps/lists reconnect
to live containers. Unrelated callbacks remain current live policy, including additions
made after restoration preparation. Changed participant membership or replaced list/group
identities reject validation before common writes; cleanup retries preserve newer input
and registrations. Replacement keeps unrelated handler ordering.

Both transfer modes test live dispatch after handoff, captured-state continuity, bound
and multi-handler rebinding, registration conflicts, outsider preservation and idempotent
cleanup. This is the participant-owned merge, not a general merge of mutable shared
hook state: participant-free shared captures need explicit ownership/service policy.
Production startup must also gate participant-owned handlers against invocation by
outside inputs while their roster is frozen. Those service/ownership boundaries remain
part of runtime assembly; the full native bootstrap and real two-client test gate are
still incomplete.

### Live activation ownership gate

Live activation dispatch now checks captured callback references as well as the input
player. A handler referencing any reserved participant is skipped outside private
simulation, even when an outsider generated the input or the callback also references
outsiders. Ownership is reevaluated on each invocation so new registrations and changed
receivers cannot bypass the gate. Private replay bypasses live reservations, and a
failed roster restoration retains the callback gate until successful release.

Tests exercise outsider inputs, unrelated callbacks continuing normally, registrations
added during ownership, mutable captured receivers, mixed-player callbacks, failed
cleanup and private-domain bypass. This closes the direct activation invocation gap
above. Opaque shared services still require explicit ownership boundaries; registration
policy changes during a running match and the complete native bootstrap remain part
of the production integration audit. These tests do not establish live duel readiness.

### Native action remainder audit: hotbar selection

Inspection of the packaged Paper ServerGamePacketListenerImpl.handleSetCarriedItem
shows an unimplemented production action boundary. RollbackInputActions.slot currently
runs the common bending handler and then writes the selected inventory slot directly.
PaperRollbackInventory and FabricRollbackInventory forward that write to the native
inventory setter. That does not perform the native input transition: Paper first rejects
immobile/no-op selection, calls PlayerItemHeldEvent while the previous slot is still
selected, honors cancellation, stops main-hand item use on accepted change, then writes
the slot and performs the captured immediate equipment-update policy.

The execution adapter currently delegates SLOT_CHANGE's remainder to an unimplemented
production Services.action. Completing that path requires deferring the inventory write
until a native selection operation has accepted it. Both loaders need the same private
event policy and main/off-hand item-use behavior, with accepted/cancelled/no-op changes
and late-input rewind tested. Idle bookkeeping and correction packets must remain at
the appropriate live/provisional output boundaries. A raw inventory setter or a fixture
Services.action implementation is not sufficient evidence for this action.
