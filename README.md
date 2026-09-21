# ProjectKorra Falling Fabric port

This source tree contains the Bukkit/common/Fabric port plus exact client
prediction. See [CLIENT_PREDICTION.md](CLIENT_PREDICTION.md) for the architecture,
security boundary, addon contract, and test matrix.

Build both sides with Java 21:

```shell
./gradlew :bukkit:shadowJar :fabric:build
```

Install the Bukkit artifact from `bukkit/build/libs/` on the Paper server. Install
the Fabric artifact from `fabric/build/libs/` only on clients that should receive
exact, zero-input-delay prediction. Paper communicates with those clients over
vanilla plugin/custom-payload channels; it does not require Fabric Loader.
Unmodded clients retain the normal server-only path.

Client prediction rendering supports Minecraft's default renderer, Sodium, and
VulkanMod. The renderer mods are optional and are not bundled in the ProjectKorra
artifact; use a version built for the same Minecraft release.

## AirBlast stamina

Set `Abilities.Air.AirBlast.StaminaEnabled: false` in `config.yml` to disable
AirBlast's stamina requirement, drain, regeneration delay, and stamina-based
speed, range, and push scaling (including self-blasts and slides). It defaults
to `true`. Other abilities and the shared stamina display keep their settings.

## EarthShell

