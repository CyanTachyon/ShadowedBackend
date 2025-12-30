package moe.tachyon.shadowed.route.packets

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import moe.tachyon.shadowed.contentNegotiationJson
import moe.tachyon.shadowed.database.*
import moe.tachyon.shadowed.dataClass.*
import moe.tachyon.shadowed.route.*

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
        val (chatId, begin, count) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val cid = json.jsonObject["chatId"]!!.jsonPrimitive.int.let(::ChatId)
            val start = json.jsonObject["begin"]!!.jsonPrimitive.long
            val limit = json.jsonObject["count"]!!.jsonPrimitive.int
            Triple(cid, start, limit)
        }.getOrNull() ?: return session.sendError("Get messages failed: Invalid packet format")

        if (getKoin().get<ChatMembers>().getUserChats(loginUser.id).none { it.chatId == chatId })
            return session.sendError("Get messages failed: You are not a member of this chat")

        val msgs = getKoin().get<Messages>().getChatMessages(chatId, begin, count)
        if (begin == 0L)
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
        )
        val (chatId, message, type) = runCatching()
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
        val isMember = if (chat?.isMoment == true)
        {
            chatMembers.isMember(chatId, loginUser.id)
        }
        else
        {
            chatMembers.getUserChats(loginUser.id).any { it.chatId == chatId }
        }

        if (!isMember)
            return session.sendError("Send message failed: You are not a member of this chat")

        val messages = getKoin().get<Messages>()
        val msgId = messages.addChatMessage(
            content = if (type == MessageType.TEXT) message else "",
            type = type,
            chatId = chatId,
            senderId = loginUser.id
        )
        chats.updateTime(chatId)
        chatMembers.incrementUnread(chatId, loginUser.id)
        chatMembers.resetUnread(chatId, loginUser.id)
        distributeMessage(
            Message(
                id = msgId,
                content = message,
                type = type,
                chatId = chatId,
                senderId = loginUser.id,
                senderName = loginUser.username,
                time = Clock.System.now().toEpochMilliseconds(),
                isRead = false,
            )
        )
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
        session.sendInfo("Chat renamed successfully")
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
        session.sendInfo("Do Not Disturb set to $doNotDisturb")
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
        )
        val (messageId, newContent) = runCatching()
        {
            contentNegotiationJson.decodeFromString<EditMessage>(packetData)
        }.getOrNull() ?: return session.sendError("Edit message failed: Invalid packet format")

        val messages = getKoin().get<Messages>()
        val originalMessage = messages.getMessage(messageId)
            ?: return session.sendError("Edit message failed: Message not found")

        if (originalMessage.senderId != loginUser.id)
            return session.sendError("Edit message failed: You can only edit your own messages")

        if (getKoin().get<ChatMembers>().getUserChats(loginUser.id).none { it.chatId == originalMessage.chatId })
            return session.sendError("Edit message failed: You are not a member of this chat")

        messages.updateMessage(messageId, newContent)

        distributeMessage(originalMessage.copy(
            content = newContent ?: "",
            type = if (newContent == null) MessageType.TEXT else originalMessage.type,
        ))
    }
}
