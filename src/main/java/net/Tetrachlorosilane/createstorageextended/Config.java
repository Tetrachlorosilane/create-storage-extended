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

    /**
     * When {@code true}, storage network operations emit detailed diagnostic
     * log lines (network creation, joins, merges, splits and id corrections).
     * When {@code false} (default) these log calls are skipped entirely, so no
     * diagnostics are produced regardless of the Log4j configuration.
     * <p>
     * Note: the messages are logged at DEBUG level, so the mod's Log4j
     * configuration must also allow DEBUG output for them to appear.
     */
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING = BUILDER
            .comment("Enable detailed debug logging for storage network operations.",
                    "The mod's log level must also be set to DEBUG (standard Log4j configuration) for the messages to appear.",
                    "Default: false")
            .define("debugLogging", false);

    static final ModConfigSpec SPEC = BUILDER.build();

    /** Current value of {@link #DEBUG_LOGGING}, refreshed on config load and reload. */
    public static boolean debugLogging;

    static void onLoad(final ModConfigEvent event) {
        debugLogging = DEBUG_LOGGING.get();
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(Config::onLoad);
    }
}
