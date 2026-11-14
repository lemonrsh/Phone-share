package com.lemonrsh.phoneshare.pip;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Content of the YouTube-style floating player: a rounded, shadowed card with
 * the mirrored video, a loading state, and gesture recognition.
 *
 * <p>Gestures (reported to {@link Listener} in screen coordinates):
 * <ul>
 *   <li>Drag to move, fling to throw, drag/fling to the bottom target to close.</li>
 *   <li>Pinch with two fingers to resize; double-tap to toggle small/large.</li>
 *   <li>With remote control enabled, one finger on the video controls the
 *       remote device; moving uses the handle at the top or two fingers.
 *       Tapping the handle shows back/home/recents.</li>
 * </ul>
 */
@SuppressLint("ViewConstructor")
public final class PipView extends FrameLayout {
    public interface Listener {
        void onSurfaceAvailable(Surface surface);

        void onSurfaceDestroyed();

        void onDragStart();

        void onDragMove(float dx, float dy);

        void onDragEnd(float velocityX, float velocityY);

        void onPinchStart();

        void onPinch(float scale, float focusRawX, float focusRawY);

        void onPinchEnd();

        void onDoubleTap();

        /** A touch for the remote device, in video-view coordinates. */
        void onRemoteTouch(MotionEvent event, int videoWidth, int videoHeight);

        void onKey(int keycode);

        /** The error card was tapped. */
        void onErrorTapped();
    }

    private static final int MODE_NONE = 0;
    private static final int MODE_WINDOW = 1;
    private static final int MODE_REMOTE = 2;
    private static final int MODE_CHILDREN = 3;
    private static final long NAV_AUTO_HIDE_MS = 4_000;

    private final Listener listener;
    private final boolean controlEnabled;
    private final float density;
    private final int touchSlop;
    private final int shadowPadding;
    private final float cornerRadius;

    private final FrameLayout card;
    private final TextureView videoView;
    private final LinearLayout loadingView;
    private final TextView statusText;
    private final ProgressBar progress;
    private final LinearLayout navBar;
    private final View handle;
    private final GestureDetector gestureDetector;
    private final Runnable hideNavRunnable = this::hideNavBar;

    private Surface surface;
    private boolean errorShown;
    private VelocityTracker velocityTracker;
    private int mode = MODE_NONE;
    private boolean downOnHandle;
    private boolean dragging;
    private boolean pinching;
    private float lastRawX;
    private float lastRawY;
    private float downRawX;
    private float downRawY;
    private float pinchStartSpan;

