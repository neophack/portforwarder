package com.aucneon.portforwarder;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public class TelnetActivity extends AppCompatActivity {
    private static final String TAG = "TelnetActivity";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_TITLE = "title";

    private TextView tvOutput;
    private EditText etInput;
    private Button btnSend;
    private Button btnDisconnect;
    private TextView tvConnectionStatus;
    private ScrollView scrollView;

    private Socket socket;
    private OutputStream outputStream;
    private volatile boolean isConnected = false;
    private Handler mainHandler;
    private StringBuilder outputBuffer = new StringBuilder();

    public static void launch(Context context, String host, int port, String title) {
        Intent intent = new Intent(context, TelnetActivity.class);
        intent.putExtra(EXTRA_HOST, host);
        intent.putExtra(EXTRA_PORT, port);
        intent.putExtra(EXTRA_TITLE, title);
        context.startActivity(intent);
    }

    public static void launch(Context context, ForwardConfig config) {
        launch(context, "127.0.0.1", config.listenPort, config.name + " - Telnet");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_telnet);

        mainHandler = new Handler(Looper.getMainLooper());

        tvOutput = findViewById(R.id.tv_telnet_output);
        etInput = findViewById(R.id.et_telnet_input);
        btnSend = findViewById(R.id.btn_telnet_send);
        btnDisconnect = findViewById(R.id.btn_telnet_disconnect);
        tvConnectionStatus = findViewById(R.id.tv_connection_status);
        scrollView = findViewById(R.id.sv_telnet_output);

        tvOutput.setMovementMethod(new ScrollingMovementMethod());

        String host = getIntent().getStringExtra(EXTRA_HOST);
        int port = getIntent().getIntExtra(EXTRA_PORT, 23);
        String title = getIntent().getStringExtra(EXTRA_TITLE);

        if (title != null) {
            setTitle(title);
        }

        btnSend.setOnClickListener(v -> sendCommand());
        btnDisconnect.setOnClickListener(v -> {
            if (isConnected) {
                disconnect();
            } else {
                finish();
            }
        });

        etInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCommand();
                return true;
            }
            return false;
        });

        // Connect
        appendOutput(getString(R.string.connecting_format, host, port));
        connect(host, port);
    }

    private void connect(String host, int port) {
        new Thread(() -> {
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 5000);
                socket.setSoTimeout(0);
                outputStream = socket.getOutputStream();
                isConnected = true;

                mainHandler.post(() -> {
                    tvConnectionStatus.setText(getString(R.string.connected_format, host, port));
                    tvConnectionStatus.setTextColor(ContextCompat.getColor(TelnetActivity.this, R.color.ios_green));
                    appendOutput(getString(R.string.connect_success));
                    btnSend.setEnabled(true);
                    btnDisconnect.setText(getString(R.string.disconnect));
                });

                // Read loop
                InputStream inputStream = socket.getInputStream();
                byte[] buffer = new byte[4096];
                int bytesRead;
                while (isConnected && (bytesRead = inputStream.read(buffer)) != -1) {
                    String data = new String(buffer, 0, bytesRead);
                    // Strip basic ANSI escape sequences
                    data = stripAnsiCodes(data);
                    final String text = data;
                    mainHandler.post(() -> appendOutput(text));
                }
            } catch (IOException e) {
                Log.e(TAG, "Connection error", e);
                mainHandler.post(() -> {
                    appendOutput("\n" + getString(R.string.connect_error, e.getMessage()));
                    tvConnectionStatus.setText(getString(R.string.disconnected));
                    tvConnectionStatus.setTextColor(ContextCompat.getColor(TelnetActivity.this, R.color.ios_red));
                    isConnected = false;
                    btnSend.setEnabled(false);
                    btnDisconnect.setText(getString(R.string.close));
                });
            }
        }).start();
    }

    private void sendCommand() {
        String command = etInput.getText().toString();
        if (!isConnected || outputStream == null) {
            Toast.makeText(this, getString(R.string.not_connected), Toast.LENGTH_SHORT).show();
            return;
        }

        etInput.setText("");
        appendOutput("> " + command + "\n");

        new Thread(() -> {
            try {
                outputStream.write((command + "\r\n").getBytes());
                outputStream.flush();
            } catch (IOException e) {
                mainHandler.post(() -> appendOutput(getString(R.string.send_failed, e.getMessage())));
            }
        }).start();
    }

    private void appendOutput(String text) {
        outputBuffer.append(text);
        // Limit buffer size
        if (outputBuffer.length() > 50000) {
            outputBuffer.delete(0, outputBuffer.length() - 40000);
        }
        tvOutput.setText(outputBuffer.toString());
        // Auto scroll to bottom
        scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private String stripAnsiCodes(String text) {
        // Strip ANSI escape sequences
        return text.replaceAll("\\x1B\\[[0-9;]*[a-zA-Z]", "")
                   .replaceAll("\\x1B\\[\\?[0-9;]*[a-zA-Z]", "");
    }

    private void disconnect() {
        isConnected = false;
        new Thread(() -> {
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error closing socket", e);
            }
            mainHandler.post(() -> {
                appendOutput(getString(R.string.disconnected_message));
                tvConnectionStatus.setText(getString(R.string.disconnected));
                tvConnectionStatus.setTextColor(ContextCompat.getColor(TelnetActivity.this, R.color.ios_red));
                btnSend.setEnabled(false);
                btnDisconnect.setText(getString(R.string.close));
            });
        }).start();
    }

    @Override
    protected void onDestroy() {
        disconnect();
        super.onDestroy();
    }
}
