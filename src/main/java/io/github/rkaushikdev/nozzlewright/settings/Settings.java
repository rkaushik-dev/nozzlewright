package io.github.rkaushikdev.nozzlewright.settings;

import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * The top level settings, assembled once at start up.
 *
 * <p>The handheld ships with a baked in default profile and reads overrides from the environment.
 * Everything a technician might need to change on a line without a rebuild lives here: timeouts,
 * the keepalive interval, how long to wait before a printer is declared unreachable. The top level
 * fields read from {@code NZW_SITE_ID}, {@code NZW_STATE_DIR}, {@code NZW_LOG_LEVEL} and {@code
 * NZW_LOG_FORMAT}; the nested ones from {@code NZW_LINK_*}, {@code NZW_SESSION_*} and {@code
 * NZW_NOTIFY_*}.
 *
 * @param siteId the site, slugged: trimmed, lower cased, spaces made dashes; letters, digits, dash
 *     and underscore only
 * @param stateDir where the site file, the registry and the database live
 * @param logLevel one of DEBUG, INFO, WARNING, ERROR, CRITICAL, upper cased
 * @param logFormat console lines or JSON
 * @param link how links are opened and held
 * @param session how the command channel behaves
 * @param notices where alarm notices go, {@code NZW_NOTIFY_*}
 */
@ConfigurationProperties(prefix = "nzw")
public record Settings(
        @DefaultValue("unnamed-site") String siteId,
        @DefaultValue("./local/state") Path stateDir,
        @DefaultValue("INFO") String logLevel,
        @DefaultValue("console") LogFormat logFormat,
        @DefaultValue LinkSettings link,
        @DefaultValue SessionSettings session,
        @Name("notify") @DefaultValue NotifySettings notices) {

    /** The site id a settings object holds until something names the site. */
    public static final String DEFAULT_SITE_ID = "unnamed-site";

    private static final Set<String> LEVELS = Set.of("DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL");

    /** Normalises the site id and the level, and checks both. */
    public Settings {
        siteId = slug(siteId);
        logLevel = level(logLevel);
        if (stateDir == null) {
            stateDir = Path.of("./local/state");
        }
        if (logFormat == null) {
            logFormat = LogFormat.CONSOLE;
        }
        if (link == null) {
            link = LinkSettings.defaults();
        }
        if (session == null) {
            session = SessionSettings.defaults();
        }
        if (notices == null) {
            notices = NotifySettings.defaults();
        }
    }

    /** Every default, with nothing read from the environment. */
    public static Settings defaults() {
        return builder().build();
    }

    /**
     * Settings from this process's environment.
     *
     * @throws InvalidSettings when a variable holds a value the controller cannot run with
     */
    public static Settings load() {
        return load(System.getenv());
    }

    /**
     * Settings from the given environment variables, defaults for everything they leave out.
     *
     * <p>Variables are matched the way a shell spells them, {@code NZW_LINK_TCP_PORT} for the link's
     * TCP port. A list, such as the reconnect backoff, is written comma separated.
     *
     * @throws InvalidSettings when a variable holds a value the controller cannot run with
     */
    public static Settings load(Map<String, String> environment) {
        Map<String, Object> variables = Map.copyOf(environment);
        // Spring maps NZW_LINK_TCP_PORT to nzw.link.tcp-port only for a source carrying the
        // system environment's name.
        Binder binder = new Binder(ConfigurationPropertySources.from(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables)));
        try {
            return binder.bindOrCreate("nzw", Bindable.of(Settings.class));
        } catch (BindException error) {
            Throwable cause = error;
            while (cause != null) {
                if (cause instanceof InvalidSettings invalid) {
                    throw invalid;
                }
                cause = cause.getCause();
            }
            throw new InvalidSettings(
                    error.getName().toString(),
                    "could not be read: " + error.getCause().getMessage());
        }
    }

    /** A builder starting from the defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** A builder starting from these values. */
    public Builder toBuilder() {
        return new Builder()
                .siteId(siteId)
                .stateDir(stateDir)
                .logLevel(logLevel)
                .logFormat(logFormat)
                .link(link)
                .session(session)
                .notices(notices);
    }

    private static String slug(String value) {
        String cleaned = (value == null ? DEFAULT_SITE_ID : value)
                .strip()
                .toLowerCase(Locale.ROOT)
                .replace(" ", "-");
        if (cleaned.isEmpty()) {
            throw new InvalidSettings("site_id", "site id must not be empty");
        }
        boolean clean = cleaned.codePoints().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '-' || ch == '_');
        if (!clean) {
            throw new InvalidSettings("site_id", "site id may only hold letters, digits, dash, underscore");
        }
        return cleaned;
    }

    private static String level(String value) {
        String upper = (value == null ? "INFO" : value).toUpperCase(Locale.ROOT);
        if (!LEVELS.contains(upper)) {
            List<String> allowed = List.copyOf(new TreeSet<>(LEVELS));
            throw new InvalidSettings("log_level", "log level must be one of " + Repr.of(allowed));
        }
        return upper;
    }

    /** Assembles a {@link Settings}, checked when it is built. */
    public static final class Builder {
        private String siteId = DEFAULT_SITE_ID;
        private Path stateDir = Path.of("./local/state");
        private String logLevel = "INFO";
        private LogFormat logFormat = LogFormat.CONSOLE;
        private LinkSettings link = LinkSettings.defaults();
        private SessionSettings session = SessionSettings.defaults();
        private NotifySettings notices = NotifySettings.defaults();

        private Builder() {}

        /** Sets the site id. */
        public Builder siteId(String value) {
            siteId = value;
            return this;
        }

        /** Sets the state directory. */
        public Builder stateDir(Path value) {
            stateDir = value;
            return this;
        }

        /** Sets the log level. */
        public Builder logLevel(String value) {
            logLevel = value;
            return this;
        }

        /** Sets the log format. */
        public Builder logFormat(LogFormat value) {
            logFormat = value;
            return this;
        }

        /** Sets the link settings. */
        public Builder link(LinkSettings value) {
            link = value;
            return this;
        }

        /** Sets the session settings. */
        public Builder session(SessionSettings value) {
            session = value;
            return this;
        }

        /** Sets the notify settings. */
        public Builder notices(NotifySettings value) {
            notices = value;
            return this;
        }

        /** The settings, checked. */
        public Settings build() {
            return new Settings(siteId, stateDir, logLevel, logFormat, link, session, notices);
        }
    }
}
