# Copper Golem Patch

A Paper plugin that gives Copper Golems a **persistent memory** of what each chest holds, and lets them **share it with each other**, so they stop re-checking chests they already know are wrong.

![Copper Golem Patch](Images/Copper%20Golem%20Patch.png)

## The problem

In vanilla, a copper golem re-evaluates every chest on every pass: it walks over, examines it for ~3 s, and leaves if the contents don't match. It learns nothing. Worse, if it picks up an item no chest will accept, it holds onto it indefinitely and the whole sorting operation stalls.

## What the plugin does

| Mechanism | Effect |
|---|---|
| **Per-golem memory** | Each golem remembers what it saw in each chest, persisted in its PDC (survives restarts). |
| **Gossip** | Two golems passing each other exchange memories — a golem knows about chests it never visited. |
| **Informed skipping** | A chest known not to match is rejected without the walk and the examine. |
| **Stall breaker** | An item nobody accepts is put back in a copper chest and marked "unplaceable", shared by gossip, and retried automatically once room frees up. |
| **Deprioritising** | If the first slot of a copper chest holds an unplaceable item, it is swapped with the first sortable one — otherwise it would block everything behind it. |

![Memory](Images/MEMORY.png)
![Gossip](Images/GOSSIP.png)

## Compatibility

| Minecraft | Java required | Status |
|---|---|---|
| 1.21.9 | 21 | ✅ |
| 1.21.10 | 21 | ✅ |
| 1.21.11 | 21 | ✅ |
| 26.1.2 | 25 | ✅ |
| 26.2 | 25 | ✅ |
| 26.3 | 25 | ✅ |

**1.21.9 is the absolute floor**: it is the version that introduced Copper Golems, so there is nothing to patch before it.

A **single jar** covers the whole range. It is compiled against the 1.21.9 API and emits Java 21 bytecode, which makes it loadable on both Java 21 and Java 25 servers. Every version in the matrix is recompiled and tested on every commit ([CI](../../actions)).

Requires **Paper** — the plugin depends on `ItemTransportingEntityValidateTargetEvent`, a Paper API, so Spigot and Bukkit will not work. Not Folia-compatible.

## Installation

1. Grab `copper-golem-patch-x.y.z.jar` from the [releases](../../releases).
2. Drop it into `plugins/`.
3. Restart the server. `plugins/CopperGolemPatch/config.yml` is generated with defaults that work as-is.
4. On update, if your `config.yml` is older than the jar (`config_version`), it is backed up and replaced automatically — re-apply custom values from the `.bak-…` file if needed.

## Commands

`/cgp` — permission `coppergolempatch.admin` (op by default).

| Subcommand | Effect |
|---|---|
| `/cgp reload` | Reloads `config.yml` at runtime. |
| `/cgp info` | Memory state of the nearest golem (within 10 blocks): known chests, age, unplaceable items. |
| `/cgp labels` | While looking at a chest: shows the item-frame label type(s) the plugin sees. |
| `/cgp toggle` | Flips `enabled` and saves. |

## Configuration

Every setting is documented inline in [config.yml](src/main/resources/config.yml). The main ones:

| Key | Default | Purpose |
|---|---|---|
| `enabled` | `true` | Kill switch: `false` restores vanilla behaviour without uninstalling. |
| `gossip.enabled` | `true` | Memory sharing between golems. |
| `gossip.radius` | `12.0` | Max distance (blocks) for an exchange. |
| `gossip.interval-ticks` | `30` | Check frequency (20 ticks = 1 s). |
| `memory.max-entries-per-golem` | `32` | Chests remembered per golem. |
| `memory.entry-ttl-seconds` | `1800` | Forget a chest that is never revisited. |
| `give-up.after-rejections` | `50` | Guard rail on how long a search may run. |
| `give-up.unplaceable-ttl-seconds` | `20` | How long an item stays marked "unplaceable" — this doubles as the retry clock, and it is what lets a stuck golem discover a chest placed after the fact. |
| `item-frame-labels.enabled` | `true` | Frame item type steers which chest to try; still vanilla type-only matching (dura/enchants ignored). |

## Good to know

A storage chest accepts an item only if it is **empty**, or **already contains that item type**. Free slots alone are not enough — a chest holding a single stone will not accept dirt.

This is vanilla Minecraft's own rule, not something this plugin adds; you can confirm it with `/cgp toggle`. In practice: plan roughly **one destination chest per item type** you want sorted. With fewer chests than item types, the leftovers stay in the copper chests, plugin or not.

With **item-frame labels** enabled, a framed chest accepts **only** that frame's item type (even when the chest is empty). The plugin only denies wrong targets through Paper's validate API — it does not rewrite chest inventories.

## Development

The default build is the floor build, and it is the distributable artifact:

```bash
./gradlew build
```

Compile and test against another version in the matrix:

```bash
./gradlew build -PmcVersion=26.3
```

Run a test server (world isolated in `run/<version>/`, never committed):

```bash
./gradlew runServer -PserverVersion=26.3
```

Check that the floor jar really does load on a recent server — the two properties are independent:

```bash
./gradlew runServer -PmcVersion=1.21.9 -PserverVersion=26.3
```

> Worlds are not backward compatible: a world opened in 26.3 will not reopen in 1.21.9. Hence one run directory per version.

### Adding a version to the matrix

One line in `supportedVersions` in [build.gradle.kts](build.gradle.kts), plus the matching entry in the matrix in [ci.yml](.github/workflows/ci.yml). If CI passes, the version is covered with no code change.

> The CI job names double as the required status check names in the branch ruleset. Renaming one silently invalidates the configured requirement — update the ruleset alongside.

### Branches

`main` is always releasable and protected. Everything goes through a short-lived branch (`feat/…`, `fix/…`) and a pull request validated by CI.

No per-Minecraft-version branches: a single source covers the whole matrix, and CI proves it on every commit. Per-version branches would duplicate the code for no benefit.

### Cutting a release

```bash
git tag v0.3.0 && git push origin v0.3.0
```

[release.yml](.github/workflows/release.yml) revalidates the full matrix, builds the floor jar, and creates the GitHub release with the jar attached.

Bump `version` in [build.gradle.kts](build.gradle.kts) **before** tagging — the tag does not set it, and the jar would otherwise ship claiming the previous version.

## Changelog

See [CHANGELOG.md](CHANGELOG.md).

## Authors

- [Craftekture](https://github.com/Craftekture) — original plugin
- [ObbywanTv](https://github.com/ObbywanTv) — co-author (26.3 support, item-frame labels)

## License

[MIT](LICENSE)
