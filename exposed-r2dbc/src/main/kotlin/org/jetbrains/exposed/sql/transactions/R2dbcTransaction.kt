package org.jetbrains.exposed.sql.transactions

import io.r2dbc.spi.Row
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.collect
import org.intellij.lang.annotations.Language
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.InternalApi
import org.jetbrains.exposed.sql.R2dbcDatabase
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.exposedLogger
import org.jetbrains.exposed.sql.statements.Statement
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.statements.api.DatabaseApi
import org.jetbrains.exposed.sql.statements.api.PreparedStatementApi
import org.jetbrains.exposed.sql.statements.r2dbc.R2dbcPreparedStatementImpl
import java.sql.SQLException
import java.util.concurrent.ThreadLocalRandom
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Class representing a unit block of work that is performed on a database with an underlying R2DBC driver. */
class R2dbcTransaction(
    transactionImpl: TransactionInterface
) : Transaction(transactionImpl) {
    /**
     * Executes the provided statement exactly, using the supplied [args] to set values to question mark
     * placeholders (if applicable).
     *
     * The [explicitStatementType] can be manually set to avoid iterating over [StatementType] values for the best match.
     */
    suspend fun exec(
        @Language("sql") stmt: String,
        args: Iterable<Pair<IColumnType<*>, Any?>> = emptyList(),
        explicitStatementType: StatementType? = null
    ) = exec(stmt, args, explicitStatementType) { }?.collect()

    /**
     * Executes the provided statement exactly, using the supplied [args] to set values to question mark
     * placeholders (if applicable).
     *
     * The [explicitStatementType] can be manually set to avoid iterating over [StatementType] values for the best match.
     *
     * @return The result of [transform] on the [Row] generated by the statement execution,
     * or `null` if no [Row] was returned by the database.
     */
    suspend fun <T : Any> exec(
        @Language("sql") stmt: String,
        args: Iterable<Pair<IColumnType<*>, Any?>> = emptyList(),
        explicitStatementType: StatementType? = null,
        transform: (Row) -> T?
    ): Flow<T?>? {
        if (stmt.isEmpty()) return emptyFlow()

        val type = explicitStatementType
            ?: StatementType.entries.find { stmt.trim().startsWith(it.name, true) }
            ?: StatementType.OTHER

        return exec(object : Statement<Flow<T?>>(type, emptyList()) {
            override suspend fun PreparedStatementApi.executeInternal(transaction: Transaction): Flow<T?> {
                // REPLACE branches with executeQuery() and executeUpdate() once finalized
                return when (type) {
                    StatementType.SELECT, StatementType.EXEC, StatementType.SHOW, StatementType.PRAGMA -> flow {
                        (this@executeInternal as R2dbcPreparedStatementImpl).statement
                            .execute()
                            .collect { result ->
                                result
                                    .map { row, _ -> transform(row) }
                                    .collect { emit(it) }
                            }
                    }
                    StatementType.MULTI -> error("Executing statement with multiple results is unsupported")
                    else -> flow {
                        (this@executeInternal as R2dbcPreparedStatementImpl).statement
                            .execute()
                            .collect { result ->
                                result.rowsUpdated.awaitFirstOrNull()
                            }
                    }
                }
            }

            override fun prepareSQL(transaction: Transaction, prepared: Boolean): String = stmt

            override fun arguments(): Iterable<Iterable<Pair<IColumnType<*>, Any?>>> = listOf(
                args.map { (columnType, value) ->
                    columnType.apply { nullable = true } to value
                }
            )
        })
    }

    /**
     * Provided statements will be executed in a batch.
     * Select statements are not supported as it's impossible to return multiple results.
     */
    suspend fun execInBatch(stmts: List<String>) {
        connection.executeInBatch(stmts)
    }
}

// naming?

/**
 * Creates a new `TransactionScope` then calls the specified suspending [statement], suspends until it completes,
 * and returns the result.
 *
 * The `TransactionScope` is derived from a new `Transaction` and a given coroutine [context],
 * or the current [CoroutineContext] if no [context] is provided.
 */
suspend fun <T> suspendTransaction(
    context: CoroutineContext? = null,
    db: R2dbcDatabase? = null,
    transactionIsolation: Int? = null,
    statement: suspend R2dbcTransaction.() -> T
): T =
    withTransactionScope(context, null, db, transactionIsolation) {
        suspendedTransactionAsyncInternal(true, statement).await()
    }

/**
 * Calls the specified suspending [statement], suspends until it completes, and returns the result.
 *
 * The resulting `TransactionScope` is derived from the current [CoroutineContext] if the latter already holds
 * [this] `Transaction`; otherwise, a new scope is created using [this] `Transaction` and a given coroutine [context].
 */
