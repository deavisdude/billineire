# Village Overhaul

A modern Minecraft village overhaul plugin inspired by [Millénaire](https://millenaire.org/), bringing dynamic, culturally-diverse villages with economy, progression, and player interaction to Paper/Purpur servers (1.20+).

## 🎮 What is This?

Village Overhaul transforms vanilla Minecraft villages into living, growing communities with:

- **Multi-Cultural Villages**: Villages inspired by historical cultures (Roman, Viking, Egyptian, and more) with unique architecture, professions, and trade goods
- **Dynamic Economy**: Trade with villagers using the Dollaz currency system (Millz, Billz, Trills) to fund village projects and earn reputation
- **Village Projects**: Your trades directly contribute to village building and expansion goals - watch villages upgrade buildings as they grow wealthier
- **Reputation & Contracts**: Complete contracts (deliveries, defense, dungeon clearing) to earn reputation and unlock unique items and property ownership
- **Custom Dungeons**: Tackle procedurally generated dungeons with custom enemies, commissioned by villages for generous rewards
- **Property Ownership**: Purchase plots and fully furnished homes in villages once you've earned enough money and reputation
- **Inter-Village Relations**: Villages maintain relationships with each other that players can influence
- **Cross-Edition Support**: Full compatibility with both Java and Bedrock clients via Geyser/Floodgate

## 🚀 Features

### Core Gameplay
- **Trade-Funded Projects**: Player trades fund village improvements like house upgrades and building expansions
- **Reputation System**: Earn standing through trading and completing contracts to unlock new opportunities
- **Village Contracts**: Accept and complete missions from villages for rewards and reputation
- **Deterministic Multiplayer**: Server-authoritative tick system ensures synchronized gameplay across all clients

### Technical Highlights
- **Performance Optimized**: Maintains 20 TPS with configurable budgets (≤2ms per village at Medium profile)
- **Data-Driven Content**: Cultures and village configurations via JSON/YAML datapacks
- **Bedrock Parity**: Full cross-play support with UI fallbacks for Bedrock clients
- **Modular Architecture**: Clean plugin API with optional integrations for Vault, LuckPerms, WorldGuard, FAWE, and MythicMobs

## 📋 Requirements

- **Server**: Paper 1.20+ or Purpur fork
- **Java**: JDK 17 or higher
- **Recommended Plugins**:
  - Geyser + Floodgate (for Bedrock support)
  - Vault (economy bridge)
  - LuckPerms (permissions)
  - WorldGuard + FAWE (region protection and builds)
  - Spark (performance profiling)

## 🛠️ Building

```bash
cd plugin
./gradlew shadowJar
```

The plugin JAR will be in `plugin/build/libs/village-overhaul-*.jar`

## 📖 Documentation

- **[Quickstart Guide](specs/001-village-overhaul/quickstart.md)** - Get up and running with Paper + Geyser/Floodgate
- **[Implementation Plan](specs/001-village-overhaul/plan.md)** - Technical architecture and design decisions
- **[Feature Specification](specs/001-village-overhaul/spec.md)** - Complete feature details and user stories
- **[Data Model](specs/001-village-overhaul/data-model.md)** - Entity relationships and validation rules
- **[Compatibility Matrix](docs/compatibility-matrix.md)** - Supported server profiles and smoke tests
- **[Cultural Review Checklist](docs/culture-review.md)** - Ensuring authentic cultural representation

## 🎯 Roadmap

### Phase 1: Foundation (Current)
- Core economy system (Dollaz)
- Village data structures and tick loop
- Basic trade system
- Project funding and completion

### Phase 2: Content & Progression
- Reputation system
- Village contracts
- Property ownership
- Multiple culture packs

### Phase 3: Advanced Features
- Custom dungeons and enemies
- Inter-village relations
- Advanced AI behaviors
- Performance optimization

## 🏛️ Design Principles

This project adheres to strict architectural principles:

1. **Cross-Edition Compatibility**: Full parity between Java and Bedrock clients
2. **Deterministic Multiplayer**: Server-authoritative state with reproducible behavior
3. **Performance Budgets**: Strict per-system tick budgets with monitoring
4. **Modularity**: Clean separation of concerns with well-defined APIs
5. **Cultural Authenticity**: Research-informed representation of historical cultures
6. **Save/Migration**: Forward-compatible save formats with migration tooling
7. **Observability**: Structured logging, metrics, and debug capabilities

See the [Constitution](specs/001-village-overhaul/plan.md#constitution-check) section for full details.

## 🤝 Contributing

This project uses the [Specify](https://specify.io/) workflow for feature planning and implementation. All features are documented in the `specs/` directory with detailed specifications, implementation plans, and checklists.

Before contributing cultural content, please review the [Cultural Review Checklist](docs/culture-review.md) to ensure accurate and respectful representation.

## 📜 License

[License information to be added]

## 🙏 Acknowledgments

Inspired by the classic [Millénaire](https://millenaire.org/) mod by Kinniken and the community that made it beloved.

---

**Status**: Early Development (v0.1.0-SNAPSHOT)  
**Target**: Paper/Purpur 1.20+ with Java 17
