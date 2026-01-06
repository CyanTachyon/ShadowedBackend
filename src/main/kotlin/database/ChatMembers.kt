package moe.tachyon.shadowed.database

import moe.tachyon.shadowed.dataClass.ChatId
import moe.tachyon.shadowed.dataClass.ChatMember
import moe.tachyon.shadowed.dataClass.User
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.utils.singleOrNull
import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.koin.core.component.get
import org.koin.core.component.inject

class ChatMembers: SqlDao<ChatMembers.ChatMemberTable>(ChatMemberTable)
{
    private val users by inject<Users>()
    private val chats by inject<Chats>()
    object ChatMemberTable: CompositeIdTable("chat_members")
    {
        val chat = reference(
            "chat",
            Chats.ChatTable,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        ).index()
        val user = reference(
            "user",
            Users.UserTable,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        ).index()
        val key = text("key")
        val unread = integer("unread").default(0)
        val doNotDisturb = bool("do_not_disturb").default(false)
        override val primaryKey: PrimaryKey = PrimaryKey(chat, user)

        init
        {
            addIdColumn(chat)
            addIdColumn(user)
        }
    }

    suspend fun addMember(chatId: ChatId, userId: UserId, key: String) = query()
    {
        table.insertIgnore()
        {
            it[chat] = chatId
            it[user] = userId
            it[this.key] = key
        }
    }

    suspend fun removeMember(chatId: ChatId, userId: UserId) = query()
    {
        table.deleteWhere { (table.chat eq chatId) and (table.user eq userId) }
    }

    suspend fun getUserChats(userId: UserId): List<ChatMember> = query()
    {
        val cm = table
        val chatTable = chats.table
        val userTable = users.table


        val membershipRows = (cm innerJoin chatTable)
            .select(
                cm.chat,
                cm.key,
                cm.unread,
                cm.doNotDisturb,
                chatTable.id,
                chatTable.name,
                chatTable.private,
                chatTable.burnTime,
                chatTable.lastChatAt,
            )
            .where { (cm.user eq userId) and (chatTable.isMoment eq false) }
            .toList()

        if (membershipRows.isEmpty()) return@query emptyList()

        val chatIds = membershipRows.map { it[chatTable.id].value }

        
        val othersByChat: Map<ChatId, List<Triple<UserId, String, Boolean>>> =
            (cm innerJoin userTable)
                .select(
                    cm.chat,
                    userTable.id,
                    userTable.username,
                    userTable.donationAmount,
                )
                .where { (cm.chat inList chatIds) and (cm.user neq userId) }
                .toList()
                .groupBy(
                    keySelector = { row -> row[cm.chat].value },
                    valueTransform = { row ->
                        val uid = row[userTable.id].value
                        val uname = row[userTable.username]
                        val isDonor = row[userTable.donationAmount] > 0
                        Triple(uid, uname, isDonor)
                    }
                )

        
        membershipRows
            .map { row ->
                val chatId = row[chatTable.id].value
                val isPrivate = row[chatTable.private]
                val chatName = row[chatTable.name]
                val burnTime = row[chatTable.burnTime]
                val lastAt = row[chatTable.lastChatAt].toEpochMilliseconds()

                val myKey = row[cm.key]
                val others = othersByChat[chatId].orEmpty()

                val parsedOtherNames = others.map { it.second }
                val parsedOtherIds = others.map { it.first.value }

                
                val otherUserIsDonor = if (isPrivate && others.size == 1) others[0].third else false

                val displayName =
                    if (isPrivate && parsedOtherNames.isNotEmpty())
                    {
                        parsedOtherNames.joinToString(", ")
                    }
                    else
                    {
                        chatName.ifBlank { parsedOtherNames.joinToString(", ") }
                    }

                ChatMember(
                    chatId = chatId,
                    name = displayName,
                    key = myKey,
                    parsedOtherNames = parsedOtherNames,
                    parsedOtherIds = parsedOtherIds,
                    isPrivate = isPrivate,
                    unreadCount = row[cm.unread],
                    doNotDisturb = row[cm.doNotDisturb],
                    burnTime = burnTime,
                    otherUserIsDonor = otherUserIsDonor
                ) to lastAt
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    
    suspend fun getMemberIds(chatId: ChatId): List<UserId> = query()
    {
        table.selectAll()
            .where { table.chat eq chatId }
            .map { it[table.user].value }
    }

    suspend fun getChatMembersDetailed(chatId: ChatId): List<User> = query()
    {
        val uTable = users.table
        (table innerJoin uTable)
            .selectAll()
            .where { table.chat eq chatId }
            .map { row ->
                User(
                    id = row[uTable.id].value,
                    username = row[uTable.username],
                    password = "",
                    publicKey = "",
                    privateKey = "",
                    signature = row[uTable.signature],
                    isDonor = row[uTable.donationAmount] > 0
                )
            }
    }

    suspend fun incrementUnread(chatId: ChatId, senderId: UserId) = query()
    {
        table.update({ (table.chat eq chatId) and (table.user neq senderId) })
        {
            it[unread] = unread + 1
        }
    }

    suspend fun resetUnread(chatId: ChatId, userId: UserId) = query()
    {
        table.update({ (table.chat eq chatId) and (table.user eq userId) })
        {
            it[unread] = 0
        }
    }

    suspend fun getUnreadCount(chatId: ChatId, userId: UserId): Int = query()
    {
        table.selectAll()
            .where { (table.chat eq chatId) and (table.user eq userId) }
            .map { it[unread] }
            .firstOrNull() ?: 0
    }

    suspend fun setDoNotDisturb(chatId: ChatId, userId: UserId, dnd: Boolean) = query()
    {
        table.update({ (table.chat eq chatId) and (table.user eq userId) })
        {
            it[doNotDisturb] = dnd
        } > 0
    }

    /**
     * Set the unread count to Int.MIN_VALUE for users who were @mentioned.
     * This negative value indicates the user was mentioned, and will still be negative
     * even after new messages increment the count.
     */
    suspend fun setAtMarker(chatId: ChatId, userId: UserId) = query()
    {
        table.update({ (table.chat eq chatId) and (table.user eq userId) })
        {
            it[unread] = Int.MIN_VALUE
        } > 0
    }

    // ====== Moment-related methods ======

    /**
     * Check if user is a member of a chat
     */
    suspend fun isMember(chatId: ChatId, userId: UserId): Boolean = query()
    {
        table.selectAll().where { (table.chat eq chatId) and (table.user eq userId) }.any()
    }

    /**
     * Get the user's key for a specific chat
     */
    suspend fun getMemberKey(chatId: ChatId, userId: UserId): String? = query()
    {
        table.selectAll()
            .where { (table.chat eq chatId) and (table.user eq userId) }
            .singleOrNull()?.get(table.key)
    }
}
