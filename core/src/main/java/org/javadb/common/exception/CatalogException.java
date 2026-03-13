package org.javadb.common.exception;

/**
 * ============================================================
 * CatalogException — Schema & Metadata Failures
 * ============================================================
 *
 * Thrown by the Catalog (Section 13) when schema operations
 * fail — table not found, column missing, name conflicts, etc.
 *
 * PostgreSQL parallel:
 *   42P01  undefined_table
 *   42703  undefined_column
 *   42P07  duplicate_table
 *   42701  duplicate_column
 *   3F000  invalid_schema_name
 *
 * These are the errors users see most often:
 *   ERROR:  relation "usr" does not exist   (typo in table name)
 *   ERROR:  column "nmae" does not exist    (typo in column name)
 *   ERROR:  relation "users" already exists (CREATE TABLE twice)
 *
 * Java concepts:
 *   - Extends DatabaseException  : fits into our hierarchy
 *   - Static factory methods     : descriptive error creation
 *   - String formatting          : clear, actionable messages
 *
 * ============================================================
 */
public class CatalogException extends DatabaseException {

    // =========================================================
    // Constructors
    // =========================================================

    /**
     * Full constructor.
     *
     * @param errorCode the catalog error code
     * @param message   description of what failed
     * @param cause     the root cause (may be null)
     */
    public CatalogException(
            ErrorCode errorCode,
            String    message,
            Throwable cause) {
        super(errorCode, message, cause);
    }

    /**
     * Constructor without cause (most common for catalog errors).
     *
     * @param errorCode the catalog error code
     * @param message   description of what failed
     */
    public CatalogException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    // =========================================================
    // Static Factory Methods
    // =========================================================

    /**
     * Table does not exist in the catalog.
     *
     * PostgreSQL output:
     *   ERROR:  relation "tableName" does not exist
     *
     * @param tableName the name of the missing table
     * @return a CatalogException with UNDEFINED_TABLE code
     */
    public static CatalogException tableNotFound(String tableName) {
        return new CatalogException(
            ErrorCode.UNDEFINED_TABLE,
            String.format(
                "relation \"%s\" does not exist. " +
                "Check the table name spelling and ensure the " +
                "table has been created with CREATE TABLE.",
                tableName
            )
        );
    }

    /**
     * Column does not exist in the specified table.
     *
     * PostgreSQL output:
     *   ERROR:  column "colName" does not exist
     *
     * @param columnName the name of the missing column
     * @param tableName  the table that was searched
     * @return a CatalogException with UNDEFINED_COLUMN code
     */
    public static CatalogException columnNotFound(
            String columnName, String tableName) {
        return new CatalogException(
            ErrorCode.UNDEFINED_COLUMN,
            String.format(
                "column \"%s\" of relation \"%s\" does not exist. " +
                "Check the column name spelling.",
                columnName, tableName
            )
        );
    }

    /**
     * Attempted to CREATE TABLE but a table with that name
     * already exists.
     *
     * PostgreSQL output:
     *   ERROR:  relation "tableName" already exists
     *
     * @param tableName the name of the duplicate table
     * @return a CatalogException with TABLE_ALREADY_EXISTS code
     */
    public static CatalogException tableAlreadyExists(String tableName) {
        return new CatalogException(
            ErrorCode.TABLE_ALREADY_EXISTS,
            String.format(
                "relation \"%s\" already exists. " +
                "Use CREATE TABLE IF NOT EXISTS to skip if it exists, " +
                "or DROP TABLE first.",
                tableName
            )
        );
    }

    /**
     * Attempted to add a column that already exists in the table.
     *
     * @param columnName the name of the duplicate column
     * @param tableName  the table it belongs to
     * @return a CatalogException with COLUMN_ALREADY_EXISTS code
     */
    public static CatalogException columnAlreadyExists(
            String columnName, String tableName) {
        return new CatalogException(
            ErrorCode.COLUMN_ALREADY_EXISTS,
            String.format(
                "column \"%s\" of relation \"%s\" already exists.",
                columnName, tableName
            )
        );
    }

    /**
     * Index does not exist.
     *
     * @param indexName the name of the missing index
     * @return a CatalogException with UNDEFINED_INDEX code
     */
    public static CatalogException indexNotFound(String indexName) {
        return new CatalogException(
            ErrorCode.UNDEFINED_INDEX,
            String.format(
                "index \"%s\" does not exist.",
                indexName
            )
        );
    }
}