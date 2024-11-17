package org.jetbrains.exposed.sql.statements.api

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.vendors.*
import org.jetbrains.exposed.sql.vendors.H2Dialect.H2CompatibilityMode
import java.math.BigDecimal

/**
 * Base class responsible for retrieving and storing information about the driver and underlying [database].
 *
 * @property database Name of the connected database.
 */
abstract class ExposedDatabaseMetadata(val database: String) {
    /** The connection URL for the database. */
    abstract val url: String

    /** The version number of the database as a `BigDecimal`. */
    abstract val version: BigDecimal

    /** The name of the database based on the name of the underlying driver. */
    abstract val databaseDialectName: String

    /** The name of the mode of the database, if applicable. */
    @InternalApi
    abstract val databaseDialectMode: String?

    /** The version number of the database product as a `String`. */
    abstract val databaseProductVersion: String

    /** The default transaction isolation level for the database. */
    abstract val defaultIsolationLevel: Int

    /** Whether the database supports `ALTER TABLE` with an add column clause. */
    abstract val supportsAlterTableWithAddColumn: Boolean

    /** Whether the database supports `ALTER TABLE` with a drop column clause. */
    abstract val supportsAlterTableWithDropColumn: Boolean

    /** Whether the database supports getting multiple result sets from a single execute. */
    abstract val supportsMultipleResultSets: Boolean

    /** Whether the database supports `SELECT FOR UPDATE` statements. */
    abstract val supportsSelectForUpdate: Boolean

    /** The database-specific and metadata-reliant implementation of [IdentifierManagerApi]. */
    abstract val identifierManager: IdentifierManagerApi

    /** Clears and resets any stored information about the database's current schema to default values. */
    abstract fun resetCurrentScheme()

    /** A mapping of all schema names in the database to a list of all defined table names in each schema. */
    abstract val tableNames: Map<String, List<String>>

    /** The catalog name for the current database connection, or `null` if there is none. */
    @InternalApi
    protected abstract val catalog: String?

    /** The schema name for the current database connection, or `null` if there is none. */
    @InternalApi
    protected abstract val schema: String?

    /** A list of existing schema names. */
    @OptIn(InternalApi::class)
    open val schemaNames: List<String>
        get() = if (currentDialect is MysqlDialect) catalogs() else schemas()

    /** All catalog names available in the current database. */
    @InternalApi
    protected abstract fun catalogs(): List<String>

    /** All schema names available in the current database. */
    @InternalApi
    protected abstract fun schemas(): List<String>

    /**
     * Returns the current schema name and a list of its existing table names, stored as [SchemaMetadata].
     *
     * A [tableNamesCache] of previously read metadata, if applicable, can be provided to avoid retrieving new metadata.
     */
    @OptIn(InternalApi::class)
    open fun tableNamesByCurrentSchema(tableNamesCache: Map<String, List<String>>?): SchemaMetadata {
        val tablesInSchema = (tableNamesCache ?: tableNames).getValue(currentSchema!!)
        return SchemaMetadata(currentSchema!!, tablesInSchema)
    }

    /** Returns a map with the [ColumnMetadata] of all the defined columns in each of the specified [tables]. */
    @OptIn(InternalApi::class)
    open fun columns(vararg tables: Table): Map<Table, List<ColumnMetadata>> {
        val result = mutableMapOf<Table, List<ColumnMetadata>>()
        val tablesBySchema = tables.groupBy {
            identifierManager.inProperCase(it.schemaName ?: currentSchema!!)
        }
        for ((schema, schemaTables) in tablesBySchema.entries) {
            for (table in schemaTables) {
                val catalog = tableCatalogAndSchema(schema, table).first
                val columns = columnsMetadata(catalog, schema, table)
                check(columns.isNotEmpty())
                result[table] = columns
            }
        }
        return result
    }

    /** Returns details about all columns in the specified [table] as [ColumnMetadata]. */
    @InternalApi
    protected abstract fun columnsMetadata(catalog: String, schema: String, table: Table): List<ColumnMetadata>

    /** Returns a map with all the defined indices in each of the specified [tables]. */
    abstract fun existingIndices(vararg tables: Table): Map<Table, List<Index>>

