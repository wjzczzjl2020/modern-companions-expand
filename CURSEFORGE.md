# Modern Companions Expand — CurseForge page body

> Paste everything below the line into the CurseForge description editor (Markdown tab).
> Replace the two `![...](...)` image placeholders with links to screenshots you upload to CF.

---

**Let your companions actually fight with the good weapons.**

**Modern Companions Expand** is a companion-combat addon for
[Modern Companions](https://www.curseforge.com/minecraft/mc-mods/modern-companions).
Hand one of **14 legendary weapons** from [L_Ender's Cataclysm](https://www.curseforge.com/minecraft/mc-mods/lendercataclysm)
or [Legendary Monsters](https://www.curseforge.com/minecraft/mc-mods/legendary-monsters) to a companion,
and it will use that weapon's skill on its own — charging the Incinerator, throwing Brontes and waiting
for the hammer to come back, dashing with Soul Render, laying the Void Forge rune lattice — while making
sure the blast never lands on you.

Nothing is added to the gameplay: **no new weapons, blocks, mobs or textures of our own.** The weapons stay
exactly as their own mods made them. This mod only teaches companions *when* and *how* to use the skills
those weapons already have, with numbers taken from the source mods wherever possible.

![Companion using a Cataclysm weapon](replace-with-your-screenshot-1.png)

---

## ✨ Features

### 14 skills, 14 ways to wreck a boss fight

**From L_Ender's Cataclysm (9 weapons):**

- **Bulwark of the Flame** — charges through a target and launches whoever is in the way *(10 s)*
- **The Incinerator** — channels for 3 s, then pillars of fire on the locked target *(20 s)*
- **Infernal Forge** — instant shockwave, 4-block radius, launches victims *(4 s)*
- **Soul Render** — dashes at a distant target, or spiral-summons phantom halberds when surrounded *(5 s)*
- **Astrape** — EMP lightning spear with a lingering static field *(3 s)*
- **Ceraunus** — throws the anchor and returns it, or fans out waves point-blank *(5 s / 7.5 s)*
- **Void Forge** — void-rune lattice, 7 rays × 5 rows out to 8.75 blocks *(5 s)*
- **Brontes** — thrown hammer with melee locked until it returns, or a point-blank lightning storm + 8 sparks *(5 s)*
- **Meat Shredder** — sustained shredding on close hostile targets *(3 s after each run)*

**From Legendary Monsters (5 weapons):**

- **Soul Great Sword** — passive parry (immunity + counterattack + counter AoE + heal) and a thrown Phantom Dagger *(10 s / 6 s)*
- **The Tesseract** — 1.5 s charge, then a pentagram of annihilation portals ending in an explosion *(7.5 s)*
- **Buckler of Annihilation** — point-blank bash plus arcing annihilation bombs *(10 s on hit / 3 s on miss)*
- **Monstrous Anchor** — stun shockwave (~3 s stun, no damage) and a splash on every melee hit *(10 s)*
- **Axe of Lightning** — lightning bolt barrage at range, electric burst when swarmed *(7.5 s)*

### Smart, not spammy

- **Situational mode choice.** Weapons with two skills pick between them automatically: far target → the
  dash/throw; surrounded → the AoE. No random waste of a cooldown.
- **Skill priority table.** A companion runs one skill per scan, in an order **you** control, so a
  multi-weapon companion doesn't dump everything at once. Normal melee attacks are never blocked.
- **Numbers from the source mods.** Damage and cooldowns are read from Cataclysm / Legendary Monsters
  through reflection where their code allows it, so a companion's pillar of fire hits about as hard as
  yours does.

### Built so it cannot ruin your world

- **Ally protection on by default.** Shockwaves, pillars, waves and charges filter out players, other
  companions and the owner's pets — no damage, no knockback. Switch it off per skill to get vanilla
  behaviour back.
- **No damage-rate-limit loopholes.** Sustained skills (Meat Shredder) keep a shadow ledger of the
  victim's invulnerability ticks, so a shredder can't be used to stack damage, and teammates' big hits
  are staggered instead of spiking.
- **Shield-passive exemption for companions.** When a companion blocks with a Frostbitten, Shulker or
  Spiky shield, only the *special* passive (freeze / levitation / thorns retaliation) is skipped — normal
  blocking (damage reduction, arrow blocks, durability, the blocking animation) is untouched. Players are
  unaffected.
- **Fails safe.** Every cross-mod call is wrapped: if a source mod is missing, its skills are quietly
  skipped; if an update renames a class or a sound, that visual goes missing and the log says which, but
  the game never crashes and the damage logic keeps working. Everything is written to the log under `[MCE]`.

### Tunable while you play

- **`config/modern_companions_expand.toml`** — a master switch, cooldown, ranges, thresholds and damage
  for every skill. Comments are bilingual: **English first line, Chinese second line**, so the file reads
  well for everyone.
- **`config/modern_companions_expand/skill_priority.json`** — the execution order and the global silence
  window. Edit it, then run `/mce skills reload`: applied instantly, no restart.
- A corrupted config falls back to safe defaults and says so in the log instead of boot-looping you.

---

## 🛠 How to use

1. Install the requirements below, drop the jar in `mods`, start the game.
2. Hire a companion from **Modern Companions**.
3. Give it a supported weapon the way you'd give it any sword — right-click it with the item.
4. Give the companion something to attack. It handles the rest on its own.

That's it. No items to craft, no commands you need, no keybinds, no GUI.

![Multiple companions with legendary weapons](replace-with-your-screenshot-2.png)

---

## 📋 Requirements

**Required**

- Minecraft **1.21.1**
- **NeoForge** 21.1.244 or newer (Java 21) — this is a NeoForge mod, there is no Fabric or classic Forge build
- **[Modern Companions](https://www.curseforge.com/minecraft/mc-mods/modern-companions)** — provides the
  companions. Without it this mod has nothing to empower and simply does nothing

**Optional (each one unlocks a part of the mod)**

- **[L_Ender's Cataclysm](https://www.curseforge.com/minecraft/mc-mods/lendercataclysm)** — the 9 Cataclysm weapon skills
- **[Legendary Monsters](https://www.curseforge.com/minecraft/mc-mods/legendary-monsters)** — the 5 Legendary Monsters weapon skills + the shield-passive exemption

Cataclysm and Legendary Monsters are **soft dependencies**: this jar does not compile against them and
does not list them as required, so you will never get a "missing dependency" screen for leaving one out.
Install on both the server and its clients.

---

## ⌨️ Commands (optional, for testing)

Everything runs automatically in normal play. These exist so you can check and debug things; they need
permission level 2 (OP or cheats enabled). `<skill>` = `incinerator`, `bulwark`, `infernal_forge`,
`soul_render`, `astrape`, `ceraunus`, `void_forge`, `brontes`, `meat_shredder`, `soul_great_sword`,
`tesseract`, `buckler`, `monstrous_anchor`, `axe_of_lightning`.

- `/mce <skill> status` — self-check: source mod detected? item registered? sounds found? current config values?
- `/mce <skill> equip <target>` — hand that mob the required weapon (for players use vanilla `/give`)
- `/mce <skill> clear <target>` — take back its main-hand and off-hand items, ending a charge cleanly
- `/mce <skill> reset <target>` — clear that skill's cooldown and all internal state
- `/mce <skill> force <target> [mode]` — cast right now, ignoring cooldown, range and charge
- `/mce skills list` / `/mce skills reload` — show or re-read the priority table
- `/mce skills show <entity>` — state-machine snapshot of one companion (state, charge progress, locked target, remaining cooldown)
- `/mce scan on|off|default|status` and `/mce trace on|off|default|status` — live log toggles for debugging, no restart

Force modes for two-skill weapons: `soul_render dash|windmill`, `ceraunus throw|wave`, `brontes throw|burst`,
`soul_great_sword parry|dagger`, `monstrous_anchor shockwave|aoe`, `axe_of_lightning bolts|shockwave`.
Omit the mode to let it decide as it would in combat.

---

## ⚠️ Known limitations

- Tested on **1.21.1 / NeoForge only**; other versions are not supported yet.
- Companions run their own scheduling rather than the vanilla right-click entry point, so a companion can
  act with a shield in its off hand and can use skills a player must hold a button for. Deliberate, and
  the per-skill "off-hand shield allowed" option can tighten it.
- Skill visuals (projectile trails, screen shake, sounds) are created through the source mods' own entity
  classes. An update that renames one of them degrades that one visual to nothing — logged, never fatal.
- The Tesseract's pull effect is intentionally not reproduced (portals, periodic damage and the expiry
  explosion are).
- Balance numbers were chosen to match the source mods, not a specific playstyle. If a companion feels
  too strong in your pack, the master switch and cooldown for that skill are one config line away.

---

## 🙏 Credits

- **[Modern Companions](https://www.curseforge.com/minecraft/mc-mods/modern-companions)** by STRHercules —
  the companion framework this addon plugs into. None of this works without it.
- **[L_Ender's Cataclysm](https://www.curseforge.com/minecraft/mc-mods/lendercataclysm)** by L_Ender —
  owner of the 9 Cataclysm weapons, their skill entities, sounds and damage types.
- **[Legendary Monsters](https://www.curseforge.com/minecraft/mc-mods/legendary-monsters)** — owner of the
  5 Legendary Monsters weapons, their stun effect and their shield passives.
- **[NeoForge](https://www.curseforge.com/minecraft/mc-mods/neoforge)** and **Mojang** for the platform.

All credit for the weapons, models, textures, sounds and balance belongs to the authors above. This addon
is **not affiliated with, endorsed by, or a port of** those mods: it contains no assets, code or textures
from them — it only looks their registries and classes up at run time, by name, and asks them nicely.
Please go support their pages, and read their own licenses for their work.

---

## 📄 License & source

Released under the **MIT License** — free to use, modify and redistribute with attribution.
Source: **https://github.com/wjzczzjl2020/modern-companions-expand** *(put your real repo URL here)*
Bugs, conflicts and ideas: please leave a comment on this page or open an issue on GitHub.