EarthShell is a two-move combo: **Shockwave (hold sneak) → EarthBlast (left-click
while still sneaking)**. Activate on earth or up to two blocks above it. The dome
stays anchored to the ground and grows taller to cover you; you can fall inside it.
Tune this allowance with `MaxHeightAboveGround` (blocks above the earth's surface).
Nearby walls count as part of the shell, which fills the open space around you
without replacing those walls. Keep holding
sneak to maintain the tight dome; release to shatter it and blast nearby living
entities outward with strong horizontal knockback and spinning block-display shards.
The fragments fan outward and shatter on impact, with dust at launch and on contact.
Each individual shard uses a wider swept hitbox; knockback and the air-move lock
apply once per target when a shard reaches their body. Walls block the shards.
`ShardHitRadius` controls each shard's hitbox radius (default `0.85` blocks).
It consumes the Shockwave charge. The burst does not add upward velocity or damage.
Airbenders hit by the burst have active AirBlast/AirScooter interrupted and both moves
put on cooldown for 3 seconds; longer existing cooldowns are preserved. Other moves
remain usable. Set `AirStunDuration` in milliseconds (or `0` to disable this effect).
Switching abilities, leaving the
shell, or reaching its duration limit dismisses it without a blast.

Source terrain stays intact unless your `/pk sourceholes` toggle is on. Both the
dome and any source holes restore when the move ends. Defaults are an 8-second
maximum hold, 7-second cooldown, 12-block burst range, and 4.0 knockback; tune these
under `Abilities.Earth.EarthShell` in `config.yml`.
`Duration: 8000` sets the maximum hold in milliseconds, counted from activation.
At the limit the dome and source holes restore automatically and the cooldown
starts, even if sneak is still held. Expiration does not trigger knockback.
The `Combination` list controls its inputs. Alternatives are
`EarthBlast:LEFT_CLICK` → `Shockwave:SHIFT_DOWN`, or
`RaiseEarth:LEFT_CLICK` → `Shockwave:SHIFT_DOWN`.

## LightningPunch

Left click LightningBurst, then switch to FirePunch to ready a lightning fist.
Your first left click on an enemy throws the punch; no extra activation click is needed.
The hit deals 3 damage (1.5 hearts), always stuns for
`Abilities.Fire.Lightning.StunDuration`, and puts FirePunch on its normal cooldown.
Once readied, a missed melee swing consumes the fist and uses only LightningPunch's
shorter miss cooldown. Selecting FirePunch itself readies the fist without consuming it.
Lightning cannot be bound in any slot. The effect manually draws an expanding
electric-spark ring with FirePunch's impact tilt and a sky-blue flash, without fire
damage or burning. Tune damage, the combo input, `Cooldown` (successful hit, default
4000 ms), and `MissCooldown` (default 1000 ms) under `Abilities.Fire.LightningPunch` in
`config.yml`. The default input is `LightningBurst:LEFT_CLICK` followed by
`FirePunch:SLOT_CHANGE`. Existing installations using the old default click input
are migrated automatically; other custom combinations are preserved.

## MetalCable

MetalCable uses interpolated block displays for its metal hook, continuous cable,
and grabbed terrain. The cable has damped rope motion with pinned endpoints, sag,
and tension when pulling. Its segments are reused and capped at 48 per cable.
The hook sweeps against actual block collision shapes and attaches to the hit face;
moving targets and grabbed blocks update the attachment before each pull.
Grabbed blocks first lift straight out of the hit face, then settle in front of
you while sneak is held. Held blocks stop against obstacles; clicking throws them
with simulated motion and swept volume collisions, shattering on impact. They
cannot fall into place as permanent blocks.
The existing click/sneak controls, damage, range, cooldown, and source regeneration
settings remain under `Abilities.Earth.MetalCable` in `hyperion/config.yml`.

## BetterModel mob hitboxes

The Bukkit build optionally recognizes BetterModel body hitboxes in ability entity queries.
Each body part supplies its actual collision bounds while the owning mob supplies health,
identity, metadata, damage, and knockback. Overlapping parts and their clickable companions
produce one target per query; passenger seat anchors are excluded. Ordinary armor stand
filtering remains controlled by the existing configuration.

This integration targets BetterModel's 3.4 hitbox API and does not bundle BetterModel or
require it on servers that do not use custom models. It changes the Bukkit plugin only;
existing clients, models, and resource packs do not need an update for server hit detection.
Automated checks cover upper-body contacts outside the vanilla host box, ray/sphere queries,
gaps between parts, duplicate hits, seats, removed parts, and effects reaching the host.
Visual alignment and live combat still need checking on the server.

## Publishing jars to GitHub

The `publishGithubJars` task builds the shaded Bukkit jar and remapped Fabric
jar, creates a real release under
`https://github.com/MCAvatarPvP/Bending/releases`, generates release notes from
the pushed commit history, and uploads both jars plus `SHA256SUMS`. Authentication
is read privately from the credentials already registered with Git Credential
Manager/Git Bash; no token environment variable is required. It refuses to
publish uncommitted or unpushed source.

Commit your source changes first, then run:

```powershell
./gradlew publishGithubJars
```

The defaults publish `v1.10.2` from the `master` branch through the `bending`
remote. Override the release name when needed:

```powershell
./gradlew publishGithubJars -PreleaseName="ProjectKorra 1.10.2"
```

Other options are `-PgithubRemote=<remote>`, `-PgithubBranch=<branch>`,
`-PreleaseTag=<tag>`, `-PreleaseDraft=true`, and `-PreleasePrerelease=true`.
Change the root project version before publishing a new version. Re-running the
same tag replaces assets with matching filenames.

Use `-PgithubReleaseDryRun=true` to verify the build, pushed commit, and stored
GitHub authentication without creating or changing a release.

The Fabric auto-updater reads the latest release from
`https://github.com/MCAvatarPvP/Bending/releases`. On the title screen, players
can choose **Install update** to download the Fabric jar and verify its SHA-256
digest, mod identity, version, and Minecraft/Loader/mod dependencies. After
download, **Quit to apply update** closes Minecraft; players relaunch through
their usual launcher. **Keep playing** applies the update when they quit later.
An independent Java helper waits for Minecraft to exit before replacing the
installed jar (including on Windows and in Modrinth App profiles). Pandora uses
a temporary runtime mods folder and restores `original_mods` at exit; the helper
instead replaces the matching inactive jar in `original_mods` before quitting,
so Pandora keeps the update. Atomic replacement preserves shared launcher cache
files and other instances even when mods are hard linked. It replaces the instance's file in place, so its
filename may retain the old version; the mod metadata contains the new version.
It keeps a temporary backup and installation log in
`config/projectkorra/updater/`, cleaned up only after the installed jar's hash
matches the completed update. If a launcher restores the old jar, these
diagnostics are retained.
Failed checks/downloads leave the installed mod untouched.

Players need to install this updater-enabled build once. Future updates must be
published as a higher project version with a matching
`ProjectKorra-<version>-fabric.jar` asset and GitHub SHA-256 digest; development,
draft, prerelease, and incompatible builds are not installed automatically.
Update checks are enabled by default; use the JVM argument
`-Dprojectkorra.updater.enabled=false` to opt out.

## Credits and upstream projects

This project is based on [ProjectKorra](https://github.com/ProjectKorra/ProjectKorra).
ProjectKorra and its contributors retain credit for the upstream code on which
this fork is built.

The following addon projects and abilities have also been embedded and adapted
for this repository's common, Bukkit, and Fabric architecture. Credit remains
with their original creators:

- [JedCore](https://github.com/JedK1/JedCore) — JedK1
- [ProjectAddons](https://github.com/Simplicitee/ProjectAddons) — Simplicitee,
  with individual ability credits retained in source (including Whip by
  NickC1211)
- Hyperion — [Moros / PrimordialMoros](https://github.com/PrimordialMoros), with
  its existing copyright and GPL notices retained in source
- ChiRework — Literka (code), Rakion and Magikas (concepts)
- Molten — Macie_Q
- Hackathon Pack abilities and Toss — Hiro3

These acknowledgements supplement, and do not replace, the copyright, license,
and author notices retained in individual source files.
