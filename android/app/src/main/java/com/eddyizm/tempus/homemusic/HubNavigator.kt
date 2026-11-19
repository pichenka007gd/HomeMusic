package com.eddyizm.tempus.homemusic

import androidx.lifecycle.MutableLiveData
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.R
import com.eddyizm.tempus.ui.activity.MainActivity

/** Opens a Hub PWA route (e.g. "/assistant/12") in the Hub tab from anywhere in the app. */
object HubNavigator {
    val pendingRoute = MutableLiveData<String?>(null)

    @JvmStatic
    fun consumePendingRoute(): String? {
        val route = pendingRoute.value
        if (route != null) pendingRoute.value = null
        return route
    }

    @JvmStatic
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun open(activity: MainActivity, route: String) {
        pendingRoute.value = route
        val nav = activity.bind?.bottomNavigation ?: return
        // Selecting the item goes through NavigationUI, so the back stack matches a tap on the tab.
        if (activity.navController.currentDestination?.id != R.id.hubFragment) {
            nav.selectedItemId = R.id.hubFragment
        }
        if (activity.isBottomSheetExpanded) activity.collapseBottomSheetDelayed()
    }
}
