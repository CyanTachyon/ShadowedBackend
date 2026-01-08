package moe.tachyon.shadowed.database

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.ListSerializer
import moe.tachyon.shadowed.dataClass.ChatId
import moe.tachyon.shadowed.dataClass.Message
import moe.tachyon.shadowed.dataClass.MessageType
import moe.tachyon.shadowed.dataClass.Reaction
import moe.tachyon.shadowed.dataClass.ReplyInfo
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.utils.singleOrNull
import moe.tachyon.shadowed.dataJson
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.json.jsonb
import org.koin.core.component.inject

class Messages: SqlDao<Messages.MessageTable>(MessageTable)
{
    private val users by inject<Users>()
    private val chats by inject<Chats>()
    private val chatMembers by inject<ChatMembers>()
    object MessageTable: LongIdTable("messages")
    {
        val content = text("content")
        val type = enumerationByName<MessageType>("type", 20).default(MessageType.TEXT)
        val time = timestamp("time").index()
        val chat = reference("chat", Chats.ChatTable, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE).index()
        val sender = reference("sender", Users.UserTable, onDelete = ReferenceOption.SET_NULL, onUpdate = ReferenceOption.CASCADE).nullable().index()
        val replyTo = reference("reply_to", MessageTable, onDelete = ReferenceOption.SET_NULL, onUpdate = ReferenceOption.CASCADE).nullable().index()
        val readAt = timestamp("read_at").nullable().index().default(null)
        val burn = long("burn").nullable().index().default(null)
        val reactions = jsonb("reactions", dataJson, ListSerializer(Reaction.serializer())).nullable().default(null)
    }

    suspend fun addChatMessage(
        content: String,
        type: MessageType,
        chatId: ChatId,
        senderId: UserId,
        burnTime: Long?,
        replyTo: Long?,
    ): Long = query()
    {
        table.insertAndGetId()
        {
            it[table.content] = content
            it[table.type] = type
            it[table.chat] = chatId
            it[table.sender] = senderId
            it[table.replyTo] = replyTo
            it[table.burn] = burnTime
            it[table.time] = Clock.System.now()
        }.value
    }

    suspend fun addSystemMessage(
        content: String,
        chatId: ChatId,
    ): Long = query()
    {
        table.insertAndGetId()
        {
            it[table.content] = content
            it[table.type] = MessageType.TEXT
            it[table.chat] = chatId
            it[table.sender] = null
            it[table.replyTo] = null
            it[table.burn] = null
            it[table.time] = Clock.System.now()
        }.value
    }

    /**
     * Toggle a user's reaction on a message
     * If the user has already reacted with this emoji, remove it
     * If the user has reacted with a different emoji, replace it
     * If the user hasn't reacted with any emoji, add this one

     */
    suspend fun toggleReaction(
        messageId: Long,
        userId: UserId,
        emoji: String
    ): Unit = query()
    {
        // First, get current reactions
        val currentReactions = table
            .select(table.reactions)
            .where { table.id eq messageId }
            .singleOrNull()
            ?.get(table.reactions) ?: emptyList()

        // Find which emoji current user has reacted to, if any
        val userCurrentReactionIndex = currentReactions.indexOfFirst { userId in it.userIds }
        val newReactions = currentReactions.toMutableList()

        // Check if user is toggling the same emoji they already have
        val userHasThisEmoji = userCurrentReactionIndex >= 0 && currentReactions[userCurrentReactionIndex].emoji == emoji

        if (userHasThisEmoji)
        {
            // User already reacted with this emoji, remove it
            val reaction = newReactions[userCurrentReactionIndex]
            newReactions[userCurrentReactionIndex] = reaction.copy(
                userIds = reaction.userIds.filter { it != userId }
            )
            // Remove reaction if no users left
            if (newReactions[userCurrentReactionIndex].userIds.isEmpty())
            {
                newReactions.removeAt(userCurrentReactionIndex)
            }
        }
        else
        {
            // Check if user reacted with any other emoji
            val userCurrentReactionIndex2 = newReactions.indexOfFirst { userId in it.userIds }
            if (userCurrentReactionIndex2 >= 0)
            {
                // User reacted with different emoji, remove it first
                val reaction = newReactions[userCurrentReactionIndex2]
                newReactions[userCurrentReactionIndex2] = reaction.copy(
                    userIds = reaction.userIds.filter { it != userId }
                )
                if (newReactions[userCurrentReactionIndex2].userIds.isEmpty())
                {
                    newReactions.removeAt(userCurrentReactionIndex2)
                }
            }

            // Add or create the new reaction
            val newReactionIndex = newReactions.indexOfFirst { it.emoji == emoji }
            if (newReactionIndex >= 0)
            {
                val reaction = newReactions[newReactionIndex]
                newReactions[newReactionIndex] = reaction.copy(
                    userIds = reaction.userIds + userId
                )
            }
            else
            {
                newReactions.add(Reaction(emoji = emoji, userIds = listOf(userId)))
            }
        }

        // Update the message with new reactions
        table.update({ table.id eq messageId })
        {
            it[table.reactions] = newReactions
        }

    }
    
