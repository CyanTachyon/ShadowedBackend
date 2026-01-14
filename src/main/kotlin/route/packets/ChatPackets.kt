package moe.tachyon.shadowed.route.packets

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import moe.tachyon.shadowed.contentNegotiationJson
import moe.tachyon.shadowed.dataClass.ChatId
import moe.tachyon.shadowed.dataClass.MessageType
import moe.tachyon.shadowed.dataClass.User
import moe.tachyon.shadowed.database.ChatMembers
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.Chats
import moe.tachyon.shadowed.database.Messages
import moe.tachyon.shadowed.route.*
import moe.tachyon.shadowed.utils.FileUtils
import moe.tachyon.shadowed.logger.ShadowedLogger
import kotlin.time.Duration.Companion.milliseconds

private val logger = ShadowedLogger.getLogger()

object GetChatsHandler: PacketHandler
{
    override val packetName = "get_chats"

    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        session.sendChatList(loginUser.id)
    }
}

object GetMessagesHandler: PacketHandler
{
    override val packetName = "get_messages"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val (chatId, before, count) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val cid = json.jsonObject["chatId"]!!.jsonPrimitive.int.let(::ChatId)
            val before = json.jsonObject["before"]?.jsonPrimitive?.longOrNull
            val limit = json.jsonObject["count"]!!.jsonPrimitive.int
            Triple(cid, before, limit)
        }.getOrNull() ?: return session.sendError("Get messages failed: Invalid packet format")
        
        if (!getKoin().get<ChatMembers>().isMember(chatId, loginUser.id))
            return session.sendError("Get messages failed: You are not a member of this chat")
        
        val msgs = getKoin().get<Messages>().getChatMessages(chatId, before?.let(Instant::fromEpochMilliseconds), count)
        if (before == null)
        {
            getKoin().get<ChatMembers>().resetUnread(chatId, loginUser.id)
            session.sendUnreadCount(loginUser.id, chatId)
        }
        val response = buildJsonObject()
        {
            put("packet", "messages_list")
            put("messages", contentNegotiationJson.encodeToJsonElement(msgs))
            put("chatId", chatId.value)
        }
        session.send(contentNegotiationJson.encodeToString(response))
    }
}

object SendMessageHandler: PacketHandler
{
    override val packetName = "send_message"

    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        @Serializable
        data class SendMessage(
            val chatId: ChatId,
            val message: String,
            val type: MessageType,
            val replyTo: Long? = null,
            val atUserIds: List<UserId> = emptyList(),
        )
        val (chatId, message, type, replyTo, atUserIds) = runCatching()
        {
            contentNegotiationJson.decodeFromString<SendMessage>(packetData)
        }.getOrNull() ?: return session.sendError("Send message failed: Invalid packet format")

        val chats = getKoin().get<Chats>()
        val chatMembers = getKoin().get<ChatMembers>()

        // Check if this is a moment chat - only owner can send messages
        val chat = chats.getChat(chatId)
        if (chat != null && chat.isMoment && chat.owner != loginUser.id)
        {
            return session.sendError("Send message failed: Only the owner can post to their moments")
        }

        // For moment chats, check membership differently (moment chats are excluded from getUserChats)
        val isMember = chatMembers.isMember(chatId, loginUser.id)

        if (!isMember)
            return session.sendError("Send message failed: You are not a member of this chat")

        val messages = getKoin().get<Messages>()
        if (replyTo != null) run()
        {
            val repliedMessage = messages.getMessage(replyTo)
            if (repliedMessage == null || repliedMessage.chatId != chatId || repliedMessage.senderId == null)
                return session.sendError("Send message failed: Replied message not found or not in this chat or is a system message")
        }
        val msgId = messages.addChatMessage(
            content = if (type == MessageType.TEXT) message else "",
            type = type,
            chatId = chatId,
            senderId = loginUser.id,
            burnTime = chat?.burnTime,
            replyTo = replyTo,
        )

        // Get the full message including reply info if applicable
        val fullMessage = messages.getMessage(msgId) ?: run {
            return session.sendError("Send message failed: Failed to retrieve sent message")
        }

        chats.updateTime(chatId)
        chatMembers.incrementUnread(chatId, loginUser.id)
        chatMembers.resetUnread(chatId, loginUser.id)

