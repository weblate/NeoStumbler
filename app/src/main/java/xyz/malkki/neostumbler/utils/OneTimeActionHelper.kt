package xyz.malkki.neostumbler.utils

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import xyz.malkki.neostumbler.StumblerApplication

class OneTimeActionHelper(private val oneTimeActionsStore: DataStore<Preferences>) {
    constructor(app: StumblerApplication) : this(app.oneTimeActionsStore)

    /**
     * Checks if the specified action has already been shown to the user
     *
     * @param actionName Action name
     * @return true if the action has been shown, false if not
     */
    suspend fun hasActionBeenShown(actionName: String): Boolean {
        return oneTimeActionsStore.data
            .map { it[booleanPreferencesKey(actionName)] }
            .firstOrNull() == true
    }

    /**
     * Marks that the specified action has bene shown to the user
     *
     * @param actionName Action name
     */
    suspend fun markActionShown(actionName: String) {
        oneTimeActionsStore.edit { it[booleanPreferencesKey(actionName)] = true }
    }
}