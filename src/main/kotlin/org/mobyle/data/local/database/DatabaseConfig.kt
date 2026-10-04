package org.mobyle.data.local.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseConfig {
    fun init() {
        val databaseUrl = System.getenv("DATABASE_URL")?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(
                "DATABASE_URL environment variable is not set. Example: postgresql://moovie:moovie@localhost:5432/moovie"
            )

        val parsed = parseUrl(databaseUrl)

        val config = HikariConfig().apply {
            jdbcUrl = parsed.jdbcUrl
            username = parsed.username
            password = parsed.password
            maximumPoolSize = 10
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            driverClassName = "org.postgresql.Driver"
            addDataSourceProperty("sslmode", "disable")
            validate()
        }

        Database.connect(HikariDataSource(config))

        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                UsersTable,
                MoviesTable,
                GenresTable,
                MovieGenresTable,
                PeopleTable,
                MovieCastTable,
                MovieSimilarsTable,
                MovieWatchProvidersTable,
                UserMoviesTable,
                UserFollowsTable,
                UserListsTable,
                UserListItemsTable,
                TagsTable,
                MovieTagsTable,
                ArticlesTable,
                TokenBlocklistTable,
                RefreshTokensTable,
                MovieLikesTable,
                ReviewLikesTable
            )
        }
    }

    private data class DbConnectionInfo(val jdbcUrl: String, val username: String?, val password: String?)

    private fun parseUrl(url: String): DbConnectionInfo {
        if (url.startsWith("jdbc:")) return DbConnectionInfo(url, null, null)

        val uri = java.net.URI(url)
        val userInfo = uri.userInfo?.split(":", limit = 2)
        val jdbcUrl = "jdbc:postgresql://${uri.host}:${uri.port}${uri.path}" +
            (uri.query?.let { "?$it" } ?: "")

        return DbConnectionInfo(
            jdbcUrl = jdbcUrl,
            username = userInfo?.getOrNull(0),
            password = userInfo?.getOrNull(1)
        )
    }
}
