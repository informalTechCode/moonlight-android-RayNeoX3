package com.limelight.utils;

import java.util.ArrayList;

import android.app.Activity;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;

public class Dialog implements Runnable {
    private final String title;
    private final String message;
    private final Activity activity;
    private final Runnable runOnDismiss;

    private View overlayView;

    private static final ArrayList<Dialog> rundownDialogs = new ArrayList<>();

    private Dialog(Activity activity, String title, String message, Runnable runOnDismiss)
    {
        this.activity = activity;
        this.title = title;
        this.message = message;
        this.runOnDismiss = runOnDismiss;
    }

    public static void closeDialogs()
    {
        synchronized (rundownDialogs) {
            for (Dialog d : rundownDialogs) {
                if (d.overlayView != null) {
                    ViewGroup parent = (ViewGroup) d.overlayView.getParent();
                    if (parent != null) {
                        parent.removeView(d.overlayView);
                    }
                }
            }

            rundownDialogs.clear();
        }
    }

    public static void displayDialog(final Activity activity, String title, String message, final boolean endAfterDismiss)
    {
        activity.runOnUiThread(new Dialog(activity, title, message, new Runnable() {
            @Override
            public void run() {
                if (endAfterDismiss) {
                    activity.finish();
                }
            }
        }));
    }

    public static void displayDialog(Activity activity, String title, String message, Runnable runOnDismiss)
    {
        activity.runOnUiThread(new Dialog(activity, title, message, runOnDismiss));
    }

    @Override
    public void run() {
        // If we're dying, don't bother creating a dialog
        if (activity.isFinishing()) {
            return;
        }

        ViewGroup container = activity.findViewById(R.id.leftEyeContainer);
        if (container == null) {
            container = activity.findViewById(android.R.id.content);
        }

        if (container != null) {
            showOverlay(container);
        }
    }

    private void showOverlay(final ViewGroup container) {
        FrameLayout scrim = new FrameLayout(activity);
        scrim.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        scrim.setBackgroundColor(0xB0000000);
        scrim.setClickable(true);
        scrim.setFocusable(true);

        int panelWidth = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 560,
                activity.getResources().getDisplayMetrics());

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.parseColor("#424242"));
        panel.setPadding(dp(20), dp(16), dp(20), dp(12));
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelParams.gravity = Gravity.CENTER;
        panel.setLayoutParams(panelParams);

        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        panel.addView(titleView);

        TextView messageView = new TextView(activity);
        messageView.setText(message);
        messageView.setTextColor(Color.WHITE);
        messageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams msgParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        msgParams.topMargin = dp(10);
        messageView.setLayoutParams(msgParams);
        panel.addView(messageView);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonsParams.topMargin = dp(14);
        buttons.setLayoutParams(buttonsParams);

        Button helpButton = new Button(activity);
        helpButton.setText(activity.getResources().getText(R.string.help));
        helpButton.setOnClickListener(v -> {
            dismissOverlay(container);
            runOnDismiss.run();
            HelpLauncher.launchTroubleshooting(activity);
        });
        buttons.addView(helpButton);

        Button okButton = new Button(activity);
        okButton.setText(activity.getResources().getText(android.R.string.ok));
        okButton.setOnClickListener(v -> {
            dismissOverlay(container);
            runOnDismiss.run();
        });
        LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        okParams.leftMargin = dp(10);
        okButton.setLayoutParams(okParams);
        buttons.addView(okButton);

        panel.addView(buttons);
        scrim.addView(panel);

        synchronized (rundownDialogs) {
            overlayView = scrim;
            rundownDialogs.add(this);
            container.addView(scrim);
        }

        okButton.requestFocus();
    }

    private void dismissOverlay(ViewGroup container) {
        synchronized (rundownDialogs) {
            rundownDialogs.remove(this);
            if (overlayView != null) {
                container.removeView(overlayView);
                overlayView = null;
            }
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, activity.getResources().getDisplayMetrics());
    }

}
