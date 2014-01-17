package uk.co.senab.actionbarpulltorefresh.library.listeners;

import android.view.View;

/**
 * Simple Listener to listen for any callbacks to Refresh Bottom.
 */
public interface OnRefreshBottomListener {
    /**
     * Called when the user has initiated a refresh by pulling.
     *
     * @param view - View which the user has started the refresh from.
     */
    public void onRefreshBottomStarted(View view);
}
