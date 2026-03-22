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
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
import android.view.Surface;
import android.graphics.Matrix;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
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

public class Decoder extends Activity {
    private static final String TAG = "OpenIPCDecoder";
    private final BlockingQueue<Frame> nalQueue = new ArrayBlockingQueue<>(32);
    private final BlockingQueue<Frame> pcmQueue = new ArrayBlockingQueue<>(32);
    private final byte[] nalBuffer = new byte[1024 * 1024];

    private int nalSize;            // only touched on the network thread — no volatile needed
    private volatile MediaCodec mDecoder;
    // Surface captured on the UI thread via TextureView.SurfaceTextureListener; volatile so the
    // network thread can safely read it in createDecoder() without holding any UI lock
    private volatile Surface mVideoSurface;
    // guards all MediaCodec lifecycle operations (create / use / release) so that
    // closeDecoder() called from the network thread never races with decodeFrame()
    // called from the video thread
    private final Object decoderLock = new Object();
    private TextureView mSurface;

    // Pinch-to-zoom and pan state
    private ScaleGestureDetector mScaleDetector;
    private GestureDetector mGestureDetector;
    private float mZoomScale = 1.0f;
    private float mPanX = 0f;
    private float mPanY = 0f;
    private static final float ZOOM_MIN = 1.0f;
    private static final float ZOOM_MAX = 5.0f;
    private volatile AudioTrack audioTrack;
    private TextView mConnect;

    private volatile boolean codecH265;
    private boolean lastCodec; // only accessed on the network thread — volatile not needed
    private boolean listener;       // only accessed on the UI thread — no volatile needed
    private volatile boolean activeStream;
    private volatile int lastWidth;
    private volatile int lastHeight;
    private volatile long lastFrame;

    // incremented in onPause so that orphaned threads from the previous
    // lifecycle can detect they are stale and exit their loops
    private volatile int listenerGen;

    // set on audio init failure; cleared on session close to allow next retry
    private volatile boolean audioFailed;

    // set on codec init failure; prevents per-frame retry when codec is unsupported
    private volatile boolean decoderFailed;

    // held so onPause() can close them to unblock blocking read()/receive() on the network thread
    private volatile Socket mTcpSocket;
    private volatile DatagramSocket mUdpSocket;
    private volatile DatagramSocket mUdpAudioSocket;

    // pre-allocated to avoid per-frame GC pressure in the video decode loop
    private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

    // pre-allocated audio staging buffer: PCM frames are at most a few KB;
    // if a frame exceeds this size we fall back to a one-time heap allocation
    private static final int PCM_BUF_SIZE = 8192;
    private final byte[] pcmStagingBuf = new byte[PCM_BUF_SIZE];

    // read from the network thread, written from the UI thread
    private static final int CAM_COUNT = 4;
    private static final String DEFAULT_URL = "rtsp://root:12345@192.168.1.10:554/stream=0";
    private final String[] mHosts = new String[CAM_COUNT];
    private final boolean[] mTypes = new boolean[CAM_COUNT]; // false = TCP, true = UDP
    private int mActive; // only accessed on the UI thread — no volatile needed
    private volatile String mHost;
    private volatile boolean mType;
    private String mVersion = "1.0";
    private String mUserAgent = "User-Agent: OpenIPC-Decoder/1.0\r\n";

    // tracks last warned unknown RTP payload type to suppress log spam on the network thread
    private int lastUnknownPayload = -1; // only accessed on the network thread — no volatile needed

    // RTP fragmentation unit NAL types
    private static final int RTP_FU_H264  = 28;  // H.264 FU-A
    private static final int RTP_FU_H265  = 49;  // H.265 FU

    // RTP dynamic payload types as negotiated in the OpenIPC camera SDP
    private static final int RTP_PT_H265  = 97;  // H.265/HEVC video
    private static final int RTP_PT_H264  = 96;  // H.264/AVC video
    private static final int RTP_PT_PCMU_DEFAULT = 100; // fallback audio PT

    // Parameter-set NAL types used to set BUFFER_FLAG_CODEC_CONFIG on the decoder input
    private static final int H265_NAL_VPS = 32;  // Video Parameter Set
    private static final int H265_NAL_SPS = 33;  // Sequence Parameter Set
    private static final int H265_NAL_PPS = 34;  // Picture Parameter Set
    private static final int H264_NAL_SPS = 7;
    private static final int H264_NAL_PPS = 8;

    // inactivity threshold: reconnect if no RTP frame arrives within this period
    private static final long WATCHDOG_MS  = 3000;

    // audio clock rate parsed from SDP rtpmap; falls back to 8000 Hz if SDP is absent
    private volatile int audioSampleRate = 8000;

    // audio payload type parsed from SDP m=audio; defaults to the OpenIPC convention
    private volatile int audioPt = RTP_PT_PCMU_DEFAULT;

    // L16 encoding is big-endian per RFC 3551; set false for native-endian encodings
    private volatile boolean audioBigEndian = true;

