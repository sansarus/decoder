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
import android.media.AudioFormat;
import android.media.AudioManager;
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
import android.view.SurfaceView;
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
import java.util.concurrent.TimeUnit;

public class Decoder extends Activity {
    private static final String TAG = "OpenIPCDecoder";
    private static final String UA  = "User-Agent: OpenIPC-Decoder/1.0\r\n";
    private final BlockingQueue<Frame> nalQueue = new ArrayBlockingQueue<>(32);
    private final BlockingQueue<Frame> pcmQueue = new ArrayBlockingQueue<>(32);
    private final byte[] nalBuffer = new byte[1024 * 1024];

    // volatile: these fields are read/written from multiple threads
    private volatile int nalSize;
    private volatile MediaCodec mDecoder;
    private SurfaceView mSurface;
    private volatile AudioTrack audioTrack;
    private TextView mConnect;

    private volatile boolean codecH265;
    private volatile boolean lastCodec;
    private volatile boolean listener;
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

    // pre-allocated to avoid per-frame GC pressure in the video decode loop
    private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

    // pre-allocated audio staging buffer: PCM frames are at most a few KB;
    // if a frame exceeds this size we fall back to a one-time heap allocation
    private static final int PCM_BUF_SIZE = 8192;
    private final byte[] pcmStagingBuf = new byte[PCM_BUF_SIZE];

    // read from the network thread, written from the UI thread
    private volatile String mHost;
    private volatile boolean mType;

