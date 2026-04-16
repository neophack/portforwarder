package com.aucneon.portforwarder;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class WebPreviewActivity extends AppCompatActivity {
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";

    private WebView webView;
    private ProgressBar progressBar;
    private TextView tvUrl;

    public static void launch(Context context, String url, String title) {
        Intent intent = new Intent(context, WebPreviewActivity.class);
        intent.putExtra(EXTRA_URL, url);
        intent.putExtra(EXTRA_TITLE, title);
        context.startActivity(intent);
    }

    public static void launch(Context context, ForwardConfig config) {
        String scheme = (config.targetPort == 443 || config.targetPort == 8443) ? "https" : "http";
        String url = scheme + "://127.0.0.1:" + config.listenPort;
        launch(context, url, config.name);
    }

    /**
     * Open URL in external browser.
     */
    public static void openInBrowser(Context context, ForwardConfig config) {
        String scheme = (config.targetPort == 443 || config.targetPort == 8443) ? "https" : "http";
        String url = scheme + "://127.0.0.1:" + config.listenPort;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        context.startActivity(intent);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web_preview);

        String url = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);

        webView = findViewById(R.id.web_view);
        progressBar = findViewById(R.id.progress_bar);
        tvUrl = findViewById(R.id.tv_url);
        Button btnBack = findViewById(R.id.btn_back);
        Button btnExternal = findViewById(R.id.btn_external);
        Button btnRefresh = findViewById(R.id.btn_web_refresh);

        if (title != null) {
            setTitle(title);
        }
        tvUrl.setText(url != null ? url : "");

        // Configure WebView
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
            }
        });

        btnBack.setOnClickListener(v -> finish());
        btnExternal.setOnClickListener(v -> {
            String currentUrl = webView.getUrl();
            if (currentUrl != null) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)));
            }
        });
        btnRefresh.setOnClickListener(v -> webView.reload());

        if (url != null) {
            webView.loadUrl(url);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
