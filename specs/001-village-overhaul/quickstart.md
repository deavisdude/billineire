# Quickstart — Village Overhaul (Plan A)

This guide runs the plugin on a Paper server with Java clients and Bedrock cross-play via Geyser/Floodgate.

## Prerequisites
- Java 17 installed
- Paper 1.20+ server files
- Plugins: Geyser, Floodgate, Vault, (optional) LuckPerms, WorldGuard, FAWE, Spark
- This plugin JAR (to be built later)

## Setup Steps
1) Create a clean Paper server and run once to generate folders.
2) Drop Geyser and Floodgate into `plugins/` and configure Bedrock port/auth.
3) Drop Vault (and LuckPerms/WorldGuard/FAWE/Spark if desired) into `plugins/`.
4) Drop the Village Overhaul plugin JAR into `plugins/`.
5) Start the server. Confirm `20 TPS` idle and no startup errors.
6) Join with a Java client; also join with a Bedrock client via Geyser to validate cross-play.
7) Use provided test commands to spawn a seed village and enable a sample project.
8) Perform trades to fund the project; observe progress and building upgrade upon completion.

## Test Scenarios
- Economy: Grant 10,000 Millz to a test player and purchase a small plot; verify wallet logs and ownership.
- Contracts: Accept a fetch contract; complete objectives; confirm reputation and rewards.
- Dungeons: Generate a test dungeon from seed; clear it; validate synchronized state for Java+Bedrock party.
- Performance: Use `/spark profiler` for 2 minutes during dungeon fight; confirm p95/p99 within budgets.

## Troubleshooting
- If Bedrock cannot join: verify Floodgate auth and Geyser ports.
- If TPS dips on generation: enable FAWE for edits; reduce generation radius; ensure structures are pre-baked.
- If permissions block commands: configure LuckPerms groups; ensure ops have required nodes.
