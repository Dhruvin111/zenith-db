package org.javadb.config;

package org.javadb.config;

/**
 * ============================================================
 * ServerConfig — Network & Runtime Server Configuration
 * ============================================================
 *
 * Holds all server-level configuration: host, port, connection
 * limits, timeouts. Separate from DatabaseConfig which holds
 * storage-level constants.
 *
 * PostgreSQL parallel:
 * postgresql.conf settings:
 * listen_addresses = 'localhost'
 * port = 5432
 * max_connections = 100
 *
 * Design decisions:
 * - Implemented as a mutable configuration object (unlike
 * DatabaseConfig which is all-static). This allows creating
 * multiple server configurations for testing.
 * - Uses the Builder pattern for clean, readable construction:
 * ServerConfig config = ServerConfig.builder()
 * .host("localhost")
 * .port(5433)
 * .maxConnections(50)
 * .build();
 *
 * Java concepts used:
 * - Builder pattern (static nested Builder class)
 * - record-like immutability after construction
 * - Method chaining in Builder
 *
 * ============================================================
 */
public final class ServerConfig {

    // =========================================================
    // Configuration Fields — all final (immutable after build)
    // =========================================================

    /**
     * Host address the server listens on.
     * "localhost" = only local connections (safe default).
     * "0.0.0.0" = accept connections from all interfaces.
     *
     * PostgreSQL parallel: listen_addresses in postgresql.conf
     */
    private final String host;

    /**
     * TCP port number the server listens on.
     *
     * PostgreSQL default: 5432
     * We use 5433 to avoid conflicts with a running PostgreSQL instance.
     */
    private final int port;

    /**
     * Maximum number of simultaneous client connections.
     *
     * Each connection in Java 21 = 1 virtual thread.
     * Java 21 can handle tens of thousands of virtual threads.
     *
     * PostgreSQL default: max_connections = 100
     */
    private final int maxConnections;

    /**
     * Connection timeout — how long to wait for a client to
     * complete the handshake before dropping the connection (ms).
     *
     * PostgreSQL parallel: authentication_timeout = 60s
     */
    private final long connectionTimeoutMs;

    /**
     * Statement timeout — maximum time a single SQL statement
     * can run before being forcibly cancelled (ms).
     * 0 = no timeout (run forever).
     *
     * PostgreSQL parallel: statement_timeout = 0 (default)
     */
    private final long statementTimeoutMs;

    /**
     * Data directory — where all database files are stored.
     * Overrides DatabaseConfig.DEFAULT_DATA_DIRECTORY.
     */
    private final String dataDirectory;

    /**
     * WAL directory — where WAL segment files are stored.
     * Ideally on a separate disk for performance.
     */
    private final String walDirectory;

    /**
     * Whether to enable page checksums.
     * When true, every page read verifies its CRC32 checksum.
     * Detects silent data corruption (hardware/disk bugs).
     *
     * PostgreSQL parallel: data_checksums (initdb option)
     */
    private final boolean dataChecksums;

    /**
     * Whether to log all SQL statements (like pg's log_statement = 'all').
     * Extremely verbose — use only for debugging.
     */
    private final boolean logAllStatements;

    // =========================================================
    // Private Constructor — use Builder to construct
    // =========================================================

    private ServerConfig(Builder builder) {
        this.host = builder.host;
        this.port = builder.port;
        this.maxConnections = builder.maxConnections;
        this.connectionTimeoutMs = builder.connectionTimeoutMs;
        this.statementTimeoutMs = builder.statementTimeoutMs;
        this.dataDirectory = builder.dataDirectory;
        this.walDirectory = builder.walDirectory;
        this.dataChecksums = builder.dataChecksums;
        this.logAllStatements = builder.logAllStatements;
    }

    // =========================================================
    // Getters — read-only access to configuration values
    // =========================================================

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public long getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public long getStatementTimeoutMs() {
        return statementTimeoutMs;
    }

    public String getDataDirectory() {
        return dataDirectory;
    }

    public String getWalDirectory() {
        return walDirectory;
    }

    public boolean isDataChecksums() {
        return dataChecksums;
    }

    public boolean isLogAllStatements() {
        return logAllStatements;
    }

    // =========================================================
    // Static Factory — creates a Builder with default values
    // =========================================================

