/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2019 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2019 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package com.github.shadowsocks.bg

import android.content.Context
import com.github.shadowsocks.Core.app
import com.github.shadowsocks.acl.Acl
import com.github.shadowsocks.acl.AclSyncer
import com.github.shadowsocks.core.R
import com.github.shadowsocks.database.Profile
import com.github.shadowsocks.plugin.PluginConfiguration
import com.github.shadowsocks.plugin.PluginManager
import com.github.shadowsocks.preference.DataStore
import kotlinx.coroutines.CoroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URISyntaxException

/**
 * This class sets up environment for ss-local.
 */
class ProxyInstance(val profile: Profile, private val route: String = profile.route) {
    init {
        require(profile.host.isNotEmpty() && (profile.method == "none" || profile.password.isNotEmpty())) {
            app.getString(R.string.proxy_empty)
        }
        // check the crypto
        require(profile.method !in arrayOf("aes-192-gcm", "chacha20", "salsa20")) {
            "cipher ${profile.method} is deprecated."
        }
    }
    private var configFile: File? = null
    var trafficMonitor: TrafficMonitor? = null
    val plugin by lazy { PluginManager.init(PluginConfiguration(profile.plugin ?: "")) }

    /**
     * Sensitive shadowsocks configuration file requires extra protection. It may be stored in encrypted storage or
     * device storage, depending on which is currently available.
     */
    fun start(service: BaseService.Interface, stat: File, configFile: File, mode: String, dnsRelay: Boolean = true) {
        // setup traffic monitor path
        trafficMonitor = TrafficMonitor(stat)

        // init JSON config
        this.configFile = configFile
        val config = profile.toJson()
        plugin?.let { (path, opts, isV2) ->
            if (service.isVpnService) {
                if (isV2) opts["__android_vpn"] = "" else config.put("plugin_args", JSONArray(arrayOf("-V")))
            }
            config.put("plugin", path).put("plugin_opts", opts.toString())
        }
        config.put("dns", "system")
        config.put("servers", JSONArray().apply {
            // local SOCKS5 proxy
            put(JSONObject().apply {
                put("address", profile.host)
                put("port", profile.remotePort)
                put("mode", mode)
                put("method", profile.method)
                put("password", profile.password);
            })
        })
        configFile.writeText(config.toString())

        // build the command line
        val cmd = arrayListOf(
                File((service as Context).applicationInfo.nativeLibraryDir, Executable.SS_SERVER).absolutePath,
                "-c", configFile.absolutePath,
        )

        if (service.isVpnService) cmd += "--vpn"

        if (route != Acl.ALL) {
            cmd += "--acl"
            cmd += Acl.getFile(route).absolutePath
        }

        service.data.processes!!.start(cmd)
    }

    fun scheduleUpdate() {
        if (route !in arrayOf(Acl.ALL, Acl.CUSTOM_RULES)) AclSyncer.schedule(route)
    }

    fun shutdown(scope: CoroutineScope) {
        trafficMonitor?.apply {
            thread.shutdown(scope)
            persistStats(profile.id)    // Make sure update total traffic when stopping the runner
        }
        trafficMonitor = null
        configFile?.delete()    // remove old config possibly in device storage
        configFile = null
    }
}
