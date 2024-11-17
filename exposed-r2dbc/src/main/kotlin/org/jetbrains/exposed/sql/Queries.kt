package org.jetbrains.exposed.sql

// code present in this file causes a resolution error if module contains tests that use JDBC SchemaUtils

// /**
// * Represents the SQL statement that deletes only rows in a table that match the provided [op].
// *
// * @param limit Maximum number of rows to delete.
// * @param op Condition that determines which rows to delete.
// * @return Count of deleted rows.
// * @sample org.jetbrains.exposed.sql.tests.shared.dml.DeleteTests.testDelete01
// */
// suspend fun <T : Table> T.deleteWhere(
//    limit: Int? = null,
//    op: T.(ISqlExpressionBuilder) -> Op<Boolean>
// ): Int {
//    return StatementBuilder { deleteWhere(limit, op) }.execute(TransactionManager.current()) ?: 0
// }
//
// /**
// * Represents the SQL statement that deletes only rows in a table that match the provided [op], while ignoring any
// * possible errors that occur during the process.
// *
// * **Note:** `DELETE IGNORE` is not supported by all vendors. Please check the documentation.
// *
// * @param limit Maximum number of rows to delete.
// * @param op Condition that determines which rows to delete.
// * @return Count of deleted rows.
// */
// suspend fun <T : Table> T.deleteIgnoreWhere(
//    limit: Int? = null,
//    op: T.(ISqlExpressionBuilder) -> Op<Boolean>
// ): Int {
//    return StatementBuilder { deleteIgnoreWhere(limit, op) }.execute(TransactionManager.current()) ?: 0
// }

// /**
// * Represents the SQL statement that deletes all rows in a table.
// *
// * @return Count of deleted rows.
// * @sample org.jetbrains.exposed.sql.tests.shared.dml.DeleteTests.testDelete01
// */
// suspend fun Table.deleteAll(): Int = StatementBuilder { deleteAll() }.execute(TransactionManager.current()) ?: 0
//
// /**
// * Represents the SQL statement that deletes rows from a table in a join relation.
// *
// * @param targetTable The specific table from this join relation to delete rows from.
// * @param targetTables (Optional) Other tables from this join relation to delete rows from.
// * **Note** Targeting multiple tables for deletion is not supported by all vendors. Please check the documentation.
// * @param ignore Whether to ignore any possible errors that occur when deleting rows.
// * **Note** [ignore] is not supported by all vendors. Please check the documentation.
// * @param limit Maximum number of rows to delete.
// * **Note** [limit] is not supported by all vendors. Please check the documentation.
// * @param where Condition that determines which rows to delete. If left as `null`, all rows will be deleted.
// * @return The number of deleted rows.
// * @sample org.jetbrains.exposed.sql.tests.shared.dml.DeleteTests.testDeleteWithSingleJoin
// */
// suspend fun Join.delete(
//    targetTable: Table,
//    vararg targetTables: Table,
//    ignore: Boolean = false,
//    limit: Int? = null,
//    where: (SqlExpressionBuilder.() -> Op<Boolean>)? = null
// ): Int {
//    val delete = StatementBuilder { delete(targetTable, targetTables = targetTables, ignore, limit, where) }
//    return delete.execute(TransactionManager.current()) ?: 0
// }

// /**
// * Represents the SQL statement that inserts a new row into a table.
// *
// * @sample org.jetbrains.exposed.sql.tests.h2.H2Tests.insertInH2
// */
// suspend fun <T : Table> T.insert(
//    body: T.(UpdateBuilder<*>) -> Unit
// ): InsertStatement<Number> {
//    return StatementBuilder { insert(body) }.apply { execute(TransactionManager.current()) }
// }
//
// /**
// * Represents the SQL statement that inserts a new row into a table.
// *
// * @return The generated ID for the new row.
// * @sample org.jetbrains.exposed.sql.tests.shared.dml.InsertTests.testGeneratedKey04
// */
// suspend fun <Key : Any, T : IdTable<Key>> T.insertAndGetId(
//    body: T.(UpdateBuilder<*>) -> Unit
// ): EntityID<Key> {
//    return StatementBuilder { insert(body) }.run {
//        execute(TransactionManager.current())
//        get(id)
//    }
// }