    // CONSIDER implementing IndexMetadata or a typealias
    /** Returns details about all indices in the specified [table] mapped to their columns. */
    @InternalApi
    protected abstract fun indicesMetadata(
        catalog: String,
        schema: String,
        table: Table
    ): HashMap<Triple<String, Boolean, Op.TRUE?>, MutableList<String>>

    /** Returns a map with the [PrimaryKeyMetadata] in each of the specified [tables]. */
    abstract fun existingPrimaryKeys(vararg tables: Table): Map<Table, PrimaryKeyMetadata?>

    /** Returns the column names for the primary key of the specified [table]. */
    @InternalApi
    protected abstract fun primaryKeyColumns(catalog: String, schema: String, table: Table): List<String>

    /** Returns a list of the names of all sequences in the database. */
    abstract fun sequences(): List<String>

    /**
     * Returns a map with the [ForeignKeyConstraint] of all the defined columns in each of the specified [tables],
     * with the table name used as the key.
     */
    abstract fun tableConstraints(tables: List<Table>): Map<String, List<ForeignKeyConstraint>>

    /** Clears any cached values. */
    abstract fun cleanCache()

    // THIS could become protected after the usage in DatabaseDialect is fully deprecated
    /** Returns the corresponding [ReferenceOption] for the specified [refOption] from the driver. */
    @InternalApi
    abstract fun resolveReferenceOption(refOption: String): ReferenceOption?

    /** Returns the name of the current database, which may be equivalent to the current schema name in some dialects. */
    @InternalApi
    protected val databaseName
        get() = when (databaseDialectName) {
            MysqlDialect.dialectName, MariaDBDialect.dialectName -> currentSchema!!
            else -> database
        }

    /** Returns the name of the current schema. */
    @InternalApi
    protected var currentSchema: String? = null
        get() {
            if (field == null) {
                field = try {
                    when (databaseDialectName) {
                        MysqlDialect.dialectName, MariaDBDialect.dialectName -> catalog.orEmpty()
                        OracleDialect.dialectName -> schema ?: databaseName
                        else -> schema.orEmpty()
                    }
                } catch (_: Throwable) {
                    ""
                }
            }
            return field!!
        }

    @InternalApi
    protected inner class CachableMapWithDefault<K, V>(
        private val map: MutableMap<K, V> = mutableMapOf(),
        val default: (K) -> V
    ) : Map<K, V> by map {
        override fun get(key: K): V? = map.getOrPut(key) { default(key) }
        override fun containsKey(key: K): Boolean = true
        override fun isEmpty(): Boolean = false
    }

    /** Returns a pair of query arguments for the catalog name and schema name of a database connection. */
    @InternalApi
    protected fun catalogAndSchemaArgs(
        schema: String,
        dialect: DatabaseDialect
    ): Pair<String, String> = when (dialect) {
        is MysqlDialect -> schema to "%"
        is OracleDialect -> databaseName to schema.ifEmpty { databaseName }
        else -> databaseName to schema.ifEmpty { "%" }
    }

    /**
     * Returns the name of the database in which a [table] is found, as well as it's schema name.
     *
     * If the table name does not include a schema prefix, the metadata value `currentScheme` is used instead.
     *
     * MySQL/MariaDB are special cases in that a schema definition is treated like a separate database. This means that
     * a connection to 'testDb' with a table defined as 'my_schema.my_table' will only successfully find the table's
     * metadata if 'my_schema' is used as the database name.
     */
    @InternalApi
    protected fun tableCatalogAndSchema(schema: String?, table: Table): Pair<String, String> {
        val tableSchema = schema ?: identifierManager.inProperCase(table.schemaName ?: currentSchema!!)
        return if (currentDialect is MysqlDialect && tableSchema != currentSchema!!) {
            tableSchema to tableSchema
        } else {
            databaseName to tableSchema
        }
    }

