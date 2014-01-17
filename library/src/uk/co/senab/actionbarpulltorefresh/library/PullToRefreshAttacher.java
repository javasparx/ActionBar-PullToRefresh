/*
 * Copyright 2013 Chris Banes
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.co.senab.actionbarpulltorefresh.library;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.view.*;
import uk.co.senab.actionbarpulltorefresh.library.listeners.HeaderViewListener;
import uk.co.senab.actionbarpulltorefresh.library.listeners.OnRefreshBottomListener;
import uk.co.senab.actionbarpulltorefresh.library.listeners.OnRefreshListener;
import uk.co.senab.actionbarpulltorefresh.library.viewdelegates.ViewDelegate;

import java.util.WeakHashMap;

public class PullToRefreshAttacher {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "PullToRefreshAttacher";

    /* Member Variables */

    private boolean isOnBottom;
    private boolean isOnTop;

    private EnvironmentDelegate mEnvironmentDelegate;
    private HeaderTransformer headerTransformer;

    private OnRefreshListener onRefreshListener;
    private OnRefreshBottomListener onRefreshBottomListener;

    private Activity activity;
    private View headerView;
    private HeaderViewListener headerViewListener;

    private final int mTouchSlop;
    private final float refreshScrollDistance;

    private float initialMotionY, lastMotionY, pullBeginY;
    private float initialMotionX;
    private boolean isBeingDragged, isRefreshing, handlingTouchEventFromDown;
    private View viewBeingDragged;

    private final WeakHashMap<View, ViewDelegate> refreshableViews;

    private final boolean refreshOnUp;
    private final int refreshMinimizeDelay;
    private final boolean refreshMinimize;
    private boolean isDestroyed = false;

    private final int[] mViewLocationResult = new int[2];
    private Rect mRect = new Rect();

    protected PullToRefreshAttacher(Activity activity, Options options) {
        if (activity == null) {
            throw new IllegalArgumentException("activity cannot be null");
        }
        if (options == null) {
            Log.i(LOG_TAG, "Given null options so using default options.");
            options = new Options();
        }

        this.activity = activity;
        refreshableViews = new WeakHashMap<View, ViewDelegate>();

        // Copy necessary values from options
        refreshScrollDistance = options.refreshScrollDistance;
        refreshOnUp = options.refreshOnUp;
        refreshMinimizeDelay = options.refreshMinimizeDelay;
        refreshMinimize = options.refreshMinimize;

        // EnvironmentDelegate
        mEnvironmentDelegate = options.environmentDelegate != null
                ? options.environmentDelegate
                : createDefaultEnvironmentDelegate();

        // Header Transformer
        headerTransformer = options.headerTransformer != null
                ? options.headerTransformer
                : createDefaultHeaderTransformer();

        // Get touch slop for use later
        mTouchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();

        // Get Window Decor View
        final ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();

        // Create Header view and then add to Decor View
        headerView = LayoutInflater.from(
                mEnvironmentDelegate.getContextForInflater(activity)).inflate(
                options.headerLayout, decorView, false);
        if (headerView == null) {
            throw new IllegalArgumentException("Must supply valid layout id for header.");
        }
        // Make Header View invisible so it still gets a layout pass
        headerView.setVisibility(View.INVISIBLE);

        // Notify transformer
        headerTransformer.onViewCreated(activity, headerView);

        // Now HeaderView to Activity
        decorView.post(new Runnable() {
            @Override
            public void run() {
                if (decorView.getWindowToken() != null) {
                    // The Decor View has a Window Token, so we can add the HeaderView!
                    addHeaderViewToActivity(headerView);
                } else {
                    // The Decor View doesn't have a Window Token yet, post ourselves again...
                    decorView.post(this);
                }
            }
        });
    }

    /**
     * Add a view which will be used to initiate refresh requests.
     *
     * @param view View which will be used to initiate refresh requests.
     */
    void addRefreshableView(View view, ViewDelegate viewDelegate) {
        if (isDestroyed()) return;

        // Check to see if view is null
        if (view == null) {
            Log.i(LOG_TAG, "Refreshable View is null.");
            return;
        }

        // ViewDelegate
        if (viewDelegate == null) {
            viewDelegate = InstanceCreationUtils.getBuiltInViewDelegate(view);
        }

        // View to detect refreshes for
        refreshableViews.put(view, viewDelegate);
    }

    void useViewDelegate(Class<?> viewClass, ViewDelegate delegate) {
        for (View view : refreshableViews.keySet()) {
            if (viewClass.isInstance(view)) {
                refreshableViews.put(view, delegate);
            }
        }
    }

    /**
     * Clear all views which were previously used to initiate refresh requests.
     */
    void clearRefreshableViews() {
        refreshableViews.clear();
    }

    /**
     * This method should be called by your Activity's or Fragment's
     * onConfigurationChanged method.
     *
     * @param newConfig The new configuration
     */
    public void onConfigurationChanged(Configuration newConfig) {
        headerTransformer.onConfigurationChanged(activity, newConfig);
    }

    /**
     * Manually set this Attacher's refreshing state. The header will be
     * displayed or hidden as requested.
     *
     * @param refreshing - Whether the attacher should be in a refreshing state,
     */
    final void setRefreshing(boolean refreshing) {
        setRefreshingInt(null, refreshing, false);
    }

    /**
     * @return true if this Attacher is currently in a refreshing state.
     */
    final boolean isRefreshing() {
        return isRefreshing;
    }

    /**
     * Call this when your refresh is complete and this view should reset itself
     * (header view will be hidden).
     * <p/>
     * This is the equivalent of calling <code>setRefreshing(false)</code>.
     */
    final void setRefreshComplete() {
        setRefreshingInt(null, false, false);
    }

    /**
     * Set the Listener to be called when a refresh is initiated.
     */
    void setOnRefreshListener(OnRefreshListener listener) {
        onRefreshListener = listener;
    }

    void setOnRefreshBottomListener(OnRefreshBottomListener listener) {
        onRefreshBottomListener = listener;
    }

    void destroy() {
        if (isDestroyed) return; // We've already been destroyed

        // Remove the Header View from the Activity
        removeHeaderViewFromActivity(headerView);

        // Lets clear out all of our internal state
        clearRefreshableViews();

        activity = null;
        headerView = null;
        headerViewListener = null;
        mEnvironmentDelegate = null;
        headerTransformer = null;

        isDestroyed = true;
    }

    /**
     * Set a {@link HeaderViewListener} which is called when the visibility
     * state of the Header View has changed.
     */
    final void setHeaderViewListener(HeaderViewListener listener) {
        headerViewListener = listener;
    }

    /**
     * @return The Header View which is displayed when the user is pulling, or
     *         we are refreshing.
     */
    final View getHeaderView() {
        return headerView;
    }

    /**
     * @return The HeaderTransformer currently used by this Attacher.
     */
    HeaderTransformer getHeaderTransformer() {
        return headerTransformer;
    }

    final boolean onInterceptTouchEvent(MotionEvent event) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onInterceptTouchEvent: " + event.toString());
        }

        // If we're not enabled or currently refreshing don't handle any touch
        // events
        if (isRefreshing()) {
            return false;
        }

        final float x = event.getX();
        final float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_MOVE: {
                // We're not currently being dragged so check to see if the user has
                // scrolled enough
                if (!isBeingDragged && initialMotionY > 0f) {
                    final float yDiff = y - initialMotionY;
                    final float xDiff = x - initialMotionX;

                    if (isOnTop) {
                        if (yDiff > xDiff && yDiff > mTouchSlop) {
                            isBeingDragged = true;
                            onPullStarted(y);
                        } else if (yDiff < -mTouchSlop) {
                            resetTouch();
                        }
                    } else if (isOnBottom) {
                        float anotherYDiff = Math.abs(yDiff);
                        if (anotherYDiff > xDiff && yDiff < -mTouchSlop) {
                            isBeingDragged = true;
                            onPullStarted(y);
                        } else if (yDiff > mTouchSlop) {
                            resetTouch();
                        }
                    }
                }
                break;
            }

            case MotionEvent.ACTION_DOWN: {
                // If we're already refreshing, ignore
                if (canRefresh(true)) {
                    for (View view : refreshableViews.keySet()) {

                        /*If refresh bottom enabled*/
                        isOnBottom = onRefreshBottomListener != null && isViewBeingDraggedDown(view, event);
                        /*If refresh top enabled*/
                        isOnTop = onRefreshListener != null && isViewBeingDragged(view, event);

                        if (isOnTop || isOnBottom) {
//                            if (DEBUG) Log.d(LOG_TAG, "isOnTop OR isOnBottom: " + true);
                            initialMotionX = x;
                            initialMotionY = y;
                            viewBeingDragged = view;
                        }
                    }
                }
                break;
            }

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                resetTouch();
                break;
            }
        }

        if (DEBUG) Log.d(LOG_TAG, "onInterceptTouchEvent. Returning " + isBeingDragged);

        return isBeingDragged;
    }

    final boolean isViewBeingDragged(View view, MotionEvent event) {
        if (view.isShown() && refreshableViews.containsKey(view)) {
            // First we need to set the rect to the view's screen co-ordinates
            view.getLocationOnScreen(mViewLocationResult);
            final int viewLeft = mViewLocationResult[0], viewTop = mViewLocationResult[1];
            mRect.set(viewLeft, viewTop, viewLeft + view.getWidth(), viewTop + view.getHeight());

//            if (DEBUG) Log.d(LOG_TAG, "isViewBeingDragged. View Rect: " + mRect.toString());

            final int rawX = (int) event.getRawX(), rawY = (int) event.getRawY();
            if (mRect.contains(rawX, rawY)) {
                // The Touch Event is within the View's display Rect
                ViewDelegate delegate = refreshableViews.get(view);
                if (delegate != null) {
                    // Now call the delegate, converting the X/Y into the View's co-ordinate system
                    return delegate.isReadyForPull(view, rawX - mRect.left, rawY - mRect.top);
                }
            }
        }
        return false;
    }

    final boolean isViewBeingDraggedDown(View view, MotionEvent event) {
        if (view.isShown() && refreshableViews.containsKey(view)) {
            // First we need to set the rect to the view's screen co-ordinates
            view.getLocationOnScreen(mViewLocationResult);
            final int viewLeft = mViewLocationResult[0], viewTop = mViewLocationResult[1];
            mRect.set(viewLeft, viewTop, viewLeft + view.getWidth(), viewTop + view.getHeight());

//            if (DEBUG) Log.d(LOG_TAG, "isViewBeingDraggedDown. View Rect: " + mRect.toString());

            final int rawX = (int) event.getRawX(), rawY = (int) event.getRawY();
//            if (DEBUG) Log.d(LOG_TAG, "RawX: " + rawX + ", RawY: " + rawY);
            if (mRect.contains(rawX, rawY)) {
//                if (DEBUG) Log.d(LOG_TAG, "mRect.contains(rawX, rawY)=" + true);
                // The Touch Event is within the View's display Rect
                ViewDelegate delegate = refreshableViews.get(view);
                if (delegate != null) {
                    // Now call the delegate, converting the X/Y into the View's co-ordinate system

                    return delegate.isReadyForPullDown(view, rawX - mRect.left, rawY - mRect.top);
                }
            }
        }
        return false;
    }

    final boolean onTouchEvent(MotionEvent event) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTouchEvent: " + event.toString());
        }

        // Record whether our handling is started from ACTION_DOWN
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            handlingTouchEventFromDown = true;
        }

        // If we're being called from ACTION_DOWN then we must call through to
        // onInterceptTouchEvent until it sets isBeingDragged
        if (handlingTouchEventFromDown && !isBeingDragged) {
            onInterceptTouchEvent(event);
            return true;
        }

