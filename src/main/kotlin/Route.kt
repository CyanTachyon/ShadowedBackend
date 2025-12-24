package moe.tachyon.shadowed

import at.favre.lib.crypto.bcrypt.BCrypt
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.io.asSource
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import moe.tachyon.shadowed.dataClass.*
import moe.tachyon.shadowed.dataClass.ChatId.Companion.toChatId
import moe.tachyon.shadowed.database.*
import moe.tachyon.shadowed.logger.ShadowedLogger
import moe.tachyon.shadowed.utils.FileUtils
import org.koin.core.component.KoinComponent
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

private val sessions = mutableMapOf<UserId, MutableSet<DefaultWebSocketServerSession>>()
private val sessionsMutex = Mutex()
private const val SERVER_AUTH_KEY = "shadowed_auth_key_v1"

private fun getKoin() = object : KoinComponent { }.getKoin()
private val logger = ShadowedLogger.getLogger()

fun Application.router() = routing()
{
    get("/")
    {
        call.respondBytes(
            Loader.getResource("/static/index.html")!!.readAllBytes(),
            contentType = ContentType.Text.Html
        )
    }
    staticResources("/", "static")

    val users by getKoin().inject<Users>()

    route("/api")
    {
        get("/config")
        {
            call.respond(
                buildJsonObject()
                {
                    put("checkingKey", environment.config.property("checkingKey").getString())
                }
            )
        }

        get("/auth/params")
        {
            call.respond(
                buildJsonObject()
                {
                    put("authKey", SERVER_AUTH_KEY)
                }
            )
        }

        post("/register")
        {
            @Serializable
            data class RegisterRequest(
                val username: String,
                val password: String,
                val publicKey: String,
                val privateKey: String,
            )
            val registerRequest = call.receive<RegisterRequest>()
            if (registerRequest.username.any { it !in ('a'..'z') + ('A'..'Z') + ('0'..'9') + '_' })
            {
                call.respond(
                    buildJsonObject {
                        put("success", false)
                        put("message", "Username contains invalid characters")
                    }
                )
                return@post
            }
            if (registerRequest.username.length !in 4..20)
            {
                call.respond(
                    buildJsonObject {
                        put("success", false)
                        put("message", "Username length must be between 4 and 20 characters")
                    }
                )
                return@post
            }
            if (users.getUserByUsername(registerRequest.username) == null)
            {
                 val id = users.createUser(
                    username = registerRequest.username,
                    encryptedPassword = encryptPassword(registerRequest.password),
                    publicKey = registerRequest.publicKey,
                    encryptedPrivateKey = registerRequest.privateKey,
                )
                if (id != null)
                {
                     call.respond(
                        buildJsonObject {
                            put("success", true)
                            put("userId", id.value)
                        }
                    )
                }
                else
                {
                     call.respond(
                        buildJsonObject {
                            put("success", false)
                            put("message", "the username already exists")
                        }
                    )
                }
            }
            else
            {
                call.respond(
                    buildJsonObject {
                        put("success", false)
                        put("message", "Username already exists")
                    }
                )
            }
        }

        post("/resetPassword")
        {
            @Serializable
            data class ResetPasswordRequest(
                val username: String,
                val oldPassword: String,
                val newPassword: String,
                val privateKey: String,
            )

            val resetRequest = call.receive<ResetPasswordRequest>()
            val user = users.getUserByUsername(resetRequest.username)
            if (user == null)
            {
                call.respond(
                    buildJsonObject {
                        put("success", false)
                        put("message", "User not found")
                    }
                )
                return@post
            }
            if (!verifyPassword(resetRequest.oldPassword, user.password))
            {
                call.respond(
                    buildJsonObject {
                        put("success", false)
                        put("message", "Old password is incorrect")
                    }
                )
                return@post
            }
            users.updatePasswordAndKey(
                userId = user.id,
                newEncryptedPassword = encryptPassword(resetRequest.newPassword),
                newEncryptedPrivateKey = resetRequest.privateKey,
            )
            return@post call.respond(
                buildJsonObject {
                    put("success", true)
                    put("message", "Password and key updated successfully")
                }
            )
        }

        route("/user")
        {
            post("/avatar")
            {
                // Simple auth check via headers
                val username = call.request.header("X-Auth-User")
                val passwordHash = call.request.header("X-Auth-Token") // Encrypted password hash

                if (username == null || passwordHash == null)
                {
                    call.respond(HttpStatusCode.Unauthorized)
                    return@post
                }

                val users = getKoin().get<Users>()
                val userAuth = users.getUserByUsername(username)

                if (userAuth == null || !verifyPassword(passwordHash, userAuth.password))
                {
                    call.respond(HttpStatusCode.Unauthorized)
                    return@post
                }

                // Process multipart
                val multipart = call.receiveMultipart()

                while (true)
                {
                    val part = multipart.readPart() ?: break
                    if (part is PartData.FileItem)
                    {
                        logger.warning("Failed to process image")
                        {
                            val fileBytes = part.provider().readBuffer().readBytes()
                            val image = ImageIO.read(ByteArrayInputStream(fileBytes))
                            if (image != null) FileUtils.setAvatar(userAuth.id, image)
                            else call.respond(HttpStatusCode.BadRequest, "Invalid image format")
                        }.getOrThrow()
                    }
                    part.dispose()
                }

                call.respond(HttpStatusCode.OK)
            }

            get("/{id}/avatar")
            {
                val idStr = call.parameters["id"]
                val id = idStr?.toIntOrNull()

                if (id == null)
                {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }

                val avatarImage = FileUtils.getAvatar(UserId(id))

                call.response.header(HttpHeaders.CacheControl, "max-age=300")
                if (avatarImage != null)
                {
                    call.respondOutputStream(ContentType.Image.PNG)
                    {
                        ImageIO.write(avatarImage, "png", this)
                    }
                }
                else
                {
                    call.respondText("""
                        <svg width="100" height="100" viewBox="0 0 64 64" xmlns="http://www.w3.org/2000/svg">
                          <rect x="0" y="0" width="64" height="64" rx="16" fill="#E3F2FD"/>
                          <g fill="#42A5F5">
                            <circle cx="32" cy="24" r="10"/>
                            <path d="M32 38C22 38 14 44 12 52C12 52 12 54 14 54H50C52 54 52 52 52 52C50 44 42 38 32 38Z"/>
                          </g>
                        </svg>
                    """.trimIndent(), contentType = ContentType.Image.SVG)
                }
            }

            get("/publicKey")
            {
                val username = call.parameters["username"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val user = getKoin().get<Users>().getUserByUsername(username) ?: return@get call.respond(HttpStatusCode.NotFound)
                val response = buildJsonObject()
                {
                    put("publicKey", user.publicKey)
                }
                call.respond(response)
            }
        }

        post("/send_file")
        {
            val chat = call.request.header("X-Chat-Id")?.toIntOrNull()?.let(::ChatId)
            val username = call.request.header("X-Auth-User")
            val passwordHash = call.request.header("X-Auth-Token")
            val messageType = call.request.header("X-Message-Type")?.let(MessageType::fromString)
            val metadata = call.request.header("X-Message-Metadata") ?: ""
            val bodySize = call.request.header(HttpHeaders.ContentLength)?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.LengthRequired)
            if (bodySize > environment.config.property("maxImageSize").getString().toLong())
            {
                call.respond(HttpStatusCode.PayloadTooLarge, "File size exceeds limit")
                return@post
            }
            val fileBase64 = call.receiveStream()
            if (chat == null || username == null || passwordHash == null || messageType == null)
                return@post call.respond(HttpStatusCode.BadRequest)
            val users = getKoin().get<Users>()
            val userAuth = users.getUserByUsername(username)
            if (userAuth == null || !verifyPassword(passwordHash, userAuth.password))
                return@post call.respond(HttpStatusCode.Unauthorized)
            val messageId = getKoin().get<Messages>().addChatMessage(
                content = metadata,
                type = messageType,
                chatId = chat,
                senderId = userAuth.id
            )
            getKoin().get<Chats>().updateTime(chat)
            getKoin().get<ChatMembers>().incrementUnread(chat, userAuth.id)
            getKoin().get<ChatMembers>().resetUnread(chat, userAuth.id)
            FileUtils.saveChatFile(messageId, fileBase64)
            call.respond(
                buildJsonObject()
                {
                    put("messageId", messageId)
                }
            )
            distributeMessage(
                chatId = chat,
                messageId = messageId,
                content = metadata,
                type = messageType,
                sender = userAuth,
            )
        }

        get("/file/{messageId}")
        {
            val messageId = call.pathParameters["messageId"]?.toLongOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
            val fileBytes = FileUtils.getChatFile(messageId) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.response.header(HttpHeaders.CacheControl, "max-age=${30*24*60*60}") // 30 days
            call.respondSource(fileBytes.asSource(), ContentType.Text.Plain)
        }

        webSocket()
    }
}

