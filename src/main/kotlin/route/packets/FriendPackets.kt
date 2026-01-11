package moe.tachyon.shadowed.route.packets

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.*
import moe.tachyon.shadowed.contentNegotiationJson
import moe.tachyon.shadowed.dataClass.ChatId
import moe.tachyon.shadowed.database.*
import moe.tachyon.shadowed.dataClass.User
import moe.tachyon.shadowed.route.*

object GetFriendsHandler : PacketHandler
{
    override val packetName = "get_friends"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val friends = getKoin().get<Friends>()
        val chats = getKoin().get<Chats>()
        val chatMembers = getKoin().get<ChatMembers>()
        
        val friendsList = friends.getFriends(loginUser.id)

        // Get the user's moment chat to check viewer permissions
        val myMomentChat = chats.getMomentChatByOwner(loginUser.id)
        val momentViewerIds = if (myMomentChat != null)
        {
            chatMembers.getMemberIds(myMomentChat.id)
        }
        else
        {
            emptyList()
        }

        val response = buildJsonObject()
        {
            put("packet", "friends_list")
            put("friends", buildJsonArray()
            {
                friendsList.forEach()
                { (id, name) ->
                    addJsonObject()
                    {
                        put("id", id.value)
                        put("username", name)
                        put("canViewMoments", momentViewerIds.contains(id))
                    }
                }
            })
        }
        session.send(contentNegotiationJson.encodeToString(response))
    }
}

object AddFriendHandler : PacketHandler
{
    override val packetName = "add_friend"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val (targetUsername, keyForFriend, keyForSelf) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val u = json.jsonObject["targetUsername"]!!.jsonPrimitive.content
            val kf = json.jsonObject["keyForFriend"]!!.jsonPrimitive.content
            val ks = json.jsonObject["keyForSelf"]!!.jsonPrimitive.content
            Triple(u, kf, ks)
        }.getOrNull() ?: return session.sendError("Add friend failed: Invalid packet format")

        if (targetUsername.equals(loginUser.username, ignoreCase = true))
            return session.sendError("Add friend failed: Cannot add yourself")

        val targetUser = getKoin().get<Users>().getUserByUsername(targetUsername) 
            ?: return session.sendError("Add friend failed: User not found")

        val friends = getKoin().get<Friends>()
        val chatMembers = getKoin().get<ChatMembers>()
        
        // Check if chat already exists
        val existingMembership = chatMembers.getUserChats(loginUser.id)
            .find { chat ->
                chat.members.size == 2 &&
                chat.members.any { it.name == targetUser.username }
            }
        
        val chatId: ChatId
        val isExisting: Boolean
        
        if (existingMembership != null)
        {
            chatId = existingMembership.chatId
            isExisting = true
        }
        else
        {
            chatId = friends.addFriend(loginUser.id, targetUser.id) ?: return session.sendError("Chat creation failed")
            isExisting = false
            chatMembers.addMember(chatId, loginUser.id, keyForSelf)
            chatMembers.addMember(chatId, targetUser.id, keyForFriend)
        }

        val response = buildJsonObject()
        {
            put("packet", "friend_added")
            put("chatId", chatId.value)
            put("isExisting", isExisting)
            put("message", if (isExisting) "Opening existing chat" else "Friend added & Chat created")
        }
        session.send(contentNegotiationJson.encodeToString(response))
        SessionManager.forEachSession(targetUser.id) { s -> s.sendChatList(targetUser.id) }
    }
}
