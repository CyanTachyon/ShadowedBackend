package moe.tachyon.shadowed.dataClass

import kotlinx.serialization.Serializable

@Serializable
data class Chat(
    val id: ChatId,
    val name: String?,
    val owner: UserId,
    val private: Boolean,
    val isMoment: Boolean = false,
    val lastChatAt: kotlinx.datetime.Instant,
)
