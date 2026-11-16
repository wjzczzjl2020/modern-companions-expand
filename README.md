# Modern Companions Expand

**Minecraft 1.21.1 · NeoForge · MIT License**

A companion-combat addon for [Modern Companions](https://www.curseforge.com/minecraft/mc-mods/modern-companions).
Give your companions one of **14 legendary weapons** from
[L_Ender's Cataclysm](https://www.curseforge.com/minecraft/mc-mods/lendercataclysm) or
[Legendary Monsters](https://www.curseforge.com/minecraft/mc-mods/legendary-monsters), and they will
wield it properly — charging up, picking the right skill for the situation, throwing the hammer and
waiting for it to come back, and *not* accidentally nuking you while doing it.

Nothing is added to the gameplay: **no weapons, blocks, mobs or textures of our own.** The 14 weapons stay
exactly as their source mods made them. This mod only teaches companion entities **when and how** to use
the skills those weapons already have. (One leftover placeholder item from the development scaffold is
still registered and does nothing — see [Notes & known limitations](#notes--known-limitations).)

---

## Table of contents

- [What it does](#what-it-does)
- [Supported weapons](#supported-weapons)
- [Requirements](#requirements)
- [Installation](#installation)
- [Configuration](#configuration)
- [Commands](#commands)
- [Notes & known limitations](#notes--known-limitations)
- [Credits](#credits)
- [License](#license)

---

## What it does

- **Autonomous skill usage.** A companion holding a supported weapon scans for a target, respects its
  own cooldown and the global silence window, and releases the weapon's skill by itself — the same
  pillars of fire, shockwaves, throws and rune lattices the player gets.
- **Situational mode selection.** Weapons with two skills choose between them automatically:
  Soul Render dashes at a distant target and spiral-summons when surrounded; Ceraunus throws at range
  and uses the fan wave point-blank; Axe of Lightning alternates bolt barrage and electric burst.
- **Numbers taken from the source mods.** Damage, cooldowns and entity types are read from the source
  mods' own config/registry through reflection wherever possible, so a skill hits about as hard as it
  does for a player holding the same weapon.
- **Ally protection.** Shockwaves, pillars, waves and dashes filter out players, other companions and
  the owner's pets: they take no damage and are not flung. Every protection can be switched off in the
  config to get the vanilla behaviour back.
- **Invulnerability-frame safety.** Sustained multi-hit skills (Meat Shredder) keep a "shadow ledger" of
  the victim's vanilla invulnerability ticks, so a shredder can't be used as a loophole to bypass the
  damage rate limit — and teammates' big hits are staggered instead of stacking into a spike.
- **Shield-passive exemption for companions.** When a companion blocks with a
  Frostbitten / Shulker / Spiky shield from Legendary Monsters, the *special* blocking passive
  (freeze / levitation / thorns retaliation) is skipped while normal blocking (damage reduction, arrow
  blocks, durability loss, blocking animation) is untouched. Players are unaffected.
- **Fully configurable, hot-reloadable.** One TOML file per skill: master switch, cooldown, ranges,
  thresholds, damage. Plus a skill priority table (JSON) you can edit and apply in game with
  `/mce skills reload` — no restart.
- **Nothing breaks without the optional mods.** All cross-mod access is reflection-based with a hard
  try/catch around every call site: if Cataclysm or Legendary Monsters is missing, the related skills
  are simply skipped. If a class or field was renamed by an update, the skill goes quiet and the game
  keeps running. Every fallback is written to the log with an `[MCE]` prefix.

---

## Supported weapons

14 skills, executed in the priority order shown (editable at runtime). 20 ticks = 1 second.

### From L_Ender's Cataclysm (9)

| # | Weapon | Item ID | What your companion does | Default cooldown |
|---|--------|---------|--------------------------|------------------|
| 1 | Bulwark of the Flame | `cataclysm:bulwark_of_the_flame` | Charges through a target and launches whoever is in the way | 200 t (10 s) |
| 3 | Meat Shredder | `cataclysm:meat_shredder` | Sustained shredding on a close target (hostile targets only), with stagger + shadow invulnerability handling | 60 t (3 s) after each run |
| 5 | Soul Render | `cataclysm:soul_render` | Dashes at a distant target, or spiral-summons phantom halberds when surrounded | 100 t (5 s) |
| 7 | Ceraunus | `cataclysm:ceraunus` | Throws the anchor and returns it, or fans out waves point-blank | throw 100 t / wave 150 t |
| 9 | Astrape | `cataclysm:astrape` | Fires an EMP lightning spear (direct hit + lingering static field) | 60 t (3 s) |
| 11 | Void Forge | `cataclysm:void_forge` | Lays a void-rune lattice: 7 rays × 5 rows out to 8.75 blocks | 100 t (5 s) |
| 12 | Brontes | `cataclysm:brontes` | Throws the hammer (melee locked until it returns), or calls a lightning storm + 8 sparks on a point-blank target | 100 t (5 s) |
| 13 | The Incinerator | `cataclysm:the_incinerator` | Channels 3 s, then pillars of fire on the locked target | 400 t (20 s) |
| 14 | Infernal Forge | `cataclysm:infernal_forge` | Instant shockwave, 4-block radius (9×9×9), launches victims | 80 t (4 s) |

### From Legendary Monsters (5)

| # | Weapon | Item ID | What your companion does | Default cooldown |
|---|--------|---------|--------------------------|------------------|
| 2 | Buckler of Annihilation | `legendary_monsters:buckler_of_annihilation` | Point-blank bash plus arcing annihilation bombs | 200 t on hit / 60 t on miss |
| 4 | Monstrous Anchor | `legendary_monsters:monstrous_anchor` | Stun shockwave (3-block box, ~3 s stun, no damage) and a splash on every melee hit | 200 t (10 s) |
| 6 | Soul Great Sword | `legendary_monsters:soul_great_sword` | Passive parry (immunity + counterattack + counter AoE + heal) and a thrown Phantom Dagger | parry 200 t / dagger 120 t |
| 8 | Axe of Lightning | `legendary_monsters:axe_of_lightning` | Lightning bolt barrage at range, electric burst when surrounded up close | 150 t (7.5 s) |
| 10 | The Tesseract | `legendary_monsters:the_tesseract` | Charges 1.5 s, then opens a pentagram of annihilation portals ending in an explosion | 150 t (7.5 s) |

Full default priority order:

```
bulwark_of_the_flame > buckler > meat_shredder > monstrous_anchor > soul_great_sword
> soul_render > ceraunus > axe_of_lightning > astrape > tesseract
> void_forge > brontes > the_incinerator > infernal_forge
```

The point of the table: only one skill starts per scan, so a companion that can hold two weapons will
not spend every cooldown at once. Melee attacks are never blocked by any of this.

---

## Requirements

| Mod | Required? | Notes |
|-----|-----------|-------|
| Minecraft | **Yes** — 1.21.1 | Tested on 1.21.1 only |
| [NeoForge](https://www.curseforge.com/minecraft/mc-mods/neoforge) | **Yes** — 21.1.244 or newer | This is a NeoForge mod; there is no Fabric/Forge build |
| Java | **Yes** — Java 21 | Ships with the launcher |
| [Modern Companions](https://www.curseforge.com/minecraft/mc-mods/modern-companions) | **Yes** | Provides the companions themselves. Without it there is no wielder, so the mod does nothing |
| [L_Ender's Cataclysm](https://www.curseforge.com/minecraft/mc-mods/lendercataclysm) | Optional | Unlocks the 9 Cataclysm weapons. Not installed → those skills are skipped silently |
| [Legendary Monsters](https://www.curseforge.com/minecraft/mc-mods/legendary-monsters) | Optional | Unlocks the 5 Legendary Monsters weapons and the shield-passive exemption. Not installed → those skills and that mixin do nothing |

Cataclysm and Legendary Monsters are **soft dependencies**: this jar does not compile against them and
does not declare them in `neoforge.mods.toml`, so you never get a "missing dependency" boot error.

---

## Installation

1. Install Minecraft 1.21.1 with NeoForge 21.1.244 or newer.
2. Put `Modern Companions` in your `mods` folder.
3. Put this mod's jar — `modern_companions_expand-1.21.1-1.0.0.jar` — in the same `mods` folder.
4. Optionally add Cataclysm and/or Legendary Monsters.
5. Start the game, hire a companion, and **throw one of the supported weapons into its hands**
   (just right-click it with the item, like any other weapon). The skill activates on its own once the
   companion has an attack target.

On a dedicated server, install the same jars on both the server and each client that joins it.
Only the 1.21.1 NeoForge build exists; other versions have not been tested.

---

## Configuration

Two files, both created on first use:

**`config/modern_companions_expand.toml`** — every skill section with a master switch, cooldown,
ranges, thresholds and damage values. Every comment is bilingual (English line first, Chinese line
second).

Main sections:

| Section | What it controls |
|---------|------------------|
| `incinerator`, `charge` | Incinerator skill + the shared charge state machine (charge ticks, break distance, interrupt retry delay, damage threshold) |
| `bulwark`, `infernal_forge`, `soul_render`, `astrape`, `ceraunus`, `void_forge`, `brontes`, `meat_shredder` | The Cataclysm skills |
| `soul_great_sword`, `tesseract`, `buckler`, `monstrous_anchor`, `axe_of_lightning` | The Legendary Monsters skills |
| `shield_passive` | Exempt companions from the Frostbitten / Shulker / Spiky shield blocking passives (default on) |
| `debug` | Troubleshooting logs. Both are **off** by default since the release build; turn them on only while investigating |

**`config/modern_companions_expand/skill_priority.json`** — the execution order and the global silence
window. Edit it in a text editor, then run `/mce skills reload` to apply it immediately. A corrupted
file falls back to the built-in default table and says so in the log instead of crashing.

Cooldowns and skill state are stored in the entity's persistent data, so they survive a server restart.

---

## Commands

All commands are under `/mce` and need permission level 2 (OP, or cheats enabled in a single-player
world). They exist for testing and diagnosis; nothing in normal gameplay requires them.

`<skill>` is one of: `incinerator`, `bulwark`, `infernal_forge`, `soul_render`, `astrape`, `ceraunus`,
`void_forge`, `brontes`, `meat_shredder`, `soul_great_sword`, `tesseract`, `buckler`,
`monstrous_anchor`, `axe_of_lightning`.

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

Omit the mode to let the skill choose the way it would in normal combat.
`/mce tesseract force` (no target) works too — the pentagram is laid out around the caster, so it fires
in place from the command executor.

---

## Notes & known limitations

- Companions use their own scheduling, not the vanilla right-click entry point. That means a companion
  can act with a shield in its off hand and can use skills the player would have to hold a button for;
  both are deliberate, and the per-skill "off-hand shield allowed" options let you tighten it.
- Skill visuals (projectile trails, screen shake, sounds) are created through the source mods' own
  entity classes. If an update renames one, that part degrades to "no visual / no sound" and the log
  says which — the damage logic keeps working.
- The Tesseract pull effect is intentionally not reproduced (only portal visuals, periodic damage and
  the expiry explosion); the numbers were copied from vanilla, see the config comment.
- Logs are prefixed `[MCE]`. Everything runs inside a guard, so a failure writes a line instead of
  crashing the game.
- The Java package is `cn.blockforge.generated.moderncompanionsexpand`. That is a historical build-scaffold
  name and it is intentionally kept: the mod ID `modern_companions_expand` — the one your saves depend on —
  is independent of the package name, so renaming the package would be pure risk for no gain.

---

## Credits

- **[Modern Companions](https://www.curseforge.com/minecraft/mc-mods/modern-companions)** by STRHercules —
  the companion framework this addon plugs into, and the reason any of this is possible.
- **[L_Ender's Cataclysm](https://www.curseforge.com/minecraft/mc-mods/lendercataclysm)** by L_Ender —
  owner of the 9 weapons from Cataclysm, their skill entities, sounds and damage types.
- **[Legendary Monsters](https://www.curseforge.com/minecraft/mc-mods/legendary-monsters)** — owner of the
  5 Legendary Monsters weapons, their stun effect and shield passives.
- **[NeoForge](https://www.curseforge.com/minecraft/mc-mods/neoforge)** and **Mojang** for the platform.

This project is **not** affiliated with, endorsed by, or a port of any of those mods. It contains no
assets, code or textures from them: it reads their registries and classes at run time, by name, and it
ships no art of its own. Please support the authors above and follow their licenses for their own work.

---

## License

[MIT](LICENSE) © 2026 wjzczzjl2020

The code of this addon is MIT-licensed. The weapons, textures, models, sounds and entities it drives
belong to their respective mods and are covered by their own licenses.
