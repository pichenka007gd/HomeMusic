package com.eddyizm.tempus.homemusic

import android.content.SharedPreferences
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.eddyizm.tempus.App
import com.eddyizm.tempus.util.Preferences
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class HomeLibraryVisibilityTest {
    @Test
    fun serverFoldersStayHiddenForNewAndExistingUsers() {
        val app = mock<App>()
        val prefs = mock<SharedPreferences>()
        whenever(app.preferences).thenReturn(prefs)
        mockStatic(App::class.java).use { appStatic ->
            appStatic.`when`<App> { App.getInstance() }.thenReturn(app)

            // A new install uses true by default; an existing install may have either value.
            whenever(prefs.getBoolean("music_directory_section_visibility", true)).thenReturn(true)
            assertFalse(Preferences.isMusicDirectorySectionVisible())
            whenever(prefs.getBoolean("music_directory_section_visibility", true)).thenReturn(false)
            assertFalse(Preferences.isMusicDirectorySectionVisible())
        }
    }

    @Test
    fun openingSettingsCategoriesCannotRestoreTheFoldersSwitch() {
        val fragment = mock<PreferenceFragmentCompat>()
        val folders = mock<Preference>()
        whenever(fragment.findPreference<Preference>("music_directory_section_visibility")).thenReturn(folders)

        HubSettings.applyVisibility(fragment)
        // Settings resets all rows to visible before applying its category/search state.
        folders.isVisible = true
        HubSettings.applyVisibility(fragment)

        verify(folders, times(2)).isVisible = false
    }
}