    // reference kept so we can dismiss the dialog (and destroy the WebView) on rotation
    private Dialog mBrowserDialog; // only accessed on the UI thread — no volatile needed

    private ExecutorService executor; // only accessed on the UI thread — no volatile needed

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.decoder);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                mVersion = getPackageManager()
                        .getPackageInfo(getPackageName(), PackageManager.PackageInfoFlags.of(0))
                        .versionName;
            } else {
                //noinspection deprecation
                mVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            }
        } catch (PackageManager.NameNotFoundException ignored) {}
        mUserAgent = "User-Agent: OpenIPC-Decoder/" + mVersion + "\r\n";

        mSurface = findViewById(R.id.video_surface);
        mSurface.setKeepScreenOn(true);
        // capture the rendering Surface on the UI thread via TextureView listener
        mSurface.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                mVideoSurface = new Surface(st);
            }
            @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) {
                mVideoSurface = new Surface(st);
            }
            @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                mVideoSurface = null;
                return true;
            }
            @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) {}
        });

        // Pinch-to-zoom
        mScaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                mZoomScale = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, mZoomScale * d.getScaleFactor()));
                clampPan();
                applyZoomTransform();
                return true;
            }
        });
        // Pan (scroll) and double-tap to reset zoom, single tap opens menu
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
        mConnect = findViewById(R.id.text_connect);
        mConnect.setTextColor(Color.LTGRAY);

        codecH265 = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController ctrl = getWindow().getInsetsController();
            if (ctrl != null) {
                ctrl.hide(WindowInsets.Type.navigationBars());
                ctrl.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }

        // Menu is opened via single-tap in the gesture detector above

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

        // migrate legacy single-camera settings to slot 0
        if (pref.contains("host") && !pref.contains("host_0")) {
            SharedPreferences.Editor edit = pref.edit();
            edit.putString("host_0", pref.getString("host", DEFAULT_URL));
            edit.putBoolean("type_0", pref.getBoolean("type", false));
            edit.remove("host");
            edit.remove("type");
            edit.apply();
        }

        mActive = pref.getInt("active", 0);
        for (int i = 0; i < CAM_COUNT; i++) {
            mHosts[i] = pref.getString("host_" + i, DEFAULT_URL);
            mTypes[i] = pref.getBoolean("type_" + i, false);
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

    /** Strip CR/LF to prevent CRLF injection into RTSP header lines. */
    private static String sanitizeUrl(String url) {
        return url.replaceAll("[\r\n]", "");
    }

    /** Copy the active slot values into the volatile fields read by the network thread. */
    private void applyActiveCamera() {
        mHost = mHosts[mActive];
        mType = mTypes[mActive];
        resetZoom();
    }

    /** Format the status text: "[N/4] url" or "Camera N — not configured". */
    private String formatStatus() {
        String url = mHosts[mActive];
        if (url == null || url.isEmpty()) {
            return "[" + (mActive + 1) + "/" + CAM_COUNT + "]";
        }
        return "[" + (mActive + 1) + "/" + CAM_COUNT + "] " + url;
    }

    private void saveSettings() {
        SharedPreferences pref = getSharedPreferences("settings", MODE_PRIVATE);
        SharedPreferences.Editor edit = pref.edit();
        edit.putInt("active", mActive);
        for (int i = 0; i < CAM_COUNT; i++) {
            edit.putString("host_" + i, mHosts[i]);
            edit.putBoolean("type_" + i, mTypes[i]);
        }
        edit.apply();

        applyActiveCamera();
        mConnect.setText(formatStatus());
        activeStream = false;
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

        // camera slot selector: horizontal row [1] [2] [3] [4]
        LinearLayout camRow = new LinearLayout(this);
        camRow.setOrientation(LinearLayout.HORIZONTAL);
        layout.addView(camRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

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

        final TextView[] camButtons = new TextView[CAM_COUNT];
        for (int i = 0; i < CAM_COUNT; i++) {
            final int slot = i;
            camButtons[i] = createItem(String.valueOf(i + 1));
            camButtons[i].setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams camBtnParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            camRow.addView(camButtons[i], camBtnParams);

            if (i == mActive) highlightItem(camButtons[i]);

            camButtons[i].setOnClickListener(v -> {
                if (slot == mActive) return;
                mActive = slot;
                for (int j = 0; j < CAM_COUNT; j++) {
                    if (j == mActive) highlightItem(camButtons[j]);
                    else resetItem(camButtons[j]);
                }
                host.setText(mHosts[mActive]);
                host.setSelection(host.getText().length());
                typeToggle.setText(mTypes[mActive] ? "Transport: UDP" : "Transport: TCP");
                saveSettings();
            });
        }

        TextView webui = createItem("WebUI");
        layout.addView(webui);
        webui.setOnClickListener(v -> {
            startBrowser();
            popup.dismiss();
        });

        String code = "Exit [V" + mVersion + "]";

        SpannableString s = new SpannableString(code);
        s.setSpan(new SuperscriptSpan(),    5, s.length(), 0);
        s.setSpan(new RelativeSizeSpan(0.5f), 5, s.length(), 0);

        View divider = new View(this);
        divider.setBackgroundColor(Color.DKGRAY);
        layout.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));

        TextView exit = createItem("Exit");
        layout.addView(exit);
        exit.setText(s);
        exit.setOnClickListener(v -> finishAndRemoveTask());

        settings.setOnClickListener(v -> {
            boolean show = header.getVisibility() == View.VISIBLE;
            header.setVisibility(show ? View.GONE : View.VISIBLE);
            settings.setVisibility(show ? View.VISIBLE : View.GONE);
            camRow.setVisibility(show ? View.VISIBLE : View.GONE);
            webui.setVisibility(show ? View.VISIBLE : View.GONE);
            exit.setVisibility(show ? View.VISIBLE : View.GONE);
        });
    }

    private TextView createItem(String title) {
        TextView text = new TextView(this);
        text.setText(title);
        text.setPadding(dp(8), dp(8), dp(8), dp(8));
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        focusChange(text);

        return text;
    }

    /** Apply blue highlight to the active camera button. */
    private void highlightItem(TextView item) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.BLACK);
        bg.setStroke(2, Color.BLUE);
        item.setBackground(bg);
        item.setTextColor(Color.CYAN);
    }

    /** Reset camera button to default style. */
    private void resetItem(TextView item) {
        item.setTextColor(Color.WHITE);
        focusChange(item);
    }

    private EditText createEdit(String title) {
        EditText text = new EditText(this);
        text.setText(title);
        text.setPadding(dp(8), dp(8), dp(8), dp(8));
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        text.setImeOptions(EditorInfo.IME_ACTION_DONE);
        text.setSelection(text.getText().length());
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

    /** Converts dp units to pixels using the current display density. */
    private int dp(float dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
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

        // Set the auth-capable client BEFORE loadUrl so credentials are
        // available on the very first HTTP 401 challenge.
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
            //noinspection deprecation
            DisplayMetrics dm = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(dm);
            screenWidth = (int) (dm.widthPixels * 0.75);
        }
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(screenWidth, WindowManager.LayoutParams.MATCH_PARENT);
        }
        dialog.show();
        // release WebView native resources when the dialog is dismissed
        dialog.setOnDismissListener(d -> view.destroy());
        mBrowserDialog = dialog;

        view.loadUrl("http://" + link);
    }

    private void updateResolution(int width, int height) {
        if (width < 64 || height < 64) {  // validate incoming params to avoid division by zero
            return;
        }

        Log.d(TAG, "Resolution update: " + width + "x" + height);
        runOnUiThread(() -> {
            // getHeight() must be called on the UI thread — View dimensions are written
            // by the layout system on the UI thread; reading them elsewhere is a data race
            int surfaceH = mSurface.getHeight();
            if (surfaceH == 0) surfaceH = getResources().getDisplayMetrics().heightPixels;
            int surfaceW = (int) ((float) surfaceH / height * width);
            Log.d(TAG, "Set surface: " + surfaceW + "x" + surfaceH);

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(surfaceW, surfaceH);
            params.gravity = Gravity.CENTER;
            mSurface.setLayoutParams(params);
            mConnect.setVisibility(View.GONE);
        });
    }

    /** Apply current zoom scale and pan offset via TextureView matrix transform */
    private void applyZoomTransform() {
        Matrix matrix = new Matrix();
        float cx = mSurface.getWidth() / 2f;
        float cy = mSurface.getHeight() / 2f;
        matrix.postScale(mZoomScale, mZoomScale, cx, cy);
        matrix.postTranslate(mPanX, mPanY);
        mSurface.setTransform(matrix);
    }

    /** Clamp pan offset so the image edges don't go past the viewport center */
    private void clampPan() {
        float maxX = (mSurface.getWidth() * (mZoomScale - 1)) / 2f;
        float maxY = (mSurface.getHeight() * (mZoomScale - 1)) / 2f;
        mPanX = Math.max(-maxX, Math.min(maxX, mPanX));
        mPanY = Math.max(-maxY, Math.min(maxY, mPanY));
    }

    /** Reset zoom to default 1:1 view */
    private void resetZoom() {
        mZoomScale = ZOOM_MIN;
        mPanX = 0f;
        mPanY = 0f;
        applyZoomTransform();
    }

    private int getFragment(byte data) {
        return codecH265 ? (data >> 1) & 0x3F : data & 0x1F;
    }

    private Frame buildFrame(Frame frame) {
        byte[] rxBuffer = frame.data();
        int rxSize = frame.length();
        int cpSize = 12;
        rxSize -= cpSize;

        if (rxSize <= 0) {  // packet must have at least 1 byte of RTP payload
            Log.w(TAG, "RTP payload too short: " + frame.length());
            return null;
        }

        int staBit;
        int endBit;
        int nalBit = 4;

        if (lastCodec != codecH265) {
            lastCodec = codecH265;
            nalSize = 0;   // discard partial NAL fragment from the previous codec
            nalQueue.clear(); // discard fully-assembled frames from the previous codec —
                              // feeding H.264 frames to an H.265 decoder (or vice versa)
                              // causes decoding errors and visual corruption
            closeDecoder();
            Log.d(TAG, "Set codec to " + (codecH265 ? "H265" : "H264"));
        }

        int fragment = getFragment(rxBuffer[cpSize]);
        if (fragment == RTP_FU_H264 || fragment == RTP_FU_H265) {
            // fragmented NAL: H.265 FU needs 3 bytes header, H.264 FU-A needs 2
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

                // Skip the FU header byte whose type bits were already extracted above.
                // nalBit++ also steps past the NAL header byte(s) already placed in
                // nalBuffer[4] (H.264) or nalBuffer[4,5] (H.265) — keeping them intact.
                nalBit++;
                cpSize++;
                rxSize--;

                if (nalBit + rxSize > nalBuffer.length) {  // guard: start fragment overflow
                    Log.e(TAG, "NAL start fragment too large, dropping");
                    nalSize = 0;
                    return null;
                }
                System.arraycopy(rxBuffer, cpSize, nalBuffer, nalBit, rxSize);
                nalSize = rxSize + nalBit;

                if (endBit > 0) {
                    // single-fragment NAL (start and end both set): flush as a complete frame
                    byte[] output = new byte[nalSize];
                    System.arraycopy(nalBuffer, 0, output, 0, nalSize);
                    return new Frame(output, nalSize);
                }
            } else {
                cpSize++;
                rxSize--;

                if (nalSize + rxSize > nalBuffer.length) {  // guard: accumulated overflow
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

            if (nalBit + rxSize > nalBuffer.length) {  // guard: single-NAL overflow
                Log.e(TAG, "Single NAL too large (" + rxSize + " bytes), dropping");
                return null;
            }
            System.arraycopy(rxBuffer, cpSize, nalBuffer, nalBit, rxSize);
            nalSize = rxSize + nalBit;

            byte[] output = new byte[nalSize];
            System.arraycopy(nalBuffer, 0, output, 0, nalSize);
            return new Frame(output, nalSize);
        }

        // middle fragment: accumulating, frame not yet complete
        return null;
    }

    private void playAudio(Frame data) {
        // snapshot to a local: onPause() can null audioTrack from the UI thread at any moment
        AudioTrack track = audioTrack;
        if (track == null) {
            if (!audioFailed) {  // skip retry if init already failed for this session
                createAudio();
            }
        } else {
            // write() may return a positive number less than the requested length;
            // loop until all bytes are consumed or a fatal error is reported.
            // Re-snapshot audioTrack on each iteration: closeAudio() can null it at any moment.
            byte[] buf = data.data();
            int offset = 0;
            int remaining = data.length();
            while (remaining > 0) {
                AudioTrack t = audioTrack; // re-check: UI thread may have called closeAudio()
                if (t == null) break;
                int written = t.write(buf, offset, remaining);
                if (written < 0) {
                    Log.e(TAG, "AudioTrack.write() error: " + written);
                    break;
                }
                offset    += written;
                remaining -= written;
            }
        }
    }

    private void processAudio(Frame frame) {
        int header = 12;
        int length = frame.length() - header;
        if (length <= 0) {  // ignore malformed packets shorter than the RTP header
            return;
        }

        // reuse pre-allocated staging buffer for typical PCM frame sizes to reduce GC pressure;
        // fall back to heap allocation only for unusually large frames
        byte[] data = (length <= PCM_BUF_SIZE) ? pcmStagingBuf : new byte[length];
        System.arraycopy(frame.data(), header, data, 0, length);
        // swap bytes only for big-endian encodings (L16 per RFC 3551)
        if (audioBigEndian) {
            for (int i = 0; i + 1 < length; i += 2) {
                byte tmp = data[i];
                data[i] = data[i + 1];
                data[i + 1] = tmp;
            }
        }

        // must copy into a fresh array before queuing — pcmStagingBuf is overwritten next call
        byte[] queued = (data == pcmStagingBuf) ? java.util.Arrays.copyOf(data, length) : data;
        Frame buffer = new Frame(queued, length);
        try {
            // offer() with a timeout to avoid blocking the network thread when
            // the audio consumer stalls; drop the frame if the queue stays full
            if (!pcmQueue.offer(buffer, 200, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Audio queue full, frame dropped");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // restore interrupt status for the caller
            Log.w(TAG, "Audio queue interrupted");
        }
    }

    private void createAudio() {
        // use the clock rate extracted from SDP; falls back to the 8000 Hz default
        int sample = audioSampleRate;
        int format = AudioFormat.CHANNEL_OUT_MONO;

        Log.d(TAG, "Create audio decoder (" + sample + "hz)");
        int size = AudioTrack.getMinBufferSize(sample, format, AudioFormat.ENCODING_PCM_16BIT);
        if (size <= 0) {  // ERROR (-1) or ERROR_BAD_VALUE (-2): unsupported sample rate
            Log.e(TAG, "Invalid audio parameters: sample=" + sample + " bufSize=" + size);
            audioFailed = true;
            return;
        }

        // AudioTrack.Builder replaces the deprecated stream-based constructor (API 21+)
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
            // resource exhaustion or invalid config at the OS audio layer
            Log.e(TAG, "AudioTrack failed to initialize, releasing");
            track.release();
            audioFailed = true;
            return;
        }

        // publish to the volatile field only after the track is fully ready
        audioTrack = track;
        try {
            track.play();
        } catch (Exception e) {
            // play() can throw on resource exhaustion; release to avoid a native-handle leak
            audioTrack = null;
            track.release();
            Log.e(TAG, "AudioTrack.play() failed", e);
            audioFailed = true;
        }
    }

    private void closeAudio() {
        AudioTrack track = audioTrack;
        if (track != null) {
            // null the field BEFORE stop/release: any concurrent playAudio() snapshot
            // will then see null and skip, rather than writing to a released AudioTrack
            audioTrack = null;
            Log.i(TAG, "Close audio decoder");
            try {
                track.stop();
                track.release();
            } catch (Exception e) {
                Log.e(TAG, "Audio close exception", e);
            }
        }
        audioFailed = false; // allow re-init on the next session
    }

    private void decodeFrame(Frame buffer) {
        if (buffer.length() < 5) { // need at least 4-byte start code + 1 NAL type byte
            Log.w(TAG, "NAL frame too short: " + buffer.length());
            return;
        }

        lastFrame = SystemClock.elapsedRealtime();

        int flag = 0;
        int fragment = getFragment(buffer.data()[4]);
        // mark parameter-set NALs so the decoder can configure itself before the first frame
        boolean isConfigNal = codecH265
                ? (fragment == H265_NAL_VPS || fragment == H265_NAL_SPS || fragment == H265_NAL_PPS)
                : (fragment == H264_NAL_SPS || fragment == H264_NAL_PPS);
        if (isConfigNal) {
            flag = MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
        }

        // Hold decoderLock for the entire codec operation: closeDecoder() (called from the
        // network thread on codec-switch) must not call stop()/release() while we are still
        // feeding buffers or dequeuing output — MediaCodec is not thread-safe for that.
        boolean needCreate = false;
        synchronized (decoderLock) {
            MediaCodec codec = mDecoder;
            if (codec == null) {
                needCreate = !decoderFailed; // createDecoder() acquires decoderLock itself
            } else {
                try {
                    // 5 ms timeout: gives the codec a chance to free a slot under load
                    // instead of immediately returning -1 and silently dropping the frame
                    int inputBufferId = codec.dequeueInputBuffer(5_000);
                    if (inputBufferId >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferId);
                        if (inputBuffer != null) {
                            inputBuffer.clear(); // reset position/limit — dequeue does not guarantee this
                            inputBuffer.put(buffer.data(), 0, buffer.length());
                            codec.queueInputBuffer(inputBufferId, 0,
                                    buffer.length(), System.nanoTime() / 1000, flag);
                        }
                    }

                    MediaCodec.BufferInfo info = mBufferInfo;
                    int outputBufferId = codec.dequeueOutputBuffer(info, 0);

                    if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // decoder signals a format change — read resolution from the canonical no-arg call
                        MediaFormat format = codec.getOutputFormat();
                        int mWidth  = format.getInteger(MediaFormat.KEY_WIDTH);
                        int mHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
                        if (lastWidth != mWidth || lastHeight != mHeight) {
                            lastWidth  = mWidth;
                            lastHeight = mHeight;
                            updateResolution(lastWidth, lastHeight);
                        }
                    } else if (outputBufferId >= 0) {
                        codec.releaseOutputBuffer(outputBufferId, true);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Codec exception: " + e.getMessage());
                    // clear mDecoder under lock so the video thread won't retry before release
                    mDecoder = null;
                    decoderFailed = false;
                    try { codec.stop();    } catch (Exception ignored) {}
                    try { codec.release(); } catch (Exception ignored) {}
                }
            }
        }
        // createDecoder() acquires decoderLock internally — must be called outside our block
        if (needCreate) createDecoder();
    }

    private void createDecoder() {
        // fast pre-check before allocating anything
        synchronized (decoderLock) {
            if (mDecoder != null) return; // already running — avoid double-init and native leak
        }

        // use the Surface captured on the UI thread — getSurface() is not thread-safe to call
        // from the network thread directly (the TextureView listener populates mVideoSurface)
        Surface mVideo = mVideoSurface;
        if (mVideo == null || !mVideo.isValid()) {
            return;
        }

        String type = codecH265 ? "video/hevc" : "video/avc";

        MediaFormat format = MediaFormat.createVideoFormat(type, 1280, 720);
        // pre-declare the max input size to match our NAL buffer; prevents
        // BufferOverflowException when a large intra-frame arrives
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024);

        MediaCodec local;
        try {
            Log.i(TAG, "Start video decoder");
            local = MediaCodec.createDecoderByType(type);
            // configure() and start() are split from createDecoderByType() so that
            // if either throws, we can release() the already-created codec object —
            // otherwise the native handle leaks until the next GC cycle
            try {
                local.configure(format, mVideo, null, 0);
                local.start();
            } catch (Exception e) {
                local.release(); // prevent native MediaCodec handle leak
                throw e;
            }
        } catch (Exception e) {
            Log.e(TAG, "Cannot setup decoder: " + e.getMessage());
            decoderFailed = true; // codec likely unsupported; stop retrying until next session
            return;
        }

        synchronized (decoderLock) {
            if (mDecoder != null) {
                // another thread created the decoder while we were outside the lock; discard ours
                local.release();
                return;
            }
            mDecoder = local;
        }
        // reset the watchdog baseline: decodeFrame() only updates lastFrame after the codec
        // is ready, so the first call (codec==null path) returns early without touching it.
        // Without this, a keyframe interval > 3s would trigger a spurious stream disconnect.
        lastFrame = SystemClock.elapsedRealtime();
        updateResolution(lastWidth, lastHeight);
    }

    private void closeDecoder() {
        synchronized (decoderLock) {
            MediaCodec codec = mDecoder;
            if (codec == null) { decoderFailed = false; return; }
            mDecoder = null;
            Log.i(TAG, "Close video decoder");
            // release inside the lock: prevents createDecoder() from allocating a second
            // codec instance while the old one is still held by the hardware pipeline
            try {
                codec.stop();
                codec.release();
            } catch (Exception e) {
                Log.e(TAG, "Decoder close exception", e);
            }
            decoderFailed = false;
        }
    }

    /**
     * Reads one CRLF-terminated line from a raw InputStream without any internal buffering,
     * so the stream position is left exactly after the '\n' — no bytes are consumed in advance.
     * A BufferedReader/BufferedInputStream must NOT be used here: after PLAY the camera
     * immediately starts sending RTP data, and any pre-read bytes would be silently lost.
     */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(128); // pre-sized for a typical header line
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

    /**
     * Reads a complete RTSP response (status line + headers until blank line).
     * Throws IOException if the status code is not 2xx.
     * Returns the trimmed value of {@code targetHeader}, or null if the header is absent.
     */
    private static String readRtspResponse(InputStream in, String targetHeader) throws IOException {
        String status = readLine(in);
        if (status == null) throw new IOException("Server closed connection during handshake");
        Log.i(TAG, status);

        // validate that the response is a 2xx success
        String[] parts = status.split(" ", 3);
        if (parts.length < 2) throw new IOException("Malformed RTSP response: " + status);
        try {
            int code = Integer.parseInt(parts[1]);
            if (code < 200 || code >= 300) throw new IOException("RTSP error: " + status.trim());
        } catch (NumberFormatException e) {
            throw new IOException("Malformed RTSP status code: " + status);
        }

        // read remaining headers until the blank separator line
        String found = null;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            Log.i(TAG, line);
            // RFC 2326: header names are case-insensitive
            if (targetHeader != null && line.toLowerCase(Locale.ROOT)
                    .startsWith(targetHeader.toLowerCase(Locale.ROOT))) {
                // extract value, stripping optional parameters (e.g. "Session: abc;timeout=60")
                found = line.substring(targetHeader.length()).split(";")[0].trim();
            }
        }
        return found;
    }

    /**
     * Parses the full SDP body in a single pass (RFC 4566), extracting:
     * <ul>
     *   <li>audio clock rate → stored in {@link #audioSampleRate}</li>
     *   <li>video codec (H.264 vs H.265) → stored in {@link #codecH265}</li>
     *   <li>per-track Control URLs (RFC 2326 §C.1.1) → returned as [video, audio]</li>
     * </ul>
     * Absolute {@code a=control:} values are used as-is; relative values are resolved
     * against {@code baseUrl}. Falls back to "{@code baseUrl}/trackID=N" if absent.
     */
    private String[] parseSdp(String sdp, String baseUrl) {
        String base = baseUrl; // may be overridden by session-level a=control:
        String[] controls = { null, null };
        int track = -1; // -1 = session section, 0 = video, 1 = audio

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
                // parse audio payload type from "m=audio <port> RTP/AVP <pt>"
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
                    // session-level control: override base URL (RFC 2326 §C.1.1)
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
        // apply defaults for tracks without explicit control
        if (controls[0] == null) controls[0] = base + "/trackID=0";
        if (controls[1] == null) controls[1] = base + "/trackID=1";
        return controls;
    }

    private void rtspConnect() throws Exception {
        nalSize = 0; // discard any partial NAL fragment from the previous session
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
            // use the raw InputStream — a BufferedReader would pre-read RTP stream bytes
            // into its internal buffer, making them unavailable to tcpStream()
            InputStream input = s.getInputStream();
            OutputStream w = s.getOutputStream();

            Log.d(TAG, "Start rtsp connection");

            String user = uri.getUserInfo();
            String auth = "";
            if (user != null) {
                auth = "Authorization: Basic " +
                        Base64.encodeToString(user.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP) + "\r\n";
            }
            // Strip userinfo from the request-line URL: credentials belong only in the
            // Authorization header — including them in the URL leaks them to server access logs.
            String path = uri.getEncodedPath();
            String query = uri.getEncodedQuery();
            // include query string (?param=value) — some cameras embed channel or stream IDs there
            String rtspUrl = uri.getScheme() + "://" + host
                    + (port >= 0 ? ":" + port : "")
                    + (path  != null ? path         : "")
                    + (query != null ? "?" + query  : "");

            int seq = 1;
            String desc = "DESCRIBE " + rtspUrl + " RTSP/1.0\r\n" +
                    "CSeq: " + seq + "\r\n" + auth + mUserAgent + "Accept: application/sdp\r\n\r\n";
            w.write(desc.getBytes(StandardCharsets.UTF_8));
            w.flush();

            // read DESCRIBE response; capture Content-Length to skip the SDP body
            String contentLenStr = readRtspResponse(input, "Content-Length:");
            int sdpBodyLen = 0;
            if (contentLenStr != null) {
                try { sdpBodyLen = Integer.parseInt(contentLenStr); }
                catch (NumberFormatException ignored) {}
            }
            // read SDP body; parse audio clock rate, then discard the rest
            StringBuilder sdp = new StringBuilder();
            byte[] skipBuf = new byte[512];
            while (sdpBodyLen > 0) {
                int n = input.read(skipBuf, 0, Math.min(sdpBodyLen, skipBuf.length));
                if (n <= 0) break; // -1 = EOF; 0 = legal but unusual, avoids infinite loop
                if (sdp.length() < 4096) // cap to avoid OOM on pathological responses
                    sdp.append(new String(skipBuf, 0, n, StandardCharsets.UTF_8));
                sdpBodyLen -= n;
            }
            // single-pass SDP parse: audio rate, video codec, and per-track Control URLs
            String[] trackUrls = parseSdp(sdp.toString(), rtspUrl);

            // bind to ephemeral ports before SETUP to advertise in Transport header
            DatagramSocket udpVideo = null;
            DatagramSocket udpAudio = null;
            if (mType) {
                udpVideo = new DatagramSocket(0);
                udpAudio = new DatagramSocket(0);
            }
            try {

            seq++;
            String type = mType
                    ? "RTP/AVP/UDP;unicast;client_port=" + udpVideo.getLocalPort()
                    : "RTP/AVP/TCP;unicast;interleaved=0-1";
            String video = "SETUP " + trackUrls[0] + " RTSP/1.0\r\n" +
                    "CSeq: " + seq + "\r\n" + auth + mUserAgent + "Transport: " + type + "\r\n\r\n";
            w.write(video.getBytes(StandardCharsets.UTF_8));
            w.flush();

            // read SETUP response; Session header is required to continue
            String session = readRtspResponse(input, "Session:");
            if (session == null) {
                throw new IOException("RTSP server did not return a Session header");
            }
            // strip CR/LF to prevent the session token from injecting extra RTSP headers
            session = session.replaceAll("[\r\n]", "");

            seq++;
            type = mType
                    ? "RTP/AVP/UDP;unicast;client_port=" + udpAudio.getLocalPort()
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

            // disable read timeout before streaming: keyframe intervals often exceed 1 second
            s.setSoTimeout(0);
            // reset watchdog baseline so the 3-second timeout counts from now,
            // not from the epoch (lastFrame == 0 would trigger the watchdog immediately)
            lastFrame = SystemClock.elapsedRealtime();
            activeStream = true;
            // pre-warm the decoder now, while first packets are still in transit;
            // without this, createDecoder() runs on the first decoded frame (~200–500 ms later)
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
                        audioRx.start();
                        udpStream(udpVideo);
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
        // wake up every second so the activeStream flag is checked even when the camera
        // goes silent (power cycle, network loss); prevents permanent thread leak
        d.setSoTimeout(1000);
        // single buffer reused every packet — avoids per-frame allocation at 25-30 fps
        byte[] buf = new byte[65535];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (activeStream) {
            try {
                d.receive(packet);
            } catch (SocketTimeoutException ignored) {
                continue; // re-check activeStream
            }
            // defensive copy: packet.getData() is reused on next receive()
            byte[] copy = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), 0, copy, 0, packet.getLength());
            processPacket(new Frame(copy, packet.getLength()));
        }
    }

    private void tcpStream(InputStream rawInput) throws IOException {
        // wrap in BufferedInputStream to batch OS-level reads
        BufferedInputStream input = new BufferedInputStream(rawInput, 65536);
        byte[] pktBuf = new byte[65535];
        while (activeStream) {
            int b = input.read();
            if (b == -1) {
                activeStream = false; // server closed connection cleanly — signal reconnect
                break;
            }
            if (b != 0x24) continue; // not an RTSP interleaved marker — skip

            int channel = input.read();
            int hi = input.read();
            int lo = input.read();
            if (channel == -1 || hi == -1 || lo == -1) {
                activeStream = false; // unexpected EOF inside interleaved header
                break;
            }
            int len = (hi << 8) | lo;
            // A zero-length or oversized packet is malformed; skip it to avoid
            // an empty read loop or an out-of-bounds access into pktBuf.
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
                processPacket(new Frame(pktBuf, len));
            }
        }
    }

    private void processPacket(Frame frame) {
        if (frame.length() < 12) {  // RTP fixed header is exactly 12 bytes
            Log.w(TAG, "RTP packet too short: " + frame.length());
            return;
        }
        byte[] data = frame.data();

        // Validate that no CSRC list or header extension is present.
        // Both shift the payload start beyond byte 12 — unsupported for now.
        // OpenIPC cameras always send CC=0 and X=0, so this guards against malformed streams.
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
                try {
                    // offer() with timeout mirrors the same pattern used for pcmQueue;
                    // avoids blocking the network thread when the video decoder stalls
                    if (!nalQueue.offer(output, 200, TimeUnit.MILLISECONDS)) {
                        Log.w(TAG, "Video queue full, frame dropped");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // restore interrupt status for the caller
                    Log.w(TAG, "Video queue interrupted");
                }
            }

            return;
        }

        // log each new unknown payload type only once to avoid spamming the network thread
        if (payload != lastUnknownPayload) {
            lastUnknownPayload = payload;
            Log.w(TAG, "Unknown rtp type: " + payload);
        }
    }

    private void startListener() {
        Log.i(TAG, "Start network listener");

        // Each thread captures its generation; exits as soon as onPause
        // increments listenerGen, preventing duplicate threads on resume.
        final int gen = listenerGen;

        executor = Executors.newFixedThreadPool(4);

        executor.execute(() -> {
            Thread.currentThread().setName("rtsp-network");
            int retryDelay = 1000; // start at 1 s, doubles up to 8 s on repeated failures
            while (gen == listenerGen) {
                try {
                    if (!activeStream) {
                        runOnUiThread(() -> mConnect.setVisibility(View.VISIBLE));
                        rtspConnect();
                        retryDelay = 1000; // reset backoff after any successful session
                        // brief pause before reconnecting after a clean server-side close;
                        // without this the loop hammers the camera with no delay
                        SystemClock.sleep(1000);
                    }
                } catch (Exception e) {
                    // reset so the next iteration re-enters the connect block;
                    // without this, an IOException thrown after activeStream=true
                    // leaves it stuck at true and the loop spins until the watchdog fires
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
                if (activeStream) {
                    if (SystemClock.elapsedRealtime() - lastFrame > WATCHDOG_MS) {
                        Log.w(TAG, "Stream is inactive");
                        activeStream = false;
                        // close the TCP socket so input.read() unblocks immediately —
                        // without this, a dead camera causes the network thread to hang
                        // until the next onPause() (UDP is already covered by setSoTimeout)
                        Socket tcp = mTcpSocket;
                        if (tcp != null) try { tcp.close(); } catch (Exception ignored) {}
                    }
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
                    Thread.currentThread().interrupt(); // restore interrupt status and stop
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
                    Thread.currentThread().interrupt(); // restore interrupt status and stop
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
        // dismiss any open browser dialog; its OnDismissListener will call view.destroy()
        // preventing a WebView native-resource leak when the Activity is rotated
        Dialog browser = mBrowserDialog;
        mBrowserDialog = null;
        if (browser != null && browser.isShowing()) browser.dismiss();
        if (listener) {
            listenerGen++;  // invalidate all threads from the current generation
            listener = false;
            activeStream = false;
            // close active sockets to immediately unblock any blocking read()/receive()
            // on the network thread; without this, a silent camera causes a thread leak
            Socket tcp = mTcpSocket;
            if (tcp != null) try { tcp.close(); } catch (Exception ignored) {}
            DatagramSocket udp = mUdpSocket;
            if (udp != null) try { udp.close(); } catch (Exception ignored) {}
            DatagramSocket udpAudio = mUdpAudioSocket;
            if (udpAudio != null) try { udpAudio.close(); } catch (Exception ignored) {}
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            closeDecoder();
            closeAudio();
            // discard stale frames so the new session starts with a clean slate
            nalQueue.clear();
            pcmQueue.clear();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        loadSettings();
        if (!listener) {
            listener = true;
            startListener();
        }

        mConnect.setText(formatStatus());
        mConnect.setVisibility(View.VISIBLE);
    }

    private record Frame(byte[] data, int length) {
    }
}
