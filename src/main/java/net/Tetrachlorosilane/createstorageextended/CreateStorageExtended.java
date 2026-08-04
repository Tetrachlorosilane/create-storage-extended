package net.Tetrachlorosilane.createstorageextended;

import com.mojang.logging.LogUtils;
import net.Tetrachlorosilane.createstorageextended.network.StorageNetworkManager;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Create: Storage Extended - a server-side companion mod for Create: Storage (fxntstorage).
 */
@Mod(CreateStorageExtended.MOD_ID)
public class CreateStorageExtended {

    public static final String MOD_ID = "createstorageextended";
    private static final Logger LOGGER = LogUtils.getLogger();

    public CreateStorageExtended(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        Config.register(modEventBus);

        // Register ourselves on the Forge event bus
        NeoForge.EVENT_BUS.register(this);

        LOGGER.info("Create: Storage Extended - Persistent Storage Networks loaded.");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // Clean up dimension caches
        event.getServer().getAllLevels().forEach(level -> {
            if (level instanceof ServerLevel serverLevel) {
                StorageNetworkManager.getInstance().invalidateDimension(serverLevel);
            }
        });
        LOGGER.info("Create: Storage Extended - Network manager caches cleared.");
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        // Settle pass: re-converge connected components changed during this
        // tick so bulk placements cannot leave split networks behind.
        // Fast path: with no recorded changes and no rebuild running there is
        // nothing to do - avoid the per-dimension iteration entirely.
        StorageNetworkManager manager = StorageNetworkManager.getInstance();
        if (!manager.hasPendingWork()) return;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            manager.onServerTick(level);
        }
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        // Clean up persisted network members inside the newly loaded chunk
        // whose position no longer holds a network block (e.g. after a
        // large-scale block move stranded them in unloaded chunks).
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            ChunkPos chunkPos = event.getChunk().getPos();
            StorageNetworkManager.getInstance().onChunkLoad(serverLevel, chunkPos.x, chunkPos.z);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("createstorageextended")
                .then(Commands.literal("rebuildnetworks")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> {
                            ServerLevel level = ctx.getSource().getLevel();
                            StorageNetworkManager.getInstance().startRebuild(level, ctx.getSource());
                            return 1;
                        })));
    }
}
