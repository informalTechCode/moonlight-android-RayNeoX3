package com.limelight.utils;

import java.util.ArrayList;
import java.util.Iterator;

import android.app.Activity;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.limelight.R;

public class SpinnerDialog implements Runnable {
    private final String title;
    private final String message;
    private final Activity activity;
    private View overlayView;
    private TextView overlayMessageView;
    private final boolean finish;

    private static final ArrayList<SpinnerDialog> rundownDialogs = new ArrayList<>();

    private SpinnerDialog(Activity activity, String title, String message, boolean finish)
    {
        this.activity = activity;
        this.title = title;
        this.message = message;
        this.finish = finish;
    }

    public static SpinnerDialog displayDialog(Activity activity, String title, String message, boolean finish)
    {
        SpinnerDialog spinner = new SpinnerDialog(activity, title, message, finish);
        activity.runOnUiThread(spinner);
        return spinner;
    }

    public static void closeDialogs(Activity activity)
    {
        synchronized (rundownDialogs) {
            Iterator<SpinnerDialog> i = rundownDialogs.iterator();
            while (i.hasNext()) {
                SpinnerDialog dialog = i.next();
                if (dialog.activity == activity) {
                    i.remove();
                    if (dialog.overlayView != null) {
                        ViewGroup parent = (ViewGroup) dialog.overlayView.getParent();
                        if (parent != null) {
                            parent.removeView(dialog.overlayView);
                        }
                        dialog.overlayView = null;
                        dialog.overlayMessageView = null;
                    }
                }
            }
        }
    }

    public void dismiss()
    {
        // Running again with overlayView != null will destroy it
        activity.runOnUiThread(this);
    }

    public void setMessage(final String message)
    {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (overlayMessageView != null) {
                    overlayMessageView.setText(message);
                }
            }
        });
    }

    @Override
    public void run() {
        // If we're dying, don't bother doing anything
        if (activity.isFinishing()) {
            return;
        }

        if (overlayView == null) {
            ViewGroup container = activity.findViewById(R.id.leftEyeContainer);
            if (container == null) {
                container = activity.findViewById(android.R.id.content);
            }

            if (container == null) {
                return;
            }

            showOverlay(container);
        }
        else {
            synchronized (rundownDialogs) {
                if (rundownDialogs.remove(this) && overlayView != null) {
                    ViewGroup parent = (ViewGroup) overlayView.getParent();
                    if (parent != null) {
                        parent.removeView(overlayView);
                    }
                    overlayView = null;
                    overlayMessageView = null;
                }
            }
        }
    }

    private void showOverlay(final ViewGroup container) {
        FrameLayout scrim = new FrameLayout(activity);
        scrim.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        scrim.setBackgroundColor(0x88000000);
        scrim.setClickable(true);
        scrim.setFocusable(true);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setBackgroundColor(Color.parseColor("#424242"));
        panel.setPadding(dp(18), dp(14), dp(18), dp(14));

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelParams.gravity = Gravity.CENTER;
        panel.setLayoutParams(panelParams);

        ProgressBar spinner = new ProgressBar(activity);
        panel.addView(spinner);

        LinearLayout textWrap = new LinearLayout(activity);
        textWrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textWrapParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textWrapParams.leftMargin = dp(12);
        textWrap.setLayoutParams(textWrapParams);

        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        textWrap.addView(titleView);

        overlayMessageView = new TextView(activity);
        overlayMessageView.setText(message);
        overlayMessageView.setTextColor(Color.WHITE);
        overlayMessageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        textWrap.addView(overlayMessageView);

        panel.addView(textWrap);
        scrim.addView(panel);

        if (finish) {
            scrim.setOnClickListener(v -> {
                synchronized (rundownDialogs) {
                    rundownDialogs.remove(this);
                }
                ViewGroup parent = (ViewGroup) scrim.getParent();
                if (parent != null) {
                    parent.removeView(scrim);
                }
                overlayView = null;
                overlayMessageView = null;
                activity.finish();
            });
        }

        synchronized (rundownDialogs) {
            overlayView = scrim;
            rundownDialogs.add(this);
            container.addView(scrim);
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, activity.getResources().getDisplayMetrics());
    }
}
