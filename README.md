# Create: Storage Extended

> A server-side companion mod for [Create: Storage](https://github.com/Creators-of-Create/Create) that replaces the original BFS-based storage network discovery with a persistent, event-driven topology system.

---

## What This Mod Does

**Create: Storage Extended** injects into fxntstorage's storage network system and fundamentally changes how networks are discovered, maintained, and persisted.

### Before (vanilla Create: Storage)

- Storage networks are rediscovered every 20 ticks via BFS flood-fill (6-direction neighbor scan)
- Network topology is **not saved** — it's recomputed from scratch on every tick and on world reload
- There is no unique network identifier — components find each other by scanning neighbors
- Interfaces hold a direct Java reference to the controller, which breaks on chunk unload
- When a network changes, it takes up to 20 ticks before the change is reflected

### After (with this mod)

| Feature | Description |
|---|---|
| **Persistent topology** | Network members are saved to world-level `SavedData`. On reload, the network is restored instantly without BFS. |
| **Unique network ID** | Every network receives a `java.util.UUID`. Each component persists the UUID in its NBT. |
| **O(1) component lookup** | Given any component's position, `StorageNetworkManager.getNetworkMembers()` returns the full set instantly. |
| **Event-driven updates** | Block placement → join/merge network. Block removal → split network via connectivity BFS. No periodic scanning. |
| **Controller-independent topology** | The network exists independently of any controller block. Breaking a controller does not split the network — interfaces simply lose their reference until a controller is placed again. |
| **Unload-safe** | The topology is saved independently of chunk loading. Querying a network from unloaded chunks returns correct data. |
| **Storage Trim support** | Blocks with the `fxntstorage:storage_network_block` tag (including Storage Trim) act as network connectors even though they don't implement `INetworkComponent`. |

---

## Dependencies

| Mod | Version | Required |
|---|---|---|
| [NeoForge](https://neoforged.net) | 21.1.181+ | Yes |
| [Create](https://modrinth.com/mod/create) | 6.0.7+ | Yes |
| [Create: Storage](https://modrinth.com/mod/create-storage-neo-forge) | 1.3+ | Yes |

---

## Architecture Overview

```
create-storage-extended/
├── network/
│   ├── StorageNetworkData.java     # World SavedData — persists all network topologies
│   ├── StorageNetworkManager.java  # Singleton managing network lifecycle (place/break/merge/split)
│   └── INetworkComponent.java      # Interface implemented by all network components
├── mixin/
│   ├── BlockEntityNetworkMixin.java        # Universal NBT persistence for all INetworkComponent BEs
│   ├── StorageControllerEntityMixin.java   # Adds networkId field + registration tick
│   ├── StorageInterfaceEntityMixin.java    # Adds networkId field + registration tick
│   ├── SimpleStorageBoxEntityMixin.java    # Adds networkId field + NBT + registration tick
│   └── StorageNetworkMixin.java            # Replaces BFS with persisted-data lookup (O(1))
├── event/
│   └── NetworkEventHandler.java   # BlockEvent.EntityPlaceEvent / BreakEvent → network update
└── Config.java                    # Debug logging and BFS search range
```

### Data Flow

```
Block Place ──→ NetworkEventHandler.onBlockPlaced
                 ├── be instanceof INetworkComponent? → read existingId
                 ├── block has storage_network_block tag? → treat as connector
                 └── StorageNetworkManager.onBlockPlaced
                       ├── findAllAdjacentNetworks (6 neighbors)
                       ├── 0 → createNetwork() (new UUID)
                       ├── 1 → addToNetwork()
                       └── 2+ → mergeNetworks() + updateComponentId() on all merged members

Block Break ──→ NetworkEventHandler.onBlockBroken
                 └── StorageNetworkManager.onBlockRemoved
                       ├── removeFromNetwork()
                       ├── findConnectedGroups (BFS on remaining members)
                       ├── 1 group → network intact, nothing to do
                       └── 2+ groups → keep first with old ID, create new IDs for others
                             └── updateComponentId() on all split members

World Load ──→ BlockEntityNetworkMixin.loadAdditional
                 ├── Save key "StorageNetworkId" → UUID
                 └── StorageNetworkManager.registerComponent()

Network Query ──→ StorageNetworkMixin intercepts getConnectedComponents()
                   ├── controller has networkId? → StorageNetworkManager.getNetworkMembers() (O(1))
                   └── no networkId? → fall back to original BFS
```

---

## Configuration

| Config Key | Type | Default | Description |
|---|---|---|---|
| `debugLogging` | boolean | `false` | Enable detailed logging of network operations |
| `networkSearchRange` | int (8–128) | `32` | Maximum BFS range for split detection |

---

## License

GNU GPL 3.0
