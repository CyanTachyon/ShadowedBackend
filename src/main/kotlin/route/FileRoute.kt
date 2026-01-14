package moe.tachyon.shadowed.route

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.websocket.*
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import moe.tachyon.shadowed.contentNegotiationJson
import moe.tachyon.shadowed.dataClass.ChatId
import moe.tachyon.shadowed.dataClass.Message
import moe.tachyon.shadowed.dataClass.MessageType
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.ChatMembers
import moe.tachyon.shadowed.database.Chats
import moe.tachyon.shadowed.database.Messages
import moe.tachyon.shadowed.database.Users
import moe.tachyon.shadowed.logger.ShadowedLogger
import moe.tachyon.shadowed.utils.FileUtils
import java.util.*
import java.util.concurrent.ConcurrentHashMap

private val logger = ShadowedLogger.getLogger()

// 上传任务信息
@Serializable
data class UploadTaskInfo(
    val uploadId: String,
    val chatId: ChatId,
    val userId: UserId,
    val messageType: MessageType,
    val metadata: String,
    val totalChunks: Int,
    val totalSize: Long,
    val createdAt: Long
)

// 内存中的上传任务缓存
private val uploadTasks = ConcurrentHashMap<String, UploadTaskInfo>()

fun Route.fileRoute()
{
    // 获取文件大小限制
    fun getMaxSize(messageType: MessageType): Long
    {
        return when (messageType)
        {
            MessageType.IMAGE -> environment.config.property("maxImageSize").getString().toLong()
            MessageType.VIDEO -> environment.config.propertyOrNull("maxVideoSize")?.getString()?.toLong() ?: (1024L * 1024 * 1024)
            MessageType.FILE -> environment.config.propertyOrNull("maxFileSize")?.getString()?.toLong() ?: (1024L * 1024 * 1024)
            else -> environment.config.property("maxImageSize").getString().toLong()
        }
    }

    // 获取分片大小
    fun getChunkSize(): Long
    {
        return environment.config.propertyOrNull("upload.chunkSize")?.getString()?.toLong() ?: (5L * 1024 * 1024)
    }

    post("/send_file")
    {
        val chat = call.request.header("X-Chat-Id")?.toIntOrNull()?.let(::ChatId)
        val username = call.request.header("X-Auth-User")
        val passwordHash = call.request.header("X-Auth-Token")
        val messageType = call.request.header("X-Message-Type")?.let(MessageType::fromString)
        val metadata = call.request.header("X-Message-Metadata") ?: ""
        val bodySize = call.request.header(HttpHeaders.ContentLength)?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.LengthRequired)
        if (messageType != null && bodySize > getMaxSize(messageType))
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
        if (!getKoin().get<ChatMembers>().isMember(chat, userAuth.id))
            return@post call.respond(HttpStatusCode.Forbidden)
        val messages = getKoin().get<Messages>()
        val burnTime = getKoin().get<Chats>().getChat(chat)?.burnTime
        val messageId = messages.addChatMessage(
            content = metadata,
            type = messageType,
            chatId = chat,
            senderId = userAuth.id,
            burnTime = burnTime,
            replyTo = null,
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
            Message(
                id = messageId,
                content = metadata,
                type = messageType,
                chatId = chat,
                senderId = userAuth.id,
                senderName = userAuth.username,
                time = Clock.System.now().toEpochMilliseconds(),
                readAt = null,
                senderIsDonor = userAuth.isDonor,
                replyTo = null,
                burn = null,
            ),
            silent = false
        )
    }

    // === 分片上传 API ===

    // 初始化上传任务
    post("/upload/init")
    {
        @Serializable
        data class InitRequest(
            val chatId: ChatId,
            val messageType: String,
            val metadata: String,
            val totalChunks: Int,
            val totalSize: Long
        )

        val username = call.request.header("X-Auth-User")
        val passwordHash = call.request.header("X-Auth-Token")
        if (username == null || passwordHash == null)
            return@post call.respond(HttpStatusCode.BadRequest, "Missing auth headers")

        val users = getKoin().get<Users>()
        val userAuth = users.getUserByUsername(username)
        if (userAuth == null || !verifyPassword(passwordHash, userAuth.password))
            return@post call.respond(HttpStatusCode.Unauthorized)

        val request = call.receive<InitRequest>()
        val messageType = MessageType.fromString(request.messageType)

        // 验证聊天权限
        if (!getKoin().get<ChatMembers>().isMember(request.chatId, userAuth.id))
            return@post call.respond(HttpStatusCode.Forbidden)

        // 验证文件大小
        if (request.totalSize > getMaxSize(messageType))
            return@post call.respond(HttpStatusCode.PayloadTooLarge, "File size exceeds limit")

        // 创建上传任务
        val uploadId = UUID.randomUUID().toString()
        val taskInfo = UploadTaskInfo(
            uploadId = uploadId,
            chatId = request.chatId,
            userId = userAuth.id,
            messageType = messageType,
            metadata = request.metadata,
            totalChunks = request.totalChunks,
            totalSize = request.totalSize,
            createdAt = Clock.System.now().toEpochMilliseconds()
        )
        uploadTasks[uploadId] = taskInfo

        // 创建分片目录
        FileUtils.getUploadDir(uploadId)

        call.respond(
            buildJsonObject()
            {
                put("uploadId", uploadId)
                put("chunkSize", getChunkSize())
            }
        )
    }

    // 上传分片
    post("/upload/{uploadId}/chunk/{chunkIndex}")
    {
        val uploadId = call.pathParameters["uploadId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val chunkIndex = call.pathParameters["chunkIndex"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
        val username = call.request.header("X-Auth-User")
        val passwordHash = call.request.header("X-Auth-Token")
        if (username == null || passwordHash == null)
            return@post call.respond(HttpStatusCode.BadRequest, "Missing auth headers")

        val users = getKoin().get<Users>()
        val userAuth = users.getUserByUsername(username)
        if (userAuth == null || !verifyPassword(passwordHash, userAuth.password))
            return@post call.respond(HttpStatusCode.Unauthorized)

        val taskInfo = uploadTasks[uploadId] ?: return@post call.respond(HttpStatusCode.NotFound, "Upload task not found")
        if (taskInfo.userId != userAuth.id)
            return@post call.respond(HttpStatusCode.Forbidden)

        if (chunkIndex < 0 || chunkIndex >= taskInfo.totalChunks)
            return@post call.respond(HttpStatusCode.BadRequest, "Invalid chunk index")

        // 保存分片
        val chunkData = call.receiveStream()
        FileUtils.saveChunk(uploadId, chunkIndex, chunkData)

        val uploadedChunks = FileUtils.getUploadedChunks(uploadId)
        call.respond(
            buildJsonObject()
            {
                put("chunkIndex", chunkIndex)
                put("uploadedCount", uploadedChunks.size)
            }
        )
    }

    // 查询上传状态
    get("/upload/{uploadId}/status")
    {
        val uploadId = call.pathParameters["uploadId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val username = call.request.header("X-Auth-User")
        val passwordHash = call.request.header("X-Auth-Token")
        if (username == null || passwordHash == null)
            return@get call.respond(HttpStatusCode.BadRequest, "Missing auth headers")

        val users = getKoin().get<Users>()
        val userAuth = users.getUserByUsername(username)
        if (userAuth == null || !verifyPassword(passwordHash, userAuth.password))
            return@get call.respond(HttpStatusCode.Unauthorized)

        val taskInfo = uploadTasks[uploadId] ?: return@get call.respond(HttpStatusCode.NotFound, "Upload task not found")
        if (taskInfo.userId != userAuth.id)
            return@get call.respond(HttpStatusCode.Forbidden)

        val uploadedChunks = FileUtils.getUploadedChunks(uploadId)
        call.respond(
            buildJsonObject()
            {
                put("uploadId", uploadId)
                put("totalChunks", taskInfo.totalChunks)
                put("uploadedChunks", contentNegotiationJson.encodeToJsonElement(uploadedChunks))
                put("isComplete", uploadedChunks.size == taskInfo.totalChunks)
            }
        )
    }

    // 完成上传
    post("/upload/{uploadId}/complete")
    {
        val uploadId = call.pathParameters["uploadId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val username = call.request.header("X-Auth-User")
        val passwordHash = call.request.header("X-Auth-Token")
        if (username == null || passwordHash == null)
            return@post call.respond(HttpStatusCode.BadRequest, "Missing auth headers")

        val users = getKoin().get<Users>()
        val userAuth = users.getUserByUsername(username)
        if (userAuth == null || !verifyPassword(passwordHash, userAuth.password))
            return@post call.respond(HttpStatusCode.Unauthorized)

        val taskInfo = uploadTasks[uploadId] ?: return@post call.respond(HttpStatusCode.NotFound, "Upload task not found")
        if (taskInfo.userId != userAuth.id)
            return@post call.respond(HttpStatusCode.Forbidden)

        // 检查是否所有分片都已上传
        val uploadedChunks = FileUtils.getUploadedChunks(uploadId)
        if (uploadedChunks.size != taskInfo.totalChunks)
            return@post call.respond(HttpStatusCode.BadRequest, "Not all chunks uploaded: ${uploadedChunks.size}/${taskInfo.totalChunks}")

        val messages = getKoin().get<Messages>()

        // 创建消息记录
        val messageId = messages.addChatMessage(
            content = taskInfo.metadata,
            type = taskInfo.messageType,
            chatId = taskInfo.chatId,
            senderId = userAuth.id,
            burnTime = getKoin().get<Chats>().getChat(taskInfo.chatId)?.burnTime,
            replyTo = null,
        )

        // 合并分片
        val mergeSuccess = FileUtils.mergeChunks(uploadId, messageId, taskInfo.totalChunks)
        if (!mergeSuccess)
        {
            // 合并失败，删除消息记录
            messages.deleteMessage(messageId)
            return@post call.respond(HttpStatusCode.InternalServerError, "Failed to merge chunks")
        }

        // 更新聊天时间和未读计数
        getKoin().get<Chats>().updateTime(taskInfo.chatId)
        getKoin().get<ChatMembers>().incrementUnread(taskInfo.chatId, userAuth.id)
        getKoin().get<ChatMembers>().resetUnread(taskInfo.chatId, userAuth.id)

        // 移除上传任务
        uploadTasks.remove(uploadId)

        call.respond(
            buildJsonObject()
            {
                put("messageId", messageId)
            }
        )

        // 推送消息
        distributeMessage(
            Message(
                id = messageId,
                content = taskInfo.metadata,
                type = taskInfo.messageType,
                chatId = taskInfo.chatId,
                senderId = userAuth.id,
                senderName = userAuth.username,
                time = Clock.System.now().toEpochMilliseconds(),
                readAt = null,
                replyTo = null,
                burn = null,
                senderIsDonor = userAuth.isDonor,
            ),
            silent = false
        )
    }

    // 取消上传
    delete("/upload/{uploadId}")
    {
        val uploadId = call.pathParameters["uploadId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
        val username = call.request.header("X-Auth-User")
        val passwordHash = call.request.header("X-Auth-Token")
        if (username == null || passwordHash == null)
            return@delete call.respond(HttpStatusCode.BadRequest, "Missing auth headers")

        val users = getKoin().get<Users>()
        val userAuth = users.getUserByUsername(username)
        if (userAuth == null || !verifyPassword(passwordHash, userAuth.password))
            return@delete call.respond(HttpStatusCode.Unauthorized)

        val taskInfo = uploadTasks[uploadId]
        if (taskInfo != null && taskInfo.userId != userAuth.id)
            return@delete call.respond(HttpStatusCode.Forbidden)

        // 删除分片目录
        FileUtils.getUploadDir(uploadId).deleteRecursively()
        uploadTasks.remove(uploadId)

        call.respond(HttpStatusCode.OK)
    }

    get("/file/{messageId}")
    {
        val messageId = call.pathParameters["messageId"]?.toLongOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
        val fileBytes = FileUtils.getChatFile(messageId) ?: return@get call.respond(HttpStatusCode.NotFound)
        val bytes = fileBytes.readBytes()
        call.response.header(HttpHeaders.CacheControl, "max-age=${30*24*60*60}") // 30 days
        call.respondBytes(bytes, ContentType.Text.Plain)
    }
}

internal suspend fun distributeMessage(message: Message, silent: Boolean)
{
    val members = getKoin().get<ChatMembers>().getMemberIds(message.chatId)
    members.forEach()
    { uid ->
        SessionManager.forEachSession(uid)
        { s ->
            s.sendUnreadCount(uid, message.chatId)
            val pushData = buildJsonObject()
            {
                put("packet", "receive_message")
                put("message", contentNegotiationJson.encodeToJsonElement(message))
                put("silent", silent)
            }
            logger.warning("sending message to $uid")
            {
                s.send(contentNegotiationJson.encodeToString(pushData))
            }
        }
    }
}
