package net.Tetrachlorosilane.createstorageextended.network;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.*;

/**
 * Server-side singleton that manages the lifecycle of storage networks.
 */
public class StorageNetworkManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final StorageNetworkManager INSTANCE = new StorageNetworkManager();

    private final Map<ServerLevel, StorageNetworkData> dimensionData = new WeakHashMap<>();

    private StorageNetworkManager() {}

    public static StorageNetworkManager getInstance() {
        return INSTANCE;
    }

    private StorageNetworkData getData(ServerLevel level) {
        return dimensionData.computeIfAbsent(level, StorageNetworkData::get);
    }

    public void invalidateDimension(ServerLevel level) {
        dimensionData.remove(level);
    }

    // ========== Block Placement ==========

    /**
     * Called when a network-capable block is placed in the world.
     * @return the UUID of the network the block was placed into, or null
     */
    @Nullable
    public UUID onBlockPlaced(Level level, BlockPos pos, BlockState state, @Nullable UUID existingId) {
        if (!(level instanceof ServerLevel serverLevel)) return null;

        StorageNetworkData data = getData(serverLevel);
        Set<UUID> adjacentIds = data.findAllAdjacentNetworks(serverLevel, pos);

        UUID targetId;
        if (adjacentIds.isEmpty()) {
            if (existingId != null) {
                data.createNetwork(existingId);
                targetId = existingId;
            } else {
                targetId = data.createNetwork();
            }
            LOGGER.debug("Created new network {} for block at {}", targetId, pos);
        } else if (adjacentIds.size() == 1) {
            targetId = adjacentIds.iterator().next();
            LOGGER.debug("Block at {} joining existing network {}", pos, targetId);
        } else {
            // Multiple adjacent networks: merge them all into the first one
            Iterator<UUID> iter = adjacentIds.iterator();
            targetId = iter.next();
            while (iter.hasNext()) {
                UUID otherId = iter.next();
                if (!otherId.equals(targetId)) {
                    Set<BlockPos> mergedMembers = data.mergeNetworks(otherId, targetId);
                    // Update all block entities in the merged source network to point to targetId
                    for (BlockPos memberPos : mergedMembers) {
                        updateComponentId(level, memberPos, targetId);
                    }
                    LOGGER.debug("Merged network {} ({} members) into {} at block placement {}",
                            otherId, mergedMembers.size(), targetId, pos);
                }
            }
        }

        data.addToNetwork(targetId, pos);
        return targetId;
    }

    // ========== Block Removal ==========

    /**
     * Called when a network-capable block is removed from the world.
     * <p>
     * The network topology does NOT depend on any specific block type
     * (e.g. a controller is not required for the network to exist — only
     * interfaces depend on controllers for item access).
     * <p>
     * Removal uses pure connectivity BFS: if splitting the remaining
     * members into connected components produces multiple groups, each
     * group becomes its own network.
     */
    @Nullable
    public UUID onBlockRemoved(Level level, BlockPos pos, @Nullable UUID networkId) {
        if (!(level instanceof ServerLevel serverLevel) || networkId == null) return null;

        StorageNetworkData data = getData(serverLevel);

        Set<BlockPos> previousMembers = new HashSet<>(data.getNetworkMembers(networkId));
        data.removeFromNetwork(pos);
        previousMembers.remove(pos);

        if (previousMembers.isEmpty()) {
            data.deleteNetwork(networkId);
            LOGGER.debug("Network {} deleted after removing last block at {}", networkId, pos);
            return null;
        }

        // Find connected groups among remaining members (pure connectivity, no controller dependency)
        List<Set<BlockPos>> groups = findConnectedGroups(level, previousMembers);

        if (groups.size() == 1) {
            // Network is still connected — nothing to split
            return networkId;
        }

        // Multiple groups → keep the first group with the old networkId,
        // create new networks for the rest
        Set<BlockPos> keepGroup = groups.get(0);
        // The keep group already has the old networkId in blockToNetwork
        // (they weren't removed from it)

        for (int i = 1; i < groups.size(); i++) {
            Set<BlockPos> group = groups.get(i);
            UUID newId = data.createNetwork();
            for (BlockPos memberPos : group) {
                data.removeFromNetwork(memberPos); // remove from old
                data.addToNetwork(newId, memberPos);
                updateComponentId(level, memberPos, newId);
            }
            LOGGER.debug("Split off new network {} with {} members from network {}",
                    newId, group.size(), networkId);
        }

        return networkId;
    }

    // ========== Network Registration ==========

    public void registerComponent(ServerLevel level, BlockPos pos, UUID networkId) {
        StorageNetworkData data = getData(level);
        data.createNetwork(networkId);
        data.addToNetwork(networkId, pos);
    }

    private void updateComponentId(Level level, BlockPos pos, UUID newId) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof INetworkComponent component) {
            component.setStorageNetworkId(newId);
            be.setChanged();
        }
    }

    // ========== BFS Utilities ==========

    private Set<BlockPos> bfsConnected(Level level, BlockPos startPos, Set<BlockPos> candidates) {
        Set<BlockPos> visited = new LinkedHashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        visited.add(startPos);
        queue.add(startPos);

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = current.relative(dir);
                if (candidates.contains(neighbor) && !visited.contains(neighbor)) {
                    visited.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }
        return visited;
    }

    private List<Set<BlockPos>> findConnectedGroups(Level level, Set<BlockPos> positions) {
        List<Set<BlockPos>> groups = new ArrayList<>();
        Set<BlockPos> remaining = new HashSet<>(positions);

        while (!remaining.isEmpty()) {
            BlockPos start = remaining.iterator().next();
            Set<BlockPos> group = bfsConnected(level, start, remaining);
            groups.add(group);
            remaining.removeAll(group);
        }
        return groups;
    }

    // ========== Query ==========

    public Set<BlockPos> getNetworkMembers(ServerLevel level, UUID networkId) {
        return getData(level).getNetworkMembers(networkId);
    }

    @Nullable
    public UUID getNetworkId(ServerLevel level, BlockPos pos) {
        return getData(level).getNetworkId(pos);
    }
}
