# Testing Roman Structures - Quick Start

## Pre-Test Setup

### 1. Delete Old World
```powershell
Remove-Item "c:\Users\davis\Documents\Workspace\billineire-test-server\world" -Recurse -Force
Remove-Item "c:\Users\davis\Documents\Workspace\billineire-test-server\world_nether" -Recurse -Force
Remove-Item "c:\Users\davis\Documents\Workspace\billineire-test-server\world_the_end" -Recurse -Force
```

### 2. Plugin Already Updated
✅ `VillageOverhaul.jar` copied to test server with Roman structures

### 3. Start Server
Navigate to test server directory and start:
```powershell
cd c:\Users\davis\Documents\Workspace\billineire-test-server
java -Xms2G -Xmx4G -jar server.jar nogui
```

## What to Expect

### On Server Start
**Console logs should show:**
```
[VillageOverhaul] Enabling VillageOverhaulPlugin
[STRUCT] FAWE not available, using Paper API fallback
[STRUCT] Loaded 10 Roman structure templates
[VO] Registered village 'Roma I' (ROMAN, seed=12345)
```

### On World Generation
**When you enter the world:**
```
[STRUCT] Searching for suitable village terrain starting from (16, 74, 0)
[STRUCT] Found suitable terrain at distance 32, coords: (48, 68, 16)
[STRUCT] Begin village placement for 'Roma I'
[STRUCT] Begin placement: structureId=house_roman_small, origin=(48,68,16)
[STRUCT] Ground level found at Y=67 (origin was Y=68)
[STRUCT] Paper API placement complete for 'house_roman_small'
[STRUCT] Seat successful: structure='house_roman_small'
```

### What You'll See In-Game

**Roman Village Buildings:**
1. **Small Insulae** (9×7×9) - Stone brick apartments
2. **Medium Domus** (13×8×13) - Townhouses with courtyards
3. **Large Villa** (17×9×17) - Luxury estate (if generated)
4. **Forge Workshop** (11×8×11) - Stone building with chimney
5. **Market Stalls** (7×6×7) - Open-air with striped awning
6. **Bathhouse** (15×7×15) - Domed public bath

**Architecture Features:**
- ✅ **Ground level placement** - No more giant pillars!
- ✅ **Flat terracotta roofs** with stone slab edges
- ✅ **Stone brick/quartz walls** with decorative columns
- ✅ **Glass pane windows** on upper levels
- ✅ **Mosaic floor patterns** (alternating stone types)
- ✅ **Interior courtyards** with water features (larger buildings)
- ✅ **Functional workstations** (forges, anvils, crafting tables)
- ✅ **Interior lighting** (wall torches, lanterns, campfires)

## Testing Steps

### 1. Visual Inspection
**Fly around the village and check:**
- [ ] Multiple building types visible
- [ ] Buildings at ground level (not floating or buried)
- [ ] Flat roofs with terracotta tiles
- [ ] Columns visible on building corners/sides
- [ ] Windows on upper floors
- [ ] Different building sizes (small, medium, large)

### 2. Enter Buildings
**Walk through structures:**
- [ ] Doorways clear and accessible
- [ ] Interior properly lit (torches/lanterns)
- [ ] Floor patterns visible
- [ ] Courtyards open to sky (medium/large houses)
- [ ] Water features in courtyards/bathhouse

### 3. Check Workshop
**Visit the forge:**
- [ ] Furnace present
- [ ] Anvil and crafting table accessible
- [ ] Chimney extends above roof
- [ ] Open entrance for ventilation

### 4. Check Market
**Find market stall:**
- [ ] Striped awning (red/white wool)
- [ ] Corner posts (oak fence)
- [ ] Storage containers (barrels/chests)
- [ ] Open sides (no walls)

### 5. Check Bathhouse
**Locate public bath:**
- [ ] Smooth quartz construction
- [ ] Central pool with water
- [ ] Domed roof structure
- [ ] Four corner campfires
- [ ] Arched windows

## Known Good Indicators

### Success Messages in Logs
```
✅ [STRUCT] Loaded 10 Roman structure templates
✅ [STRUCT] Found suitable terrain at distance X
✅ [STRUCT] Ground level found at Y=XX
✅ [STRUCT] Seat successful: structure='house_roman_small'
✅ [STRUCT] Paper API placement complete
```

### Failure Messages to Watch For
```
❌ [STRUCT] Site validation failed
❌ [STRUCT] Abort: structure='X', reason=no_valid_site
❌ [STRUCT] Re-seat required
❌ [STRUCT] Terraforming exceeded limit
```

## Troubleshooting

### "Only seeing stone tower"
**Solution:** 
- Ensure world was deleted before restart
- Check logs for structure placement messages
- Verify village seed registered: `[VO] Registered village 'Roma I'`

### "Giant dirt pillars still appearing"
**Solution:**
- Verify latest plugin JAR copied to server
- Check build timestamp: `village-overhaul-0.1.0-SNAPSHOT.jar`
- Look for ground-level detection logs: `Ground level found at Y=XX`

### "Structures floating in air"
**Solution:**
- Check terrain search logs: `Found suitable terrain at distance X`
- May need to relax terrain criteria if search fails
- Fallback to spawn offset should still work

### "Buildings buried in hillside"
**Solution:**
- Site validation should prevent this
- Check logs for validation failure messages
- May indicate terraforming not working

## Screenshot Opportunities

**Capture these views:**
1. **Aerial view** - Full village layout
2. **Street level** - Building facades with columns
3. **Interior courtyard** - Water feature and mosaic floors
4. **Workshop interior** - Forge, anvil, crafting setup
5. **Market stall** - Striped awning and vendor goods
6. **Bathhouse** - Domed roof and central pool
7. **Night view** - Interior lighting through windows

## Performance Notes

**Expected generation time:**
- Initial world gen: 5-10 seconds
- Village placement: 2-3 seconds
- Per structure: 0.1-0.5 seconds

**Block changes per structure:**
- Small house: ~500 blocks
- Medium house: ~1,200 blocks
- Large villa: ~2,500 blocks
- Workshop: ~800 blocks
- Total village: ~5,000-10,000 blocks

## Next Steps After Testing

### If Structures Look Good
1. ✅ Mark Phase 3 (US1) complete
2. 🎯 Move to Phase 4 (US2): Path Network & Main Building
3. 📸 Document with screenshots
4. 💾 Commit changes to git

### If Issues Found
1. 🐛 Document specific problems
2. 📋 Check relevant logs
3. 🔧 Debug and fix
4. 🔄 Rebuild and retest

### Future Enhancements
1. 🎨 Create actual WorldEdit schematics (replace procedural)
2. 🛣️ Generate connecting paths between buildings
3. 🏛️ Designate main building (forum/temple)
4. 👥 Add interior furnishings and decorations
5. 📦 Configure loot tables for storage containers

## Quick Commands

### In-Game
```
/votest list-villages              # See registered villages
/votest village-info roma-i        # Village details
/votest generate-structures roma-i # Regenerate structures (if needed)
```

### Console
```
[Server thread/INFO]: [VillageOverhaul] Loaded X structures
[Server thread/INFO]: [VO] Registered village 'Roma I'
```

### Logs Location
```
c:\Users\davis\Documents\Workspace\billineire-test-server\logs\latest.log
```

---

**Ready to test!** Delete the old world, start the server, and prepare to see actual Roman architecture instead of wooden boxes or giant dirt pillars! 🏛️
