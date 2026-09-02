# Prediction architecture

ProjectKorra prediction has one supported network topology:

```text
Fabric client prediction  <---- custom payloads ---->  Paper authority
```

Fabric does not host a prediction server. A dedicated Fabric server continues to run normal
ProjectKorra gameplay, but it does not advertise, accept, or publish the prediction protocol.

## Common contracts

Code shared by Paper and the predicting client lives under
`common/.../projectkorra/prediction` and is grouped by behavior:

- `action`: execution context, deterministic action seeds, removal identity, and input poses.
- `authority`: visibility and region-protection snapshots.
- `block`: direct-block receipts, TempBlock ledgers/snapshot pruning, and falling blocks.
- `hit`: predicted contact evidence and bounded hit rewind calculations.
- `movement`: velocity ownership, receipt policy, and external-knockback fences.
- `state`: cooldown, player-ability, configuration, and snapshot ordering contracts.

These classes define loader-neutral policy. They do not own networking or Minecraft client state.

## Fabric client

`PredictionClient` owns the connection/session lifecycle and payload handlers.
`ExactPredictionRuntime` coordinates the common gameplay runtime. Stateful reconciliation is
delegated to focused components under `fabric/client/prediction`:

| Package | Responsibility |
| --- | --- |
| `action` | Generic Paper-to-local native sequence correlation and captured-pose matching |
| `block` | A render-only block compositor, direct-block ownership, TempBlock semantic pairing, and snapshot recovery |
| `config` | Applying Paper's public configuration and permission projection |
| `entity` | Entity aliases, display concealment, and exact TempFallingBlock receipts |
| `movement` | Predicted impulses, ownership receipts, authoritative knockback staging, and writer fences |
| `state` | Cooldown authority plus player abilities/experience packet reconciliation |

The wire records and Fabric payload registration live in `fabric/prediction/protocol`.

### Block-state invariant

The backing `ClientWorld` is server-owned. Predicted direct-earth changes and predicted
`TempBlock`s never call `ClientWorld#setBlockState`, and vanilla block/chunk packets are never
cancelled. Prediction is stored in two removable visual layers (`TEMP` above `DIRECT`). Every
locally proven drawable state is also submitted through an immediate per-frame foreground path:
block models cover direct moved earth and solid TempBlocks, while fluids use immutable geometry
captured from Minecraft's ordinary fluid tessellator. This keeps EarthBlast, EarthSmash, and
WaterManipulation visible even when they advance faster than an asynchronous chunk rebuild. Air
and server concealment remain terrain-only. Common gameplay reads the same logical composition
through `FabricPredictionMC`.

The vanilla terrain compiler, Sodium's optional `LevelSlice` integration, and VulkanMod's optional
`RenderRegion` integration all read the same render-only compositor. Sodium and VulkanMod remain
optional when absent, but an installed incompatible renderer fails startup instead of silently
exposing physical prediction duplicates. None of these paths mutates the world snapshot it reads.
The immediate foreground renderer uses Fabric's extracted render state and command queue, so its
block and fluid submissions stay independent of an OpenGL or Vulkan backend. Active foregrounds
end only on an explicit local lifecycle transition;
equality with the backing world is never treated as completion. A foreground-to-terrain handoff
is reserved for a settled static frame whose viewer state, announced server state, and physically
installed backing state all agree. Moving EarthBlast cells, conflicts, expiry, and rollback remove
their foreground immediately; they can never enter the timed handoff. Every expiry schedules a
final rebuild.

Client-owned falling blocks and the local player also sample this logical composition for movement
collision. This prevents EarthShard and a moving/ridden EarthSmash from colliding with Paper's
latency-delayed previous TempBlock frame. Remote entities and unrelated predicted entities keep the
authoritative chunk collision view.

Paper publishes a TempBlock prediction owner only for an authenticated exact-capable session whose
client advertised support for that ability. A physical layer carrying that provenance is concealed
from its owner only while its mapped local action has an active TempBlock or remains inside a
latency-aware, strictly bounded close grace. Coordinates are deliberately irrelevant, so Paper's
real copy can trail several cells behind without becoming visible; stale ownership still fails open
instead of granting permanent ghost air. The physical write is always installed in `ClientWorld`,
and remote/unowned layers remain visible. A concealed layer is transparent to a newer DIRECT visual
at the same coordinate and otherwise uses Paper's saved viewer-underlay. Exact action/effect pairing
is retained for lifecycle reconciliation but is not a coordinate prerequisite for concealment.
Closing fences survive intermediate vanilla fluid/block writes and are consumed only by the named
final restore or bounded expiry. A stream gap clears concealment and requests a replacement snapshot.
Clearing prediction merely clears visual maps; there is no saved client-world state to restore and
therefore no stale prediction write that can become a ghost block.

