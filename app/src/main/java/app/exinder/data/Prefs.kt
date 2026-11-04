package app.exinder.data

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.exinder.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// A corrupted preferences file (flash wear, interrupted write) is replaced with
// empty preferences instead of crashing on every launch: everything stored here
// is disposable (last folder, theme, one skipped permission).
private val Context.dataStore by preferencesDataStore(
    name = "prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Theme chosen in the menu. [SISTEMA] follows Android's light/dark mode. */
enum class ThemeMode(@StringRes val label: Int) {
    SISTEMA(R.string.theme_system),
    CLARO(R.string.theme_light),
    ESCURO(R.string.theme_dark),
}

/** Small app preferences, stored with DataStore. */
class Prefs(private val context: Context) {

    private val lastFolderKey = stringPreferencesKey("last_folder")
    private val skipManageMediaKey = booleanPreferencesKey("skip_manage_media")
    private val themeKey = stringPreferencesKey("theme")

    /** App theme; defaults to following the system. */
    val theme: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        prefs[themeKey]?.let { saved -> ThemeMode.entries.firstOrNull { it.name == saved } } ?: ThemeMode.SISTEMA
    }

    suspend fun setTheme(mode: ThemeMode) {
        context.dataStore.edit { prefs -> prefs[themeKey] = mode.name }
    }

    /** Last opened folder, so the app can go straight back to it. */
    val lastFolder: Flow<FolderRef?> =
        context.dataStore.data.map { prefs -> prefs[lastFolderKey]?.let(FolderRef::fromKey) }

    suspend fun setLastFolder(folder: FolderRef?) {
        context.dataStore.edit { prefs ->
            if (folder == null) prefs.remove(lastFolderKey) else prefs[lastFolderKey] = folder.key
        }
    }

    /**
     * The user may continue without "Media management". The app still works,
     * but Android then asks for confirmation on every send to the trash.
     */
    val skipManageMedia: Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[skipManageMediaKey] ?: false }

    suspend fun setSkipManageMedia(skip: Boolean) {
        context.dataStore.edit { prefs -> prefs[skipManageMediaKey] = skip }
    }
}
