package com.example.freevpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val config: String
) {
    fun extractEndpoint(): String {
        val line = config.lines().firstOrNull {
            it.trim().startsWith("Endpoint", ignoreCase = true)
        } ?: return "No endpoint configured"
        return line.substringAfter("=").trim().ifEmpty { "No endpoint configured" }
    }
}

class ProfileRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        "freevpn_local_profiles",
        Context.MODE_PRIVATE
    )

    fun loadProfiles(): List<ServerProfile> {
        val raw = prefs.getString(KEY_PROFILES, null)
        if (raw.isNullOrBlank()) {
            val defaultProfile = ServerProfile(
                name = "Primary Server",
                config = """
                    [Interface]
                    PrivateKey = YOUR_CLIENT_PRIVATE_KEY
                    Address = 10.66.66.2/32
                    DNS = 1.1.1.1

                    [Peer]
                    PublicKey = YOUR_SERVER_PUBLIC_KEY
                    Endpoint = YOUR_SERVER_IP:51820
                    AllowedIPs = 0.0.0.0/0, ::/0
                    PersistentKeepalive = 25
                """.trimIndent()
            )
            saveProfiles(listOf(defaultProfile))
            setActiveProfileId(defaultProfile.id)
            return listOf(defaultProfile)
        }

        val array = JSONArray(raw)
        val result = mutableListOf<ServerProfile>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            result.add(
                ServerProfile(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    config = obj.getString("config")
                )
            )
        }
        return result
    }

    fun saveProfiles(profiles: List<ServerProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            val obj = JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("config", profile.config)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    fun getActiveProfileId(): String? {
        return prefs.getString(KEY_ACTIVE_ID, null)
    }

    fun setActiveProfileId(id: String?) {
        prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
    }

    companion object {
        private const val KEY_PROFILES = "profiles_json"
        private const val KEY_ACTIVE_ID = "active_profile_id"
    }
}