//        if (DEBUG) Log.d(LOG_TAG, "Passed isDragging");

        if (viewBeingDragged == null) {
            return false;
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_MOVE: {
                // If we're already refreshing ignore it
                if (isRefreshing()) {
                    return false;
                }

                final float y = event.getY();

                if (isBeingDragged && y != lastMotionY) {

                    float yDx = -mTouchSlop - 1;

                    if (isOnTop) {
                        yDx = y - lastMotionY;
                    } else if (isOnBottom) {
                        yDx = lastMotionY == -1 ? (y - lastMotionY) : (lastMotionY - y);
                    }

                    if (yDx >= -mTouchSlop) {

                        onPull(viewBeingDragged, y);
                        // Only record the y motion if the user has scrolled down.

                        if (yDx > 0f) {
                            lastMotionY = y;
                        }
                    } else {
                        onPullEnded();
                        resetTouch();
                    }

                }
                break;
            }

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                checkScrollForRefresh(viewBeingDragged);
                if (isBeingDragged) {
                    onPullEnded();
                }
                resetTouch();
                break;
            }
        }

        return true;
    }

    void minimizeHeader() {
        if (isDestroyed()) return;

        headerTransformer.onRefreshMinimized();

        if (headerViewListener != null) {
            headerViewListener.onStateChanged(headerView, HeaderViewListener.STATE_MINIMIZED);
        }
    }

    void resetTouch() {
        isBeingDragged = false;
        handlingTouchEventFromDown = false;
        initialMotionY = lastMotionY = pullBeginY = -1f;
    }

    void onPullStarted(float y) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onPullStarted");
        }
        showHeaderView();
        pullBeginY = y;
    }

    void onPull(View view, float y) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onPull");
        }

        final float pxScrollForRefresh = getScrollNeededForRefresh(view);
        final float scrollLength = isOnTop ? (y - pullBeginY) : (pullBeginY - y);

        if (scrollLength < pxScrollForRefresh) {
            headerTransformer.onPulled(scrollLength / pxScrollForRefresh);
        } else {
            if (refreshOnUp) {
                headerTransformer.onReleaseToRefresh();
            } else {
                setRefreshingInt(view, true, true);
            }
        }

    }

    void onPullEnded() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onPullEnded");
        }
        if (!isRefreshing) {
            reset(true);
        }
    }

    void showHeaderView() {
        updateHeaderViewPosition(headerView);
        if (headerTransformer.showHeaderView()) {
            if (headerViewListener != null) {
                headerViewListener.onStateChanged(headerView, HeaderViewListener.STATE_VISIBLE);
            }
        }
    }

    void hideHeaderView() {
        if (headerTransformer.hideHeaderView()) {
            if (headerViewListener != null) {
                headerViewListener.onStateChanged(headerView, HeaderViewListener.STATE_HIDDEN);
            }
        }
    }

    protected final Activity getAttachedActivity() {
        return activity;
    }

    protected EnvironmentDelegate createDefaultEnvironmentDelegate() {
        return new EnvironmentDelegate() {
            @Override
            public Context getContextForInflater(Activity activity) {
                Context context = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                    ActionBar ab = activity.getActionBar();
                    if (ab != null) {
                        context = ab.getThemedContext();
                    }
                }
                if (context == null) {
                    context = activity;
                }
                return context;
            }
        };
    }

    protected HeaderTransformer createDefaultHeaderTransformer() {
        return new DefaultHeaderTransformer();
    }

    private boolean checkScrollForRefresh(View view) {
        if (isBeingDragged && refreshOnUp && view != null) {
            if (isOnTop) {
//                if (DEBUG) Log.d(LOG_TAG, "pullBeginY: " + pullBeginY);
//                if (DEBUG) Log.d(LOG_TAG, "lastMotionY: " + lastMotionY);
//                if (DEBUG) Log.d(LOG_TAG, "pullBeginY - lastMotionY: " + (lastMotionY - pullBeginY));
//                if (DEBUG) Log.d(LOG_TAG, "getScrollNeededForRefresh(view): " + getScrollNeededForRefresh(view));
                if (lastMotionY - pullBeginY >= getScrollNeededForRefresh(view)) {
                    setRefreshingInt(view, true, true);
                    return true;
                }
            } else if (isOnBottom) {
//                if (DEBUG) Log.d(LOG_TAG, "pullBeginY: " + pullBeginY);
//                if (DEBUG) Log.d(LOG_TAG, "lastMotionY: " + lastMotionY);
//                if (DEBUG) Log.d(LOG_TAG, "pullBeginY - lastMotionY: " + (pullBeginY - lastMotionY));
//                if (DEBUG) Log.d(LOG_TAG, "getScrollNeededForRefresh(view): " + getScrollNeededForRefresh(view));
                if (pullBeginY - lastMotionY >= getScrollNeededForRefresh(view)) {
                    setRefreshingInt(view, true, true);
                    return true;
                }
            }
        }
        return false;
    }

    private void setRefreshingInt(View view, boolean refreshing, boolean fromTouch) {
        if (isDestroyed()) return;

        if (DEBUG) Log.d(LOG_TAG, "setRefreshingInt: " + refreshing);
        // Check to see if we need to do anything
        if (isRefreshing == refreshing) {
            return;
        }

        resetTouch();

        if (refreshing && canRefresh(fromTouch)) {
            startRefresh(view, fromTouch);
        } else {
            reset(fromTouch);
        }
    }

    /**
     * @param fromTouch Whether this is being invoked from a touch event
     * @return true if we're currently in a state where a refresh can be
     *         started.
     */
    private boolean canRefresh(boolean fromTouch) {
        return !isRefreshing && (!fromTouch || onRefreshListener != null || onRefreshBottomListener != null);
    }

    private float getScrollNeededForRefresh(View view) {
        return view.getHeight() * refreshScrollDistance;
    }

    private void reset(boolean fromTouch) {
        // Update isRefreshing state
        isRefreshing = false;

        // Remove any minimize callbacks
        if (refreshMinimize) {
            getHeaderView().removeCallbacks(refreshMinimizeRunnable);
        }

        // Hide Header View
        hideHeaderView();
    }

    private void startRefresh(View view, boolean fromTouch) {
        // Update isRefreshing state
        isRefreshing = true;

        // Call OnRefreshListener if this call has originated from a touch event
        if (fromTouch) {
            if (onRefreshListener != null && isOnTop) {
                onRefreshListener.onRefreshStarted(view);
            } else if (onRefreshBottomListener != null && isOnBottom) {
                onRefreshBottomListener.onRefreshBottomStarted(view);
            }
        }

        // Call Transformer
        headerTransformer.onRefreshStarted();

        // Show Header View
        showHeaderView();

        // Post a runnable to minimize the refresh header
        if (refreshMinimize) {
            if (refreshMinimizeDelay > 0) {
                getHeaderView().postDelayed(refreshMinimizeRunnable, refreshMinimizeDelay);
            } else {
                getHeaderView().post(refreshMinimizeRunnable);
            }
        }
    }

    private boolean isDestroyed() {
        if (isDestroyed) {
            Log.i(LOG_TAG, "PullToRefreshAttacher is destroyed.");
        }
        return isDestroyed;
    }

    protected void addHeaderViewToActivity(View headerView) {
        // Get the Display Rect of the Decor View
        activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(mRect);

        // Honour the requested layout params
        int width = WindowManager.LayoutParams.MATCH_PARENT;
        int height = WindowManager.LayoutParams.WRAP_CONTENT;
        ViewGroup.LayoutParams requestedLp = headerView.getLayoutParams();
        if (requestedLp != null) {
            width = requestedLp.width;
            height = requestedLp.height;
        }

        // Create LayoutParams for adding the View as a panel
        WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        wlp.x = 0;
        wlp.y = mRect.top;
        wlp.gravity = Gravity.TOP;

        // Workaround for Issue #182
        headerView.setTag(wlp);
        activity.getWindowManager().addView(headerView, wlp);
    }

    protected void updateHeaderViewPosition(View headerView) {
        // Refresh the Display Rect of the Decor View
        activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(mRect);

        WindowManager.LayoutParams wlp = null;
        if (headerView.getLayoutParams() instanceof WindowManager.LayoutParams) {
            wlp = (WindowManager.LayoutParams) headerView.getLayoutParams();
        } else if (headerView.getTag() instanceof WindowManager.LayoutParams) {
            wlp = (WindowManager.LayoutParams) headerView.getTag();
        }

        Log.i(LOG_TAG, "wlp != null && wlp.y != mRect.top = " + (wlp != null && wlp.y != mRect.top));

        if (wlp != null && wlp.y != mRect.top) {
            wlp.y = mRect.top;
            activity.getWindowManager().updateViewLayout(headerView, wlp);
        }
    }

    protected void removeHeaderViewFromActivity(View headerView) {
        if (headerView.getWindowToken() != null) {
            activity.getWindowManager().removeViewImmediate(headerView);
        }
    }

    private final Runnable refreshMinimizeRunnable = new Runnable() {
        @Override
        public void run() {
            minimizeHeader();
        }
    };
}