@Serializable
private data class NotifyPacket(
    val type: Type,
    val message: String,
)
{
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val packet = "notify"
    @Serializable
    enum class Type
    {
        INFO,
        WARNING,
        ERROR,
    }
}

private fun Route.webSocket() = webSocket("/socket") socket@
{
    var loginUser: User? = null
    try
    {
        incoming.consumeAsFlow().filterIsInstance<Frame.Text>().collect()
        { frame ->
            val data = frame.readText().split("\n")
            val packetName = data[0]
            val packetData = data.subList(1, data.size).joinToString("\n")

            if (packetName.equals("login", ignoreCase = true))
            {
                val (username, password) = runCatching()
                {
                    val json = contentNegotiationJson.parseToJsonElement(packetData)
                    val user = json.jsonObject["username"]!!.jsonPrimitive.content
                    val pass = json.jsonObject["password"]!!.jsonPrimitive.content
                    Pair(user, pass)
                }.getOrNull() ?: return@collect send(
                    contentNegotiationJson.encodeToString(
                        NotifyPacket(
                            type = NotifyPacket.Type.ERROR,
                            message = "Login failed: Invalid packet format",
                        )
                    )
                )
                val user = getKoin().get<Users>().getUserByUsername(username) ?: return@collect send(
                    contentNegotiationJson.encodeToString(
                        NotifyPacket(
                            type = NotifyPacket.Type.ERROR,
                            message = "Login failed: User not found",
                        )
                    )
                )
                if (!verifyPassword(password, user.password))
                {
                    return@collect send(
                        contentNegotiationJson.encodeToString(
                            NotifyPacket(
                                type = NotifyPacket.Type.ERROR,
                                message = "Login failed: Incorrect password",
                            )
                        )
                    )
                }
                loginUser = user
                sessionsMutex.withLock()
                {
                    sessions.getOrPut(user.id) { mutableSetOf() }.add(this)
                }

                val response = buildJsonObject()
                {
                    put("packet", "login_success")
                    put("user", contentNegotiationJson.encodeToJsonElement(user))
                }
                send(contentNegotiationJson.encodeToString(response))
                return@collect
            }

            if (loginUser == null)
            {
                val response = buildJsonObject()
                {
                    put("packet", "require_login")
                }
                return@collect send(contentNegotiationJson.encodeToString(response))
            }

            if (packetName.equals("get_chats", ignoreCase = true))
            {
                sendChatList(loginUser.id)
            }

            if (packetName.equals("get_public_key_by_username", ignoreCase = true))
            {
                val username = runCatching()
                {
                    val json = contentNegotiationJson.parseToJsonElement(packetData)
                    json.jsonObject["username"]!!.jsonPrimitive.content
                }.getOrNull() ?: return@collect

                val user = getKoin().get<Users>().getUserByUsername(username)

                if (user != null)
                {
                    val response = buildJsonObject()
                    {
                        put("packet", "public_key_by_username")
                        put("username", username) // Echo back for correlation
                        put("publicKey", user.publicKey)
                    }
                    send(contentNegotiationJson.encodeToString(response))
                }
                else
                {
                    val response = NotifyPacket(
                        type = NotifyPacket.Type.ERROR,
                        message = "Failed to get public key: User not found",
                    )
                    send(contentNegotiationJson.encodeToString(response))
                }
                return@collect
            }

            if (packetName.equals("add_friend", ignoreCase = true))
            {
                val (targetUsername, keyForFriend, keyForSelf) = runCatching()
                {
                    val json = contentNegotiationJson.parseToJsonElement(packetData)
                    val u = json.jsonObject["targetUsername"]!!.jsonPrimitive.content
                    val kf = json.jsonObject["keyForFriend"]!!.jsonPrimitive.content
                    val ks = json.jsonObject["keyForSelf"]!!.jsonPrimitive.content
                    Triple(u, kf, ks)
                }.getOrNull() ?: return@collect send(
                    contentNegotiationJson.encodeToString(
                        NotifyPacket(
                            type = NotifyPacket.Type.ERROR,
                            message = "Add friend failed: Invalid packet format",
                        )
                    )
                )

                if (targetUsername.equals(loginUser.username, ignoreCase = true))return@collect send(
                    contentNegotiationJson.encodeToString(
                        NotifyPacket(
                            type = NotifyPacket.Type.ERROR,
                            message = "Add friend failed: Cannot add yourself",
                        )
                    )
                )

                val targetUser = getKoin().get<Users>().getUserByUsername(targetUsername) ?: return@collect send(
                    contentNegotiationJson.encodeToString(
                        NotifyPacket(
                            type = NotifyPacket.Type.ERROR,
                            message = "Add friend failed: User not found",
                        )
                    )
                )

                val friends = getKoin().get<Friends>()
                val chatMembers = getKoin().get<ChatMembers>()
                
                // Check if chat already exists
                val existingMembership = chatMembers.getUserChats(loginUser.id)
                    .find { chat -> 
                        chat.parsedOtherNames.size == 1 && 
                        chat.parsedOtherNames.contains(targetUser.username) 
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
                    chatId = friends.addFriend(loginUser.id, targetUser.id) ?: return@collect send(
                        contentNegotiationJson.encodeToString(
                            NotifyPacket(
                                type = NotifyPacket.Type.ERROR,
                                message = "Chat creation failed",
                            )
                        )
                    )
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
                send(contentNegotiationJson.encodeToString(response))
                for (s in sessionsMutex.withLock { sessions[targetUser.id]?.toList() ?: emptyList() }) runCatching()
                {
                    s.sendChatList(targetUser.id)
                }
            }

            if (packetName.equals("create_group", ignoreCase = true))
            {
                val (groupName, memberUsernames, encryptedKeys) = runCatching()
                {
                    val json = contentNegotiationJson.parseToJsonElement(packetData)
                    val name = json.jsonObject["name"]?.jsonPrimitive?.takeUnless { it is JsonNull }?.content ?: "New Group"
                    val members = json.jsonObject["memberUsernames"]!!.jsonArray.map { it.jsonPrimitive.content }
                    val keys = json.jsonObject["encryptedKeys"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
                    Triple(name, members, keys)
                }.getOrNull() ?: return@collect send(
                    contentNegotiationJson.encodeToString(
                        NotifyPacket(
                            type = NotifyPacket.Type.ERROR,
                            message = "Create group failed: Invalid packet format",
                        )
                    )
                )

                // Validate all members exist and get their user objects
                val users = getKoin().get<Users>()
                val memberUsers = memberUsernames.mapNotNull()
                { username ->
                    users.getUserByUsername(username)
                }

                if (memberUsers.size != memberUsernames.size)
                {
                    return@collect send(
                        contentNegotiationJson.encodeToString(
                            NotifyPacket(
                                type = NotifyPacket.Type.ERROR,
                                message = "Create group failed: One or more users not found",
                            )
                        )
                    )
                }

                // Check all members have keys
                val missingKeys = memberUsernames.filter { !encryptedKeys.containsKey(it) }
                if (missingKeys.isNotEmpty())
                {
                    return@collect send(
                        contentNegotiationJson.encodeToString(
                            NotifyPacket(
                                type = NotifyPacket.Type.ERROR,
                                message = "Create group failed: Missing keys for: ${missingKeys.joinToString()}",
                            )
                        )
                    )
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
                send(
                    contentNegotiationJson.encodeToString(
                        NotifyPacket(
                            type = NotifyPacket.Type.INFO,
                            message = "Group created successfully",
                        )
                    )
                )
                for (user in memberUsers)
                    for (s in sessionsMutex.withLock { sessions[user.id]?.toList() ?: emptyList() })
                        runCatching()
                        {
                            s.sendChatList(user.id)
                        }
            }

            if (packetName.equals("send_message", ignoreCase = true))
            {
                val (chatId, message, type) = runCatching()
                {
                    val json = contentNegotiationJson.parseToJsonElement(packetData)
                    val cid = json.jsonObject["chatId"]!!.jsonPrimitive.int.let(::ChatId)
                    val msg = json.jsonObject["message"]!!.jsonPrimitive.content
                    val t = json.jsonObject["type"]!!.jsonPrimitive.content.let(MessageType::fromString)
                    Triple(cid, msg, t)
                }.getOrNull() ?: return@collect send(
                    contentNegotiationJson.encodeToString(
                        NotifyPacket(
                            type = NotifyPacket.Type.ERROR,
                            message = "Send message failed: Invalid packet format",
                        )
                    )
                )
                if (getKoin().get<ChatMembers>().getUserChats(loginUser.id).none { it.chatId == chatId }) return@collect send(
                    contentNegotiationJson.encodeToString(
                        NotifyPacket(
                            type = NotifyPacket.Type.ERROR,
                            message = "Send message failed: You are not a member of this chat",
                        )
                    )
                )
                val messages = getKoin().get<Messages>()
                val msgId = messages.addChatMessage(
                    content = if (type == MessageType.TEXT) message else "",
                    type = type,
                    chatId = chatId,
                    senderId = loginUser.id
                )
                getKoin().get<Chats>().updateTime(chatId)
                getKoin().get<ChatMembers>().incrementUnread(chatId, loginUser.id)
                getKoin().get<ChatMembers>().resetUnread(chatId, loginUser.id)
                distributeMessage(
                    chatId = chatId,
                    messageId = msgId,
                    content = message,
                    type = type,
                    sender = loginUser,
                )
            }

            if (packetName.equals("get_messages", ignoreCase = true))
            {
                val (chatId, begin, count) = runCatching()
                {
                    val json = contentNegotiationJson.parseToJsonElement(packetData)
                    val cid = json.jsonObject["chatId"]!!.jsonPrimitive.int.let(::ChatId)
                    val start = json.jsonObject["begin"]!!.jsonPrimitive.long
                    val limit = json.jsonObject["count"]!!.jsonPrimitive.int
                    Triple(cid, start, limit)
                }.getOrNull() ?: return@collect send(
                    contentNegotiationJson.encodeToString(
                        NotifyPacket(
                            type = NotifyPacket.Type.ERROR,
                            message = "Get messages failed: Invalid packet format",
                        )
                    )
                )

                if (getKoin().get<ChatMembers>().getUserChats(loginUser.id).none { it.chatId == chatId }) return@collect send(
                    contentNegotiationJson.encodeToString(
                        NotifyPacket(
                            type = NotifyPacket.Type.ERROR,
                            message = "Get messages failed: You are not a member of this chat",
                        )
                    )
                )

                val msgs = getKoin().get<Messages>().getChatMessages(chatId, begin, count)
                if (begin == 0L)
                {
                    getKoin().get<ChatMembers>().resetUnread(chatId, loginUser.id)
                    sendUnreadCount(loginUser.id, chatId)
                }
                val response = buildJsonObject()
                {
                    put("packet", "messages_list")
                    put("messages", contentNegotiationJson.encodeToJsonElement(msgs))
                    put("chatId", chatId.value)
                }
                return@collect send(contentNegotiationJson.encodeToString(response))
            }

            if (packetName.equals("get_friends", ignoreCase = true))
            {
                val currentId = loginUser.id
                val friendsList = getKoin().get<Friends>().getFriends(currentId)

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
                            }
                        }
                    })
                }
                return@collect send(contentNegotiationJson.encodeToString(response))
            }

            if (packetName.equals("get_chat_details", ignoreCase = true))
            {
                val chatId = runCatching()
                {
                    val json = contentNegotiationJson.parseToJsonElement(packetData)
                    json.jsonObject["chatId"]!!.jsonPrimitive.int.let(::ChatId)
                }.getOrNull() ?: return@collect

                val chats = getKoin().get<Chats>()
                val chat = chats.getChat(chatId) ?: return@collect

                val members = getKoin().get<ChatMembers>().getChatMembersDetailed(chatId)

                return@collect sendChatDetails(chat, members)
            }

            if (packetName.equals("rename_chat", ignoreCase = true))
            {
                val (chatId, newName) = runCatching {
                    val json = contentNegotiationJson.parseToJsonElement(packetData)
                    val id = json.jsonObject["chatId"]!!.jsonPrimitive.int.let(::ChatId)
                    val name = json.jsonObject["newName"]!!.jsonPrimitive.content
                    id to name
                }.getOrNull() ?: return@collect

                val chats = getKoin().get<Chats>()
                val isOwner = chats.isChatOwner(chatId, loginUser.id)

                if (!isOwner)
                {
                    return@collect send(contentNegotiationJson.encodeToString(
                        NotifyPacket(NotifyPacket.Type.ERROR, "Only owner can rename chat")
                    ))
                }

                chats.renameChat(chatId, newName)

                send(contentNegotiationJson.encodeToString(
                    NotifyPacket(NotifyPacket.Type.INFO, "Chat renamed successfully")
                ))
            }

            if (packetName.equals("add_member_to_chat", ignoreCase = true))
            {
                val (chatIdVal, username, encryptedKey) = runCatching()
                {
                    val json = contentNegotiationJson.parseToJsonElement(packetData)
                    val id = json.jsonObject["chatId"]!!.jsonPrimitive.int
                    val user = json.jsonObject["username"]!!.jsonPrimitive.content
                    val key = json.jsonObject["encryptedKey"]!!.jsonPrimitive.content
                    Triple(id, user, key)
                }.getOrNull() ?: return@collect send(
                    contentNegotiationJson.encodeToString(
                        NotifyPacket(NotifyPacket.Type.ERROR, "Invalid packet format")
                    )
                )

                val chatId = ChatId(chatIdVal)
                
                // Verify current user is a member of this chat
                val chatMembers = getKoin().get<ChatMembers>()
                val isMember = chatMembers.getUserChats(loginUser.id).any { it.chatId == chatId }
                
                if (!isMember)
                {
                    return@collect send(
                        contentNegotiationJson.encodeToString(
                            NotifyPacket(NotifyPacket.Type.ERROR, "You are not a member of this chat")
                        )
                    )
                }

                // Get target user
                val targetUser = getKoin().get<Users>().getUserByUsername(username) ?: return@collect send(
                    contentNegotiationJson.encodeToString(
                        NotifyPacket(NotifyPacket.Type.ERROR, "User not found: $username")
                    )
                )

                // Check if user is already a member
                val alreadyMember = chatMembers.getUserChats(targetUser.id).any { it.chatId == chatId }
                if (alreadyMember)
                {
                    return@collect send(
                        contentNegotiationJson.encodeToString(
                            NotifyPacket(NotifyPacket.Type.ERROR, "$username is already a member")
                        )
                    )
                }

                // Add the new member
                chatMembers.addMember(chatId, targetUser.id, encryptedKey)

                send(
                    contentNegotiationJson.encodeToString(
                        NotifyPacket(NotifyPacket.Type.INFO, "Member added successfully")
                    )
                )

                val members = chatMembers.getChatMembersDetailed(chatId)
                val chat = getKoin().get<Chats>().getChat(chatId)!!
                for (user in members)
                    for (s in sessionsMutex.withLock { sessions[user.id]?.toList() ?: emptyList() }) runCatching()
                    {
                        s.sendChatDetails(chat, members)
                    }
                for (s in sessionsMutex.withLock { sessions[targetUser.id]?.toList() ?: emptyList() }) runCatching()
                {
                    s.sendChatList(targetUser.id)
                }
            }

            if (packetName.equals("kick_member_from_chat", ignoreCase = true))
            {
                val (chatId, username) = runCatching()
                {
                    val json = contentNegotiationJson.parseToJsonElement(packetData)
                    val id = json.jsonObject["chatId"]!!.jsonPrimitive.int.toChatId()
                    val user = json.jsonObject["username"]!!.jsonPrimitive.content
                    Pair(id, user)
                }.getOrNull() ?: return@collect send(
                    contentNegotiationJson.encodeToString(
                        NotifyPacket(NotifyPacket.Type.ERROR, "Invalid packet format")
                    )
                )
                val chats = getKoin().get<Chats>()
                val isOwner = chats.isChatOwner(chatId, loginUser.id)
                if (!isOwner && loginUser.username != username) return@collect send(
                    contentNegotiationJson.encodeToString(
                        NotifyPacket(NotifyPacket.Type.ERROR, "Only owner can kick members")
                    )
                )
                if (isOwner && loginUser.username == username) return@collect send(
                    contentNegotiationJson.encodeToString(
                        NotifyPacket(NotifyPacket.Type.ERROR, "As owner, you cannot kick yourself")
                    )
                )

                val chatMembers = getKoin().get<ChatMembers>()
                val targetUser = getKoin().get<Users>().getUserByUsername(username) ?: return@collect send(
                    contentNegotiationJson.encodeToString(
                        NotifyPacket(NotifyPacket.Type.ERROR, "User not found: $username")
                    )
                )
                val members = chatMembers.getChatMembersDetailed(chatId).filterNot { it.id == targetUser.id }
                if (members.size <= 2) return@collect send(
                    contentNegotiationJson.encodeToString(
                        NotifyPacket(NotifyPacket.Type.ERROR, "Cannot kick member: Chat must have at least 3 members")
                    )
                )
                chatMembers.removeMember(chatId, targetUser.id)
                send(
                    contentNegotiationJson.encodeToString(
                        NotifyPacket(NotifyPacket.Type.INFO, "Member kicked successfully")
                    )
                )
                val chat = chats.getChat(chatId)!!
                for (user in members)
                {
                    for (s in sessionsMutex.withLock { sessions[user.id]?.toList() ?: emptyList() }) runCatching()
                    {
                        s.sendChatDetails(chat, members)
                    }
                }
                for (s in sessionsMutex.withLock { sessions[targetUser.id]?.toList() ?: emptyList() }) runCatching()
                {
                    s.sendChatList(targetUser.id)
                }
            }
        }
    }
    finally
    {
        loginUser?.let()
        {
            sessionsMutex.withLock()
            {
                val set = sessions[it.id] ?: return@withLock
                set.remove(this)
                if (set.isEmpty()) sessions.remove(it.id)
            }
        }
    }
}

