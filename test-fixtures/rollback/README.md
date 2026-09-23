# Shared native rollback fixtures

## World services

`world-settings.base64` contains 2,018 bytes captured by
`PaperRollbackWorldServicesNativeTest` from the installed Paper native default feature
set, with fire damage disabled and maximum entity cramming set to 9. It includes all
58 available rules, game tick 20, HARD difficulty, sea level 63, world/sound seeds 57/53,
and the fixture's captured combat policy. Both loaders consume this startup component;
Paper compares newly captured bytes. The full live-world capture entry point additionally
reads Paper/Spigot configuration through compiled APIs, without retaining its world.

`world-random.txt` records native world draws (long, double, Gaussian) followed by the
separate sound stream's first long. `native-random.txt` records broader actual Paper
draws/forks for seeds 0, 57, 7001 and both signed long extremes. Native Paper tests remain
the authority for these files. Fabric checks its private compatibility stream and the
RNG installed on an imported player against the same values, including after rewind.
The installed Paper runtime rounds double draws through float; replacing this reference
with Fabric's default output would hide a real replay discrepancy.

For an intentional schema/runtime change, use the `Current Paper world settings fixture`
or `Current Paper random fixture` native assertion output, inspect the change, then rerun
both loaders. These files do not include terrain lighting, border/environment inputs,
event handlers, live transport or arbitrary running RNG state.

`environment.base64` contains the separately captured registered dimension type,
weather-layer flag and native rain/effective-thunder gradients. `environment-values.txt`
records 864 native Paper values: all 36 primitive-valued environment attributes across
Overworld/Nether/End, day/night, dry/wet and direct/weighted biome reads. The weighted
case combines desert and soul-sand-valley attributes. Paper additionally checks every
registered attribute, including nonprimitive values, against its native default-layer
builder before comparing this reference. Fabric compares the same scalar reference;
both loaders test restoring weather, time, noise biomes and the derived native caches.
For intentional changes, inspect `Current Paper environment values` and
`Current Paper environment seed` from `PaperRollbackEnvironmentNativeTest` and rerun
both loaders. These fixtures do not prove live weather evolution, custom registry
transfer, sky lighting or border behavior.

`border.base64` is an exact native Paper border captured three ticks into an eleven-tick
resize from size 41 to 7. It preserves the original curve and previous size, with an
offset center and a radius limit that clamps some bounds. `border-frames.txt` contains
twelve native frames through completion: size, remaining ticks, speed, stage, bounds
at partial ticks 0/.25/1, point/box containment and distance. Paper verifies its private
adapter against the native source and compares collision shapes; Fabric imports the
same seed and scalar frames. Both loaders verify geometry/settings/clock restoration
and world-service ticking. For intentional native changes, inspect `Current Paper
border seed` and `Current Paper border frames` from `PaperRollbackBorderNativeTest`
before replacing these references. The fixtures do not prove live Bukkit border event
delivery or session startup/rendering.

## Combat frames

`native-combat-frames.txt` contains 42 frames captured from the owned Paper 1.21.11
ServerPlayer runtime, using the native execution integration test's captured arena,
default survival policy and dynamically registered accelerating projectile/defender
volume. There are two players and seven ticks per scenario: `0` takes the hit, `1`
strafes away, and `2` jumps. Both loaders read this same reference and compare exact
movement, bounds, velocity-update flag, health and age. It is a readable test reference,
not a network format or coverage of all server/client behavior.

Paper supplies the authoritative reference. If a native version or intentional physics
policy changes, inspect that change and recapture the Paper frames from
`PaperRollbackExecutionNativeTest.nativeCombatFramesMatchThePaperReference`, then run
the matching Fabric test. Do not replace the reference with Fabric output to hide a
client/server difference. The full private ServerPlayer maintenance and event/configuration
parity on Fabric are separate outstanding work.

The Fabric execution suite also pairs authoritative and replica runtimes with this dynamic
combat scenario. A native use-item packet enters the generic client input collector and
the negotiated-runtime owner, which predicts before emitting its input packet. The test
encodes and assembles real authority publications before applying a
late jump or finalizing a shot that the server never received. Both cases retract native
predicted damage and knockback and compare the resulting kinematics and finalized output.
Corrections do not resend physical input. This exercises input ownership, correction
transport and private simulation, not live rendering or complete
bootstrap/state transfer.

