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
installed jar (including on Windows). It replaces that file in place, so its
filename may retain the old version; the mod metadata contains the new version.
It keeps a temporary backup and installation log in
`config/projectkorra/updater/`, cleaned up after the next successful launch.
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
