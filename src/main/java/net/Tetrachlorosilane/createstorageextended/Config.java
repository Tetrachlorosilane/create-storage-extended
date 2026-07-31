package net.Tetrachlorosilane.createstorageextended;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Configuration for Create: Storage Extended.
 * <p>
 * Config listeners are registered manually in {@link CreateStorageExtended}
 * to avoid the deprecated {@code @EventBusSubscriber(bus = MOD)}.
 */
public class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING = BUILDER
            .comment("Enable detailed debug logging for storage network operations.")
            .define("debugLogging", false);

    public static final ModConfigSpec.IntValue NETWORK_SEARCH_RANGE = BUILDER
            .comment("Maximum BFS range for network component discovery during split operations.")
            .defineInRange("networkSearchRange", 32, 8, 128);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static boolean debugLogging;
    public static int networkSearchRange;

    /**
     * Called by {@link CreateStorageExtended} via the mod event bus.
     */
    static void onLoad(final ModConfigEvent event) {
        debugLogging = DEBUG_LOGGING.get();
        networkSearchRange = NETWORK_SEARCH_RANGE.get();
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(Config::onLoad);
    }
}