        // Set at marker for mentioned users
        for (atUserId in atUserIds)
        {
            chatMembers.setAtMarker(chatId, atUserId)
        }

        distributeMessage(fullMessage, silent = false)
    }
}

object GetChatDetailsHandler: PacketHandler
{
    override val packetName = "get_chat_details"

    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val chatId = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            json.jsonObject["chatId"]!!.jsonPrimitive.int.let(::ChatId)
        }.getOrNull() ?: return

        val chats = getKoin().get<Chats>()
        val chat = chats.getChat(chatId) ?: return

        val members = getKoin().get<ChatMembers>().getChatMembersDetailed(chatId)
        session.sendChatDetails(chat, members)
    }
}

object RenameChatHandler: PacketHandler
{
    override val packetName = "rename_chat"

    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val (chatId, newName) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val id = json.jsonObject["chatId"]!!.jsonPrimitive.int.let(::ChatId)
            val name = json.jsonObject["newName"]!!.jsonPrimitive.content
            id to name
        }.getOrNull() ?: return

        val chats = getKoin().get<Chats>()
        val isOwner = chats.isChatOwner(chatId, loginUser.id)

        if (!isOwner)
        {
            return session.sendError("Only owner can rename chat")
        }
        chats.renameChat(chatId, newName)

        // Add system message for rename
        val id = getKoin().get<Messages>().addSystemMessage(
            content = "${loginUser.username} changed the chat name to \"$newName\"",
            chatId = chatId
        )

        session.sendSuccess("Chat renamed successfully")
        getKoin().get<ChatMembers>().getMemberIds(chatId).forEach()
        { uid ->
            SessionManager.forEachSession(uid)
            { s ->
                s.sendChatList(uid)
            }
        }

        // Distribute system message to all members
        val systemMessage = getKoin().get<Messages>().getMessage(id) ?: return
        distributeMessage(systemMessage, false)
    }
}

object SetDoNotDisturb: PacketHandler
{
    override val packetName: String = "set_do_not_disturb"

    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val (chatId, doNotDisturb) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val id = json.jsonObject["chatId"]!!.jsonPrimitive.int.let(::ChatId)
            val dnd = json.jsonObject["doNotDisturb"]!!.jsonPrimitive.boolean
            id to dnd
        }.getOrNull() ?: return

        val chatMembers = getKoin().get<ChatMembers>()
        if (!chatMembers.setDoNotDisturb(chatId, loginUser.id, doNotDisturb))
            return session.sendError("You are not a member of this chat")
        session.sendSuccess("Do Not Disturb set to $doNotDisturb")
        session.sendChatList(loginUser.id)
    }
}

object EditMessageHandler: PacketHandler
{
    override val packetName = "edit_message"

    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        @Serializable
        data class EditMessage(
            val messageId: Long,
            val message: String?,
            val atUserIds: List<UserId> = emptyList(),
        )
        val (messageId, newContent, atUserIds) = runCatching()
        {
            contentNegotiationJson.decodeFromString<EditMessage>(packetData)
        }.getOrNull() ?: return session.sendError("Edit message failed: Invalid packet format")

        val messages = getKoin().get<Messages>()
        val chatMembers = getKoin().get<ChatMembers>()
        val originalMessage = messages.getMessage(messageId)
            ?: return session.sendError("Edit message failed: Message not found")

        if (originalMessage.senderId != loginUser.id)
            return session.sendError("Edit message failed: You can only edit your own messages")

        if (!chatMembers.isMember(originalMessage.chatId, loginUser.id))
            return session.sendError("Edit message failed: You are not a member of this chat")

        // If deleting (newContent == null), also delete the associated file
        if (newContent == null)
        {
            logger.warning("Failed to delete file for message $messageId")
            {
                FileUtils.deleteChatFile(messageId)
            }
        }

        messages.updateMessage(messageId, newContent)

        // Set at marker for mentioned users
        for (atUserId in atUserIds)
        {
            chatMembers.setAtMarker(originalMessage.chatId, atUserId)
        }

