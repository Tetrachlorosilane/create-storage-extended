package net.Tetrachlorosilane.createstorageextended;

import com.mojang.logging.LogUtils;
import net.Tetrachlorosilane.createstorageextended.network.StorageNetworkManager;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

/**
 * Create: Storage Extended — a server-side companion mod for Create: Storage (fxntstorage).
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

        LOGGER.info("Create: Storage Extended — Persistent Storage Networks loaded.");
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        LOGGER.info("Create: Storage Extended — Server started. Network manager ready.");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // Clean up dimension caches
        event.getServer().getAllLevels().forEach(level -> {
            if (level instanceof ServerLevel serverLevel) {
                StorageNetworkManager.getInstance().invalidateDimension(serverLevel);
            }
        });
        LOGGER.info("Create: Storage Extended — Network manager caches cleared.");
    }
}
