package moe.tachyon.shadowed.database

import moe.tachyon.shadowed.dataClass.ChatId
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.utils.singleOrNull
import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.sql.*
import org.koin.core.component.get

class Friends: SqlDao<Friends.FriendTable>(FriendTable)
{
    object FriendTable: CompositeIdTable("friends")
    {
        val userA = reference(
            "user_a",
            Users.UserTable,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        ).index()
        val userB = reference(
            "user_b",
            Users.UserTable,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        ).index()
        val chat = reference(
            "chat",
            Chats.ChatTable,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        ).index()
        // reference to chat is enough; moment viewer membership is stored in ChatMembers
        override val primaryKey: PrimaryKey = PrimaryKey(userA, userB)

        init
        {
            addIdColumn(userA)
            addIdColumn(userB)
        }
    }

    suspend fun addFriend(
        userAId: UserId,
        userBId: UserId,
    ): ChatId? = query()
    {
        val chatTable = get<Chats>().table

        val userA = minOf(userAId, userBId)
        val userB = maxOf(userAId, userBId)

        // Check if friendship already exists
        val existingFriend = selectAll().where { (table.userA eq userA) and (table.userB eq userB) }.singleOrNull()
        if (existingFriend != null)
        {
            // Return the existing chat ID instead of null
            return@query existingFriend[table.chat].value
        }

        val chatName = "Friend Chat ($userA, $userB)"
        val chat = chatTable.insertIgnoreAndGetId()
        {
            it[this.name] = chatName
            it[this.owner] = userA
            it[this.private] = true
        }?.value ?: return@query null
        insertIgnoreAndGetId()
        {
            it[this.userA] = userA
            it[this.userB] = userB
            it[this.chat] = chat
        }
        chat
    }

    suspend fun getFriends(userId: UserId): List<Pair<UserId, String>> = query()
    {
        val userTable = get<Users>().table
        val chatTable = get<Chats>().table
        val queryA = table
            .join(userTable, JoinType.INNER, table.userB, userTable.id)
            .join(chatTable, JoinType.INNER, table.chat, chatTable.id)
            .selectAll()
            .where { table.userA eq userId }
            .orderBy(chatTable.lastChatAt, SortOrder.DESC)
            .map { it[userTable.id].value to it[userTable.username] }
        val queryB = table
            .join(userTable, JoinType.INNER, table.userA, userTable.id)
            .join(chatTable, JoinType.INNER, table.chat, chatTable.id)
            .selectAll()
            .where { table.userB eq userId }
            .orderBy(chatTable.lastChatAt, SortOrder.DESC)
            .map { it[userTable.id].value to it[userTable.username] }

        (queryA + queryB).distinctBy { it.first }
    }

    // ====== Friendship helpers ======
    /**
     * Check if the friendship exists between two users
     */
    suspend fun areFriends(userAId: UserId, userBId: UserId): Boolean = query()
    {
        val a = minOf(userAId, userBId)
        val b = maxOf(userAId, userBId)
        table.selectAll().where { (table.userA eq a) and (table.userB eq b) }.any()
    }

    // Note: moment viewer permissions are determined by membership in the owner's moment chat
}