        distributeMessage(originalMessage.copy(
            content = newContent ?: "",
            type = if (newContent == null) MessageType.TEXT else originalMessage.type,
        ), silent = true)
    }
}
object SetBurnTimeHandler: PacketHandler
{
    override val packetName = "set_burn_time"

    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val (chatId, burnTime) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val id = json.jsonObject["chatId"]!!.jsonPrimitive.int.let(::ChatId)
            val time = json.jsonObject["burnTime"]?.jsonPrimitive?.longOrNull
            id to time
        }.getOrNull() ?: return session.sendError("Set burn time failed: Invalid packet format")

        val chats = getKoin().get<Chats>()
        val chatMembers = getKoin().get<ChatMembers>()

        // Check if user is a member of this chat
        if (!chatMembers.isMember(chatId, loginUser.id))
            return session.sendError("Set burn time failed: You are not a member of this chat")

        // Check if this is a private chat
        val chat = chats.getChat(chatId)
            ?: return session.sendError("Set burn time failed: Chat not found")
        if (!chat.private)
            return session.sendError("Set burn time failed: Burn after read is only available for private chats")

        // Set burn time
        chats.setBurnTime(chatId, burnTime)

        // Notify all members of the chat to refresh their chat list
        val members = chatMembers.getMemberIds(chatId)
        members.forEach { uid ->
            SessionManager.forEachSession(uid) { s ->
                s.sendChatList(uid)
            }
        }

        session.sendSuccess(if (burnTime != null) "Burn time set to ${burnTime / 1000} seconds" else "Burn after read disabled")
    }
}

object MarkMessageReadHandler: PacketHandler
{
    override val packetName = "mark_message_read"

    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val messageId = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            json.jsonObject["messageId"]!!.jsonPrimitive.long
        }.getOrNull() ?: return session.sendError("Mark message read failed: Invalid packet format")

        val messages = getKoin().get<Messages>()
        val chatMembers = getKoin().get<ChatMembers>()
        val chats = getKoin().get<Chats>()

        val message = messages.getMessage(messageId) ?: return
        val chat = chats.getChat(message.chatId) ?: return

        if (!chatMembers.isMember(message.chatId, loginUser.id)) return
        if (message.senderId == loginUser.id || message.readAt != null || !chat.private) return

        val now = Clock.System.now()
        val burnTime = message.burn?.let { now + it.milliseconds }
        messages.markAsRead(messageId, now, burnTime)
        messages.getMessage(messageId)?.let { distributeMessage(it, silent = true) }
    }
}

object ToggleReactionHandler: PacketHandler
{
    override val packetName = "toggle_reaction"

    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val (messageId, emoji) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val id = json.jsonObject["messageId"]!!.jsonPrimitive.long
            val emoji = json.jsonObject["emoji"]!!.jsonPrimitive.content
            id to emoji
        }.getOrNull() ?: return session.sendError("Toggle reaction failed: Invalid packet format")

        val messages = getKoin().get<Messages>()
        val chatMembers = getKoin().get<ChatMembers>()
        val chats = getKoin().get<Chats>()

        // Get the message
        val message = messages.getMessage(messageId)
            ?: return session.sendError("Toggle reaction failed: Message not found")

        // Get the chat to check if it's a moment
        val chat = chats.getChat(message.chatId)
            ?: return session.sendError("Toggle reaction failed: Chat not found")

        // Check if user is a member of this chat
        if (!chatMembers.isMember(message.chatId, loginUser.id))
            return session.sendError("Toggle reaction failed: You are not a member of this chat")

        // Toggle the reaction
        messages.toggleReaction(messageId, loginUser.id, emoji)

        // Fetch the updated message
        val updatedMessage = messages.getMessage(messageId)
        if (updatedMessage != null)
        {
            // If this is a moment chat, send moment_edited to all viewers
            // Otherwise, use the regular message distribution
            if (chat.isMoment)
            {
                // Get all viewers of this moment chat
                val viewers = chatMembers.getMemberIds(chat.id)
                viewers.forEach { uid ->
                    SessionManager.forEachSession(uid) { s ->
                        val response = buildJsonObject {
                            put("packet", "moment_edited")
                            put("messageId", messageId)
                            put("content", updatedMessage.content)
                            put("reactions", contentNegotiationJson.encodeToJsonElement(updatedMessage.reactions))
                        }
                        s.send(contentNegotiationJson.encodeToString(response))
                    }
                }
            }
            else
            {
                distributeMessage(updatedMessage, silent = true)
            }
        }
    }
}
