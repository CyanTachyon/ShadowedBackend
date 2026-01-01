package moe.tachyon.shadowed.route.packets

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import moe.tachyon.shadowed.contentNegotiationJson
import moe.tachyon.shadowed.dataClass.MessageType
import moe.tachyon.shadowed.dataClass.User
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.*
import moe.tachyon.shadowed.route.getKoin

/**
 * Data class representing a moment item in the feed
 */
@Serializable
data class MomentItem(
    val messageId: Long,
    val content: String,
    val type: MessageType,
    val ownerId: Int,
    val ownerName: String,
    val time: Long,
    val key: String, // Encrypted key for decryption
)

/**
 * Get all visible moments for the current user
 * Returns moments from chats where user is a member and chat is a moment chat
 */
object GetMomentsHandler : PacketHandler
{
    override val packetName = "get_moments"

    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val (offset, count) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val o = json.jsonObject["offset"]?.jsonPrimitive?.longOrNull ?: 0L
            val c = json.jsonObject["count"]?.jsonPrimitive?.intOrNull ?: 50
            Pair(o, c)
        }.getOrElse { Pair(0L, 50) }

        val messages = getKoin().get<Messages>()

        // Use JOIN query to get all moment messages user can see
        val momentMessages = messages.getMomentMessagesForUser(loginUser.id, offset, count)

        val momentItems = momentMessages.map { msg ->
            MomentItem(
                messageId = msg.messageId,
                content = msg.content,
                type = msg.type,
                ownerId = msg.ownerId,
                ownerName = msg.ownerName,
                time = msg.time,
                key = msg.key
            )
        }

        val response = buildJsonObject()
        {
            put("packet", "moments_list")
            put("moments", contentNegotiationJson.encodeToJsonElement(momentItems))
        }
        session.send(contentNegotiationJson.encodeToString(response))
    }
}

/**
 * Post a new moment (send message to own moment chat)
 */
object PostMomentHandler : PacketHandler
{
    override val packetName = "post_moment"

    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        @Serializable
        data class PostMoment(
            val content: String,
            val type: MessageType,
            val key: String?, // Encrypted key (only needed when first creating moment chat)
        )

        val postData = runCatching()
        {
            contentNegotiationJson.decodeFromString<PostMoment>(packetData)
        }.getOrNull() ?: return session.sendError("Post moment failed: Invalid packet format")

        val chats = getKoin().get<Chats>()
        val chatMembers = getKoin().get<ChatMembers>()
        val messages = getKoin().get<Messages>()

        // Get or create moment chat
        val momentChatId = chats.getOrCreateMomentChat(loginUser.id, loginUser.username)

        // Add user as member if not already
        val isMember = chatMembers.isMember(momentChatId, loginUser.id)
        if (!isMember)
        {
            val key = postData.key ?: return session.sendError("Post moment failed: Key required for first moment")
            chatMembers.addMember(momentChatId, loginUser.id, key)
        }

        // Add the message
        val msgId = messages.addChatMessage(
            content = if (postData.type == MessageType.TEXT) postData.content else "",
            type = postData.type,
            chatId = momentChatId,
            senderId = loginUser.id
        )

        chats.updateTime(momentChatId)

        val response = buildJsonObject()
        {
            put("packet", "moment_posted")
            put("messageId", msgId)
            put("chatId", momentChatId.value)
        }
        session.send(contentNegotiationJson.encodeToString(response))
        session.sendSuccess("Moment posted successfully")
    }
}

/**
 * Get a specific user's moments
 */
object GetUserMomentsHandler : PacketHandler
{
    override val packetName = "get_user_moments"

    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val (targetUserId, before, count) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val uid = json.jsonObject["userId"]!!.jsonPrimitive.int.let(::UserId)
            val b = json.jsonObject["before"]?.jsonPrimitive?.longOrNull ?: Long.MAX_VALUE
            val c = json.jsonObject["count"]?.jsonPrimitive?.intOrNull ?: 50
            Triple(uid, b, c)
        }.getOrNull() ?: return session.sendError("Get user moments failed: Invalid packet format")

        val chats = getKoin().get<Chats>()
        val chatMembers = getKoin().get<ChatMembers>()
        val messages = getKoin().get<Messages>()
        val users = getKoin().get<Users>()

        val targetUser = users.getUser(targetUserId)
            ?: return session.sendError("User not found")

        val momentChat = chats.getMomentChatByOwner(targetUserId)
        if (momentChat == null)
        {
            // No moments yet
            val response = buildJsonObject()
            {
                put("packet", "user_moments_list")
                put("userId", targetUserId.value)
                put("username", targetUser.username)
                put("moments", buildJsonArray { })
            }
            session.send(contentNegotiationJson.encodeToString(response))
            return
        }

        // Check if requester is owner or a member (has key)
        val key = chatMembers.getMemberKey(momentChat.id, loginUser.id)
        if (key == null && targetUserId != loginUser.id)
        {
            return session.sendError("You are not a viewer of this user's moments")
        }

        val momentMessages = messages.getChatMessages(momentChat.id, before, count)
        val momentItems = momentMessages.map { msg ->
            MomentItem(
                messageId = msg.id,
                content = msg.content,
                type = msg.type,
                ownerId = targetUserId.value,
                ownerName = targetUser.username,
                time = msg.time,
                key = key ?: ""
            )
        }

        val response = buildJsonObject()
        {
            put("packet", "user_moments_list")
            put("userId", targetUserId.value)
            put("username", targetUser.username)
            put("moments", contentNegotiationJson.encodeToJsonElement(momentItems))
        }
        session.send(contentNegotiationJson.encodeToString(response))
    }
}

