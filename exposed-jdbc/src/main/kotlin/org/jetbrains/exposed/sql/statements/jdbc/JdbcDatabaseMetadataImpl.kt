package org.jetbrains.exposed.sql.statements.jdbc

import org.intellij.lang.annotations.Language
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.api.ExposedDatabaseMetadata
import org.jetbrains.exposed.sql.statements.api.IdentifierManagerApi
import org.jetbrains.exposed.sql.transactions.JdbcTransaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.vendors.*
import org.jetbrains.exposed.sql.vendors.H2Dialect.H2CompatibilityMode
import java.math.BigDecimal
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.util.concurrent.ConcurrentHashMap

/**
 * Class responsible for retrieving and storing information about the JDBC driver and underlying DBMS, using [metadata].
 */
class JdbcDatabaseMetadataImpl(database: String, val metadata: DatabaseMetaData) : ExposedDatabaseMetadata(database) {
    override val url: String by lazyMetadata { url }
    override val version: BigDecimal by lazyMetadata { BigDecimal("$databaseMajorVersion.$databaseMinorVersion") }

    override val databaseDialectName: String by lazyMetadata {
        when (driverName) {
            "MySQL-AB JDBC Driver", "MySQL Connector/J", "MySQL Connector Java" -> MysqlDialect.dialectName

            "MariaDB Connector/J" -> MariaDBDialect.dialectName
            "SQLite JDBC" -> SQLiteDialect.dialectName
            "H2 JDBC Driver" -> H2Dialect.dialectName
            "pgjdbc-ng", "PostgreSQL JDBC - NG" -> PostgreSQLNGDialect.dialectName
            "PostgreSQL JDBC Driver" -> PostgreSQLDialect.dialectName
            "Oracle JDBC driver" -> OracleDialect.dialectName
            else -> {
                if (driverName.startsWith("Microsoft JDBC Driver ")) {
                    SQLServerDialect.dialectName
                } else {
                    Database.getDialectName(url) ?: error("Unsupported driver $driverName detected")
                }
            }
        }
    }

    @InternalApi
    override val databaseDialectMode: String? by lazy {
        when (val dialect = currentDialect) {
            is H2Dialect -> {
                val (settingNameField, settingValueField) = when (dialect.majorVersion) {
                    H2Dialect.H2MajorVersion.One -> "NAME" to "VALUE"
                    H2Dialect.H2MajorVersion.Two -> "SETTING_NAME" to "SETTING_VALUE"
                }

                @Language("H2")
                val modeQuery = "SELECT $settingValueField FROM INFORMATION_SCHEMA.SETTINGS WHERE $settingNameField = 'MODE'"
                (TransactionManager.current() as JdbcTransaction).exec(modeQuery) { rs ->
                    rs.next()
                    rs.getString(settingValueField)
                }
            }
            else -> null
        }
    }

    override val databaseProductVersion by lazyMetadata { databaseProductVersion!! }

    override val defaultIsolationLevel: Int by lazyMetadata { defaultTransactionIsolation }

    override val supportsAlterTableWithAddColumn by lazyMetadata { supportsAlterTableWithAddColumn() }
    override val supportsAlterTableWithDropColumn by lazyMetadata { supportsAlterTableWithDropColumn() }
    override val supportsMultipleResultSets by lazyMetadata { supportsMultipleResultSets() }
    override val supportsSelectForUpdate: Boolean by lazyMetadata { supportsSelectForUpdate() }

    override val identifierManager: IdentifierManagerApi by lazyMetadata {
        identityManagerCache.getOrPut(url) {
            JdbcIdentifierManager(this)
        }
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
        return getTables(catalogName, schemeName, "%", arrayOf("TABLE")).iterate {
            val tableName = getString("TABLE_NAME")!!
            val fullTableName = when (dialect) {
                is MysqlDialect -> getString("TABLE_CAT")?.let { "$it.$tableName" }
                else -> getString("TABLE_SCHEM")?.let { "$it.$tableName" }
            } ?: tableName
            identifierManager.inProperCase(fullTableName)
        }
    }

    @InternalApi
    override val catalog: String?
        get() = metadata.connection.catalog

    @InternalApi
    override val schema: String?
        get() = metadata.connection.schema

    @InternalApi
    override fun catalogs(): List<String> = metadata.catalogs
        .iterate { getString("TABLE_CAT") }
        .map { identifierManager.inProperCase(it) }

    @InternalApi
    override fun schemas(): List<String> = metadata.schemas
        .iterate { getString("TABLE_SCHEM") }
        .map { identifierManager.inProperCase(it) }

