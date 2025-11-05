# Performance Test Baselines

This directory contains performance test baselines and results for the Village Overhaul plugin.

## NPC Performance Tests (T019s)

Performance budgets per FR-012:
- **Low Profile**: ≤5ms per village per tick
- **Medium Profile**: ≤2ms per village per tick
- **High Profile**: ≤1ms per village per tick

### Baseline Files

- `npc-baseline-Low.json` - Low profile baseline
- `npc-baseline-Medium.json` - Medium profile baseline (primary target)
- `npc-baseline-High.json` - High profile baseline

### Running Tests

```powershell
# Test Medium profile (default)
.\scripts\ci\sim\test-npc-performance.ps1

# Test with more villagers
.\scripts\ci\sim\test-npc-performance.ps1 -VillagerCount 20

# Test High profile
.\scripts\ci\sim\test-npc-performance.ps1 -PerfProfile High
```

### Interpreting Results

Each baseline file contains:
- `avg_tick_time_ms` - Average NPC tick time
- `max_tick_time_ms` - Maximum observed tick time
- `min_tick_time_ms` - Minimum observed tick time
- `sample_count` - Number of samples collected
- `passed` - Whether the test met the performance budget

### CI Integration

Performance tests should be run:
1. Before merging major NPC changes
2. As part of the nightly build
3. When adding new NPC features
4. After optimization attempts

## Other Performance Tests

(Add additional performance test documentation here as needed)
