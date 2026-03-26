/*
 *
 * Copyright (c) OpenIPC  https://openipc.org  MIT License
 *
 * Decoder.java — main activity for H.264/H.265 hardware video decoding
 *
 */

package com.openipc.decoder;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.text.style.SuperscriptSpan;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.HttpAuthHandler;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Decoder extends Activity implements WebServer.SettingsChangeListener {
    private static final String TAG = "OpenIPCDecoder";
    private final BlockingQueue<Frame> nalQueue = new ArrayBlockingQueue<>(32);
    private final BlockingQueue<Frame> pcmQueue = new ArrayBlockingQueue<>(32);
    private final byte[] nalBuffer = new byte[1024 * 1024];

    private int nalSize;

    // web server control
    private WebServer webServer;
    private Thread webServerThread;

    private volatile MediaCodec mDecoder;
    private volatile Surface mVideoSurface;
    private final Object decoderLock = new Object();
    private TextureView mSurface;

    private float mDensity;

    private ScaleGestureDetector mScaleDetector;
    private GestureDetector mGestureDetector;
    private float mZoomScale = 1.0f;
    private float mPanX = 0f;
    private float mPanY = 0f;
    private static final float ZOOM_MIN = 1.0f;
    private static final float ZOOM_MAX = 5.0f;
    private volatile AudioTrack audioTrack;

    private volatile boolean codecH265;
    private boolean lastCodec;
    private boolean listener;
    private volatile boolean activeStream;
    private volatile int lastWidth;
    private volatile int lastHeight;
    private volatile long lastFrame;

    private volatile int listenerGen;
    private volatile boolean audioFailed;
    private volatile boolean decoderFailed;

    private volatile Socket mTcpSocket;
    private volatile DatagramSocket mUdpSocket;
    private volatile DatagramSocket mUdpAudioSocket;

    private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

    private static final int CAM_COUNT = 8;
    private static final String DEFAULT_URL = "rtsp://root:12345@192.168.1.10:554/stream=0";
    private final String[] mHosts = new String[CAM_COUNT];
    private final boolean[] mTypes = new boolean[CAM_COUNT];
    private final boolean[] mCarousel = new boolean[CAM_COUNT];
    private final boolean[] mQuad = new boolean[CAM_COUNT];
    private int mActive;
    private volatile String mHost;
    private volatile boolean mType;
    private String mVersion = "1.0";
    private String mUserAgent = "User-Agent: OpenIPC-Decoder/1.0\r\n";

    private int lastUnknownPayload = -1;

    private static final int RTP_FU_H264 = 28;
    private static final int RTP_FU_H265 = 49;
    private static final int RTP_PT_H265 = 97;
    private static final int RTP_PT_H264 = 96;
    private static final int RTP_PT_PCMU_DEFAULT = 100;

    private static final int H265_NAL_VPS = 32;
    private static final int H265_NAL_SPS = 33;
    private static final int H265_NAL_PPS = 34;
    private static final int H264_NAL_SPS = 7;
    private static final int H264_NAL_PPS = 8;

    private static final long WATCHDOG_MS = 3000;

    private volatile int audioSampleRate = 8000;
    private volatile int audioPt = RTP_PT_PCMU_DEFAULT;
    private volatile boolean audioBigEndian = true;

    private Dialog mBrowserDialog;

    private static final int CAROUSEL_MIN_SEC = 3;
    private static final int CAROUSEL_MAX_SEC = 120;
    private static final int CAROUSEL_DEFAULT_SEC = 10;
    private boolean carouselEnabled;
    private int carouselInterval = CAROUSEL_DEFAULT_SEC;
    private final Handler carouselHandler = new Handler(Looper.getMainLooper());
    private final Runnable carouselRunnable = new Runnable() {
        @Override public void run() {
            if (!carouselEnabled) return;
            if (activeStream && lastFrame > 0
                    && SystemClock.elapsedRealtime() - lastFrame < WATCHDOG_MS) {
                carouselSwitch();
            }
            carouselHandler.postDelayed(this, carouselInterval * 1000L);
        }
    };

    private ExecutorService executor;

    private boolean quadEnabled;
    private QuadCell[] quadCells;
    private LinearLayout quadContainer;
    private final TextureView[] quadViews = new TextureView[4];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.decoder);

        // Start web server
        startWebServer();

        // Check saved settings
        SharedPreferences pref = getSharedPreferences("settings", MODE_PRIVATE);
        Log.d(TAG, "Saved settings check:");
        for (int i = 0; i < 8; i++) {
            String url = pref.getString("host_" + i, null);
            if (url != null) {
                Log.d(TAG, "Camera " + i + ": " + url);
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                mVersion = getPackageManager()
                        .getPackageInfo(getPackageName(), PackageManager.PackageInfoFlags.of(0))
                        .versionName;
            } else {
                mVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            }
        } catch (PackageManager.NameNotFoundException ignored) {}
        mUserAgent = "User-Agent: OpenIPC-Decoder/" + mVersion + "\r\n";
        mDensity = getResources().getDisplayMetrics().density;

        mSurface = findViewById(R.id.video_surface);
        mSurface.setKeepScreenOn(true);
        mSurface.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                replaceSurface(new Surface(st));
            }
            @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) {
                replaceSurface(new Surface(st));
            }
            @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                replaceSurface(null);
                return true;
            }
            @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) {}
        });

        mScaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                mZoomScale = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, mZoomScale * d.getScaleFactor()));
                clampPan();
                applyZoomTransform();
                return true;
            }
        });

        final View menuAnchor = findViewById(R.id.decoder);
        mGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (mZoomScale > ZOOM_MIN) {
                    mPanX -= dx;
                    mPanY -= dy;
                    clampPan();
                    applyZoomTransform();
                    return true;
                }
                return false;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                resetZoom();
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                createMenu(menuAnchor);
                return true;
            }
        });
        mSurface.setOnTouchListener((v, event) -> {
            mScaleDetector.onTouchEvent(event);
            mGestureDetector.onTouchEvent(event);
            return true;
        });

        quadContainer = new LinearLayout(this);
        quadContainer.setOrientation(LinearLayout.VERTICAL);
        quadContainer.setVisibility(View.GONE);
        for (int row = 0; row < 2; row++) {
            LinearLayout lr = new LinearLayout(this);
            lr.setOrientation(LinearLayout.HORIZONTAL);
            quadContainer.addView(lr, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            for (int col = 0; col < 2; col++) {
                int idx = row * 2 + col;
                quadViews[idx] = new TextureView(this);
                quadViews[idx].setOnClickListener(cv -> createMenu(menuAnchor));
                lr.addView(quadViews[idx], new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
            }
        }
        FrameLayout root = (FrameLayout) menuAnchor;
        root.addView(quadContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController ctrl = getWindow().getInsetsController();
            if (ctrl != null) {
                ctrl.hide(WindowInsets.Type.navigationBars());
                ctrl.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "Uncaught exception", throwable);

            Intent intent = new Intent(getApplicationContext(), Crash.class);
            intent.putExtra("error", Log.getStackTraceString(throwable));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            System.exit(1);
        });
    }

    @SuppressLint("AuthLeak")
    private void loadSettings() {
        SharedPreferences pref = getSharedPreferences("settings", MODE_PRIVATE);

        if (pref.contains("host") && !pref.contains("host_0")) {
            SharedPreferences.Editor edit = pref.edit();
            edit.putString("host_0", pref.getString("host", DEFAULT_URL));
            edit.putBoolean("type_0", pref.getBoolean("type", false));
            edit.remove("host");
            edit.remove("type");
            edit.apply();
        }

        mActive = pref.getInt("active", 0);
        carouselEnabled = pref.getBoolean("carousel_enabled", false);
        carouselInterval = pref.getInt("carousel_interval", CAROUSEL_DEFAULT_SEC);
        quadEnabled = pref.getBoolean("quad_enabled", false);
        for (int i = 0; i < CAM_COUNT; i++) {
            mHosts[i] = pref.getString("host_" + i, DEFAULT_URL);
            mTypes[i] = pref.getBoolean("type_" + i, false);
            mCarousel[i] = pref.getBoolean("carousel_" + i, false);
            mQuad[i] = pref.getBoolean("quad_" + i, false);
        }
        applyActiveCamera();

        Intent intent = getIntent();
        String link = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (link != null) {
            Log.d(TAG, "Link: " + Uri.parse(link).getHost());
            mHosts[mActive] = sanitizeUrl(link);
            mHost = mHosts[mActive];
            intent.removeExtra(Intent.EXTRA_TEXT);
        }
    }

    private static String sanitizeUrl(String url) {
        return url.replaceAll("[\r\n]", "");
    }

    private void applyActiveCamera() {
        mHost = mHosts[mActive];
        mType = mTypes[mActive];
        resetZoom();
    }

    private void carouselSwitch() {
        int start = mActive;
        int next = -1;
        for (int i = 1; i <= CAM_COUNT; i++) {
            int candidate = (start + i) % CAM_COUNT;
            if (!mCarousel[candidate]) continue;
            String url = mHosts[candidate];
            if (url != null && !url.isEmpty() && !url.equals(DEFAULT_URL)) {
                next = candidate;
                break;
            }
        }
        if (next < 0 || next == mActive) return;

        mActive = next;
        applyActiveCamera();

        activeStream = false;
        closeSockets();

        nalQueue.clear();
        pcmQueue.clear();
        closeDecoder();
        closeAudio();

        lastFrame = 0;
        lastWidth = 0;
        lastHeight = 0;
    }

    private void startCarousel() {
        carouselEnabled = true;
        carouselHandler.removeCallbacks(carouselRunnable);
        carouselHandler.postDelayed(carouselRunnable, carouselInterval * 1000L);
        saveCarouselPrefs();
    }

    private void stopCarousel() {
        carouselEnabled = false;
        carouselHandler.removeCallbacks(carouselRunnable);
        saveCarouselPrefs();
    }

    private void saveCarouselPrefs() {
        SharedPreferences pref = getSharedPreferences("settings", MODE_PRIVATE);
        pref.edit()
            .putBoolean("carousel_enabled", carouselEnabled)
            .putInt("carousel_interval", carouselInterval)
            .apply();
    }

    private void startQuad() {
        if (carouselEnabled) stopCarousel();

        if (listener) {
            listenerGen++;
            listener = false;
            activeStream = false;
            closeSockets();
            if (executor != null) { executor.shutdownNow(); executor = null; }
            closeDecoder();
            closeAudio();
            nalQueue.clear();
            pcmQueue.clear();
        }

        mSurface.setVisibility(View.GONE);
        quadContainer.setVisibility(View.VISIBLE);

        quadCells = new QuadCell[4];
        int count = 0;
        for (int i = 0; i < CAM_COUNT && count < 4; i++) {
            if (!mQuad[i]) continue;
            String url = mHosts[i];
            if (url == null || url.isEmpty() || url.equals(DEFAULT_URL)) continue;
            quadCells[count] = new QuadCell(count, url, quadViews[count]);
            quadCells[count].start();
            count++;
        }

        quadEnabled = true;
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putBoolean("quad_enabled", true).apply();
    }

    private void stopQuad() {
        quadEnabled = false;
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putBoolean("quad_enabled", false).apply();

        if (quadCells != null) {
            for (QuadCell cell : quadCells) {
                if (cell != null) cell.stop();
            }
            quadCells = null;
        }

        quadContainer.setVisibility(View.GONE);
        mSurface.setVisibility(View.VISIBLE);

        if (!listener) {
            listener = true;
            startListener();
        }
    }

    private void saveSettings() {
        SharedPreferences pref = getSharedPreferences("settings", MODE_PRIVATE);
        SharedPreferences.Editor edit = pref.edit();
        edit.putInt("active", mActive);
        edit.putBoolean("carousel_enabled", carouselEnabled);
        edit.putInt("carousel_interval", carouselInterval);
        for (int i = 0; i < CAM_COUNT; i++) {
            edit.putString("host_" + i, mHosts[i]);
            edit.putBoolean("type_" + i, mTypes[i]);
            edit.putBoolean("carousel_" + i, mCarousel[i]);
            edit.putBoolean("quad_" + i, mQuad[i]);
        }
        edit.apply();

        applyActiveCamera();
        activeStream = false;
        closeSockets();
    }

    private void closeSockets() {
        Socket tcp = mTcpSocket;
        if (tcp != null) try { tcp.close(); } catch (Exception ignored) {}
        DatagramSocket udp = mUdpSocket;
        if (udp != null) try { udp.close(); } catch (Exception ignored) {}
        DatagramSocket udpAudio = mUdpAudioSocket;
        if (udpAudio != null) try { udpAudio.close(); } catch (Exception ignored) {}
    }

    private void createMenu(View menu) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        PopupWindow popup = new PopupWindow(layout, LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.showAtLocation(menu, Gravity.TOP | Gravity.START, 0, 0);

        LinearLayout camRow = new LinearLayout(this);
        camRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams wrapParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        layout.addView(camRow, wrapParams);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setVisibility(View.GONE);
        layout.addView(header);

        TextView settings = createItem("Settings");
        layout.addView(settings);

        EditText host = createEdit(mHosts[mActive]);
        header.addView(host);
        host.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                mHosts[mActive] = sanitizeUrl(host.getText().toString().trim());
                saveSettings();
                popup.dismiss();
                return true;
            }
            return false;
        });

        TextView typeToggle = createItem(mTypes[mActive] ? "Transport: UDP" : "Transport: TCP");
        header.addView(typeToggle);
        typeToggle.setOnClickListener(v -> {
            mTypes[mActive] = !mTypes[mActive];
            typeToggle.setText(mTypes[mActive] ? "Transport: UDP" : "Transport: TCP");
            saveSettings();
        });

        TextView carouselCamToggle = createItem(mCarousel[mActive]
                ? "Carousel: YES" : "Carousel: NO");
        header.addView(carouselCamToggle);
        carouselCamToggle.setOnClickListener(v -> {
            mCarousel[mActive] = !mCarousel[mActive];
            carouselCamToggle.setText(mCarousel[mActive]
                    ? "Carousel: YES" : "Carousel: NO");
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putBoolean("carousel_" + mActive, mCarousel[mActive]).apply();
        });

        TextView quadCamToggle = createItem(mQuad[mActive] ? "Quad: YES" : "Quad: NO");
        header.addView(quadCamToggle);
        quadCamToggle.setOnClickListener(v -> {
            mQuad[mActive] = !mQuad[mActive];
            quadCamToggle.setText(mQuad[mActive] ? "Quad: YES" : "Quad: NO");
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putBoolean("quad_" + mActive, mQuad[mActive]).apply();
        });

        final TextView[] camButtons = new TextView[CAM_COUNT];
        for (int i = 0; i < CAM_COUNT; i++) {
            final int slot = i;
            camButtons[i] = createItem(String.valueOf(i + 1));
            camButtons[i].setGravity(Gravity.CENTER);
            camButtons[i].setPadding(dp(4), dp(4), dp(4), dp(4));
            camRow.addView(camButtons[i], new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            if (i == mActive) highlightItem(camButtons[i]);

            camButtons[i].setOnClickListener(v -> {
                if (slot == mActive) return;
                if (carouselEnabled) stopCarousel();
                mActive = slot;
                for (int j = 0; j < CAM_COUNT; j++) {
                    if (j == mActive) highlightItem(camButtons[j]);
                    else resetItem(camButtons[j]);
                }
                host.setText(mHosts[mActive]);
                host.setSelection(host.getText().length());
                typeToggle.setText(mTypes[mActive] ? "Transport: UDP" : "Transport: TCP");
                carouselCamToggle.setText(mCarousel[mActive]
                        ? "Carousel: YES" : "Carousel: NO");
                quadCamToggle.setText(mQuad[mActive] ? "Quad: YES" : "Quad: NO");
                saveSettings();
            });
        }

        LinearLayout carouselPanel = new LinearLayout(this);
        carouselPanel.setOrientation(LinearLayout.VERTICAL);
        carouselPanel.setVisibility(View.GONE);

        TextView carouselToggle = createItem(carouselEnabled
                ? "Carousel: ON" : "Carousel: OFF");

        LinearLayout intervalRow = new LinearLayout(this);
        intervalRow.setOrientation(LinearLayout.HORIZONTAL);
        intervalRow.setGravity(Gravity.CENTER_VERTICAL);
        carouselPanel.addView(intervalRow);

        TextView intervalLabel = createItem("Interval:");
        intervalLabel.setPadding(dp(8), dp(6), dp(4), dp(6));
        intervalRow.addView(intervalLabel);

        EditText intervalEdit = new EditText(this);
        intervalEdit.setText(String.valueOf(carouselInterval));
        intervalEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        intervalEdit.setTextColor(Color.WHITE);
        intervalEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        intervalEdit.setPadding(dp(4), dp(6), dp(8), dp(6));
        intervalEdit.setMaxLines(1);
        intervalEdit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        intervalRow.addView(intervalEdit, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Runnable applyInterval = () -> {
            try {
                int val = Integer.parseInt(intervalEdit.getText().toString().trim());
                carouselInterval = Math.max(CAROUSEL_MIN_SEC, Math.min(CAROUSEL_MAX_SEC, val));
            } catch (NumberFormatException ignored) {
                carouselInterval = CAROUSEL_DEFAULT_SEC;
            }
            intervalEdit.setText(String.valueOf(carouselInterval));
            saveCarouselPrefs();
            if (carouselEnabled) {
                carouselHandler.removeCallbacks(carouselRunnable);
                carouselHandler.postDelayed(carouselRunnable, carouselInterval * 1000L);
            }
        };

        intervalEdit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN)) {
                applyInterval.run();
                return true;
            }
            return false;
        });

        GradientDrawable intervalBorder = new GradientDrawable();
        intervalBorder.setColor(Color.BLACK);
        intervalBorder.setStroke(1, Color.GRAY);
        intervalEdit.setBackground(intervalBorder);
        intervalEdit.setOnFocusChangeListener((v, hasFocus) -> {
            intervalBorder.setStroke(1, hasFocus ? Color.BLUE : Color.GRAY);
            v.setBackground(intervalBorder);
            if (!hasFocus) applyInterval.run();
        });

        carouselToggle.setOnClickListener(v -> {
            boolean show = carouselPanel.getVisibility() != View.VISIBLE;
            carouselPanel.setVisibility(show ? View.VISIBLE : View.GONE);
            if (!show) {
                if (carouselEnabled) stopCarousel();
                else startCarousel();
                carouselToggle.setText(carouselEnabled
                        ? "Carousel: ON" : "Carousel: OFF");
            }
        });

        TextView webui = createItem("WebUI");
        layout.addView(webui);
        webui.setOnClickListener(v -> {
            startBrowser();
            popup.dismiss();
        });

        layout.addView(carouselToggle);
        layout.addView(carouselPanel);

        TextView quadToggle = createItem(quadEnabled ? "Quadrator: ON" : "Quadrator: OFF");
        layout.addView(quadToggle);
        quadToggle.setOnClickListener(v -> {
            popup.dismiss();
            if (quadEnabled) stopQuad(); else startQuad();
        });

        // Web Settings button
        TextView webSettings = createItem("🌐 Web Settings");
        layout.addView(webSettings);
        webSettings.setOnClickListener(v -> {
            popup.dismiss();
            openWebSettings();
        });

        String code = "Exit [V" + mVersion + "]";

        SpannableString s = new SpannableString(code);
        s.setSpan(new SuperscriptSpan(), 5, s.length(), 0);
        s.setSpan(new RelativeSizeSpan(0.5f), 5, s.length(), 0);

        View divider = new View(this);
        divider.setBackgroundColor(Color.DKGRAY);
        layout.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        TextView exit = createItem("Exit");
        layout.addView(exit);
        exit.setText(s);
        exit.setOnClickListener(v -> finishAndRemoveTask());

        settings.setOnClickListener(v -> {
            boolean closing = header.getVisibility() == View.VISIBLE;
            header.setVisibility(closing ? View.GONE : View.VISIBLE);
            settings.setVisibility(closing ? View.VISIBLE : View.GONE);
            camRow.setVisibility(closing ? View.VISIBLE : View.GONE);
            webui.setVisibility(closing ? View.VISIBLE : View.GONE);
            carouselToggle.setVisibility(closing ? View.VISIBLE : View.GONE);
            carouselPanel.setVisibility(View.GONE);
            quadToggle.setVisibility(closing ? View.VISIBLE : View.GONE);
            webSettings.setVisibility(closing ? View.VISIBLE : View.GONE);
            divider.setVisibility(closing ? View.VISIBLE : View.GONE);
            exit.setVisibility(closing ? View.VISIBLE : View.GONE);
            popup.update(closing ? LinearLayout.LayoutParams.WRAP_CONTENT
                    : LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        });
    }

    private void openWebSettings() {
        try {
            java.net.NetworkInterface networkInterface = java.net.NetworkInterface.getByName("wlan0");
            if (networkInterface == null) {
                networkInterface = java.net.NetworkInterface.getByName("eth0");
            }
            if (networkInterface != null) {
                java.util.Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr.getAddress().length == 4) {
                        String ip = addr.getHostAddress();
                        Intent browserIntent = new Intent(Intent.ACTION_VIEW,
                                Uri.parse("http://" + ip + ":8080"));
                        startActivity(browserIntent);
                        return;
                    }
                }
            }
            android.widget.Toast.makeText(this,
                    "Cannot get IP address. Make sure Wi-Fi is connected.",
                    android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Cannot open web settings: " + e.getMessage());
            android.widget.Toast.makeText(this,
                    "Error: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private TextView createItem(String title) {
        TextView text = new TextView(this);
        text.setText(title);
        text.setPadding(dp(8), dp(6), dp(8), dp(6));
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        focusChange(text);
        return text;
    }

    private void highlightItem(TextView item) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.BLACK);
        bg.setStroke(2, Color.BLUE);
        item.setBackground(bg);
        item.setTextColor(Color.CYAN);
    }

    private void resetItem(TextView item) {
        item.setTextColor(Color.WHITE);
        focusChange(item);
    }

    private EditText createEdit(String title) {
        EditText text = new EditText(this);
        text.setText(title);
        text.setPadding(dp(8), dp(8), dp(8), dp(8));
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        text.setMaxLines(1);
        text.setImeOptions(EditorInfo.IME_ACTION_DONE);
        text.setSelection(0);
        focusChange(text);
        return text;
    }

    private void focusChange(View item) {
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.BLACK);
        border.setStroke(1, Color.GRAY);

        item.setBackground(border);
        item.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                border.setStroke(1, Color.BLUE);
            } else {
                border.setStroke(1, Color.GRAY);
            }
            v.setBackground(border);
        });
    }

    private int dp(float dp) {
        return (int) (dp * mDensity + 0.5f);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void startBrowser() {
        String link = Uri.parse(mHost).getHost();
        if (link == null) {
            Log.w(TAG, "Cannot open WebUI: invalid host in URL");
            return;
        }

        WebView view = new WebView(this);
        view.getSettings().setJavaScriptEnabled(true);

        view.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedHttpAuthRequest(
                    WebView view, HttpAuthHandler handler, String host, String realm) {
                String userInfo = Uri.parse(mHost).getUserInfo();
                if (userInfo != null) {
                    String[] part = userInfo.split(":", 2);
                    if (part.length == 2) {
                        handler.proceed(part[0], part[1]);
                        return;
                    }
                }
                handler.cancel();
            }
        });

        Log.d(TAG, "WebView: " + link);
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(view);
        dialog.setCanceledOnTouchOutside(true);

        int screenWidth;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            screenWidth = (int) (getWindowManager().getCurrentWindowMetrics()
                    .getBounds().width() * 0.75);
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(dm);
            screenWidth = (int) (dm.widthPixels * 0.75);
        }
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(screenWidth, WindowManager.LayoutParams.MATCH_PARENT);
        }
        dialog.show();
        dialog.setOnDismissListener(d -> view.destroy());
        mBrowserDialog = dialog;

        view.loadUrl("http://" + link);
    }

    private void updateResolution(int width, int height) {
        if (width < 64 || height < 64) {
            return;
        }

        Log.d(TAG, "Resolution update: " + width + "x" + height);
        runOnUiThread(() -> {
            int surfaceH = mSurface.getHeight();
            if (surfaceH == 0) surfaceH = getResources().getDisplayMetrics().heightPixels;
            int surfaceW = (int) ((float) surfaceH / height * width);
            Log.d(TAG, "Set surface: " + surfaceW + "x" + surfaceH);

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(surfaceW, surfaceH);
            params.gravity = Gravity.CENTER;
            mSurface.setLayoutParams(params);
        });
    }

    private void applyZoomTransform() {
        Matrix matrix = new Matrix();
        float cx = mSurface.getWidth() / 2f;
        float cy = mSurface.getHeight() / 2f;
        matrix.postScale(mZoomScale, mZoomScale, cx, cy);
        matrix.postTranslate(mPanX, mPanY);
        mSurface.setTransform(matrix);
    }

    private void clampPan() {
        float maxX = (mSurface.getWidth() * (mZoomScale - 1)) / 2f;
        float maxY = (mSurface.getHeight() * (mZoomScale - 1)) / 2f;
        mPanX = Math.max(-maxX, Math.min(maxX, mPanX));
        mPanY = Math.max(-maxY, Math.min(maxY, mPanY));
    }

    private void resetZoom() {
        mZoomScale = ZOOM_MIN;
        mPanX = 0f;
        mPanY = 0f;
        applyZoomTransform();
    }

    private void replaceSurface(Surface next) {
        Surface prev = mVideoSurface;
        mVideoSurface = next;
        if (prev != null) prev.release();
    }

    private int getFragment(byte data) {
        return codecH265 ? (data >> 1) & 0x3F : data & 0x1F;
    }

    private Frame buildFrame(Frame frame) {
        byte[] rxBuffer = frame.data();
        int rxSize = frame.length();
        int cpSize = 12;
        rxSize -= cpSize;

        if (rxSize <= 0) {
            Log.w(TAG, "RTP payload too short: " + frame.length());
            return null;
        }

        int staBit;
        int endBit;
        int nalBit = 4;

        if (lastCodec != codecH265) {
            lastCodec = codecH265;
            nalSize = 0;
            nalQueue.clear();
            closeDecoder();
            Log.d(TAG, "Set codec to " + (codecH265 ? "H265" : "H264"));
        }

        int fragment = getFragment(rxBuffer[cpSize]);
        if (fragment == RTP_FU_H264 || fragment == RTP_FU_H265) {
            int minPayload = codecH265 ? 3 : 2;
            if (rxSize < minPayload) {
                Log.w(TAG, "FU header too short: " + rxSize);
                return null;
            }

            if (codecH265) {
                staBit = rxBuffer[cpSize + 2] & 0x80;
                endBit = rxBuffer[cpSize + 2] & 0x40;
                int type = (rxBuffer[cpSize] & 0x81) | (rxBuffer[cpSize + 2] & 0x3F) << 1;
                nalBuffer[4] = (byte) type;
                nalBuffer[5] = 1;

                nalBit++;
                cpSize++;
                rxSize--;
            } else {
                staBit = rxBuffer[cpSize + 1] & 0x80;
                endBit = rxBuffer[cpSize + 1] & 0x40;
                int type = (rxBuffer[cpSize] & 0xE0) | (rxBuffer[cpSize + 1] & 0x1F);
                nalBuffer[4] = (byte) type;
            }

            cpSize++;
            rxSize--;

            if (staBit > 0) {
                nalBuffer[0] = 0;
                nalBuffer[1] = 0;
                nalBuffer[2] = 0;
                nalBuffer[3] = 1;

                nalBit++;
                cpSize++;
                rxSize--;

                if (nalBit + rxSize > nalBuffer.length) {
                    Log.e(TAG, "NAL start fragment too large, dropping");
                    nalSize = 0;
                    return null;
                }
                System.arraycopy(rxBuffer, cpSize, nalBuffer, nalBit, rxSize);
                nalSize = rxSize + nalBit;

                if (endBit > 0) {
                    byte[] output = new byte[nalSize];
                    System.arraycopy(nalBuffer, 0, output, 0, nalSize);
                    return new Frame(output, nalSize);
                }
            } else {
                cpSize++;
                rxSize--;

                if (nalSize + rxSize > nalBuffer.length) {
                    Log.e(TAG, "NAL buffer overflow, dropping frame");
                    nalSize = 0;
                    return null;
                }
                System.arraycopy(rxBuffer, cpSize, nalBuffer, nalSize, rxSize);
                nalSize += rxSize;

                if (endBit > 0) {
                    byte[] output = new byte[nalSize];
                    System.arraycopy(nalBuffer, 0, output, 0, nalSize);
                    return new Frame(output, nalSize);
                }
            }
        } else {
            nalBuffer[0] = 0;
            nalBuffer[1] = 0;
            nalBuffer[2] = 0;
            nalBuffer[3] = 1;

            if (nalBit + rxSize > nalBuffer.length) {
                Log.e(TAG, "Single NAL too large (" + rxSize + " bytes), dropping");
                return null;
            }
            System.arraycopy(rxBuffer, cpSize, nalBuffer, nalBit, rxSize);
            nalSize = rxSize + nalBit;

            byte[] output = new byte[nalSize];
            System.arraycopy(nalBuffer, 0, output, 0, nalSize);
            return new Frame(output, nalSize);
        }

        return null;
    }

    private void playAudio(Frame data) {
        AudioTrack track = audioTrack;
        if (track == null) {
            if (!audioFailed) {
                createAudio();
            }
        } else {
            byte[] buf = data.data();
            int offset = 0;
            int remaining = data.length();
            while (remaining > 0) {
                AudioTrack t = audioTrack;
                if (t == null) break;
                int written = t.write(buf, offset, remaining);
                if (written < 0) {
                    Log.e(TAG, "AudioTrack.write() error: " + written);
                    break;
                }
                offset += written;
                remaining -= written;
            }
        }
    }

    private void processAudio(Frame frame) {
        int header = 12;
        int length = frame.length() - header;
        if (length <= 0) {
            return;
        }

        byte[] queued = new byte[length];
        System.arraycopy(frame.data(), header, queued, 0, length);
        if (audioBigEndian) {
            for (int i = 0; i + 1 < length; i += 2) {
                byte tmp = queued[i];
                queued[i] = queued[i + 1];
                queued[i + 1] = tmp;
            }
        }

        if (!pcmQueue.offer(new Frame(queued, length))) {
            Log.w(TAG, "Audio queue full, frame dropped");
        }
    }

    private void createAudio() {
        int sample = audioSampleRate;
        int format = AudioFormat.CHANNEL_OUT_MONO;

        Log.d(TAG, "Create audio decoder (" + sample + "hz)");
        int size = AudioTrack.getMinBufferSize(sample, format, AudioFormat.ENCODING_PCM_16BIT);
        if (size <= 0) {
            Log.e(TAG, "Invalid audio parameters: sample=" + sample + " bufSize=" + size);
            audioFailed = true;
            return;
        }

        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sample)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack failed to initialize, releasing");
            track.release();
            audioFailed = true;
            return;
        }

        audioTrack = track;
        try {
            track.play();
        } catch (Exception e) {
            audioTrack = null;
            track.release();
            Log.e(TAG, "AudioTrack.play() failed", e);
            audioFailed = true;
        }
    }

    private void closeAudio() {
        AudioTrack track = audioTrack;
        if (track != null) {
            audioTrack = null;
            Log.i(TAG, "Close audio decoder");
            try {
                track.stop();
                track.release();
            } catch (Exception e) {
                Log.e(TAG, "Audio close exception", e);
            }
        }
        audioFailed = false;
    }

    private void decodeFrame(Frame buffer) {
        if (buffer.length() < 5) {
            Log.w(TAG, "NAL frame too short: " + buffer.length());
            return;
        }

        lastFrame = SystemClock.elapsedRealtime();

        int flag = 0;
        int fragment = getFragment(buffer.data()[4]);
        boolean isConfigNal = codecH265
                ? (fragment == H265_NAL_VPS || fragment == H265_NAL_SPS || fragment == H265_NAL_PPS)
                : (fragment == H264_NAL_SPS || fragment == H264_NAL_PPS);
        if (isConfigNal) {
            flag = MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
        }

        boolean needCreate = false;
        synchronized (decoderLock) {
            MediaCodec codec = mDecoder;
            if (codec == null) {
                needCreate = !decoderFailed;
            } else {
                try {
                    int inputBufferId = codec.dequeueInputBuffer(5_000);
                    if (inputBufferId >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferId);
                        if (inputBuffer != null) {
                            inputBuffer.clear();
                            inputBuffer.put(buffer.data(), 0, buffer.length());
                            codec.queueInputBuffer(inputBufferId, 0,
                                    buffer.length(), System.nanoTime() / 1000, flag);
                        }
                    }

                    MediaCodec.BufferInfo info = mBufferInfo;
                    int outputBufferId;
                    while ((outputBufferId = codec.dequeueOutputBuffer(info, 0)) >= 0
                            || outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            MediaFormat format = codec.getOutputFormat();
                            int mWidth = format.getInteger(MediaFormat.KEY_WIDTH);
                            int mHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
                            if (lastWidth != mWidth || lastHeight != mHeight) {
                                lastWidth = mWidth;
                                lastHeight = mHeight;
                                updateResolution(lastWidth, lastHeight);
                            }
                        } else {
                            codec.releaseOutputBuffer(outputBufferId, true);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Codec exception: " + e.getMessage());
                    mDecoder = null;
                    decoderFailed = true;
                    try { codec.stop(); } catch (Exception ignored) {}
                    try { codec.release(); } catch (Exception ignored) {}
                }
            }
        }
        if (needCreate) createDecoder();
    }

    private void createDecoder() {
        synchronized (decoderLock) {
            if (mDecoder != null) return;
        }

        Surface mVideo = mVideoSurface;
        if (mVideo == null || !mVideo.isValid()) {
            return;
        }

        String type = codecH265 ? "video/hevc" : "video/avc";

        MediaFormat format = MediaFormat.createVideoFormat(type, 1280, 720);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024);

        MediaCodec local;
        try {
            Log.i(TAG, "Start video decoder");
            local = MediaCodec.createDecoderByType(type);
            try {
                local.configure(format, mVideo, null, 0);
                local.start();
            } catch (Exception e) {
                local.release();
                throw e;
            }
        } catch (Exception e) {
            Log.e(TAG, "Cannot setup decoder: " + e.getMessage());
            decoderFailed = true;
            return;
        }

        synchronized (decoderLock) {
            if (mDecoder != null) {
                local.release();
                return;
            }
            mDecoder = local;
        }
        lastFrame = SystemClock.elapsedRealtime();
        updateResolution(lastWidth, lastHeight);
    }

    private void closeDecoder() {
        synchronized (decoderLock) {
            MediaCodec codec = mDecoder;
            if (codec == null) { decoderFailed = false; return; }
            mDecoder = null;
            Log.i(TAG, "Close video decoder");
            try {
                codec.stop();
                codec.release();
            } catch (Exception e) {
                Log.e(TAG, "Decoder close exception", e);
            }
            decoderFailed = false;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return sb.toString();
            if (c != '\r') {
                if (sb.length() >= 8192) throw new IOException("RTSP header line too long");
                sb.append((char) c);
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static String readRtspResponse(InputStream in, String targetHeader) throws IOException {
        String status = readLine(in);
        if (status == null) throw new IOException("Server closed connection during handshake");
        Log.i(TAG, status);

        String[] parts = status.split(" ", 3);
        if (parts.length < 2) throw new IOException("Malformed RTSP response: " + status);
        try {
            int code = Integer.parseInt(parts[1]);
            if (code < 200 || code >= 300) throw new IOException("RTSP error: " + status.trim());
        } catch (NumberFormatException e) {
            throw new IOException("Malformed RTSP status code: " + status);
        }

        String found = null;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            Log.i(TAG, line);
            if (targetHeader != null && line.toLowerCase(Locale.ROOT)
                    .startsWith(targetHeader.toLowerCase(Locale.ROOT))) {
                found = line.substring(targetHeader.length()).split(";")[0].trim();
            }
        }
        return found;
    }

    private String[] parseSdp(String sdp, String baseUrl) {
        String base = baseUrl;
        String[] controls = { null, null };
        int track = -1;

        for (String line : sdp.split("[\r\n]+")) {
            if (line.startsWith("m=video")) {
                track = 0;
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    try {
                        int pt = Integer.parseInt(parts[3]);
                        codecH265 = (pt == RTP_PT_H265);
                        Log.d(TAG, "SDP video codec: " + (codecH265 ? "H.265" : "H.264")
                                + " (PT=" + pt + ")");
                    } catch (NumberFormatException ignored) {}
                }
            } else if (line.startsWith("m=audio")) {
                track = 1;
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    try {
                        audioPt = Integer.parseInt(parts[3]);
                        Log.d(TAG, "SDP audio PT: " + audioPt);
                    } catch (NumberFormatException ignored) {}
                }
            } else if (line.startsWith("a=control:")) {
                String ctrl = line.substring("a=control:".length()).trim();
                if (track == -1) {
                    base = ctrl.startsWith("rtsp://") ? ctrl : baseUrl + "/" + ctrl;
                } else {
                    controls[track] = ctrl.startsWith("rtsp://") ? ctrl : base + "/" + ctrl;
                }
            } else if (audioPt >= 0 && line.startsWith("a=rtpmap:" + audioPt + " ")) {
                String encoding = line.substring(("a=rtpmap:" + audioPt + " ").length());
                audioBigEndian = encoding.toUpperCase(Locale.ROOT).startsWith("L16");
                int slash = encoding.indexOf('/');
                if (slash >= 0) {
                    int end = encoding.indexOf('/', slash + 1);
                    String rateStr = (end >= 0
                            ? encoding.substring(slash + 1, end)
                            : encoding.substring(slash + 1)).trim();
                    try {
                        int rate = Integer.parseInt(rateStr);
                        if (rate > 0 && rate <= 192000) {
                            audioSampleRate = rate;
                            Log.d(TAG, "Audio: " + encoding.split("/")[0]
                                    + " " + rate + " Hz, BE=" + audioBigEndian);
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        if (controls[0] == null) controls[0] = base + "/trackID=0";
        if (controls[1] == null) controls[1] = base + "/trackID=1";
        return controls;
    }

    private void rtspConnect() throws Exception {
        nalSize = 0;
        lastUnknownPayload = -1;
        String currentHost = mHost;
        if (currentHost == null || currentHost.isEmpty()) {
            throw new IOException("Camera slot not configured");
        }
        Uri uri = Uri.parse(currentHost);
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IOException("Invalid RTSP URL: host is missing or empty");
        }
        try (Socket s = new Socket()) {
            int port = uri.getPort();
            s.connect(new InetSocketAddress(host, port < 0 ? 554 : port), 1000);
            s.setSoTimeout(1000);
            InputStream input = s.getInputStream();
            OutputStream w = s.getOutputStream();

            Log.d(TAG, "Start rtsp connection");

            String user = uri.getUserInfo();
            String auth = "";
            if (user != null) {
                auth = "Authorization: Basic " +
                        Base64.encodeToString(user.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP) + "\r\n";
            }
            String path = uri.getEncodedPath();
            String query = uri.getEncodedQuery();
            String rtspUrl = uri.getScheme() + "://" + host
                    + (port >= 0 ? ":" + port : "")
                    + (path != null ? path : "")
                    + (query != null ? "?" + query : "");

            int seq = 1;
            String desc = "DESCRIBE " + rtspUrl + " RTSP/1.0\r\n" +
                    "CSeq: " + seq + "\r\n" + auth + mUserAgent + "Accept: application/sdp\r\n\r\n";
            w.write(desc.getBytes(StandardCharsets.UTF_8));
            w.flush();

            String contentLenStr = readRtspResponse(input, "Content-Length:");
            int sdpBodyLen = 0;
            if (contentLenStr != null) {
                try { sdpBodyLen = Integer.parseInt(contentLenStr); }
                catch (NumberFormatException ignored) {}
            }
            StringBuilder sdp = new StringBuilder();
            byte[] skipBuf = new byte[512];
            while (sdpBodyLen > 0) {
                int n = input.read(skipBuf, 0, Math.min(sdpBodyLen, skipBuf.length));
                if (n <= 0) break;
                if (sdp.length() < 4096)
                    sdp.append(new String(skipBuf, 0, n, StandardCharsets.UTF_8));
                sdpBodyLen -= n;
            }
            String[] trackUrls = parseSdp(sdp.toString(), rtspUrl);

            DatagramSocket udpVideo = null;
            DatagramSocket udpAudio = null;
            if (mType) {
                udpVideo = new DatagramSocket(null);
                udpVideo.setReuseAddress(true);
                udpVideo.bind(new InetSocketAddress(5000));
                udpAudio = new DatagramSocket(null);
                udpAudio.setReuseAddress(true);
                udpAudio.bind(new InetSocketAddress(5002));
            }
            try {
                seq++;
                String type = mType
                        ? "RTP/AVP/UDP;unicast;client_port=5000-5001"
                        : "RTP/AVP/TCP;unicast;interleaved=0-1";
                String video = "SETUP " + trackUrls[0] + " RTSP/1.0\r\n" +
                        "CSeq: " + seq + "\r\n" + auth + mUserAgent + "Transport: " + type + "\r\n\r\n";
                w.write(video.getBytes(StandardCharsets.UTF_8));
                w.flush();

                String session = readRtspResponse(input, "Session:");
                if (session == null) {
                    throw new IOException("RTSP server did not return a Session header");
                }
                session = session.replaceAll("[\r\n]", "");

                seq++;
                type = mType
                        ? "RTP/AVP/UDP;unicast;client_port=5002-5003"
                        : "RTP/AVP/TCP;unicast;interleaved=2-3";
                String audio = "SETUP " + trackUrls[1] + " RTSP/1.0\r\n" +
                        "CSeq: " + seq + "\r\n" + auth + mUserAgent + "Transport: " + type + "\r\n" +
                        "Session: " + session + "\r\n\r\n";
                w.write(audio.getBytes(StandardCharsets.UTF_8));
                w.flush();

                readRtspResponse(input, null);

                seq++;
                String play = "PLAY " + rtspUrl + " RTSP/1.0\r\n" +
                        "CSeq: " + seq + "\r\n" + auth + mUserAgent + "Session: " + session + "\r\n\r\n";
                w.write(play.getBytes(StandardCharsets.UTF_8));
                w.flush();

                readRtspResponse(input, null);

                s.setSoTimeout(0);
                lastFrame = SystemClock.elapsedRealtime();
                activeStream = true;
                createDecoder();
                try {
                    if (mType) {
                        mUdpSocket = udpVideo;
                        mUdpAudioSocket = udpAudio;
                        final DatagramSocket audioSock = udpAudio;
                        try {
                            Thread audioRx = new Thread(() -> {
                                try { udpStream(audioSock); } catch (IOException ignored) {}
                            }, "rtsp-udp-audio");
                            audioRx.setDaemon(true);
                            audioRx.start();
                            udpStream(udpVideo);
                            audioSock.close();
                            try { audioRx.join(2000); } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            }
                        } finally {
                            mUdpSocket = null;
                            mUdpAudioSocket = null;
                        }
                    } else {
                        mTcpSocket = s;
                        try {
                            tcpStream(input);
                        } finally {
                            mTcpSocket = null;
                        }
                    }
                } finally {
                    try {
                        String teardown = "TEARDOWN " + rtspUrl + " RTSP/1.0\r\n" +
                                "CSeq: " + (seq + 1) + "\r\n" + auth + mUserAgent +
                                "Session: " + session + "\r\n\r\n";
                        w.write(teardown.getBytes(StandardCharsets.UTF_8));
                        w.flush();
                        Log.d(TAG, "RTSP TEARDOWN sent");
                    } catch (Exception ignored) {}
                }
            } finally {
                if (udpVideo != null) try { udpVideo.close(); } catch (Exception ignored) {}
                if (udpAudio != null) try { udpAudio.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void udpStream(DatagramSocket d) throws IOException {
        d.setSoTimeout(1000);
        byte[] buf = new byte[65535];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (activeStream) {
            try {
                d.receive(packet);
            } catch (SocketTimeoutException ignored) {
                continue;
            }
            byte[] copy = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), 0, copy, 0, packet.getLength());
            processPacket(new Frame(copy, packet.getLength()));
        }
    }

    private void tcpStream(InputStream rawInput) throws IOException {
        BufferedInputStream input = new BufferedInputStream(rawInput, 65536);
        byte[] pktBuf = new byte[65535];
        while (activeStream) {
            int b = input.read();
            if (b == -1) {
                activeStream = false;
                break;
            }
            if (b != 0x24) continue;

            int channel = input.read();
            int hi = input.read();
            int lo = input.read();
            if (channel == -1 || hi == -1 || lo == -1) {
                activeStream = false;
                break;
            }
            int len = (hi << 8) | lo;
            if (len <= 0 || len > pktBuf.length) {
                Log.w(TAG, "Invalid RTSP interleaved packet length: " + len);
                continue;
            }

            int read = 0;
            while (read < len) {
                int n = input.read(pktBuf, read, len - read);
                if (n == -1) throw new IOException("stream truncated mid-packet");
                read += n;
            }

            if (channel == 0 || channel == 2) {
                byte[] copy = new byte[len];
                System.arraycopy(pktBuf, 0, copy, 0, len);
                processPacket(new Frame(copy, len));
            }
        }
    }

    private void processPacket(Frame frame) {
        if (frame.length() < 12) {
            Log.w(TAG, "RTP packet too short: " + frame.length());
            return;
        }
        byte[] data = frame.data();

        int cc = data[0] & 0x0F;
        boolean hasExt = (data[0] & 0x10) != 0;
        if (cc != 0 || hasExt) {
            Log.w(TAG, "Unsupported RTP header: CC=" + cc + " X=" + (hasExt ? 1 : 0) + ", dropping");
            return;
        }

        int payload = (data[1] & 0x7F);
        if (payload == audioPt) {
            processAudio(frame);
            return;
        } else if (payload == RTP_PT_H265 || payload == RTP_PT_H264) {
            codecH265 = payload == RTP_PT_H265;
            Frame output = buildFrame(frame);
            if (output != null) {
                if (!nalQueue.offer(output)) {
                    Log.w(TAG, "Video queue full, frame dropped");
                }
            }
            return;
        }

        if (payload != lastUnknownPayload) {
            lastUnknownPayload = payload;
            Log.w(TAG, "Unknown rtp type: " + payload);
        }
    }

    private void startListener() {
        Log.i(TAG, "Start network listener");

        final int gen = listenerGen;

        executor = Executors.newFixedThreadPool(4);

        executor.execute(() -> {
            Thread.currentThread().setName("rtsp-network");
            int retryDelay = 1000;
            while (gen == listenerGen) {
                try {
                    if (!activeStream) {
                        rtspConnect();
                        retryDelay = 1000;
                        SystemClock.sleep(1000);
                    }
                } catch (Exception e) {
                    activeStream = false;
                    Log.w(TAG, "Cannot connect rtsp: " + e.getMessage());
                    SystemClock.sleep(retryDelay);
                    retryDelay = Math.min(retryDelay * 2, 8000);
                }
            }
        });

        executor.execute(() -> {
            Thread.currentThread().setName("rtsp-watchdog");
            while (gen == listenerGen) {
                if (activeStream && lastFrame > 0
                        && SystemClock.elapsedRealtime() - lastFrame > WATCHDOG_MS) {
                    Log.w(TAG, "Stream is inactive");
                    activeStream = false;
                    Socket tcp = mTcpSocket;
                    if (tcp != null) try { tcp.close(); } catch (Exception ignored) {}
                }
                SystemClock.sleep(1000);
            }
        });

        executor.execute(() -> {
            Thread.currentThread().setName("rtsp-video");
            while (gen == listenerGen) {
                try {
                    Frame buffer = nalQueue.poll(5, TimeUnit.MILLISECONDS);
                    if (buffer != null) {
                        decodeFrame(buffer);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Video decode error: " + e.getMessage());
                }
            }
        });

        executor.execute(() -> {
            Thread.currentThread().setName("rtsp-audio");
            while (gen == listenerGen) {
                try {
                    Frame buffer = pcmQueue.poll(5, TimeUnit.MILLISECONDS);
                    if (buffer != null) {
                        playAudio(buffer);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Audio playback error: " + e.getMessage());
                }
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        carouselHandler.removeCallbacks(carouselRunnable);
        Dialog browser = mBrowserDialog;
        mBrowserDialog = null;
        if (browser != null && browser.isShowing()) browser.dismiss();
        if (quadCells != null) {
            for (QuadCell cell : quadCells) {
                if (cell != null) cell.stop();
            }
            quadCells = null;
        }
        if (listener) {
            listenerGen++;
            listener = false;
            activeStream = false;
            closeSockets();
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            closeDecoder();
            closeAudio();
            nalQueue.clear();
            pcmQueue.clear();
        }
        if (webServer != null) {
            webServer.stopServer();
            webServer = null;
            if (webServerThread != null) {
                webServerThread.interrupt();
                webServerThread = null;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        loadSettings();
        if (webServer == null) {
            startWebServer();
        }
        if (quadEnabled) {
            mSurface.setVisibility(View.GONE);
            quadContainer.setVisibility(View.VISIBLE);
            quadCells = new QuadCell[4];
            int count = 0;
            for (int i = 0; i < CAM_COUNT && count < 4; i++) {
                if (!mQuad[i]) continue;
                String url = mHosts[i];
                if (url == null || url.isEmpty() || url.equals(DEFAULT_URL)) continue;
                quadCells[count] = new QuadCell(count, url, quadViews[count]);
                quadCells[count].start();
                count++;
            }
        } else {
            mSurface.setVisibility(View.VISIBLE);
            quadContainer.setVisibility(View.GONE);
            if (!listener) {
                listener = true;
                startListener();
            }
            if (carouselEnabled) {
                carouselHandler.removeCallbacks(carouselRunnable);
                carouselHandler.postDelayed(carouselRunnable, carouselInterval * 1000L);
            }
        }
    }

    // Web server methods
    private void startWebServer() {
        if (webServer == null) {
            webServer = new WebServer(getSharedPreferences("settings", MODE_PRIVATE));
            webServer.setListener(this);
            webServerThread = new Thread(webServer);
            webServerThread.start();
            showWebServerAddress();
        }
    }

    private void showWebServerAddress() {
        try {
            java.net.NetworkInterface networkInterface = java.net.NetworkInterface.getByName("wlan0");
            if (networkInterface == null) {
                networkInterface = java.net.NetworkInterface.getByName("eth0");
            }
            if (networkInterface != null) {
                java.util.Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr.getAddress().length == 4) {
                        String ip = addr.getHostAddress();
                        Log.i(TAG, "========================================");
                        Log.i(TAG, "Web Interface available at:");
                        Log.i(TAG, "http://" + ip + ":8080");
                        Log.i(TAG, "========================================");
                        runOnUiThread(() -> {
                            android.widget.Toast.makeText(this,
                                    "🌐 WebUI: http://" + ip + ":8080",
                                    android.widget.Toast.LENGTH_LONG).show();
                        });
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Cannot get IP address: " + e.getMessage());
        }
    }

    public void onSettingsChanged() {
        Log.i(TAG, "Settings changed from web interface, reloading...");
        runOnUiThread(() -> {
            loadSettings();
            if (listener && !quadEnabled) {
                activeStream = false;
                closeSockets();
                closeDecoder();
                closeAudio();
                nalQueue.clear();
                pcmQueue.clear();
            }
            android.widget.Toast.makeText(this,
                    "Settings updated from WebUI.\nRestarting stream...",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Self-contained RTSP video-only player for one quadrant cell.
     */
    private class QuadCell {
        final String host;
        final TextureView view;
        final String tag;

        private volatile boolean running;
        private volatile boolean activeStream;
        private volatile long lastFrame;
        private volatile boolean codecH265;
        private boolean lastCodec;

        private volatile Surface surface;
        private volatile MediaCodec decoder;
        private boolean decoderFailed;
        private final Object decoderLock = new Object();

        private final BlockingQueue<Frame> nalQueue = new ArrayBlockingQueue<>(30);
        private final byte[] nalBuffer = new byte[512 * 1024];
        private int nalSize;
        private int lastUnknownPayload = -1;

        private volatile Socket tcpSocket;
        private ExecutorService executor;

        QuadCell(int index, String host, TextureView view) {
            this.host = host;
            this.view = view;
            this.tag = "Quad-" + index;
        }

        void start() {
            running = true;
            view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                    Surface old = surface;
                    surface = new Surface(st);
                    if (old != null) old.release();
                    startThreads();
                }
                @Override
                public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) {
                    Surface old = surface;
                    surface = new Surface(st);
                    if (old != null) old.release();
                }
                @Override
                public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                    Surface s = surface;
                    surface = null;
                    if (s != null) s.release();
                    return true;
                }
                @Override
                public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) {}
            });
            if (view.isAvailable()) {
                surface = new Surface(view.getSurfaceTexture());
                startThreads();
            }
        }

        private void startThreads() {
            if (executor != null) return;
            executor = Executors.newFixedThreadPool(3);

            executor.execute(() -> {
                Thread.currentThread().setName(tag + "-net");
                int retryDelay = 1000;
                while (running) {
                    try {
                        connect();
                        retryDelay = 1000;
                        SystemClock.sleep(1000);
                    } catch (Exception e) {
                        activeStream = false;
                        Log.w(TAG, tag + ": " + e.getMessage());
                        SystemClock.sleep(retryDelay);
                        retryDelay = Math.min(retryDelay * 2, 8000);
                    }
                }
            });

            executor.execute(() -> {
                Thread.currentThread().setName(tag + "-wd");
                while (running) {
                    if (activeStream && lastFrame > 0
                            && SystemClock.elapsedRealtime() - lastFrame > WATCHDOG_MS) {
                        activeStream = false;
                        Socket tcp = tcpSocket;
                        if (tcp != null) try { tcp.close(); } catch (Exception ignored) {}
                    }
                    SystemClock.sleep(1000);
                }
            });

            executor.execute(() -> {
                Thread.currentThread().setName(tag + "-dec");
                while (running) {
                    try {
                        Frame f = nalQueue.poll(5, TimeUnit.MILLISECONDS);
                        if (f != null) decode(f);
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        Log.e(TAG, tag + " decode: " + e.getMessage());
                    }
                }
            });
        }

        void stop() {
            running = false;
            activeStream = false;
            Socket tcp = tcpSocket;
            if (tcp != null) try { tcp.close(); } catch (Exception ignored) {}
            if (executor != null) { executor.shutdownNow(); executor = null; }
            synchronized (decoderLock) {
                MediaCodec c = decoder;
                if (c != null) {
                    decoder = null;
                    try { c.stop(); } catch (Exception ignored) {}
                    try { c.release(); } catch (Exception ignored) {}
                }
                decoderFailed = false;
            }
            nalQueue.clear();
            Surface s = surface;
            if (s != null) { surface = null; s.release(); }
        }

        private void connect() throws Exception {
            nalSize = 0;
            lastUnknownPayload = -1;
            if (host == null || host.isEmpty()) {
                SystemClock.sleep(5000);
                return;
            }

            Uri uri = Uri.parse(host);
            String h = uri.getHost();
            if (h == null || h.isEmpty()) throw new IOException("Invalid host");

            try (Socket s = new Socket()) {
                int port = uri.getPort();
                s.connect(new InetSocketAddress(h, port < 0 ? 554 : port), 5000);
                s.setSoTimeout(5000);

                InputStream input = s.getInputStream();
                OutputStream w = s.getOutputStream();

                String user = uri.getUserInfo();
                String auth = "";
                if (user != null) {
                    auth = "Authorization: Basic " +
                            Base64.encodeToString(user.getBytes(StandardCharsets.UTF_8),
                                    Base64.NO_WRAP) + "\r\n";
                }

                String path = uri.getEncodedPath();
                String query = uri.getEncodedQuery();
                String rtspUrl = uri.getScheme() + "://" + h
                        + (port >= 0 ? ":" + port : "")
                        + (path != null ? path : "")
                        + (query != null ? "?" + query : "");

                int seq = 1;
                w.write(("DESCRIBE " + rtspUrl + " RTSP/1.0\r\n" +
                        "CSeq: " + seq + "\r\n" + auth + mUserAgent +
                        "Accept: application/sdp\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                w.flush();

                String contentLen = readRtspResponse(input, "Content-Length:");
                int sdpLen = 0;
                if (contentLen != null) {
                    try { sdpLen = Integer.parseInt(contentLen); }
                    catch (NumberFormatException ignored) {}
                }
                StringBuilder sdpBuf = new StringBuilder();
                byte[] buf = new byte[512];
                while (sdpLen > 0) {
                    int n = input.read(buf, 0, Math.min(sdpLen, buf.length));
                    if (n <= 0) break;
                    if (sdpBuf.length() < 4096)
                        sdpBuf.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    sdpLen -= n;
                }

                String videoControl = null;
                String baseControl = rtspUrl;
                int section = -1;
                for (String line : sdpBuf.toString().split("[\r\n]+")) {
                    if (line.startsWith("m=video")) {
                        section = 0;
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 4) {
                            try { codecH265 = Integer.parseInt(parts[3]) == RTP_PT_H265; }
                            catch (NumberFormatException ignored) {}
                        }
                    } else if (line.startsWith("m=")) {
                        section = 1;
                    } else if (line.startsWith("a=control:")) {
                        String ctrl = line.substring("a=control:".length()).trim();
                        if (section == -1) {
                            baseControl = ctrl.startsWith("rtsp://")
                                    ? ctrl : rtspUrl + "/" + ctrl;
                        } else if (section == 0) {
                            videoControl = ctrl.startsWith("rtsp://")
                                    ? ctrl : baseControl + "/" + ctrl;
                        }
                    }
                }
                if (videoControl == null) videoControl = baseControl + "/trackID=0";

                seq++;
                w.write(("SETUP " + videoControl + " RTSP/1.0\r\n" +
                        "CSeq: " + seq + "\r\n" + auth + mUserAgent +
                        "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                w.flush();

                String session = readRtspResponse(input, "Session:");
                if (session == null) throw new IOException("No Session");
                session = session.replaceAll("[\r\n]", "");

                seq++;
                w.write(("PLAY " + rtspUrl + " RTSP/1.0\r\n" +
                        "CSeq: " + seq + "\r\n" + auth + mUserAgent +
                        "Session: " + session + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                w.flush();

                readRtspResponse(input, null);

                s.setSoTimeout(0);
                lastFrame = SystemClock.elapsedRealtime();
                activeStream = true;
                initDecoder();

                tcpSocket = s;
                try {
                    readTcp(input);
                } finally {
                    tcpSocket = null;
                    try {
                        w.write(("TEARDOWN " + rtspUrl + " RTSP/1.0\r\n" +
                                "CSeq: " + (seq + 1) + "\r\n" + auth + mUserAgent +
                                "Session: " + session + "\r\n\r\n")
                                .getBytes(StandardCharsets.UTF_8));
                        w.flush();
                    } catch (Exception ignored) {}
                }
            }
        }

        private void readTcp(InputStream rawInput) throws IOException {
            BufferedInputStream input = new BufferedInputStream(rawInput, 65536);
            byte[] pktBuf = new byte[65535];
            while (activeStream && running) {
                int b = input.read();
                if (b == -1) { activeStream = false; break; }
                if (b != 0x24) continue;

                int channel = input.read();
                int hi = input.read();
                int lo = input.read();
                if (channel == -1 || hi == -1 || lo == -1) { activeStream = false; break; }

                int len = (hi << 8) | lo;
                if (len <= 0 || len > pktBuf.length) continue;

                int read = 0;
                while (read < len) {
                    int n = input.read(pktBuf, read, len - read);
                    if (n == -1) throw new IOException("Truncated");
                    read += n;
                }

                if (channel == 0) {
                    byte[] copy = new byte[len];
                    System.arraycopy(pktBuf, 0, copy, 0, len);
                    handlePacket(new Frame(copy, len));
                }
            }
        }

        private void handlePacket(Frame frame) {
            if (frame.length() < 12) return;
            byte[] data = frame.data();
            if ((data[0] & 0x0F) != 0 || (data[0] & 0x10) != 0) return;

            int pt = data[1] & 0x7F;
            if (pt == RTP_PT_H265 || pt == RTP_PT_H264) {
                codecH265 = (pt == RTP_PT_H265);
                Frame output = assembleNal(frame);
                if (output != null) nalQueue.offer(output);
            } else if (pt != lastUnknownPayload) {
                lastUnknownPayload = pt;
                Log.w(TAG, tag + " unknown PT: " + pt);
            }
        }

        private int fragment(byte data) {
            return codecH265 ? (data >> 1) & 0x3F : data & 0x1F;
        }

        private Frame assembleNal(Frame frame) {
            byte[] rx = frame.data();
            int rxSize = frame.length();
            int cp = 12;
            rxSize -= cp;
            if (rxSize <= 0) return null;

            int nalBit = 4;

            if (lastCodec != codecH265) {
                lastCodec = codecH265;
                nalSize = 0;
                nalQueue.clear();
                synchronized (decoderLock) {
                    MediaCodec c = decoder;
                    if (c != null) {
                        decoder = null;
                        try { c.stop(); } catch (Exception ignored) {}
                        try { c.release(); } catch (Exception ignored) {}
                        decoderFailed = false;
                    }
                }
            }

            int frag = fragment(rx[cp]);
            if (frag == RTP_FU_H264 || frag == RTP_FU_H265) {
                int minPayload = codecH265 ? 3 : 2;
                if (rxSize < minPayload) return null;

                int staBit, endBit;
                if (codecH265) {
                    staBit = rx[cp + 2] & 0x80;
                    endBit = rx[cp + 2] & 0x40;
                    nalBuffer[4] = (byte) ((rx[cp] & 0x81) | (rx[cp + 2] & 0x3F) << 1);
                    nalBuffer[5] = 1;
                    nalBit++;
                    cp++;
                    rxSize--;
                } else {
                    staBit = rx[cp + 1] & 0x80;
                    endBit = rx[cp + 1] & 0x40;
                    nalBuffer[4] = (byte) ((rx[cp] & 0xE0) | (rx[cp + 1] & 0x1F));
                }

                cp++;
                rxSize--;

                if (staBit > 0) {
                    nalBuffer[0] = 0; nalBuffer[1] = 0; nalBuffer[2] = 0; nalBuffer[3] = 1;
                    nalBit++;
                    cp++;
                    rxSize--;
                    if (nalBit + rxSize > nalBuffer.length) { nalSize = 0; return null; }
                    System.arraycopy(rx, cp, nalBuffer, nalBit, rxSize);
                    nalSize = rxSize + nalBit;
                    if (endBit > 0) {
                        byte[] out = new byte[nalSize];
                        System.arraycopy(nalBuffer, 0, out, 0, nalSize);
                        return new Frame(out, nalSize);
                    }
                } else {
                    cp++;
                    rxSize--;
                    if (nalSize + rxSize > nalBuffer.length) { nalSize = 0; return null; }
                    System.arraycopy(rx, cp, nalBuffer, nalSize, rxSize);
                    nalSize += rxSize;
                    if (endBit > 0) {
                        byte[] out = new byte[nalSize];
                        System.arraycopy(nalBuffer, 0, out, 0, nalSize);
                        return new Frame(out, nalSize);
                    }
                }
            } else {
                nalBuffer[0] = 0; nalBuffer[1] = 0; nalBuffer[2] = 0; nalBuffer[3] = 1;
                if (nalBit + rxSize > nalBuffer.length) return null;
                System.arraycopy(rx, cp, nalBuffer, nalBit, rxSize);
                nalSize = rxSize + nalBit;
                byte[] out = new byte[nalSize];
                System.arraycopy(nalBuffer, 0, out, 0, nalSize);
                return new Frame(out, nalSize);
            }

            return null;
        }

        private void decode(Frame buffer) {
            if (buffer.length() < 5) return;
            lastFrame = SystemClock.elapsedRealtime();

            int flag = 0;
            int frag = fragment(buffer.data()[4]);
            boolean config = codecH265
                    ? (frag == H265_NAL_VPS || frag == H265_NAL_SPS || frag == H265_NAL_PPS)
                    : (frag == H264_NAL_SPS || frag == H264_NAL_PPS);
            if (config) flag = MediaCodec.BUFFER_FLAG_CODEC_CONFIG;

            boolean needCreate = false;
            synchronized (decoderLock) {
                MediaCodec c = decoder;
                if (c == null) {
                    needCreate = !decoderFailed;
                } else {
                    try {
                        int id = c.dequeueInputBuffer(5_000);
                        if (id >= 0) {
                            ByteBuffer in = c.getInputBuffer(id);
                            if (in != null) {
                                in.clear();
                                in.put(buffer.data(), 0, buffer.length());
                                c.queueInputBuffer(id, 0, buffer.length(),
                                        System.nanoTime() / 1000, flag);
                            }
                        }
                        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                        int oid;
                        while ((oid = c.dequeueOutputBuffer(info, 0)) >= 0
                                || oid == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (oid >= 0) c.releaseOutputBuffer(oid, true);
                        }
                    } catch (Exception e) {
                        decoder = null;
                        decoderFailed = true;
                        try { c.stop(); } catch (Exception ignored) {}
                        try { c.release(); } catch (Exception ignored) {}
                    }
                }
            }
            if (needCreate) initDecoder();
        }

        private void initDecoder() {
            synchronized (decoderLock) {
                if (decoder != null) return;
            }
            Surface s = surface;
            if (s == null || !s.isValid()) return;

            String type = codecH265 ? "video/hevc" : "video/avc";
            MediaFormat fmt = MediaFormat.createVideoFormat(type, 1280, 720);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024);

            MediaCodec local;
            try {
                local = MediaCodec.createDecoderByType(type);
                try {
                    local.configure(fmt, s, null, 0);
                    local.start();
                } catch (Exception e) {
                    local.release();
                    throw e;
                }
            } catch (Exception e) {
                Log.e(TAG, tag + " decoder init: " + e.getMessage());
                decoderFailed = true;
                return;
            }

            synchronized (decoderLock) {
                if (decoder != null) { local.release(); return; }
                decoder = local;
            }
            lastFrame = SystemClock.elapsedRealtime();
        }
    }

    private record Frame(byte[] data, int length) {
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.d(TAG, "Key pressed: " + keyCode);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                createMenu(findViewById(R.id.decoder));
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }
}