    /** Filters a map of index details to exclude key column indices and returns each entry as an [Index]. */
    @InternalApi
    protected fun HashMap<Triple<String, Boolean, Op.TRUE?>, MutableList<String>>.filterKeysAndParse(
        table: Table,
        keyColumnNames: List<String>
    ): List<Index> {
        val transaction = TransactionManager.current()
        val tColumns = table.columns.associateBy { transaction.identity(it) }
        return filterNot { it.key.first in keyColumnNames }
            .mapNotNull { (index, columns) ->
                val (functionBased, columnBased) = columns.distinct().partition { cn ->
                    tColumns[cn] == null
                }
                columnBased
                    .map { cn -> tColumns[cn]!! }
                    .takeIf { c -> c.size + functionBased.size == columns.size }
                    ?.let { c ->
                        Index(
                            c,
                            index.second,
                            index.first,
                            filterCondition = index.third,
                            functions = functionBased.map { stringLiteral(it) }.ifEmpty { null },
                            functionsTable = if (functionBased.isNotEmpty()) table else null
                        )
                    }
            }
    }

    @InternalApi
    protected fun quoteSequenceNameIfNecessary(name: String): String {
        return if (identifierManager.isDotPrefixedAndUnquoted(name)) "\"$name\"" else name
    }

    /**
     * Here is the table of default values which are returned from the column `"COLUMN_DEF"` depending on how it was configured:
     *
     * - Not set: `varchar("any", 128).nullable()`
     * - Set null: `varchar("any", 128).nullable().default(null)`
     * - Set "NULL": `varchar("any", 128).nullable().default("NULL")`
     * ```
     * DB                  Not set    Set null                    Set "NULL"
     * SqlServer           null       "(NULL)"                    "('NULL')"
     * SQLite              null       "NULL"                      "'NULL'"
     * Postgres            null       "NULL::character varying"   "'NULL'::character varying"
     * PostgresNG          null       "NULL::character varying"   "'NULL'::character varying"
     * Oracle              null       "NULL "                     "'NULL' "
     * MySql5              null       null                        "NULL"
     * MySql8              null       null                        "NULL"
     * MariaDB3            "NULL"     "NULL"                      "'NULL'"
     * MariaDB2            "NULL"     "NULL"                      "'NULL'"
     * H2V1                null       "NULL"                      "'NULL'"
     * H2V1 (MySql)        null       "NULL"                      "'NULL'"
     * H2V2                null       "NULL"                      "'NULL'"
     * H2V2 (MySql)        null       "NULL"                      "'NULL'"
     * H2V2 (MariaDB)      null       "NULL"                      "'NULL'"
     * H2V2 (PSQL)         null       "NULL"                      "'NULL'"
     * H2V2 (Oracle)       null       "NULL"                      "'NULL'"
     * H2V2 (SqlServer)    null       "NULL"                      "'NULL'"
     * ```
     * According to this table there is no simple rule of what is the default value. It should be checked
     * for each DB (or groups of DBs) specifically.
     * In the case of MySql and MariaDB it's also not possible to say whether was default value skipped or
     * explicitly set to `null`.
     *
     * @return `null` - if the value was set to `null` or not configured. `defaultValue` in other case.
     */
    @InternalApi
    protected fun sanitizedDefault(defaultValue: String): String? {
        val dialect = currentDialect
        val h2Mode = dialect.h2Mode
        return when {
            // Check for MariaDB must be before MySql because MariaDBDialect as a class inherits MysqlDialect
            dialect is MariaDBDialect || h2Mode == H2CompatibilityMode.MariaDB -> when {
                defaultValue.startsWith("b'") -> defaultValue.substringAfter("b'").trim('\'')
                else -> defaultValue.extractNullAndStringFromDefaultValue()
            }
            // A special case, because MySql returns default string "NULL" as string "NULL", but other DBs return it as "'NULL'"
            dialect is MysqlDialect && defaultValue == "NULL" -> defaultValue
            dialect is MysqlDialect || h2Mode == H2CompatibilityMode.MySQL -> when {
                defaultValue.startsWith("b'") -> defaultValue.substringAfter("b'").trim('\'')
                else -> defaultValue.extractNullAndStringFromDefaultValue()
            }
            dialect is SQLServerDialect -> defaultValue.trim('(', ')').extractNullAndStringFromDefaultValue()
            dialect is OracleDialect -> defaultValue.trim().extractNullAndStringFromDefaultValue()
            else -> defaultValue.extractNullAndStringFromDefaultValue()
        }
    }
    private fun String.extractNullAndStringFromDefaultValue() = when {
        this.startsWith("NULL") -> null
        this.startsWith('\'') && this.endsWith('\'') -> this.trim('\'')
        else -> this
    }
}