    @InternalApi
    override fun columnsMetadata(catalog: String, schema: String, table: Table): List<ColumnMetadata> {
        return metadata.getColumns(catalog, schema, table.nameInDatabaseCaseUnquoted(), "%").iterate {
            val defaultDbValue = getString("COLUMN_DEF")?.let { sanitizedDefault(it) }
            val autoIncrement = getString("IS_AUTOINCREMENT") == "YES"
            val type = getInt("DATA_TYPE")
            val name = getString("COLUMN_NAME")
            val nullable = getBoolean("NULLABLE")
            val size = getInt("COLUMN_SIZE")?.takeIf { it != 0 }
            val scale = getInt("DECIMAL_DIGITS")?.takeIf { it != 0 }
            ColumnMetadata(name, type, nullable, size, scale, autoIncrement, defaultDbValue?.takeIf { !autoIncrement })
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
        val rs = metadata.getIndexInfo(catalog, schema, storedIndexTable, false, false)
        while (rs.next()) {
            rs.getString("INDEX_NAME")?.let { indexName ->
                // if index is function-based, SQLite & MySQL return null column_name metadata
                val columnNameMetadata = rs.getString("COLUMN_NAME") ?: when (currentDialect) {
                    is MysqlDialect, is SQLiteDialect -> "\"\""
                    else -> null
                }
                columnNameMetadata?.let { columnName ->
                    val column = identifierManager.quoteIdentifierWhenWrongCaseOrNecessary(columnName)
                    val isUnique = !rs.getBoolean("NON_UNIQUE")
                    val isPartial = if (rs.getString("FILTER_CONDITION").isNullOrEmpty()) null else Op.TRUE
                    tmpIndices.getOrPut(Triple(indexName, isUnique, isPartial)) { arrayListOf() }.add(column)
                }
            }
        }
        rs.close()
        return tmpIndices
    }

    override fun existingPrimaryKeys(vararg tables: Table): Map<Table, PrimaryKeyMetadata?> {
        @OptIn(InternalApi::class)
        return tables.associateWith { table ->
            val (catalog, tableSchema) = tableCatalogAndSchema(null, table)
            metadata.getPrimaryKeys(catalog, tableSchema, table.nameInDatabaseCaseUnquoted()).let { rs ->
                val columnNames = mutableListOf<String>()
                var pkName = ""
                while (rs.next()) {
                    rs.getString("PK_NAME")?.let { pkName = it }
                    columnNames += rs.getString("COLUMN_NAME")
                }
                rs.close()
                if (pkName.isEmpty()) null else PrimaryKeyMetadata(pkName, columnNames)
            }
        }
    }

    @InternalApi
    override fun primaryKeyColumns(catalog: String, schema: String, table: Table): List<String> {
        val rs = metadata.getPrimaryKeys(catalog, schema, table.nameInDatabaseCaseUnquoted())
        val names = arrayListOf<String>()
        while (rs.next()) {
            rs.getString("PK_NAME")?.let { names += it }
        }
        rs.close()
        return names
    }

    @Suppress("MagicNumber")
    override fun sequences(): List<String> {
        val dialect = currentDialect
        val transaction = TransactionManager.current() as JdbcTransaction
        @OptIn(InternalApi::class)
        return when (dialect) {
            is OracleDialect -> transaction.exec("SELECT SEQUENCE_NAME FROM USER_SEQUENCES") { rs ->
                rs.iterate {
                    quoteSequenceNameIfNecessary(getString("SEQUENCE_NAME"))
                }
            }
            is SQLServerDialect -> transaction.exec("SELECT name FROM sys.sequences") { rs ->
                rs.iterate {
                    getString("name")
                }
            }
            is H2Dialect -> transaction.exec("SELECT SEQUENCE_NAME FROM INFORMATION_SCHEMA.SEQUENCES") { rs ->
                rs.iterate {
                    quoteSequenceNameIfNecessary(getString("SEQUENCE_NAME"))
                }
            }
            else -> metadata.getTables(null, null, null, arrayOf("SEQUENCE")).iterate {
                getString(3)
            }
        } ?: emptyList()
    }

    @Synchronized
    override fun tableConstraints(tables: List<Table>): Map<String, List<ForeignKeyConstraint>> {
        val allTables = SchemaUtils.sortTablesByReferences(tables).associateBy {
            it.nameInDatabaseCaseUnquoted()
        }
        val allTableNames = allTables.keys
        val isMysqlDialect = currentDialect is MysqlDialect
        return if (isMysqlDialect) {
            val tx = TransactionManager.current() as JdbcTransaction
            val inTableList = allTableNames.joinToString("','", prefix = " ku.TABLE_NAME IN ('", postfix = "')")
            val tableSchema = "'${tables.mapNotNull { it.schemaName }.toSet().singleOrNull() ?: tx.connection.catalog}'"
            val constraintsToLoad = HashMap<String, MutableMap<String, ForeignKeyConstraint>>()
            tx.exec(
                """SELECT
                  rc.CONSTRAINT_NAME AS FK_NAME,
                  ku.TABLE_NAME AS FKTABLE_NAME,
                  ku.COLUMN_NAME AS FKCOLUMN_NAME,
                  ku.REFERENCED_TABLE_NAME AS PKTABLE_NAME,
                  ku.REFERENCED_COLUMN_NAME AS PKCOLUMN_NAME,
                  rc.UPDATE_RULE,
                  rc.DELETE_RULE
                FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS rc
                  INNER JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE ku
                    ON ku.TABLE_SCHEMA = rc.CONSTRAINT_SCHEMA AND rc.CONSTRAINT_NAME = ku.CONSTRAINT_NAME
                WHERE ku.TABLE_SCHEMA = $tableSchema
                  AND ku.CONSTRAINT_SCHEMA = $tableSchema
                  AND rc.CONSTRAINT_SCHEMA = $tableSchema
                  AND $inTableList
                ORDER BY ku.ORDINAL_POSITION
                """.trimIndent()
            ) { rs ->
                while (rs.next()) {
                    rs.parseConstraint(allTables, true)?.let { (fromTableName, fk) ->
                        constraintsToLoad.getOrPut(fromTableName) { mutableMapOf() }.merge(
                            fk.customFkName!!,
                            fk,
                            ForeignKeyConstraint::plus
                        )
                    }
                }
            }
            constraintsToLoad.mapValues { (_, v) -> v.values.toList() }
        } else {
            @OptIn(InternalApi::class)
            allTableNames.associateWith { table ->
                val (catalog, tableSchema) = tableCatalogAndSchema(null, allTables[table]!!)
                metadata.getImportedKeys(catalog, identifierManager.inProperCase(tableSchema), table).iterate {
                    parseConstraint(allTables, false)
                }.filterNotNull()
                    .unzip().second
                    .groupBy { it.fkName }.values
                    .map { it.reduce(ForeignKeyConstraint::plus) }
            }
        }
    }

    private fun ResultSet.parseConstraint(
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
        val constraintUpdateRule = getObject("UPDATE_RULE")?.toString()?.let {
            resolveReferenceOption(it)
        }
        val constraintDeleteRule = getObject("DELETE_RULE")?.toString()?.let {
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

    @Synchronized
    override fun cleanCache() {
        existingIndicesCache.clear()
    }

    @OptIn(InternalApi::class)
    override fun resolveReferenceOption(refOption: String): ReferenceOption? {
        return when (val dialect = currentDialect) {
            is H2Dialect -> {
                val modeDelegates = dialect.h2Mode == H2CompatibilityMode.Oracle || dialect.h2Mode == H2CompatibilityMode.SQLServer
                val refOptionInt = refOption.toIntOrNull() ?: return null
                when {
                    refOptionInt == DatabaseMetaData.importedKeyRestrict && modeDelegates -> ReferenceOption.NO_ACTION
                    refOptionInt == DatabaseMetaData.importedKeyRestrict -> ReferenceOption.RESTRICT
                    else -> defaultReferenceMapping(refOptionInt)
                }
            }
            // Oracle returns a 1 for NO ACTION and does not support RESTRICT
            // sql: `decode (f.delete_rule, 'CASCADE', 0, 'SET NULL', 2, 1) as delete_rule`
            is OracleDialect -> {
                val refOptionInt = refOption.toIntOrNull() ?: return null
                when (refOptionInt) {
                    DatabaseMetaData.importedKeyCascade -> ReferenceOption.CASCADE
                    DatabaseMetaData.importedKeySetNull -> ReferenceOption.SET_NULL
                    DatabaseMetaData.importedKeyRestrict -> ReferenceOption.NO_ACTION
                    else -> currentDialect.defaultReferenceOption
                }
            }
            is MysqlDialect -> ReferenceOption.valueOf(refOption.replace(" ", "_"))
            else -> {
                val refOptionInt = refOption.toIntOrNull() ?: return null
                defaultReferenceMapping(refOptionInt)
            }
        }
    }
    private fun defaultReferenceMapping(refOption: Int): ReferenceOption = when (refOption) {
        DatabaseMetaData.importedKeyCascade -> ReferenceOption.CASCADE
        DatabaseMetaData.importedKeySetNull -> ReferenceOption.SET_NULL
        DatabaseMetaData.importedKeyRestrict -> ReferenceOption.RESTRICT
        DatabaseMetaData.importedKeyNoAction -> ReferenceOption.NO_ACTION
        DatabaseMetaData.importedKeySetDefault -> ReferenceOption.SET_DEFAULT
        else -> currentDialect.defaultReferenceOption
    }

    private fun <T> lazyMetadata(body: DatabaseMetaData.() -> T) = lazy { metadata.body() }

    companion object {
        private val identityManagerCache = ConcurrentHashMap<String, JdbcIdentifierManager>()
    }
}

private fun <T> ResultSet.iterate(body: ResultSet.() -> T): List<T> {
    val result = arrayListOf<T>()
    while (next()) {
        result.add(body())
    }
    close()
    return result
}
