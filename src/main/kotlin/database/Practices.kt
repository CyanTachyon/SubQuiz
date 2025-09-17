package moe.tachyon.quiz.database

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.serializer
import moe.tachyon.quiz.dataClass.*
import moe.tachyon.quiz.dataClass.Slice
import moe.tachyon.quiz.database.utils.CustomExpression
import moe.tachyon.quiz.database.utils.asSlice
import moe.tachyon.quiz.database.utils.singleOrNull
import moe.tachyon.quiz.plugin.contentNegotiation.dataJson
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.koin.core.component.inject

class Practices: SqlDao<Practices.PracticeTable>(PracticeTable)
{
    object PracticeTable: IdTable<PracticeId>("practices")
    {
        override val id = practiceId("id").autoIncrement().entityId()
        val clazz = reference("class", Classes.ClassTable, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE).index()
        val name = varchar("name", 255).index()
        val description = text("description").index()
        val available = bool("available").default(false)
        val knowledgePoints = jsonb<List<KnowledgePointId>>("knowledge_points", dataJson, dataJson.serializersModule.serializer())
        val sectionCount = integer("section_count").default(0)
        val accuracy = double("accuracy").default(0.0)
        val dueDate = timestamp("due_date").nullable().default(null).index()
        override val primaryKey = PrimaryKey(id)
    }

    private val quizzes: Quizzes by inject()
    private val classMembers: ClassMembers by inject()
    private val permissions: Permissions by inject()
    private val classes: Classes by inject()
    private val users: Users by inject()

    private fun deserialize(row: ResultRow): Practice = Practice(
        id = row[table.id].value,
        clazz = row[table.clazz].value,
        name = row[table.name],
        description = row[table.description],
        available = row[table.available],
        knowledgePoints = row[table.knowledgePoints],
        sectionCount = row[table.sectionCount],
        accuracy = row[table.accuracy],
        dueDate = row[table.dueDate]?.toEpochMilliseconds(),
    )
    
    suspend fun getPractice(id: PracticeId): Practice? = query()
    {
        selectAll().where { table.id eq id }
            .singleOrNull()
            ?.let(::deserialize)
    }
    
    suspend fun getPractices(
        clazz: ClassId,
        availableOnly: Boolean,
        begin: Long,
        count: Int
    ): Slice<Practice> = query()
    {
        selectAll()
            .andWhere { table.clazz eq clazz }
            .apply()
            {
                if (availableOnly) andWhere { table.available eq true }
            }
            .asSlice(begin, count)
            .map(::deserialize)
    }
    
    suspend fun createPractice(practice: Practice): PracticeId = query()
    {
        PracticeTable.insertAndGetId()
        {
            it[clazz] = practice.clazz
            it[name] = practice.name
            it[description] = practice.description
            it[available] = practice.available
            it[knowledgePoints] = practice.knowledgePoints
            it[sectionCount] = practice.sectionCount
            it[accuracy] = practice.accuracy
            it[dueDate] = practice.dueDate?.let(Instant::fromEpochMilliseconds)
        }.value
    }
    
    suspend fun updatePractice(practice: Practice): Boolean = query()
    {
        PracticeTable.update({ PracticeTable.id eq practice.id })
        {
            it[clazz] = practice.clazz
            it[name] = practice.name
            it[description] = practice.description
            it[available] = practice.available
            it[knowledgePoints] = practice.knowledgePoints
            it[sectionCount] = practice.sectionCount
            it[accuracy] = practice.accuracy
            it[dueDate] = practice.dueDate?.let { Instant.fromEpochMilliseconds(it) }
        } > 0
    }

    suspend fun removePractice(id: PracticeId): Boolean = query()
    {
        PracticeTable.deleteWhere { PracticeTable.id eq id } > 0
    }

    suspend fun getUsersInPractice(practiceId: PracticeId): List<Pair<ClassMember, Double?>>? = query()
    {
        val practice = getPractice(practiceId) ?: return@query null
        classMembers.table
            .join(quizzes.table, JoinType.LEFT, additionalConstraint = {
                var op = quizzes.table.user eq classMembers.table.user
                op = op and (quizzes.table.finished eq true)
                op = op and (quizzes.table.practices eq practiceId)
                if (practice.dueDate != null)
                {
                    @Suppress("UNCHECKED_CAST")
                    val duration = (quizzes.table.duration * CustomExpression("interval '1 ms'")) as Expression<Instant>
                    val op1 = (quizzes.table.time + duration) lessEq Instant.fromEpochMilliseconds(practice.dueDate)
                    op = op and op1
                }
                op
            })
            .join(users.table, JoinType.LEFT) { classMembers.table.user eq users.table.id }
            .join(classes.table, JoinType.INNER) { classMembers.table.clazz eq classes.table.id } // 用户所在班级，为了获取备课组
            .join(permissions.table, JoinType.LEFT) { (permissions.table.user eq classMembers.table.user) and (permissions.table.group eq classes.table.group) } // 用户在该备课组的权限
            .select(classMembers.table.user, classMembers.table.realName, classMembers.table.studentId, quizzes.table.accuracy.max())
            .andWhere { classMembers.table.clazz eq practice.clazz }
            .andWhere { users.table.permission.isNull() or (users.table.permission lessEq Permission.NORMAL) } // 不是老师
            .andWhere { (permissions.table.permission.isNull()) or (permissions.table.permission lessEq Permission.NORMAL) } // 不是老师
            .groupBy(classMembers.table.user, classMembers.table.realName, classMembers.table.studentId)
            .map()
            {
                ClassMember(
                    user = it[classMembers.table.user]?.value,
                    seiue = SsoUserFull.Seiue(
                        studentId = it[classMembers.table.studentId].value,
                        realName = it[classMembers.table.realName].value,
                        archived = false,
                    )
                ) to it[quizzes.table.accuracy.max()]
            }
    }

    suspend fun getUnfinishedPractices(user: UserId): List<Practice> = query()
    {
        classMembers.table
            .join(classes.table, JoinType.INNER) { classMembers.table.clazz eq classes.table.id } // 用户所在班级，为了获取备课组
            .join(permissions.table, JoinType.LEFT) { (permissions.table.user eq user) and (permissions.table.group eq classes.table.group) } // 用户在该备课组的权限
            .join(table, JoinType.LEFT) { classMembers.table.clazz eq table.clazz } // 所有的练习
            .join(quizzes.table, JoinType.LEFT) { (quizzes.table.user eq user) and (quizzes.table.finished eq true) and (quizzes.table.practices eq table.id) and (quizzes.table.accuracy greaterEq table.accuracy) } // 用户完成且达标的测验
            .select(table.columns)
            .andWhere { classMembers.table.user eq user } // 当前用户
            .andWhere { table.available eq true } // 练习可用
            .andWhere { quizzes.table.id.isNull() } // 没有完成且达标的测验
            .andWhere { table.dueDate.isNull() or (table.dueDate greater Clock.System.now()) } // 没有截止日期或未截止
            .andWhere { (permissions.table.permission.isNull()) or (permissions.table.permission lessEq Permission.NORMAL) } // 不是老师
            .map(::deserialize)
    }
}