## Terrain transfer

`terrain.base64` is version 1 of the bounded common terrain wire format, produced by
`PaperRollbackTerrainCaptureNativeTest` through the production capture and encoder.
Its eight cells include a top stone slab, fire with age 7 and a north face, a live native
chest holding four diamonds, and a separately packed chest tag. Light, smoothed desert
and noise plains biome keys, temperature and humidity are preserved. The fixture world
is read-only test state; no server or live world is used to produce the reference.

The Paper test compares newly captured bytes with this reference. Fabric decodes and
re-encodes the same bytes, checks native NBT and typed block properties, and verifies slab
collision and provisional removal/restore. For intentional format/native changes, inspect
the Paper test's `Current Paper terrain fixture` assertion output and update this file from
that capture, then run both loaders' tests. Never regenerate it from Fabric to conceal a
mapping difference. This component does not include player/bending state or a live bootstrap.

## Player value fields

`player-values.base64` is version 1 of `RollbackPlayerValues`, captured by
`PaperRollbackPlayerSeedNativeTest` through the production player seed and encoder.
It contains all 149 value fields from a private Paper ServerPlayer: position `(0.5,1,0.5)`,
velocity `(0.05,0,0.08)`, age 123, damage immunity/hurt timers, both velocity flags,
offhand swing state, supporting block, dimensions/attachments, experience, food and
Paper-specific state. The fixture explicitly sets head rotation to 20 degrees because
the native constructor otherwise introduces random head jitter.

Paper imports/re-exports every field and compares these bytes. Fabric checks all fields,
applies its 117 native counterparts, retains the 32 Paper/server fields separately and
re-encodes identical bytes before and after a native checkpoint restore. The retained
values do not claim that Fabric implements the corresponding Paper behavior yet.
This is a value-field component, not a complete player or session snapshot.

For an intentional format/native change, inspect the Paper test's
`Current Paper player-values fixture` assertion and update this file from that capture;
run both loaders' tests afterward. Do not regenerate from Fabric to hide mapping errors.

## Player vitals

`player-vitals.base64` is version 1 of `RollbackPlayerVitals`, captured by
`PaperRollbackPlayerSeedNativeTest` through the production player seed. It contains full
native tracked state (health 17, air 180 and crouching pose), a 40-tick speed effect with
an amplifier of 2 and a hidden 200-tick effect, permanent and temporary movement-speed
modifiers, and a registered follow-range attribute with base 42 outside the default
player supplier.

Paper verifies import/re-export and rejects malformed components without import hooks or
partial updates. Fabric imports this same capture into an owned native player, compares
tracked bytes and attribute values, and compares decoded effect NBT because native
compound-key ordering may differ between loaders. It then checks independent repeated
imports and exact restoration of its captured bytes after mutation and rollback.

For an intentional format/native change, inspect the Paper test's
`Current Paper player-vitals fixture` assertion and update from that capture, then run
both loaders' tests. This component requires matching native protocol and registries;
it is not a complete player/session snapshot or live renderer integration.

## Player items and cooldowns

`player-items.base64` is version 1 of `RollbackPlayerItems`, captured through the
production Paper player seed in `PaperRollbackPlayerSeedNativeTest`. It contains a
named diamond sword with damage 7 and pop timer 4, an equal but separate sword, boots,
inventory/ender-chest roots, active-use/last/spin item references and equipment caches.
The selected slot is 3 and the custom inventory stack limit is 32. Cooldown counter 18
accompanies ender-pearl interval `[10,50]` and custom group `test:group` interval `[3,44]`.

Paper compares re-encoded bytes and checks aliases, independent decoding and rejected
imports without callbacks or partial target updates. Fabric compares native item
component equality (NBT key order may differ), every table index/root and cooldown
value. Native checkpoint tests mutate the items and inventory limit, expire cooldowns,
then restore exact captured bytes and shared references. A fresh ordinary inventory
still uses vanilla's stack limit of 64.

For an intentional format/native change, inspect the Paper test's
`Current Paper player-items fixture` assertion and update from that capture, then run
both loaders' tests. The item graph covers these player roots; aliases to separate
bending/world components require the complete session assembly.

## Player controls and contact state

