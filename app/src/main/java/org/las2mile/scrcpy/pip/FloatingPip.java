package org.las2mile.scrcpy.pip;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * A YouTube / picture-in-picture style floating player window:
 * rounded corners, spring physics when thrown, snapping to the screen sides,
 * pinch and double-tap resizing, and drag-or-fling to the bottom target to
 * close (no close button).
 */
@SuppressLint("RtlHardcoded")
public final class FloatingPip implements PipView.Listener {
    private static final String TAG = "FloatingPip";

    public interface Callbacks {
        void onSurfaceAvailable(Surface surface);

        void onSurfaceDestroyed();

        void onRemoteTouch(MotionEvent event, int videoWidth, int videoHeight);

        void onKey(int keycode);

        /** The user tapped the error card (to see the log). */
        void onErrorTapped();

        /** The user swiped the window away. */
        void onDismissed();
    }

    /** Window sizes as a fraction of the screen area. */
    private static final float DEFAULT_AREA = 0.09f;
    private static final float LARGE_AREA = 0.24f;
    private static final float MIN_AREA = 0.035f;
    private static final float MAX_AREA = 0.5f;
    private static final float SPRING_STIFFNESS = 700f;
    private static final float SPRING_DAMPING = 0.82f;
    private static final float FLING_PROJECTION_SECONDS = 0.22f;

    private final Context context;
    private final Callbacks callbacks;
    private final WindowManager windowManager;
    private final PipView pipView;
    private final WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
    private final int padding;
    private final float density;
    private final Spring2D spring;

    private FrameLayout dismissView;
    private View dismissCircle;
    private boolean dismissAdded;

    // Window state; x/y are the window's top-left corner (including the shadow padding).
    private float x;
    private float y;
    private int cardWidth;
    private int cardHeight;
    private float aspect = 9f / 16f;
    private float areaFraction = DEFAULT_AREA;

    private int screenWidth;
    private int screenHeight;
    private final Rect bounds = new Rect(); // allowed area for the card
    private int bottomInset;

    private boolean added;
    private boolean removed;
    private boolean magnetized;
    private boolean dismissing;
    private float dragX;
    private float dragY;
    private float pinchStartArea;
    private float pinchCenterX;
    private float pinchCenterY;
    private float pinchFocusX = Float.NaN;
    private float pinchFocusY = Float.NaN;
    private ValueAnimator resizeAnimator;

    public FloatingPip(Context context, boolean controlEnabled, Callbacks callbacks) {
        this.context = context;
        this.callbacks = callbacks;
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        density = context.getResources().getDisplayMetrics().density;
        pipView = new PipView(context, controlEnabled, this);
        padding = pipView.getShadowPadding();
        spring = new Spring2D(new Spring2D.Target() {
            @Override
            public void onSpringUpdate(float springX, float springY) {
                x = springX;
                y = springY;
                applyLayout();
            }

            @Override
            public void onSpringEnd() {
                // Nothing to do: the window rests where the spring stopped.
            }
        }, SPRING_STIFFNESS, SPRING_DAMPING, 0.5f);
    }

    // ---------------------------------------------------------------- public API

    /** Adds the window (bottom-right, like YouTube). Throws if overlays are not permitted. */
    public void show() {
        if (added) {
            return;
        }
        computeBounds();
        computeCardSize();
        x = maxX();
        y = maxY();

        layoutParams.type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : legacyOverlayType();
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                // Required for TextureView in a window that is not owned by an activity.
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        layoutParams.format = PixelFormat.TRANSLUCENT;
        layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        layoutParams.setTitle("scrcpy floating player");
        fillLayoutParams();
        windowManager.addView(pipView, layoutParams);
        added = true;

        View card = pipView.getCard();
        card.setScaleX(0.6f);
        card.setScaleY(0.6f);
        card.setAlpha(0f);
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(280)
                .setInterpolator(new OvershootInterpolator(1.1f)).start();
    }

    public void setStatus(String text) {
        pipView.setStatus(text);
    }

    public void showLoading(boolean show) {
        pipView.showLoading(show);
    }

