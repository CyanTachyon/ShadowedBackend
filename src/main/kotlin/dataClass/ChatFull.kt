package moe.tachyon.shadowed.dataClass

import kotlinx.serialization.Serializable

@Serializable
data class ChatMember(
    val id: Int,
    val name: String
)

@Serializable
data class ChatFull(
    val chatId: ChatId,
    val name: String?,
    val key: String,
    val members: List<ChatMember>,
    val isPrivate: Boolean,
    val unreadCount: Int,
    val doNotDisturb: Boolean,
    val burnTime: Long?, // 阅后即焚时间（毫秒），null表示关闭
    val otherUserIsDonor: Boolean,
)
