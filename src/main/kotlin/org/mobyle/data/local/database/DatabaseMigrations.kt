package org.mobyle.data.local.database

import org.flywaydb.core.Flyway
import javax.sql.DataSource

object DatabaseMigrations {
    /**
     * Applies the versioned migrations in `src/main/resources/db/migration`.
     *
     * A legacy database (created by Exposed, without flyway_schema_history) is
     * baselined at version 0, so the idempotent V1__baseline still runs and fills
     * in whatever that older schema is missing.
     */
    fun migrate(dataSource: DataSource) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate()
    }
}
