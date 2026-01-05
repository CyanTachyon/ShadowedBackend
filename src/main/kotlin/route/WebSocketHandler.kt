package moe.tachyon.shadowed.route

import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.tachyon.shadowed.contentNegotiationJson
import moe.tachyon.shadowed.dataClass.User
import moe.tachyon.shadowed.route.packets.*

private val packetHandlers: Map<String, PacketHandler> = listOf(
    // Chat packets
    GetChatsHandler,
    GetMessagesHandler,
    SendMessageHandler,
    EditMessageHandler,
    GetChatDetailsHandler,
    RenameChatHandler,
    SetDoNotDisturb,
    SetBurnTimeHandler,
    MarkMessageReadHandler,
    ToggleReactionHandler,
    // Friend packets
    GetFriendsHandler,
    AddFriendHandler,
    // Group packets
    CreateGroupHandler,
    AddMemberToChatHandler,
    KickMemberFromChatHandler,
    // Broadcast packets
    SendBroadcastHandler,
    GetBroadcastsHandler,
    // Moment packets
    GetMomentsHandler,
    PostMomentHandler,
    GetUserMomentsHandler,
    ToggleMomentPermissionHandler,
    GetMomentPermissionHandler,
    GetMyMomentKeyHandler,
    DeleteMomentHandler,
    EditMomentHandler,
    CommentMomentHandler,
    GetMomentCommentsHandler,
    // Login packets (except login itself)
    GetPublicKeyByUsernameHandler,
    UpdateSignatureHandler,
).associateBy { it.packetName.lowercase() }

fun Route.webSocketRoute() = webSocket("/socket") socket@
{
    var loginUser: User? = null
    try
    {
        launch()
        {
            // Ping-pong to keep connection alive
            while (true)
            {
                val packet = buildJsonObject()
                {
                    put("packet", "time")
                    put("t", Clock.System.now().toEpochMilliseconds())
                }
                send(contentNegotiationJson.encodeToString(packet))
                delay(30000L) // 30 seconds
            }
        }

        incoming.consumeAsFlow().filterIsInstance<Frame.Text>().collect()
        { frame ->
            val data = frame.readText().split("\n")
            val packetName = data[0]
            val packetData = data.subList(1, data.size).joinToString("\n")

            // Handle login packet separately
            if (packetName.equals("login", ignoreCase = true))
            {
                loginUser = LoginHandler.handle(this@socket, packetData)
                return@collect
            }

            // Require login for all other packets
            val user = loginUser
            if (user == null)
            {
                val response = buildJsonObject()
                {
                    put("packet", "require_login")
                }
                send(contentNegotiationJson.encodeToString(response))
                return@collect
            }

            // Find and execute packet handler
            val handler = packetHandlers[packetName.lowercase()]
            handler?.handle(this@socket, packetData, user)
        }
    }
    finally
    {
        loginUser?.let()
        {
            SessionManager.removeSession(it.id, this)
        }
    }
}
