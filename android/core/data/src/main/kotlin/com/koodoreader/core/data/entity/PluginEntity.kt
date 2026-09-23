package com.koodoreader.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Desktop table `plugins` (schema.lock). Kept for schema compatibility with
 * desktop exports even though the plugin *system* itself is out of scope for
 * the native client (appendix A marks it "不做"); `config` / `autoValue`
 * carry JSON payloads ("object" / "string" columns on desktop).
 */
@Entity(tableName = "plugins")
data class PluginEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "type") val type: String? = null,
    @ColumnInfo(name = "displayName") val displayName: String? = null,
    @ColumnInfo(name = "icon") val icon: String? = null,
    @ColumnInfo(name = "version") val version: String? = null,
    /** Desktop type "object": JSON-serialised plugin config. */
    @ColumnInfo(name = "config") val config: String? = null,
    /** Desktop type "string": JSON-serialised value. */
    @ColumnInfo(name = "autoValue") val autoValue: String? = null,
    @ColumnInfo(name = "langList") val langList: String? = null,
    @ColumnInfo(name = "voiceList") val voiceList: String? = null,
    @ColumnInfo(name = "scriptSHA256") val scriptSHA256: String? = null,
    @ColumnInfo(name = "script") val script: String? = null,
)
