package moe.tachyon.shadowed.dataClass

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val id: Long,
    val content: String,
    val type: MessageType,
    val chatId: ChatId,
    val senderId: UserId?,
    val senderName: String?,
    val time: Long,
    val replyTo: ReplyInfo?,
    val readAt: Long?,
    val burn: Long?,
    val senderIsDonor: Boolean,
    val reactions: List<Reaction> = emptyList()
)