package moe.tachyon.shadowed.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.tachyon.shadowed.dataClass.Message
import moe.tachyon.shadowed.dataClass.MessageType
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.Messages
import moe.tachyon.shadowed.logger.ShadowedLogger
import moe.tachyon.shadowed.route.distributeMessage
import moe.tachyon.shadowed.utils.FileUtils
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.coroutines.cancellation.CancellationException

/**
 * Service for cleaning up expired burn-after-read messages
 */
object BurnAfterReadService: KoinComponent
{
    private val logger = ShadowedLogger.getLogger<BurnAfterReadService>()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    private const val CLEANUP_INTERVAL_MS = 1000L

    fun start()
    {
        if (job != null)
        {
            logger.warning("BurnAfterReadService is already running")
            return
        }

        job = scope.launch()
        {
            logger.info("BurnAfterReadService started, checking every ${CLEANUP_INTERVAL_MS / 1000} seconds")
            while (isActive)
            {
                try
                {
                    cleanupExpiredMessages()
                    delay(CLEANUP_INTERVAL_MS)
                }
                catch (e: Throwable)
                {
                    if (e is CancellationException) throw e
                    logger.severe("Error in burn-after-read cleanup: ${e.message}", e)
                    delay(CLEANUP_INTERVAL_MS) // Continue even after error
                }
            }
        }
    }

    fun stop()
    {
        job?.cancel()
        job = null
        logger.info("BurnAfterReadService stopped")
    }

    private suspend fun cleanupExpiredMessages()
    {
        val messages = get<Messages>()
        val expiredMessageInfos = messages.getExpiredMessageIds()

        for (info in expiredMessageInfos)
        {
            // Delete associated file first
            logger.warning("Failed to delete file for expired message ${info.first}")
            {
                FileUtils.deleteChatFile(info.first)
            }

            // Delete message
            logger.warning("Failed to delete expired message ${info.first}")
            {
                messages.deleteMessage(info.first)
            }

            // Distribute a minimal "deleted" message to notify clients
            // Use empty content and TEXT type to indicate deletion (same as edit_message with null)
            distributeMessage(Message(
                id = info.first,
                content = "",
                type = MessageType.TEXT,
                chatId = info.second,
                senderId = UserId(0), // Not needed for deletion notification
                senderName = "",
                time = 0,
                readAt = null,
                replyTo = null,
                burn = null,
                senderIsDonor = false,
            ), silent = true)

            logger.info("Deleted expired message ${info.first} from chat ${info.second}")
        }
    }
}
