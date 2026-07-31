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
            .comment("Enable detailed debug logging for storage network operations. "
                    + "Log level can also be controlled via standard Log4j configuration.")
            .define("debugLogging", false);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static boolean debugLogging;

    static void onLoad(final ModConfigEvent event) {
        debugLogging = DEBUG_LOGGING.get();
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(Config::onLoad);
    }
}