Paper sends a direct moved-earth receipt before performing its physical world write. The client
therefore tracks the announced state as a bounded pending write until the corresponding vanilla
packet has actually entered `ClientWorld`. A pending causal packet cannot be mistaken for an
external conflict or replace a newer coordinate-local visual revision. Revisions, rather than cast
numbers, define visual order because a long-lived projectile can write again after another action
has started. This is what keeps an old EarthBlast position visually air while Paper's delayed solid
and air packets both still update the real client world. Pending confirmations use the action's
bounded 4-40 tick measured confirmation window. RaiseEarth's coordinate-local coalescing exception
keeps ordered predecessor identities, consumes individual packets FIFO, and resolves full chunk
snapshots to the newest matching predecessor. A missing successor rolls back to the physical
predecessor instead of deleting an unrelated foreground. Its final frame closes only when every
live pillar created by that exact input has finished, so concurrent walls cannot freeze one another.
A receipt-only EarthBlast arrival that exists only on Paper's latency-offset
path keeps its pending identity while the exact server cause is open and stays concealed until its
departure AIR is physically installed. The ordered `AbilityRemoved` receipt closes that cause,
after which unresolved coordinates fail open inside a bounded confirmation grace. Projectile speed
and local coordinate drift therefore cannot expose a stationary Paper block, while neither a lost
departure nor a later external edit can leave a permanent visual mask.

TempBlock payloads carry a monotonic stream sequence. Full snapshots also carry a snapshot ID and
fragment index/count. The client stages every fragment, commits only the complete snapshot, and
prunes active layers omitted by that snapshot. A stream gap requests a replacement snapshot.

## Paper authority

Paper's endpoint lives in `bukkit/prediction/server`. Encoding and decoding are isolated in
`bukkit/prediction/protocol`; initial and region-protection snapshots live in
`bukkit/prediction/snapshot`.

Paper observes the real Bukkit input first, associates it with the client's action tag, runs the
ordinary ProjectKorra handler, and publishes the resulting ownership metadata. The client uses
that metadata to suppress only an exactly predicted echo. External damage, velocity, blocks, and
state changes remain authoritative and are applied after local progress when necessary.

## Native action correlation

Paper and client sequence numbers are separate streams. The client pairs a Paper native-action
receipt to the best unmatched local action with the same input kind, slot, ability, and captured
pose. Every later owned receipt is translated through that association; raw Paper ordinals are
never treated as client ordinals.

This generic correlation is also the recovery path for a locally missed AirBlast or combo input.
There is no AirBlast-specific parity tracker or trace protocol.

## Where to change behavior

- Velocity/knockback is in `ClientVelocityAuthority` and the common `movement` contracts.
- Predicted block composition is in `ClientBlockVisualOverlay`; TempBlock pairing and snapshot
  recovery are in `ClientTempBlockAuthority` and the common `block` ledger. Immediate models and
  captured fluid meshes are submitted by `PredictionBlockVisualRenderer` and
  `PredictionFluidMesh`. Vanilla, Sodium, and VulkanMod terrain composition are projected by
  `ChunkRendererRegionPredictionMixin`, `SodiumLevelSlicePredictionMixin`, and
  `VulkanRenderRegionPredictionMixin`; predicted movement collision is projected by
  `BlockCollisionSpliteratorPredictionMixin`.
- EarthSmash transition-position reconciliation is in `EarthSmashCheckpointPolicy` and
  `EarthSmash.reconcilePredictionCheckpoint`.
- Falling/display entity matching is in `ClientEntityReconciliation`.
- Flight/experience packet ownership is in `ClientPlayerStateAuthority`.
- Cooldown timing is in `PredictionCooldownAuthority` and common `state` policy.
- Handshake, world/session replacement, and payload ordering are in `PredictionClient`.
- Input execution, generic native association, and ability reconciliation are coordinated by
  `ExactPredictionRuntime`.
