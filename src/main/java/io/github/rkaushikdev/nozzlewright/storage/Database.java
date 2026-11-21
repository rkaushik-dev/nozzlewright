package io.github.rkaushikdev.nozzlewright.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/**
 * The gateway's database: an H2 file in the state directory, or an in memory one for tests.
 *
 * <p>Every URL goes through {@link #jdbcUrl}, which adds the settings the rest of the code relies
 * on: lower case names in the catalogue, and a five second wait on a locked row rather than an
 * immediate failure, so the poll writer and an API reader never trip over each other. The entity
 * mapping is checked against the tables the first time a unit of work is opened, not when the
 * database is opened, so a database can be opened, inspected and upgraded before it has a schema.
 */
public final class Database implements AutoCloseable {

    /** What H2 adds to a file database's name. */
    public static final String FILE_SUFFIX = ".mv.db";

    private static final AtomicInteger MEMORY = new AtomicInteger();

    private final String url;
    private final boolean memory;
    private final HikariDataSource dataSource;
    private volatile EntityManagerFactory factory;

    private Database(String url) {
        this.url = jdbcUrl(url);
        this.memory = url.startsWith("jdbc:h2:mem:");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(this.url);
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);
        config.setPoolName("nozzlewright-db");
        this.dataSource = new HikariDataSource(config);
    }

    /**
     * Open the database at a JDBC URL ({@code jdbc:h2:file:...} or {@code jdbc:h2:mem:...}).
     *
     * <p>For a file database the directory is made first.
     */
    public static Database open(String url) {
        if (url.startsWith("jdbc:h2:file:")) {
            ensureParent(Path.of(stripSettings(url.substring("jdbc:h2:file:".length()))));
        }
        return new Database(url);
    }

    /** Open the file database at {@code path}; H2 stores it as {@code path} plus {@value #FILE_SUFFIX}. */
    public static Database file(Path path) {
        return open(fileUrl(path));
    }

    /** A fresh, empty, private in memory database. */
    public static Database inMemory() {
        return open("jdbc:h2:mem:nozzlewright-" + MEMORY.incrementAndGet());
    }

    /** The JDBC URL of the file database at {@code path}. */
    public static String fileUrl(Path path) {
        return "jdbc:h2:file:" + path.toAbsolutePath().normalize();
    }

    /** {@code url} with the settings every connection needs, added once. */
    public static String jdbcUrl(String url) {
        StringBuilder full = new StringBuilder(url);
        Map<String, String> wanted = new java.util.LinkedHashMap<>();
        wanted.put("DATABASE_TO_LOWER", "TRUE");
        wanted.put("LOCK_TIMEOUT", "5000");
        if (url.startsWith("jdbc:h2:mem:")) {
            wanted.put("DB_CLOSE_DELAY", "-1");
        }
        String upper = url.toUpperCase(java.util.Locale.ROOT);
        wanted.forEach((key, value) -> {
            if (!upper.contains(";" + key + "=")) {
                full.append(';').append(key).append('=').append(value);
            }
        });
        return full.toString();
    }

    /** The JDBC URL, settings included. */
    public String url() {
        return url;
    }

    /** True for an in memory database. */
    public boolean inMemoryDatabase() {
        return memory;
    }

    /** The connection pool. */
    public DataSource dataSource() {
        return dataSource;
    }

    /**
     * Open a unit of work. The caller closes it.
     *
     * @throws jakarta.persistence.PersistenceException when the tables do not match the entities,
     *     which is what an un-upgraded database looks like
     */
    public UnitOfWork begin() {
        return new UnitOfWork(entityManagerFactory());
    }

    /** Run {@code work} in a unit of work that commits when it returns and rolls back when it throws. */
    public <T> T apply(Function<UnitOfWork, T> work) {
        try (UnitOfWork unit = begin()) {
            T result;
            try {
                result = work.apply(unit);
            } catch (RuntimeException error) {
                unit.rollback();
                throw error;
            }
            unit.commit();
            return result;
        }
    }

    /** Run {@code work} in a unit of work that commits when it returns and rolls back when it throws. */
    public void run(Consumer<UnitOfWork> work) {
        apply(unit -> {
            work.accept(unit);
            return null;
        });
    }

    /** Bring the schema up to the newest revision: the tables every test and a first run need. */
    public void createAll() {
        Schema.upgrade(this);
    }

    /** Close the pool; an in memory database is dropped with it. */
    @Override
    public void close() {
        EntityManagerFactory built = factory;
        if (built != null) {
            built.close();
        }
        if (memory && !dataSource.isClosed()) {
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            } catch (SQLException error) {
                // Already gone; closing the pool is all that is left to do.
                dataSource.close();
                return;
            }
        }
        dataSource.close();
    }

    private EntityManagerFactory entityManagerFactory() {
        EntityManagerFactory built = factory;
        if (built != null) {
            return built;
        }
        synchronized (this) {
            if (factory == null) {
                LocalContainerEntityManagerFactoryBean bean = new LocalContainerEntityManagerFactoryBean();
                bean.setDataSource(dataSource);
                bean.setPackagesToScan(Database.class.getPackageName());
                bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
                bean.setPersistenceUnitName("nozzlewright");
                bean.setJpaPropertyMap(Map.of(
                        "hibernate.hbm2ddl.auto", "validate",
                        "hibernate.column_ordering_strategy", "legacy",
                        "hibernate.jdbc.time_zone", "UTC"));
                bean.afterPropertiesSet();
                factory = bean.getObject();
            }
            return factory;
        }
    }

    private static String stripSettings(String path) {
        int semicolon = path.indexOf(';');
        return semicolon < 0 ? path : path.substring(0, semicolon);
    }

    private static void ensureParent(Path path) {
        Path parent = path.toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
