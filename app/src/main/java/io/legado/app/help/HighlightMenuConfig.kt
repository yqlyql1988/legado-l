package io.legado.app.help

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString

/** Reuses the two-zone drag editor and JSON format of the existing selection menu. */
object HighlightMenuConfig {
    val keys = listOf("NOTE", "STYLE", "COPY", "CREATE_RULE", "DELETE")
    fun default() = TextSelectMenuConfig(keys.take(3), keys.drop(3))
    fun parse(json: String?): TextSelectMenuConfig {
        val config = GSON.fromJsonObject<TextSelectMenuConfig>(json).getOrNull() ?: return default()
        @Suppress("USELESS_ELVIS")
        return TextSelectMenuConfig(config.bar ?: emptyList(), config.more ?: emptyList()).normalized(keys)
    }
    fun load(context: Context) = parse(context.getPrefString(PreferKey.highlightMenuConfig))
    fun save(context: Context, config: TextSelectMenuConfig) =
        context.putPrefString(PreferKey.highlightMenuConfig, config.normalized(keys).toJson())
}
