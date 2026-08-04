package net.Tetrachlorosilane.createstorageextended.network;

import com.mojang.logging.LogUtils;
import net.Tetrachlorosilane.createstorageextended.Config;
import net.Tetrachlorosilane.createstorageextended.mixin.ChunkMapAccessor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.*;

/**
 * Server-side singleton that manages the lifecycle of storage networks.
 * <p>
 * {@code Level.setBlock} does not touch the topology directly: it only records
 * changed positions via {@link #markChanged}. All network operations (joins,
 * merges, splits, cleanup) are performed in a single pass at the next server
 * tick via {@link #onServerTick}. This makes the outcome independent of the
 * order in which a bulk placement was processed, and amortises the cost of a
 * batch of changes into one traversal over the affected area.
 * <p>
 * {@link #startRebuild} performs a full world rebuild: it clears the persisted
 * topology and rescans every currently-loaded chunk, re-converging the network
 * blocks found there. Unloaded chunks are not force-loaded; their network
 * blocks are re-registered incrementally by {@link #registerComponent} when
 * the chunk loads.
 */
public class StorageNetworkManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final StorageNetworkManager INSTANCE = new StorageNetworkManager();

    /** Tag shared with Create: Storage marking blocks that participate in storage networks. */
    private static final TagKey<Block> STORAGE_NETWORK_BLOCK_TAG =
            BlockTags.create(ResourceLocation.fromNamespaceAndPath("fxntstorage", "storage_network_block"));

    /** Chunks scanned per tick during a full rebuild (keeps the server responsive). */
    private static final int REBUILD_CHUNKS_PER_TICK = 64;

    /** Cached direction array - {@code Direction.values()} allocates a fresh array on every call. */
    private static final Direction[] DIRS = Direction.values();

    private final Map<ServerLevel, StorageNetworkData> dimensionData = new WeakHashMap<>();
    /** Positions whose block-state changed since the last settle pass. */
    private final Map<ServerLevel, Set<BlockPos>> pendingChanges = new WeakHashMap<>();

    @Nullable
    private RebuildTask rebuildTask;

    private StorageNetworkManager() {}

    public static StorageNetworkManager getInstance() {
        return INSTANCE;
    }

    private StorageNetworkData getData(ServerLevel level) {
        return dimensionData.computeIfAbsent(level, StorageNetworkData::get);
    }

    private Set<BlockPos> getPending(ServerLevel level) {
        return pendingChanges.computeIfAbsent(level, k -> new HashSet<>());
    }

    public void invalidateDimension(ServerLevel level) {
        dimensionData.remove(level);
        pendingChanges.remove(level);
        if (rebuildTask != null && rebuildTask.level == level) {
            rebuildTask = null;
        }
    }

    // ========== Change Recording (from Level.setBlock) ==========

    /**
     * Records that the block at {@code pos} changed. No topology mutation
     * happens here; the actual network update runs at the next tick.
     */
    public void markChanged(ServerLevel level, BlockPos pos) {
        getPending(level).add(pos);
    }

    // ========== Network Registration (chunk load path) ==========

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

    // ========== Tick Pass (incremental changes) ==========

    /**
     * Whether any dimension has recorded changes to settle, or a full rebuild
     * is running. Lets the tick handler skip the per-dimension pass entirely
     * when there is nothing to do.
     */
    public boolean hasPendingWork() {
        return !pendingChanges.isEmpty() || rebuildTask != null;
    }

    /**
     * Runs once per server tick. Resolves every change recorded since the
     * previous pass, and advances a running full rebuild.
     */
    public void onServerTick(ServerLevel level) {
        Set<BlockPos> changed = pendingChanges.remove(level);
        if (changed != null && !changed.isEmpty()) {
            resolveChanges(level, changed);
        }
        tickRebuild();
    }

    /**
     * Resolves a batch of changed positions against the final world state:
     * <ol>
     *   <li>positions that are no longer network blocks are removed;</li>
     *   <li>each affected connected component is forced to a single network
     *       id (registering unregistered members and merging split ids);</li>
     *   <li>networks whose members now span several physical components are
     *       split;</li>
     *   <li>empty networks are cleaned up.</li>
     * </ol>
     * The result does not depend on the order of the underlying {@code setBlock}
     * calls. Cost is O(changed area + affected networks), not O(changes).
     */
    private void resolveChanges(ServerLevel level, Set<BlockPos> changed) {
        StorageNetworkData data = getData(level);

        // 1. Removals: positions that are no longer network blocks.
        for (BlockPos pos : changed) {
            if (!isNetworkBlock(level, pos)) {
                data.removeFromNetwork(pos);
            }
        }

        // 2. Starting points: changed network blocks plus neighbours of
        //    removed positions (their components may need splitting).
        Set<BlockPos> starts = new HashSet<>();
        for (BlockPos pos : changed) {
            if (isNetworkBlock(level, pos)) {
                starts.add(pos);
            } else {
                for (Direction dir : DIRS) {
                    BlockPos neighbor = pos.relative(dir);
                    if (isNetworkBlock(level, neighbor)) {
                        starts.add(neighbor);
                    }
                }
            }
        }
        if (starts.isEmpty()) {
            data.cleanupEmptyNetworks();
            return;
        }

        // 3. Component convergence: every physical component becomes one
        //    network id; unregistered members are registered.
        Set<UUID> touchedNetworks = new HashSet<>();
        Set<Long> visited = new HashSet<>();
        for (BlockPos start : starts) {
            long startEnc = BlockPosEncoding.encode(start); if (!visited.add(startEnc)) continue;

            Set<Long> component = new HashSet<>();
            Set<UUID> componentIds = new LinkedHashSet<>();
            bfsComponent(level, startEnc, component, componentIds, data);
            visited.addAll(component);

            if (componentIds.isEmpty()) {
                UUID newId = data.createNetwork();
                for (long encodedPos : component) {
                    BlockPos pos = BlockPosEncoding.decode(encodedPos);
                    data.addToNetwork(newId, pos);
                    updateComponentId(level, pos, newId);
                }
                touchedNetworks.add(newId);
                if (Config.debugLogging) LOGGER.debug("Settle: registered {} members as new network {} at {}",
                        component.size(), newId, start);
            } else {
                UUID targetId = componentIds.iterator().next();
                for (UUID otherId : componentIds) {
                    if (otherId.equals(targetId)) continue;
                    Set<BlockPos> merged = data.mergeNetworks(otherId, targetId);
                    for (BlockPos pos : merged) {
                        updateComponentId(level, pos, targetId);
                    }
                    if (Config.debugLogging) LOGGER.debug("Settle: merged network {} into {} at {}",
                            otherId, targetId, start);
                }
                for (long encodedPos : component) {
                    BlockPos pos = BlockPosEncoding.decode(encodedPos);
                    UUID current = data.getNetworkId(pos);
                    if (current == null || !current.equals(targetId)) {
                        data.addToNetwork(targetId, pos);
                        updateComponentId(level, pos, targetId);
                    } else {
                        // Already registered to the target network: make sure
                        // the block entity's id matches (covers replacements
                        // where the new BE starts with a null id).
                        BlockEntity be = level.getBlockEntity(pos);
                        if (be instanceof INetworkComponent c
                                && !targetId.equals(c.getStorageNetworkId())) {
                            c.setStorageNetworkId(targetId);
                            be.setChanged();
                        }
                    }
                }
                touchedNetworks.add(targetId);
            }
        }

        // 4. Splits: any touched network whose members now occupy more than
        //    one physical component is split into one network per component.
        for (UUID networkId : touchedNetworks) {
            splitIfDisconnected(level, data, networkId);
        }

        // 5. Cleanup empty networks (e.g. after removals).
        data.cleanupEmptyNetworks();
    }

    /**
     * If the members of {@code networkId} are physically disconnected, keep the
     * first component on the original id and assign a fresh id to every other
     * component.
     */
    private static void splitIfDisconnected(ServerLevel level, StorageNetworkData data, UUID networkId) {
        Set<BlockPos> members = new HashSet<>(data.getNetworkMembers(networkId));
        if (members.size() <= 1) return;

        // Drop ghost members: loaded positions that are no longer network blocks.
        members.removeIf(pos -> isGhost(level, pos));
        if (members.size() <= 1) return;

        // Encode members once into longs; physical grouping runs entirely on longs.
        Set<Long> encodedMembers = new HashSet<>(members.size());
        for (BlockPos pos : members) {
            encodedMembers.add(BlockPosEncoding.encode(pos));
        }

        List<Set<Long>> groups = findPhysicalGroups(level, encodedMembers);
        if (groups.size() <= 1) return;

        for (int i = 1; i < groups.size(); i++) {
            Set<Long> group = groups.get(i);
            UUID newId = data.createNetwork();
            for (long encodedPos : group) {
                BlockPos pos = BlockPosEncoding.decode(encodedPos);
                data.removeFromNetwork(pos);
                data.addToNetwork(newId, pos);
                updateComponentId(level, pos, newId);
            }
            if (Config.debugLogging) LOGGER.debug("Settle: split {} members off network {} as new network {}",
                    group.size(), networkId, newId);
        }
    }

    // ========== Full Rebuild (command) ==========

    /**
     * Clears the whole persisted topology for the dimension and rescans every
     * currently-loaded chunk, rebuilding all networks from the real world
     * state. The scan is spread over several ticks so large worlds do not lag.
     * <p>
     * Chunks that are not loaded when the rebuild runs are intentionally not
     * force-loaded; their network blocks are re-registered incrementally by
     * {@link #registerComponent} as the chunks load.
     */
    public void startRebuild(ServerLevel level, CommandSourceStack source) {
        if (rebuildTask != null) {
            source.sendFailure(Component.literal("A storage network rebuild is already in progress"));
            return;
        }

        // 1. Drop all existing topology.
        getData(level).clear();

        // 2. Queue every currently-loaded chunk for scanning.
        List<LevelChunk> chunks = new ArrayList<>();
        ServerChunkCache cache = level.getChunkSource();
        ChunkMap chunkMap = cache.chunkMap;
        for (ChunkHolder holder : ((ChunkMapAccessor) chunkMap).createstorageextended$getChunks()) {
            LevelChunk chunk = holder.getTickingChunk();
            if (chunk != null) {
                chunks.add(chunk);
            }
        }

        rebuildTask = new RebuildTask(level, chunks);
        source.sendSuccess(() -> Component.literal(
                "Rebuilding storage networks across " + chunks.size()
                        + " loaded chunks; unloaded chunks are re-registered when they load..."), true);
    }

    /** Advances the running rebuild by one batch of chunks. */
    private void tickRebuild() {
        RebuildTask task = rebuildTask;
        if (task == null) return;

        int budget = REBUILD_CHUNKS_PER_TICK;
        while (budget-- > 0 && !task.pendingChunks.isEmpty()) {
            scanChunk(task, task.pendingChunks.poll());
            task.scannedChunks++;
        }

        if (!task.pendingChunks.isEmpty()) return;

        // All queued chunks scanned: converge the collected network blocks.
        try {
            resolveChanges(task.level, task.networkBlocks);
            if (Config.debugLogging) LOGGER.debug("Rebuild complete: {} network blocks in {} chunks",
                    task.networkBlocks.size(), task.scannedChunks);
        } finally {
            rebuildTask = null;
        }
    }

    private void scanChunk(RebuildTask task, LevelChunk chunk) {
        try {
            for (BlockPos pos : chunk.getBlockEntitiesPos()) {
                if (task.level.getBlockState(pos).is(STORAGE_NETWORK_BLOCK_TAG)) {
                    task.networkBlocks.add(pos.immutable());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Create: Storage Extended - failed to scan chunk {} during rebuild", chunk.getPos(), e);
        }
    }

    /** State of an in-progress full rebuild. */
    private static final class RebuildTask {
        final ServerLevel level;
        final Queue<LevelChunk> pendingChunks;
        final Set<BlockPos> networkBlocks = new HashSet<>();
        int scannedChunks;

        RebuildTask(ServerLevel level, List<LevelChunk> chunks) {
            this.level = level;
            this.pendingChunks = new ArrayDeque<>(chunks);        }
    }

    // ========== Chunk Load Cleanup ==========

    /**
     * Called when a chunk loads. Cleans up persisted members inside that chunk
     * whose position no longer holds a network block, so members stranded in
     * unloaded chunks (e.g. after a large-scale block move) are eventually
     * removed without ever force-loading their chunk.
     */
    public void onChunkLoad(ServerLevel level, int chunkX, int chunkZ) {
        getData(level).cleanupGhostsInChunk(level, chunkX, chunkZ, STORAGE_NETWORK_BLOCK_TAG);
    }

    // ========== Helpers ==========

    private static void bfsComponent(ServerLevel level, long start, Set<Long> component,
                                     Set<UUID> componentIds, StorageNetworkData data) {
        Queue<Long> queue = new ArrayDeque<>();
        component.add(start);
        queue.add(start);

        while (!queue.isEmpty()) {
            long current = queue.poll();
            UUID id = data.getNetworkId(BlockPosEncoding.decode(current));
            if (id != null) componentIds.add(id);

            int cx = BlockPosEncoding.x(current), cy = BlockPosEncoding.y(current), cz = BlockPosEncoding.z(current);
            for (Direction dir : DIRS) {
                long neighbor = BlockPosEncoding.encode(cx + dir.getStepX(), cy + dir.getStepY(), cz + dir.getStepZ());
                if (component.contains(neighbor)) continue;
                if (!isNetworkBlock(level, neighbor)) continue;
                component.add(neighbor);
                queue.add(neighbor);
            }
        }
    }

    /** Physical connectivity grouping over the given members. Runs entirely on encoded longs. */
    private static List<Set<Long>> findPhysicalGroups(ServerLevel level, Set<Long> members) {
        Set<Long> remaining = new HashSet<>(members);
        List<Set<Long>> groups = new ArrayList<>();

        while (!remaining.isEmpty()) {
            long start = remaining.iterator().next();
            remaining.remove(start);
            Set<Long> group = new HashSet<>();
            Queue<Long> queue = new ArrayDeque<>();
            group.add(start);
            queue.add(start);

            while (!queue.isEmpty()) {
                long current = queue.poll();
                int cx = BlockPosEncoding.x(current), cy = BlockPosEncoding.y(current), cz = BlockPosEncoding.z(current);
                for (Direction dir : DIRS) {
                    long neighbor = BlockPosEncoding.encode(cx + dir.getStepX(), cy + dir.getStepY(), cz + dir.getStepZ());
                    // Member-set first (in-memory), world tag check second (only for candidates).
                    if (remaining.contains(neighbor) && isNetworkBlock(level, neighbor)) {
                        remaining.remove(neighbor);
                        group.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }
            groups.add(group);
        }
        return groups;
    }

    /**
     * Whether the position currently holds a network block.
     * <p>
     * Gated on {@code level.isLoaded} to avoid triggering chunk loads during
     * traversals ({@code Level.getBlockState} would force-load unloaded
     * chunks). Members in unloaded chunks are cleaned up by
     * {@link #onChunkLoad} when their chunk loads.
     */
    private static boolean isNetworkBlock(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        return level.getBlockState(pos).is(STORAGE_NETWORK_BLOCK_TAG);
    }

    /** {@link #isNetworkBlock(Level, BlockPos)} over an encoded coordinate. */
    private static boolean isNetworkBlock(Level level, long encoded) {
        return isNetworkBlock(level, BlockPosEncoding.decode(encoded));
    }

    /** A member whose loaded position no longer holds a network block (stale entry). */
    private static boolean isGhost(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        return !level.getBlockState(pos).is(STORAGE_NETWORK_BLOCK_TAG);
    }

    private static void updateComponentId(Level level, BlockPos pos, UUID newId) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof INetworkComponent component) {
            component.setStorageNetworkId(newId);
            be.setChanged();
        }
    }

    // ========== Query ==========

    public Set<BlockPos> getNetworkMembers(ServerLevel level, UUID networkId) {
        return getData(level).getNetworkMembers(networkId);
    }

}