    // tracks last warned unknown RTP payload type to suppress log spam on the network thread
    private volatile int lastUnknownPayload = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.decoder);

        mSurface = findViewById(R.id.video_surface);
        mSurface.setKeepScreenOn(true);
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

        View menu = findViewById(R.id.decoder);
        menu.setOnClickListener(item -> createMenu(menu));

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
        mHost = pref.getString("host", "rtsp://root:12345@192.168.1.10:554/stream=0");
        mType = pref.getBoolean("type", false); // false = TCP (default), true = UDP

        String link = getIntent().getStringExtra(Intent.EXTRA_TEXT);
        if (link != null) {
            Log.d(TAG, "Link: " + Uri.parse(link).getHost()); // host only, never credentials
            mHost = link;
        }
    }

    private void saveSettings() {
        SharedPreferences pref = getSharedPreferences("settings", MODE_PRIVATE);
        SharedPreferences.Editor edit = pref.edit();
        edit.putString("host", mHost);
        edit.putBoolean("type", mType);
        edit.apply();

        mConnect.setText(mHost);
        activeStream = false;
        // close the active stream sockets so the network thread unblocks immediately
        // instead of waiting for the next packet or the UDP 1-second receive timeout
        Socket tcp = mTcpSocket;
        if (tcp != null) try { tcp.close(); } catch (Exception ignored) {}
        DatagramSocket udp = mUdpSocket;
        if (udp != null) try { udp.close(); } catch (Exception ignored) {}
    }

    private void createMenu(View menu) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        PopupWindow popup = new PopupWindow(layout, LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.showAtLocation(menu, Gravity.TOP | Gravity.START, 0, dp(20));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setVisibility(View.GONE);
        layout.addView(header);

        TextView settings = createItem("Settings");
        layout.addView(settings);

        EditText host = createEdit(mHost);
        header.addView(host);
        host.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                mHost = host.getText().toString().trim();
                saveSettings();
                popup.dismiss();
                return true;
            }
            return false;
        });

        // toggle between TCP (default) and UDP transport; persisted via saveSettings()
        TextView typeToggle = createItem(mType ? "Transport: UDP" : "Transport: TCP");
        header.addView(typeToggle);
        typeToggle.setOnClickListener(v -> {
            mType = !mType;
            typeToggle.setText(mType ? "Transport: UDP" : "Transport: TCP");
            saveSettings();
        });

        TextView webui = createItem("WebUI");
        layout.addView(webui);
        webui.setOnClickListener(v -> {
            startBrowser();
            popup.dismiss();
        });

        String code = "Exit [V1.0]";
        try {
            String name;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                name = getPackageManager()
                        .getPackageInfo(getPackageName(), PackageManager.PackageInfoFlags.of(0))
                        .versionName;
            } else {
                //noinspection deprecation
                name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            }
            code = "Exit [V" + name + "]";
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Cannot extract version code");
        }

        SpannableString s = new SpannableString(code);
        s.setSpan(new SuperscriptSpan(),    5, s.length(), 0); // "[V...]" raised, excluding leading space
        s.setSpan(new RelativeSizeSpan(0.5f), 5, s.length(), 0);

        TextView exit = createItem("Exit");
        layout.addView(exit);
        exit.setText(s);
        exit.setOnClickListener(v -> finishAndRemoveTask());

        settings.setOnClickListener(v -> {
            boolean show = header.getVisibility() == View.VISIBLE;
            header.setVisibility(show ? View.GONE : View.VISIBLE);
            settings.setVisibility(show ? View.VISIBLE : View.GONE);
            webui.setVisibility(show ? View.VISIBLE : View.GONE);
            exit.setVisibility(show ? View.VISIBLE : View.GONE);
        });
    }

    private TextView createItem(String title) {
        TextView text = new TextView(this);
        text.setText(title);
        text.setPadding(dp(12), dp(12), dp(12), dp(12));
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        focusChange(text);

        return text;
    }

    private EditText createEdit(String title) {
        EditText text = new EditText(this);
        text.setText(title);
        text.setPadding(dp(12), dp(12), dp(12), dp(12));
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        // TYPE_TEXT_VARIATION_URI shows the text normally (not masked like a password)
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
                String mUser = Uri.parse(mHost).getUserInfo();
                if (mUser != null) {
                    String[] part = mUser.split(":", 2);
                    if (part.length > 1) {
                        handler.proceed(part[0], part[1]);
                    }
                }
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

        view.loadUrl("http://" + link);
    }

    private void updateResolution(int width, int height) {
        if (width < 64 || height < 64) {  // validate incoming params to avoid division by zero
            return;
        }

        int surfaceH = mSurface.getHeight();
        // surface may not be measured yet on the first decoded frame — fall back to display height
        if (surfaceH == 0) surfaceH = getResources().getDisplayMetrics().heightPixels;
        int surfaceW = (int) ((float) surfaceH / height * width);
        Log.d(TAG, "Set resolution: " + width + "x" + height + " -> " + surfaceW + "x" + surfaceH);

        final int w = surfaceW, h = surfaceH;
        runOnUiThread(() -> {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(w, h);
            params.gravity = Gravity.CENTER;
            findViewById(R.id.video_surface).setLayoutParams(params);
            mConnect.setVisibility(View.GONE);
        });
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
            closeDecoder();
            Log.d(TAG, "Set codec to " + (codecH265 ? "H265" : "H264"));
        }

        int fragment = getFragment(rxBuffer[cpSize]);
        if (fragment == 28 || fragment == 49) {
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

        return null;
    }

    private void playAudio(Frame data) {
        // snapshot to a local: onPause() can null audioTrack from the UI thread at any moment
        AudioTrack track = audioTrack;
        if (track == null) {
            if (!audioFailed) {  // skip retry if init already failed for this session
                createAudio(data.length());
            }
        } else {
            track.write(data.data(), 0, data.length());
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
        for (int i = 0; i + 1 < length; i += 2) { // guard against odd-length frames
            byte tmp = data[i];
            data[i] = data[i + 1];
            data[i + 1] = tmp;
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

    private void createAudio(int length) {
        int sample = length * 25;
        int format = AudioFormat.CHANNEL_OUT_MONO;

        Log.d(TAG, "Create audio decoder (" + sample + "hz)");
        int size = AudioTrack.getMinBufferSize(sample, format, AudioFormat.ENCODING_PCM_16BIT);
        if (size <= 0) {  // ERROR (-1) or ERROR_BAD_VALUE (-2): unsupported sample rate
            Log.e(TAG, "Invalid audio parameters: sample=" + sample + " bufSize=" + size);
            audioFailed = true;
            return;
        }
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sample, format,
                AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STREAM);
        if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            // resource exhaustion or invalid config at the OS audio layer
            Log.e(TAG, "AudioTrack failed to initialize, releasing");
            audioTrack.release();
            audioTrack = null;
            audioFailed = true;
            return;
        }
        audioTrack.play();
    }

    private void closeAudio() {
        if (audioTrack != null) {
            Log.i(TAG, "Close audio decoder");
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }
        audioFailed = false; // allow re-init on the next session
    }

    private void decodeFrame(Frame buffer) {
        // snapshot to a local: onPause() can null mDecoder from the UI thread at any moment,
        // and a volatile read between null-check and method call would be a TOCTOU race
        MediaCodec codec = mDecoder;
        if (codec == null) {
            if (!decoderFailed) createDecoder(); // skip if codec proved unsupported
            return;
        }

        if (buffer.length() < 5) { // need at least 4-byte start code + 1 NAL type byte
            Log.w(TAG, "NAL frame too short: " + buffer.length());
            return;
        }

        lastFrame = SystemClock.elapsedRealtime();

        int flag = 0;
        int fragment = getFragment(buffer.data()[4]);
        if (fragment == 32 || fragment == 33 || fragment == 34) {
            flag = MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
        }

        try {
            int inputBufferId = codec.dequeueInputBuffer(0);
            if (inputBufferId >= 0) {
                ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferId);
                if (inputBuffer != null) {
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
            closeDecoder(); // stop() + release() to avoid native resource leak
        }
    }

    private void createDecoder() {
        Surface mVideo = mSurface.getHolder().getSurface();
        if (!mVideo.isValid()) {
            return;
        }

        MediaCodec local;
        String type = codecH265 ? "video/hevc" : "video/avc";

        MediaFormat format = MediaFormat.createVideoFormat(type, 1280, 720);
        // pre-declare the max input size to match our NAL buffer; prevents
        // BufferOverflowException when a large intra-frame arrives
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024);

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

        mDecoder = local;
        updateResolution(lastWidth, lastHeight);
    }

    private void closeDecoder() {
        if (mDecoder != null) {
            Log.i(TAG, "Close video decoder");
            try {
                mDecoder.stop();
                mDecoder.release();
            } catch (Exception e) {
                Log.e(TAG, "Decoder exception", e);
            }
            mDecoder = null;
        }
        decoderFailed = false; // allow re-init on the next RTSP session
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
            if (c != '\r') sb.append((char) c);
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

    private void rtspConnect() throws Exception {
        nalSize = 0; // discard any partial NAL fragment from the previous session
        Uri uri = Uri.parse(mHost);
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IOException("Invalid RTSP URL: no host in '" + mHost + "'");
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

            int seq = 1;
            String desc = "DESCRIBE " + mHost + " RTSP/1.0\r\n" +
                    "CSeq: " + seq + "\r\n" + auth + UA + "Accept: application/sdp\r\n\r\n";
            w.write(desc.getBytes(StandardCharsets.UTF_8));
            w.flush();

            // read DESCRIBE response; capture Content-Length to skip the SDP body
            String contentLenStr = readRtspResponse(input, "Content-Length:");
            int sdpBodyLen = 0;
            if (contentLenStr != null) {
                try { sdpBodyLen = Integer.parseInt(contentLenStr); }
                catch (NumberFormatException ignored) {}
            }
            // consume SDP body so it does not pollute the SETUP response
            byte[] skipBuf = new byte[512];
            while (sdpBodyLen > 0) {
                int n = input.read(skipBuf, 0, Math.min(sdpBodyLen, skipBuf.length));
                if (n == -1) break;
                sdpBodyLen -= n;
            }

            seq++;
            String type = mType ? "RTP/AVP/UDP;unicast;client_port=5000"
                    : "RTP/AVP/TCP;unicast;interleaved=0-1";
            String video = "SETUP " + mHost + "/trackID=0 RTSP/1.0\r\n" +
                    "CSeq: " + seq + "\r\n" + auth + UA + "Transport: " + type + "\r\n\r\n";
            w.write(video.getBytes(StandardCharsets.UTF_8));
            w.flush();

            // read SETUP response; Session header is required to continue
            String session = readRtspResponse(input, "Session:");
            if (session == null) {
                throw new IOException("RTSP server did not return a Session header");
            }

            seq++;
            type = mType ? "RTP/AVP/UDP;unicast;client_port=5002"
                    : "RTP/AVP/TCP;unicast;interleaved=2-3";
            String audio = "SETUP " + mHost + "/trackID=1 RTSP/1.0\r\n" +
                    "CSeq: " + seq + "\r\n" + auth + UA + "Transport: " + type + "\r\n" +
                    "Session: " + session + "\r\n\r\n";
            w.write(audio.getBytes(StandardCharsets.UTF_8));
            w.flush();

            readRtspResponse(input, null);

            seq++;
            String play = "PLAY " + mHost + " RTSP/1.0\r\n" +
                    "CSeq: " + seq + "\r\n" + auth + UA + "Session: " + session + "\r\n\r\n";
            w.write(play.getBytes(StandardCharsets.UTF_8));
            w.flush();

            readRtspResponse(input, null);

            // disable read timeout before streaming: keyframe intervals often exceed 1 second
            s.setSoTimeout(0);
            // reset watchdog baseline so the 3-second timeout counts from now,
            // not from the epoch (lastFrame == 0 would trigger the watchdog immediately)
            lastFrame = SystemClock.elapsedRealtime();
            activeStream = true;
            try {
                if (mType) {
                    try (DatagramSocket d = new DatagramSocket(5000)) {
                        mUdpSocket = d; // stored so onPause() can close it to unblock receive()
                        try {
                            udpStream(d);
                        } finally {
                            mUdpSocket = null;
                        }
                    }
                } else {
                    mTcpSocket = s; // stored so onPause() can close it to unblock read()
                    try {
                        // pass the same InputStream used for handshake — no bytes are lost
                        tcpStream(input);
                    } finally {
                        mTcpSocket = null;
                    }
                }
            } finally {
                // tell the server to release the session; best-effort — ignore errors if
                // the socket was already closed by onPause()
                try {
                    String teardown = "TEARDOWN " + mHost + " RTSP/1.0\r\n" +
                            "CSeq: " + (seq + 1) + "\r\n" + auth + UA +
                            "Session: " + session + "\r\n\r\n";
                    w.write(teardown.getBytes(StandardCharsets.UTF_8));
                    w.flush();
                    Log.d(TAG, "RTSP TEARDOWN sent");
                } catch (Exception ignored) {}
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
            processPacket(new Frame(packet.getData(), packet.getLength()));
        }
    }

    private void tcpStream(InputStream input) throws IOException {
        // single read buffer reused for every RTSP interleaved packet
        byte[] pktBuf = new byte[65535];
        while (activeStream) {
            int b = input.read();
            if (b == -1) break;      // server closed the connection
            if (b != 0x24) continue; // not an RTSP interleaved marker — skip

            int channel = input.read();
            int hi = input.read();
            int lo = input.read();
            if (channel == -1 || hi == -1 || lo == -1) break; // unexpected EOF in header
            int len = (hi << 8) | lo;

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
        if (frame.length() < 2) {  // need at least 2 bytes to read the payload type
            Log.w(TAG, "RTP packet too short: " + frame.length());
            return;
        }
        byte[] data = frame.data();
        int payload = (data[1] & 0x7F);
        if (payload == 100) {
            processAudio(frame);
            return;
        } else if (payload == 99) {
            return; // AAC not supported
        } else if (payload == 98) {
            return; // Opus not supported
        } else if (payload == 97 || payload == 96) {
            codecH265 = payload == 97;
            Frame output = buildFrame(frame);
            if (output != null) {
                try {
                    // offer() with timeout mirrors the same pattern used for pcmQueue;
                    // avoids blocking the network thread when the video decoder stalls
                    if (!nalQueue.offer(output, 200, TimeUnit.MILLISECONDS)) {
                        Log.w(TAG, "Video queue full, frame dropped");
                    }
                } catch (InterruptedException e) {
                    Log.w(TAG, "Video thread interrupted");
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

        new Thread(() -> {
            while (gen == listenerGen) {
                try {
                    if (!activeStream) {
                        runOnUiThread(() -> {
                            mSurface.setVisibility(View.GONE);
                            mSurface.setVisibility(View.VISIBLE);
                            mConnect.setVisibility(View.VISIBLE);
                        });
                        rtspConnect();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Cannot connect rtsp: " + e.getMessage());
                    SystemClock.sleep(1000);
                }
            }
        }, "rtsp-network").start();

        new Thread(() -> {
            while (gen == listenerGen) {
                if (activeStream) {
                    if (SystemClock.elapsedRealtime() - lastFrame > 3000) {
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
        }, "rtsp-watchdog").start();

        new Thread(() -> {
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
        }, "rtsp-video").start();

        new Thread(() -> {
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
        }, "rtsp-audio").start();
    }

    @Override
    protected void onPause() {
        super.onPause();
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

        mConnect.setText(mHost);
        mConnect.setVisibility(View.VISIBLE);
    }

    private record Frame(byte[] data, int length) {
    }
}