    public PipView(Context context, boolean controlEnabled, Listener listener) {
        super(context);
        this.listener = listener;
        this.controlEnabled = controlEnabled;
        density = getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        shadowPadding = dp(10);
        cornerRadius = dp(14);

        setClipChildren(false);
        setClipToPadding(false);
        setPadding(shadowPadding, shadowPadding, shadowPadding, shadowPadding);

        card = new FrameLayout(context);
        card.setBackgroundColor(Color.BLACK);
        card.setElevation(dp(8));
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius);
            }
        });
        card.setClipToOutline(true);
        addView(card, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        videoView = new TextureView(context);
        videoView.setOpaque(true);
        videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                releaseSurface();
                surface = new Surface(texture);
                PipView.this.listener.onSurfaceAvailable(surface);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                // The decoder output is scaled to the view.
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                // Stop rendering into the texture before it is released.
                PipView.this.listener.onSurfaceDestroyed();
                releaseSurface();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                // Nothing to do per frame.
            }
        });
        card.addView(videoView, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        loadingView = new LinearLayout(context);
        loadingView.setOrientation(LinearLayout.VERTICAL);
        loadingView.setGravity(Gravity.CENTER);
        loadingView.setBackgroundColor(0xFF111318);
        progress = new ProgressBar(context);
        progress.setIndeterminate(true);
        loadingView.addView(progress, new LinearLayout.LayoutParams(dp(32), dp(32)));
        statusText = new TextView(context);
        statusText.setTextColor(0xCCFFFFFF);
        statusText.setTextSize(12);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(dp(12), dp(10), dp(12), 0);
        loadingView.addView(statusText, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT));
        card.addView(loadingView, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        navBar = new LinearLayout(context);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable navBackground = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xB3000000, 0x00000000});
        navBar.setBackground(navBackground);
        navBar.setVisibility(GONE);
        navBar.setAlpha(0f);
        addNavButton("◁", 4, "Back");
        addNavButton("○", 3, "Home");
        addNavButton("▢", 187, "Recent apps");
        card.addView(navBar, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48), Gravity.BOTTOM));

        handle = new View(context);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x99FFFFFF);
        pill.setCornerRadius(dp(2));
        handle.setBackground(pill);
        handle.setVisibility(controlEnabled ? VISIBLE : GONE);
        FrameLayout.LayoutParams handleParams = new FrameLayout.LayoutParams(dp(36), dp(4),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        handleParams.topMargin = dp(7);
        card.addView(handle, handleParams);

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent event) {
                if (errorShown) {
                    PipView.this.listener.onErrorTapped();
                } else if (controlEnabled) {
                    toggleNavBar();
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent event) {
                PipView.this.listener.onDoubleTap();
                return true;
            }
        });
    }

    public int getShadowPadding() {
        return shadowPadding;
    }

    public View getCard() {
        return card;
    }

    public void setStatus(String text) {
        statusText.setText(text);
    }

    /** Replaces the video with an error message that stays until the window is swiped away. */
    public void showError(String message) {
        errorShown = true;
        hideNavBar();
        progress.setVisibility(GONE);
        statusText.setTextColor(Color.WHITE);
        statusText.setText(message + "\n\nTap to view the log · swipe away to close");
        loadingView.setBackgroundColor(0xFF3A1515);
        loadingView.animate().cancel();
        loadingView.setAlpha(1f);
        loadingView.setVisibility(VISIBLE);
    }

    public void showLoading(boolean show) {
        errorShown = false;
        if (show) {
            progress.setVisibility(VISIBLE);
            statusText.setTextColor(0xCCFFFFFF);
            loadingView.setBackgroundColor(0xFF111318);
            loadingView.animate().cancel();
            loadingView.setAlpha(1f);
            loadingView.setVisibility(VISIBLE);
        } else if (loadingView.getVisibility() == VISIBLE) {
            loadingView.animate().alpha(0f).setDuration(220)
                    .withEndAction(() -> loadingView.setVisibility(GONE)).start();
        }
    }

    public void release() {
        removeCallbacks(hideNavRunnable);
        recycleVelocityTracker();
        releaseSurface();
    }

    private void releaseSurface() {
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    private void addNavButton(String label, final int keycode, String description) {
        TextView button = new TextView(getContext());
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(20);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setOnClickListener(view -> {
            listener.onKey(keycode);
            scheduleNavHide();
        });
        navBar.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    private void toggleNavBar() {
        if (navBar.getVisibility() == VISIBLE) {
            hideNavBar();
        } else {
            navBar.animate().cancel();
            navBar.setVisibility(VISIBLE);
            navBar.animate().alpha(1f).setDuration(150).start();
            scheduleNavHide();
        }
    }

    private void scheduleNavHide() {
        removeCallbacks(hideNavRunnable);
        postDelayed(hideNavRunnable, NAV_AUTO_HIDE_MS);
    }

    private void hideNavBar() {
        removeCallbacks(hideNavRunnable);
        if (navBar.getVisibility() != VISIBLE) {
            return;
        }
        navBar.animate().cancel();
        navBar.animate().alpha(0f).setDuration(150).withEndAction(() -> navBar.setVisibility(GONE)).start();
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mode = chooseMode(event);
            dragging = false;
            pinching = false;
        }

        switch (mode) {
            case MODE_CHILDREN:
                return super.dispatchTouchEvent(event);
            case MODE_REMOTE:
                if (action == MotionEvent.ACTION_POINTER_DOWN) {
                    // A second finger turns the gesture into move/resize: cancel the remote touch.
                    // Only the first finger reached the remote device, so only lift that one.
                    int first = event.getActionIndex() == 0 ? 1 : 0;
                    MotionEvent cancel = MotionEvent.obtain(event.getDownTime(), event.getEventTime(),
                            MotionEvent.ACTION_CANCEL, event.getX(first), event.getY(first), 0);
                    forwardToRemote(cancel);
                    cancel.recycle();
                    mode = MODE_WINDOW;
                    startWindowGesture(event);
                    handleWindowGesture(event);
                    return true;
                }
                forwardToRemote(event);
                return true;
            case MODE_WINDOW:
                if (action == MotionEvent.ACTION_DOWN) {
                    startWindowGesture(event);
                }
                handleWindowGesture(event);
                return true;
            default:
                return true;
        }
    }

    private int chooseMode(MotionEvent event) {
        float x = event.getX() - card.getLeft();
        float y = event.getY() - card.getTop();
        downOnHandle = false;
        if (navBar.getVisibility() == VISIBLE && y >= card.getHeight() - navBar.getHeight()) {
            return MODE_CHILDREN;
        }
        if (!controlEnabled || loadingView.getVisibility() == VISIBLE) {
            return MODE_WINDOW;
        }
        int handleZoneHeight = dp(28);
        float handleZoneHalfWidth = Math.max(dp(48), card.getWidth() * 0.3f);
        if (y <= handleZoneHeight && Math.abs(x - card.getWidth() / 2f) <= handleZoneHalfWidth) {
            downOnHandle = true;
            return MODE_WINDOW;
        }
        return MODE_REMOTE;
    }

    private void forwardToRemote(MotionEvent event) {
        MotionEvent local = MotionEvent.obtain(event);
        local.offsetLocation(-card.getLeft(), -card.getTop());
        listener.onRemoteTouch(local, videoView.getWidth(), videoView.getHeight());
        local.recycle();
    }

    private void startWindowGesture(MotionEvent event) {
        recycleVelocityTracker();
        velocityTracker = VelocityTracker.obtain();
        float[] focus = rawFocus(event);
        lastRawX = focus[0];
        lastRawY = focus[1];
        downRawX = focus[0];
        downRawY = focus[1];
    }

    private void handleWindowGesture(MotionEvent event) {
        // The window moves under the finger, so track screen coordinates.
        MotionEvent raw = MotionEvent.obtain(event);
        raw.offsetLocation(event.getRawX() - event.getX(), event.getRawY() - event.getY());
        if (velocityTracker != null) {
            velocityTracker.addMovement(raw);
        }
        if (!pinching && (!controlEnabled || downOnHandle || errorShown)) {
            gestureDetector.onTouchEvent(raw);
        }
        raw.recycle();

        int action = event.getActionMasked();
        float[] focus = rawFocus(event);
        switch (action) {
            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() == 2) {
                    if (dragging) {
                        listener.onDragEnd(0, 0);
                        dragging = false;
                    }
                    pinching = true;
                    pinchStartSpan = span(event);
                    listener.onPinchStart();
                }
                lastRawX = focus[0];
                lastRawY = focus[1];
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (pinching && event.getPointerCount() <= 2) {
                    pinching = false;
                    listener.onPinchEnd();
                }
                // Re-anchor on the remaining finger(s), excluding the one going up.
                float[] remaining = rawFocusExcluding(event, event.getActionIndex());
                lastRawX = remaining[0];
                lastRawY = remaining[1];
                break;
            case MotionEvent.ACTION_MOVE:
                if (pinching) {
                    if (event.getPointerCount() >= 2 && pinchStartSpan > 0) {
                        listener.onPinch(span(event) / pinchStartSpan, focus[0], focus[1]);
                    }
                    break;
                }
                if (!dragging && Math.hypot(focus[0] - downRawX, focus[1] - downRawY) > touchSlop) {
                    dragging = true;
                    lastRawX = focus[0];
                    lastRawY = focus[1];
                    listener.onDragStart();
                }
                if (dragging) {
                    listener.onDragMove(focus[0] - lastRawX, focus[1] - lastRawY);
                    lastRawX = focus[0];
                    lastRawY = focus[1];
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pinching) {
                    pinching = false;
                    listener.onPinchEnd();
                }
                if (dragging) {
                    float vx = 0;
                    float vy = 0;
                    if (velocityTracker != null && action == MotionEvent.ACTION_UP) {
                        velocityTracker.computeCurrentVelocity(1000, ViewConfiguration.get(getContext())
                                .getScaledMaximumFlingVelocity());
                        vx = velocityTracker.getXVelocity();
                        vy = velocityTracker.getYVelocity();
                    }
                    listener.onDragEnd(vx, vy);
                    dragging = false;
                }
                recycleVelocityTracker();
                mode = MODE_NONE;
                break;
            default:
                break;
        }
    }

    private static float[] rawFocus(MotionEvent event) {
        return rawFocusExcluding(event, -1);
    }

    private static float[] rawFocusExcluding(MotionEvent event, int excludedIndex) {
        float offsetX = event.getRawX() - event.getX();
        float offsetY = event.getRawY() - event.getY();
        float sumX = 0;
        float sumY = 0;
        int count = 0;
        for (int i = 0; i < event.getPointerCount(); ++i) {
            if (i == excludedIndex) {
                continue;
            }
            sumX += event.getX(i);
            sumY += event.getY(i);
            count++;
        }
        if (count == 0) {
            return new float[] {event.getRawX(), event.getRawY()};
        }
        return new float[] {sumX / count + offsetX, sumY / count + offsetY};
    }

    private static float span(MotionEvent event) {
        if (event.getPointerCount() < 2) {
            return 0;
        }
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    private void recycleVelocityTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    private int dp(float value) {
        return Math.round(value * density);
    }
}
