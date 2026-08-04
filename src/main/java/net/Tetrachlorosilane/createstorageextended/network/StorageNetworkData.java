package net.Tetrachlorosilane.createstorageextended.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * World-level persisted data that stores all storage network topologies.
 */
public class StorageNetworkData extends SavedData {

    private static final String DATA_NAME = "createstorageextended_networks";
    private static final String KEY_NETWORKS = "Networks";
    private static final String KEY_ID = "Id";
    private static final String KEY_POSITIONS = "Positions";

    // network UUID -> all member block positions
    private final Map<UUID, Set<BlockPos>> networks = new HashMap<>();
    // block position -> network UUID (reverse lookup)
    private final Map<BlockPos, UUID> blockToNetwork = new HashMap<>();
    /**
     * Derived index: chunk key (x<<32|z) -> member positions. Keeps per-chunk
     * ghost cleanup O(that chunk) instead of O(all members).
     * <p>
     * MAINTENANCE CONSTRAINT: this index is a projection of {@link #networks}
     * (and {@link #blockToNetwork}); every path that adds or removes members
     * MUST keep it in sync - currently {@link #addToNetwork},
     * {@link #removeFromNetwork}, {@link #clear}, {@link #load} and
     * {@link #cleanupGhostsInChunk}. {@link #mergeNetworks} needs no change
     * because member positions do not move. If a new mutation path is added,
     * update this index too, otherwise ghost cleanup silently stops covering
     * the affected chunk.
     */
    private final Map<Long, Set<BlockPos>> chunkMembers = new HashMap<>();

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static long chunkKey(BlockPos pos) {
        return chunkKey(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static StorageNetworkData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(StorageNetworkData::new, StorageNetworkData::load, null),
                DATA_NAME
        );
    }

    // ========== Network CRUD ==========

    public UUID createNetwork() {
        UUID id = UUID.randomUUID();
        networks.put(id, new LinkedHashSet<>());
        setDirty();
        return id;
    }

    public void createNetwork(UUID id) {
        if (networks.putIfAbsent(id, new LinkedHashSet<>()) == null) {
            setDirty();
        }
    }

    public void addToNetwork(UUID networkId, BlockPos pos) {
        UUID oldNetwork = blockToNetwork.get(pos);
        if (oldNetwork != null && oldNetwork.equals(networkId)) return;

        removeFromNetwork(pos);

        networks.computeIfAbsent(networkId, k -> new LinkedHashSet<>()).add(pos);
        blockToNetwork.put(pos, networkId);
        chunkMembers.computeIfAbsent(chunkKey(pos), k -> new HashSet<>()).add(pos);
        setDirty();
    }

    public void removeFromNetwork(BlockPos pos) {
        UUID networkId = blockToNetwork.remove(pos);
        if (networkId != null) {
            Set<BlockPos> members = networks.get(networkId);
            if (members != null) {
                members.remove(pos);
            }
            removeChunkMember(pos);
            setDirty();
        }
    }

    private void removeChunkMember(BlockPos pos) {
        long key = chunkKey(pos);
        Set<BlockPos> bucket = chunkMembers.get(key);
        if (bucket != null) {
            bucket.remove(pos);
            if (bucket.isEmpty()) {
                chunkMembers.remove(key);
            }
        }
    }

        /** Removes networks that no longer have any members. */
    public void cleanupEmptyNetworks() {
        boolean dirty = networks.values().removeIf(Set::isEmpty);
        if (dirty) setDirty();
    }

    /**
     * Removes persisted members inside the given chunk whose position no
     * longer holds a network block. Called when the chunk loads, so members
     * stranded in previously-unloaded chunks are cleaned up without ever
     * force-loading them.
     */
    /**
     * Removes every network and every membership. Used by the full rebuild
     * command before rescanning the world.
     */
    public void clear() {
        networks.clear();
        blockToNetwork.clear();
        chunkMembers.clear();
        setDirty();
    }

