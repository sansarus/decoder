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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class Decoder extends Activity {
    private final String TAG = "OpenIPCDecoder";
    private final BlockingQueue<Frame> nalQueue = new ArrayBlockingQueue<>(32);
    private final BlockingQueue<Frame> pcmQueue = new ArrayBlockingQueue<>(32);
    private final byte[] nalBuffer = new byte[1024 * 1024];

    private int nalSize;
    private MediaCodec mDecoder;
    private SurfaceView mSurface;
    private AudioTrack audioTrack;
    private TextView mConnect;

    private boolean codecH265;
    private boolean lastCodec;
    private boolean listener;
    private boolean activeStream;
    private int lastWidth;
    private int lastHeight;
    private long lastFrame;

    private String mHost;
    private boolean mType;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.decoder);

        mSurface = findViewById(R.id.video_surface);
        mSurface.setKeepScreenOn(true);
        mConnect = findViewById(R.id.text_connect);
        mConnect.setTextColor(Color.LTGRAY);

        codecH265 = true;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

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

        String link = getIntent().getStringExtra(Intent.EXTRA_TEXT);
        if (link != null) {
            Log.d(TAG, "Link: " + link);
            mHost = link;
        }
    }

    private void saveSettings() {
        SharedPreferences pref = getSharedPreferences("settings", MODE_PRIVATE);
        SharedPreferences.Editor edit = pref.edit();
        edit.putString("host", mHost);
        edit.apply();

        mConnect.setText(mHost);
        activeStream = false;
    }

    private void createMenu(View menu) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        PopupWindow popup = new PopupWindow(layout, LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.showAtLocation(menu, Gravity.TOP | Gravity.START, 0, 80);

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

        TextView webui = createItem("WebUI");
        layout.addView(webui);
        webui.setOnClickListener(v -> {
            startBrowser();
            popup.dismiss();
        });

        String code = "Exit [V1.0]";
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            code = "Exit [V" + name + "]";
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Cannot extract version code");
        }

        SpannableString s = new SpannableString(code);
        s.setSpan(new SuperscriptSpan(), 4, s.length(), 0);
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
        text.setPadding(40, 40, 40, 40);
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        focusChange(text);

        return text;
    }

    private EditText createEdit(String title) {
        EditText text = new EditText(this);
        text.setText(title);
        text.setPadding(40, 40, 40, 40);
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        text.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD);
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

    @SuppressLint("SetJavaScriptEnabled")
    private void startBrowser() {
        WebView view = new WebView(this);
        view.setWebViewClient(new WebViewClient());
        view.getSettings().setJavaScriptEnabled(true);
        String link = Uri.parse(mHost).getHost();
        if (link != null) {
            view.loadUrl("http://" + link);
        }

        Log.d(TAG, "WebView: " + link);
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(view);
        dialog.setCanceledOnTouchOutside(true);

        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int screenWidth = (int) (displayMetrics.widthPixels * 0.75);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(screenWidth, WindowManager.LayoutParams.MATCH_PARENT);
        }
        dialog.show();

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
    }

    private void updateResolution(int width, int height) {
        if (lastWidth < 64 || lastHeight < 64) {
            return;
        }

        int lastH = mSurface.getHeight();
        int lastW = (int) ((float) lastH / height * width);
        Log.d(TAG, "Set resolution: " + width + "x" + height + " -> " + lastW + "x" + lastH);

        runOnUiThread(() -> {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(lastW, lastH);
            params.width = lastW;
            params.height = lastH;
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

                System.arraycopy(rxBuffer, cpSize, nalBuffer, nalBit, rxSize);
                nalSize = rxSize + nalBit;
            } else {
                cpSize++;
                rxSize--;

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

            System.arraycopy(rxBuffer, cpSize, nalBuffer, nalBit, rxSize);
            nalSize = rxSize + nalBit;

            byte[] output = new byte[nalSize];
            System.arraycopy(nalBuffer, 0, output, 0, nalSize);
            return new Frame(output, nalSize);
        }

        return null;
    }

    private void playAudio(Frame data) {
        if (audioTrack == null) {
            createAudio(data.length);
        } else {
            audioTrack.write(data.data, 0, data.length);
        }
    }

    private void processAudio(Frame frame) {
        int header = 12;
        int length = frame.length() - header;

        byte[] data = new byte[length];
        System.arraycopy(frame.data(), header, data, 0, length);
        for (int i = 0; i < length; i += 2) {
            byte tmp = data[i];
            data[i] = data[i + 1];
            data[i + 1] = tmp;
        }

        Frame buffer = new Frame(data, length);
        try {
            pcmQueue.put(buffer);
        } catch (InterruptedException e) {
            Log.w(TAG, "Audio queue is full");
        }
    }

    private void createAudio(int length) {
        int sample = length * 25;
        int format = AudioFormat.CHANNEL_OUT_MONO;

        Log.d(TAG, "Create audio decoder (" + sample + "hz)");
        int size = AudioTrack.getMinBufferSize(sample, format, AudioFormat.ENCODING_PCM_16BIT);
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sample, format,
                AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STREAM);
        audioTrack.play();
    }

    private void closeAudio() {
        if (audioTrack != null) {
            Log.i(TAG, "Close audio decoder");
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }
    }

    private void decodeFrame(Frame buffer) {
        if (mDecoder == null) {
            createDecoder();
            return;
        }

        lastFrame = SystemClock.elapsedRealtime();

        int flag = 0;
        int fragment = getFragment(buffer.data[4]);
        if (fragment == 32 || fragment == 33 || fragment == 34) {
            flag = MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
        }

        try {
            int inputBufferId = mDecoder.dequeueInputBuffer(0);
            if (inputBufferId >= 0) {
                ByteBuffer inputBuffer = mDecoder.getInputBuffer(inputBufferId);
                if (inputBuffer != null) {
                    inputBuffer.put(buffer.data, 0, buffer.length);
                    mDecoder.queueInputBuffer(inputBufferId, 0,
                            buffer.length, System.nanoTime() / 1000, flag);
                }
            }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int outputBufferId = mDecoder.dequeueOutputBuffer(info, 0);

            if (outputBufferId >= 0) {
                MediaFormat format = mDecoder.getOutputFormat(outputBufferId);
                int mWidth = format.getInteger(MediaFormat.KEY_WIDTH);
                int mHeight = format.getInteger(MediaFormat.KEY_HEIGHT);

                if (lastWidth != mWidth || lastHeight != mHeight) {
                    lastWidth = mWidth;
                    lastHeight = mHeight;
                    updateResolution(lastWidth, lastHeight);
                }

                mDecoder.releaseOutputBuffer(outputBufferId, true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Codec exception: " + e.getMessage());
            mDecoder = null;
        }
    }

    private void createDecoder() {
        Surface mVideo = mSurface.getHolder().getSurface();
        if (!mVideo.isValid()) {
            return;
        }

        MediaCodec local;
        String type = codecH265 ? "video/hevc" : "video/avc";

        MediaFormat format;
        format = MediaFormat.createVideoFormat(type, 1280, 720);

        try {
            Log.i(TAG, "Start video decoder");
            local = MediaCodec.createDecoderByType(type);
            local.configure(format, mVideo, null, 0);
            local.start();
        } catch (Exception e) {
            Log.e(TAG, "Cannot setup decoder: " + e.getMessage());
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
                Log.e(TAG, "Decoder exception");
            }
            mDecoder = null;
        }
    }

    private void rtsp_connect() throws Exception {
        Uri uri = Uri.parse(mHost);
        try (Socket s = new Socket()) {
            int port = Uri.parse(mHost).getPort();
            s.connect(new InetSocketAddress(uri.getHost(), port < 0 ? 554 : port), 1000);
            s.setSoTimeout(1000);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
            OutputStream w = s.getOutputStream();

            Log.d(TAG, "Start rtsp connection");

            String user = uri.getUserInfo();
            String auth = "";
            if (user != null) {
                auth = "Authorization: Basic " +
                        Base64.encodeToString(user.getBytes(), Base64.NO_WRAP) + "\r\n";
            }

            int seq = 1;
            String desc = "DESCRIBE " + mHost + " RTSP/1.0\r\n" +
                    "CSeq: " + seq + "\r\n" + auth + "Accept: application/sdp\r\n\r\n";
            w.write(desc.getBytes());
            w.flush();

            String line;
            while ((line = r.readLine()) != null && !line.isEmpty()) {
                Log.i(TAG, line);
            }

            seq++;
            String type = mType ? "RTP/AVP/UDP;unicast;client_port=5000"
                    : "RTP/AVP/TCP;unicast;interleaved=0-1";
            String video = "SETUP " + mHost + "/trackID=0 RTSP/1.0\r\n" +
                    "CSeq: " + seq + "\r\n" + auth + "Transport: " + type + "\r\n\r\n";
            w.write(video.getBytes());
            w.flush();

            String session = null;
            while ((line = r.readLine()) != null && !line.isEmpty()) {
                if (line.startsWith("Session:")) {
                    session = line.replace("Session:", "").split(";")[0].trim();
                }
                Log.i(TAG, line);
            }

            seq++;
            type = mType ? "RTP/AVP/UDP;unicast;client_port=5002"
                    : "RTP/AVP/TCP;unicast;interleaved=2-3";
            String audio = "SETUP " + mHost + "/trackID=1 RTSP/1.0\r\n" +
                    "CSeq: " + seq + "\r\n" + auth + "Transport: " + type + "\r\n" +
                    "Session: " + session + "\r\n\r\n";
            w.write(audio.getBytes());
            w.flush();

            while ((line = r.readLine()) != null && !line.isEmpty()) {
                Log.i(TAG, line);
            }

            seq++;
            String play = "PLAY " + mHost + " RTSP/1.0\r\n" +
                    "CSeq: " + seq + "\r\n" + auth + "Session: " + session + "\r\n\r\n";
            w.write(play.getBytes());
            w.flush();

            while ((line = r.readLine()) != null && !line.isEmpty()) {
                Log.i(TAG, line);
            }

            activeStream = true;
            if (mType) {
                try (DatagramSocket d = new DatagramSocket(5000)) {
                    udp_stream(d);
                }
            } else {
                tcp_stream(s.getInputStream());
            }
        }
    }

    private void udp_stream(DatagramSocket d) throws IOException {
        while (activeStream) {
            byte[] data = new byte[2048];
            DatagramPacket packet = new DatagramPacket(data, data.length);
            d.receive(packet);
            processPacket(new Frame(packet.getData(), packet.getLength()));
        }
    }

    private void tcp_stream(InputStream input) throws IOException {
        while (activeStream) {
            if (input.read() != 0x24) {
                continue;
            }

            int channel = input.read();
            int len = (input.read() << 8) | input.read();
            byte[] data = new byte[len];

            int read = 0;
            while (read < len) {
                read += input.read(data, read, len - read);
            }

            if (channel == 0 || channel == 2) {
                processPacket(new Frame(data, len));
            }
        }
    }

    private void processPacket(Frame frame) {
        byte[] data = frame.data();
        int payload = (data[1] & 0x7F);
        if (payload == 100) {
            processAudio(frame);
            return;
        } else if (payload == 99) {
            //Log.w(TAG, "AAC codec not supported");
            return;
        } else if (payload == 98) {
            //Log.w(TAG, "Opus codec not supported");
            return;
        } else if (payload == 97 || payload == 96) {
            codecH265 = payload == 97;
            Frame output = buildFrame(frame);
            if (output != null) {
                try {
                    nalQueue.put(output);
                } catch (InterruptedException e) {
                    Log.w(TAG, "Frame queue is full");
                }
            }

            return;
        }

        Log.w(TAG, "Unknown rtp type: " + payload);
    }

    private void startListener() {
        Log.i(TAG, "Start network listener");
        new Thread(() -> {
            while (listener) {
                try {
                    if (!activeStream) {
                        runOnUiThread(() -> {
                            mSurface.setVisibility(View.GONE);
                            mSurface.setVisibility(View.VISIBLE);
                            mConnect.setVisibility(View.VISIBLE);
                        });
                        rtsp_connect();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Cannot connect rtsp: " + e.getMessage());
                    SystemClock.sleep(1000);
                }
            }
        }).start();

        new Thread(() -> {
            while (listener) {
                if (activeStream) {
                    if (SystemClock.elapsedRealtime() - lastFrame > 3000) {
                        Log.w(TAG, "Stream is inactive");
                        activeStream = false;
                    }
                }
                SystemClock.sleep(1000);
            }
        }).start();

        new Thread(() -> {
            while (listener) {
                try {
                    Frame buffer = nalQueue.poll(5, TimeUnit.MILLISECONDS);
                    if (buffer != null) {
                        decodeFrame(buffer);
                    }
                } catch (InterruptedException e) {
                    Log.w(TAG, "Cannot decode video: " + e.getMessage());
                }
            }
        }).start();

        new Thread(() -> {
            while (listener) {
                try {
                    Frame buffer = pcmQueue.poll(5, TimeUnit.MILLISECONDS);
                    if (buffer != null) {
                        playAudio(buffer);
                    }
                } catch (InterruptedException e) {
                    Log.w(TAG, "Cannot decode audio: " + e.getMessage());
                }
            }
        }).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (listener) {
            listener = false;
            activeStream = false;
            closeDecoder();
            closeAudio();
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
