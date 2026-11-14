package org.las2mile.scrcpy;

import android.content.Context;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

public class DisplayWindow extends FrameLayout {
    private static final String TAG = "DisplayWindow";
    OnClickListener closeListener;
    OnMoveCallback moveCallback;
    OnActionCallback actionCallback;
    OnTouchListener onDisplayTouchListener;

    private float oldX;
    private float oldY;

    ViewGroup header;
    ViewGroup container;
    SurfaceView surfaceView;
    ViewGroup actionbar;
    private boolean controlsEnabled = true;

    public DisplayWindow(Context context) {
        super(context);
        init();
    }

    public DisplayWindow(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DisplayWindow(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init(){
        setClipChildren(false);
        setClipToPadding(false);
        LayoutInflater.from(getContext()).inflate(R.layout.window_display,this,true);

        container = findViewById(R.id.container);
        surfaceView = findViewById(R.id.surface);
        actionbar = findViewById(R.id.actionbar);

        findViewById(R.id.iv_close).setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                if (motionEvent.getActionMasked() == MotionEvent.ACTION_DOWN && closeListener != null) {
                    closeListener.onClick(view);
                }
                return true;
            }
        });

        header = findViewById(R.id.header);
        header.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                final int action = motionEvent.getAction();
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        oldX = motionEvent.getRawX();
                        oldY = motionEvent.getRawY();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float disX = motionEvent.getRawX()-oldX;
                        float disY = motionEvent.getRawY()-oldY;
                        if (moveCallback != null) {
                        moveCallback.onMove(disX, disY);
                    }
                        oldX = motionEvent.getRawX();
                        oldY = motionEvent.getRawY();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:

                        break;
                }
                return true;
            }
        });
        findViewById(R.id.iv_mini).setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                if (motionEvent.getActionMasked() == MotionEvent.ACTION_DOWN){
                    if (container.isShown()){
                        container.setVisibility(View.GONE);
                        actionbar.setVisibility(View.GONE);
                    }else{
                        container.setVisibility(View.VISIBLE);
                        actionbar.setVisibility(controlsEnabled ? View.VISIBLE : View.GONE);
                    }
                }
                return false;
            }
        });
        findViewById(R.id.action_back).setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                if (motionEvent.getActionMasked() == MotionEvent.ACTION_DOWN){
                    if (actionCallback != null) {
                        actionCallback.onAction(0);
                    }
                }
                return false;
            }
        });
        findViewById(R.id.action_home).setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                if (motionEvent.getActionMasked() == MotionEvent.ACTION_DOWN){
                    if (actionCallback != null) {
                        actionCallback.onAction(1);
                    }
                }
                return false;
            }
        });
        findViewById(R.id.action_menu).setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                if (motionEvent.getActionMasked() == MotionEvent.ACTION_DOWN){
                    if (actionCallback != null) {
                        actionCallback.onAction(2);
                    }
                }
                return false;
            }
        });
        container.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                return onDisplayTouchListener != null && onDisplayTouchListener.onTouch(view, motionEvent);
            }
        });
    }

    public void setCloseListener(OnClickListener closeListener) {
        this.closeListener = closeListener;
    }

    public void setMoveCallback(OnMoveCallback moveCallback) {
        this.moveCallback = moveCallback;
    }

    public void setActionCallback(OnActionCallback actionCallback) {
        this.actionCallback = actionCallback;
    }

    public void setOnDisplayTouchListener(OnTouchListener onDisplayTouchListener) {
        this.onDisplayTouchListener = onDisplayTouchListener;
    }

    public SurfaceView getSurfaceView() {
        return surfaceView;
    }

    /** Sizes the video area to the remote aspect ratio, within 90% x 85% of this screen. */
    public void setRemote(final int w, final int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        post(new Runnable() {
            @Override
            public void run() {
                DisplayMetrics metrics = getResources().getDisplayMetrics();
                float maxWidth = metrics.widthPixels * 0.9f;
                float maxHeight = metrics.heightPixels * 0.85f - actionbar.getMeasuredHeight() - header.getMeasuredHeight();
                if (maxWidth <= 0 || maxHeight <= 0) {
                    return;
                }
                float scale = Math.min(maxWidth / w, maxHeight / h);
                int width = Math.max(1, Math.round(w * scale));
                int height = Math.max(1, Math.round(h * scale));
                Log.d(TAG, "setRemote: " + w + "x" + h + " -> " + width + "x" + height);

                ViewGroup.LayoutParams lp = container.getLayoutParams();
                if (lp.width != width || lp.height != height) {
                    lp.width = width;
                    lp.height = height;
                    container.setLayoutParams(lp);
                }
                ViewGroup.LayoutParams headerParams = header.getLayoutParams();
                if (headerParams.width != width) {
                    headerParams.width = width;
                    header.setLayoutParams(headerParams);
                }
                requestLayout();
            }
        });
    }

    public void hideHintTip() {
        findViewById(R.id.hint).setVisibility(GONE);
    }

    public void showHint(String text) {
        TextView hint = findViewById(R.id.hint);
        hint.setText(text);
        hint.setVisibility(VISIBLE);
    }

    public void setControlEnabled(boolean enabled) {
        controlsEnabled = enabled;
        actionbar.setVisibility(enabled ? View.VISIBLE : View.GONE);
    }

    public Surface getDisplaySurface(){
        return surfaceView.getHolder().getSurface();
    }

    public int getSurfaceWidth(){
        return container.getMeasuredWidth();
    }

    public int getSurfaceHeight(){
        return container.getMeasuredHeight();
    }

    public interface OnMoveCallback {
        void onMove(float x,float y);
    }
    public interface OnActionCallback{
        void onAction(int actionType);
    }
}
