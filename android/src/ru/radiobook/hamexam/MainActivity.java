package ru.radiobook.hamexam;

import android.Manifest;
import android.app.UiModeManager;
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
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
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

    private static final String APPEARANCE_PATH = "/__appearance/";
    private static final String STARTUP_PREFERENCES = "handbook_appearance";
    private static final String STARTUP_APPEARANCE = "mode";
    private static final String READ_APPEARANCE = "(function(){var selected = "
            + "document.querySelector('input[name=appearance]:checked');"
            + "return selected ? selected.value : null;})()";
    private static final long STARTUP_TIMEOUT_MS = 15000;
    private static final long STARTUP_CHECK_MS = 60;

    private WebView webView;
    private FrameLayout contentRoot;
    private StartupCover startupCover;
    private StudyReminders reminders;
    private ExamHtmlExport examExport;
    private boolean requestingPermission;
    private boolean openStudyWhenReady;
    private boolean firstFrameRequested;
    private String appearanceMode;
    private String platformAppearanceMode;
    private long paletteRequest;
    private final Runnable checkStartup = this::checkStartupState;
    private final Runnable startupTimeout = () -> {
        if (waitingForFirstFrame()) {
            Log.w("HamExam", "Timed out waiting for handbook first frame");
            Toast.makeText(this, "Не удалось вовремя подготовить экран справочника", Toast.LENGTH_SHORT).show();
            showHandbook();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 31) {
            getSplashScreen().setOnExitAnimationListener(splash -> {
                if (waitingForFirstFrame()) startupCover.alignIcon(splash.getIconView());
                splash.remove();
            });
        }
        reminders = new StudyReminders(this);
        requestingPermission = state != null && state.getBoolean("requestingReminderPermission");
        openStudyWhenReady = StudyReminders.OPEN_STUDY.equals(getIntent().getAction());
        WebView.setWebContentsDebuggingEnabled(
                (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        webView = new WebView(this);
        webView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
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
        examExport = new ExamHtmlExport(this, webView, () -> isHandbook(webView.getUrl()), state);
        appearanceMode = getSharedPreferences(STARTUP_PREFERENCES, MODE_PRIVATE)
                .getString(STARTUP_APPEARANCE, "system");
        int background = backgroundFor(appearanceMode);
        contentRoot = setAppContent(webView, background);
        startupCover = new StartupCover(this, background);
        contentRoot.addView(startupCover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyWindowPalette();
        webView.postDelayed(startupTimeout, STARTUP_TIMEOUT_MS);

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
        examExport.saveState(state);
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
            if (!waitingForFirstFrame()) openStudySection();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ExamHtmlExport.CREATE_DOCUMENT_REQUEST) examExport.deliverResult(resultCode, data);
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
        if ("system".equals(appearanceMode)) updateSystemPalette(webView.getUrl(), configuration);
    }

    private int backgroundFor(String mode) {
        if ("dark".equals(mode)) return getColor(R.color.handbook_background_dark);
        if ("light".equals(mode)) return getColor(R.color.handbook_background_light);
        return getColor(R.color.handbook_startup_background);
    }

    private void applyWindowPalette() {
        if (contentRoot == null) return;
        applyWindowPalette(backgroundFor(appearanceMode));
    }

    private void applyWindowPalette(int background) {
        contentRoot.setBackgroundColor(background);
        webView.setBackgroundColor(background);
        if (startupCover != null) startupCover.setPalette(background);
        setSystemBarPalette(background, background == getColor(R.color.handbook_background_light));
    }

    private boolean selectAppearance(String mode) {
        int platformMode;
        switch (mode) {
            case "dark": platformMode = UiModeManager.MODE_NIGHT_YES; break;
            case "light": platformMode = UiModeManager.MODE_NIGHT_NO; break;
            case "system": platformMode = UiModeManager.MODE_NIGHT_AUTO; break;
            default: return false;
        }
        appearanceMode = mode;
        getSharedPreferences(STARTUP_PREFERENCES, MODE_PRIVATE).edit()
                .putString(STARTUP_APPEARANCE, mode).apply();
        if (waitingForFirstFrame()) {
            applyPlatformAppearance(mode, platformMode);
            applyWindowPalette();
        } else if ("system".equals(mode)) {
            applyPlatformAppearance(mode, platformMode);
            updateSystemPalette(webView.getUrl(), getResources().getConfiguration());
        } else {
            // Explicit choices already changed the page palette before dispatching the command.
            // Persist Android's launch mode after the visual callback so it cannot recolor the bars early.
            applyPaletteOnVisualState(++paletteRequest, backgroundFor(mode),
                    () -> applyPlatformAppearance(mode, platformMode));
        }
        return true;
    }

    private void applyPlatformAppearance(String mode, int platformMode) {
        if (Build.VERSION.SDK_INT >= 31 && !mode.equals(platformAppearanceMode)) {
            platformAppearanceMode = mode;
            getSystemService(UiModeManager.class).setApplicationNightMode(platformMode);
        }
    }

    private boolean waitingForFirstFrame() {
        return !isDestroyed() && !isFinishing() && startupCover != null;
    }

    private void checkStartupState() {
        if (!waitingForFirstFrame() || firstFrameRequested || !isHandbook(webView.getUrl())) return;
        webView.evaluateJavascript("document.documentElement.dataset.handbookReady === 'true' && "
                + READ_APPEARANCE, result -> {
            if (!waitingForFirstFrame() || firstFrameRequested || !isHandbook(webView.getUrl())) return;
            if (result == null || result.length() < 2 || result.charAt(0) != '"'
                    || !selectAppearance(result.substring(1, result.length() - 1))) {
                webView.postDelayed(checkStartup, STARTUP_CHECK_MS);
                return;
            }

            firstFrameRequested = true;
            openStudySection();
            webView.postVisualStateCallback(0, new WebView.VisualStateCallback() {
                @Override
                public void onComplete(long requestId) {
                    showHandbook();
                }
            });
        });
    }

    private void showHandbook() {
        if (!waitingForFirstFrame()) return;
        webView.removeCallbacks(startupTimeout);
        webView.removeCallbacks(checkStartup);
        contentRoot.removeView(startupCover);
        startupCover = null;
        webView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        openStudySection();
    }

    private void updateSystemPalette(String url, Configuration configuration) {
        if (!isHandbook(url)) return;
        int nightMode = configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        String palette = nightMode == Configuration.UI_MODE_NIGHT_YES ? "dark" : "light";
        synchronizeWindowPalette("window.dispatchEvent(new CustomEvent('radioexam-system-theme',"
                + "{detail:'" + palette + "'}));");
    }

    private void synchronizeWindowPalette(String update) {
        long request = ++paletteRequest;
        webView.evaluateJavascript(update + "document.documentElement.dataset.palette", result -> {
            if (!isCurrentPaletteRequest(request)) return;
            boolean dark = "\"dark\"".equals(result);
            if (!dark && !"\"light\"".equals(result)) return;
            int background = getColor(dark ? R.color.handbook_background_dark : R.color.handbook_background_light);
            applyPaletteOnVisualState(request, background, null);
        });
    }

    private void applyPaletteOnVisualState(long request, int background, Runnable afterPalette) {
        webView.postVisualStateCallback(request, new WebView.VisualStateCallback() {
            @Override
            public void onComplete(long requestId) {
                if (!isCurrentPaletteRequest(requestId)) return;
                applyWindowPalette(background);
                if (afterPalette != null) afterPalette.run();
            }
        });
    }

    private boolean isCurrentPaletteRequest(long request) {
        return request == paletteRequest && !isDestroyed() && !isFinishing() && isHandbook(webView.getUrl());
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
        webView.removeCallbacks(startupTimeout);
        webView.removeCallbacks(checkStartup);
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
            if (!isHandbook(url)) return;
            updateSystemPalette(url, getResources().getConfiguration());
            publishReminderState();
            examExport.publishProfile();
            if (waitingForFirstFrame()) {
                view.removeCallbacks(checkStartup);
                checkStartupState();
            } else {
                openStudySection();
            }
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
                if (ExamHtmlExport.SAVE_PATH.equals(path) || ExamHtmlExport.FORGET_PATH.equals(path)) {
                    if (isHandbook(view.getUrl()) && request.hasGesture() && "GET".equals(request.getMethod())
                            && url.getEncodedQuery() == null && url.getEncodedFragment() == null && !request.isRedirect()) {
                        if (ExamHtmlExport.SAVE_PATH.equals(path)) examExport.requestSave();
                        else examExport.forgetProfile();
                    }
                    return true;
                }
                if (path != null && path.startsWith(APPEARANCE_PATH)) {
                    if (isHandbook(view.getUrl()) && "GET".equals(request.getMethod())
                            && url.getEncodedQuery() == null && url.getEncodedFragment() == null && !request.isRedirect()) {
                        selectAppearance(path.substring(APPEARANCE_PATH.length()));
                    }
                    return true;
                }
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
