package moe.tachyon.quiz.database

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import moe.tachyon.quiz.dataClass.UserId
import moe.tachyon.quiz.database.utils.singleOrNull
import moe.tachyon.quiz.JwtAuth
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class CustomUsers: SqlDao<CustomUsers.CustomUserTable>(CustomUserTable)
{
    object CustomUserTable: IdTable<UserId>("custom_users")
    {
        override val id = userId("id").autoIncrement().entityId()
        val name = varchar("name", 255).index()
        val password = text("password")
        val lastPasswordChange = timestamp("last_password_change").defaultExpression(CurrentTimestamp)
        override val primaryKey = PrimaryKey(id)
    }

    suspend fun createUser(name: String, password: String): UserId = query()
    {
        insertAndGetId()
        {
            it[table.name] = name
            it[table.password] = JwtAuth.encryptPassword(password)
            it[table.lastPasswordChange] = Clock.System.now()
        }.value
    }

    suspend fun changePassword(userId: UserId, newPassword: String): Boolean = query()
    {
        update({ table.id eq userId })
        {
            it[table.password] = JwtAuth.encryptPassword(newPassword)
            it[table.lastPasswordChange] = Instant.fromEpochSeconds(Clock.System.now().epochSeconds, 0)
        } > 0
    }

    data class CustomUser(
        val id: UserId,
        val name: String,
        val password: String,
        val lastPasswordChange: Instant,
    )

    suspend fun getUser(id: UserId): CustomUser? = query()
    {
        selectAll().where { table.id eq id }
            .singleOrNull()
            ?.let {
                CustomUser(
                    id = it[table.id].value,
                    name = it[table.name],
                    password = it[table.password],
                    lastPasswordChange = it[table.lastPasswordChange],
                )
            }
    }
}