This folder will contain data pack assets for cultures (structures, tags, loot tables, trades).

Bootstrap note:
- For early CI/testing, culture definition JSONs are loaded from `resources/cultures/*.json` via CultureService.
- In later phases, migrate culture content into proper datapack structure with `pack.mcmeta` and namespaced assets.
