package com.limelight;

import com.limelight.utils.ToastHelper;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.List;

import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.AppGridAdapter;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.AdapterFragment;
import com.limelight.ui.AdapterFragmentCallbacks;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.Dialog;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.UiHelper;

import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.PixelCopy;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.util.TypedValue;

import org.xmlpull.v1.XmlPullParserException;
import java.util.ArrayList;

public class AppView extends Activity implements AdapterFragmentCallbacks {
    private AppGridAdapter appGridAdapter;
    private String uuidString;
    private ShortcutHelper shortcutHelper;

    private ComputerDetails computer;
    private ComputerManagerService.ApplistPoller poller;
    private SpinnerDialog blockingLoadSpinner;
    private String lastRawApplist;
    private int lastRunningAppId;
    private boolean suspendGridUpdates;
    private boolean inForeground;
    private boolean showHiddenApps;
    private HashSet<Integer> hiddenAppIds = new HashSet<>();

    private final static int START_OR_RESUME_ID = 1;
    private final static int QUIT_ID = 2;
    private final static int START_WITH_QUIT = 4;
    private final static int VIEW_DETAILS_ID = 5;
    private final static int CREATE_SHORTCUT_ID = 6;
    private final static int HIDE_APP_ID = 7;
    private static final int EYE_WIDTH_PX = 640;
    private static final int EYE_HEIGHT_PX = 480;
    private static final long RIGHT_EYE_MIRROR_FRAME_DELAY_MS = 16;
    private View appMenuOverlay;

    public final static String HIDDEN_APPS_PREF_FILENAME = "HiddenApps";

    public final static String NAME_EXTRA = "Name";
    public final static String UUID_EXTRA = "UUID";
    public final static String NEW_PAIR_EXTRA = "NewPair";
    public final static String SHOW_HIDDEN_APPS_EXTRA = "ShowHiddenApps";

    private FrameLayout stereoRoot;
    private FrameLayout leftEyeContainer;
    private SurfaceView rightEyeSurfaceView;
    private final Handler rightEyeMirrorHandler = new Handler(Looper.getMainLooper());
    private final Rect leftEyeCaptureRect = new Rect();
    private Bitmap rightEyeBitmap;
    private boolean rightEyeMirrorActive;
    private boolean rightEyeCopyInProgress;
    private final Runnable rightEyeMirrorRunnable = new Runnable() {
        @Override
        public void run() {
            if (!rightEyeMirrorActive) {
                return;
            }

            mirrorLeftEyeToRightEye();
            rightEyeMirrorHandler.postDelayed(this, RIGHT_EYE_MIRROR_FRAME_DELAY_MS);
        }
    };

    private static final class AppMenuItem {
        final int id;
        final String label;
        final boolean checked;