    suspend fun getChatMessages(
        chatId: ChatId,
        begin: Long,
        count: Int
    ): List<Message> = query()
    {
        val usersTable = users.table
        val replyTable = table.alias("reply_table")
        val replyUsersTable = users.table.alias("reply_users_table")

        table
            .leftJoin(usersTable, { this@Messages.table.sender }, { usersTable.id })
            .leftJoin(replyTable, { this@Messages.table.replyTo }, { replyTable[this@Messages.table.id] })
            .leftJoin(replyUsersTable, { replyTable[MessageTable.sender] }, { replyUsersTable[users.table.id] })
            .selectAll()
            .where { table.chat eq chatId }
            .orderBy(table.time to SortOrder.DESC)
            .limit(count)
            .offset(start = begin)
            .map {
                val replyInfo = it.getOrNull(replyTable[MessageTable.id])?.let()
                { replyId ->
                    val senderId = it[replyTable[MessageTable.sender]]?.value
                    val senderName = it[replyUsersTable[users.table.username]]
                    if (senderId == null) null
                    else ReplyInfo(
                        messageId = replyId.value,
                        content = it[replyTable[MessageTable.content]],
                        senderId = senderId,
                        senderName = senderName,
                        type = it[replyTable[MessageTable.type]]
                    )
                }

                Message(
                    id = it[table.id].value,
                    content = it[table.content],
                    type = it[table.type],
                    chatId = it[table.chat].value,
                    senderId = it[table.sender]?.value,
                    senderName = it[usersTable.username],
                    time = it[table.time].toEpochMilliseconds(),
                    readAt = it[table.readAt]?.toEpochMilliseconds(),
                    burn = it[table.burn],
                    replyTo = replyInfo,
                    senderIsDonor = (it.getOrNull(usersTable.donationAmount) ?: 0) > 0,
                    reactions = it[table.reactions] ?: emptyList()
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
        val usersTable = users.table
        val replyTable = table.alias("reply_table")
        val replyUsersTable = users.table.alias("reply_users_table")

        table
            .leftJoin(usersTable, { this@Messages.table.sender }, { usersTable.id })
            .leftJoin(replyTable, { this@Messages.table.replyTo }, { replyTable[this@Messages.table.id] })
            .leftJoin(replyUsersTable, { replyTable[MessageTable.sender] }, { replyUsersTable[users.table.id] })
            .selectAll()
            .where { table.id eq messageId }
            .singleOrNull()
            ?.let {
                val replyInfo = it.getOrNull(replyTable[MessageTable.id])?.let()
                { replyId ->
                    val senderId = it[replyTable[MessageTable.sender]]?.value
                    val senderName = it[replyUsersTable[users.table.username]]
                    if (senderId == null) null
                    else ReplyInfo(
                        messageId = replyId.value,
                        content = it[replyTable[MessageTable.content]],
                        senderId = senderId,
                        senderName = senderName,
                        type = it[replyTable[MessageTable.type]]
                    )
                }

                Message(
                    id = it[table.id].value,
                    content = it[table.content],
                    type = it[table.type],
                    chatId = it[table.chat].value,
                    senderId = it[table.sender]?.value,
                    senderName = it[usersTable.username],
                    time = it[table.time].toEpochMilliseconds(),
                    readAt = it[table.readAt]?.toEpochMilliseconds(),
                    burn = it[table.burn],
                    replyTo = replyInfo,
                    senderIsDonor = (it.getOrNull(usersTable.donationAmount) ?: 0) > 0,
                    reactions = it[table.reactions] ?: emptyList()
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
        val ownerIsDonor: Boolean = false,
        val reactions: List<Reaction> = emptyList()
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
        val chatsTable = chats.table
        val chatMembersTable = chatMembers.table
        val usersTable = users.table

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
                    ownerIsDonor = (it[usersTable.donationAmount] > 0),
                    reactions = it[table.reactions] ?: emptyList()
                )
            }
    }

    suspend fun getTopActiveUsers(after: Instant): List<Pair<String, Long>> = query()
    {
        val userTable = users.table
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
        val chatTable = chats.table
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

    suspend fun markAsRead(
        messageId: Long,
        time: Instant,
        burn: Instant?,
    ): Unit = query()
    {
        table.update({ (table.id eq messageId) and (table.readAt.isNull()) })
        {
            it[readAt] = time
            it[table.burn] = burn?.toEpochMilliseconds()
        }
    }

    suspend fun getExpiredMessageIds(): List<Pair<Long, ChatId>> = query()
    {
        table.select(table.id, table.chat)
            .andWhere { table.readAt.isNotNull() }
            .andWhere { table.burn lessEq Clock.System.now().toEpochMilliseconds() }
            .map { row -> row[table.id].value to row[table.chat].value }
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
        val usersTable = users.table
        val replyTable = table.alias("reply_table")
        val replyUsersTable = users.table.alias("reply_users_table")

        table
            .leftJoin(usersTable, { this@Messages.table.sender }, { usersTable.id })
            .leftJoin(replyTable, { this@Messages.table.replyTo }, { replyTable[this@Messages.table.id] })
            .leftJoin(replyUsersTable, { replyTable[MessageTable.sender] }, { replyUsersTable[users.table.id] })
            .selectAll()
            .where { table.replyTo eq momentMessageId }
            .orderBy(table.time to SortOrder.ASC)
            .map {
                val replyInfo = it.getOrNull(replyTable[MessageTable.id])?.let()
                { replyId ->
                    val senderId = it[replyTable[MessageTable.sender]]?.value
                    val senderName = it[replyUsersTable[users.table.username]]
                    if (senderId == null) null
                    else ReplyInfo(
                        messageId = replyId.value,
                        content = it[replyTable[MessageTable.content]],
                        senderId = senderId,
                        senderName = senderName,
                        type = it[replyTable[MessageTable.type]]
                    )
                }

                Message(
                    id = it[table.id].value,
                    content = it[table.content],
                    type = it[table.type],
                    chatId = it[table.chat].value,
                    senderId = it[table.sender]?.value,
                    senderName = it[usersTable.username],
                    time = it[table.time].toEpochMilliseconds(),
                    readAt = it[table.readAt]?.toEpochMilliseconds(),
                    burn = it[table.burn],
                    replyTo = replyInfo,
                    senderIsDonor = (it.getOrNull(usersTable.donationAmount) ?: 0) > 0,
                    reactions = it[table.reactions] ?: emptyList()
                )
            }
    }
}