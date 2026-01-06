package moe.tachyon.shadowed.route

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.readBytes
import moe.tachyon.shadowed.database.Users
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.logger.ShadowedLogger
import moe.tachyon.shadowed.utils.FileUtils
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

private val logger = ShadowedLogger.getLogger()

fun Route.userRoute()
{
    route("/user")
    {
        post("/avatar")
        {
            // Simple auth check via headers
            val username = call.request.header("X-Auth-User")
            val passwordHash = call.request.header("X-Auth-Token") // Encrypted password hash

            if (username == null || passwordHash == null)
            {
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }

            val users = getKoin().get<Users>()
            val userAuth = users.getUserByUsername(username)

            if (userAuth == null || !verifyPassword(passwordHash, userAuth.password))
            {
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }

            // Process multipart
            val multipart = call.receiveMultipart()

            while (true)
            {
                val part = multipart.readPart() ?: break
                if (part is PartData.FileItem)
                {
                    logger.warning("Failed to process image")
                    {
                        val fileBytes = part.provider().readBuffer().readBytes()
                        val image = ImageIO.read(ByteArrayInputStream(fileBytes))
                        if (image != null) FileUtils.setAvatar(userAuth.id, image)
                        else call.respond(HttpStatusCode.BadRequest, "Invalid image format")
                    }.getOrThrow()
                }
                part.dispose()
            }

            call.respond(HttpStatusCode.OK)
        }

        get("/{id}/avatar")
        {
            val idStr = call.parameters["id"]
            val id = idStr?.toIntOrNull()

            if (id == null)
            {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }

            val avatarImage = FileUtils.getAvatar(UserId(id))

            call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
            if (avatarImage != null)
            {
                val bytes = ByteArrayOutputStream().also { ImageIO.write(avatarImage, "png", it) }.toByteArray()
                val hash = bytes.contentHashCode().toString()
                if (call.request.headers[HttpHeaders.IfNoneMatch] == hash)
                    return@get call.respond(HttpStatusCode.NotModified)
                call.response.header(HttpHeaders.ETag, hash)
                call.respondBytes(bytes, contentType = ContentType.Image.PNG)
            }
            else
            {
                if (call.request.headers[HttpHeaders.IfNoneMatch] == "default_avatar")
                    return@get call.respond(HttpStatusCode.NotModified)
                call.response.header(HttpHeaders.ETag, "default_avatar")
                call.respondText("""
                    <svg width="100" height="100" viewBox="0 0 64 64" xmlns="http://www.w3.org/2000/svg">
                      <rect x="0" y="0" width="64" height="64" rx="16" fill="#E3F2FD"/>
                      <g fill="#42A5F5">
                        <circle cx="32" cy="24" r="10"/>
                        <path d="M32 38C22 38 14 44 12 52C12 52 12 54 14 54H50C52 54 52 52 52 52C50 44 42 38 32 38Z"/>
                      </g>
                    </svg>
                """.trimIndent(), contentType = ContentType.Image.SVG)
            }
        }

        get("/publicKey")
        {
            val username = call.request.queryParameters["username"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing username")

            val user = getKoin().get<Users>().getUserByUsername(username)
                ?: return@get call.respond(HttpStatusCode.NotFound)

            val response = buildJsonObject
            {
                put("publicKey", user.publicKey)
            }
            call.respond(response)
        }

        get("/info")
        {
            val idStr = call.request.queryParameters["id"]
            val id = idStr?.toIntOrNull()

            if (id == null)
            {
                call.respond(HttpStatusCode.BadRequest, "Missing or invalid id")
                return@get
            }

            val user = getKoin().get<Users>().getUser(UserId(id))
            if (user == null)
            {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }

            val response = buildJsonObject
            {
                put("id", user.id.value)
                put("username", user.username)
                put("signature", user.signature)
                put("isDonor", user.isDonor)
            }
            call.respond(response)
        }
    }
}
