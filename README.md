# BedWars1058-MultiGroup

Play one BedWars1058 map in several arena groups, with a configuration per group and **one copy of the map**.

BedWars1058 ties every arena to exactly one group (`group:` in `plugins/BedWars1058/Arenas/<arena>.yml`) and every arena to its own world. Plugins that build game modes on top of groups (`SoloRush`, `Doubles_OneBlock`, `4v4v4v4`...) therefore force you to copy a map, and its whole configuration, once per mode. MultiGroup removes that:

* A map file `plugins/BedWars1058-MultiGroup/maps/<arena>.yml` lists the extra groups the arena's map is played in, and named configurations (overrides of the arena's own settings).
* For every extra group a BedWars1058 arena ("variant", e.g. `lighthouse-doubles`) is generated. It is a normal arena for BedWars1058 and for every other plugin (`getGroup()` is `Doubles`), but it has **no map of its own**: its world is a temporary copy of the original map, made only while the variant is loaded and deleted afterwards.
* Each group gets the configuration whose name matches the group name best (see *Matching*). `SoloRush` gets `solo`, `Duos` gets `doubles`, `4v4v4v4` gets `squads`, unless you pin a choice.
* Editing the map itself (`/bw setupArena lighthouse`) updates every variant: they inherit the arena's settings and use its map.

## Install
1. Put `BedWars1058-MultiGroup-1.2.0.jar` in `plugins/` next to BedWars1058 (required).
2. Recommended: BedWars1058-MapLoader **3.6.0** or newer (see below).
3. Start the server once, then e.g.:
   ```
   /bwmg create lighthouse
   /bwmg group add lighthouse Doubles SoloRush
   /bwmg config create lighthouse doubles
   /bwmg set lighthouse doubles maxInTeam 2
   /bwmg reload
   ```

## How variants are loaded
| Installed | What happens |
|---|---|
| **BedWars1058-MapLoader 3.6.0+** | MultiGroup registers the variants with MapLoader's alias hook. MapLoader loads them from its library like any other arena: parked, listed in the selector, loaded just-in-time, unloaded when idle. The map is stored once in the library. |
| BedWars1058-MapLoader before 3.6.0 | MultiGroup copies the variants' maps out of MapLoader's library itself. Works, but variants are always loaded (not parked) and MapLoader's idle unload recycles them. Update MapLoader. |
| Stock BedWars1058 | MultiGroup copies the map from BedWars1058's pristine `Cache/<map>.zip` (or the map's world folder) into a temporary world `<variant>` with a `.multigroup-runtime` marker, and removes it after the game. |

MultiGroup puts its restore adapter *in front of* the active one (BedWars1058's or MapLoader's). Every call about a normal arena is passed through unchanged; only variants are handled. Only folders carrying the `.multigroup-runtime` marker are ever deleted.

## Map file
```yaml
# plugins/BedWars1058-MultiGroup/maps/lighthouse.yml
groups: [Doubles, SoloRush, DoublesRush, "4v4v4v4"]   # patterns like *Rush work too
group-configs:          # optional: pin a group to a configuration ("base" = the arena's own config)
  DoublesRush: doubles
shared:                 # applied to every variant of this map
  allowSpectate: true
configs:
  doubles:
    match: [duos, 2v2]  # extra names this configuration answers to
    overrides:          # any arena setting; Team is replaced as a whole (replace-sections)
      maxInTeam: 2
  squads:
    overrides:
      maxInTeam: 4
      Team: { ... }     # a different team layout of the same map
    remove: [generator.Emerald]   # paths dropped from the arena's own configuration
```
The arena's own group (`Arenas/lighthouse.yml`) is always played by the arena itself. `maps/_example.yml` documents every key.

Generated arena files carry a `multigroup:` section and are rewritten on every start and `/bwmg reload`; edit the map file instead. A file with a variant's name that MultiGroup did not write is never touched (the variant is skipped with a warning that suggests `/bwmg import`).

## Matching
Names are compared as words (split on punctuation and camelCase, normalised through `matching.synonyms` and NvNvN team notation):

