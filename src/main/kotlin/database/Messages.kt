package moe.tachyon.shadowed.database

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import moe.tachyon.shadowed.dataClass.ChatId
import moe.tachyon.shadowed.dataClass.Message
import moe.tachyon.shadowed.dataClass.MessageType
import moe.tachyon.shadowed.dataClass.ReplyInfo
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.utils.CustomExpression
import moe.tachyon.shadowed.database.utils.CustomExpressionWithColumnType
import moe.tachyon.shadowed.database.utils.singleOrNull
import moe.tachyon.shadowed.logger.ShadowedLogger
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.kotlin.datetime.KotlinInstantColumnType
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

class Messages: SqlDao<Messages.MessageTable>(MessageTable)
{
    object MessageTable: LongIdTable("messages")
    {
        val content = text("content")
        val type = enumerationByName<MessageType>("type", 20).default(MessageType.TEXT)
        val time = timestamp("time").index()
        val chat = reference("chat", Chats.ChatTable, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE).index()
        val sender = reference("sender", Users.UserTable, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE).index()
        val replyTo = reference("reply_to", MessageTable, onDelete = ReferenceOption.SET_NULL, onUpdate = ReferenceOption.CASCADE).nullable().index()
        val readAt = timestamp("read_at").nullable().index().default(null)
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
            it[table.readAt] = null
            it[table.replyTo] = null
            it[table.time] = Clock.System.now()
        }.value
    }

    /**
     * Add a message that replies to another message
     * @return The ID of the newly created message
     */
    suspend fun addReplyMessage(
        content: String,
        type: MessageType,
        chatId: ChatId,
        senderId: UserId,
        replyToMessageId: Long
    ): Long = query()
    {
        table.insertAndGetId()
        {
            it[table.content] = content
            it[table.type] = type
            it[table.chat] = chatId
            it[table.sender] = senderId
            it[table.readAt] = null
            it[table.replyTo] = replyToMessageId
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
        val replyTable = table.alias("reply_table")
        val replyUsersTable = Users.UserTable.alias("reply_users_table")

        table
            .innerJoin(usersTable, { this@Messages.table.sender }, { usersTable.id })
            .leftJoin(replyTable, { this@Messages.table.replyTo }, { replyTable[this@Messages.table.id] })
            .leftJoin(replyUsersTable, { replyTable[MessageTable.sender] }, { replyUsersTable[Users.UserTable.id] })
            .selectAll()
            .where { table.chat eq chatId }
            .orderBy(table.time to SortOrder.DESC)
            .limit(count)
            .offset(start = begin)
            .map {
                val replyInfo = it.getOrNull(replyTable[MessageTable.id])?.let { replyId ->
                    ReplyInfo(
                        messageId = replyId.value,
                        content = it[replyTable[MessageTable.content]],
                        senderId = it[replyTable[MessageTable.sender]].value,
                        senderName = it[replyUsersTable[Users.UserTable.username]],
                        type = it[replyTable[MessageTable.type]]
                    )
                }

                Message(
                    id = it[table.id].value,
                    content = it[table.content],
                    type = it[table.type],
                    chatId = it[table.chat].value,
                    senderId = it[table.sender].value,
                    senderName = it[usersTable.username],
                    time = it[table.time].toEpochMilliseconds(),
                    readAt = it[table.readAt]?.toEpochMilliseconds(),
                    replyTo = replyInfo,
                    senderIsDonor = it[usersTable.isDonor]
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
        val replyTable = table.alias("reply_table")
        val replyUsersTable = Users.UserTable.alias("reply_users_table")

        table
            .innerJoin(usersTable, { this@Messages.table.sender }, { usersTable.id })
            .leftJoin(replyTable, { this@Messages.table.replyTo }, { replyTable[this@Messages.table.id] })
            .leftJoin(replyUsersTable, { replyTable[MessageTable.sender] }, { replyUsersTable[Users.UserTable.id] })
            .selectAll()
            .where { table.id eq messageId }
            .singleOrNull()
            ?.let {
                val replyInfo = it.getOrNull(replyTable[MessageTable.id])?.let { replyId ->
                    ReplyInfo(
                        messageId = replyId.value,
                        content = it[replyTable[MessageTable.content]],
                        senderId = it[replyTable[MessageTable.sender]].value,
                        senderName = it[replyUsersTable[Users.UserTable.username]],
                        type = it[replyTable[MessageTable.type]]
                    )
                }

                Message(
                    id = it[table.id].value,
                    content = it[table.content],
                    type = it[table.type],
                    chatId = it[table.chat].value,
                    senderId = it[table.sender].value,
                    senderName = it[usersTable.username],
                    time = it[table.time].toEpochMilliseconds(),
                    readAt = it[table.readAt]?.toEpochMilliseconds(),
                    replyTo = replyInfo,
                    senderIsDonor = it[usersTable.isDonor]
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
        val ownerIsDonor: Boolean = false
    )

    /**
     * Get all moment messages visible to a user using JOIN query
     * Joins chat_members -> chats (where is_moment=true) -> messages -> users (owner)
     * Only returns original moments (replyTo is null), not comments
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
            .andWhere { chatMembersTable.user eq userId }
            .andWhere { chatsTable.isMoment eq true }
            .andWhere { table.replyTo.isNull() }
            .andWhere { table.sender eq chatsTable.owner }
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
                    ownerIsDonor = it[usersTable.isDonor]
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

    // ====== Burn after read methods ======

    /**
     * Mark a message as read and return the updated message
     * @return The updated message, or null if not found
     */
    suspend fun markAsRead(messageId: Long): Message? = query()
    {
        val now = Clock.System.now()
        table.update({ (table.id eq messageId) and (table.readAt.isNull()) })
        {
            it[readAt] = now
        }
        // Return the updated message
        null // Will be fetched by getMessage after this
    }

    /**
     * Data class for expired message info (minimal data needed for deletion and notification)
     */
    data class ExpiredMessageInfo(
        val messageId: Long,
        val chatId: ChatId,
    )

    /**
     * Get expired message IDs that should be deleted (readAt + burnTime < now)
     * Only for private chats with burn time enabled
     * Uses SQL-level time comparison for efficiency
     */
    suspend fun getExpiredMessageIds(): List<ExpiredMessageInfo> = query()
    {
        val chatTable = getKoin().get<Chats>().table
        table
            .innerJoin(chatTable, { this@Messages.table.chat }, { chatTable.id })
            .select(table.id, table.chat)
            .andWhere { chatTable.private eq true }
            .andWhere { chatTable.burnTime.isNotNull() }
            .andWhere { table.readAt.isNotNull() }
            .andWhere { CustomExpressionWithColumnType("${table.readAt.name} + ${chatTable.burnTime.name} * INTERVAL '1 millisecond'", KotlinInstantColumnType()) less Clock.System.now() }
            .map { row ->
                ExpiredMessageInfo(
                    messageId = row[table.id].value,
                    chatId = row[table.chat].value,
                )
            }
    }

    /**
     * Delete a message by ID
     */
    suspend fun deleteMessage(messageId: Long): Unit = query()
    {
        table.deleteWhere { table.id eq messageId }
    }

    /**
     * Delete all messages in a chat
     */
    suspend fun deleteChatMessages(chatId: ChatId): Unit = query()
    {
        table.deleteWhere { table.chat eq chatId }
    }

    /**
     * Get all message IDs that have files (IMAGE, VIDEO, FILE) in a chat
     * This is a lightweight query that only fetches IDs and types without any JOINs
     */
    suspend fun getFileMessageIds(chatId: ChatId): List<Long> = query()
    {
        table
            .select(table.id, table.type)
            .where { (table.chat eq chatId) and (table.type inList listOf(MessageType.IMAGE, MessageType.VIDEO, MessageType.FILE)) }
            .map { it[table.id].value }
    }

    /**
     * Get all comments for a specific moment (messages that reply to the moment message)
     */
    suspend fun getMomentComments(momentMessageId: Long): List<Message> = query()
    {
        val usersTable = getKoin().get<Users>().table
        val replyTable = table.alias("reply_table")
        val replyUsersTable = Users.UserTable.alias("reply_users_table")

        table
            .innerJoin(usersTable, { this@Messages.table.sender }, { usersTable.id })
            .leftJoin(replyTable, { this@Messages.table.replyTo }, { replyTable[this@Messages.table.id] })
            .leftJoin(replyUsersTable, { replyTable[MessageTable.sender] }, { replyUsersTable[Users.UserTable.id] })
            .selectAll()
            .where { table.replyTo eq momentMessageId }
            .orderBy(table.time to SortOrder.ASC)
            .map {
                val replyInfo = it.getOrNull(replyTable[MessageTable.id])?.let { replyId ->
                    ReplyInfo(
                        messageId = replyId.value,
                        content = it[replyTable[MessageTable.content]],
                        senderId = it[replyTable[MessageTable.sender]].value,
                        senderName = it[replyUsersTable[Users.UserTable.username]],
                        type = it[replyTable[MessageTable.type]]
                    )
                }

                Message(
                    id = it[table.id].value,
                    content = it[table.content],
                    type = it[table.type],
                    chatId = it[table.chat].value,
                    senderId = it[table.sender].value,
                    senderName = it[usersTable.username],
                    time = it[table.time].toEpochMilliseconds(),
                    readAt = it[table.readAt]?.toEpochMilliseconds(),
                    replyTo = replyInfo,
                    senderIsDonor = it[usersTable.isDonor]
                )
            }
    }
}