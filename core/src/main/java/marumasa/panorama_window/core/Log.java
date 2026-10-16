package marumasa.panorama_window.core;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * ログの出力先。log4j はどのバージョンの Minecraft にも同梱されている。
 */
public final class Log {

    private static final Logger LOGGER = LogManager.getLogger("PanoramaWindow");

    private Log() {
    }

    public static void info(String message, Object... args) {
        LOGGER.info(message, args);
    }

    public static void warn(String message, Object... args) {
        LOGGER.warn(message, args);
    }

    public static void error(String message, Throwable throwable) {
        LOGGER.error(message, throwable);
    }
}
