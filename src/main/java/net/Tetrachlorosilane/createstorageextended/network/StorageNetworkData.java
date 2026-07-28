package net.Tetrachlorosilane.createstorageextended.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * World-level persisted data that stores all storage network topologies.
 * <p>
 * Each network is identified by a unique {@link UUID}.
 * The data maps network UUID -> {@link NetworkState} (set of member BlockPos),
 * and also maintains a reverse lookup BlockPos -> network UUID for O(1) access.
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

    // ========== SavedData factory ==========

    public static StorageNetworkData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(StorageNetworkData::new, StorageNetworkData::load, null),
                DATA_NAME
        );
    }

    // ========== Network CRUD ==========

    /**
     * Create a new network with a fresh UUID and no members.
     */
    public UUID createNetwork() {
        UUID id = UUID.randomUUID();
        networks.put(id, new LinkedHashSet<>());
        setDirty();
        return id;
    }

    /**
     * Create a network with a specific UUID (used when restoring from NBT).
     */
    public void createNetwork(UUID id) {
        networks.putIfAbsent(id, new LinkedHashSet<>());
        setDirty();
    }

    /**
     * Add a block position to an existing network.
     */
    public void addToNetwork(UUID networkId, BlockPos pos) {
        // Remove from any existing network first
        UUID oldNetwork = blockToNetwork.get(pos);
        if (oldNetwork != null && oldNetwork.equals(networkId)) return; // already in this network

        removeFromNetwork(pos);

        networks.computeIfAbsent(networkId, k -> new LinkedHashSet<>()).add(pos);
        blockToNetwork.put(pos, networkId);
        setDirty();
    }

    /**
     * Remove a block from its network. If the network becomes empty, it is NOT deleted
     * (empty networks can exist temporarily during merges).
     */
    public void removeFromNetwork(BlockPos pos) {
        UUID networkId = blockToNetwork.remove(pos);
        if (networkId != null) {
            Set<BlockPos> members = networks.get(networkId);
            if (members != null) {
                members.remove(pos);
            }
            setDirty();
        }
    }

    /**
     * Delete an entire network and remove all its members from the reverse lookup.
     */
    public void deleteNetwork(UUID networkId) {
        Set<BlockPos> members = networks.remove(networkId);
        if (members != null) {
            for (BlockPos pos : members) {
                blockToNetwork.remove(pos);
            }
            setDirty();
        }
    }

    /**
     * Move all members from {@code sourceId} into {@code targetId}, then delete the source.
     */
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
        return members != null ? Collections.unmodifiableSet(members) : Collections.emptySet();
    }

    @Nullable
    public UUID findAdjacentNetwork(ServerLevel level, BlockPos pos) {
        for (var dir : net.minecraft.core.Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            UUID id = blockToNetwork.get(neighbor);
            if (id != null) return id;
        }
        return null;
    }

    /**
     * Return all networks that are adjacent to the given position.
     */
    public Set<UUID> findAllAdjacentNetworks(ServerLevel level, BlockPos pos) {
        Set<UUID> result = new LinkedHashSet<>();
        for (var dir : net.minecraft.core.Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            UUID id = blockToNetwork.get(neighbor);
            if (id != null) result.add(id);
        }
        return result;
    }

    public Map<UUID, Set<BlockPos>> getAllNetworks() {
        return Collections.unmodifiableMap(networks);
    }

    public boolean isNetworkComponent(BlockPos pos) {
        return blockToNetwork.containsKey(pos);
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
                CompoundTag posTag = new CompoundTag();
                posTag.putInt("X", pos.getX());
                posTag.putInt("Y", pos.getY());
                posTag.putInt("Z", pos.getZ());
                posList.add(posTag);
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
            ListTag posList = networkTag.getList(KEY_POSITIONS, Tag.TAG_COMPOUND);
            for (int j = 0; j < posList.size(); j++) {
                CompoundTag posTag = posList.getCompound(j);
                positions.add(new BlockPos(
                        posTag.getInt("X"),
                        posTag.getInt("Y"),
                        posTag.getInt("Z")
                ));
            }

            data.networks.put(id, positions);
            for (BlockPos pos : positions) {
                data.blockToNetwork.put(pos, id);
            }
        }

        return data;
    }
}
