package moe.tachyon.shadowed.database

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import moe.tachyon.shadowed.dataClass.ChatId
import moe.tachyon.shadowed.dataClass.Message
import moe.tachyon.shadowed.dataClass.MessageType
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.utils.singleOrNull
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

class Messages: SqlDao<Messages.MessageTable>(MessageTable)
{
    object MessageTable: LongIdTable("messages")
    {
        val content = text("content")
        val type = enumerationByName<MessageType>("type", 20).default(MessageType.TEXT)
        val time = timestamp("time")
        val chat = reference("chat", Chats.ChatTable, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE).index()
        val sender = reference("sender", Users.UserTable, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE).index()
        val isRead = bool("is_read").default(false)
    }

    suspend fun addChatMessage(
        content: String,
        type: MessageType,
        chatId: ChatId,
        senderId: UserId
    ): Long = query()
    {
        table.insertAndGetId()
        {
            it[table.content] = content
            it[table.type] = type
            it[table.chat] = chatId
            it[table.sender] = senderId
            it[table.isRead] = false
            it[table.time] = Clock.System.now()
        }.value
    }
    
    suspend fun getChatMessages(
        chatId: ChatId,
        begin: Long,
        count: Int
    ): List<Message> = query()
    {
        val usersTable = getKoin().get<Users>().table
        (table innerJoin usersTable)
            .selectAll()
            .where { table.chat eq chatId }
            .orderBy(table.time to SortOrder.DESC)
            .limit(count)
            .offset(start = begin)
            .map {
                Message(
                    id = it[table.id].value,
                    content = it[table.content],
                    type = it[table.type],
                    chatId = it[table.chat].value,
                    senderId = it[table.sender].value,
                    senderName = it[usersTable.username],
                    time = it[table.time].toEpochMilliseconds(),
                    isRead = it[table.isRead],
                )
            }
            .reversed()
    }

    suspend fun updateMessage(
        messageId: Long,
        newContent: String?
    ): Unit = query()
    {
        if (newContent == null) table.deleteWhere { table.id eq messageId }
        else table.update({ table.id eq messageId })
        {
            it[table.content] = newContent
        }
    }

    suspend fun getMessage(messageId: Long): Message? = query()
    {
        val usersTable = getKoin().get<Users>().table
        (table innerJoin usersTable)
            .selectAll()
            .where { table.id eq messageId }
            .singleOrNull()
            ?.let {
                Message(
                    id = it[table.id].value,
                    content = it[table.content],
                    type = it[table.type],
                    chatId = it[table.chat].value,
                    senderId = it[table.sender].value,
                    senderName = it[usersTable.username],
                    time = it[table.time].toEpochMilliseconds(),
                    isRead = it[table.isRead],
                )
            }
    }

    /**
     * Data class for moment messages with owner info and decryption key
     */
    data class MomentMessage(
        val messageId: Long,
        val content: String,
        val type: MessageType,
        val ownerId: Int,
        val ownerName: String,
        val time: Long,
        val key: String,
    )

    /**
     * Get all moment messages visible to a user using JOIN query
     * Joins chat_members -> chats (where is_moment=true) -> messages -> users (owner)
     */
    suspend fun getMomentMessagesForUser(
        userId: UserId,
        offset: Long,
        count: Int
    ): List<MomentMessage> = query()
    {
        val chatsTable = getKoin().get<Chats>().table
        val chatMembersTable = getKoin().get<ChatMembers>().table
        val usersTable = getKoin().get<Users>().table

        chatMembersTable
            .innerJoin(chatsTable, { chatMembersTable.chat }, { chatsTable.id })
            .innerJoin(table, { chatsTable.id }, { table.chat })
            .innerJoin(usersTable, { chatsTable.owner }, { usersTable.id })
            .selectAll()
            .where { (chatMembersTable.user eq userId) and (chatsTable.isMoment eq true) }
            .orderBy(table.time to SortOrder.DESC)
            .limit(count)
            .offset(start = offset)
            .map {
                MomentMessage(
                    messageId = it[table.id].value,
                    content = it[table.content],
                    type = it[table.type],
                    ownerId = it[chatsTable.owner].value.value,
                    ownerName = it[usersTable.username],
                    time = it[table.time].toEpochMilliseconds(),
                    key = it[chatMembersTable.key],
                )
            }
    }

    suspend fun getTopActiveUsers(after: Instant): List<Pair<String, Long>> = query()
    {
        val userTable = getKoin().get<Users>().table
        userTable.join(table, JoinType.INNER, userTable.id, table.sender)
            .select(table.id.count(), userTable.username)
            .where { table.time greater after }
            .groupBy(table.sender, userTable.username)
            .orderBy(table.id.count(), SortOrder.DESC)
            .limit(10)
            .map()
            {
                it[userTable.username] to it[table.id.count()]
            }
    }

    suspend fun getTopActiveChats(after: Instant): List<Pair<String, Long>> = query()
    {
        val chatTable = getKoin().get<Chats>().table
        chatTable.join(table, JoinType.INNER, chatTable.id, table.chat)
            .select(table.id.count(), chatTable.name)
            .where { (table.time greater after) and (chatTable.private eq false) }
            .groupBy(table.chat, chatTable.name)
            .orderBy(table.id.count(), SortOrder.DESC)
            .limit(10)
            .map()
            {
                it[chatTable.name] to it[table.id.count()]
            }
    }
}