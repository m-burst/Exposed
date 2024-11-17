# Migrating to 1.0 - WORK IN PROGRESS

This is a temporary file to document all user-facing API changes so that none are missed when an actual migration guide
must be presented.

**Maintainer Notes:**
* All previously `private` or `internal` objects whose visibility had to be weakened have been marked with `@InternalApi` for potential refactoring.
* The introduction of `IStatementBuilder` also allows for the implementation of non-hacky SQL generation, for example:
```kotlin
val sql: String = StatementBuilder.toSql {
    MyTable.insert { }
}
// or more directly
val sql: String = sqlString {
    MyTable.insert { }
}
```
* The introduction of `IStatementBuilder` also essentially reverts the entire [inline DSL PR](https://github.com/JetBrains/Exposed/pull/2272).

**API Major changes:**
* Interface `DatabaseDialect`:
    * Deprecate `resolveRefOptionFromJdbc()` in favor of `resolveReferenceOption()`
* Abstract Class `ExposedDatabaseMetadata`:
    * Multiple `protected abstract` members added. Should this not be `abstract` and have values defined instead, if possible?
    * Multiple `abstract` members have had their non-common code extracted, resulting in modifier being switched to `open`.
    * Multiple `protected` members have been added, all marked with `@InternalApi`.
* Function `ExposedDatabaseMetadata.tableConstraints()`:
    * As part of the API, this is invoked in only 1 place: `VendorDialect.fillConstraintCacheForTables()` which is called by `columnConstraints()`.
      MySql used to have a complex override for the former, which performed similar functionality as `tableConstraints()` but using plain SQL.
      In spite of this, `tableConstraints()` was being used directly for testing leading to assertions that would fail if the MySQL override was invoked.
      `tableConstraints()` now holds implementation for all db and `fillConstraintCacheForTables()` has no vendor-specific override so, if users
      are doing the same as in tests, there may be some unexpected behavior with MySQL-like dialects.
* Function `explain()`:
    * Parameter `body` has a different type: `Transaction.() -> Any?` -> `IStatementBuilder.() -> Statement<*>`. This avoids need for `Transaction`
      members but means that invalid statements (which used to throw exception before) will now no longer compile.
* Function `ignore()`:
    * Parameter `body` has a different type: `T.(InsertStatement<Number>) -> Unit` -> `T.(UpdateBuilder<*>) -> Unit`.
      This allows `IStatementBuilder` to be used and follows the existing signature of `insertIgnore()`.
* Function `ignoreAndGetId()`:
    * Parameter `body` has a different type: `T.(InsertStatement<EntityID<Key>>) -> Unit` -> `T.(UpdateBuilder<*>) -> Unit`.
      This allows `IStatementBuilder` to be used and follows the existing signature of `insertIgnoreAndGetId()`.
* Functions `replace(query)`, `insert(query)`, `insertIgnore(query)`:
    * Parameter `columns` has a different type: `List<Column<*>>?` with a default `null` argument instead of a filtered list.
      This allows `IStatementBuilder` to be used and common logic to be moved into the interface as private.
* DSL functions are no longer inline, so that a common `StatementBuilder` could be used.  
