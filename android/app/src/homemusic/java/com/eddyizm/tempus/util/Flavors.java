package com.eddyizm.tempus.util;

import android.app.Activity;
import android.content.Context;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.core.content.ContextCompat;
import androidx.mediarouter.app.MediaRouteButton;

import com.eddyizm.tempus.R;
import com.eddyizm.tempus.homemusic.GoogleIdCallback;
import com.eddyizm.tempus.homemusic.HomeGoogleSignIn;

public class Flavors {
    public static void initializeCastContext(Context context) {

    }

    public static void setUpRouteButton(Context context, Menu menu) {
        MenuItem item = menu.findItem(R.id.media_route_menu_item);
        item.setTitle(R.string.upnp_route_menu_title);

        // The stock glyph is Google Cast's, which this build cannot reach.
        View routeView = item.getActionView();
        if (routeView instanceof MediaRouteButton) {
            ((MediaRouteButton) routeView).setRemoteIndicatorDrawable(
                    ContextCompat.getDrawable(context, R.drawable.ic_cast_audio));
        }
    }

    /** Sign-in with Google through Credential Manager (needs Google Play services on the phone). */
    public static boolean hasGoogleSignIn() {
        return true;
    }

    public static void googleSignIn(Activity activity, String clientId, GoogleIdCallback callback) {
        HomeGoogleSignIn.request(activity, clientId, callback);
    }
}