suspend fun <T> R2dbcTransaction.suspendTransaction(
    context: CoroutineContext? = null,
    statement: suspend R2dbcTransaction.() -> T
): T =
    withTransactionScope(context, this, db = null, transactionIsolation = null) {
        suspendedTransactionAsyncInternal(false, statement).await()
    }

/**
 * Creates a new `TransactionScope` and returns its future result as an implementation of [Deferred].
 *
 * The `TransactionScope` is derived from a new `Transaction` and a given coroutine [context],
 * or the current [CoroutineContext] if no [context] is provided.
 */
suspend fun <T> suspendTransactionAsync(
    context: CoroutineContext? = null,
    db: R2dbcDatabase? = null,
    transactionIsolation: Int? = null,
    statement: suspend R2dbcTransaction.() -> T
): Deferred<T> {
    val currentTransaction = TransactionManager.currentOrNull() as? R2dbcTransaction
    return withTransactionScope(context, null, db, transactionIsolation) {
        suspendedTransactionAsyncInternal(!holdsSameTransaction(currentTransaction), statement)
    }
}

private fun R2dbcTransaction.closeAsync() {
    val currentTransaction = TransactionManager.currentOrNull()
    try {
        val temporaryManager = this.db.transactionManager
        temporaryManager.bindTransactionToThread(this)
        TransactionManager.resetCurrent(temporaryManager)
    } finally {
        closeStatementsAndConnection(this)
        val transactionManager = currentTransaction?.db?.transactionManager
        transactionManager?.bindTransactionToThread(currentTransaction)
        TransactionManager.resetCurrent(transactionManager)
    }
}

private suspend fun <T> withTransactionScope(
    context: CoroutineContext?,
    currentTransaction: R2dbcTransaction?,
    db: DatabaseApi? = null,
    transactionIsolation: Int?,
    body: suspend TransactionScope.() -> T
): T {
    val currentScope = coroutineContext[TransactionScope]

    @OptIn(InternalApi::class)
    suspend fun newScope(currentTransaction: R2dbcTransaction?): T {
        val currentDatabase: R2dbcDatabase? = (
            currentTransaction?.db
                ?: db
                ?: TransactionManager.currentDefaultDatabase.get()
            ) as? R2dbcDatabase
        val manager = (
            currentDatabase?.transactionManager
                ?: TransactionManager.manager
            ) as R2dbcTransactionManager

        val tx = lazy(LazyThreadSafetyMode.NONE) {
            currentTransaction ?: manager.newTransaction(transactionIsolation ?: manager.defaultIsolationLevel)
        }

        val element = TransactionCoroutineElement(tx, manager)

        val newContext = context ?: coroutineContext

        return TransactionScope(tx, newContext + element).body()
    }

    val sameTransaction = currentScope?.holdsSameTransaction(currentTransaction) == true
    val sameContext = context == coroutineContext
    return when {
        currentScope == null -> newScope(currentTransaction)
        sameTransaction && sameContext -> currentScope.body()
        else -> newScope(currentTransaction)
    }
}

private fun R2dbcTransaction.resetIfClosed(): R2dbcTransaction {
    return if (connection.isClosed) {
        // Repetition attempts will throw org.h2.jdbc.JdbcSQLException: The object is already closed
        // unless the transaction is reset before every attempt (after the 1st failed attempt)
        val currentManager = db.transactionManager as R2dbcTransactionManager
        currentManager.bindTransactionToThread(this)
        TransactionManager.resetCurrent(currentManager)
        currentManager.newTransaction(transactionIsolation, readOnly, outerTransaction)
    } else {
        this
    }
}

