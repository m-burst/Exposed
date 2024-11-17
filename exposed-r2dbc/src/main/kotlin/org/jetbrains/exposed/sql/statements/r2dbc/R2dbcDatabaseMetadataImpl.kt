package org.jetbrains.exposed.sql.statements.r2dbc

import io.r2dbc.spi.Connection
import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.intellij.lang.annotations.Language
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.api.ExposedDatabaseMetadata
import org.jetbrains.exposed.sql.statements.api.IdentifierManagerApi
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.vendors.*
import org.jetbrains.exposed.sql.vendors.H2Dialect.H2MajorVersion
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

@Suppress("UnusedParameter", "UnusedPrivateMember")
class R2dbcDatabaseMetadataImpl(
    database: String,
    val metadata: MetadataProvider,
    private val connection: Connection,
    private val scope: R2dbcScope
) : ExposedDatabaseMetadata(database) {
    private val connectionData = connection.metadata

    override val url: String by lazyMetadata { TransactionManager.current().db.url }

    override val version: BigDecimal by lazy {
        connectionData.databaseVersion
            .split('.', ' ')
            .let { BigDecimal("${it[0]}.${it[1]}") }
    }

    override val databaseDialectName: String by lazy {
        when (connectionData.databaseProductName) {
            "MySQL Community Server - GPL", "MySQL Community Server (GPL)" -> MysqlDialect.dialectName
            "MariaDB" -> MariaDBDialect.dialectName
            "H2" -> H2Dialect.dialectName
            "PostgreSQL" -> PostgreSQLDialect.dialectName
            "Oracle" -> OracleDialect.dialectName
            else -> {
                if (connectionData.databaseProductName.startsWith("Microsoft SQL Server ")) {
                    SQLServerDialect.dialectName
                } else {
                    Database.getDialectName(url)
                        ?: error("Unsupported driver ${connectionData.databaseProductName} detected")
                }
            }
        }
    }

    @InternalApi
    override val databaseDialectMode: String? by lazy {
        when (val dialect = currentDialect) {
            is H2Dialect -> {
                val (settingNameField, settingValueField) = when (dialect.majorVersion) {
                    H2MajorVersion.One -> "NAME" to "VALUE"
                    H2MajorVersion.Two -> "SETTING_NAME" to "SETTING_VALUE"
                }

                @Language("H2")
                val modeQuery = "SELECT $settingValueField FROM INFORMATION_SCHEMA.SETTINGS WHERE $settingNameField = 'MODE'"
                fetchMetadata(modeQuery) { row, _ ->
                    row.getString(settingValueField)
                }.first()
            }
            else -> null
        }
    }

    override val databaseProductVersion: String by lazy { connectionData.databaseVersion }

    override val defaultIsolationLevel: Int by lazyMetadata { defaultIsolationLevel }

    override val supportsAlterTableWithAddColumn: Boolean by lazyMetadata { propertyProvider.supportsAlterTableWithAddColumn }

    override val supportsAlterTableWithDropColumn: Boolean by lazyMetadata { propertyProvider.supportsAlterTableWithDropColumn }

    override val supportsMultipleResultSets: Boolean by lazyMetadata { propertyProvider.supportsMultipleResultSets }

    override val supportsSelectForUpdate: Boolean by lazyMetadata { propertyProvider.supportsSelectForUpdate }

    override val identifierManager: IdentifierManagerApi by lazyMetadata {
        // db URL as KEY causes issues with multi-tenancy!
        identityManagerCache.getOrPut(url) { R2dbcIdentifierManager(this, connectionData) }
    }

    override fun resetCurrentScheme() {
        @OptIn(InternalApi::class)
        currentSchema = null
    }

    override val tableNames: Map<String, List<String>>
        get() {
            @OptIn(InternalApi::class)
            return CachableMapWithDefault(
                default = { schemeName -> tableNamesFor(schemeName) }
            )
        }

    @OptIn(InternalApi::class)
    private fun tableNamesFor(schema: String): List<String> = with(metadata) {
        val dialect = currentDialect
        val (catalogName, schemeName) = catalogAndSchemaArgs(schema, dialect)
        return fetchMetadata(
            sqlQuery = getTables(catalogName, schemeName, "%")
        ) { row, _ ->
            TODO("Not yet implemented")
//            parseTableName(dialect)
        }
    }

    @InternalApi
    override val catalog: String?
        get() = with(metadata) {
            fetchMetadata(getCatalog()) { row, _ ->
                row.getString("TABLE_CAT")
            }
        }.single()

    @InternalApi
    override val schema: String?
        get() = with(metadata) {
            fetchMetadata(getSchema()) { row, _ ->
                row.getString("TABLE_SCHEM")
            }
        }.single()

    @InternalApi
    override fun catalogs(): List<String> = with(metadata) {
        fetchMetadata(getCatalogs()) { row, _ ->
            row.getString("TABLE_CAT")
        }
    }.mapNotNull { name -> name?.let { identifierManager.inProperCase(it) } }

    @InternalApi
    override fun schemas(): List<String> = with(metadata) {
        fetchMetadata(getSchemas()) { row, _ ->
            row.getString("TABLE_SCHEM")
        }
    }.mapNotNull { name -> name?.let { identifierManager.inProperCase(it) } }

    @InternalApi
    override fun columnsMetadata(catalog: String, schema: String, table: Table): List<ColumnMetadata> {
        return with(metadata) {
            fetchMetadata(getColumns(catalog, schema, table.nameInDatabaseCaseUnquoted())) { row, _ ->
                TODO()
//            parseColumnMetadata(dialect)
            }
        }
    }

    private val existingIndicesCache = HashMap<Table, List<Index>>()

    @OptIn(InternalApi::class)
    override fun existingIndices(vararg tables: Table): Map<Table, List<Index>> {
        for (table in tables) {
            val (catalog, tableSchema) = tableCatalogAndSchema(null, table)
            existingIndicesCache.getOrPut(table) {
                val pkNames = primaryKeyColumns(catalog, tableSchema, table)
                indicesMetadata(catalog, tableSchema, table)
                    .filterKeysAndParse(table, pkNames)
            }
        }
        return HashMap(existingIndicesCache)
    }

    @Suppress("NestedBlockDepth")
    @InternalApi
    override fun indicesMetadata(
        catalog: String,
        schema: String,
        table: Table
    ): HashMap<Triple<String, Boolean, Op.TRUE?>, MutableList<String>> {
        val tmpIndices = hashMapOf<Triple<String, Boolean, Op.TRUE?>, MutableList<String>>()
        val storedIndexTable = if (schema == currentSchema!! && currentDialect is OracleDialect) {
            table.nameInDatabaseCase()
        } else {
            table.nameInDatabaseCaseUnquoted()
        }
        with(metadata) {
            fetchMetadata(getIndexInfo(catalog, schema, storedIndexTable)) { row, _ ->
                row.getString("INDEX_NAME")?.let { indexName ->
                    // if index is function-based, SQLite & MySQL return null column_name metadata
                    val columnNameMetadata = row.getString("COLUMN_NAME") ?: when (currentDialect) {
                        is MysqlDialect, is SQLiteDialect -> "\"\""
                        else -> null
                    }
                    columnNameMetadata?.let { columnName ->
                        val column = identifierManager.quoteIdentifierWhenWrongCaseOrNecessary(columnName)
                        val isUnique = !row.getBoolean("NON_UNIQUE")
                        val isPartial = if (row.getString("FILTER_CONDITION").isNullOrEmpty()) null else Op.TRUE
                        tmpIndices.getOrPut(Triple(indexName, isUnique, isPartial)) { arrayListOf() }.add(column)
                    }
                }
            }
        }
        return tmpIndices
    }

    override fun existingPrimaryKeys(vararg tables: Table): Map<Table, PrimaryKeyMetadata?> {
        @OptIn(InternalApi::class)
        return tables.associateWith { table ->
            val (catalog, tableSchema) = tableCatalogAndSchema(null, table)
            with(metadata) {
                val columnNames = mutableListOf<String>()
                var pkName = ""
                fetchMetadata(getPrimaryKeys(catalog, tableSchema, table.nameInDatabaseCaseUnquoted())) { row, _ ->
                    row.getString("PK_NAME")?.let { pkName = it }
                    columnNames += row.getString("COLUMN_NAME")!!
                }
                if (pkName.isEmpty()) null else PrimaryKeyMetadata(pkName, columnNames)
            }
        }
    }

    @InternalApi
    override fun primaryKeyColumns(catalog: String, schema: String, table: Table): List<String> {
        val names = arrayListOf<String>()
        with(metadata) {
            fetchMetadata(getPrimaryKeys(catalog, schema, table.nameInDatabaseCaseUnquoted())) { row, _ ->
                row.getString("PK_NAME")?.let { names += it }
            }
        }
        return names
    }

    override fun sequences(): List<String> {
        val sequences = fetchMetadata(
            metadata.getSequences()
        ) { row, _ ->
            row.getString("SEQUENCE_NAME")!!
        }
        val dialect = currentDialect
        @OptIn(InternalApi::class)
        return when (dialect) {
            is OracleDialect, is H2Dialect -> sequences.map { quoteSequenceNameIfNecessary(it) }
            else -> sequences
        }
    }

    override fun tableConstraints(tables: List<Table>): Map<String, List<ForeignKeyConstraint>> {
        val allTables = SchemaUtils.sortTablesByReferences(tables).associateBy { it.nameInDatabaseCaseUnquoted() }
        val allTableNames = allTables.keys
        val isMysqlDialect = currentDialect is MysqlDialect
        return if (isMysqlDialect) {
            val tx = TransactionManager.current()
            val tableSchema = "'${tables.mapNotNull { it.schemaName }.toSet().singleOrNull() ?: tx.connection.catalog}'"
            val constraintsToLoad = HashMap<String, MutableMap<String, ForeignKeyConstraint>>()
            fetchMetadata(
                metadata.getImportedKeys("", tableSchema, "")
            ) { row, _ ->
                row.parseConstraint(allTables, true)?.let { (fromTableName, fk) ->
                    constraintsToLoad.getOrPut(fromTableName) { mutableMapOf() }.merge(
                        fk.customFkName!!,
                        fk,
                        ForeignKeyConstraint::plus
                    )
                }
            }
            constraintsToLoad.mapValues { (_, v) -> v.values.toList() }
        } else {
            @OptIn(InternalApi::class)
            allTableNames.associateWith { table ->
                val (catalog, tableSchema) = tableCatalogAndSchema(null, allTables[table]!!)
                fetchMetadata(
                    metadata.getImportedKeys(catalog, identifierManager.inProperCase(tableSchema), table)
                ) { row, _ ->
                    row.parseConstraint(allTables, false)
                }.filterNotNull()
                    .unzip().second
                    .groupBy { it.fkName }.values
                    .map { it.reduce(ForeignKeyConstraint::plus) }
            }
        }
    }

    private fun Row.parseConstraint(
        allTables: Map<String, Table>,
        isMysqlDialect: Boolean
    ): Pair<String, ForeignKeyConstraint>? {
        val fromTableName = getString("FKTABLE_NAME")!!
        if (isMysqlDialect && fromTableName !in allTables.keys) return null
        val fromColumnName = identifierManager.quoteIdentifierWhenWrongCaseOrNecessary(
            getString("FKCOLUMN_NAME")!!
        )
        val fromColumn = allTables[fromTableName]?.columns?.firstOrNull {
            val identifier = if (isMysqlDialect) it.nameInDatabaseCase() else it.name
            identifierManager.quoteIdentifierWhenWrongCaseOrNecessary(identifier) == fromColumnName
        } ?: return null // Do not crash if there are missing fields in the Exposed tables
        val constraintName = getString("FK_NAME")!!
        val targetTableName = getString("PKTABLE_NAME")!!
        val targetColumnName = identifierManager.quoteIdentifierWhenWrongCaseOrNecessary(
            if (isMysqlDialect) {
                getString("PKCOLUMN_NAME")!!
            } else {
                identifierManager.inProperCase(getString("PKCOLUMN_NAME")!!)
            }
        )
        val targetColumn = allTables[targetTableName]?.columns?.firstOrNull {
            identifierManager.quoteIdentifierWhenWrongCaseOrNecessary(it.nameInDatabaseCase()) == targetColumnName
        } ?: return null // Do not crash if there are missing fields in the Exposed tables
        val constraintUpdateRule = get("UPDATE_RULE")?.toString()?.let {
            resolveReferenceOption(it)
        }
        val constraintDeleteRule = get("DELETE_RULE")?.toString()?.let {
            resolveReferenceOption(it)
        }
        return fromTableName to ForeignKeyConstraint(
            target = targetColumn,
            from = fromColumn,
            onUpdate = constraintUpdateRule,
            onDelete = constraintDeleteRule,
            name = constraintName
        )
    }

    override fun cleanCache() {
        existingIndicesCache.clear()
    }

    @OptIn(InternalApi::class)
    override fun resolveReferenceOption(refOption: String): ReferenceOption? {
        TODO("Not yet implemented")
    }

    private fun <T> lazyMetadata(body: MetadataProvider.() -> T) = lazy { metadata.body() }

    companion object {
        private val identityManagerCache = ConcurrentHashMap<String, R2dbcIdentifierManager>()
    }

    private fun <T> fetchMetadata(
        sqlQuery: String,
        body: (Row, RowMetadata) -> T
    ): List<T> = runBlocking {
        withContext(scope.coroutineContext) {
            val result = mutableListOf<T>()
            connection
                .createStatement(sqlQuery)
                .execute()
                .awaitFirst()
                .map { row, metadata ->
                    result.add(body(row, metadata))
                }
            result
        }
    }

    private fun Row.getString(name: String): String? = get(name, java.lang.String::class.java)?.toString()

    private fun Row.getBoolean(name: String): Boolean = get(name, java.lang.Boolean::class.java)?.booleanValue() ?: false
}
