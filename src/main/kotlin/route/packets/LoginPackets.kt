package moe.tachyon.shadowed.route.packets

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.*
import moe.tachyon.shadowed.contentNegotiationJson
import moe.tachyon.shadowed.database.Users
import moe.tachyon.shadowed.dataClass.User
import moe.tachyon.shadowed.route.SessionManager
import moe.tachyon.shadowed.route.getKoin
import moe.tachyon.shadowed.route.verifyPassword

object LoginHandler : LoginPacketHandler
{
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String
    ): User?
    {
        val (username, password) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val user = json.jsonObject["username"]!!.jsonPrimitive.content
            val pass = json.jsonObject["password"]!!.jsonPrimitive.content
            Pair(user, pass)
        }.getOrNull() ?: run()
        {
            val response = buildJsonObject()
            {
                put("packet", "login_result")
                put("success", false)
                put("error", "Login failed: Invalid packet format")
            }
            session.send(contentNegotiationJson.encodeToString(response))
            return null
        }
        
        val user = getKoin().get<Users>().getUserByUsername(username) ?: run()
        {
            val response = buildJsonObject()
            {
                put("packet", "login_result")
                put("success", false)
                put("error", "Login failed: User not found")
            }
            session.send(contentNegotiationJson.encodeToString(response))
            return null
        }
        
        if (!verifyPassword(password, user.password))
        {
            val response = buildJsonObject()
            {
                put("packet", "login_result")
                put("success", false)
                put("error", "Login failed: Incorrect password")
            }
            session.send(contentNegotiationJson.encodeToString(response))
            return null
        }
        
        SessionManager.addSession(user.id, session)

        val response = buildJsonObject()
        {
            put("packet", "login_result")
            put("success", true)
            put("user", contentNegotiationJson.encodeToJsonElement(user))
        }
        session.send(contentNegotiationJson.encodeToString(response))
        return user
    }
}

object GetPublicKeyByUsernameHandler : PacketHandler
{
    override val packetName = "get_public_key_by_username"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val username = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            json.jsonObject["username"]!!.jsonPrimitive.content
        }.getOrNull() ?: return

        val user = getKoin().get<Users>().getUserByUsername(username)

        if (user != null)
        {
            val response = buildJsonObject()
            {
                put("packet", "public_key_by_username")
                put("username", username) // Echo back for correlation
                put("publicKey", user.publicKey)
            }
            session.send(contentNegotiationJson.encodeToString(response))
        }
        else session.sendError("Failed to get public key: User not found")
    }
}

object UpdateSignatureHandler : PacketHandler
{
    override val packetName = "update_signature"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val signature = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            json.jsonObject["signature"]!!.jsonPrimitive.content
        }.getOrNull() ?: return session.sendError("Invalid signature format")

        // Limit signature length
        if (signature.length > 100)
        {
            return session.sendError("Signature too long (max 100 characters)")
        }

        getKoin().get<Users>().updateSignature(loginUser.id, signature)
        
        val response = buildJsonObject()
        {
            put("packet", "signature_updated")
            put("signature", signature)
        }
        session.send(contentNegotiationJson.encodeToString(response))
        session.sendSuccess("Signature updated successfully")
    }
}
