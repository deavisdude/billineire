# Creating Your First Village Structure

This guide walks you through creating a simple Roman house schematic for the Village Overhaul plugin.

## Prerequisites

- Minecraft server with WorldEdit installed
- Creative mode access or WorldEdit permissions
- Basic building skills

## Step 1: Plan Your Build

For a small Roman house, we'll create:
- **Footprint**: 9 blocks × 9 blocks
- **Height**: 7 blocks (including roof)
- **Style**: Stone brick walls, terracotta roof, mosaic floor

## Step 2: Build the Structure

### Foundation (Y=0)
1. Fly to an empty area at Y=64 (or any flat location)
2. Place a 9×9 floor of smooth stone or polished andesite
3. Add decorative pattern (optional): Use different blocks every 2-3 blocks for a mosaic effect

### Walls (Y=1 to Y=4)
1. Build 3-block tall walls using **Stone Bricks**
2. Leave a 2×2 doorway on the south side
3. Add 2×1 windows on the other walls (leave air gaps)
4. Place **Chiseled Stone Bricks** at corners for accent

### Interior (Y=1 to Y=4)
1. Keep the interior mostly **AIR** - this is important!
2. Optional: Add a few furnishings:
   - Crafting table near entrance
   - Bed against one wall
   - Chest in a corner

### Roof (Y=5 to Y=6)
1. Create a flat roof using **Terracotta** (any color)
2. Add a 1-block rim using **Stone Brick Stairs** facing outward
3. Optional: Add a small chimney (2 blocks of stone bricks + campfire)

## Step 3: Select with WorldEdit

1. Get the WorldEdit wand:
   ```
   //wand
   ```

2. Select the **entire structure** including some air:
   - **First corner** (southwest, bottom): Left-click at X, Y=0, Z
   - **Second corner** (northeast, top): Right-click at X+8, Y=6, Z+8
   
   Your selection should be 9×7×9 blocks

3. Verify selection:
   ```
   //size
   ```
   Should show approximately: `9x7x9 = 567 blocks`

## Step 4: Copy the Structure

1. Stand at the **southwest corner** on the ground (Y=0)
2. Copy relative to where you're standing:
   ```
   //copy
   ```

## Step 5: Save as Schematic

1. Save with a descriptive name:
   ```
   //schem save house_roman_small
   ```

2. You'll see: `Saved to file: house_roman_small.schem`

## Step 6: Move to Plugin Directory

1. Find the schematic:
   - Windows: `server/plugins/WorldEdit/schematics/house_roman_small.schem`
   - Linux: `server/plugins/WorldEdit/schematics/house_roman_small.schem`

2. Copy or move it to:
   ```
   server/plugins/VillageOverhaul/structures/house_roman_small.schem
   ```

## Step 7: Test in Server

1. Restart your server to load the new schematic

2. Check logs for:
   ```
   [VillageOverhaul] [STRUCT] Loaded WorldEdit schematic 'house_roman_small' (9x7x9)
   ```

3. The structure will now be used when villages generate!

## Common Issues

### Structure is floating/underground
- **Cause**: Origin point is wrong
- **Fix**: The plugin uses ground-level detection, but ensure your schematic starts at Y=0

### Structure has weird rotation
- **Cause**: Copy origin was incorrect
- **Fix**: Always copy from the **southwest corner** at ground level

### Structure is cut off
- **Cause**: Selection was too small
- **Fix**: Make sure your selection includes the entire build plus a bit of air around it

### Interior is filled with blocks
- **Cause**: Air blocks weren't preserved
- **Fix**: Use `//copy` instead of `//cut`, and ensure interior is AIR blocks

## Next Steps

Create more structures:
- `house_roman_medium.schem` - 13×8×13 townhouse
- `house_roman_villa.schem` - 17×9×17 luxury estate  
- `workshop_roman_forge.schem` - 11×8×11 blacksmith
- `market_roman_stall.schem` - 7×6×7 market vendor

## Building Tips

### Roman Architecture Style
- **Materials**: Stone bricks, quartz, terracotta, smooth stone
- **Features**: Columns (stone brick pillars), courtyards, mosaic floors
- **Roof**: Flat or low-pitched terracotta roofs
- **Details**: Chiseled stone for accents, polished andesite for floors

### Keep It Village-Sized
- Small houses: 7-11 blocks wide
- Medium houses: 11-15 blocks wide
- Large buildings: 15-21 blocks wide
- Height: 6-9 blocks total

### Performance Considerations
- Avoid complex redstone
- Minimize tile entities (chests, furnaces, etc.)
- Use simple block types when possible

## Reference Images

Check `docs/roman-structures-visual.md` for ASCII art examples of each structure type.
