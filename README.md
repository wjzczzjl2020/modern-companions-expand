# Modern Companions Expand

*A companion-combat addon for **Modern Companions** — hand your companions a legendary weapon and let them use it properly.*

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green?style=flat-square)
![Loader](https://img.shields.io/badge/NeoForge-21.1.244%2B-orange?style=flat-square)
![Language](https://img.shields.io/badge/Java-21-blue?style=flat-square)
![License](https://img.shields.io/badge/License-MIT-yellow?style=flat-square)
![Optional mods](https://img.shields.io/badge/Cataclysm%20%C2%B7%20Legendary%20Monsters-14%20weapons%20and%20shields-lightgrey?style=flat-square)

Give a companion one of **14 legendary weapons and shields** from
[L_Ender's Cataclysm](https://www.curseforge.com/minecraft/mc-mods/lendercataclysm) or
[Legendary Monsters](https://www.curseforge.com/minecraft/mc-mods/legendary-monsters), and it will wield
that weapon the way the name suggests: charging the Incinerator, throwing Brontes and waiting for the
hammer to come back, dashing with Soul Render, laying the Void Forge rune lattice — and *not* accidentally
nuking you while doing it.

This addon adds **no weapons, blocks, mobs or textures of its own**. The 14 weapons and shields stay exactly
as their source mods made them. It only teaches companion entities **when and how** to use the skills those
weapons already have, with damage and cooldown numbers taken from the source mods wherever possible.

## Table of contents

- [Features](#features)
- [Supported Weapons](#supported-weapons)
- [Requirements](#requirements)
- [Installation](#installation)
- [Configuration](#configuration)
- [Commands](#commands)
- [Known Limitations](#known-limitations)
- [Credits](#credits)
- [License](#license)

---

## Features

- **Autonomous skill usage.** A companion holding a supported weapon scans for a target, respects its own
  cooldown and the global silence window, and releases the weapon's skill by itself — the same pillars of
  fire, shockwaves, throws and rune lattices you get as a player.
- **Situational mode selection.** Weapons with two skills choose between them automatically: Soul Render
  dashes at a distant target and spiral-summons phantom halberds when surrounded; Ceraunus throws at range
  and fans out waves point-blank; Axe of Lightning alternates bolt barrage and electric burst.
- **Numbers from the source mods.** Damage, cooldowns and entity types are read from the source mods' own
  config and registries where their code allows it, so a companion's pillar of fire hits about as hard as
  yours does with the same weapon. Default cooldowns mirror the source mods and can be overridden in the
  config.
- **Ally protection on by default.** Shockwaves, pillars, waves and charges filter out players, other
  companions and the owner's pets — no damage, no knockback. Every protection can be switched off per
  skill in the config to get vanilla behaviour back.
- **No damage-rate-limit loopholes.** Sustained multi-hit skills (Meat Shredder) keep a shadow ledger of
  the victim's vanilla invulnerability ticks, so a shredder cannot be used to bypass the damage cap, and
  teammates' big hits are staggered instead of stacking into a spike.
- **Shield-passive exemption for companions.** When a companion blocks with a Frostbitten, Shulker or
  Spiky shield from Legendary Monsters, only the *special* blocking passive (freeze / levitation / thorns
  retaliation) is skipped — normal blocking (damage reduction, arrow blocks, durability loss, the blocking
  animation) is untouched. Players are unaffected.
- **Smart, not spammy.** Only one skill starts per scan, in a priority order **you** control, so a
  multi-weapon companion does not dump every cooldown at once. Apart from skills that intentionally lock
  melee (e.g., Brontes), normal melee attacks are not blocked.
- **Fully configurable, hot-reloadable.** One TOML file covers every skill's master switch, cooldown,
  ranges, thresholds and damage; the skill priority table is a JSON file you can apply in game with
  `/mce skills reload` — no restart. Config comments are bilingual: English first line, Chinese second.
- **Fails safe.** All cross-mod access is wrapped in guards: if an optional mod is missing, its skills are
  simply skipped. If an update renames a class or a sound, that one part goes quiet and the log says which:
  every such path is designed to fail safely, so the game keeps running. Everything is written to the log
  under `[MCE]`.

---

## Supported Weapons

14 weapon skill sets in total. Within each table, weapons are listed in the order the default priority
table runs them. 20 ticks = 1 second.

### From L_Ender's Cataclysm (9 weapons)

| Weapon | Item ID | What your companion does | Default cooldown |
|--------|---------|--------------------------|------------------|
| Bulwark of the Flame | `cataclysm:bulwark_of_the_flame` | Charges through a target and launches whoever is in the way | 200 t (10 s) |
| Meat Shredder | `cataclysm:meat_shredder` | Sustained shredding on a close target (hostile targets only), with stagger and invulnerability handling | 60 t (3 s) after each run |
| Soul Render | `cataclysm:soul_render` | Dashes at a distant target, or spiral-summons phantom halberds when surrounded | 100 t (5 s) |
| Ceraunus | `cataclysm:ceraunus` | Throws the anchor and returns it, or fans out waves point-blank | throw 100 t / wave 150 t |
| Astrape | `cataclysm:astrape` | Fires an EMP lightning spear (direct hit + lingering static field) | 60 t (3 s) |
| Void Forge | `cataclysm:void_forge` | Lays a void-rune lattice: 7 rays × 5 rows out to 8.75 blocks | 100 t (5 s) |
| Brontes | `cataclysm:brontes` | Throws the hammer (melee locked until it returns), or calls a lightning storm + 8 sparks on a point-blank target | 100 t (5 s) |
| The Incinerator | `cataclysm:the_incinerator` | Channels 3 s, then pillars of fire on the locked target | 400 t (20 s) |
| Infernal Forge | `cataclysm:infernal_forge` | Instant shockwave, 4-block radius (9×9×9), launches victims | 80 t (4 s) |

### From Legendary Monsters (5 weapons)

| Weapon | Item ID | What your companion does | Default cooldown |
|--------|---------|--------------------------|------------------|
| Buckler of Annihilation | `legendary_monsters:buckler_of_annihilation` | Point-blank bash plus arcing annihilation bombs | 200 t on hit / 60 t on miss |
| Monstrous Anchor | `legendary_monsters:monstrous_anchor` | Stun shockwave (~3 s stun, no damage) with a 10 s cooldown, plus an uncooled splash on every melee hit | 200 t (10 s) shockwave; the splash has no cooldown (10 t rate-limit) |
| Soul Great Sword | `legendary_monsters:soul_great_sword` | Passive parry (immunity + counterattack + counter AoE + heal) and a thrown Phantom Dagger | parry 200 t / dagger 120 t |
| Axe of Lightning | `legendary_monsters:axe_of_lightning` | Lightning bolt barrage at range, electric burst when swarmed up close | 150 t (7.5 s) |
| The Tesseract | `legendary_monsters:the_tesseract` | Charges 1.5 s, then opens a pentagram of annihilation portals ending in an explosion | 150 t (7.5 s) |

Full default priority order (editable at run time):

```
bulwark_of_the_flame > buckler_of_annihilation > meat_shredder > monstrous_anchor
> soul_great_sword > soul_render > ceraunus > axe_of_lightning > astrape
> tesseract > void_forge > brontes > the_incinerator > infernal_forge
```

---

## Requirements

| Mod | Required? | Notes |
|-----|-----------|-------|
| Minecraft | **Yes** — 1.21.1 | Tested on 1.21.1 only |
| [NeoForge](https://www.curseforge.com/minecraft/mc-mods/neoforge) | **Yes** — 21.1.244 or newer | This is a NeoForge mod; there is no Fabric or classic Forge build |
| Java | **Yes** — Java 21 | Ships with the launcher |
| [Modern Companions](https://www.curseforge.com/minecraft/mc-mods/modern-companions) | **Yes** | Provides the companions themselves. Without it there is no wielder, so this mod does nothing |
| [L_Ender's Cataclysm](https://www.curseforge.com/minecraft/mc-mods/lendercataclysm) | Optional | Unlocks the 9 Cataclysm weapon skills. Not installed → those skills are skipped silently |
| [Legendary Monsters](https://www.curseforge.com/minecraft/mc-mods/legendary-monsters) | Optional | Unlocks the 5 Legendary Monsters weapon skills and the shield-passive exemption. Not installed → those skills and that exemption do nothing |

Cataclysm and Legendary Monsters are **soft dependencies**: this jar does not compile against them and does
not declare them as required, so leaving one out never produces a "missing dependency" boot screen.

---

## Installation

1. Install Minecraft 1.21.1 with NeoForge 21.1.244 or newer.
2. Put **Modern Companions** in your `mods` folder.
3. Put this mod's jar — `modern_companions_expand-1.21.1-1.0.0.jar` — in the same `mods` folder.
4. Optionally add **L_Ender's Cataclysm** and/or **Legendary Monsters**.
5. Start the game, hire a companion, and **give it one of the supported weapons** the way you would give
   it any sword (right-click it with the item). The skill fires on its own once the companion has an
   attack target.

That is all: nothing to craft, no keybinds, no GUI. On a dedicated server, install the same jars on the
server and on every client that joins it.

---

## Configuration

Two files, both created on first use.

**`config/modern_companions_expand.toml`** — one section per skill, with a master switch, cooldown, ranges,
thresholds and damage values. Every comment is bilingual (English line first, Chinese line second).

| Section | What it controls |
|---------|------------------|
| `incinerator`, `charge` | The Incinerator skill + the shared charge state machine (charge ticks, break distance, interrupt retry delay, damage threshold) |
| `bulwark`, `infernal_forge`, `soul_render`, `astrape`, `ceraunus`, `void_forge`, `brontes`, `meat_shredder` | The Cataclysm skills |
| `soul_great_sword`, `tesseract`, `buckler`, `monstrous_anchor`, `axe_of_lightning` | The Legendary Monsters skills |
| `shield_passive` | Exempt companions from the Frostbitten / Shulker / Spiky shield blocking passives (default on) |
| `debug` | Troubleshooting logs. Both switches are **off** by default; turn them on only while investigating |

Per-skill options include whether allies may be hit or knocked back by that skill, and whether the skill may
be used with a shield in the off hand.

**`config/modern_companions_expand/skill_priority.json`** — the execution order and the global silence
window. Edit it in any text editor, then run `/mce skills reload` to apply it immediately. A corrupted file
falls back to the built-in default table and says so in the log instead of crashing.

Cooldowns and skill state are stored in the entity's persistent data, so they survive a server restart.

---

## Commands

All commands live under `/mce` and need permission level 2 (OP, or cheats enabled in a single-player world).
They exist for testing and diagnosis — nothing in normal gameplay requires them.

`<skill>` is one of: `incinerator`, `bulwark`, `infernal_forge`, `soul_render`, `astrape`, `ceraunus`,
`void_forge`, `brontes`, `meat_shredder`, `soul_great_sword`, `tesseract`, `buckler`, `monstrous_anchor`,
`axe_of_lightning`.

| Command | What it does |
|---------|--------------|
| `/mce <skill> status` | Self-check for that skill: is the source mod detected, is the item registered, are the sounds found, what are the current config values |
| `/mce <skill> equip <target>` | Put the required weapon into a mob's hand and clear its cooldown (for players, use vanilla `/give`) |
| `/mce <skill> clear <target>` | Take back the target's main-hand and off-hand items; an in-progress charge is ended cleanly |
| `/mce <skill> reset <target>` | Clear that skill's cooldown and every internal state key (charge, windows, locks, throttles) |
| `/mce <skill> force <target> [mode]` | Release the skill right now, ignoring cooldown, range and charge |
| `/mce skills list` | Print the priority table with each skill's on/off state, cooldown and charge |
| `/mce skills reload` | Reload `skill_priority.json` without restarting |
| `/mce skills show <entity>` | State-machine snapshot of one companion: state, charge progress, locked target, remaining cooldown |
| `/mce scan on\|off\|default\|status` | Live toggle for the skill-scan log (`debug.scan_debug_log`), no file edit or restart needed |
| `/mce trace on\|off\|default\|status` | Live toggle for the Shredder × Soul Render trace log (`debug.shredder_trace_log`) |

Force modes, for weapons with two skills:

| Skill | Modes |
|-------|-------|
| `soul_render` | `dash` \| `windmill` |
| `ceraunus` | `throw` \| `wave` |
| `brontes` | `throw` \| `burst` |
| `soul_great_sword` | `parry` \| `dagger` |
| `monstrous_anchor` | `shockwave` \| `aoe` |
| `axe_of_lightning` | `bolts` \| `shockwave` |

Omit the mode to let the skill choose the way it would in normal combat. `/mce tesseract force` (no target)
works too — the pentagram is laid out around the caster, so it fires in place from the command executor.

---

## Known Limitations

- **Modern Companions is mandatory.** Without it this mod has nothing to empower: it loads fine and does
  nothing at all.
- **Missing optional mods mean missing skills.** No Cataclysm → the 9 Cataclysm skills never fire; no
  Legendary Monsters → the 5 Legendary Monsters skills and the shield-passive exemption do nothing. No
  error is thrown; the skills are simply skipped.
- **A source-mod update can break individual skills.** All cross-mod access is by name (reflection and
  three mixins into Legendary Monsters' shield handlers). If Cataclysm or Legendary Monsters renames a
  class, field or sound in a future version, the affected skill may go quiet or lose its visual/sound —
  logged under `[MCE]` and designed to fail safely. Fixing it needs a new build of this addon.
- **Only 1.21.1 / NeoForge is tested.** Other Minecraft versions and other loaders (Fabric, classic Forge)
  are not supported and are not expected to work.
- **Companions run their own scheduling**, not the vanilla right-click entry point. That means a companion
  can act with a shield in its off hand and can use skills a player would have to hold a button for. Both
  are deliberate; the per-skill "off-hand shield allowed" options let you tighten it.
- **The Tesseract's pull effect is intentionally not reproduced** — only the portal visuals, periodic
  damage and the expiry explosion are.
- **Balance numbers follow the source mods**, not any specific playstyle. If a companion feels too strong
  in your pack, that skill's master switch and cooldown are one config line away.

---

## Credits

- **[Modern Companions](https://www.curseforge.com/minecraft/mc-mods/modern-companions)** by STRHercules —
  the companion framework this addon plugs into. None of this works without it.
- **[L_Ender's Cataclysm](https://www.curseforge.com/minecraft/mc-mods/lendercataclysm)** by L_Ender —
  owner of the 9 Cataclysm weapons, their skill entities, sounds and damage types.
- **[Legendary Monsters](https://www.curseforge.com/minecraft/mc-mods/legendary-monsters)** — owner of the
  5 Legendary Monsters weapons, their stun effect and their shield passives.
- **[NeoForge](https://www.curseforge.com/minecraft/mc-mods/neoforge)** and **Mojang** for the platform.

All credit for the weapons, models, textures, sounds and balance belongs to the authors above. This addon is
**not affiliated with, endorsed by, or a port of** those mods: it contains no assets, code or textures from
them — it only looks their registries and classes up at run time. Please support their pages and read their
own licenses for their work.

---

## License

[MIT](LICENSE) © 2026 wjzczzjl2020

The code of this addon is MIT-licensed: free to use, modify and redistribute with attribution. The weapons,
textures, models, sounds and entities it drives belong to their respective mods and are covered by their own
licenses.

Source: <https://github.com/wjzczzjl2020/modern-companions-expand> · Bugs, conflicts and ideas: open an
issue here or leave a comment on the [CurseForge page](https://www.curseforge.com/minecraft/mc-mods/modern-companions-expand).