// TODO SEE comment on executeBatch() below
// /**
// * Represents the SQL statement that batch inserts new rows into a table.
// *
// * @param data Collection of values to use in the batch insert.
// * @param ignore Whether to ignore errors or not.
// * **Note** [ignore] is not supported by all vendors. Please check the documentation.
// * @param shouldReturnGeneratedValues Specifies whether newly generated values (for example, auto-incremented IDs)
// * should be returned. See [Batch Insert](https://github.com/JetBrains/Exposed/wiki/DSL#batch-insert) for more details.
// * @return A list of [ResultRow] representing data from each newly inserted row.
// * @sample org.jetbrains.exposed.sql.tests.shared.dml.InsertTests.testBatchInsert01
// */
// suspend fun <T : Table, E> T.batchInsert(
//    data: Iterable<E>,
//    ignore: Boolean = false,
//    shouldReturnGeneratedValues: Boolean = true,
//    body: BatchInsertStatement.(E) -> Unit
// ): List<ResultRow> = batchInsert(data.iterator(), ignoreErrors = ignore, shouldReturnGeneratedValues, body)
//
// /**
// * Represents the SQL statement that batch inserts new rows into a table.
// *
// * @param data Sequence of values to use in the batch insert.
// * @param ignore Whether to ignore errors or not.
// * **Note** [ignore] is not supported by all vendors. Please check the documentation.
// * @param shouldReturnGeneratedValues Specifies whether newly generated values (for example, auto-incremented IDs)
// * should be returned. See [Batch Insert](https://github.com/JetBrains/Exposed/wiki/DSL#batch-insert) for more details.
// * @return A list of [ResultRow] representing data from each newly inserted row.
// * @sample org.jetbrains.exposed.sql.tests.shared.dml.InsertTests.testBatchInsertWithSequence
// */
// suspend fun <T : Table, E> T.batchInsert(
//    data: Sequence<E>,
//    ignore: Boolean = false,
//    shouldReturnGeneratedValues: Boolean = true,
//    body: BatchInsertStatement.(E) -> Unit
// ): List<ResultRow> = batchInsert(data.iterator(), ignoreErrors = ignore, shouldReturnGeneratedValues, body)
//
// private suspend fun <T : Table, E> T.batchInsert(
//    data: Iterator<E>,
//    ignoreErrors: Boolean = false,
//    shouldReturnGeneratedValues: Boolean = true,
//    body: BatchInsertStatement.(E) -> Unit
// ): List<ResultRow> = executeBatch(data, body) {
//    StatementBuilder { batchInsert(ignoreErrors, shouldReturnGeneratedValues, body) }
// }
//
// /**
// * Represents the SQL statement that either batch inserts new rows into a table, or, if insertions violate unique constraints,
// * first deletes the existing rows before inserting new rows.
// *
// * **Note:** This operation is not supported by all vendors, please check the documentation.
// *
// * @param data Collection of values to use in replace.
// * @param shouldReturnGeneratedValues Specifies whether newly generated values (for example, auto-incremented IDs) should be returned.
// * See [Batch Insert](https://github.com/JetBrains/Exposed/wiki/DSL#batch-insert) for more details.
// * @sample org.jetbrains.exposed.sql.tests.shared.dml.ReplaceTests.testBatchReplace01
// */
// suspend fun <T : Table, E : Any> T.batchReplace(
//    data: Iterable<E>,
//    shouldReturnGeneratedValues: Boolean = true,
//    body: BatchReplaceStatement.(E) -> Unit
// ): List<ResultRow> = batchReplace(data.iterator(), shouldReturnGeneratedValues, body)
//
// /**
// * Represents the SQL statement that either batch inserts new rows into a table, or, if insertions violate unique constraints,
// * first deletes the existing rows before inserting new rows.
// *
// * **Note:** This operation is not supported by all vendors, please check the documentation.
// *
// * @param data Sequence of values to use in replace.
// * @param shouldReturnGeneratedValues Specifies whether newly generated values (for example, auto-incremented IDs) should be returned.
// * See [Batch Insert](https://github.com/JetBrains/Exposed/wiki/DSL#batch-insert) for more details.
// * @sample org.jetbrains.exposed.sql.tests.shared.dml.ReplaceTests.testBatchReplaceWithSequence
// */
// suspend fun <T : Table, E : Any> T.batchReplace(
//    data: Sequence<E>,
//    shouldReturnGeneratedValues: Boolean = true,
//    body: BatchReplaceStatement.(E) -> Unit
// ): List<ResultRow> = batchReplace(data.iterator(), shouldReturnGeneratedValues, body)
//
// private suspend fun <T : Table, E> T.batchReplace(
//    data: Iterator<E>,
//    shouldReturnGeneratedValues: Boolean = true,
//    body: BatchReplaceStatement.(E) -> Unit
// ): List<ResultRow> = executeBatch(data, body) {
//    StatementBuilder { batchReplace(shouldReturnGeneratedValues, body) }
// }
//
// TODO UNCOMMENT when decision is made about execution in core module. Many of BBIS internals would need to be opened.
// private suspend fun <E, S : BaseBatchInsertStatement> executeBatch(
//    data: Iterator<E>,
//    body: S.(E) -> Unit,
//    newBatchStatement: () -> S
// ): List<ResultRow> {
//    if (!data.hasNext()) return emptyList()
//
//    var statement = newBatchStatement()
//
//    val result = ArrayList<ResultRow>()
//    fun S.handleBatchException(removeLastData: Boolean = false, body: S.() -> Unit) {
//        try {
//            body()
//            if (removeLastData) validateLastBatch()
//        } catch (e: BatchDataInconsistentException) {
//            if (this.data.size == 1) {
//                throw e
//            }
//            val notTheFirstBatch = this.data.size > 1
//            if (notTheFirstBatch) {
//                if (removeLastData) {
//                    removeLastBatch()
//                }
//                execute(TransactionManager.current())
//                result += resultedValues.orEmpty()
//            }
//            statement = newBatchStatement()
//            if (removeLastData && notTheFirstBatch) {
//                statement.addBatch()
//                statement.body()
//                statement.validateLastBatch()
//            }
//        }
//    }
//
//    data.forEach { element ->
//        statement.handleBatchException { addBatch() }
//        statement.handleBatchException(true) { body(element) }
//    }
//    if (statement.arguments().isNotEmpty()) {
//        statement.execute(TransactionManager.current())
//        result += statement.resultedValues.orEmpty()
//    }
//    return result
// }
//
// /**
// * Represents the SQL statement that inserts a new row into a table, while ignoring any possible errors that occur
// * during the process.
// *
// * For example, if the new row would violate a unique constraint, its insertion would be ignored.
// * **Note:** `INSERT IGNORE` is not supported by all vendors. Please check the documentation.
// *
// * @sample org.jetbrains.exposed.sql.tests.shared.dml.InsertTests.testInsertIgnoreAndGetIdWithPredefinedId
// */
// suspend fun <T : Table> T.insertIgnore(
//    body: T.(UpdateBuilder<*>) -> Unit
// ): InsertStatement<Long> {
//    return StatementBuilder { insertIgnore(body) }.apply { execute(TransactionManager.current()) }
// }
//
// /**
// * Represents the SQL statement that inserts a new row into a table, while ignoring any possible errors that occur
// * during the process.
// *
// * For example, if the new row would violate a unique constraint, its insertion would be ignored.
// * **Note:** `INSERT IGNORE` is not supported by all vendors. Please check the documentation.
// *
// * @return The generated ID for the new row, or `null` if none was retrieved after statement execution.
// * @sample org.jetbrains.exposed.sql.tests.shared.dml.InsertTests.testInsertIgnoreAndGetId01
// */
// suspend fun <Key : Any, T : IdTable<Key>> T.insertIgnoreAndGetId(
//    body: T.(UpdateBuilder<*>) -> Unit
// ): EntityID<Key>? {
//    return StatementBuilder { insertIgnore(body) }.run {
//        when (execute(TransactionManager.current())) {
//            null, 0 -> null
//            else -> getOrNull(id)
//        }
//    }
// }
//
// /**
// * Represents the SQL statement that either inserts a new row into a table, or, if insertion would violate a unique constraint,
// * first deletes the existing row before inserting a new row.
// *
// * **Note:** This operation is not supported by all vendors, please check the documentation.
// *
// * @sample org.jetbrains.exposed.sql.tests.shared.dml.ReplaceTests.testReplaceWithExpression
// */
// suspend fun <T : Table> T.replace(
//    body: T.(UpdateBuilder<*>) -> Unit
// ): ReplaceStatement<Long> {
//    return StatementBuilder { replace(body) }.apply { execute(TransactionManager.current()) }
// }
//
// /**
// * Represents the SQL statement that uses data retrieved from a [selectQuery] to either insert a new row into a table,
// * or, if insertion would violate a unique constraint, first delete the existing row before inserting a new row.
// *
// * **Note:** This operation is not supported by all vendors, please check the documentation.
// *
// * @param selectQuery Source `SELECT` query that provides the values to insert.
// * @param columns Columns to either insert values into or delete values from then insert into. This defaults to all
// * columns in the table that are not auto-increment columns without a valid sequence to generate new values.
// * @return The number of inserted (and possibly deleted) rows, or `null` if nothing was retrieved after statement execution.
// * @sample org.jetbrains.exposed.sql.tests.shared.dml.ReplaceTests.testReplaceSelect
// */
// suspend fun <T : Table> T.replace(
//    selectQuery: AbstractQuery<*>,
//    columns: List<Column<*>>? = null
// ): Int? {
//    return StatementBuilder { replace(selectQuery, columns) }.execute(TransactionManager.current())
// }
//
// /**
// * Represents the SQL statement that uses data retrieved from a [selectQuery] to insert new rows into a table.
// *
// * @param selectQuery Source `SELECT` query that provides the values to insert.
// * @param columns Columns to insert the values into. This defaults to all columns in the table that are not
// * auto-increment columns without a valid sequence to generate new values.
// * @return The number of inserted rows, or `null` if nothing was retrieved after statement execution.
// * @sample org.jetbrains.exposed.sql.tests.shared.dml.InsertSelectTests.testInsertSelect04
// */
// suspend fun <T : Table> T.insert(
//    selectQuery: AbstractQuery<*>,
//    columns: List<Column<*>>? = null
// ): Int? {
//    return StatementBuilder { insert(selectQuery, columns) }.execute(TransactionManager.current())
// }
//
// /**
// * Represents the SQL statement that uses data retrieved from a [selectQuery] to insert new rows into a table,
// * while ignoring any possible errors that occur during the process.
// *
// * **Note:** `INSERT IGNORE` is not supported by all vendors. Please check the documentation.
// *
// * @param selectQuery Source `SELECT` query that provides the values to insert.
// * @param columns Columns to insert the values into. This defaults to all columns in the table that are not
// * auto-increment columns without a valid sequence to generate new values.
// * @return The number of inserted rows, or `null` if nothing was retrieved after statement execution.
// */
// suspend fun <T : Table> T.insertIgnore(
//    selectQuery: AbstractQuery<*>,
//    columns: List<Column<*>>? = null
// ): Int? {
//    return StatementBuilder { insertIgnore(selectQuery, columns) }.execute(TransactionManager.current())
// }
//
// /**
// * Represents the SQL statement that updates rows of a table.
// *
// * @param where Condition that determines which rows to update.
// * @param limit Maximum number of rows to update.
// * @return The number of updated rows.
// * @sample org.jetbrains.exposed.sql.tests.shared.dml.UpdateTests.testUpdate01
// */
// suspend fun <T : Table> T.update(
//    where: (SqlExpressionBuilder.() -> Op<Boolean>)? = null,
//    limit: Int? = null,
//    body: T.(UpdateStatement) -> Unit
// ): Int {
//    return StatementBuilder { update(where, limit, body) }.execute(TransactionManager.current()) ?: 0
// }
//
// /**
// * Represents the SQL statement that updates rows of a join relation.
// *
// * @param where Condition that determines which rows to update. If left `null`, all columns will be updated.
// * @param limit Maximum number of rows to update.
// * @return The number of updated rows.
// * @sample org.jetbrains.exposed.sql.tests.shared.dml.UpdateTests.testUpdateWithSingleJoin
// */
// suspend fun Join.update(
//    where: (SqlExpressionBuilder.() -> Op<Boolean>)? = null,
//    limit: Int? = null,
//    body: (UpdateStatement) -> Unit
// ): Int {
//    return StatementBuilder { update(where, limit, body) }.execute(TransactionManager.current()) ?: 0
// }
//
// /**
// * Represents the SQL statement that either inserts a new row into a table, or updates the existing row if insertion would violate a unique constraint.
// *
// * **Note:** Vendors that do not support this operation directly implement the standard MERGE USING command.
// *
// * **Note:** Currently, the `upsert()` function might return an incorrect auto-generated ID (such as a UUID) if it performs an update.
// * In this case, it returns a new auto-generated ID instead of the ID of the updated row.
// * Postgres should not be affected by this issue as it implicitly returns the IDs of updated rows.
// *
// * @param keys (optional) Columns to include in the condition that determines a unique constraint match.
// * If no columns are provided, primary keys will be used. If the table does not have any primary keys, the first unique index will be attempted.
// * @param onUpdate Lambda block with an [UpdateStatement] as its argument, allowing values to be assigned to the UPDATE clause.
// * To specify manually that the insert value should be used when updating a column, for example within an expression
// * or function, invoke `insertValue()` with the desired column as the function argument.
// * If left null, all columns will be updated with the values provided for the insert.
// * @param onUpdateExclude List of specific columns to exclude from updating.
// * If left null, all columns will be updated with the values provided for the insert.
// * @param where Condition that determines which rows to update, if a unique violation is found.
// * @sample org.jetbrains.exposed.sql.tests.shared.dml.UpsertTests.testUpsertWithUniqueIndexConflict
// */
// suspend fun <T : Table> T.upsert(
//    vararg keys: Column<*>,
//    onUpdate: (UpsertBuilder.(UpdateStatement) -> Unit)? = null,
//    onUpdateExclude: List<Column<*>>? = null,
//    where: (SqlExpressionBuilder.() -> Op<Boolean>)? = null,
//    body: T.(UpsertStatement<Long>) -> Unit
// ): UpsertStatement<Long> {
//    return StatementBuilder { upsert(keys = keys, onUpdate, onUpdateExclude, where, body) }.apply {
//        execute(TransactionManager.current())
//    }
// }

