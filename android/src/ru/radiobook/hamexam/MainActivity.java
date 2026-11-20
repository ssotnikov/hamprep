package ru.radiobook.hamexam;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;

public final class MainActivity extends AppActivity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String PAGE_PATH = "/ham_prepare_ru.html";
    private static final String SCRIPT_PATH = "/handbook.js";
    private static final String PAGE_URL = "https://" + HOST + PAGE_PATH;

    private WebView webView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        WebView.setWebContentsDebuggingEnabled(
                (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new HandbookClient());
        setAppContent(webView);

        if (state == null || webView.restoreState(state) == null) {
            webView.loadUrl(PAGE_URL);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::navigateBack);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        webView.saveState(state);
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        updateSystemPalette(webView.getUrl(), configuration);
    }

    private void updateSystemPalette(String url, Configuration configuration) {
        if (url == null) return;
        Uri page = Uri.parse(url);
        if (!isLocal(page) || !PAGE_PATH.equals(page.getEncodedPath())) return;

        int nightMode = configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        String palette = nightMode == Configuration.UI_MODE_NIGHT_YES ? "dark" : "light";
        webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('radioexam-system-theme',"
                + "{detail:'" + palette + "'}));", null);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        navigateBack();
    }

    private void navigateBack() {
        if (webView.getUrl() == null) {
            finish();
            return;
        }
        webView.evaluateJavascript("typeof closeHandbookDialog === 'function' && closeHandbookDialog()", closed -> {
            if (isFinishing() || isDestroyed() || "true".equals(closed)) return;
            if (webView.canGoBack()) {
                webView.goBack();
            } else {
                finish();
            }
        });
    }

    @Override
    protected void onDestroy() {
        ((ViewGroup) webView.getParent()).removeView(webView);
        webView.destroy();
        super.onDestroy();
    }

    private static boolean isLocal(Uri url) {
        return "https".equals(url.getScheme()) && HOST.equals(url.getEncodedAuthority());
    }

    private final class HandbookClient extends WebViewClient {
        @Override
        public void onPageFinished(WebView view, String url) {
            updateSystemPalette(url, getResources().getConfiguration());
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (isLocal(url)) {
                String path = url.getPath();
                try {
                    if (PAGE_PATH.equals(path)) {
                        return new WebResourceResponse("text/html", "UTF-8",
                                getAssets().open("ham_prepare_ru.html"));
                    }
                    if (SCRIPT_PATH.equals(path)) {
                        return new WebResourceResponse("text/javascript", "UTF-8",
                                getAssets().open("handbook.js"));
                    }
                } catch (IOException error) {
                    Log.e("HamExam", "Unable to read bundled page", error);
                }
            }
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                    Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (!request.isForMainFrame()) {
                return true;
            }
            if (isLocal(url)) {
                return !PAGE_PATH.equals(url.getPath());
            }
            if (request.hasGesture() && "https".equals(url.getScheme()) && url.getHost() != null) {
                try {
                    Intent external = new Intent(Intent.ACTION_VIEW, url)
                            .addCategory(Intent.CATEGORY_BROWSABLE)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(external);
                } catch (ActivityNotFoundException error) {
                    Toast.makeText(MainActivity.this, "На устройстве нет браузера",
                            Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
    }
}
