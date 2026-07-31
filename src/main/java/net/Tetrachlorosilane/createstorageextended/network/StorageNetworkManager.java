package net.Tetrachlorosilane.createstorageextended.network;

import com.mojang.logging.LogUtils;
import net.Tetrachlorosilane.createstorageextended.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
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

    @Nullable
    public UUID onBlockPlaced(Level level, BlockPos pos, @Nullable UUID existingId) {
        if (!(level instanceof ServerLevel serverLevel)) return null;

        StorageNetworkData data = getData(serverLevel);
        Set<UUID> adjacentIds = data.findAllAdjacentNetworks(pos);

        UUID targetId;
        if (adjacentIds.isEmpty()) {
            if (existingId != null) {
                data.createNetwork(existingId);
                targetId = existingId;
            } else {
                targetId = data.createNetwork();
            }
            if (Config.debugLogging) LOGGER.debug("Created new network {} for block at {}", targetId, pos);
        } else if (adjacentIds.size() == 1) {
            targetId = adjacentIds.iterator().next();
            if (Config.debugLogging) LOGGER.debug("Block at {} joining existing network {}", pos, targetId);
        } else {
            // Multiple adjacent networks: merge them all into the first one
            Iterator<UUID> iter = adjacentIds.iterator();
            targetId = iter.next();
            while (iter.hasNext()) {
                UUID otherId = iter.next();
                if (!otherId.equals(targetId)) {
                    Set<BlockPos> mergedMembers = data.mergeNetworks(otherId, targetId);
                    for (BlockPos memberPos : mergedMembers) {
                        updateComponentId(level, memberPos, targetId);
                    }
                    if (Config.debugLogging) LOGGER.debug("Merged network {} ({} members) into {} at block placement {}",
                            otherId, mergedMembers.size(), targetId, pos);
                }
            }
        }

        data.addToNetwork(targetId, pos);
        return targetId;
    }

    // ========== Block Removal ==========

    public void onBlockRemoved(Level level, BlockPos pos, @Nullable UUID networkId) {
        if (!(level instanceof ServerLevel serverLevel) || networkId == null) return;

        StorageNetworkData data = getData(serverLevel);

        Set<BlockPos> previousMembers = new HashSet<>(data.getNetworkMembers(networkId));
        data.removeFromNetwork(pos);
        previousMembers.remove(pos);

        if (previousMembers.isEmpty()) {
            data.deleteNetwork(networkId);
            if (Config.debugLogging) LOGGER.debug("Network {} deleted after removing last block at {}", networkId, pos);
            return;
        }

        List<Set<BlockPos>> groups = findConnectedGroups(previousMembers);

        if (groups.size() == 1) {
            return;
        }

        // Multiple groups -> keep the first group with the old networkId,
        // create new networks for the rest
        for (int i = 1; i < groups.size(); i++) {
            Set<BlockPos> group = groups.get(i);
            UUID newId = data.createNetwork();
            for (BlockPos memberPos : group) {
                data.removeFromNetwork(memberPos);
                data.addToNetwork(newId, memberPos);
                updateComponentId(level, memberPos, newId);
            }
            if (Config.debugLogging) LOGGER.debug("Split off new network {} with {} members from network {}",
                    newId, group.size(), networkId);
        }
    }

    // ========== Network Registration ==========

    /**
     * Register a component with the persisted network data.
     * <p>
     * {@link StorageNetworkData} is the authoritative source of network membership.
     * If this position already exists in the SavedData (e.g. after a merge or split
     * while this chunk was unloaded), the BE's stored UUID is treated as stale and
     * overwritten with the SavedData's UUID. The BE's NBT UUID serves only as a
     * migration hint when the position is not yet in SavedData.
     */
    public void registerComponent(ServerLevel level, BlockPos pos, UUID networkIdFromBE) {
        StorageNetworkData data = getData(level);
        UUID savedId = data.getNetworkId(pos);

        if (savedId != null) {
            // SavedData already has this position - it is authoritative.
            if (!savedId.equals(networkIdFromBE)) {
                if (Config.debugLogging) LOGGER.debug("BE at {} has stale networkId {}; correcting to persisted {}",
                        pos, networkIdFromBE, savedId);
                updateComponentId(level, pos, savedId);
            }
            return;
        }

        // Position not in SavedData yet - register with the BE's UUID.
        data.createNetwork(networkIdFromBE);
        data.addToNetwork(networkIdFromBE, pos);

        // Check if this registration bridges adjacent networks that should be merged.
        Set<UUID> adjacentIds = data.findAllAdjacentNetworks(pos);
        for (UUID adjId : adjacentIds) {
            if (!adjId.equals(networkIdFromBE)) {
                Set<BlockPos> mergedMembers = data.mergeNetworks(adjId, networkIdFromBE);
                for (BlockPos memberPos : mergedMembers) {
                    updateComponentId(level, memberPos, networkIdFromBE);
                }
                if (Config.debugLogging) LOGGER.debug("registerComponent at {} merged adjacent network {} ({} members) into {}",
                        pos, adjId, mergedMembers.size(), networkIdFromBE);
            }
        }
    }

    private void updateComponentId(Level level, BlockPos pos, UUID newId) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof INetworkComponent component) {
            component.setStorageNetworkId(newId);
            be.setChanged();
        }
    }

    // ========== BFS Utilities ==========

    private static Set<BlockPos> bfsConnected(BlockPos startPos, Set<BlockPos> candidates) {
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

    private static List<Set<BlockPos>> findConnectedGroups(Set<BlockPos> positions) {
        List<Set<BlockPos>> groups = new ArrayList<>();
        Set<BlockPos> remaining = new HashSet<>(positions);

        while (!remaining.isEmpty()) {
            BlockPos start = remaining.iterator().next();
            Set<BlockPos> group = bfsConnected(start, remaining);
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
