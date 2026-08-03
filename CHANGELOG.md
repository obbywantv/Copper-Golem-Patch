# Changelog

## 0.2.0

First public release.

### Compatibility

- Supports Minecraft **1.21.9 through 26.2** on Paper (previously 26.1.2 only).
- **One jar covers the whole range.** It is built against the 1.21.9 API and targets Java 21 bytecode, so the same file loads on Java 21 servers (1.21.9–1.21.11) and Java 25 servers (26.x) alike.
- 1.21.9 is the floor by definition — it is the version that introduced Copper Golems.
- All five supported versions are compiled and unit-tested on every commit.

### Fixed

- **Golems could stand idle for up to 5 minutes, ignoring empty chests placed right next to them.**

  When a golem gave up on an item that no chest would accept, it marked that item type unsortable for 5 minutes and refused to pick anything up until the mark expired. An empty-handed golem is only ever offered copper chests to look at, so it could never notice a storage chest you added in the meantime — and noticing one was the only thing that would have cleared the mark. Adding chests to unblock a stalled setup simply did nothing.

  The retry window is now 20 seconds: a stuck golem retries quickly, discovers newly placed chests, and resumes sorting.

### Changed

- `give-up.unplaceable-ttl-seconds` default: `300` → `20`. This value doubles as the retry interval, which is why it has to stay short. Existing `config.yml` files are never overwritten on update — if you ran an earlier build, change this value by hand to get the fix.

### Notes

- **Paper only.** Will not load on Spigot or Bukkit (it uses a Paper-specific API), and is not Folia-compatible.
- A storage chest accepts an item only if it is **empty**, or **already contains that item type**. Free slots alone are not enough — a chest holding a single stone will not accept dirt. This is vanilla Minecraft's own rule, not something this plugin adds, so plan roughly one destination chest per item type you want sorted. With fewer chests than item types, the leftovers stay in the copper chests.

## 0.1.0

Internal build, never released publicly. Supported Minecraft 26.1.2 only.
