package moe.tachyon.shadowed.route.packets

import io.ktor.server.websocket.*
import kotlinx.serialization.json.*
import moe.tachyon.shadowed.contentNegotiationJson
import moe.tachyon.shadowed.dataClass.ChatId
import moe.tachyon.shadowed.dataClass.ChatId.Companion.toChatId
import moe.tachyon.shadowed.dataClass.User
import moe.tachyon.shadowed.database.ChatMembers
import moe.tachyon.shadowed.database.Chats
import moe.tachyon.shadowed.database.Users
import moe.tachyon.shadowed.database.Messages
import moe.tachyon.shadowed.logger.ShadowedLogger
import moe.tachyon.shadowed.route.SessionManager
import moe.tachyon.shadowed.route.getKoin
import moe.tachyon.shadowed.route.sendChatDetails
import moe.tachyon.shadowed.route.sendChatList
import moe.tachyon.shadowed.route.distributeMessage
import moe.tachyon.shadowed.utils.FileUtils

private val logger = ShadowedLogger.getLogger()

object CreateGroupHandler : PacketHandler
{
    override val packetName = "create_group"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val (groupName, memberUsernames, encryptedKeys) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val name = json.jsonObject["name"]?.jsonPrimitive?.takeUnless { it is JsonNull }?.content ?: "New Group"
            val members = json.jsonObject["memberUsernames"]!!.jsonArray.map { it.jsonPrimitive.content }
            val keys = json.jsonObject["encryptedKeys"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
            Triple(name, members, keys)
        }.getOrNull() ?: return session.sendError("Create group failed: Invalid packet format")

        // Validate all members exist and get their user objects
        val users = getKoin().get<Users>()
        val memberUsers = memberUsernames.mapNotNull()
        { username ->
            users.getUserByUsername(username)
        }

        if (memberUsers.size != memberUsernames.size)
        {
            return session.sendError("Create group failed: One or more users not found")
        }

        // Check all members have keys
        val missingKeys = memberUsernames.filter { !encryptedKeys.containsKey(it) }
        if (missingKeys.isNotEmpty())
        {
            return session.sendError("Create group failed: Missing keys for: ${missingKeys.joinToString()}")
        }

        val chats = getKoin().get<Chats>()
        val chatMembers = getKoin().get<ChatMembers>()

        val chatId = chats.createChat(name = groupName, owner = loginUser.id)

        val creatorKey = encryptedKeys[loginUser.username]
        if (creatorKey != null)
            chatMembers.addMember(chatId, loginUser.id, creatorKey)
        memberUsers.forEach()
        { user ->
            val key = encryptedKeys[user.username]
            if (key != null && user.id != loginUser.id)
                chatMembers.addMember(chatId, user.id, key)
        }
        
        session.sendSuccess("Group created successfully")

        for (user in (memberUsers + loginUser).distinct())
            SessionManager.forEachSession(user.id) { s -> s.sendChatList(user.id) }
    }
}

