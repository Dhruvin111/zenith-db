package org.javadb.common.exception;

/**
 * ============================================================
 * QueryException — SQL Parse, Plan & Execution Failures
 * ============================================================
 *
 * Thrown by the query processing pipeline (Sections 17-20):
 * - Lexer : unrecognized token, invalid character
 * - Parser : syntax error, unexpected token
 * - Planner : type mismatch, ambiguous column
 * - Executor : division by zero, NOT NULL violation
 *
 * PostgreSQL parallel:
 * 42601 syntax_error
 * 42804 datatype_mismatch
 * 22012 division_by_zero
 * 23502 not_null_violation
 * 42702 ambiguous_column
 * 0A000 feature_not_supported
 *
 * These errors are user-facing — the SQL the user typed is wrong.
 * Messages should be clear and actionable.
 *
 * The line/column position fields mirror PostgreSQL's error
 * location reporting — psql underlines the offending token.
 *
 * Java concepts:
 * - Extends DatabaseException : fits into our hierarchy
 * - Line/column tracking : source location in SQL text
 * - Static factory methods : readable construction
 *
 * ============================================================
 */
public class QueryException extends DatabaseException {

    /**
     * Line number in the SQL string where the error occurred.
     * -1 if unknown or not applicable.
     */
    private final int line;

    /**
     * Column (character position) in the SQL string.
     * -1 if unknown or not applicable.
     */
    private final int column;

    /**
     * The SQL token or fragment that caused the error.
     * Used to produce PostgreSQL-style "at or near X" messages.
     */
    private final String offendingToken;

    // =========================================================
    // Constructors
    // =========================================================

    /**
     * Full constructor with source location.
     *
     * @param errorCode      the query error code
     * @param message        description of the error
     * @param line           line number in SQL (-1 if unknown)
     * @param column         column number in SQL (-1 if unknown)
     * @param offendingToken the token that caused the error
     * @param cause          root cause (may be null)
     */
    public QueryException(
            ErrorCode errorCode,
            String message,
            int line,
            int column,
            String offendingToken,
            Throwable cause) {
        super(errorCode, message, cause);
        this.line = line;
        this.column = column;
        this.offendingToken = offendingToken;
    }

    /**
     * Constructor without source location (for planner/executor errors).
     *
     * @param errorCode the query error code
     * @param message   description of the error
     */
    public QueryException(ErrorCode errorCode, String message) {
        this(errorCode, message, -1, -1, null, null);
    }

    // =========================================================
    // Static Factory Methods
    // =========================================================

    /**
     * SQL syntax error — unrecognized or unexpected token.
     *
     * PostgreSQL output:
     * ERROR: syntax error at or near "SELEKT"
     * LINE 1: SELEKT * FROM users;
     * ^
     *
     * @param token  the offending token
     * @param line   line number in the SQL string
     * @param column column position in the SQL string
     * @return a QueryException with SYNTAX_ERROR code
     */
    public static QueryException syntaxError(
            String token, int line, int column) {
        return new QueryException(
                ErrorCode.SYNTAX_ERROR,
                String.format(
                        "syntax error at or near \"%s\" at line %d, column %d.",
                        token, line, column),
                line,
                column,
                token,
                null);
    }

    /**
     * Type mismatch — incompatible types in expression or assignment.
     *
     * PostgreSQL output:
     * ERROR: column "age" is of type integer but
     * expression is of type text
     *
     * @param expected the expected type name
     * @param actual   the actual type name found
     * @return a QueryException with TYPE_MISMATCH code
     */
    public static QueryException typeMismatch(
            String expected, String actual) {
        return new QueryException(
                ErrorCode.TYPE_MISMATCH,
                String.format(
                        "type mismatch: expected %s but got %s. " +
                                "Use an explicit cast if conversion is intended.",
                        expected, actual));
    }

    /**
     * Division by zero in a SQL expression.
     *
     * PostgreSQL output:
     * ERROR: division by zero
     *
     * @return a QueryException with DIVISION_BY_ZERO code
     */
    public static QueryException divisionByZero() {
        return new QueryException(
                ErrorCode.DIVISION_BY_ZERO,
                "division by zero");
    }

    /**
     * NOT NULL constraint violated — NULL inserted into a
     * column defined as NOT NULL.
     *
     * PostgreSQL output:
     * ERROR: null value in column "name" of relation "users"
     * violates not-null constraint
     *
     * @param columnName the NOT NULL column that received NULL
     * @param tableName  the table containing the column
     * @return a QueryException with NOT_NULL_VIOLATION code
     */
    public static QueryException notNullViolation(
            String columnName, String tableName) {
        return new QueryException(
                ErrorCode.NOT_NULL_VIOLATION,
                String.format(
                        "null value in column \"%s\" of relation \"%s\" " +
                                "violates not-null constraint. " +
                                "Provide a non-null value for this column.",
                        columnName, tableName));
    }

    /**
     * Ambiguous column reference — same column name exists
     * in multiple tables in the query's FROM clause.
     *
     * PostgreSQL output:
     * ERROR: column reference "id" is ambiguous
     *
     * @param columnName the ambiguous column name
     * @return a QueryException with AMBIGUOUS_COLUMN code
     */
    public static QueryException ambiguousColumn(String columnName) {
        return new QueryException(
                ErrorCode.AMBIGUOUS_COLUMN,
                String.format(
                        "column reference \"%s\" is ambiguous. " +
                                "Qualify the column with its table name: " +
                                "tableName.%s",
                        columnName, columnName));
    }

    /**
     * Feature not yet implemented in this version of JavaDB.
     *
     * PostgreSQL output:
     * ERROR: feature not supported
     *
     * @param feature description of the unimplemented feature
     * @return a QueryException with FEATURE_NOT_SUPPORTED code
     */
    public static QueryException notSupported(String feature) {
        return new QueryException(
                ErrorCode.FEATURE_NOT_SUPPORTED,
                String.format(
                        "\"%s\" is not yet supported in JavaDB. " +
                                "This feature is planned for a future version.",
                        feature));
    }

    // =========================================================
    // Getters
    // =========================================================

    /**
     * Returns the line number in the SQL where the error occurred.
     * Returns -1 if location is unknown.
     */
    public int getLine() {
        return line;
    }

    /**
     * Returns the column position in the SQL where error occurred.
     * Returns -1 if location is unknown.
     */
    public int getColumn() {
        return column;
    }

    /**
     * Returns the SQL token that caused the error.
     * Returns null if not applicable.
     */
    public String getOffendingToken() {
        return offendingToken;
    }

    /**
     * Returns true if source location information is available.
     */
    public boolean hasLocation() {
        return line != -1 && column != -1;
    }
}