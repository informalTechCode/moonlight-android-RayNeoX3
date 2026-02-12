package com.limelight.utils;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import com.limelight.LimeLog;
import com.limelight.R;

public final class StereoMirrorController {
    private static final int EYE_WIDTH_PX = 640;
    private static final int EYE_HEIGHT_PX = 480;
    private static final long FRAME_DELAY_MS = 16;

    private final Activity activity;
    private final FrameLayout stereoRoot;
    private final FrameLayout leftEyeContainer;
    private final SurfaceView rightEyeSurfaceView;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Rect captureRect = new Rect();

    private Bitmap rightEyeBitmap;
    private boolean mirrorActive;
    private boolean copyInProgress;

    private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            ensureBitmap();
            start();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            ensureBitmap();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            stop();
            recycleBitmap();
        }
    };

    private final View.OnLayoutChangeListener layoutChangeListener = new View.OnLayoutChangeListener() {
        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                   int oldLeft, int oldTop, int oldRight, int oldBottom) {
            applyStereoLayout();
        }
    };

    private final Runnable mirrorRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mirrorActive) {
                return;
            }

            mirrorLeftToRight();
            handler.postDelayed(this, FRAME_DELAY_MS);
        }
    };

    private StereoMirrorController(Activity activity, FrameLayout stereoRoot,
                                   FrameLayout leftEyeContainer, SurfaceView rightEyeSurfaceView) {
        this.activity = activity;
        this.stereoRoot = stereoRoot;
        this.leftEyeContainer = leftEyeContainer;
        this.rightEyeSurfaceView = rightEyeSurfaceView;
    }

    public static StereoMirrorController attach(Activity activity) {
        View root = activity.findViewById(R.id.stereoRoot);
        View left = activity.findViewById(R.id.leftEyeContainer);
        View right = activity.findViewById(R.id.rightEyeSurfaceView);

        if (!(root instanceof FrameLayout) || !(left instanceof FrameLayout) || !(right instanceof SurfaceView)) {
            return null;
        }

        StereoMirrorController controller =
                new StereoMirrorController(activity, (FrameLayout) root, (FrameLayout) left, (SurfaceView) right);
        controller.init();
        return controller;
    }

    private void init() {
        rightEyeSurfaceView.setClickable(false);
        rightEyeSurfaceView.setFocusable(false);
        rightEyeSurfaceView.getHolder().setFormat(PixelFormat.RGBA_8888);
        rightEyeSurfaceView.getHolder().addCallback(surfaceCallback);

        stereoRoot.addOnLayoutChangeListener(layoutChangeListener);
        stereoRoot.post(new Runnable() {
            @Override
            public void run() {
                applyStereoLayout();
                start();
            }
        });
    }

    public void start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        if (!rightEyeSurfaceView.getHolder().getSurface().isValid()) {
            return;
        }

        if (mirrorActive) {
            return;
        }

        mirrorActive = true;
        copyInProgress = false;
        handler.removeCallbacks(mirrorRunnable);
        handler.post(mirrorRunnable);
    }

    public void stop() {
        mirrorActive = false;
        copyInProgress = false;
        handler.removeCallbacks(mirrorRunnable);
    }

    public void release() {
        stop();
        stereoRoot.removeOnLayoutChangeListener(layoutChangeListener);
        rightEyeSurfaceView.getHolder().removeCallback(surfaceCallback);
        recycleBitmap();
    }

    private void applyStereoLayout() {
        int totalStereoWidth = EYE_WIDTH_PX * 2;
        int leftMargin = Math.max(0, (stereoRoot.getWidth() - totalStereoWidth) / 2);
        int topMargin = Math.max(0, (stereoRoot.getHeight() - EYE_HEIGHT_PX) / 2);

        FrameLayout.LayoutParams leftParams = (FrameLayout.LayoutParams) leftEyeContainer.getLayoutParams();
        leftParams.width = EYE_WIDTH_PX;
        leftParams.height = EYE_HEIGHT_PX;
        leftParams.leftMargin = leftMargin;
        leftParams.topMargin = topMargin;
        leftEyeContainer.setLayoutParams(leftParams);

        FrameLayout.LayoutParams rightParams = (FrameLayout.LayoutParams) rightEyeSurfaceView.getLayoutParams();
        rightParams.width = EYE_WIDTH_PX;
        rightParams.height = EYE_HEIGHT_PX;
        rightParams.leftMargin = leftMargin + EYE_WIDTH_PX;
        rightParams.topMargin = topMargin;
        rightEyeSurfaceView.setLayoutParams(rightParams);
    }

    private void ensureBitmap() {
        int width = rightEyeSurfaceView.getWidth() > 0 ? rightEyeSurfaceView.getWidth() : EYE_WIDTH_PX;
        int height = rightEyeSurfaceView.getHeight() > 0 ? rightEyeSurfaceView.getHeight() : EYE_HEIGHT_PX;

        if (rightEyeBitmap != null &&
                !rightEyeBitmap.isRecycled() &&
                rightEyeBitmap.getWidth() == width &&
                rightEyeBitmap.getHeight() == height) {
            return;
        }

        recycleBitmap();
        rightEyeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }

    private void recycleBitmap() {
        if (rightEyeBitmap != null && !rightEyeBitmap.isRecycled()) {
            rightEyeBitmap.recycle();
        }
        rightEyeBitmap = null;
    }

    private void mirrorLeftToRight() {
        if (!mirrorActive || copyInProgress) {
            return;
        }

        if (!rightEyeSurfaceView.getHolder().getSurface().isValid()) {
            return;
        }

        int[] location = new int[2];
        leftEyeContainer.getLocationInWindow(location);
        captureRect.set(
                location[0],
                location[1],
                location[0] + leftEyeContainer.getWidth(),
                location[1] + leftEyeContainer.getHeight()
        );

        if (captureRect.isEmpty()) {
            return;
        }

        ensureBitmap();
        if (rightEyeBitmap == null || rightEyeBitmap.isRecycled()) {
            return;
        }

        copyInProgress = true;
        final Bitmap targetBitmap = rightEyeBitmap;
        try {
            PixelCopy.request(activity.getWindow(), captureRect, targetBitmap, copyResult -> {
                try {
                    if (copyResult != PixelCopy.SUCCESS || !mirrorActive) {
                        return;
                    }

                    Canvas canvas = null;
                    try {
                        canvas = rightEyeSurfaceView.getHolder().lockCanvas();
                        if (canvas != null) {
                            canvas.drawBitmap(targetBitmap, 0f, 0f, null);
                        }
                    } finally {
                        if (canvas != null) {
                            rightEyeSurfaceView.getHolder().unlockCanvasAndPost(canvas);
                        }
                    }
                } finally {
                    copyInProgress = false;
                }
            }, handler);
        } catch (IllegalArgumentException e) {
            copyInProgress = false;
            LimeLog.warning("Stereo PixelCopy failed: " + e.getMessage());
        }
    }
}

