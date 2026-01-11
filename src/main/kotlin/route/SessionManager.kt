package moe.tachyon.shadowed.route

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import moe.tachyon.shadowed.contentNegotiationJson
import moe.tachyon.shadowed.dataClass.Chat
import moe.tachyon.shadowed.dataClass.ChatId
import moe.tachyon.shadowed.dataClass.User
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.Broadcasts
import moe.tachyon.shadowed.database.ChatMembers
import moe.tachyon.shadowed.route.packets.NotifyPacket
import org.koin.core.component.KoinComponent

private val sessions = mutableMapOf<UserId, MutableSet<DefaultWebSocketServerSession>>()
private val sessionsMutex = Mutex()

internal fun getKoin() = object : KoinComponent { }.getKoin()

object SessionManager
{
    suspend fun addSession(userId: UserId, session: DefaultWebSocketServerSession)
    {
        sessionsMutex.withLock()
        {
            sessions.getOrPut(userId) { mutableSetOf() }.add(session)
        }
    }

    suspend fun removeSession(userId: UserId, session: DefaultWebSocketServerSession)
    {
        sessionsMutex.withLock()
        {
            val set = sessions[userId] ?: return@withLock
            set.remove(session)
            if (set.isEmpty()) sessions.remove(userId)
        }
    }

    suspend fun getSessions(userId: UserId): List<DefaultWebSocketServerSession>
    {
        return sessionsMutex.withLock { sessions[userId]?.toList() ?: emptyList() }
    }

    suspend fun getAllSessions(): List<DefaultWebSocketServerSession>
    {
        return sessionsMutex.withLock { sessions.values.flatten().toList() }
    }

    suspend fun forEachSession(userId: UserId, action: suspend (DefaultWebSocketServerSession) -> Unit)
    {
        for (s in getSessions(userId)) runCatching { action(s) }
    }

    suspend fun forAllSessions(action: suspend (DefaultWebSocketServerSession) -> Unit)
    {
        for (s in getAllSessions()) runCatching { action(s) }
    }
}

suspend fun WebSocketSession.sendChatList(userId: UserId)
{
    val userChats = getKoin().get<ChatMembers>().getUserChats(userId)
    val response = buildJsonObject()
    {
        put("packet", "chats_list")
        put("chats", contentNegotiationJson.encodeToJsonElement(userChats))
    }
    return send(contentNegotiationJson.encodeToString(response))
}

suspend fun WebSocketSession.sendUnreadCount(userId: UserId, chatId: ChatId)
{
    val unread = getKoin().get<ChatMembers>().getUnreadCount(chatId, userId)
    val response = buildJsonObject()
    {
        put("packet", "unread_count")
        put("chatId", chatId.value)
        put("unread", unread)
    }
    return send(contentNegotiationJson.encodeToString(response))
}

suspend fun WebSocketSession.sendChatDetails(chat: Chat, members: List<User>)
{
    // Check online status for each member and sort online members first
    val membersWithOnlineStatus = members.map { member ->
        val isOnline = sessionsMutex.withLock { sessions.containsKey(member.id) }
        member to isOnline
    }.sortedByDescending { it.second } // Sort by isOnline (true first)

    val response = buildJsonObject()
    {
        put("packet", "chat_details")
        put("chat", buildJsonObject()
        {
            put("id", chat.id.value)
            put("name", chat.name)
            put("ownerId", chat.owner.value)
            put("isPrivate", chat.private)
            put("members", buildJsonArray()
            {
                membersWithOnlineStatus.forEach { (member, isOnline) ->
                    addJsonObject()
                    {
                        put("id", member.id.value)
                        put("username", member.username)
                        put("isDonor", member.isDonor)
                        put("isOnline", isOnline)
                        // Only include signature for private chats
                        if (chat.private) put("signature", member.signature)
                    }
                }
            })
        })
    }
    return send(contentNegotiationJson.encodeToString(response))
}

suspend fun renewBroadcast(broadcastId: Long)
{
    val broadcast = getKoin()
        .get<Broadcasts>()
        .getBroadcast(broadcastId)
        ?.let(::listOf)
        ?.let(contentNegotiationJson::encodeToJsonElement)
        ?: return
    SessionManager.forAllSessions()
    { s ->
        val response = buildJsonObject()
        {
            put("packet", "broadcasts_list")
            put("broadcasts", broadcast)
        }
        s.send(contentNegotiationJson.encodeToString(response))
    }
}

suspend fun sendNotifyToAll(type: NotifyPacket.Type, message: String) = SessionManager.forAllSessions()
{ s ->
    s.send(contentNegotiationJson.encodeToString(NotifyPacket(type, message)))
}