/**
 * Toggle whether a friend can view my moments
 */
object ToggleMomentPermissionHandler : PacketHandler
{
    override val packetName = "toggle_moment_permission"

    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val (friendId, canView) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val fid = json.jsonObject["friendId"]!!.jsonPrimitive.int.let(::UserId)
            val cv = json.jsonObject["canView"]!!.jsonPrimitive.boolean
            Pair(fid, cv)
        }.getOrNull() ?: return session.sendError("Toggle moment permission failed: Invalid packet format")

        val friends = getKoin().get<Friends>()
        val chatMembers = getKoin().get<ChatMembers>()
        val chats = getKoin().get<Chats>()

        // Verify friendship exists
        if (!friends.areFriends(loginUser.id, friendId))
            return session.sendError("User is not your friend")

        // If disabling, remove from moment chat viewers
        if (!canView)
        {
            val momentChat = chats.getMomentChatByOwner(loginUser.id)
            if (momentChat != null)
            {
                chatMembers.removeMember(momentChat.id, friendId)
            }
        }
        else
        {
            // Enabling access requires providing the encrypted key for that friend.
            // Client should call `add_moment_viewer` with the encryptedKey to add them.
        }

        val response = buildJsonObject()
        {
            put("packet", "moment_permission_updated")
            put("friendId", friendId.value)
            put("canView", canView)
        }
        session.send(contentNegotiationJson.encodeToString(response))
        session.sendSuccess(if (canView) "Enable: call add_moment_viewer with encryptedKey" else "Friend can no longer view your moments")
    }
}

/**
 * Get moment permission status for a specific friend, optionally add as viewer if encryptedKey is provided
 */
object GetMomentPermissionHandler : PacketHandler
{
    override val packetName = "get_moment_permission"

    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val (friendId, encryptedKey) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val fid = json.jsonObject["friendId"]!!.jsonPrimitive.int.let(::UserId)
            val key = json.jsonObject["encryptedKey"]?.jsonPrimitive?.content
            Pair(fid, key)
        }.getOrNull() ?: return session.sendError("Get moment permission failed: Invalid packet format")

        val friends = getKoin().get<Friends>()
        val chats = getKoin().get<Chats>()
        val chatMembers = getKoin().get<ChatMembers>()

        if (!friends.areFriends(loginUser.id, friendId)) return

        // If encryptedKey is provided, add friend as viewer
        if (encryptedKey != null)
        {
            val momentChatId = chats.getOrCreateMomentChat(loginUser.id, loginUser.username)
            if (!chatMembers.isMember(momentChatId, friendId))
                chatMembers.addMember(momentChatId, friendId, encryptedKey)
        }

        // Return permission status
        val myMomentChat = chats.getMomentChatByOwner(loginUser.id)
        val canFriendViewMine = if (myMomentChat != null) chatMembers.isMember(myMomentChat.id, friendId) else false

        val friendMomentChat = chats.getMomentChatByOwner(friendId)
        val canIViewFriends = if (friendMomentChat != null) chatMembers.isMember(friendMomentChat.id, loginUser.id) else false

        val response = buildJsonObject()
        {
            put("packet", "moment_permission_status")
            put("friendId", friendId.value)
            put("canFriendViewMine", canFriendViewMine)
            put("canIViewFriends", canIViewFriends)
        }
        session.send(contentNegotiationJson.encodeToString(response))
    }
}

/**
 * Get the key for the current user's moment chat (for encrypting new moments)
 */
object GetMyMomentKeyHandler : PacketHandler
{
    override val packetName = "get_my_moment_key"

    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val chats = getKoin().get<Chats>()
        val chatMembers = getKoin().get<ChatMembers>()

        val momentChat = chats.getMomentChatByOwner(loginUser.id)
        if (momentChat == null)
        {
            // No moment chat yet - client needs to create one
            val response = buildJsonObject()
            {
                put("packet", "my_moment_key")
                put("exists", false)
                put("key", JsonNull)
            }
            session.send(contentNegotiationJson.encodeToString(response))
            return
        }

        val key = chatMembers.getMemberKey(momentChat.id, loginUser.id)
        val response = buildJsonObject()
        {
            put("packet", "my_moment_key")
            put("exists", true)
            put("chatId", momentChat.id.value)
            if (key != null) put("key", key) else put("key", JsonNull)
        }
        session.send(contentNegotiationJson.encodeToString(response))
    }
}