`player-context.base64` is version 1 of `RollbackPlayerContext`, captured through the
production Paper player seed in `PaperRollbackPlayerSeedNativeTest`. Its abilities allow
flight, use fly/walk speeds `.08`/`.12`, and retain the last received input flags and
known client movement `(.15,-.05,.25)`. It includes Unicode entity tags, a collision
exemption UUID, water/custom-fluid contact caches and piston offsets `(.2,-.3,.4)`.
Jump/eating offsets are `-100,000,000`/`-200,000,000` nanoseconds from the capture clock.

Paper and Fabric import/re-export identical bytes and test native checkpoint restoration.
Fabric restores Paper-only input, known movement, collision policy and timers as retained
state; implementing their corresponding Paper behavior is still required before live
simulation. Both adapters reject clock overflow or collision with the inactive eating
sentinel before modifying the target. Paper also rejects those clocks before creating
and registering a private player.

For an intentional format/native change, inspect the Paper test's
`Current Paper player-context fixture` assertion and update from that capture, then run
both loaders' tests. This is separate from the existing collision-query context adapters.

## Player combat history

`player-combat-a.base64` and `player-combat-b.base64` are version 1 captures of
`RollbackPlayerCombatData` from `PaperRollbackRosterSeedNativeTest`. They describe players
with UUID low bits 7001 and 7002 at age 100 and world tick 20. Both players have native
damage history from the other; player A retains a kinetic contact with B at tick 18.
Player B also retains A as an explosion cause and has four combat entries. Two entries
share a positioned damage source, while a third has an equal but distinct source. The
history includes Bukkit MAGIC/CUSTOM causes, critical flags, an event damager, stored
position `(1.2,3.4,5.6)` and fall-location/distance metadata.

Both loaders import these captures and re-export identical bytes. Tests check private
attacker/target references, source aliases, cooldowns, independent repeated imports and
native checkpoint restoration. Fabric additionally exercises native damage after import
and combat-history expiry. A bad damage type on the later player must leave the whole
target roster unchanged. Paper-only metadata is retained on Fabric; its event-policy
behavior is not implemented by this transport.

For an intentional format/native change, inspect the Paper test's
`Current Paper player-combat-a fixture` and `Current Paper player-combat-b fixture`
assertions and update from those captures, then run both loaders' tests. These components
do not include non-player/block/custom damage sources or complete session assembly.

## Combined native roster

`player-roster.base64` is version 1 of `RollbackRosterData`, captured in
`PaperRollbackRosterSeedNativeTest`. It combines every intrinsic player component for
UUIDs ending in 7001/7002 at world tick 20, with native entity IDs 701/702. The first
player uses adventure mode, two texture properties (one signed), custom client settings,
and a damaged diamond sword shared between inventory slot 0 and ender-chest slot 2.
The second uses survival mode and has taken four damage from the first. Private RNG
seeds are 7001/7002; relative clocks use the capture epoch of 5,000,000,000 nanoseconds.

Paper compares the whole capture against its native cohort import. Fabric constructs
the roster through the production bootstrap, compares every component (using semantic
NBT equality across loaders), then checkpoints and restores another native hit, movement,
item mutation and random-number generation. Repeated imports must own separate bodies
and item instances. This is intrinsic player data; external service, terrain and bending
graphs must accompany it before a live session can begin.

For an intentional format/native change, inspect the Paper test's
`Current Paper player-roster fixture` assertion, update from that capture, and run both
loaders' tests. Do not regenerate it from Fabric.

## Item components

`item-components.base64` is unnamed, uncompressed binary NBT: a compound type byte
followed directly by its contents, without a root name. Both native adapters decode
this same fixture. The document is equivalent to:

```snbt
{id:"minecraft:diamond_boots",count:1,components:{
  "minecraft:damage":17,
  "minecraft:custom_name":{text:"Rollback fixture",bold:1b,color:"gold"},
  "minecraft:custom_data":{opaque:[B;0b,2b,-1b],nested:{kept:"value"}},
  "minecraft:enchantments":{"minecraft:unbreaking":3},
  "!minecraft:attribute_modifiers":{}
}}
```

The fixture exercises registry references, formatted text, opaque nested data and a
removed default component. Encoders may emit compound keys in a different order;
the tests compare native item/component equality after decoding.
