# Roman Structure Reference

Generated Roman-style buildings for the Village Overhaul test village "Roma I".

## Available Structures

### 1. Roman Insula (Small House)
**ID**: `house_roman_small` / `house_small`  
**Dimensions**: 9×7×9 blocks  
**Style**: Small apartment building

**Features**:
- **Stone brick walls** with chiseled stone pillar accents every 3 blocks
- **Mosaic floor pattern** (smooth stone with polished andesite accents)
- **Flat terracotta roof** with decorative stone slab edge
- **Glass pane windows** on upper level
- **Main entrance** on south side (centered)
- **Wall-mounted torches** for interior lighting

**Historical Note**: Insulae were multi-story apartment buildings that housed Rome's urban population.

---

### 2. Roman Domus (Medium House)
**ID**: `house_roman_medium` / `house_medium`  
**Dimensions**: 13×8×13 blocks  
**Style**: Wealthy townhouse

**Features**:
- **Stone brick construction** with decorative columns
- **Interior courtyard** (open to sky with water feature)
- **Mosaic floor pattern** throughout
- **Flat terracotta roof** with stone slab edges
- **Multiple windows** (randomized glass panes)
- **Courtyard fountain** in center

**Historical Note**: The domus was a private urban residence for wealthy Roman families, typically built around a central atrium.

---

### 3. Roman Villa (Large House)
**ID**: `house_roman_villa`  
**Dimensions**: 17×9×17 blocks  
**Style**: Luxury estate

**Features**:
- **Quartz block walls** (representing marble/limestone)
- **Polished andesite floors** with decorative pattern
- **Large interior courtyard** with water feature
- **Chiseled quartz decorative elements**
- **Multiple interior chambers**
- **High ceilings** and abundant natural light

**Historical Note**: Roman villas were grand country estates with extensive gardens, courtyards, and elaborate decoration.

---

### 4. Roman Forge (Workshop)
**ID**: `workshop_roman_forge` / `workshop`  
**Dimensions**: 11×8×11 blocks  
**Style**: Blacksmith/metalworking facility

**Features**:
- **Stone brick construction** (fire-resistant)
- **Cobblestone floor** (heat tolerant)
- **Central furnace** with working anvil and crafting table
- **Stone brick chimney** extending above roofline
- **Large open entrance** for ventilation
- **Lantern lighting**
- **Partial roof** with chimney opening

**Work Areas**:
- Smelting station (furnace)
- Forging station (anvil)
- Crafting station (crafting table)

---

### 5. Roman Market Stall
**ID**: `market_roman_stall`  
**Dimensions**: 7×6×7 blocks  
**Style**: Open-air vendor stall

**Features**:
- **Smooth stone platform**
- **Oak fence corner posts**
- **Striped canvas awning** (white/red wool)
- **Storage containers** (barrels, chests, composters)
- **No walls** (open air design)

**Historical Note**: Roman markets (fora) featured open-air stalls where merchants sold goods directly to customers.

---

### 6. Roman Bathhouse
**ID**: `building_roman_bathhouse`  
**Dimensions**: 15×7×15 blocks  
**Style**: Public bath facility

**Features**:
- **Quartz construction** (smooth quartz/quartz blocks)
- **Decorative mosaic floor**
- **Central bathing pool** (recessed water feature)
- **Domed roof** (stepped quartz dome)
- **Arched windows** (glass panes at regular intervals)
- **Four corner campfires** (heating braziers)
- **Grand entrance** (3-block wide doorway)

**Historical Note**: Roman bathhouses (thermae) were social centers featuring hot and cold baths, exercise areas, and meeting spaces.

---

## Architecture Style Guide

### Color Palette
- **Primary**: Stone bricks, smooth stone, quartz
- **Accents**: Chiseled stone/quartz, polished andesite
- **Roofing**: Terracotta (clay tiles)
- **Decoration**: Glass panes, lanterns, torches

### Structural Elements

**Columns**: 
- Chiseled stone/quartz pillars every 3 blocks
- Full-height support on corners and entrances

**Roofs**:
- Flat terracotta roofs with decorative edges
- Domed roofs for public buildings
- No peaked/angled roofs (historically accurate)

**Floors**:
- Mosaic patterns using alternating materials
- Smooth stone, polished andesite, quartz
- Geometric patterns (checkerboard, diagonal)

**Openings**:
- Centered main entrances (2-3 blocks wide)
- Upper-level windows with glass panes
- Arched doorways for public buildings

**Courtyards**:
- Interior open spaces in larger buildings
- Central water features (impluvium)
- Open to sky (no roof)

### Material Translations

**Historical → Minecraft**:
- Marble → Quartz blocks
- Limestone → Smooth stone / stone bricks
- Clay tiles → Terracotta
- Plaster → White/light gray concrete
- Mosaic → Alternating floor patterns
- Bronze → Copper blocks (future enhancement)

---

## Placement Behavior

### Ground Detection
All structures automatically detect and place at ground level:
- Searches downward up to 10 blocks
- Ignores vegetation and leaves
- Places foundation flush with solid ground

### Rotation
Deterministic rotation based on village seed:
- 0°, 90°, 180°, or 270° rotation
- Maintains entrance orientation when possible
- Consistent within same village seed

### Terrain Adaptation
Structures adapt to minor terrain variations:
- Light grading (max 3 blocks)
- Gap filling for small holes
- Vegetation trimming in footprint

---

## Testing Commands

### Generate Specific Structure
```
/votest generate-structures <village-id>
```

### Check Loaded Structures
Look for log message:
```
[STRUCT] Loaded 10 Roman structure templates
```

### Verify Placement
Check logs for:
```
[STRUCT] Begin placement: structureId=house_roman_small
[STRUCT] Ground level found at Y=XX
[STRUCT] Paper API placement complete for 'house_roman_small'
```

---

## Future Enhancements

### Planned Additions
- [ ] Interior furnishings (beds, storage, decorations)
- [ ] Villager job site blocks (profession assignment)
- [ ] Loot tables for chests/barrels
- [ ] Multi-block structures (connected buildings)
- [ ] Elevation variants (hillside, terraced)
- [ ] Weathering/age variations
- [ ] Regional variants (Greek, Byzantine)

### WorldEdit Schematic Support
These procedural structures serve as placeholders until proper schematics are created:

1. Build refined versions in creative mode
2. Save with WorldEdit: `//schem save house_roman_small`
3. Place in `plugins/VillageOverhaul/structures/`
4. Reload plugin to use actual schematics

The procedural generators will continue as fallback if schematics are unavailable.

---

## Architecture Notes

### Historical Accuracy
These structures represent simplified but architecturally informed interpretations of:
- Imperial Roman architecture (1st-3rd century CE)
- Mediterranean building traditions
- Pompeii/Herculaneum archaeological evidence

### Compromises for Gameplay
- **Simplified interiors**: Full Roman houses had dozens of rooms
- **Reduced scale**: Real insulae were 4-7 stories tall
- **Materials**: Minecraft blocks approximate historical materials
- **Functionality**: Optimized for villager pathfinding and player interaction

### Cultural Context
Future culture packs will include:
- **Norse**: Longhouses, stave churches, mead halls
- **Byzantine**: Domed basilicas, fortified compounds
- **Medieval**: Timber-frame, wattle-and-daub construction
