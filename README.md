# Create: Storage Extended

> A server-side companion mod for [Create: Storage](https://modrinth.com/mod/create-storage-neo-forge) that replaces the original BFS-based storage network discovery with a persistent, event-driven topology system.

---

## What This Mod Does

| Original (fxntstorage) | Extended |
|---|---|
| BFS rediscovery every 20 ticks | Event-driven: `Level.setBlock` hook catches all changes |
| Network topology not saved | World `SavedData` persists full topology |
| No unique network ID | Every network has a persistent `UUID` |
| Controller-dependent topology | Pure connectivity — breaking a controller does not split |
| Interfaces hold direct Java refs | UUID-based lookup via `StorageNetworkManager` |
| Chunk unload breaks references | SavedData independent of chunk loading |

---

## Dependencies

| Mod | Version |
|---|---|
| NeoForge | 21.1+ |
| Create | 6.0.7+ |
| Create: Storage (fxntstorage) | 1.3+ |

---

## Architecture

```
src/main/java/net/Tetrachlorosilane/createstorageextended/
├── CreateStorageExtended.java          # Mod entry point
├── Config.java                         # debugLogging toggle
├── network/
│   ├── StorageNetworkData.java         # World SavedData (persisted topology)
│   ├── StorageNetworkManager.java      # Singleton: place/remove/merge/split
│   └── INetworkComponent.java          # BE interface: get/setStorageNetworkId
├── mixin/
│   ├── BlockEntityNetworkMixin.java    # Universal NBT persistence for all components
│   ├── LevelSetBlockMixin.java         # Hooks Level.setBlock → handles ALL block changes
│   ├── StorageControllerEntityMixin.java
│   ├── StorageInterfaceEntityMixin.java
│   ├── SimpleStorageBoxEntityMixin.java
│   └── StorageNetworkMixin.java        # Replaces BFS with SavedData lookup
```

### Data Flow

```
Block Placed ──→ Level.setBlock (old=air, new=network_block)
                 └── LevelSetBlockMixin.onSetBlock
                       └── StorageNetworkManager.onBlockPlaced → neighbor merge + join

Block Removed ──→ Level.setBlock (old=network_block, new=air)
                   └── LevelSetBlockMixin.onSetBlock
                         └── StorageNetworkManager.onBlockRemoved → split detection

World Load ──→ BlockEntityNetworkMixin.loadAdditional → registerComponent
               └── SavedData authoritative check → corrects stale UUIDs

Network Query ──→ StorageNetworkMixin intercepts getConnectedComponents
                   └── StorageNetworkManager.getNetworkMembers (O(1) from SavedData)
```

### Key Behaviors

- **Merges**: Block placed between two networks → `findAllAdjacentNetworks` detects both → merges all into one UUID → updates all affected BEs.
- **Splits**: Block removed → `findConnectedGroups` runs pure positional BFS on remaining members → each disconnected group becomes its own network.
- **Stale UUIDs**: When a chunk loads with an old NBT UUID, `registerComponent` checks `StorageNetworkData` first — SavedData is authoritative, BE is corrected.
- **Empty networks**: Deleted when last member removed, skipped during save.
- **Storage Trim**: Supported via `fxntstorage:storage_network_block` tag (no `INetworkComponent` needed — topology lives in SavedData).

---

## Configuration

| Key | Type | Default | Description |
|---|---|---|---|
| `debugLogging` | boolean | `false` | Enable detailed topology operation logging |

Note: Log level can also be controlled via standard Log4j configuration.

---

## License

GNU GPL 3.0
