package org.jetbrains.exposed.sql.statements.r2dbc

import io.r2dbc.spi.Connection
import io.r2dbc.spi.Parameters
import io.r2dbc.spi.R2dbcType
import io.r2dbc.spi.Statement
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.reactive.collect
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.StatementResult
import org.jetbrains.exposed.sql.statements.api.PreparedStatementApi
import java.io.InputStream
import java.math.BigDecimal
import java.time.Duration
import java.util.*

/**
 * Class representing a precompiled SQL [statement] from the R2DBC SPI.
 */
class R2dbcPreparedStatementImpl(
    val statement: Statement,
    val connection: Connection,
    val wasGeneratedKeysRequested: Boolean
) : PreparedStatementApi {
    override val resultSet: R2dbcResult?
        get() = TODO("Not yet implemented")
    // needs suspend & returns Result (should this be wrapped by ResultApi instead of Row?)
//        get() {
//            val rs = if (wasGeneratedKeysRequested) {
//                statement.returnGeneratedValues().execute().awaitFirstOrNull()
//            } else {
//                statement.execute().awaitFirstOrNull()
//            }
//            return R2dbcResult(rs)
//        }

    override var fetchSize: Int?
        get() = TODO("Not yet implemented")
        set(value) { value?.let { statement.fetchSize(value) } }

    override var timeout: Int?
        get() = TODO("Not yet implemented")
        set(value) {
            value?.let { connection.setStatementTimeout(Duration.ofSeconds(value.toLong())) }
        }

    override fun addBatch() {
        statement.add() // is this technically correct?
    }

    override fun executeQuery(): R2dbcResult = TODO("Not yet implemented")
    // statement.execute().awaitFirst()

    override fun executeUpdate(): Int = runBlocking {
        flow {
            statement
                .execute()
                .collect { result ->
                    result.rowsUpdated.collect { count ->
                        emit(count.toInt())
                    }
                }
        }.single()
    }

    override fun executeMultiple(): List<StatementResult> = TODO("Not yet implemented")

    override fun set(index: Int, value: Any) {
        statement.bind(index - 1, value)
    }

    override fun setNull(index: Int, columnType: IColumnType<*>) {
        val columnValueType = when (columnType) {
            is EntityIDColumnType<*> -> {
                setNull(index, columnType.idColumn.columnType)
                return
            }
            is ColumnWithTransform<*, *> -> {
                setNull(index, columnType.delegate)
                return
            }
            is ByteColumnType -> Byte::class.java
            is UByteColumnType -> UByte::class.java
            is ShortColumnType -> Short::class.java
            is UShortColumnType -> UShort::class.java
            is IntegerColumnType -> Int::class.java
            is UIntegerColumnType -> UInt::class.java
            is LongColumnType -> Long::class.java
            is ULongColumnType -> ULong::class.java
            is FloatColumnType -> Float::class.java
            is DoubleColumnType -> Double::class.java
            is DecimalColumnType -> BigDecimal::class.java
            is BasicBinaryColumnType, is BlobColumnType -> ByteArray::class.java
            is UUIDColumnType -> UUID::class.java
            is CharacterColumnType -> Char::class.java
            is BooleanColumnType -> Boolean::class.java
            is ArrayColumnType<*> -> List::class.java
            else -> String::class.java
        }
        statement.bindNull(index - 1, columnValueType)
    }

    override fun setInputStream(index: Int, inputStream: InputStream, setAsBlobObject: Boolean) {
        if (setAsBlobObject) {
            statement.bind(index - 1, Parameters.`in`(R2dbcType.BLOB, inputStream.readBytes()))
        } else {
            statement.bind(index - 1, Parameters.`in`(R2dbcType.VARBINARY, inputStream.readBytes()))
        }
    }

    override fun setArray(index: Int, type: String, array: Array<*>) {
        statement.bind(index - 1, Parameters.`in`(R2dbcType.COLLECTION, array))
    }

    override fun closeIfPossible() {
        // do nothing
    }

    override fun executeBatch(): List<Int> = TODO("Not yet implemented")

    override fun cancel() {
        TODO("Not yet implemented")
    }
}