@Suppress("CyclomaticComplexMethod")
private fun <T> TransactionScope.suspendedTransactionAsyncInternal(
    shouldCommit: Boolean,
    statement: suspend R2dbcTransaction.() -> T
): Deferred<T> = async {
    var attempts = 0
    var intermediateDelay: Long = 0
    var retryInterval: Long? = null

    var answer: T
    while (true) {
        val transaction = if (attempts == 0) tx.value else tx.value.resetIfClosed()

        @Suppress("TooGenericExceptionCaught")
        try {
            answer = transaction.statement().apply {
                if (shouldCommit) transaction.commit()
            }
            break
        } catch (cause: SQLException) {
            handleSQLException(cause, transaction, attempts)
            attempts++
            if (attempts >= transaction.maxAttempts) {
                throw cause
            }

            if (retryInterval == null) {
                retryInterval = transaction.getRetryInterval()
                intermediateDelay = transaction.minRetryDelay
            }
            // set delay value with an exponential backoff time period
            val retryDelay = when {
                transaction.minRetryDelay < transaction.maxRetryDelay -> {
                    intermediateDelay += retryInterval * attempts
                    ThreadLocalRandom.current().nextLong(intermediateDelay, intermediateDelay + retryInterval)
                }
                transaction.minRetryDelay == transaction.maxRetryDelay -> transaction.minRetryDelay
                else -> 0
            }
            exposedLogger.warn("Wait $retryDelay milliseconds before retrying")
            try {
                delay(retryDelay)
            } catch (cause: InterruptedException) {
                // Do nothing
            }
        } catch (cause: Throwable) {
            val currentStatement = transaction.currentStatement
            transaction.rollbackLoggingException {
                exposedLogger.warn("Transaction rollback failed: ${it.message}. Statement: $currentStatement", it)
            }
            throw cause
        } finally {
            if (shouldCommit) transaction.closeAsync()
        }
    }
    answer
}

// how to handle these internals, alongside deprecations, without duplicating (as done) or propagating opt-ins...

private fun R2dbcTransaction.getRetryInterval(): Long = if (maxAttempts > 0) {
    maxOf((maxRetryDelay - minRetryDelay) / (maxAttempts + 1), 1)
} else {
    0
}

@OptIn(InternalApi::class)
private fun handleSQLException(cause: SQLException, transaction: R2dbcTransaction, attempts: Int) {
    val exposedSQLException = cause as? ExposedSQLException
    val queriesToLog = exposedSQLException?.causedByQueries()?.joinToString(";\n") ?: "${transaction.currentStatement}"
    val message = "Transaction attempt #$attempts failed: ${cause.message}. Statement(s): $queriesToLog"
    exposedSQLException?.contexts?.forEach {
        transaction.loggingInterceptors.forEach { logger ->
            logger.log(it, transaction)
        }
    }
    exposedLogger.warn(message, cause)
    transaction.rollbackLoggingException {
        exposedLogger.warn("Transaction rollback failed: ${it.message}. See previous log line for statement", it)
    }
}

private fun closeStatementsAndConnection(transaction: R2dbcTransaction) {
    val currentStatement = transaction.currentStatement
    @Suppress("TooGenericExceptionCaught")
    try {
        currentStatement?.let {
            it.closeIfPossible()
            transaction.currentStatement = null
        }
        transaction.closeExecutedStatements()
    } catch (cause: Exception) {
        exposedLogger.warn("Statements close failed", cause)
    }
    transaction.closeLoggingException {
        exposedLogger.warn("Transaction close failed: ${it.message}. Statement: $currentStatement", it)
    }
}

@Suppress("TooGenericExceptionCaught")
private fun TransactionInterface.rollbackLoggingException(log: (Exception) -> Unit) {
    try {
        rollback()
    } catch (e: Exception) {
        log(e)
    }
}

@Suppress("TooGenericExceptionCaught")
private inline fun TransactionInterface.closeLoggingException(log: (Exception) -> Unit) {
    try {
        close()
    } catch (e: Exception) {
        log(e)
    }
}

internal class TransactionContext(val manager: R2dbcTransactionManager?, val transaction: R2dbcTransaction?)

internal class TransactionScope(
    val tx: Lazy<R2dbcTransaction>,
    parent: CoroutineContext
) : CoroutineScope, CoroutineContext.Element {
    private val baseScope = CoroutineScope(parent)
    override val coroutineContext get() = baseScope.coroutineContext + this
    override val key = Companion

    fun holdsSameTransaction(transaction: R2dbcTransaction?) =
        transaction != null && tx.isInitialized() && tx.value == transaction
    companion object : CoroutineContext.Key<TransactionScope>
}

internal class TransactionCoroutineElement(
    private val newTransaction: Lazy<R2dbcTransaction>,
    val manager: R2dbcTransactionManager
) : ThreadContextElement<TransactionContext> {
    override val key: CoroutineContext.Key<TransactionCoroutineElement> = Companion

    override fun updateThreadContext(context: CoroutineContext): TransactionContext {
        val currentTransaction = TransactionManager.currentOrNull() as? R2dbcTransaction
        val currentManager = currentTransaction?.db?.transactionManager as? R2dbcTransactionManager
        manager.bindTransactionToThread(newTransaction.value)
        TransactionManager.resetCurrent(manager)
        return TransactionContext(currentManager, currentTransaction)
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: TransactionContext) {
        manager.bindTransactionToThread(oldState.transaction)
        TransactionManager.resetCurrent(oldState.manager)
    }

    companion object : CoroutineContext.Key<TransactionCoroutineElement>
}
