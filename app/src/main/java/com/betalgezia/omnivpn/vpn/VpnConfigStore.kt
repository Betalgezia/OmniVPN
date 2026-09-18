package com.betalgezia.omnivpn.vpn

import android.content.Context
import java.io.File

class VpnConfigStore(context: Context) {

    private val configFile = File(context.filesDir, FILE_NAME)

    fun write(config: String) {
        require(config.isNotBlank()) { "VPN config is empty" }
        configFile.parentFile?.mkdirs()
        configFile.writeText(config, Charsets.UTF_8)
    }

    fun read(): String? {
        if (!configFile.isFile) return null
        return configFile.readText(Charsets.UTF_8)
    }

    fun clear() {
        configFile.delete()
    }

    companion object {
        private const val FILE_NAME = "active-config.json"
    }
}
