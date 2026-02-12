package com.limelight;

import com.limelight.utils.ToastHelper;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;

import com.limelight.binding.PlatformBinding;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.PcGridAdapter;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.preferences.AddComputerManually;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.StreamSettings;
import com.limelight.ui.AdapterFragment;
import com.limelight.ui.AdapterFragmentCallbacks;
import com.limelight.utils.Dialog;
import com.limelight.utils.HelpLauncher;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.UiHelper;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.PixelCopy;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnClickListener;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.util.TypedValue;

import org.xmlpull.v1.XmlPullParserException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import java.util.ArrayList;

public class PcView extends Activity implements AdapterFragmentCallbacks {
    private RelativeLayout noPcFoundLayout;
    private PcGridAdapter pcGridAdapter;
    private ShortcutHelper shortcutHelper;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean freezeUpdates, runningPolling, inForeground, completeOnCreateCalled;
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

                    // Now make the binder visible
                    managerBinder = localBinder;

                    // Start updates
                    startComputerUpdates();

                    // Force a keypair to be generated early to avoid discovery delays
                    new AndroidCryptoProvider(PcView.this).getClientCertificate();
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

        // Only reinitialize views if completeOnCreate() was called
        // before this callback. If it was not, completeOnCreate() will
        // handle initializing views with the config change accounted for.
        // This is not prone to races because both callbacks are invoked
        // in the main thread.
        if (completeOnCreateCalled) {
            // Reinitialize views just in case orientation changed
            initializeViews();
        }
    }

    private final static int PAIR_ID = 2;
    private final static int UNPAIR_ID = 3;
    private final static int WOL_ID = 4;
    private final static int DELETE_ID = 5;
    private final static int RESUME_ID = 6;
    private final static int QUIT_ID = 7;
    private final static int VIEW_DETAILS_ID = 8;
    private final static int FULL_APP_LIST_ID = 9;
    private final static int TEST_NETWORK_ID = 10;
    private final static int GAMESTREAM_EOL_ID = 11;
    private static final int EYE_WIDTH_PX = 640;
    private static final int EYE_HEIGHT_PX = 480;
    private static final long RIGHT_EYE_MIRROR_FRAME_DELAY_MS = 16;
    private View pcMenuOverlay;

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

    private static final class PcMenuItem {
        final int id;
        final String label;

        PcMenuItem(int id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private void initializeViews() {
        stopRightEyeMirroring();
        recycleRightEyeBitmap();
        setContentView(R.layout.activity_pc_view);

        setupStereoMirroring();

        UiHelper.notifyNewRootView(this);

        // Allow floating expanded PiP overlays while browsing PCs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        // Set default preferences if we've never been run
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        // Set the correct layout for the PC grid
        pcGridAdapter.updateLayoutWithPreferences(this, PreferenceConfiguration.readPreferences(this));

        // Setup the list view
        ImageButton settingsButton = findViewById(R.id.settingsButton);
        ImageButton addComputerButton = findViewById(R.id.manuallyAddPc);
        ImageButton helpButton = findViewById(R.id.helpButton);

        settingsButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(PcView.this, StreamSettings.class));
            }
        });
        addComputerButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(PcView.this, AddComputerManually.class);
                startActivity(i);
            }
        });
        helpButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                HelpLauncher.launchSetupGuide(PcView.this);
            }
        });

        // Amazon review didn't like the help button because the wiki was not entirely
        // navigable via the Fire TV remote (though the relevant parts were). Let's hide
        // it on Fire TV.
        if (getPackageManager().hasSystemFeature("amazon.hardware.fire_tv")) {
            helpButton.setVisibility(View.GONE);
        }

        getFragmentManager().beginTransaction()
            .replace(R.id.pcFragmentContainer, new AdapterFragment())
            .commitAllowingStateLoss();

        noPcFoundLayout = findViewById(R.id.no_pc_found_layout);
        if (pcGridAdapter.getCount() == 0) {
            noPcFoundLayout.setVisibility(View.VISIBLE);
        }
        else {
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }
        pcGridAdapter.notifyDataSetChanged();
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
            LimeLog.warning("PcView PixelCopy failed: " + e.getMessage());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        // Create a GLSurfaceView to fetch GLRenderer unless we have
        // a cached result already.
        final GlPreferences glPrefs = GlPreferences.readPreferences(this);
        if (!glPrefs.savedFingerprint.equals(Build.FINGERPRINT) || glPrefs.glRenderer.isEmpty()) {
            GLSurfaceView surfaceView = new GLSurfaceView(this);
            surfaceView.setRenderer(new GLSurfaceView.Renderer() {
                @Override
                public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
                    // Save the GLRenderer string so we don't need to do this next time
                    glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER);
                    glPrefs.savedFingerprint = Build.FINGERPRINT;
                    glPrefs.writePreferences();

                    LimeLog.info("Fetched GL Renderer: " + glPrefs.glRenderer);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            completeOnCreate();
                        }
                    });
                }

                @Override
                public void onSurfaceChanged(GL10 gl10, int i, int i1) {
                }

                @Override
                public void onDrawFrame(GL10 gl10) {
                }
            });
            setContentView(surfaceView);
        }
        else {
            LimeLog.info("Cached GL Renderer: " + glPrefs.glRenderer);
            completeOnCreate();
        }
    }

    private void completeOnCreate() {
        completeOnCreateCalled = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        // Bind to the computer manager service
        bindService(new Intent(PcView.this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);

        pcGridAdapter = new PcGridAdapter(this, PreferenceConfiguration.readPreferences(this));

        initializeViews();
    }

    private void startComputerUpdates() {
        // Only allow polling to start if we're bound to CMS, polling is not already running,
        // and our activity is in the foreground.
        if (managerBinder != null && !runningPolling && inForeground) {
            freezeUpdates = false;
            managerBinder.startPolling(new ComputerManagerListener() {
                @Override
                public void notifyComputerUpdated(final ComputerDetails details) {
                    if (!freezeUpdates) {
                        PcView.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                updateComputer(details);
                            }
                        });

                        // Add a launcher shortcut for this PC (off the main thread to prevent ANRs)
                        if (details.pairState == PairState.PAIRED) {
                            shortcutHelper.createAppViewShortcutForOnlineHost(details);
                        }
                    }
                }
            });
            runningPolling = true;
        }
    }

    private void stopComputerUpdates(boolean wait) {
        if (managerBinder != null) {
            if (!runningPolling) {
                return;
            }

            freezeUpdates = true;

            managerBinder.stopPolling();

            if (wait) {
                managerBinder.waitForPollingStopped();
            }

            runningPolling = false;
        }
    }

    @Override
    public void onDestroy() {
        stopRightEyeMirroring();
        recycleRightEyeBitmap();

        super.onDestroy();

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
        stopRightEyeMirroring();

        super.onPause();

        inForeground = false;
        stopComputerUpdates(false);
    }

    @Override
    protected void onStop() {
        super.onStop();

        dismissPcMenuOverlay();
        Dialog.closeDialogs();
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private void dismissPcMenuOverlay() {
        if (pcMenuOverlay != null) {
            ViewGroup parent = (ViewGroup) pcMenuOverlay.getParent();
            if (parent != null) {
                parent.removeView(pcMenuOverlay);
            }
            pcMenuOverlay = null;
        }
    }

    private ArrayList<PcMenuItem> buildPcMenuItems(ComputerObject computer) {
        ArrayList<PcMenuItem> items = new ArrayList<>();

        if (computer.details.state == ComputerDetails.State.OFFLINE ||
                computer.details.state == ComputerDetails.State.UNKNOWN) {
            items.add(new PcMenuItem(WOL_ID, getResources().getString(R.string.pcview_menu_send_wol)));
            items.add(new PcMenuItem(GAMESTREAM_EOL_ID, getResources().getString(R.string.pcview_menu_eol)));
        }
        else if (computer.details.pairState != PairState.PAIRED) {
            items.add(new PcMenuItem(PAIR_ID, getResources().getString(R.string.pcview_menu_pair_pc)));
            if (computer.details.nvidiaServer) {
                items.add(new PcMenuItem(GAMESTREAM_EOL_ID, getResources().getString(R.string.pcview_menu_eol)));
            }
        }
        else {
            if (computer.details.runningGameId != 0) {
                items.add(new PcMenuItem(RESUME_ID, getResources().getString(R.string.applist_menu_resume)));
                items.add(new PcMenuItem(QUIT_ID, getResources().getString(R.string.applist_menu_quit)));
            }

            if (computer.details.nvidiaServer) {
                items.add(new PcMenuItem(GAMESTREAM_EOL_ID, getResources().getString(R.string.pcview_menu_eol)));
            }

            items.add(new PcMenuItem(FULL_APP_LIST_ID, getResources().getString(R.string.pcview_menu_app_list)));
        }

        items.add(new PcMenuItem(TEST_NETWORK_ID, getResources().getString(R.string.pcview_menu_test_network)));
        items.add(new PcMenuItem(DELETE_ID, getResources().getString(R.string.pcview_menu_delete_pc)));
        items.add(new PcMenuItem(VIEW_DETAILS_ID, getResources().getString(R.string.pcview_menu_details)));
        return items;
    }

    private void showPcMenuOverlay(final ComputerObject computer) {
        stopComputerUpdates(false);
        dismissPcMenuOverlay();

        FrameLayout leftContainer = findViewById(R.id.leftEyeContainer);
        if (leftContainer == null) {
            startComputerUpdates();
            return;
        }

        FrameLayout scrim = new FrameLayout(this);
        scrim.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        scrim.setBackgroundColor(0x88000000);
        scrim.setClickable(true);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.parseColor("#4A4A4A"));
        panel.setPadding(dp(18), dp(14), dp(18), dp(14));
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        panelParams.gravity = Gravity.CENTER;
        panelParams.leftMargin = dp(40);
        panelParams.rightMargin = dp(40);
        panel.setLayoutParams(panelParams);

        String headerTitle = computer.details.name + " - ";
        switch (computer.details.state) {
            case ONLINE:
                headerTitle += getResources().getString(R.string.pcview_menu_header_online);
                break;
            case OFFLINE:
                headerTitle += getResources().getString(R.string.pcview_menu_header_offline);
                break;
            case UNKNOWN:
                headerTitle += getResources().getString(R.string.pcview_menu_header_unknown);
                break;
        }

        TextView header = new TextView(this);
        header.setText(headerTitle);
        header.setTextColor(Color.WHITE);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        panel.addView(header);

        ArrayList<PcMenuItem> items = buildPcMenuItems(computer);
        for (final PcMenuItem item : items) {
            TextView row = new TextView(this);
            row.setText(item.label);
            row.setTextColor(Color.WHITE);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            row.setPadding(0, dp(12), 0, dp(12));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                dismissPcMenuOverlay();
                onPcMenuItemSelected(item.id, computer);
                startComputerUpdates();
            });
            panel.addView(row);
        }

        scrim.setOnClickListener(v -> {
            dismissPcMenuOverlay();
            startComputerUpdates();
        });

        scrim.addView(panel);
        leftContainer.addView(scrim);
        pcMenuOverlay = scrim;
    }

    private boolean onPcMenuItemSelected(int itemId, final ComputerObject computer) {
        switch (itemId) {
            case PAIR_ID:
                doPair(computer.details);
                return true;

            case UNPAIR_ID:
                doUnpair(computer.details);
                return true;

            case WOL_ID:
                doWakeOnLan(computer.details);
                return true;

            case DELETE_ID:
                if (ActivityManager.isUserAMonkey()) {
                    LimeLog.info("Ignoring delete PC request from monkey");
                    return true;
                }
                UiHelper.displayDeletePcConfirmationDialog(this, computer.details, new Runnable() {
                    @Override
                    public void run() {
                        if (managerBinder == null) {
                            ToastHelper.show(PcView.this, getResources().getString(R.string.error_manager_not_running), ToastHelper.LENGTH_LONG);
                            return;
                        }
                        removeComputer(computer.details);
                    }
                }, null);
                return true;

            case FULL_APP_LIST_ID:
                doAppList(computer.details, false, true);
                return true;

            case RESUME_ID:
                if (managerBinder == null) {
                    ToastHelper.show(PcView.this, getResources().getString(R.string.error_manager_not_running), ToastHelper.LENGTH_LONG);
                    return true;
                }
                ServerHelper.doStart(this, new NvApp("app", computer.details.runningGameId, false), computer.details, managerBinder);
                return true;

            case QUIT_ID:
                if (managerBinder == null) {
                    ToastHelper.show(PcView.this, getResources().getString(R.string.error_manager_not_running), ToastHelper.LENGTH_LONG);
                    return true;
                }
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        ServerHelper.doQuit(PcView.this, computer.details,
                                new NvApp("app", 0, false), managerBinder, null);
                    }
                }, null);
                return true;

            case VIEW_DETAILS_ID:
                Dialog.displayDialog(PcView.this, getResources().getString(R.string.title_details), computer.details.toString(), false);
                return true;

            case TEST_NETWORK_ID:
                ServerHelper.doNetworkTest(PcView.this);
                return true;

            case GAMESTREAM_EOL_ID:
                HelpLauncher.launchGameStreamEolFaq(PcView.this);
                return true;

            default:
                return false;
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        stopComputerUpdates(false);

        // Call superclass
        super.onCreateContextMenu(menu, v, menuInfo);
                
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(info.position);

        // Add a header with PC status details
        menu.clearHeader();
        String headerTitle = computer.details.name + " - ";
        switch (computer.details.state)
        {
            case ONLINE:
                headerTitle += getResources().getString(R.string.pcview_menu_header_online);
                break;
            case OFFLINE:
                menu.setHeaderIcon(R.drawable.ic_pc_offline);
                headerTitle += getResources().getString(R.string.pcview_menu_header_offline);
                break;
            case UNKNOWN:
                headerTitle += getResources().getString(R.string.pcview_menu_header_unknown);
                break;
        }

        menu.setHeaderTitle(headerTitle);

        // Inflate the context menu
        if (computer.details.state == ComputerDetails.State.OFFLINE ||
            computer.details.state == ComputerDetails.State.UNKNOWN) {
            menu.add(Menu.NONE, WOL_ID, 1, getResources().getString(R.string.pcview_menu_send_wol));
            menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 2, getResources().getString(R.string.pcview_menu_eol));
        }
        else if (computer.details.pairState != PairState.PAIRED) {
            menu.add(Menu.NONE, PAIR_ID, 1, getResources().getString(R.string.pcview_menu_pair_pc));
            if (computer.details.nvidiaServer) {
                menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 2, getResources().getString(R.string.pcview_menu_eol));
            }
        }
        else {
            if (computer.details.runningGameId != 0) {
                menu.add(Menu.NONE, RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }

            if (computer.details.nvidiaServer) {
                menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 3, getResources().getString(R.string.pcview_menu_eol));
            }

            menu.add(Menu.NONE, FULL_APP_LIST_ID, 4, getResources().getString(R.string.pcview_menu_app_list));
        }

        menu.add(Menu.NONE, TEST_NETWORK_ID, 5, getResources().getString(R.string.pcview_menu_test_network));
        menu.add(Menu.NONE, DELETE_ID, 6, getResources().getString(R.string.pcview_menu_delete_pc));
        menu.add(Menu.NONE, VIEW_DETAILS_ID, 7,  getResources().getString(R.string.pcview_menu_details));
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        // For some reason, this gets called again _after_ onPause() is called on this activity.
        // startComputerUpdates() manages this and won't actual start polling until the activity
        // returns to the foreground.
        startComputerUpdates();
    }

    private void doPair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            ToastHelper.show(PcView.this, getResources().getString(R.string.pair_pc_offline), ToastHelper.LENGTH_SHORT);
            return;
        }
        if (managerBinder == null) {
            ToastHelper.show(PcView.this, getResources().getString(R.string.error_manager_not_running), ToastHelper.LENGTH_LONG);
            return;
        }

        ToastHelper.show(PcView.this, getResources().getString(R.string.pairing), ToastHelper.LENGTH_SHORT);
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                boolean success = false;
                try {
                    // Stop updates and wait while pairing
                    stopComputerUpdates(true);

                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                            PlatformBinding.getCryptoProvider(PcView.this));
                    if (httpConn.getPairState() == PairState.PAIRED) {
                        // Don't display any toast, but open the app list
                        message = null;
                        success = true;
                    }
                    else {
                        final String pinStr = PairingManager.generatePinString();

                        // Spin the dialog off in a thread because it blocks
                        Dialog.displayDialog(PcView.this, getResources().getString(R.string.pair_pairing_title),
                                getResources().getString(R.string.pair_pairing_msg)+" "+pinStr+"\n\n"+
                                getResources().getString(R.string.pair_pairing_help), false);

                        PairingManager pm = httpConn.getPairingManager();

                        PairState pairState = pm.pair(httpConn.getServerInfo(true), pinStr);
                        if (pairState == PairState.PIN_WRONG) {
                            message = getResources().getString(R.string.pair_incorrect_pin);
                        }
                        else if (pairState == PairState.FAILED) {
                            if (computer.runningGameId != 0) {
                                message = getResources().getString(R.string.pair_pc_ingame);
                            }
                            else {
                                message = getResources().getString(R.string.pair_fail);
                            }
                        }
                        else if (pairState == PairState.ALREADY_IN_PROGRESS) {
                            message = getResources().getString(R.string.pair_already_in_progress);
                        }
                        else if (pairState == PairState.PAIRED) {
                            // Just navigate to the app view without displaying a toast
                            message = null;
                            success = true;

                            // Pin this certificate for later HTTPS use
                            managerBinder.getComputer(computer.uuid).serverCert = pm.getPairedCert();

                            // Invalidate reachability information after pairing to force
                            // a refresh before reading pair state again
                            managerBinder.invalidateStateForComputer(computer.uuid);
                        }
                        else {
                            // Should be no other values
                            message = null;
                        }
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                    message = e.getMessage();
                }

                Dialog.closeDialogs();

                final String toastMessage = message;
                final boolean toastSuccess = success;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (toastMessage != null) {
                            ToastHelper.show(PcView.this, toastMessage, ToastHelper.LENGTH_LONG);
                        }

                        if (toastSuccess) {
                            // Open the app list after a successful pairing attempt
                            doAppList(computer, true, false);
                        }
                        else {
                            // Start polling again if we're still in the foreground
                            startComputerUpdates();
                        }
                    }
                });
            }
        }).start();
    }

    private void doWakeOnLan(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            ToastHelper.show(PcView.this, getResources().getString(R.string.wol_pc_online), ToastHelper.LENGTH_SHORT);
            return;
        }

        if (computer.macAddress == null) {
            ToastHelper.show(PcView.this, getResources().getString(R.string.wol_no_mac), ToastHelper.LENGTH_SHORT);
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                try {
                    WakeOnLanSender.sendWolPacket(computer);
                    message = getResources().getString(R.string.wol_waking_msg);
                } catch (IOException e) {
                    message = getResources().getString(R.string.wol_fail);
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        ToastHelper.show(PcView.this, toastMessage, ToastHelper.LENGTH_LONG);
                    }
                });
            }
        }).start();
    }

    private void doUnpair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            ToastHelper.show(PcView.this, getResources().getString(R.string.error_pc_offline), ToastHelper.LENGTH_SHORT);
            return;
        }
        if (managerBinder == null) {
            ToastHelper.show(PcView.this, getResources().getString(R.string.error_manager_not_running), ToastHelper.LENGTH_LONG);
            return;
        }

        ToastHelper.show(PcView.this, getResources().getString(R.string.unpairing), ToastHelper.LENGTH_SHORT);
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                try {
                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                            PlatformBinding.getCryptoProvider(PcView.this));
                    if (httpConn.getPairState() == PairingManager.PairState.PAIRED) {
                        httpConn.unpair();
                        if (httpConn.getPairState() == PairingManager.PairState.NOT_PAIRED) {
                            message = getResources().getString(R.string.unpair_success);
                        }
                        else {
                            message = getResources().getString(R.string.unpair_fail);
                        }
                    }
                    else {
                        message = getResources().getString(R.string.unpair_error);
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    message = e.getMessage();
                    e.printStackTrace();
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        ToastHelper.show(PcView.this, toastMessage, ToastHelper.LENGTH_LONG);
                    }
                });
            }
        }).start();
    }

    private void doAppList(ComputerDetails computer, boolean newlyPaired, boolean showHiddenGames) {
        if (computer.state == ComputerDetails.State.OFFLINE) {
            ToastHelper.show(PcView.this, getResources().getString(R.string.error_pc_offline), ToastHelper.LENGTH_SHORT);
            return;
        }
        if (managerBinder == null) {
            ToastHelper.show(PcView.this, getResources().getString(R.string.error_manager_not_running), ToastHelper.LENGTH_LONG);
            return;
        }

        Intent i = new Intent(this, AppView.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.putExtra(AppView.NEW_PAIR_EXTRA, newlyPaired);
        i.putExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, showHiddenGames);
        startActivity(i);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        final ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(info.position);
        if (onPcMenuItemSelected(item.getItemId(), computer)) {
            return true;
        }
        return super.onContextItemSelected(item);
    }
    
    private void removeComputer(ComputerDetails details) {
        managerBinder.removeComputer(details);

        new DiskAssetLoader(this).deleteAssetsForComputer(details.uuid);

        // Delete hidden games preference value
        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .remove(details.uuid)
                .apply();

        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

            if (details.equals(computer.details)) {
                // Disable or delete shortcuts referencing this PC
                shortcutHelper.disableComputerShortcut(details,
                        getResources().getString(R.string.scut_deleted_pc));

                pcGridAdapter.removeComputer(computer);
                pcGridAdapter.notifyDataSetChanged();

                if (pcGridAdapter.getCount() == 0) {
                    // Show the "Discovery in progress" view
                    noPcFoundLayout.setVisibility(View.VISIBLE);
                }

                break;
            }
        }
    }
    
    private void updateComputer(ComputerDetails details) {
        ComputerObject existingEntry = null;

        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

            // Check if this is the same computer
            if (details.uuid.equals(computer.details.uuid)) {
                existingEntry = computer;
                break;
            }
        }

        if (existingEntry != null) {
            // Replace the information in the existing entry
            existingEntry.details = details;
        }
        else {
            // Add a new entry
            pcGridAdapter.addComputer(new ComputerObject(details));

            // Remove the "Discovery in progress" view
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }

        // Notify the view that the data has changed
        pcGridAdapter.notifyDataSetChanged();
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        return R.layout.pc_grid_view;
    }

    @Override
    public void receiveAbsListView(AbsListView listView) {
        listView.setAdapter(pcGridAdapter);
        listView.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int pos,
                                    long id) {
                ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(pos);
                if (computer.details.state == ComputerDetails.State.UNKNOWN ||
                    computer.details.state == ComputerDetails.State.OFFLINE) {
                    showPcMenuOverlay(computer);
                } else if (computer.details.pairState != PairState.PAIRED) {
                    // Pair an unpaired machine by default
                    doPair(computer.details);
                } else {
                    doAppList(computer.details, false, false);
                }
            }
        });
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(position);
                showPcMenuOverlay(computer);
                return true;
            }
        });
        UiHelper.applyStatusBarPadding(listView);
        // Keep this in-layout to avoid system window dialogs/menus.
        listView.requestFocus();
    }

    public static class ComputerObject {
        public ComputerDetails details;

        public ComputerObject(ComputerDetails details) {
            if (details == null) {
                throw new IllegalArgumentException("details must not be null");
            }
            this.details = details;
        }

        @Override
        public String toString() {
            return details.name;
        }
    }
}