private val hasher = BCrypt.with(BCrypt.Version.VERSION_2B)
private val verifier = BCrypt.verifyer(BCrypt.Version.VERSION_2B)
fun encryptPassword(password: String): String = hasher.hashToString(12, password.toCharArray())
fun verifyPassword(password: String, hash: String): Boolean = verifier.verify(password.toCharArray(), hash).verified

private suspend fun WebSocketSession.sendChatList(userId: UserId)
{
    val userChats = getKoin().get<ChatMembers>().getUserChats(userId)
    val response = buildJsonObject()
    {
        put("packet", "chats_list")
        put("chats", contentNegotiationJson.encodeToJsonElement(userChats))
    }
    return send(contentNegotiationJson.encodeToString(response))
}

private suspend fun WebSocketSession.sendUnreadCount(userId: UserId, chatId: ChatId)
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

private suspend fun distributeMessage(
    chatId: ChatId,
    messageId: Long,
    content: String,
    type: MessageType,
    sender: User,
)
{
    val members = getKoin().get<ChatMembers>().getMemberIds(chatId)
    members.forEach()
    { uid ->
        for (s in sessionsMutex.withLock { sessions[uid]?.toList() ?: emptyList() })
        {
            s.sendUnreadCount(uid, chatId)
            val pushData = buildJsonObject()
            {
                put("packet", "receive_message")
                put("message", buildJsonObject()
                {
                    put("id", messageId)
                    put("content", content)
                    put("type", type.name)
                    put("chatId", chatId.value)
                    put("senderId", sender.id.value)
                    put("senderName", sender.username)
                    put("time", Clock.System.now().toEpochMilliseconds())
                })
            }
            logger.warning("sending message to $uid")
            {
                s.send(contentNegotiationJson.encodeToString(pushData))
            }
        }
    }
}

private suspend fun WebSocketSession.sendChatDetails(chat: Chat, members: List<User>)
{
    val response = buildJsonObject()
    {
        put("packet", "chat_details")
        put("chat", buildJsonObject()
        {
            put("id", chat.id.value)
            put("name", chat.name)
            put("ownerId", chat.owner.value)
            put("isPrivate", chat.private)
            put("members", contentNegotiationJson.encodeToJsonElement(members))
        })
    }
    return send(contentNegotiationJson.encodeToString(response))
}