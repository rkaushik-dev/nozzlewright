package io.github.rkaushikdev.nozzlewright.telemetry;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import io.github.rkaushikdev.nozzlewright.settings.LogFormat;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;

/**
 * Setting the log up for one process.
 *
 * <p>One setting picks the audience: {@code console} for a technician tailing a terminal, {@code
 * json} for the plant collector. Same events either way. Configuring again replaces the previous
 * set up, so a command line that reads its level late can call it twice.
 */
public final class Logs {

    private static final AtomicBoolean CONFIGURED = new AtomicBoolean();

    private Logs() {}

    /** Write events at {@code level} and above to standard error, in {@code format}. */
    public static void configure(String level, LogFormat format) {
        configure(level, format, System.err);
    }

    /**
     * Write events at {@code level} and above to {@code target}, in {@code format}.
     *
     * <p>The level is one of DEBUG, INFO, WARNING, ERROR or CRITICAL in any case; anything else
     * means INFO. The target is never closed by the log, so standard error survives being
     * configured twice.
     */
    public static void configure(String level, LogFormat format, OutputStream target) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.detachAndStopAllAppenders();

        EventLayout layout = new EventLayout(format);
        layout.setContext(context);
        layout.start();
        LayoutWrappingEncoder<ILoggingEvent> encoder = new LayoutWrappingEncoder<>();
        encoder.setContext(context);
        encoder.setLayout(layout);
        encoder.start();

        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setContext(context);
        appender.setName("nozzlewright");
        appender.setEncoder(encoder);
        appender.setImmediateFlush(true);
        appender.setOutputStream(new KeptOpen(target));
        appender.start();

        root.addAppender(appender);
        root.setLevel(level(level));
        CONFIGURED.set(true);
    }

    /** True once {@link #configure} has run in this process. */
    public static boolean isConfigured() {
        return CONFIGURED.get();
    }

    /** The level the root logger is at now. */
    public static String rootLevel() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        return context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel().toString();
    }

    static Level level(String name) {
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "DEBUG" -> Level.DEBUG;
            case "WARNING", "WARN" -> Level.WARN;
            case "ERROR", "CRITICAL" -> Level.ERROR;
            default -> Level.INFO;
        };
    }

    /** A stream the appender may close without closing what it wraps. */
    private static final class KeptOpen extends FilterOutputStream {
        KeptOpen(OutputStream target) {
            super(target);
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            out.write(data, offset, length);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
