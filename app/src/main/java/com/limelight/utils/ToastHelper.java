package com.limelight.utils;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import com.limelight.R;
public final class ToastHelper {
    public static final int LENGTH_SHORT = Toast.LENGTH_SHORT;
    public static final int LENGTH_LONG = Toast.LENGTH_LONG;
    private ToastHelper() {
    }
    public static void show(Context context, int resId, int duration) {
        if (context == null) {
            return;
        }
        show(context, context.getText(resId), duration);
    }
    public static void show(Context context, CharSequence message, int duration) {
        if (context == null || message == null) {
            return;
        }
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> {
            Context appContext = context.getApplicationContext();
            LayoutInflater inflater = LayoutInflater.from(context);
            View toastView = inflater.inflate(R.layout.view_custom_toast, null);
            TextView textView = toastView.findViewById(R.id.customToastText);
            textView.setText(message);
            Toast toast = new Toast(appContext);
            toast.setDuration(duration);
            toast.setView(toastView);
            toast.show();
        });
    }
}
