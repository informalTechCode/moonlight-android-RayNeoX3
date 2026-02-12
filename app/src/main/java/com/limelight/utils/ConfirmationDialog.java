package com.limelight.utils;

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

public final class ConfirmationDialog implements Runnable {
    private final Activity activity;
    private final String title;
    private final String message;
    private final String positiveText;
    private final String negativeText;
    private final Runnable onPositive;
    private final Runnable onNegative;

    private ConfirmationDialog(Activity activity, String title, String message,
                               String positiveText, String negativeText,
                               Runnable onPositive, Runnable onNegative) {
        this.activity = activity;
        this.title = title;
        this.message = message;
        this.positiveText = positiveText;
        this.negativeText = negativeText;
        this.onPositive = onPositive;
        this.onNegative = onNegative;
    }

    public static void displayDialog(Activity activity, String title, String message,
                                     String positiveText, String negativeText,
                                     Runnable onPositive, Runnable onNegative) {
        activity.runOnUiThread(new ConfirmationDialog(activity, title, message,
                positiveText, negativeText, onPositive, onNegative));
    }

    @Override
    public void run() {
        if (activity.isFinishing()) {
            return;
        }

        ViewGroup dialogContainer = activity.findViewById(R.id.leftEyeContainer);
        if (dialogContainer == null) {
            dialogContainer = activity.findViewById(android.R.id.content);
        }
        if (dialogContainer == null) {
            return;
        }
        final ViewGroup container = dialogContainer;

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

        if (title != null && !title.isEmpty()) {
            TextView titleView = new TextView(activity);
            titleView.setText(title);
            titleView.setTextColor(Color.WHITE);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            panel.addView(titleView);
        }

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

        Button negativeButton = new Button(activity);
        negativeButton.setText(negativeText);
        negativeButton.setOnClickListener(v -> {
            container.removeView(scrim);
            if (onNegative != null) {
                onNegative.run();
            }
        });
        buttons.addView(negativeButton);

        Button positiveButton = new Button(activity);
        positiveButton.setText(positiveText);
        LinearLayout.LayoutParams posParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        posParams.leftMargin = dp(10);
        positiveButton.setLayoutParams(posParams);
        positiveButton.setOnClickListener(v -> {
            container.removeView(scrim);
            if (onPositive != null) {
                onPositive.run();
            }
        });
        buttons.addView(positiveButton);

        panel.addView(buttons);
        scrim.addView(panel);
        container.addView(scrim);

        positiveButton.requestFocus();
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, activity.getResources().getDisplayMetrics());
    }
}
