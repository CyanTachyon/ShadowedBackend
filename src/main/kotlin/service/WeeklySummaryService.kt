package moe.tachyon.shadowed.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days
import moe.tachyon.shadowed.config.weeklySummaryConfig
import moe.tachyon.shadowed.database.Broadcasts
import moe.tachyon.shadowed.database.Messages
import kotlinx.datetime.Instant
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import moe.tachyon.shadowed.logger.ShadowedLogger
import moe.tachyon.shadowed.route.renewBroadcast
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.coroutines.cancellation.CancellationException

object WeeklySummaryService: KoinComponent
{
    private val logger = ShadowedLogger.getLogger<WeeklySummaryService>()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    fun start()
    {
        if (job != null)
        {
            logger.warning("WeeklySummaryService is already running")
            return
        }

        if (!weeklySummaryConfig.enableWeeklySummary)
        {
            logger.info("Weekly summary is disabled in config")
            return
        }

        job = scope.launch()
        {
            while (isActive)
            {
                try
                {
                    val scheduledTime = parseScheduledTime(weeklySummaryConfig.weeklySummaryTime)
                    if (scheduledTime == null)
                    {
                        logger.warning("Invalid weeklySummaryTime format: ${weeklySummaryConfig.weeklySummaryTime}, retrying in 1 hour")
                        delay(3600000L) // 1 hour
                        continue
                    }

                    val nextRun = calculateNextRunTime(scheduledTime)
                    val delay = nextRun - Clock.System.now()

                    if (delay.isPositive())
                    {
                        logger.info("Weekly summary scheduled at $nextRun, waiting $delay")
                        delay(delay)
                    }

                    if (isActive)
                    {
                        logger.info("Generating weekly summary...")
                        generateAndSendSummary()
                    }
                }
                catch (e: Throwable)
                {
                    if (e is CancellationException) throw e
                    logger.severe("Error in weekly summary scheduler: ${e.message}", e)
                }
            }
        }

        logger.info("WeeklySummaryService started")
    }

    fun stop()
    {
        job?.cancel()
        job = null
        logger.info("WeeklySummaryService stopped")
    }

    private fun parseScheduledTime(timeString: String): Triple<Int, Int, Int>?
    {
        if (timeString.isBlank()) return null
        val parts = timeString.split(":")
        if (parts.size != 3) return null
        return runCatching()
        {
            val dayOfWeek = parts[0].toIntOrNull()
            val hour = parts[1].toIntOrNull()
            val minute = parts[2].toIntOrNull()

            if (dayOfWeek != null && hour != null && minute != null && dayOfWeek in 1..7 && hour in 0..23 && minute in 0..59)
                Triple(dayOfWeek, hour, minute)
            else null
        }.getOrNull()
    }

    private fun calculateNextRunTime(scheduledTime: Triple<Int, Int, Int>): Instant
    {
        val timeZone = TimeZone.currentSystemDefault()
        val now = Clock.System.now()
        val (targetDay, targetHour, targetMinute) = scheduledTime
        val nowLocal = now.toLocalDateTime(timeZone)
        var target = nowLocal.date.plus((targetDay - nowLocal.date.dayOfWeek.value + 7) % 7, DateTimeUnit.DAY)
        while (target.atTime(targetHour, targetMinute) <= nowLocal) target = target.plus(7, DateTimeUnit.DAY)
        val resLocal = target.atTime(targetHour, targetMinute)
        return resLocal.toInstant(timeZone)
    }

    private suspend fun generateAndSendSummary() = logger.severe("Failed to generate and send weekly summary")
    {
        val messages = get<Messages>()
        val broadcasts = get<Broadcasts>()

        val oneWeekAgo = Clock.System.now() - 7.days
        val topUsers = messages.getTopActiveUsers(oneWeekAgo)
        val topChats = messages.getTopActiveChats(oneWeekAgo)
        val summary = buildSummary(topUsers, topChats, oneWeekAgo)
        val broadcastId = broadcasts.addSystemBroadcast(summary)
        renewBroadcast(broadcastId)
        logger.info("Weekly summary sent successfully: broadcast id=$broadcastId")
    }

    private fun buildSummary(
        topUsers: List<Pair<String, Long>>,
        topChats: List<Pair<String, Long>>,
        startTime: Instant
    ): String
    {
        val timeZone = TimeZone.currentSystemDefault()
        val startDate = startTime.toLocalDateTime(timeZone).date
        val endDate = Clock.System.now().toLocalDateTime(timeZone).date

        val sb = StringBuilder()
        sb.append("📊 周报摘要\n")
        sb.append("=".repeat(30)).append("\n\n")
        sb.append("📅 统计时间: $startDate ~ $endDate\n\n")

        // 最活跃的用户
        sb.append("🏆 最活跃的10位用户:\n")
        topUsers.forEachIndexed()
        { index, user ->
            val medal = when (index)
            {
                0    -> "🥇"
                1    -> "🥈"
                2    -> "🥉"
                else -> "${index + 1}."
            }
            sb.append("  $medal ${user.first}: ${user.second} 条消息\n")
        }
        sb.append("\n")
        sb.append("💬 最活跃的10个群聊:\n")
        topChats.forEachIndexed()
        { index, chat ->
            val medal = when (index)
            {
                0    -> "🥇"
                1    -> "🥈"
                2    -> "🥉"
                else -> "${index + 1}."
            }
            sb.append("  $medal ${chat.first}: ${chat.second} 条消息\n")
        }
        sb.append("\n")
        sb.append("=".repeat(30)).append("\n")
        sb.append("下周见! 👋")

        return sb.toString()
    }
}
