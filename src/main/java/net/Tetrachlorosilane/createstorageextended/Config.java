package net.Tetrachlorosilane.createstorageextended;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Configuration for Create: Storage Extended.
 */
@EventBusSubscriber(modid = CreateStorageExtended.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /**
     * Whether to log detailed network operations (place/break/merge/split).
     */
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING = BUILDER
            .comment("Enable detailed debug logging for storage network operations.")
            .define("debugLogging", false);

    /**
     * Maximum BFS range for network discovery during event-driven updates (split detection).
     * Default matches the original fxntstorage value.
     */
    public static final ModConfigSpec.IntValue NETWORK_SEARCH_RANGE = BUILDER
            .comment("Maximum BFS range for network component discovery during split operations.")
            .defineInRange("networkSearchRange", 32, 8, 128);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static boolean debugLogging;
    public static int networkSearchRange;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        debugLogging = DEBUG_LOGGING.get();
        networkSearchRange = NETWORK_SEARCH_RANGE.get();
    }
}