object AddMemberToChatHandler : PacketHandler
{
    override val packetName = "add_member_to_chat"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val (chatIdVal, username, encryptedKey) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val id = json.jsonObject["chatId"]!!.jsonPrimitive.int
            val user = json.jsonObject["username"]!!.jsonPrimitive.content
            val key = json.jsonObject["encryptedKey"]!!.jsonPrimitive.content
            Triple(id, user, key)
        }.getOrNull() ?: return session.sendError("Invalid packet format")

        val chatId = ChatId(chatIdVal)
        val chats = getKoin().get<Chats>()
        val chatMembers = getKoin().get<ChatMembers>()
        
        // Check if this is a moment chat - only owner can invite
        val chat = chats.getChat(chatId) ?: return session.sendError("Chat not found")
        if (chat.isMoment && chat.owner != loginUser.id)
        {
            return session.sendError("Only the owner can invite viewers to their moments")
        }
        
        // Verify current user is a member of this chat
        if (!chatMembers.isMember(chatId, loginUser.id))
            return session.sendError("You are not a member of this chat")

        // Get target user
        val targetUser = getKoin().get<Users>().getUserByUsername(username) ?: return session.sendError("User not found: $username")

        // Check if user is already a member
        if (chatMembers.isMember(chatId, targetUser.id))
            return session.sendError("$username is already a member")

        // Add the new member
        chatMembers.addMember(chatId, targetUser.id, encryptedKey)

        session.sendSuccess("Member added successfully")

        val members = chatMembers.getChatMembersDetailed(chatId)
        
        for (user in members)
            SessionManager.forEachSession(user.id) { s -> s.sendChatDetails(chat, members) }
        SessionManager.forEachSession(targetUser.id) { s -> s.sendChatList(targetUser.id) }

        val systemMessageId = getKoin().get<Messages>().addSystemMessage(
            content = "${loginUser.username} invited ${targetUser.username} to the chat",
            chatId = chatId
        )

        val systemMessage = getKoin().get<Messages>().getMessage(systemMessageId) ?: return
        distributeMessage(systemMessage, silent = true)
    }
}

object KickMemberFromChatHandler : PacketHandler
{
    override val packetName = "kick_member_from_chat"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val (chatId, username) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val id = json.jsonObject["chatId"]!!.jsonPrimitive.int.toChatId()
            val user = json.jsonObject["username"]!!.jsonPrimitive.content
            Pair(id, user)
        }.getOrNull() ?: return session.sendError("Invalid packet format")
        
        val chats = getKoin().get<Chats>()
        val chatMembers = getKoin().get<ChatMembers>()
        val chat = chats.getChat(chatId) ?: return session.sendError("Chat not found")

        // Check if this is a moment chat - only owner can kick
        if (chat.isMoment && chat.owner != loginUser.id)
        {
            return session.sendError("Only the owner can remove viewers from their moments")
        }

        val isOwner = chats.isChatOwner(chatId, loginUser.id)

        if (chat.private || (isOwner && loginUser.username == username))
        {
            val members = chatMembers.getChatMembersDetailed(chatId)
            if (members.none { it.id == loginUser.id })
                return session.sendError("You are not a member of this chat")
            chats.deleteChat(chatId)

            // Get all message IDs that have files in this chat
            val fileMessageIds = getKoin().get<Messages>().getFileMessageIds(chatId)

            // Delete message files for this chat
            fileMessageIds.forEach()
            { msgId ->
                logger.warning("Failed to delete file for message $msgId")
                {
                    FileUtils.deleteChatFile(msgId)
                }
            }

            // Delete group avatar for this chat
            logger.warning("Failed to delete avatar for group $chatId")
            {
                FileUtils.deleteGroupAvatar(chatId)
            }
            getKoin().get<Messages>().deleteChatMessages(chatId)
            for (user in members)
                SessionManager.forEachSession(user.id) { s -> s.sendChatList(user.id) }
            return session.sendSuccess("Chat deleted successfully")
        }

        if (!isOwner && loginUser.username != username)
            return session.sendError("Only owner can kick members")
        
        val targetUser = getKoin().get<Users>().getUserByUsername(username) ?: return session.sendError("User not found: $username")
        
        val members = chatMembers.getChatMembersDetailed(chatId).filterNot { it.id == targetUser.id }
        if (members.size <= 2)
            return session.sendError("Cannot kick member: Chat must have at least 3 members")
        
        chatMembers.removeMember(chatId, targetUser.id)
        session.sendSuccess("Member kicked successfully")
        
        for (user in members)
            SessionManager.forEachSession(user.id) { s -> s.sendChatDetails(chat, members) }
        SessionManager.forEachSession(targetUser.id) { s -> s.sendChatList(targetUser.id) }

        val messageId = getKoin().get<Messages>().addSystemMessage(
            content = "${loginUser.username} removed ${targetUser.username} from the chat",
            chatId = chatId
        )
        val message = getKoin().get<Messages>().getMessage(messageId) ?: return
        distributeMessage(message, silent = true)
    }
}