        AppMenuItem(int id, String label, boolean checked) {
            this.id = id;
            this.label = label;
            this.checked = checked;
        }
    };

    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder)binder);

            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    // Wait for the binder to be ready
                    localBinder.waitForReady();

                    // Get the computer object
                    computer = localBinder.getComputer(uuidString);
                    if (computer == null) {
                        finish();
                        return;
                    }

                    // Add a launcher shortcut for this PC (forced, since this is user interaction)
                    shortcutHelper.createAppViewShortcut(computer, true, getIntent().getBooleanExtra(NEW_PAIR_EXTRA, false));
                    shortcutHelper.reportComputerShortcutUsed(computer);

                    try {
                        appGridAdapter = new AppGridAdapter(AppView.this,
                                PreferenceConfiguration.readPreferences(AppView.this),
                                computer, localBinder.getUniqueId(),
                                showHiddenApps);
                    } catch (Exception e) {
                        e.printStackTrace();
                        finish();
                        return;
                    }

                    appGridAdapter.updateHiddenApps(hiddenAppIds, true);

                    // Now make the binder visible. We must do this after appGridAdapter
                    // is set to prevent us from reaching updateUiWithServerinfo() and
                    // touching the appGridAdapter prior to initialization.
                    managerBinder = localBinder;

                    // Load the app grid with cached data (if possible).
                    // This must be done _before_ startComputerUpdates()
                    // so the initial serverinfo response can update the running
                    // icon.
                    populateAppGridWithCache();

                    // Start updates
                    startComputerUpdates();

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isChangingConfigurations()) {
                                return;
                            }

                            // Despite my best efforts to catch all conditions that could
                            // cause the activity to be destroyed when we try to commit
                            // I haven't been able to, so we have this try-catch block.
                            try {
                                getFragmentManager().beginTransaction()
                                        .replace(R.id.appFragmentContainer, new AdapterFragment())
                                        .commitAllowingStateLoss();
                            } catch (IllegalStateException e) {
                                e.printStackTrace();
                            }
                        }
                    });
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // If appGridAdapter is initialized, let it know about the configuration change.
        // If not, it will pick it up when it initializes.
        if (appGridAdapter != null) {
            // Update the app grid adapter to create grid items with the correct layout
            appGridAdapter.updateLayoutWithPreferences(this, PreferenceConfiguration.readPreferences(this));

            try {
                // Reinflate the app grid itself to pick up the layout change
                getFragmentManager().beginTransaction()
                        .replace(R.id.appFragmentContainer, new AdapterFragment())
                        .commitAllowingStateLoss();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }
        }
    }

    private void startComputerUpdates() {
        // Don't start polling if we're not bound or in the foreground
        if (managerBinder == null || !inForeground) {
            return;
        }

        managerBinder.startPolling(new ComputerManagerListener() {
            @Override
            public void notifyComputerUpdated(final ComputerDetails details) {
                // Do nothing if updates are suspended
                if (suspendGridUpdates) {
                    return;
                }

                // Don't care about other computers
                if (!details.uuid.equalsIgnoreCase(uuidString)) {
                    return;
                }

                if (details.state == ComputerDetails.State.OFFLINE) {
                    // The PC is unreachable now
                    AppView.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Display a toast to the user and quit the activity
                            ToastHelper.show(AppView.this, getResources().getText(R.string.lost_connection), ToastHelper.LENGTH_SHORT);
                            finish();
                        }
                    });

                    return;
                }

                // Close immediately if the PC is no longer paired
                if (details.state == ComputerDetails.State.ONLINE && details.pairState != PairingManager.PairState.PAIRED) {
                    AppView.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Disable shortcuts referencing this PC for now
                            shortcutHelper.disableComputerShortcut(details,
                                    getResources().getString(R.string.scut_not_paired));

                            // Display a toast to the user and quit the activity
                            ToastHelper.show(AppView.this, getResources().getText(R.string.scut_not_paired), ToastHelper.LENGTH_SHORT);
                            finish();
                        }
                    });

                    return;
                }

                // App list is the same or empty
                if (details.rawAppList == null || details.rawAppList.equals(lastRawApplist)) {

                    // Let's check if the running app ID changed
                    if (details.runningGameId != lastRunningAppId) {
                        // Update the currently running game using the app ID
                        lastRunningAppId = details.runningGameId;
                        updateUiWithServerinfo(details);
                    }

                    return;
                }

                lastRunningAppId = details.runningGameId;
                lastRawApplist = details.rawAppList;

                try {
                    updateUiWithAppList(NvHTTP.getAppListByReader(new StringReader(details.rawAppList)));
                    updateUiWithServerinfo(details);

                    if (blockingLoadSpinner != null) {
                        blockingLoadSpinner.dismiss();
                        blockingLoadSpinner = null;
                    }
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                }
            }
        });

        if (poller == null) {
            poller = managerBinder.createAppListPoller(computer);
        }
        poller.start();
    }

    private void stopComputerUpdates() {
        if (poller != null) {
            poller.stop();
        }

        if (managerBinder != null) {
            managerBinder.stopPolling();
        }

        if (appGridAdapter != null) {
            appGridAdapter.cancelQueuedOperations();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        setContentView(R.layout.activity_app_view);
        setupStereoMirroring();

        // Allow floating expanded PiP overlays while browsing apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        UiHelper.notifyNewRootView(this);

        showHiddenApps = getIntent().getBooleanExtra(SHOW_HIDDEN_APPS_EXTRA, false);
        uuidString = getIntent().getStringExtra(UUID_EXTRA);

        SharedPreferences hiddenAppsPrefs = getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE);
        for (String hiddenAppIdStr : hiddenAppsPrefs.getStringSet(uuidString, new HashSet<String>())) {
            hiddenAppIds.add(Integer.parseInt(hiddenAppIdStr));
        }

        String computerName = getIntent().getStringExtra(NAME_EXTRA);

        TextView label = findViewById(R.id.appListText);
        setTitle(computerName);
        label.setText(computerName);

        // Bind to the computer manager service
        bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);
    }

    private void setupStereoMirroring() {
        stereoRoot = findViewById(R.id.stereoRoot);
        leftEyeContainer = findViewById(R.id.leftEyeContainer);
        rightEyeSurfaceView = findViewById(R.id.rightEyeSurfaceView);

        if (stereoRoot == null || leftEyeContainer == null || rightEyeSurfaceView == null) {
            return;
        }

        rightEyeSurfaceView.setClickable(false);
        rightEyeSurfaceView.setFocusable(false);
        rightEyeSurfaceView.getHolder().setFormat(PixelFormat.RGBA_8888);
        rightEyeSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                ensureRightEyeBitmap();
                startRightEyeMirroring();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                ensureRightEyeBitmap();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                stopRightEyeMirroring();
                recycleRightEyeBitmap();
            }
        });

        stereoRoot.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                applyStereoLayout();
            }
        });

        stereoRoot.post(new Runnable() {
            @Override
            public void run() {
                applyStereoLayout();
                startRightEyeMirroring();
            }
        });
    }

    private void applyStereoLayout() {
        if (stereoRoot == null || leftEyeContainer == null || rightEyeSurfaceView == null) {
            return;
        }

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

    private void ensureRightEyeBitmap() {
        if (rightEyeSurfaceView == null) {
            return;
        }

        int width = rightEyeSurfaceView.getWidth() > 0 ? rightEyeSurfaceView.getWidth() : EYE_WIDTH_PX;
        int height = rightEyeSurfaceView.getHeight() > 0 ? rightEyeSurfaceView.getHeight() : EYE_HEIGHT_PX;

        if (rightEyeBitmap != null &&
                !rightEyeBitmap.isRecycled() &&
                rightEyeBitmap.getWidth() == width &&
                rightEyeBitmap.getHeight() == height) {
            return;
        }

        recycleRightEyeBitmap();
        rightEyeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }

    private void startRightEyeMirroring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        if (rightEyeSurfaceView == null || !rightEyeSurfaceView.getHolder().getSurface().isValid()) {
            return;
        }

        if (rightEyeMirrorActive) {
            return;
        }

        rightEyeMirrorActive = true;
        rightEyeCopyInProgress = false;
        rightEyeMirrorHandler.removeCallbacks(rightEyeMirrorRunnable);
        rightEyeMirrorHandler.post(rightEyeMirrorRunnable);
    }

    private void stopRightEyeMirroring() {
        rightEyeMirrorActive = false;
        rightEyeCopyInProgress = false;
        rightEyeMirrorHandler.removeCallbacks(rightEyeMirrorRunnable);
    }

    private void recycleRightEyeBitmap() {
        if (rightEyeBitmap != null && !rightEyeBitmap.isRecycled()) {
            rightEyeBitmap.recycle();
        }
        rightEyeBitmap = null;
    }

    private void mirrorLeftEyeToRightEye() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        if (!rightEyeMirrorActive || rightEyeCopyInProgress) {
            return;
        }

        if (leftEyeContainer == null || rightEyeSurfaceView == null) {
            return;
        }

        if (!rightEyeSurfaceView.getHolder().getSurface().isValid()) {
            return;
        }

        int[] location = new int[2];
        leftEyeContainer.getLocationInWindow(location);
        leftEyeCaptureRect.set(
                location[0],
                location[1],
                location[0] + leftEyeContainer.getWidth(),
                location[1] + leftEyeContainer.getHeight()
        );

        if (leftEyeCaptureRect.isEmpty()) {
            return;
        }

        ensureRightEyeBitmap();
        if (rightEyeBitmap == null || rightEyeBitmap.isRecycled()) {
            return;
        }

        rightEyeCopyInProgress = true;
        final Bitmap targetBitmap = rightEyeBitmap;
        try {
            PixelCopy.request(getWindow(), leftEyeCaptureRect, targetBitmap, copyResult -> {
                try {
                    if (copyResult != PixelCopy.SUCCESS || !rightEyeMirrorActive) {
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
                    rightEyeCopyInProgress = false;
                }
            }, rightEyeMirrorHandler);
        } catch (IllegalArgumentException e) {
            rightEyeCopyInProgress = false;
            LimeLog.warning("AppView PixelCopy failed: " + e.getMessage());
        }
    }

    private void updateHiddenApps(boolean hideImmediately) {
        HashSet<String> hiddenAppIdStringSet = new HashSet<>();

        for (Integer hiddenAppId : hiddenAppIds) {
            hiddenAppIdStringSet.add(hiddenAppId.toString());
        }

        getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .putStringSet(uuidString, hiddenAppIdStringSet)
                .apply();

        appGridAdapter.updateHiddenApps(hiddenAppIds, hideImmediately);
    }

    private void populateAppGridWithCache() {
        try {
            // Try to load from cache
            lastRawApplist = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(getCacheDir(), "applist", uuidString));
            List<NvApp> applist = NvHTTP.getAppListByReader(new StringReader(lastRawApplist));
            updateUiWithAppList(applist);
            LimeLog.info("Loaded applist from cache");
        } catch (IOException | XmlPullParserException e) {
            if (lastRawApplist != null) {
                LimeLog.warning("Saved applist corrupted: "+lastRawApplist);
                e.printStackTrace();
            }
            LimeLog.info("Loading applist from the network");
            // We'll need to load from the network
            loadAppsBlocking();
        }
    }

    private void loadAppsBlocking() {
        blockingLoadSpinner = SpinnerDialog.displayDialog(this, getResources().getString(R.string.applist_refresh_title),
                getResources().getString(R.string.applist_refresh_msg), true);
    }

    @Override
    protected void onDestroy() {
        stopRightEyeMirroring();
        recycleRightEyeBitmap();

        super.onDestroy();

        SpinnerDialog.closeDialogs(this);
        Dialog.closeDialogs();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        startRightEyeMirroring();

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        inForeground = true;
        startComputerUpdates();
    }

    @Override
    protected void onPause() {
        dismissAppMenuOverlay();
        stopRightEyeMirroring();

        super.onPause();

        inForeground = false;
        stopComputerUpdates();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        AppObject selectedApp = (AppObject) appGridAdapter.getItem(info.position);

        menu.setHeaderTitle(selectedApp.app.getAppName());

        if (lastRunningAppId != 0) {
            if (lastRunningAppId == selectedApp.app.getAppId()) {
                menu.add(Menu.NONE, START_OR_RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }
            else {
                menu.add(Menu.NONE, START_WITH_QUIT, 1, getResources().getString(R.string.applist_menu_quit_and_start));
            }
        }

        // Only show the hide checkbox if this is not the currently running app or it's already hidden
        if (lastRunningAppId != selectedApp.app.getAppId() || selectedApp.isHidden) {
            MenuItem hideAppItem = menu.add(Menu.NONE, HIDE_APP_ID, 3, getResources().getString(R.string.applist_menu_hide_app));
            hideAppItem.setCheckable(true);
            hideAppItem.setChecked(selectedApp.isHidden);
        }

        menu.add(Menu.NONE, VIEW_DETAILS_ID, 4, getResources().getString(R.string.applist_menu_details));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Only add an option to create shortcut if box art is loaded
            // and when we're in grid-mode (not list-mode).
            ImageView appImageView = info.targetView.findViewById(R.id.grid_image);
            if (appImageView != null) {
                // We have a grid ImageView, so we must be in grid-mode
                BitmapDrawable drawable = (BitmapDrawable)appImageView.getDrawable();
                if (drawable != null && drawable.getBitmap() != null) {
                    // We have a bitmap loaded too
                    menu.add(Menu.NONE, CREATE_SHORTCUT_ID, 5, getResources().getString(R.string.applist_menu_scut));
                }
            }
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        if (!(item.getMenuInfo() instanceof AdapterContextMenuInfo)) {
            return super.onContextItemSelected(item);
        }

        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        final AppObject app = (AppObject) appGridAdapter.getItem(info.position);
        return onAppMenuItemSelected(item.getItemId(), app, info.targetView, item.isChecked());
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private void dismissAppMenuOverlay() {
        if (appMenuOverlay != null) {
            ViewGroup parent = (ViewGroup) appMenuOverlay.getParent();
            if (parent != null) {
                parent.removeView(appMenuOverlay);
            }
            appMenuOverlay = null;
        }
    }

    private ArrayList<AppMenuItem> buildAppMenuItems(AppObject app, View targetView) {
        ArrayList<AppMenuItem> items = new ArrayList<>();

        if (lastRunningAppId != 0) {
            if (lastRunningAppId == app.app.getAppId()) {
                items.add(new AppMenuItem(START_OR_RESUME_ID, getResources().getString(R.string.applist_menu_resume), false));
                items.add(new AppMenuItem(QUIT_ID, getResources().getString(R.string.applist_menu_quit), false));
            }
            else {
                items.add(new AppMenuItem(START_WITH_QUIT, getResources().getString(R.string.applist_menu_quit_and_start), false));
            }
        }

        if (lastRunningAppId != app.app.getAppId() || app.isHidden) {
            items.add(new AppMenuItem(HIDE_APP_ID, getResources().getString(R.string.applist_menu_hide_app), app.isHidden));
        }

        items.add(new AppMenuItem(VIEW_DETAILS_ID, getResources().getString(R.string.applist_menu_details), false));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && targetView != null) {
            ImageView appImageView = targetView.findViewById(R.id.grid_image);
            if (appImageView != null) {
                if (!(appImageView.getDrawable() instanceof BitmapDrawable)) {
                    return items;
                }
                BitmapDrawable drawable = (BitmapDrawable) appImageView.getDrawable();
                if (drawable != null && drawable.getBitmap() != null) {
                    items.add(new AppMenuItem(CREATE_SHORTCUT_ID, getResources().getString(R.string.applist_menu_scut), false));
                }
            }
        }

        return items;
    }

    private void showAppMenuOverlay(final AppObject app, final View targetView) {
        if (leftEyeContainer == null || app == null) {
            return;
        }

        dismissAppMenuOverlay();

        final ArrayList<AppMenuItem> items = buildAppMenuItems(app, targetView);
        if (items.isEmpty()) {
            return;
        }

        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xB0000000);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissAppMenuOverlay();
            }
        });

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF424242);
        panel.setClickable(true);
        panel.setFocusable(true);
        panel.setPadding(dp(24), dp(16), dp(24), dp(16));
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                dp(380),
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        panelParams.gravity = Gravity.CENTER;
        overlay.addView(panel, panelParams);

        TextView header = new TextView(this);
        header.setText(app.app.getAppName());
        header.setTextColor(Color.WHITE);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        for (final AppMenuItem item : items) {
            TextView option = new TextView(this);
            option.setText(item.checked ? "[x] " + item.label : item.label);
            option.setTextColor(Color.WHITE);
            option.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            option.setPadding(0, dp(14), 0, dp(14));
            option.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismissAppMenuOverlay();
                    onAppMenuItemSelected(item.id, app, targetView, item.checked);
                }
            });
            panel.addView(option, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }

        leftEyeContainer.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        appMenuOverlay = overlay;
    }

    private boolean onAppMenuItemSelected(int itemId, final AppObject app, View targetView, boolean currentChecked) {
        switch (itemId) {
            case START_WITH_QUIT:
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        ServerHelper.doStart(AppView.this, app.app, computer, managerBinder);
                    }
                }, null);
                return true;

            case START_OR_RESUME_ID:
                ServerHelper.doStart(AppView.this, app.app, computer, managerBinder);
                return true;

            case QUIT_ID:
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        suspendGridUpdates = true;
                        ServerHelper.doQuit(AppView.this, computer, app.app, managerBinder, new Runnable() {
                            @Override
                            public void run() {
                                suspendGridUpdates = false;
                                if (poller != null) {
                                    poller.pollNow();
                                }
                            }
                        });
                    }
                }, null);
                return true;

            case VIEW_DETAILS_ID:
                Dialog.displayDialog(AppView.this, getResources().getString(R.string.title_details), app.app.toString(), false);
                return true;

            case HIDE_APP_ID:
                if (currentChecked) {
                    hiddenAppIds.remove(app.app.getAppId());
                }
                else {
                    hiddenAppIds.add(app.app.getAppId());
                }
                updateHiddenApps(false);
                return true;

            case CREATE_SHORTCUT_ID:
                if (targetView != null) {
                    ImageView appImageView = targetView.findViewById(R.id.grid_image);
                    if (appImageView != null && appImageView.getDrawable() instanceof BitmapDrawable) {
                        Bitmap appBits = ((BitmapDrawable) appImageView.getDrawable()).getBitmap();
                        if (appBits != null &&
                                !shortcutHelper.createPinnedGameShortcut(computer, app.app, appBits)) {
                            ToastHelper.show(AppView.this, getResources().getString(R.string.unable_to_pin_shortcut), ToastHelper.LENGTH_LONG);
                        }
                    }
                }
                return true;

            default:
                return false;
        }
    }

    private void updateUiWithServerinfo(final ComputerDetails details) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean updated = false;

                    // Look through our current app list to tag the running app
                for (int i = 0; i < appGridAdapter.getCount(); i++) {
                    AppObject existingApp = (AppObject) appGridAdapter.getItem(i);

                    // There can only be one or zero apps running.
                    if (existingApp.isRunning &&
                            existingApp.app.getAppId() == details.runningGameId) {
                        // This app was running and still is, so we're done now
                        return;
                    }
                    else if (existingApp.app.getAppId() == details.runningGameId) {
                        // This app wasn't running but now is
                        existingApp.isRunning = true;
                        updated = true;
                    }
                    else if (existingApp.isRunning) {
                        // This app was running but now isn't
                        existingApp.isRunning = false;
                        updated = true;
                    }
                    else {
                        // This app wasn't running and still isn't
                    }
                }

                if (updated) {
                    appGridAdapter.notifyDataSetChanged();
                }
            }
        });
    }

    private void updateUiWithAppList(final List<NvApp> appList) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean updated = false;

                // First handle app updates and additions
                for (NvApp app : appList) {
                    boolean foundExistingApp = false;

                    // Try to update an existing app in the list first
                    for (int i = 0; i < appGridAdapter.getCount(); i++) {
                        AppObject existingApp = (AppObject) appGridAdapter.getItem(i);
                        if (existingApp.app.getAppId() == app.getAppId()) {
                            // Found the app; update its properties
                            if (!existingApp.app.getAppName().equals(app.getAppName())) {
                                existingApp.app.setAppName(app.getAppName());
                                updated = true;
                            }

                            foundExistingApp = true;
                            break;
                        }
                    }

                    if (!foundExistingApp) {
                        // This app must be new
                        appGridAdapter.addApp(new AppObject(app));

                        // We could have a leftover shortcut from last time this PC was paired
                        // or if this app was removed then added again. Enable those shortcuts
                        // again if present.
                        shortcutHelper.enableAppShortcut(computer, app);

                        updated = true;
                    }
                }

                // Next handle app removals
                int i = 0;
                while (i < appGridAdapter.getCount()) {
                    boolean foundExistingApp = false;
                    AppObject existingApp = (AppObject) appGridAdapter.getItem(i);

                    // Check if this app is in the latest list
                    for (NvApp app : appList) {
                        if (existingApp.app.getAppId() == app.getAppId()) {
                            foundExistingApp = true;
                            break;
                        }
                    }

                    // This app was removed in the latest app list
                    if (!foundExistingApp) {
                        shortcutHelper.disableAppShortcut(computer, existingApp.app, "App removed from PC");
                        appGridAdapter.removeApp(existingApp);
                        updated = true;

                        // Check this same index again because the item at i+1 is now at i after
                        // the removal
                        continue;
                    }

                    // Move on to the next item
                    i++;
                }

                if (updated) {
                    appGridAdapter.notifyDataSetChanged();
                }
            }
        });
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        return PreferenceConfiguration.readPreferences(AppView.this).smallIconMode ?
                    R.layout.app_grid_view_small : R.layout.app_grid_view;
    }

    @Override
    public void receiveAbsListView(AbsListView listView) {
        listView.setAdapter(appGridAdapter);
        listView.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int pos,
                                    long id) {
                AppObject app = (AppObject) appGridAdapter.getItem(pos);

                // Only open the context menu if something is running, otherwise start it
                if (lastRunningAppId != 0) {
                    showAppMenuOverlay(app, arg1);
                } else {
                    ServerHelper.doStart(AppView.this, app.app, computer, managerBinder);
                }
            }
        });
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                AppObject app = (AppObject) appGridAdapter.getItem(position);
                showAppMenuOverlay(app, view);
                return true;
            }
        });
        UiHelper.applyStatusBarPadding(listView);
        listView.requestFocus();
    }

    public static class AppObject {
        public final NvApp app;
        public boolean isRunning;
        public boolean isHidden;

        public AppObject(NvApp app) {
            if (app == null) {
                throw new IllegalArgumentException("app must not be null");
            }
            this.app = app;
        }

        @Override
        public String toString() {
            return app.getAppName();
        }
    }
}

