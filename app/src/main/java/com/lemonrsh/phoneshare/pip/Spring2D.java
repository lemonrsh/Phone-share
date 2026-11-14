package com.lemonrsh.phoneshare.pip;

import android.view.Choreographer;

/**
 * A tiny critically-tunable 2D spring driven by {@link Choreographer}, used to
 * throw and snap the floating window (the physics YouTube's mini player uses).
 * Must be used from the main thread.
 */
final class Spring2D implements Choreographer.FrameCallback {
    interface Target {
        void onSpringUpdate(float x, float y);

        void onSpringEnd();
    }

    private static final float STEP_SECONDS = 1f / 240f;
    private static final float MAX_FRAME_SECONDS = 1f / 20f;

    private final Target target;
    private final float stiffness;
    private final float damping;
    private final float restDistance;
    private final float restVelocity;

    private float x;
    private float y;
    private float velocityX;
    private float velocityY;
    private float finalX;
    private float finalY;
    private long lastFrameNanos;
    private boolean running;

    /**
     * @param stiffness    spring constant (unit mass)
     * @param dampingRatio 1 = critically damped, below 1 = slight bounce
     * @param restDistance distance in px below which the spring settles
     */
    Spring2D(Target target, float stiffness, float dampingRatio, float restDistance) {
        this.target = target;
        this.stiffness = stiffness;
        this.damping = (float) (2 * dampingRatio * Math.sqrt(stiffness));
        this.restDistance = restDistance;
        this.restVelocity = restDistance * 20;
    }

    void start(float fromX, float fromY, float velocityX, float velocityY, float toX, float toY) {
        x = fromX;
        y = fromY;
        this.velocityX = velocityX;
        this.velocityY = velocityY;
        finalX = toX;
        finalY = toY;
        if (!running) {
            running = true;
            lastFrameNanos = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    /** Moves the destination while keeping the current position and velocity. */
    void retarget(float toX, float toY) {
        finalX = toX;
        finalY = toY;
    }

    boolean isRunning() {
        return running;
    }

    void cancel() {
        if (running) {
            running = false;
            Choreographer.getInstance().removeFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) {
            return;
        }
        float elapsed = lastFrameNanos == 0 ? 1f / 60f : (frameTimeNanos - lastFrameNanos) / 1e9f;
        lastFrameNanos = frameTimeNanos;
        elapsed = Math.max(0f, Math.min(elapsed, MAX_FRAME_SECONDS));

        // Semi-implicit Euler in small fixed steps: stable and frame-rate independent.
        while (elapsed > 0f) {
            float dt = Math.min(STEP_SECONDS, elapsed);
            elapsed -= dt;
            velocityX += (-stiffness * (x - finalX) - damping * velocityX) * dt;
            velocityY += (-stiffness * (y - finalY) - damping * velocityY) * dt;
            x += velocityX * dt;
            y += velocityY * dt;
        }

        boolean atRest = Math.abs(x - finalX) < restDistance && Math.abs(y - finalY) < restDistance
                && Math.abs(velocityX) < restVelocity && Math.abs(velocityY) < restVelocity;
        if (atRest) {
            x = finalX;
            y = finalY;
            running = false;
            target.onSpringUpdate(x, y);
            target.onSpringEnd();
            return;
        }
        target.onSpringUpdate(x, y);
        Choreographer.getInstance().postFrameCallback(this);
    }
}