| Score | Rule | Example |
|---|---|---|
| 1000 | same name, ignoring case/punctuation | `solo-rush` = `SoloRush` |
| 900 | same words | `duos` = `Doubles`, `squads` = `4v4v4v4` |
| 600-899 | shared words, mostly how many of the configuration's words the group contains | `solo` for `SoloRush` |
| < 300 | one name inside the other, typos | `doubels` ~ `doubles` |

Below `matching.min-score` (150) the group plays the base configuration (`fallback-to-base`). Ties go to the word that comes first in the group name and are logged; `/bwmg match <map> <group>` shows all scores, `/bwmg fix` pins a choice.

## Existing copies are merged automatically
Servers that already copied a map per game mode (`lighthouse`, `lighthouse_doubles`, `lighthouse-solorush`...) are moved to the new system on the first start.

**Detection.** A copied map keeps its coordinates, so two arenas of one map mention the same locations (team spawns, beds, shops, generators, lobby). The plugin compares those, not names or world files, so it finds copies however they are called. The original is the arena whose name is the common beginning of the others' (`lighthouse`), preferring one that already has a map file.

**Merge** (`duplicates.auto-merge`, on by default; only at startup, before BedWars1058 reads its arenas). For every copy that has the same layout (>= 0.8 alike), a different group and a recognisable original:
* only what differs from the original is stored as a configuration in `maps/<original>.yml` (named after the copy's group; the copy's name is kept as an alias)
* the copy's group becomes a variant of the original
* the copy's arena file is moved to `backups/arenas/`; nothing is deleted. A plain world folder that would block the new variant is moved to `backups/worlds/`

**Not merged, only logged** (see `/bwmg duplicates`): copies in the *same* group as the original (a variant cannot replace them), arenas 0.5-0.8 alike (probably another layout of the map, e.g. fewer teams), and groups of arenas where no original can be told apart. Merge those by hand with `/bwmg merge <original> <copy>`.

**Freeing the space.** The copies' worlds are still stored after the merge. `/bwmg cleanup` lists them and `/bwmg cleanup confirm` deletes them through the active restore adapter (MapLoader keeps a backup according to its `backups-to-keep`). `/bwmg merge ... delete-map confirm` merges and deletes in one step.

## Commands (`/bwmg`, permission `bw1058multigroup.admin`)
| Command | Purpose |
|---|---|
| `status`, `list`, `info <map>` | what is loaded, variants and the configuration chosen for each group |
| `match <map> <group>` | scores of every configuration for a group (dry run) |
| `create <map>` | start a map file for an existing arena |
| `group add\|remove <map> <group...>` | play the map in more / fewer groups |
| `config create\|delete <map> <config> [copy-of]`, `config alias <map> <config> <names...>` | manage configurations |
| `fix <map> <group> <config\|base\|auto>` | pin a group |
| `set <map> <config\|shared> <path> <value>` | set an arena setting (YAML value: `2`, `true`, `[a, b]`) |
| `setloc <map> <config\|shared> <path> [center] [add]` | store your position in BedWars1058's arena format (`add` appends to a list such as `generator.Diamond`) |
| `unset`, `drop <map> <config> <path>` | remove an override / remove a setting of the arena |
| `duplicates` | arenas that are copies of one map, and why they are or are not merged |
| `merge all [delete-maps] [confirm]` | merge every clear duplicate (preview without `confirm`) |
| `merge <map> <copy> [config] [delete-map] [confirm]` | merge one copy (`import` is an alias) |
| `cleanup [confirm]` | delete the worlds of copies that were already merged |
| `reload` | apply the map files |

`/bwmg reload` registers new variants immediately, restarts idle variants whose configuration changed (and winds down removed ones); variants in a game, or parked by MapLoader, pick changes up after their next game. `/bw setupArena` and `/bw deleteArena` are blocked for variants.

## Compatibility
Spigot/Paper 1.8 - 26.x (compiled against the 1.8.8 API; the 1.13+ void generator is loaded by name), Java 11+ bytecode like BedWars1058 and BedWars1058-MapLoader. Kotlin is shaded and relocated (`dev.bwmultigroup.libs.kotlin`), so it never clashes with MapLoader's.

## Developer note
This plugin is coded with AI assistance and architected completely by me. Regardless, use at your own risk.

## License
This plugin is under the MIT License, check the [License](LICENSE) file for more details.