    /** Shows an error in the window, enlarged so that the text is readable. */
    public void showError(String message) {
        pipView.showError(message);
        if (!dismissing && areaFraction < LARGE_AREA) {
            animateResize(LARGE_AREA);
        }
    }

    /** Adapts the window to the remote screen's aspect ratio (smoothly). */
    public void setVideoSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        float newAspect = (float) width / height;
        if (Math.abs(newAspect - aspect) < 0.001f) {
            return;
        }
        aspect = newAspect;
        animateResize(areaFraction);
    }

    /** Call when the local screen rotates or resizes. */
    public void onConfigurationChanged() {
        if (!added || dismissing) {
            return;
        }
        boolean wasLeft = isOnLeft();
        float relativeY = (y - minY()) / Math.max(1f, maxY() - minY());
        computeBounds();
        computeCardSize();
        x = wasLeft ? minX() : maxX();
        y = minY() + clamp(relativeY, 0f, 1f) * (maxY() - minY());
        applyLayout();
    }

    public void remove() {
        // Late callbacks (e.g. the texture being destroyed after removal) must not
        // reach the owner, which may already use a new window.
        removed = true;
        spring.cancel();
        if (resizeAnimator != null) {
            resizeAnimator.cancel();
        }
        removeDismissTarget();
        if (added) {
            added = false;
            try {
                windowManager.removeView(pipView);
            } catch (RuntimeException ignored) {
                // Already removed.
            }
        }
        pipView.release();
    }

    // ---------------------------------------------------------------- PipView.Listener

    @Override
    public void onSurfaceAvailable(Surface surface) {
        if (!removed) {
            callbacks.onSurfaceAvailable(surface);
        }
    }

    @Override
    public void onSurfaceDestroyed() {
        if (!removed) {
            callbacks.onSurfaceDestroyed();
        }
    }

    @Override
    public void onRemoteTouch(MotionEvent event, int videoWidth, int videoHeight) {
        if (!removed && !dismissing) {
            callbacks.onRemoteTouch(event, videoWidth, videoHeight);
        }
    }

    @Override
    public void onKey(int keycode) {
        if (!removed && !dismissing) {
            callbacks.onKey(keycode);
        }
    }

    @Override
    public void onErrorTapped() {
        if (!removed && !dismissing) {
            callbacks.onErrorTapped();
        }
    }

    @Override
    public void onDragStart() {
        if (dismissing) {
            return;
        }
        cancelMotion();
        dragX = x;
        dragY = y;
        magnetized = false;
        showDismissTarget();
    }

    @Override
    public void onDragMove(float dx, float dy) {
        if (dismissing) {
            return;
        }
        dragX += dx;
        dragY += dy;
        // Follow the finger, but do not let the window leave the screen completely.
        dragX = clamp(dragX, minX() - cardWidth * 0.4f, maxX() + cardWidth * 0.4f);
        dragY = clamp(dragY, minY() - cardHeight * 0.2f, screenHeight - padding - cardHeight * 0.4f);

        float distance = distanceToTarget(dragX, dragY);
        // Hysteresis: easier to stay captured than to get captured.
        boolean near = magnetized ? distance < dp(150) : distance < dp(110);
        if (near && !magnetized) {
            magnetized = true;
            pipView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            animateCircle(1.3f);
            springTo(targetWindowX(), targetWindowY(), 0, 0);
            pipView.getCard().animate().scaleX(0.85f).scaleY(0.85f).alpha(0.9f).setDuration(150).start();
        } else if (!near && magnetized) {
            magnetized = false;
            animateCircle(1f);
            pipView.getCard().animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(150).start();
        }
        if (!magnetized) {
            if (spring.isRunning()) {
                // Just released from the magnet: glide back under the finger.
                spring.retarget(dragX, dragY);
            } else {
                x = dragX;
                y = dragY;
                applyLayout();
            }
        }
    }

    @Override
    public void onDragEnd(float velocityX, float velocityY) {
        if (dismissing) {
            return;
        }
        if (magnetized) {
            dismiss();
            return;
        }
        // A fast downward throw towards the bottom closes the window, like YouTube.
        float projectedCenterY = y + padding + cardHeight / 2f + velocityY * FLING_PROJECTION_SECONDS;
        if (velocityY > dp(1600) && velocityY > Math.abs(velocityX) * 1.5f
                && projectedCenterY > targetCenterY() - dp(120)) {
            magnetized = true;
            animateCircle(1.3f);
            springTo(targetWindowX(), targetWindowY(), velocityX, velocityY);
            pipView.postDelayed(this::dismiss, 140);
            return;
        }
        // A hard sideways throw past the screen edge swipes it away.
        float projectedCenterX = x + padding + cardWidth / 2f + velocityX * FLING_PROJECTION_SECONDS;
        if (Math.abs(velocityX) > dp(2200) && Math.abs(velocityX) > Math.abs(velocityY) * 1.5f
                && (projectedCenterX < 0 || projectedCenterX > screenWidth)) {
            hideDismissTarget();
            swipeAway(velocityX);
            return;
        }
        hideDismissTarget();
        settle(velocityX, velocityY);
    }

    /** Slides the window off the side of the screen and closes it. */
    private void swipeAway(float velocityX) {
        dismissing = true;
        spring.cancel();
        final float startX = x;
        final float endX = velocityX < 0 ? -cardWidth - 2f * padding : screenWidth;
        long duration = (long) clamp(Math.abs(endX - startX) / Math.abs(velocityX) * 1000f, 120f, 280f);
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(duration);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();
            x = startX + (endX - startX) * fraction;
            pipView.getCard().setAlpha(1f - 0.6f * fraction);
            applyLayout();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                removeDismissTarget();
                if (!removed) {
                    callbacks.onDismissed();
                }
            }
        });
        animator.start();
    }

    @Override
    public void onPinchStart() {
        if (dismissing) {
            return;
        }
        cancelMotion();
        hideDismissTarget();
        magnetized = false;
        pinchStartArea = areaFraction;
        pinchCenterX = x + padding + cardWidth / 2f;
        pinchCenterY = y + padding + cardHeight / 2f;
        pinchFocusX = Float.NaN;
        pinchFocusY = Float.NaN;
    }

    @Override
    public void onPinch(float scale, float focusRawX, float focusRawY) {
        if (dismissing) {
            return;
        }
        if (Float.isNaN(pinchFocusX)) {
            pinchFocusX = focusRawX;
            pinchFocusY = focusRawY;
        }
        areaFraction = clamp(pinchStartArea * scale * scale, MIN_AREA, MAX_AREA);
        computeCardSize();
        float centerX = pinchCenterX + (focusRawX - pinchFocusX);
        float centerY = pinchCenterY + (focusRawY - pinchFocusY);
        x = centerX - padding - cardWidth / 2f;
        y = centerY - padding - cardHeight / 2f;
        applyLayout();
    }

    @Override
    public void onPinchEnd() {
        if (dismissing) {
            return;
        }
        settle(0, 0);
    }

    @Override
    public void onDoubleTap() {
        if (dismissing) {
            return;
        }
        animateResize(areaFraction < (DEFAULT_AREA + LARGE_AREA) / 2f ? LARGE_AREA : DEFAULT_AREA);
    }

    // ---------------------------------------------------------------- motion

    /** Throws the window to the nearest side with spring physics. */
    private void settle(float velocityX, float velocityY) {
        float projectedCenterX = x + padding + cardWidth / 2f + velocityX * FLING_PROJECTION_SECONDS;
        float targetX = projectedCenterX < screenWidth / 2f ? minX() : maxX();
        float targetY = clamp(y + velocityY * FLING_PROJECTION_SECONDS, minY(), maxY());
        springTo(targetX, targetY, velocityX, velocityY);
    }

    private void springTo(float targetX, float targetY, float velocityX, float velocityY) {
        spring.start(x, y, velocityX, velocityY, targetX, targetY);
    }

    private void cancelMotion() {
        spring.cancel();
        if (resizeAnimator != null) {
            resizeAnimator.cancel();
            resizeAnimator = null;
        }
    }

    private void animateResize(float targetArea) {
        if (!added) {
            areaFraction = targetArea;
            computeCardSize();
            return;
        }
        cancelMotion();
        final boolean left = isOnLeft();
        final float centerY = y + padding + cardHeight / 2f;
        final int startWidth = cardWidth;
        final int startHeight = cardHeight;
        areaFraction = targetArea;
        computeCardSize();
        final int endWidth = cardWidth;
        final int endHeight = cardHeight;
        cardWidth = startWidth;
        cardHeight = startHeight;

        resizeAnimator = ValueAnimator.ofFloat(0f, 1f);
        resizeAnimator.setDuration(260);
        resizeAnimator.setInterpolator(new DecelerateInterpolator(1.5f));
        resizeAnimator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();
            cardWidth = Math.round(startWidth + (endWidth - startWidth) * fraction);
            cardHeight = Math.round(startHeight + (endHeight - startHeight) * fraction);
            x = left ? minX() : maxX();
            y = clamp(centerY - padding - cardHeight / 2f, minY(), maxY());
            applyLayout();
        });
        resizeAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                resizeAnimator = null;
            }
        });
        resizeAnimator.start();
    }

    private void dismiss() {
        if (dismissing) {
            return;
        }
        dismissing = true;
        pipView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (dismissCircle != null) {
            dismissCircle.animate().scaleX(1.5f).scaleY(1.5f).alpha(0f).setDuration(220).start();
        }
        if (dismissView != null) {
            dismissView.animate().alpha(0f).setStartDelay(120).setDuration(200).start();
        }
        pipView.getCard().animate().cancel();
        pipView.getCard().animate().scaleX(0.1f).scaleY(0.1f).alpha(0f).setDuration(220)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    removeDismissTarget();
                    if (!removed) {
                        callbacks.onDismissed();
                    }
                })
                .start();
    }

    // ---------------------------------------------------------------- dismiss target

    private void showDismissTarget() {
        if (dismissAdded) {
            dismissView.animate().cancel();
            dismissView.animate().alpha(1f).setDuration(150).start();
            animateCircle(1f);
            return;
        }
        if (dismissView == null) {
            createDismissView();
        }
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.type = layoutParams.type;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        params.format = PixelFormat.TRANSLUCENT;
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = 0;
        params.y = screenHeight - dismissAreaHeight();
        params.width = screenWidth;
        params.height = dismissAreaHeight();
        // Keeps touches on apps below allowed by Android 12+ untrusted-touch rules.
        params.alpha = 0.8f;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        params.setTitle("scrcpy dismiss target");
        FrameLayout.LayoutParams circleParams = (FrameLayout.LayoutParams) dismissCircle.getLayoutParams();
        circleParams.bottomMargin = bottomInset + dp(32);
        dismissCircle.setLayoutParams(circleParams);
        try {
            windowManager.addView(dismissView, params);
            dismissAdded = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not show the dismiss target", e);
            return;
        }
        dismissView.setAlpha(0f);
        dismissView.animate().alpha(1f).setDuration(180).start();
        dismissCircle.setScaleX(0.6f);
        dismissCircle.setScaleY(0.6f);
        animateCircle(1f);
    }

    private void hideDismissTarget() {
        if (!dismissAdded) {
            return;
        }
        animateCircle(0.6f);
        dismissView.animate().cancel();
        dismissView.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            if (!dismissing) {
                removeDismissTarget();
            }
        }).start();
    }

    private void removeDismissTarget() {
        if (dismissAdded && dismissView != null) {
            dismissView.animate().cancel();
            try {
                windowManager.removeView(dismissView);
            } catch (RuntimeException ignored) {
                // Already removed.
            }
        }
        dismissAdded = false;
    }

    private void createDismissView() {
        dismissView = new FrameLayout(context);
        dismissView.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0x99000000, 0x00000000}));

        FrameLayout circle = new FrameLayout(context);
        GradientDrawable circleBackground = new GradientDrawable();
        circleBackground.setShape(GradientDrawable.OVAL);
        circleBackground.setColor(0x66000000);
        circleBackground.setStroke(dp(2), Color.WHITE);
        circle.setBackground(circleBackground);
        TextView cross = new TextView(context);
        cross.setText("✕");
        cross.setTextColor(Color.WHITE);
        cross.setTextSize(22);
        cross.setGravity(Gravity.CENTER);
        circle.addView(cross, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(56), dp(56),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dismissView.addView(circle, params);
        dismissCircle = circle;
    }

    private void animateCircle(float scale) {
        if (dismissCircle != null) {
            dismissCircle.animate().scaleX(scale).scaleY(scale).alpha(1f)
                    .setInterpolator(new OvershootInterpolator(2f)).setDuration(180).start();
        }
    }

    private int dismissAreaHeight() {
        return bottomInset + dp(170);
    }

    private float targetCenterY() {
        return screenHeight - bottomInset - dp(32) - dp(28);
    }

    private float targetWindowX() {
        return screenWidth / 2f - padding - cardWidth / 2f;
    }

    private float targetWindowY() {
        return targetCenterY() - padding - cardHeight / 2f;
    }

    private float distanceToTarget(float windowX, float windowY) {
        float centerX = windowX + padding + cardWidth / 2f;
        float centerY = windowY + padding + cardHeight / 2f;
        return (float) Math.hypot(centerX - screenWidth / 2f, centerY - targetCenterY());
    }

    // ---------------------------------------------------------------- geometry

    private void computeBounds() {
        DisplayMetrics metrics = new DisplayMetrics();
        realMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        int topInset = systemDimension("status_bar_height", dp(24));
        bottomInset = systemDimension("navigation_bar_height", dp(48));
        int margin = dp(12);
        bounds.set(margin, topInset + margin, screenWidth - margin, screenHeight - bottomInset - margin);
    }

    @SuppressWarnings("deprecation")
    private void realMetrics(DisplayMetrics metrics) {
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
    }

    private int systemDimension(String name, int fallback) {
        Resources resources = context.getResources();
        int id = resources.getIdentifier(name, "dimen", "android");
        if (id != 0) {
            try {
                return resources.getDimensionPixelSize(id);
            } catch (Resources.NotFoundException ignored) {
                // Use the fallback.
            }
        }
        return fallback;
    }

    private void computeCardSize() {
        float screenArea = (float) screenWidth * screenHeight;
        float width = (float) Math.sqrt(areaFraction * screenArea * aspect);
        float height = width / aspect;
        float maxWidth = bounds.width();
        float maxHeight = bounds.height();
        float fit = Math.min(1f, Math.min(maxWidth / width, maxHeight / height));
        width *= fit;
        height *= fit;
        float minSide = dp(96);
        float grow = Math.max(1f, minSide / Math.min(width, height));
        width *= grow;
        height *= grow;
        cardWidth = Math.max(1, Math.round(width));
        cardHeight = Math.max(1, Math.round(height));
    }

    private float minX() {
        return bounds.left - padding;
    }

    private float maxX() {
        return Math.max(minX(), bounds.right - padding - cardWidth);
    }

    private float minY() {
        return bounds.top - padding;
    }

    private float maxY() {
        return Math.max(minY(), bounds.bottom - padding - cardHeight);
    }

    private boolean isOnLeft() {
        return x + padding + cardWidth / 2f < screenWidth / 2f;
    }

    private void applyLayout() {
        if (!added) {
            return;
        }
        fillLayoutParams();
        try {
            windowManager.updateViewLayout(pipView, layoutParams);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not update the floating window", e);
        }
    }

    private void fillLayoutParams() {
        layoutParams.x = Math.round(x);
        layoutParams.y = Math.round(y);
        layoutParams.width = cardWidth + 2 * padding;
        layoutParams.height = cardHeight + 2 * padding;
    }

    @SuppressWarnings("deprecation")
    private static int legacyOverlayType() {
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(float value) {
        return Math.round(value * density);
    }
}
