# Floating Structures & Path Generation Fix

**Date**: 2025-11-06  
**Issues**: 
1. Structures floating with air gaps beneath them
2. Path generation failing ("Failed to generate path from main to building")

## Root Causes

### Issue 1: Floating Structures
**Location**: `TerraformingUtil.java` - `prepareSite()` method

The code was skipping **all terraforming** (including foundation filling) for large structures (>30x30 footprint):

```java
// OLD CODE - WRONG!
if (isLargeStructure) {
    LOGGER.info("Large structure detected, skipping terraforming");
    return true;  // ← No foundation filling!
}
```

This left air pockets underneath large buildings, causing them to appear floating.

### Issue 2: Path Generation Failure  
**Location**: `PathServiceImpl.java` - `generatePath()` method

Insufficient debug logging made it impossible to diagnose why A* pathfinding was failing. The logs only showed "Failed to generate path" without details about:
- Start/end coordinates
- Terrain elevation differences  
- Distance calculations
- Why the path was rejected

## Fixes Applied

### Fix 1: Foundation Filling for Large Structures

Modified `TerraformingUtil.prepareSite()` to:
1. **Always trim vegetation** (trees inside buildings = bad)
2. **Skip grading for large structures** (to avoid massive terrain changes)
3. **ALWAYS fill foundation gaps** (prevents floating, even for large structures)

```java
// NEW CODE - CORRECT!
if (isLargeStructure) {
    LOGGER.info("Large structure detected, skipping grading but filling foundation gaps");
    
    // Fill gaps beneath foundation to prevent floating structures
    int filled = fillGapsWithLimit(world, origin, width, depth, targetY - 1, maxBlocks);
    
    if (filled < 0) {
        LOGGER.warning("Foundation filling exceeded limits");
        // Continue anyway - better to have some floating than no structure
    }
    
    LOGGER.info(String.format("Site prepared (large): trimmed=%d, foundation filled=%d",
            trimmed, filled > 0 ? filled : 0));
    return true;
}
```

**How `fillGapsWithLimit()` works**:
- Only fills within 3 blocks below foundation level (MAX_VERTICAL_CHANGE)
- Does NOT build pillars from bedrock
- Uses dirt blocks for natural appearance
- Respects block budget (max 2000 blocks for large structures)
- Skips water blocks and existing solid blocks

### Fix 2: Enhanced Path Generation Logging

Added detailed logging to `PathServiceImpl.generatePath()`:

```java
LOGGER.fine(String.format("[STRUCT] Attempting path from (%d,%d,%d) to (%d,%d,%d), distance=%.1f",
        start.getBlockX(), start.getBlockY(), start.getBlockZ(),
        end.getBlockX(), end.getBlockY(), end.getBlockZ(), distance));

// After pathfinding...
if (path == null || path.isEmpty()) {
    LOGGER.warning(String.format("[STRUCT] No path found from (%d,%d,%d) to (%d,%d,%d)",
            start.getBlockX(), start.getBlockY(), start.getBlockZ(),
            end.getBlockX(), end.getBlockY(), end.getBlockZ()));
    return Optional.empty();
}

LOGGER.info(String.format("[STRUCT] Path found: distance=%.1f, blocks=%d", distance, pathBlocks.size()));
```

This will help diagnose:
- Building elevation differences
- Whether structures are too far apart
- If terrain costs are blocking paths

## Expected Behavior After Fix

### Logs You Should See

**For Large Structures**:
```
[STRUCT] Large structure detected (60x89), skipping grading but filling foundation gaps
[STRUCT] Site prepared (large): trimmed=288, foundation filled=1523
```

**For Path Generation** (if successful):
```
[STRUCT] Attempting path from (220,83,238) to (160,73,238), distance=62.4
[STRUCT] Path found: distance=62.4, blocks=67
[STRUCT] Path emitted: culture=roman, blocks=67, material=COBBLESTONE
[STRUCT] Path smoothed: culture=roman, blocks=8
```

**For Path Generation** (if failing, now with details):
```
[STRUCT] Attempting path from (220,83,238) to (160,73,238), distance=62.4
[STRUCT] No path found from (220,83,238) to (160,73,238)
```

### Visual Results

1. **No more floating structures**: Buildings will have dirt foundation filling air gaps
2. **Paths visible between buildings** (if terrain permits pathfinding)
3. **Natural appearance**: Only small gaps filled, no massive pillars

## Testing Steps

```powershell
# 1. Rebuild plugin
cd plugin
.\gradlew shadowJar

# 2. Copy to test server
copy .\build\libs\village-overhaul-0.1.0-SNAPSHOT-all.jar ..\test-server\plugins\

# 3. Delete old world (to test fresh village generation)
rm -r ..\test-server\world

# 4. Restart server and create NEW village
```

## Known Limitations

### Foundation Filling Constraints
- Max 2000 blocks per large structure (prevents runaway filling)
- Only fills within 3 blocks below foundation (no deep pits)
- Won't fill over water blocks (preserves lakes/rivers)
- If budget exceeded, structure may still have some floating sections

### Path Generation May Still Fail If:
- Buildings >200 blocks apart (MAX_SEARCH_DISTANCE)
- Terrain too steep between buildings (>3 blocks per horizontal distance)
- Major water bodies blocking path
- Structures at vastly different elevations

## Next Steps

If paths still don't generate after this fix:
1. Check new debug logs to see exact failure reason
2. Consider increasing MAX_SEARCH_DISTANCE if buildings are far apart
3. Consider relaxing MAX_ACCEPTABLE_SLOPE if terrain is hilly
4. Consider path segments that go around obstacles instead of direct A*

## Files Modified

1. `TerraformingUtil.java` - Fixed foundation filling for large structures
2. `PathServiceImpl.java` - Added detailed path generation logging

## Build Status

```
BUILD SUCCESSFUL in 2s
JAR ready: plugin/build/libs/village-overhaul-0.1.0-SNAPSHOT-all.jar
```
