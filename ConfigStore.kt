package com.anutunnel.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Local-only profile storage. Secrets never leave the device through this class. */
class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("anu_tunnel_configs", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): MutableList<TunnelConfig> {
        val raw = prefs.getString("items", "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                val item = array.getJSONObject(index)
                TunnelConfig.fromJson(item).apply {
                    account = SecretCrypto.decrypt(item.optString("account"))
                }
            }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    fun save(items: List<TunnelConfig>) {
        val array = JSONArray()
        items.forEach { config ->
            val item = config.toJson()
            item.put("account", SecretCrypto.encrypt(config.account))
            array.put(item)
        }
        prefs.edit().putString("items", array.toString()).apply()
        val selected = prefs.getString("selected_id", null)
        if (selected != null && items.none { it.id == selected }) {
            prefs.edit().remove("selected_id").apply()
        }
    }

    fun selectedId(): String? = prefs.getString("selected_id", null)
    fun select(id: String) {
        if (all().any { it.id == id }) prefs.edit().putString("selected_id", id).apply()
    }

    @Synchronized
    fun upsert(config: TunnelConfig) {
        val list = all()
        val index = list.indexOfFirst { it.id == config.id }
        if (index >= 0) list[index] = config else list.add(config)
        save(list)
    }

    @Synchronized
    fun delete(id: String) = save(all().filterNot { it.id == id })

    @Synchronized
    fun reset() {
        prefs.edit().remove("items").remove("selected_id").apply()
    }

    fun exportJson(configs: List<TunnelConfig> = all()): String = JSONObject()
        .put("app", "Anu TUNNEL")
        .put("schemaVersion", 1)
        .put("configs", JSONArray().also { array -> configs.forEach { array.put(it.toJson()) } })
        .toString(2)

    /** Imports valid profiles; duplicate IDs are re-keyed so profiles are not silently overwritten. */
    @Synchronized
    fun importJson(raw: String): Int {
        require(raw.isNotBlank()) { "The selected file is empty." }
        require(raw.length <= 2_000_000) { "Config file is too large (2 MB maximum)." }
        val root = JSONObject(raw)
        val array = root.optJSONArray("configs") ?: JSONArray().put(root)
        require(array.length() in 1..100) { "The file must contain 1–100 configurations." }
        val current = all()
        val ids = current.map { it.id }.toMutableSet()
        var added = 0
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val imported = TunnelConfig.fromJson(item)
            if (imported.name.isBlank() || imported.account.isBlank() || imported.serverName.isBlank()) continue
            if (imported.id in ids) imported.id = UUID.randomUUID().toString()
            ids.add(imported.id)
            current.add(imported)
            added++
        }
        require(added > 0) { "No valid configurations were found in this file." }
        save(current)
        return added
    }
}
