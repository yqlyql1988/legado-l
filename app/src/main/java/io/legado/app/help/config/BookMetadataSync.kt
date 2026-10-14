package io.legado.app.help.config

enum class BookMetadataSync {
    SYNC, NEVER, ASK;

    companion object {
        fun fromPreference(value: String?): BookMetadataSync = when (value) {
            "sync" -> SYNC
            "never" -> NEVER
            else -> ASK
        }
    }
}
