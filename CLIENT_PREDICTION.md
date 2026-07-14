# Exact Fabric client prediction

This fork uses an authoritative-Paper-server, predicted-Fabric-client model. A compatible
Fabric client executes the **same ProjectKorra ability classes** as the Paper plugin;
it does not draw a generic replacement projectile. Constructors and `progress()`
therefore produce the same particles, sounds, source selection, temporary
blocks, falling blocks, display entities, ray traces, and velocity calls that
the ability produces in local play.

## Flow

1. The Fabric client advertises protocol 14 over vanilla `projectkorra:*` custom
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
4. The server runs the real ability from its actual player position and view,
   enforces its own cooldown and source rules, and replies with acceptance,
   authoritative origin, and cooldown expiry.
5. Acceptance keeps the local simulation. A small origin discrepancy translates
   origin-near `Location` fields in the predicted ability. Rejection removes the
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
- Every common-model `setVelocity` call reaches the native client entity
  immediately. A later matching vanilla velocity packet is treated as an
  acknowledgement and is not applied a second time. A materially different
  vector remains authoritative and is applied normally.

## Hit registration and trust boundary

Client damage calls do not change health. They send a hit claim containing the
action sequence, client tick, target entity ID, and contact point. The server:

- rate-limits and session-validates claims;
- maps the client tick into a bounded 12-tick history window;
- checks finite coordinates, target state, rewound hit box, distance, view cone,
  and line of sight; and
- only exposes an accepted rewound player to the **real server ability's nearby
  entity query**. The real ability still decides whether and how much to damage.

The server never accepts client health, cooldown, block, element, bind, source,
or origin state as authority. Claimed yaw/pitch is not used for validation; the
server player's own view is used.

## Addons and compatibility

The bundled JedCore, ProjectAddons, ChiRework, and Hyperion sources are started
on both logical sides and run their actual classes. Every `Config` created by a
bundled addon is automatically registered for public prediction sync. An addon
may also register a tighter server hit envelope through
`PredictionSnapshotBuilder.registerProfile`.

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
- The server runs Paper and installs `ProjectKorra-1.10.2-bukkit.jar` from this fork.
- Predicting players install `ProjectKorra-1.10.2-fabric.jar` in their Fabric client.
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
