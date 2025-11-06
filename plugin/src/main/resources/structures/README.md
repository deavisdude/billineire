# Village Structures Directory

This directory is for WorldEdit schematic files (`.schem` format) used by the Village Overhaul plugin.

## Quick Start

1. **Place `.schem` files here**: Drop your WorldEdit schematics into this directory
2. **Naming convention**: Use descriptive names like `house_roman_small.schem`, `workshop_forge.schem`
3. **Restart server**: Structures are loaded on plugin startup

## Supported Formats

- **WorldEdit Schematics v2** (`.schem`) - Recommended, Sponge format
- **Legacy WorldEdit** (`.schematic`) - Older format, still supported

## Structure Naming

The plugin uses structure IDs based on the filename (without extension):

| Filename | Structure ID | Usage |
|----------|-------------|-------|
| `house_roman_small.schem` | `house_roman_small` | Small Roman house |
| `house_roman_medium.schem` | `house_roman_medium` | Medium Roman townhouse |
| `house_roman_villa.schem` | `house_roman_villa` | Large Roman villa |
| `workshop_roman_forge.schem` | `workshop_roman_forge` | Blacksmith workshop |
| `market_roman_stall.schem` | `market_roman_stall` | Market stall |
| `building_roman_bathhouse.schem` | `building_roman_bathhouse` | Public bathhouse |

## Fallback Aliases

Some generic aliases are supported for backward compatibility:
- `house_small` → uses `house_roman_small`
- `house_medium` → uses `house_roman_medium`
- `workshop` → uses `workshop_roman_forge`

## Creating Schematics with WorldEdit

### In-Game Commands

1. Select the area with WorldEdit:
   ```
   //wand
   //pos1   (left-click a corner)
   //pos2   (right-click opposite corner)
   ```

2. Copy the selection:
   ```
   //copy
   ```

3. Save as schematic:
   ```
   //schem save <name>
   ```

4. Find the file in `plugins/WorldEdit/schematics/` and move it to this directory

### Tips for Building Structures

- **Origin Point**: The structure will be placed with its southwest corner at the target location
- **Ground Level**: The plugin automatically finds ground level, so build structures at Y=0 in your schematic
- **Dimensions**: Structures can be any size, but reasonable sizes for villages:
  - Small houses: 7x7 to 11x11 footprint
  - Medium houses: 11x11 to 15x15 footprint
  - Large buildings: 15x15 to 21x21 footprint
- **Air Blocks**: Interior air blocks are preserved for proper room generation
- **Materials**: Use period-appropriate materials for your culture theme

## Procedural Fallback

If no schematic files are found, the plugin will generate procedural Roman-style buildings using the Paper API. This ensures villages always generate even without custom schematics.

## Debugging

Check server logs for structure loading messages:
```
[VillageOverhaul] [STRUCT] Loaded 6 structure(s) from plugins/VillageOverhaul/structures
[VillageOverhaul] [STRUCT] Loaded WorldEdit schematic 'house_roman_small' (9x7x9) from house_roman_small.schem
```

If structures fail to load, check:
1. File format is `.schem` (Sponge v2) or `.schematic`
2. WorldEdit plugin is installed and loaded
3. File permissions allow reading

## Directory Structure Example

```
plugins/VillageOverhaul/structures/
├── README.md (this file)
├── house_roman_small.schem
├── house_roman_medium.schem
├── house_roman_villa.schem
├── workshop_roman_forge.schem
├── market_roman_stall.schem
└── building_roman_bathhouse.schem
```

## Culture-Specific Structures

Future versions will support culture-specific structure sets loaded from subdirectories:

```
structures/
├── roman/
│   ├── house_small.schem
│   ├── forum.schem
│   └── bathhouse.schem
├── medieval/
│   ├── house_small.schem
│   ├── castle.schem
│   └── church.schem
└── ...
```

For now, all structures are loaded from the root structures directory.
