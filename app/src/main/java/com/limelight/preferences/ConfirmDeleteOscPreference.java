package com.limelight.preferences;

import com.limelight.utils.ToastHelper;
import com.limelight.utils.ConfirmationDialog;
import android.app.Activity;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;
import android.preference.DialogPreference;
import android.util.AttributeSet;

import com.limelight.R;

import static com.limelight.binding.input.virtual_controller.VirtualControllerConfigurationLoader.OSC_PREFERENCE;

public class ConfirmDeleteOscPreference extends DialogPreference {
    public ConfirmDeleteOscPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public ConfirmDeleteOscPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ConfirmDeleteOscPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ConfirmDeleteOscPreference(Context context) {
        super(context);
    }

    public void onClick(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON_POSITIVE) {
            getContext().getSharedPreferences(OSC_PREFERENCE, Context.MODE_PRIVATE).edit().clear().apply();
            ToastHelper.show(getContext(), R.string.toast_reset_osc_success, ToastHelper.LENGTH_SHORT);
        }
    }

    @Override
    public void showDialog(Bundle state) {
        if (!(getContext() instanceof Activity)) {
            return;
        }

        Activity activity = (Activity) getContext();
        CharSequence title = getDialogTitle();
        CharSequence message = getDialogMessage();
        CharSequence positive = getPositiveButtonText();
        CharSequence negative = getNegativeButtonText();

        ConfirmationDialog.displayDialog(
                activity,
                title != null ? title.toString() : "",
                message != null ? message.toString() : "",
                positive != null ? positive.toString() : activity.getString(android.R.string.ok),
                negative != null ? negative.toString() : activity.getString(android.R.string.cancel),
                () -> onClick(null, DialogInterface.BUTTON_POSITIVE),
                () -> onClick(null, DialogInterface.BUTTON_NEGATIVE));
    }
}

