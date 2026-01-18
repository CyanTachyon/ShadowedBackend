package moe.tachyon.shadowed.route

import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import moe.tachyon.shadowed.contentNegotiationJson

fun Route.configRoute()
{
    get("/auth/params")
    {
        call.respond(
            buildJsonObject()
            {
                put("authKey", SERVER_AUTH_KEY)
            }
        )
    }

    get("/project")
    {
        val projectName = environment.config.propertyOrNull("project.name")?.getString() ?: "ShadowedChat"
        val projectWebsite = environment.config.propertyOrNull("project.website")?.getString()
        val projectVersion = environment.config.propertyOrNull("version")?.getString() ?: "1.0.0"
        val developerName = environment.config.propertyOrNull("developer.name")?.getString() ?: "Unknown"
        val developerWebsite = environment.config.propertyOrNull("developer.website")?.getString()
        val developerGithub = environment.config.propertyOrNull("developer.github")?.getString()
        val donationWechat = environment.config.propertyOrNull("donation.wechatQrCode")?.getString()
        val donationAlipay = environment.config.propertyOrNull("donation.alipayQrCode")?.getString()

        // Get all donors directly from database
        val users = getKoin().get<moe.tachyon.shadowed.database.Users>()
        val donorsWithAmount = users.getDonors()

        call.respond(
            buildJsonObject()
            {
                put("name", projectName)
                projectWebsite?.let { put("website", it) }
                put("version", projectVersion)
                put("developer", buildJsonObject()
                {
                    put("name", developerName)
                    developerWebsite?.let { put("website", it) }
                    developerGithub?.let { put("github", it) }
                })
                put("donation", buildJsonObject()
                {
                    donationWechat?.let { put("wechatQrCode", it) }
                    donationAlipay?.let { put("alipayQrCode", it) }
                })
                put("donors", buildJsonArray()
                {
                    donorsWithAmount.forEach { (donor, amount) ->
                        addJsonObject()
                        {
                            put("id", donor.id.value)
                            put("username", donor.username)
                            put("isDonor", donor.isDonor)
                            put("donationAmount", amount)
                        }
                    }
                })
            }
        )
    }
}
