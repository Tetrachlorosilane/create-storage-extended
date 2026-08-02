# Create: Storage Extended

> A server-side companion mod for [Create: Storage](https://modrinth.com/mod/create-storage-neo-forge) that replaces the original BFS-based storage network discovery with a persistent, event-driven topology system.

---

## What This Mod Does

| Original (fxntstorage) | Extended |
|---|---|
| BFS rediscovery every 20 ticks | Event-driven: changes are recorded by a `Level.setBlock` hook and resolved in a deferred tick pass |
| Network topology not saved | World `SavedData` persists full topology |
| No unique network ID | Every network has a persistent `UUID` |
| Controller-dependent topology | Pure connectivity - breaking a controller does not split |
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
├── CreateStorageExtended.java          # Mod entry point: tick pass, chunk-load cleanup, commands
├── Config.java                         # debugLogging toggle
├── network/
│   ├── StorageNetworkData.java         # World SavedData (persisted topology)
│   ├── StorageNetworkManager.java      # Singleton: change recording, tick pass, full rebuild
│   └── INetworkComponent.java          # BE interface: get/setStorageNetworkId
├── mixin/
│   ├── BlockEntityNetworkMixin.java    # Universal NBT persistence + registration for all components
│   ├── ChunkMapAccessor.java           # Exposes ChunkMap.getChunks() for the rebuild command
│   ├── LevelSetBlockMixin.java         # Records network-block changes from Level.setBlock
│   ├── StorageControllerEntityMixin.java
│   ├── StorageInterfaceEntityMixin.java
│   ├── SimpleStorageBoxEntityMixin.java
│   └── StorageNetworkMixin.java        # Replaces BFS with SavedData lookup
```

### Data Flow

```
Block Placed / Removed / Replaced ──→ Level.setBlock
                 └── LevelSetBlockMixin.onSetBlockReturn (success only)
                       └── StorageNetworkManager.markChanged(pos)

Next server tick ──→ StorageNetworkManager.onServerTick
                       └── resolveChanges(changed):
                             removals → component convergence (merge/register)
                                       → split detection → ghost/empty cleanup

Chunk Load ──→ BlockEntityNetworkMixin.loadAdditional → registerComponent
               └── SavedData authoritative check → corrects stale UUIDs
            ──→ CreateStorageExtended.onChunkLoad → cleanupGhostsInChunk

Network Query ──→ StorageNetworkMixin intercepts getConnectedComponents
                   └── StorageNetworkManager.getNetworkMembers (from SavedData)
```

### Key Behaviors

- **Order-independent bulk changes**: `setBlock` only records changed positions; all joins, merges, splits and cleanups happen in one pass at the next tick, computed from the final world state. Bulk placements can never stay split regardless of placement order.
- **Merges**: a connected component that carries several network ids is merged into one; unregistered members are registered on the spot.
- **Splits**: after a removal, any network whose members now span more than one physical component is split into one network per component.
- **Ghost cleanup**: members whose position no longer holds a network block are removed; members stranded in unloaded chunks (e.g. after a large-scale move) are cleaned up when their chunk loads.
- **Stale UUIDs**: when a chunk loads with an old NBT UUID, `registerComponent` checks `StorageNetworkData` first - SavedData is authoritative, BE is corrected.
- **Empty networks**: deleted when the last member is removed, skipped during save.
- **Storage Trim**: supported via the `fxntstorage:storage_network_block` tag (topology lives in SavedData).

---

## Commands

| Command | Permission | Description |
|---|---|---|
| `/createstorageextended rebuildnetworks` | op (level 2) | Clears the persisted topology and rebuilds every network from the currently-loaded chunks. The scan is spread over several ticks so large worlds do not lag. Unloaded chunks are **not** force-loaded; their network blocks are re-registered automatically when the chunks load. |

---

## Configuration

| Key | Type | Default | Description |
|---|---|---|---|
| `debugLogging` | boolean | `false` | Enable detailed topology operation logging (network creation, joins, merges, splits, id corrections) |

Note: Diagnostics are logged at DEBUG level - in addition to this option, the mod log4j level must allow DEBUG output for the messages to appear.

---

## License

GNU GPL 3.0
