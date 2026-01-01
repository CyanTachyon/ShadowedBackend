package moe.tachyon.shadowed.route.packets

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import moe.tachyon.shadowed.contentNegotiationJson
import moe.tachyon.shadowed.dataClass.User

@Serializable
data class NotifyPacket(
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
        SUCCESS,
        INFO,
        WARNING,
        ERROR,
    }
}

suspend fun DefaultWebSocketServerSession.sendNotify(type: NotifyPacket.Type, message: String): Unit =
    send(contentNegotiationJson.encodeToString(NotifyPacket(type, message)))
suspend fun DefaultWebSocketServerSession.sendSuccess(message: String) =
    sendNotify(NotifyPacket.Type.SUCCESS, message)
suspend fun DefaultWebSocketServerSession.sendInfo(message: String) =
    sendNotify(NotifyPacket.Type.INFO, message)
suspend fun DefaultWebSocketServerSession.sendWarning(message: String) =
    sendNotify(NotifyPacket.Type.WARNING, message)
suspend fun DefaultWebSocketServerSession.sendError(message: String) =
    sendNotify(NotifyPacket.Type.ERROR, message)
interface PacketHandler
{
    val packetName: String
    suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
}
interface LoginPacketHandler
{
    val packetName: String get() = "login"
    suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String
    ): User?
}