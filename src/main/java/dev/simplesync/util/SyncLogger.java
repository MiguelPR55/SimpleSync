package dev.simplesync.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Unified logging utility for SimpleSync.
 * Automatically delegates to SLF4J Logger when available (inside Minecraft / Fabric),
 * or falls back to formatted System.out / System.err when running in standalone mode.
 */
public final class SyncLogger {

    private static final String LOGGER_NAME = "simplesync";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Object SLF4J_LOGGER;
    private static final java.lang.reflect.Method SLF4J_INFO;
    private static final java.lang.reflect.Method SLF4J_WARN;
    private static final java.lang.reflect.Method SLF4J_ERROR_ARGS;
    private static final java.lang.reflect.Method SLF4J_ERROR_THROWABLE;

    static {
        Object logger = null;
        java.lang.reflect.Method info = null;
        java.lang.reflect.Method warn = null;
        java.lang.reflect.Method errorArgs = null;
        java.lang.reflect.Method errorThrowable = null;

        try {
            Class<?> factoryClass = Class.forName("org.slf4j.LoggerFactory");
            java.lang.reflect.Method getLogger = factoryClass.getMethod("getLogger", String.class);
            logger = getLogger.invoke(null, LOGGER_NAME);

            Class<?> loggerClass = Class.forName("org.slf4j.Logger");
            info = loggerClass.getMethod("info", String.class, Object[].class);
            warn = loggerClass.getMethod("warn", String.class, Object[].class);
            errorArgs = loggerClass.getMethod("error", String.class, Object[].class);
            errorThrowable = loggerClass.getMethod("error", String.class, Throwable.class);
        } catch (Throwable ignored) {
            logger = null;
        }

        SLF4J_LOGGER = logger;
        SLF4J_INFO = info;
        SLF4J_WARN = warn;
        SLF4J_ERROR_ARGS = errorArgs;
        SLF4J_ERROR_THROWABLE = errorThrowable;
    }

    private SyncLogger() {}

    public static void info(String format, Object... args) {
        if (SLF4J_LOGGER != null && SLF4J_INFO != null) {
            try {
                SLF4J_INFO.invoke(SLF4J_LOGGER, format, args);
                return;
            } catch (Throwable ignored) {}
        }
        System.out.println(formatConsole("INFO", format, args));
    }

    public static void warn(String format, Object... args) {
        if (SLF4J_LOGGER != null && SLF4J_WARN != null) {
            try {
                SLF4J_WARN.invoke(SLF4J_LOGGER, format, args);
                return;
            } catch (Throwable ignored) {}
        }
        System.err.println(formatConsole("WARN", format, args));
    }

    public static void error(String format, Object... args) {
        if (SLF4J_LOGGER != null && SLF4J_ERROR_ARGS != null) {
            try {
                SLF4J_ERROR_ARGS.invoke(SLF4J_LOGGER, format, args);
                return;
            } catch (Throwable ignored) {}
        }
        System.err.println(formatConsole("ERROR", format, args));
    }

    public static void error(String message, Throwable t) {
        if (SLF4J_LOGGER != null && SLF4J_ERROR_THROWABLE != null) {
            try {
                SLF4J_ERROR_THROWABLE.invoke(SLF4J_LOGGER, message, t);
                return;
            } catch (Throwable ignored) {}
        }
        System.err.println(formatConsole("ERROR", message));
        if (t != null) {
            t.printStackTrace(System.err);
        }
    }

    public static void error(String format, Object arg, Throwable t) {
        if (SLF4J_LOGGER != null && SLF4J_ERROR_ARGS != null) {
            try {
                SLF4J_ERROR_ARGS.invoke(SLF4J_LOGGER, format, new Object[]{arg, t});
                return;
            } catch (Throwable ignored) {}
        }
        System.err.println(formatConsole("ERROR", format, arg));
        if (t != null) {
            t.printStackTrace(System.err);
        }
    }

    private static String formatConsole(String level, String format, Object... args) {
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        String formattedMessage = formatMessage(format, args);
        return "[" + timestamp + "] [SimpleSync/" + level + "] " + formattedMessage;
    }

    public static String formatMessage(String pattern, Object... args) {
        if (pattern == null) return "null";
        if (args == null || args.length == 0) return pattern;

        StringBuilder sb = new StringBuilder(pattern.length() + 32);
        int argIndex = 0;
        int lastPos = 0;

        while (argIndex < args.length) {
            int placeholderPos = pattern.indexOf("{}", lastPos);
            if (placeholderPos == -1) {
                break;
            }
            sb.append(pattern, lastPos, placeholderPos);
            Object arg = args[argIndex++];
            sb.append(arg != null ? arg.toString() : "null");
            lastPos = placeholderPos + 2;
        }

        sb.append(pattern.substring(lastPos));

        // If there is an unused trailing Throwable argument, handle its message
        if (argIndex < args.length && args[argIndex] instanceof Throwable t) {
            sb.append(" (").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(")");
        }

        return sb.toString();
    }
}
