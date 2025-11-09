# WorldEdit Schematic Support

The Village Overhaul plugin now supports loading WorldEdit/FAWE schematics for village structures!

## Creating Schematics

### In-Game with WorldEdit

1. **Build your structure** in creative mode
2. **Select the region** with WorldEdit wand (`//wand`)
   - Left-click to set position 1
   - Right-click to set position 2
3. **Copy the selection**: `//copy`
4. **Save as schematic**: `//schem save <name>`
   - Example: `//schem save house_roman_small`

### File Location

Schematics are saved to: `plugins/WorldEdit/schematics/`

WorldEdit supports two formats:
- **`.schem`** - Sponge Schematic v2 format (recommended, supports 1.13+)
- **`.schematic`** - Legacy MCEdit format (older versions)

## Loading Schematics into Plugin

### Manual Loading

Place `.schem` files in: `plugins/VillageOverhaul/structures/`

The plugin will auto-load on startup or use `/votest reload-structures`

### Naming Convention

Structure files should follow this pattern:
- `house_<culture>_<size>.schem` - Example: `house_roman_small.schem`
- `workshop_<culture>_<type>.schem` - Example: `workshop_roman_blacksmith.schem`
- `market_<culture>.schem`
- `temple_<culture>.schem`

### File Structure

```
plugins/VillageOverhaul/
  structures/
    roman/
      house_small.schem
      house_medium.schem
      house_large.schem
      workshop_blacksmith.schem
      workshop_carpentry.schem
      market.schem
    norse/
      house_longhouse.schem
      workshop_shipyard.schem
```

## Structure Requirements

### Best Practices

1. **Ground Level**: Build structures with their floor at Y=0 in the schematic
   - The plugin will automatically adjust placement to actual ground level
   - No need to include foundation pillars

2. **Size Limits**: Reasonable structure dimensions
   - Small houses: 7×6×7 to 11×8×11
   - Medium houses: 11×8×11 to 15×10×15
   - Large buildings: 15×12×15 to 25×15×25

3. **Entrances**: Include at least one doorway
   - Preferably on the southern side (Z+ direction)
   - Clear path from entrance to interior

4. **Materials**: Use culture-appropriate blocks
   - **Roman**: Stone bricks, terracotta, sandstone
   - **Norse**: Spruce planks, cobblestone, dark oak
   - **Byzantine**: Polished andesite, purple terracotta, gold details

5. **Interior**: Furnish with functional blocks
   - Beds, crafting tables, furnaces
   - Chests with loot tables
   - Job site blocks for villager professions

## Deterministic Placement

The plugin supports deterministic structure placement:

- **Rotation**: Structures are rotated based on village seed (0°, 90°, 180°, 270°)
- **Re-seating**: If initial placement fails validation, structure searches for nearby valid location
- **Terraforming**: Minor terrain adjustment (max 3 blocks) to fit structure naturally

## Fallback Behavior

If no schematics are loaded or WorldEdit is not available:

1. Plugin uses **placeholder structures** (simple houses built with Paper API)
2. Logs will show: `[STRUCT] Using Paper API placement for 'house_small'`
3. Once schematics are added, they will replace placeholders on next reload

## Testing Structures

### In-Game Testing

```
/votest generate-structures <village-id>
```

This will:
- Generate structures for the specified village
- Apply site validation and terraforming
- Log detailed placement information

### CI Testing

Run automated structure tests:
```powershell
.\scripts\ci\sim\test-custom-villager-interaction.ps1
```

Validates:
- Structure placement succeeds
- No floating/embedded buildings
- Proper re-seating on difficult terrain
- Deterministic seed-based placement

## Troubleshooting

### "Structure file not found"
- Check file is in `plugins/VillageOverhaul/structures/`
- Verify `.schem` extension (not `.schematic`)

### "Unknown schematic format"
- Re-save with WorldEdit 7.2+: `//schem save <name>`
- Ensure using Sponge Schematic v2 format

### "WorldEdit placement failed"
- Check server has WorldEdit or FAWE installed
- Verify WorldEdit version compatibility (7.2+)
- Falls back to Paper API automatically

### "Giant dirt pillars"
- **FIXED** in latest version
- Structures now properly detect ground level
- No more massive foundations

## Future Enhancements

Planned features:
- [ ] Dynamic structure loading from culture JSON
- [ ] Structure variants based on village tier
- [ ] Schematic randomization (furniture, decorations)
- [ ] Multi-block structures (compound buildings)
- [ ] Underground/elevated structure support