    /**
     * Creates a new Builder pre-filled with sensible defaults.
     * Caller overrides only what they need to change.
     *
     * Usage:
     * ServerConfig config = ServerConfig.builder()
     * .port(5433)
     * .build();
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a default ServerConfig without any customization.
     * Equivalent to ServerConfig.builder().build().
     *
     * Useful for tests and quick startup.
     */
    public static ServerConfig defaultConfig() {
        return new Builder().build();
    }

    // =========================================================
    // Builder — constructs ServerConfig via method chaining
    // =========================================================

    /**
     * Builder for ServerConfig.
     *
     * Java concept — Builder Pattern:
     * Solves the "telescoping constructor" problem where a class
     * has many optional parameters. Instead of:
     * new ServerConfig("localhost", 5433, 100, 30000, 0, "./data", ...)
     * we write:
     * ServerConfig.builder().host("localhost").port(5433).build()
     *
     * Each setter returns 'this' (the Builder), enabling chaining.
     * build() validates and constructs the final immutable object.
     */
    public static final class Builder {

        // Default values — mirrors PostgreSQL's default postgresql.conf
        private String host = "localhost";
        private int port = 5433;
        private int maxConnections = DatabaseConfig.MAX_TRANSACTIONS;
        private long connectionTimeoutMs = 30_000L; // 30 seconds
        private long statementTimeoutMs = 0L; // no timeout
        private String dataDirectory = DatabaseConfig.DEFAULT_DATA_DIRECTORY;
        private String walDirectory = DatabaseConfig.DEFAULT_WAL_DIRECTORY;
        private boolean dataChecksums = true; // on by default (safety)
        private boolean logAllStatements = false; // off by default (performance)

        // Private — only ServerConfig.builder() creates this
        private Builder() {
        }

        public Builder host(String host) {
            this.host = host;
            return this; // return Builder for chaining
        }

        public Builder port(int port) {
            // Validate: ports must be in valid range
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException(
                        "Port must be between 1 and 65535, got: " + port);
            }
            this.port = port;
            return this;
        }

        public Builder maxConnections(int maxConnections) {
            if (maxConnections < 1) {
                throw new IllegalArgumentException(
                        "maxConnections must be >= 1, got: " + maxConnections);
            }
            this.maxConnections = maxConnections;
            return this;
        }

        public Builder connectionTimeoutMs(long timeoutMs) {
            this.connectionTimeoutMs = timeoutMs;
            return this;
        }

        public Builder statementTimeoutMs(long timeoutMs) {
            this.statementTimeoutMs = timeoutMs;
            return this;
        }

        public Builder dataDirectory(String dataDirectory) {
            this.dataDirectory = dataDirectory;
            return this;
        }

        public Builder walDirectory(String walDirectory) {
            this.walDirectory = walDirectory;
            return this;
        }

        public Builder dataChecksums(boolean dataChecksums) {
            this.dataChecksums = dataChecksums;
            return this;
        }

        public Builder logAllStatements(boolean logAllStatements) {
            this.logAllStatements = logAllStatements;
            return this;
        }

        /**
         * Validates all fields and constructs the immutable ServerConfig.
         *
         * @throws IllegalStateException if configuration is invalid
         */
        public ServerConfig build() {
            validate();
            return new ServerConfig(this);
        }

        /**
         * Validates the configuration before building.
         * Throws descriptive errors for invalid combinations.
         */
        private void validate() {
            if (host == null || host.isBlank()) {
                throw new IllegalStateException("Host cannot be null or blank.");
            }
            if (dataDirectory == null || dataDirectory.isBlank()) {
                throw new IllegalStateException("Data directory cannot be null or blank.");
            }
            if (walDirectory == null || walDirectory.isBlank()) {
                throw new IllegalStateException("WAL directory cannot be null or blank.");
            }
        }
    }

    // =========================================================
    // toString — for logging/debugging
    // =========================================================

    @Override
    public String toString() {
        return String.format(
                "ServerConfig{host='%s', port=%d, maxConnections=%d, " +
                        "dataDirectory='%s', walDirectory='%s', " +
                        "dataChecksums=%b, logAllStatements=%b}",
                host, port, maxConnections,
                dataDirectory, walDirectory,
                dataChecksums, logAllStatements);
    }
}