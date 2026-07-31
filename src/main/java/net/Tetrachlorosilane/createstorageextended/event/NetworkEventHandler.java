package net.Tetrachlorosilane.createstorageextended.event;

import com.mojang.logging.LogUtils;
import net.Tetrachlorosilane.createstorageextended.network.INetworkComponent;
import net.Tetrachlorosilane.createstorageextended.network.StorageNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Handles block place/break events for storage network components.
 * <p>
 * Supports both:
 * <ul>
 *   <li>Block entities implementing {@link INetworkComponent} (controllers, interfaces, storage boxes)</li>
 *   <li>Blocks with the {@code fxntstorage:storage_network_block} tag (e.g. Storage Trim)
 *       that act as network connectors but don't store items</li>
 * </ul>
 */
@EventBusSubscriber
public class NetworkEventHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Tag that marks blocks as storage network components in Create: Storage.
     * Used to identify network-connecting blocks (like Storage Trim) that don't implement INetworkComponent.
     */
    private static final TagKey<Block> STORAGE_NETWORK_BLOCK_TAG =
            BlockTags.create(ResourceLocation.fromNamespaceAndPath("fxntstorage", "storage_network_block"));

    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;

        BlockPos pos = event.getPos();
        BlockEntity be = serverLevel.getBlockEntity(pos);

        UUID existingId = null;
        boolean isNetworkBlock = false;

        if (be instanceof INetworkComponent component) {
            existingId = component.getStorageNetworkId();
            isNetworkBlock = true;
        } else if (event.getState().is(STORAGE_NETWORK_BLOCK_TAG)) {
            // Blocks like Storage Trim that connect networks but have no custom BE with INetworkComponent
            isNetworkBlock = true;
        }

        if (isNetworkBlock) {
            UUID newId = StorageNetworkManager.getInstance()
                    .onBlockPlaced(serverLevel, pos, existingId);

            if (newId != null && be instanceof INetworkComponent component) {
                component.setStorageNetworkId(newId);
                be.setChanged();
            }
            if (newId != null) {
                LOGGER.debug("Block at {} placed into network {}", pos, newId);
            }
        }
    }

    @SubscribeEvent
    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;

        BlockPos pos = event.getPos();
        UUID networkId = StorageNetworkManager.getInstance().getNetworkId(serverLevel, pos);

        if (networkId != null) {
            LOGGER.debug("Block at {} (network {}) being broken", pos, networkId);
            StorageNetworkManager.getInstance().onBlockRemoved(serverLevel, pos, networkId);
        }
    }
}

