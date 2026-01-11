package moe.tachyon.shadowed.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.tachyon.shadowed.dataClass.ChatId
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.dataDir
import moe.tachyon.shadowed.logger.ShadowedLogger
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import javax.imageio.ImageIO

object FileUtils
{
    private val logger = ShadowedLogger.getLogger()
    val userAvatarDir = File(dataDir, "user_avatars").apply { mkdirs() }
    val groupAvatarDir = File(dataDir, "group_avatars").apply { mkdirs() }
    val chatFilesDir = File(dataDir, "chat_files").apply { mkdirs() }
    val uploadChunksDir = File(dataDir, "upload_chunks").apply { mkdirs() }

    suspend fun getAvatar(user: UserId): BufferedImage? = runCatching()
    {
        val avatarFile = File(userAvatarDir, "$user.png")
        if (!avatarFile.exists()) return null
        return withContext(Dispatchers.IO)
        {
            ImageIO.read(avatarFile)
        }
    }.getOrNull()

    suspend fun setAvatar(user: UserId, image: BufferedImage)
    {
        val image1 = BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB)
        val g = image1.createGraphics()
        g.drawImage(image, 0, 0, 512, 512, null)
        g.dispose()
        val avatarFile = File(userAvatarDir, "$user.png")
        withContext(Dispatchers.IO)
        {
            ImageIO.write(image1, "png", avatarFile)
        }
    }

    suspend fun getGroupAvatar(chatId: ChatId): BufferedImage? = runCatching()
    {
        val avatarFile = File(groupAvatarDir, "$chatId.png")
        if (!avatarFile.exists()) return null
        return withContext(Dispatchers.IO)
        {
            ImageIO.read(avatarFile)
        }
    }.getOrNull()

    suspend fun setGroupAvatar(chatId: ChatId, image: BufferedImage)
    {
        val image1 = BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB)
        val g = image1.createGraphics()
        g.drawImage(image, 0, 0, 512, 512, null)
        g.dispose()
        val avatarFile = File(groupAvatarDir, "$chatId.png")
        withContext(Dispatchers.IO)
        {
            ImageIO.write(image1, "png", avatarFile)
        }
    }

    suspend fun deleteGroupAvatar(chatId: ChatId): Boolean = withContext(Dispatchers.IO)
    {
        val avatarFile = File(groupAvatarDir, "$chatId.png")
        if (!avatarFile.exists()) return@withContext false
        val deleted = avatarFile.delete()
        if (deleted)
        {
            logger.info("Deleted avatar for group $chatId")
        }
        return@withContext deleted
    }

    suspend fun saveChatFile(messageId: Long, bytes: InputStream)
    {
        val chatFile = File(chatFilesDir, "$messageId.dat")
        withContext(Dispatchers.IO)
        {
            bytes.use { input ->
                chatFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    fun getChatFile(messageId: Long): File? = runCatching()
    {
        val chatFile = File(chatFilesDir, "$messageId.dat")
        if (!chatFile.exists()) return null
        return chatFile
    }.getOrNull()

    // 获取上传任务的分片目录
    fun getUploadDir(uploadId: String): File = File(uploadChunksDir, uploadId).apply { mkdirs() }

    // 保存分片
    suspend fun saveChunk(uploadId: String, chunkIndex: Int, bytes: InputStream)
    {
        val chunkFile = File(getUploadDir(uploadId), "chunk_$chunkIndex")
        withContext(Dispatchers.IO)
        {
            bytes.use()
            { input ->
                chunkFile.outputStream().use()
                { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    // 获取已上传的分片索引列表
    suspend fun getUploadedChunks(uploadId: String): List<Int> = withContext(Dispatchers.IO)
    {
        val dir = getUploadDir(uploadId)
        if (!dir.exists()) return@withContext emptyList()
        dir.listFiles()
            ?.filter { it.name.startsWith("chunk_") }
            ?.mapNotNull { it.name.removePrefix("chunk_").toIntOrNull() }
            ?.sorted()
            ?: emptyList()
    }

    // 合并分片到最终文件
    suspend fun mergeChunks(uploadId: String, messageId: Long, totalChunks: Int): Boolean = withContext(Dispatchers.IO)
    {
        val uploadDir = getUploadDir(uploadId)
        val chatFile = File(chatFilesDir, "$messageId.dat")

        // 检查所有分片是否存在
        for (i in 0 until totalChunks)
        {
            val chunkFile = File(uploadDir, "chunk_$i")
            if (!chunkFile.exists()) return@withContext false
        }

        // 合并分片
        chatFile.outputStream().use()
        { output ->
            for (i in 0 until totalChunks)
            {
                val chunkFile = File(uploadDir, "chunk_$i")
                chunkFile.inputStream().use()
                { input ->
                    input.copyTo(output)
                }
            }
        }

        // 清理分片目录
        uploadDir.deleteRecursively()
        true
    }

    /**
     * Delete a message's associated file if it exists
     * Used for IMAGE, VIDEO, and FILE message types
     */
    suspend fun deleteChatFile(messageId: Long): Boolean = withContext(Dispatchers.IO)
    {
        val chatFile = File(chatFilesDir, "$messageId.dat")
        if (!chatFile.exists()) return@withContext false
        val deleted = chatFile.delete()
        if (deleted)
        {
            logger.info("Deleted file for message $messageId")
        }
        return@withContext deleted
    }
}