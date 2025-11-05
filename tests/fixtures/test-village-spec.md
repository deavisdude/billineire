# Test Village Fixture

**Purpose**: Deterministic test village for US1 validation

## Village Specification

- **Culture**: Roman
- **Name**: Test Village Alpha
- **Seed**: `villageoverhaul_test_001`
- **Location**: Spawn coordinates (0, 64, 0)
- **Initial Wealth**: 0 Millz

## Buildings

### Initial State
1. **Forum** (Town Center)
   - Structure ID: `roman_forum_tier1`
   - Coordinates: (0, 64, 0)
   - Size: 15x10x15 blocks

2. **Small Villa** (Residential)
   - Structure ID: `roman_villa_s_tier1`
   - Coordinates: (20, 64, 0)
   - Size: 10x8x10 blocks

### Upgrade Path (for US1 testing)

**Project 1**: Forum Expansion
- **Cost**: 10,000 Millz (100 Billz)
- **Upgrade**: `roman_forum_tier1` → `roman_forum_tier2`
- **Changes**: 
  - Add fountain in center
  - Expand trading stalls (+2 traders)
  - Add decorative columns
- **Deterministic**: Same seed produces identical structure

## Trade Offers

See `trades/roman_test.json` for initial trade definitions.

## Usage

### Manual Placement (for local testing)
1. Start Paper server with plugin
2. Use `/vo village create roman "Test Village Alpha" 0 64 0`
3. Structures will be placed via FAWE

### CI Simulation
1. Load world with seed `villageoverhaul_test_001`
2. Inject village via VillageService.loadVillage()
3. Run N-tick simulation with trade events
4. Assert project completion and structure upgrade

## Validation Checklist

- [ ] Village spawns at correct coordinates
- [ ] Initial structures match specifications
- [ ] Culture assignment is "roman"
- [ ] Trade offers are available
- [ ] Project cost is 10,000 Millz
- [ ] Upgrade triggers deterministically at 100% funding
- [ ] Structure changes are reproducible from seed
