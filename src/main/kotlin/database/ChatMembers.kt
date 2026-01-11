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

    suspend fun getUserChats(userId: UserId): List<ChatFull> = query()
    {
        val myMemberships = table.selectAll().where { table.user eq userId }.toList()

        val chats = myMemberships.mapNotNull()
        { row ->
            val chatId = row[table.chat].value
            val chatRow = chats.table.selectAll().where { chats.table.id eq chatId }.singleOrNull()
            
            // Filter out moment chats from regular chat list
            val isMoment = chatRow?.get(chats.table.isMoment) ?: false
            if (isMoment) return@mapNotNull null
            
            val isPrivate = chatRow?.get(chats.table.private) ?: false
            val chatName = chatRow?.get(chats.table.name)
            val burnTime = chatRow?.get(chats.table.burnTime)
            val myKey = row[table.key]
            val allMembersInfo = table.selectAll().where { table.chat eq chatId }
                .map()
                { mRow ->
                    val uid = mRow[table.user].value
                    val userRow = users.table.selectAll().where { users.table.id eq uid }.single()
                    val uname = userRow[users.table.username]
                    val isDonor = userRow[users.table.donationAmount] > 0
                    Triple(uid, uname, isDonor)
                }

            val members = allMembersInfo.map { (uid, uname, _) ->
                ChatMember(
                    id = uid.value,
                    name = uname
                )
            }
            val otherUserIsDonor = allMembersInfo.find { it.first != userId }?.third ?: false

            // For private chats, use other person's name instead of chat name
            val displayName = if (isPrivate && members.size == 2)
            {
                members.find { it.id != userId.value }?.name ?: chatName
            }
            else
            {
                chatName ?: members.joinToString(", ") { it.name }
            }

            ChatFull(
                chatId = chatId,
                name = displayName,
                key = myKey,
                members = members,
                isPrivate = isPrivate,
                unreadCount = row[table.unread],
                doNotDisturb = row[table.doNotDisturb],
                burnTime = burnTime,
                otherUserIsDonor = otherUserIsDonor
            )
        }
        val chatTable =  get<Chats>().table
        val times = chatTable.select(chatTable.id, chatTable.lastChatAt).where { chatTable.id inList chats.map { it.chatId } }
            .associate { it[chatTable.id].value to it[chatTable.lastChatAt].toEpochMilliseconds() }
        chats.sortedByDescending { times[it.chatId] ?: 0L }
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

    // ====== Moment-related methods ======

    /**
     * Check if user is a member of a chat
     */
    suspend fun isMember(chatId: ChatId, userId: UserId): Boolean = query()
    {
        table.selectAll().where { (table.chat eq chatId) and (table.user eq userId) }.any()
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
