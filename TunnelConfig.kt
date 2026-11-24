package com.anutunnel.app

import org.json.JSONObject
import java.util.UUID

data class TunnelConfig(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "VPN Jantit - IN5",
    var connectionType: String = "SSH Through DNSTT",
    var account: String = "",
    var udpDns: String = "8.8.8.8:53",
    var serverName: String = "ns-in5.vpnjantit.com",
    var publicKey: String = "c47123ae2ab21b299cfff8feab8d8410ecee25f9803730c12b9953f4cba62d7b",
    var payload: String = "",
    var dnsOnly: Boolean = false,
    var udpgwPort: Int = 0,
    var udpgwPortConfigured: Boolean = false,
    var isDefault: Boolean = false
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("connectionType", connectionType)
        put("account", account); put("udpDns", udpDns); put("serverName", serverName)
        put("publicKey", publicKey); put("payload", payload); put("dnsOnly", dnsOnly); put("udpgwPort", udpgwPort); put("udpgwPortConfigured", udpgwPortConfigured)
        put("isDefault", isDefault)
    }

    companion object {
        fun fromJson(o: JSONObject) = TunnelConfig(
            id = o.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            name = o.optString("name", "Imported config"),
            connectionType = o.optString("connectionType", "SSH Through DNSTT"),
            account = o.optString("account"), udpDns = o.optString("udpDns", "8.8.8.8:53"),
            serverName = o.optString("serverName"), publicKey = o.optString("publicKey"),
            payload = o.optString("payload"), dnsOnly = o.optBoolean("dnsOnly"),
            udpgwPort = if (o.optBoolean("udpgwPortConfigured", false)) o.optInt("udpgwPort", 0) else 0,
            udpgwPortConfigured = o.optBoolean("udpgwPortConfigured", false),
            isDefault = o.optBoolean("isDefault")
        )
    }
}
