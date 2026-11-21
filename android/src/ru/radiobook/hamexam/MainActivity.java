package ru.radiobook.hamexam;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends AppActivity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String PAGE_PATH = "/ham_prepare_ru.html";
    private static final String SCRIPT_PATH = "/handbook.js";
    private static final String PAGE_URL = "https://" + HOST + PAGE_PATH;

    private static final String REMINDER_PATH = "/__study-reminders/";
    private static final Pattern REMINDER_SCHEDULE = Pattern.compile("schedule/([1237])/([0-9]{1,4})");
    private static final int NOTIFICATION_PERMISSION = 31;

    private WebView webView;
    private StudyReminders reminders;
    private boolean requestingPermission;
    private boolean openStudyWhenReady;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        reminders = new StudyReminders(this);
        requestingPermission = state != null && state.getBoolean("requestingReminderPermission");
        openStudyWhenReady = StudyReminders.OPEN_STUDY.equals(getIntent().getAction());
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
        state.putBoolean("requestingReminderPermission", requestingPermission);
        webView.saveState(state);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reminders.refresh();
        publishReminderState();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (StudyReminders.OPEN_STUDY.equals(intent.getAction())) {
            openStudyWhenReady = true;
            openStudySection();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != NOTIFICATION_PERMISSION || !requestingPermission) return;
        requestingPermission = false;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            reminders.enable();
        }
        publishReminderState();
    }

    private void publishReminderState() {
        if (!isHandbook(webView.getUrl())) return;
        boolean blocked = !reminders.canNotify() && (reminders.hasPermission() || reminders.requestedPermission());
        webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('radioexam-reminders', {detail:{"
                + "enabled:" + reminders.enabled() + ",blocked:" + blocked + ",pending:" + requestingPermission
                + ",days:" + reminders.days() + ",minutes:" + reminders.minutes() + "}}));", null);
    }

    private void openStudySection() {
        if (!openStudyWhenReady || !isHandbook(webView.getUrl())) return;
        openStudyWhenReady = false;
        webView.evaluateJavascript("if (typeof closeHandbookDialog === 'function') closeHandbookDialog();"
                + "if (typeof revealBelowTopbar === 'function') revealBelowTopbar(document.getElementById('sec-1'));",
                null);
    }

    private void requestReminders() {
        if (reminders.hasPermission()) {
            reminders.enable();
        } else if (Build.VERSION.SDK_INT >= 33 && !requestingPermission
                && (!reminders.requestedPermission()
                    || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            requestingPermission = true;
            reminders.markPermissionRequested();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
        }
    }

    private void handleReminderCommand(String command, boolean gesture) {
        Matcher schedule = REMINDER_SCHEDULE.matcher(command);
        if (schedule.matches()) {
            reminders.setSchedule(Integer.parseInt(schedule.group(1)), Integer.parseInt(schedule.group(2)));
        } else if ("studied".equals(command)) {
            reminders.studied();
            return;
        } else if ("off".equals(command)) {
            reminders.disable();
        } else if ("on".equals(command) && gesture) {
            requestReminders();
        } else if ("settings".equals(command) && gesture) {
            Intent settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            try {
                startActivity(settings);
            } catch (ActivityNotFoundException unavailable) {
                Toast.makeText(this, "Настройки уведомлений недоступны", Toast.LENGTH_SHORT).show();
            }
        }
        publishReminderState();
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

    private static boolean isHandbook(String url) {
        if (url == null) return false;
        Uri page = Uri.parse(url);
        return isLocal(page) && PAGE_PATH.equals(page.getEncodedPath()) && page.getEncodedQuery() == null;
    }

    private final class HandbookClient extends WebViewClient {
        @Override
        public void onPageFinished(WebView view, String url) {
            updateSystemPalette(url, getResources().getConfiguration());
            publishReminderState();
            openStudySection();
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (isLocal(url) && url.getEncodedQuery() == null) {
                String path = url.getEncodedPath();
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
                String path = url.getEncodedPath();
                if (path != null && path.startsWith(REMINDER_PATH) && isHandbook(view.getUrl())
                        && "GET".equals(request.getMethod()) && url.getEncodedQuery() == null
                        && url.getEncodedFragment() == null && !request.isRedirect()) {
                    handleReminderCommand(path.substring(REMINDER_PATH.length()), request.hasGesture());
                }
                return !isHandbook(url.toString());
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