    public void cleanupGhostsInChunk(ServerLevel level, int chunkX, int chunkZ, TagKey<Block> networkBlockTag) {
        long key = chunkKey(chunkX, chunkZ);
        Set<BlockPos> bucket = chunkMembers.get(key);
        if (bucket == null || bucket.isEmpty()) return;

        boolean dirty = false;
        Iterator<BlockPos> it = bucket.iterator();
        while (it.hasNext()) {
            BlockPos pos = it.next();
            if (!level.getBlockState(pos).is(networkBlockTag)) {
                it.remove();
                UUID networkId = blockToNetwork.remove(pos);
                if (networkId != null) {
                    Set<BlockPos> members = networks.get(networkId);
                    if (members != null) {
                        members.remove(pos);
                    }
                }
                dirty = true;
            }
        }
        if (bucket.isEmpty()) {
            chunkMembers.remove(key);
        }
        if (dirty) setDirty();
    }

    public Set<BlockPos> mergeNetworks(UUID sourceId, UUID targetId) {
        Set<BlockPos> sourceMembers = networks.get(sourceId);
        if (sourceMembers == null) return Collections.emptySet();

        Set<BlockPos> targetMembers = networks.computeIfAbsent(targetId, k -> new LinkedHashSet<>());
        targetMembers.addAll(sourceMembers);

        for (BlockPos pos : sourceMembers) {
            blockToNetwork.put(pos, targetId);
        }

        networks.remove(sourceId);
        setDirty();
        return sourceMembers;
    }

    // ========== Queries ==========

    @Nullable
    public UUID getNetworkId(BlockPos pos) {
        return blockToNetwork.get(pos);
    }

    public Set<BlockPos> getNetworkMembers(UUID networkId) {
        Set<BlockPos> members = networks.get(networkId);
        return members != null ? Set.copyOf(members) : Set.of();
    }

    /**
     * Return all networks that are adjacent to the given position.
     */
    public Set<UUID> findAllAdjacentNetworks(BlockPos pos) {
        Set<UUID> result = new LinkedHashSet<>();
        for (var dir : net.minecraft.core.Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            UUID id = blockToNetwork.get(neighbor);
            if (id != null) result.add(id);
        }
        return result;
    }

    // ========== Persistence ==========

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag networksList = new ListTag();
        for (var entry : networks.entrySet()) {
            if (entry.getValue().isEmpty()) continue; // skip empty ghost networks
            CompoundTag networkTag = new CompoundTag();
            networkTag.putUUID(KEY_ID, entry.getKey());

            ListTag posList = new ListTag();
            for (BlockPos pos : entry.getValue()) {
                posList.add(LongTag.valueOf(BlockPosEncoding.encode(pos)));
            }
            networkTag.put(KEY_POSITIONS, posList);
            networksList.add(networkTag);
        }
        tag.put(KEY_NETWORKS, networksList);
        return tag;
    }

    public static StorageNetworkData load(CompoundTag tag, HolderLookup.Provider registries) {
        StorageNetworkData data = new StorageNetworkData();

        ListTag networksList = tag.getList(KEY_NETWORKS, Tag.TAG_COMPOUND);
        for (int i = 0; i < networksList.size(); i++) {
            CompoundTag networkTag = networksList.getCompound(i);
            UUID id = networkTag.getUUID(KEY_ID);

            Set<BlockPos> positions = new LinkedHashSet<>();
            ListTag posList = (networkTag.get(KEY_POSITIONS) instanceof ListTag list) ? list : new ListTag();
            for (int j = 0; j < posList.size(); j++) {
                Tag posTag = posList.get(j);
                if (posTag instanceof LongTag longTag) {
                    positions.add(BlockPosEncoding.decode(longTag.getAsLong()));
                } else if (posTag instanceof CompoundTag legacyTag) {
                    // Legacy format (pre-coordinate-packing): three ints X/Y/Z.
                    positions.add(new BlockPos(
                            legacyTag.getInt("X"),
                            legacyTag.getInt("Y"),
                            legacyTag.getInt("Z")
                    ));
                }
            }

            data.networks.put(id, positions);
            for (BlockPos pos : positions) {
                data.blockToNetwork.put(pos, id);
                data.chunkMembers.computeIfAbsent(chunkKey(pos), k -> new HashSet<>()).add(pos);
            }
        }

        return data;
    }
}
