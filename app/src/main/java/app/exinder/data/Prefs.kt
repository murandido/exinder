package app.exinder.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "prefs")

/** Tema escolhido no menu. [SISTEMA] segue o modo claro/escuro do Android. */
enum class ThemeMode(val label: String) {
    SISTEMA("igual ao sistema"),
    CLARO("claro"),
    ESCURO("escuro"),
}

/** Preferências pequenas do app, guardadas com DataStore. */
class Prefs(private val context: Context) {

    private val lastFolderKey = stringPreferencesKey("last_folder")
    private val skipManageMediaKey = booleanPreferencesKey("skip_manage_media")
    private val themeKey = stringPreferencesKey("theme")

    /** Tema do app; padrão: igual ao sistema. */
    val theme: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        prefs[themeKey]?.let { saved -> ThemeMode.entries.firstOrNull { it.name == saved } } ?: ThemeMode.SISTEMA
    }

    suspend fun setTheme(mode: ThemeMode) {
        context.dataStore.edit { prefs -> prefs[themeKey] = mode.name }
    }

    /** Última pasta aberta, para o app voltar direto nela. */
    val lastFolder: Flow<FolderRef?> =
        context.dataStore.data.map { prefs -> prefs[lastFolderKey]?.let(FolderRef::fromKey) }

    suspend fun setLastFolder(folder: FolderRef?) {
        context.dataStore.edit { prefs ->
            if (folder == null) prefs.remove(lastFolderKey) else prefs[lastFolderKey] = folder.key
        }
    }

    /**
     * O usuário pode seguir sem o "Gerenciamento de mídia". O app funciona,
     * mas o Android passa a pedir confirmação a cada envio para a lixeira.
     */
    val skipManageMedia: Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[skipManageMediaKey] ?: false }

    suspend fun setSkipManageMedia(skip: Boolean) {
        context.dataStore.edit { prefs -> prefs[skipManageMediaKey] = skip }
    }
}
