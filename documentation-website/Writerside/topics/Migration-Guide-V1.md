# Migrating to 1.0 - WORK IN PROGRESS

This is a temporary file to document all user-facing API changes so that none are missed when an actual migration guide
must be presented.

**Maintainer Notes:**
* All previously `private` or `internal` objects whose visibility had to be weakened have been marked with `@InternalApi` for potential refactoring.

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
