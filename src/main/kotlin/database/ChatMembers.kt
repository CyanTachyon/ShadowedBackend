package moe.tachyon.shadowed.database

import moe.tachyon.shadowed.dataClass.ChatId
import moe.tachyon.shadowed.dataClass.ChatFull
import moe.tachyon.shadowed.dataClass.ChatMember
import moe.tachyon.shadowed.dataClass.User
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.utils.singleOrNull
import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
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

    suspend fun getUserChats(userId: UserId): List<ChatFull> = query()
    {
        val res = (table innerJoin chats.table)
            .select(table.chat, table.key, table.unread, table.doNotDisturb,
                chats.table.name, chats.table.private, chats.table.burnTime, chats.table.lastChatAt)
            .andWhere { table.user eq userId }
            .andWhere { chats.table.isMoment eq false }
            .toList()
            .sortedByDescending { it[chats.table.lastChatAt] }

        val chatIds = res.map { it[table.chat].value }
        val members = (table innerJoin users.table)
            .select(users.table.id, users.table.username, table.chat, users.table.donationAmount)
            .andWhere { table.chat inList chatIds }
            .toList()
            .groupBy { it[table.chat].value }

        res.map()
        { chatRow ->
            val isPrivate = chatRow[chats.table.private]
            ChatFull(
                chatId = chatRow[table.chat].value,
                name =
                    if (!isPrivate) chatRow[chats.table.name]
                    else members[chatRow[table.chat].value]
                        ?.firstOrNull { it[users.table.id].value != userId }
                        ?.get(users.table.username)
                        ?: "Private Chat",
                key = chatRow[table.key],
                members = members[chatRow[table.chat].value]?.map()
                { memberRow ->
                    ChatMember(
                        id = memberRow[users.table.id].value,
                        name = memberRow[users.table.username]
                    )
                } ?: emptyList(),
                isPrivate = chatRow[chats.table.private],
                unreadCount = chatRow[table.unread],
                doNotDisturb = chatRow[table.doNotDisturb],
                burnTime = chatRow[chats.table.burnTime],
                otherUserIsDonor = isPrivate && members[chatRow[table.chat].value]?.any { it[users.table.id].value != userId && it[users.table.donationAmount] > 0 } == true
            )
        }
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
     * Set unread count to Int.MIN_VALUE for users who were @mentioned.
     * This negative value indicates user was mentioned, and will still be negative
     * even after new messages increment the count.
     */
    suspend fun setAtMarker(chatId: ChatId, userId: UserId) = query()
    {
        table.update({ (table.chat eq chatId) and (table.user eq userId) })
        {
            it[unread] = Int.MIN_VALUE
        } > 0
    }

    /**
     * Check if user is a member of a chat
     */
    suspend fun isMember(chatId: ChatId, userId: UserId): Boolean = query()
    {
        table.selectAll().where { (table.chat eq chatId) and (table.user eq userId) }.count() > 0
    }

    /**
     * Get user's key for a specific chat
     */
    suspend fun getMemberKey(chatId: ChatId, userId: UserId): String? = query()
    {
        table.selectAll()
            .where { (table.chat eq chatId) and (table.user eq userId) }
            .singleOrNull()?.get(table.key)
    }
}
