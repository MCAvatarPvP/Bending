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
- `block`: direct-block receipts, TempBlock ledgers, teardown fences, and falling blocks.
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
| `block` | Direct block ownership, TempBlock semantic pairing, packet masking, chunk restoration, and teardown fences |
| `config` | Applying Paper's public configuration and permission projection |
| `entity` | Entity aliases, display concealment, and exact TempFallingBlock receipts |
| `movement` | Predicted impulses, ownership receipts, authoritative knockback staging, and writer fences |
| `state` | Cooldown authority plus player abilities/experience packet reconciliation |

The wire records and Fabric payload registration live in `fabric/prediction/protocol`.

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
- TempBlock ghosting or packet masking is in `ClientTempBlockAuthority` and common `block` policy.
- Falling/display entity matching is in `ClientEntityReconciliation`.
- Flight/experience packet ownership is in `ClientPlayerStateAuthority`.
- Cooldown timing is in `PredictionCooldownAuthority` and common `state` policy.
- Handshake, world/session replacement, and payload ordering are in `PredictionClient`.
- Input execution, generic native association, and ability reconciliation are coordinated by
  `ExactPredictionRuntime`.