// /**
// * Represents the SQL statement that either batch inserts new rows into a table, or updates the existing rows if insertions violate unique constraints.
// *
// * @param data Collection of values to use in batch upsert.
// * @param keys (optional) Columns to include in the condition that determines a unique constraint match. If no columns are provided,
// * primary keys will be used. If the table does not have any primary keys, the first unique index will be attempted.
// * @param onUpdate Lambda block with an [UpdateStatement] as its argument, allowing values to be assigned to the UPDATE clause.
// * To specify manually that the insert value should be used when updating a column, for example within an expression
// * or function, invoke `insertValue()` with the desired column as the function argument.
// * If left null, all columns will be updated with the values provided for the insert.
// * @param onUpdateExclude List of specific columns to exclude from updating.
// * If left null, all columns will be updated with the values provided for the insert.
// * @param where Condition that determines which rows to update, if a unique violation is found.
// * @param shouldReturnGeneratedValues Specifies whether newly generated values (for example, auto-incremented IDs) should be returned.
// * See [Batch Insert](https://github.com/JetBrains/Exposed/wiki/DSL#batch-insert) for more details.
// * @sample org.jetbrains.exposed.sql.tests.shared.dml.UpsertTests.testBatchUpsertWithNoConflict
// */
// suspend fun <T : Table, E : Any> T.batchUpsert(
//    data: Iterable<E>,
//    vararg keys: Column<*>,
//    onUpdate: (UpsertBuilder.(UpdateStatement) -> Unit)? = null,
//    onUpdateExclude: List<Column<*>>? = null,
//    where: (SqlExpressionBuilder.() -> Op<Boolean>)? = null,
//    shouldReturnGeneratedValues: Boolean = true,
//    body: BatchUpsertStatement.(E) -> Unit
// ): List<ResultRow> {
//    return batchUpsert(data.iterator(), null, onUpdate, onUpdateExclude, where, shouldReturnGeneratedValues, keys = keys, body = body)
// }
//
// /**
// * Represents the SQL statement that either batch inserts new rows into a table, or updates the existing rows if insertions violate unique constraints.
// *
// * @param data Sequence of values to use in batch upsert.
// * @param keys (optional) Columns to include in the condition that determines a unique constraint match. If no columns are provided,
// * primary keys will be used. If the table does not have any primary keys, the first unique index will be attempted.
// * @param onUpdate Lambda block with an [UpdateStatement] as its argument, allowing values to be assigned to the UPDATE clause.
// * To specify manually that the insert value should be used when updating a column, for example within an expression
// * or function, invoke `insertValue()` with the desired column as the function argument.
// * If left null, all columns will be updated with the values provided for the insert.
// * @param onUpdateExclude List of specific columns to exclude from updating.
// * If left null, all columns will be updated with the values provided for the insert.
// * @param where Condition that determines which rows to update, if a unique violation is found.
// * @param shouldReturnGeneratedValues Specifies whether newly generated values (for example, auto-incremented IDs) should be returned.
// * See [Batch Insert](https://github.com/JetBrains/Exposed/wiki/DSL#batch-insert) for more details.
// * @sample org.jetbrains.exposed.sql.tests.shared.dml.UpsertTests.testBatchUpsertWithSequence
// */
// suspend fun <T : Table, E : Any> T.batchUpsert(
//    data: Sequence<E>,
//    vararg keys: Column<*>,
//    onUpdate: (UpsertBuilder.(UpdateStatement) -> Unit)? = null,
//    onUpdateExclude: List<Column<*>>? = null,
//    where: (SqlExpressionBuilder.() -> Op<Boolean>)? = null,
//    shouldReturnGeneratedValues: Boolean = true,
//    body: BatchUpsertStatement.(E) -> Unit
// ): List<ResultRow> {
//    return batchUpsert(data.iterator(), null, onUpdate, onUpdateExclude, where, shouldReturnGeneratedValues, keys = keys, body = body)
// }
//
// @Suppress("LongParameterList")
// private suspend fun <T : Table, E> T.batchUpsert(
//    data: Iterator<E>,
//    onUpdateList: List<Pair<Column<*>, Any?>>? = null,
//    onUpdate: (UpsertBuilder.(UpdateStatement) -> Unit)? = null,
//    onUpdateExclude: List<Column<*>>? = null,
//    where: (SqlExpressionBuilder.() -> Op<Boolean>)? = null,
//    shouldReturnGeneratedValues: Boolean = true,
//    vararg keys: Column<*>,
//    body: BatchUpsertStatement.(E) -> Unit
// ): List<ResultRow> = executeBatch(data, body) {
//    StatementBuilder {
//        batchUpsert(onUpdateList, onUpdate, onUpdateExclude, where, shouldReturnGeneratedValues, keys = keys, body)
//    }
// }
//
// /**
// * Returns whether [this] table exists in the database.
// *
// * @sample org.jetbrains.exposed.sql.tests.shared.DDLTests.tableExists02
// */
// suspend fun Table.exists(): Boolean = currentDialect.tableExists(this)
//
// /**
// * Performs an SQL MERGE operation to insert, update, or delete records in the target table based on
// * a comparison with a source table.
// *
// * @param D The target table type extending from [Table].
// * @param S The source table type extending from [Table].
// * @param source An instance of the source table.
// * @param on A lambda function with [SqlExpressionBuilder] as its receiver that should return a [Op<Boolean>] condition.
// * This condition is used to match records between the source and target tables.
// * @param body A lambda where [MergeTableStatement] can be configured with specific actions to perform
// * when records are matched or not matched.
// * @return A [MergeTableStatement] which represents the MERGE operation with the configured actions.
// */
// suspend fun <D : Table, S : Table> D.mergeFrom(
//    source: S,
//    on: (SqlExpressionBuilder.() -> Op<Boolean>)? = null,
//    body: MergeTableStatement.() -> Unit
// ): MergeTableStatement {
//    return StatementBuilder { mergeFrom(source, on, body) }.apply { execute(TransactionManager.current()) }
// }
//
// /**
// * Performs an SQL MERGE operation to insert, update, or delete records in the target table based on
// * a comparison with a select query source.
// *
// * @param T The target table type extending from [Table].
// * @param selectQuery represents the aliased query for a complex subquery to be used as the source.
// * @param on A lambda with a receiver of type [SqlExpressionBuilder] that returns a condition `Op<Boolean>`
// * used to match records between the source query and the target table.
// * @param body A lambda where [MergeSelectStatement] can be configured with specific actions to perform
// * when records are matched or not matched.
// * @return A [MergeSelectStatement] which represents the MERGE operation with the configured actions.
// */
// suspend fun <T : Table> T.mergeFrom(
//    selectQuery: QueryAlias,
//    on: SqlExpressionBuilder.() -> Op<Boolean>,
//    body: MergeSelectStatement.() -> Unit
// ): MergeSelectStatement {
//    return StatementBuilder { mergeFrom(selectQuery, on, body) }.apply { execute(TransactionManager.current()) }
// }
