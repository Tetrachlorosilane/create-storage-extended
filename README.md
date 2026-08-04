# Create: Storage Extended

> A companion mod for [Create: Storage](https://modrinth.com/mod/create-storage-neo-forge) with two independent halves:
>
> - **Server side** — replaces the original BFS-based storage network discovery with a persistent, event-driven topology system.
> - **Client side (optional)** — face-culling optimization for the storage boxes, removing faces that are hidden against full blocks.

---

## Installation / Compatibility

Both halves are fully decoupled — neither side requires the other, and no version matching is enforced:

- **Server-only install**: install on the server; clients do **not** need the mod. The mod declares `displayTest = "IGNORE_SERVER_ONLY"`, so even a client that happens to carry a different version of the mod is never rejected.
- **Client-only install**: install on the client; the server does **not** need the mod.
- Install on both for the full experience: server topology + client rendering optimization.

The client half has **no server-data dependency**: face culling works purely from local world state, so it is equally correct with or without the server half.

---

## What This Mod Does

**Server side:**

| Original (fxntstorage) | Extended |
|---|---|
| BFS rediscovery every 20 ticks | Event-driven: changes are recorded by a `Level.setBlock` hook and resolved in a deferred tick pass |
| Network topology not saved | World `SavedData` persists full topology |
| No unique network ID | Every network has a persistent `UUID` |
| Controller-dependent topology | Pure connectivity - breaking a controller does not split |
| Interfaces hold direct Java refs | UUID-based lookup via `StorageNetworkManager` |
| Chunk unload breaks references | SavedData independent of chunk loading |

**Client side (optional):**

| Rendering (fxntstorage) | Extended |
|---|---|
| Box faces always drawn, even against full blocks | Faces pressed against fully opaque neighbours are culled (model `cullface` + renderer overlay skip) |

---

## Dependencies

| Mod | Version | Side |
|---|---|---|
| NeoForge | 21.1+ | both |
| Create | 6.0.7+ | both (transitive) |
| Create: Storage (fxntstorage) | 1.3+ | server only (declared `side = "SERVER"`) |

---

## Architecture

```
src/main/java/net/Tetrachlorosilane/createstorageextended/
├── CreateStorageExtended.java          # Mod entry: tick pass, chunk-load cleanup, commands
├── Config.java                         # debugLogging toggle
├── client/
│   └── RenderCulling.java              # Client culling: occlusion, behind-test, overlay distance
├── network/
│   ├── StorageNetworkData.java         # World SavedData (persisted topology) + per-chunk index
│   ├── StorageNetworkManager.java      # Singleton: change recording, tick pass, full rebuild
│   ├── BlockPosEncoding.java           # Packed 64-bit coordinate codec (BFS + NBT)
│   └── INetworkComponent.java          # BE interface: get/setStorageNetworkId
└── mixin/
    ├── BlockEntityNetworkMixin.java    # NBT persistence + registration for all components
    ├── ChunkMapAccessor.java           # Exposes ChunkMap.getChunks() for the rebuild command
    ├── LevelSetBlockMixin.java         # Records network-block changes from Level.setBlock
    ├── StorageControllerEntityMixin.java
    ├── StorageInterfaceEntityMixin.java
    ├── SimpleStorageBoxEntityMixin.java
    ├── StorageNetworkMixin.java        # Replaces BFS with SavedData lookup
    ├── SimpleStorageBoxEntityRendererMixin.java   # Client: skip overlay (occluded / behind / far)
    └── StorageBoxEntityRendererMixin.java         # Client: same for the Storage Box
```

### Server Data Flow

```
Block Placed / Removed / Replaced ──→ Level.setBlock
                 └── LevelSetBlockMixin.onSetBlockReturn (success only)
                       └── StorageNetworkManager.markChanged(pos)

Next server tick ──→ StorageNetworkManager.onServerTick (skipped entirely when no pending work)
                       └── resolveChanges(changed):
                             removals → component convergence (merge/register)
                                       → split detection → ghost/empty cleanup

Chunk Load ──→ BlockEntityNetworkMixin.loadAdditional → registerComponent
               └── SavedData authoritative check → corrects stale UUIDs
            ──→ CreateStorageExtended.onChunkLoad → cleanupGhostsInChunk (chunk-indexed)

Network Query ──→ StorageNetworkMixin intercepts getConnectedComponents
                   └── StorageNetworkManager.getNetworkMembers (from SavedData)
```

### Client Rendering Flow

```
For every storage-box BE:
  ├─ model: storage_box_base / storage_box_light overrides declare cullface
  │         → faces pressed against fully opaque neighbours are never baked
  ├─ distance: beyond the overlay render distance (Create filterItemRenderDistance,
  │            read lazily on first render) the whole BE render is skipped
  ├─ occlusion: front neighbour fully opaque → overlay skipped
  └─ behind: box facing away from the camera → overlay skipped
Ponder scenes are exempt (overlay always shown, distance forced to 5)
```

### Key Behaviors

- **Order-independent bulk changes**: `setBlock` only records changed positions; all joins, merges, splits and cleanups happen in one pass at the next tick, computed from the final world state. Bulk placements can never stay split regardless of placement order.
- **Merges**: a connected component that carries several network ids is merged into one; unregistered members are registered on the spot.
- **Splits**: after a removal, any network whose members now span more than one physical component is split into one network per component.
- **Ghost cleanup**: members whose position no longer holds a network block are removed; members stranded in unloaded chunks (e.g. after a large-scale move) are cleaned up when their chunk loads — a per-chunk index keeps this O(that chunk) instead of O(all members).
- **Stale UUIDs**: when a chunk loads with an old NBT UUID, `registerComponent` checks `StorageNetworkData` first - SavedData is authoritative, BE is corrected.
- **Empty networks**: deleted when the last member is removed, skipped during save.
- **Storage Trim**: supported via the `fxntstorage:storage_network_block` tag (topology lives in SavedData).
- **Client overlay culling**: the front overlay is skipped when it cannot be seen — the box faces away from the camera (behind test), the front is pressed against a fully opaque neighbour (occlusion, same predicate as the model culler), or the box is beyond the overlay render distance (default: Create's `filterItemRenderDistance`, read lazily on first render). Ponder scenes are exempt.
- **Bidirectional decoupling**: the server and client halves are independent; neither requires the other, and no version matching is enforced.
- **Bounded capture stack**: the `Level.setBlock` capture stack self-heals after an exceptional unwind, so it can never grow unboundedly.

---

## Client-Side Rendering Optimization

Implemented as two resource overrides plus two client-only mixins:

| File | Effect |
|---|---|
| `assets/fxntstorage/models/block/storage_box_base.json` | all 10 elements gain `cullface`: side faces are culled by their own direction; the whole recessed front compound (chamfer + display recess) is culled by `north` |
| `assets/fxntstorage/models/block/storage_box_light.json` | status light culled when the front is blocked |
| `SimpleStorageBoxEntityRendererMixin` | skips the front overlay when the front neighbour fully occludes |
| `StorageBoxEntityRendererMixin` | same for the Storage Box renderer (its `renderSafe` entry point) |

Notes:

- Affects both Simple Storage Boxes (12 wood variants) and Storage Boxes (7 variants) — they share the `storage_box_base` model.
- The overlay skip uses the same occlusion predicate as the model culler, so the two layers always agree.
- The overlay is skipped in three situations: beyond the overlay render distance (threshold = Create `filterItemRenderDistance` squared, read lazily on first render and cached - changing the config requires a restart), front neighbour fully opaque, or box facing away from the camera (behind test). Ponder scenes are always exempt.
- **Version coupling note**: the overrides replace the upstream model files wholesale. If a future fxntstorage release changes the box geometry, the overrides must be updated in lockstep.

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
