package ru.radiobook.hamexam;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

final class ExamHtmlExport {
    static final String SAVE_PATH = "/__exam-export/save";
    static final String FORGET_PATH = "/__exam-export/forget";
    static final int CREATE_DOCUMENT_REQUEST = 42;
    private static final String PREFERENCES = "exam_export_profile";
    private static final String DRAFT_STATE = "exam-export.pending-file";
    private static final String FIRST = "first";
    private static final String LAST = "last";
    private static final Pattern DRAFT_NAME = Pattern.compile("exam-export-[0-9a-f-]{36}\\.json");
    private static final Pattern REPORT_NAME = Pattern.compile("radioexam-category-[1-4]-[0-9]{4}-[0-9]{2}-[0-9]{2}\\.html");
    private static final Pattern INVALID_NAME = Pattern.compile("[\\x00-\\x1f\\x7f-\\x9f\\x{2028}-\\x{202e}\\x{2066}-\\x{2069}]");
    private static final int MAX_HTML_BYTES = 256 * 1024;
    private static final int MAX_DRAFT_BYTES = MAX_HTML_BYTES * 4;
    private static final long DRAFT_MAX_AGE_MS = 15 * 60 * 1000L;

    private final Activity activity;
    private final WebView webView;
    private final BooleanSupplier isLocalPage;
    private final SharedPreferences preferences;
    private String pendingName;
    private boolean busy;
    private boolean restored;
    private String deferredStatus;

    ExamHtmlExport(Activity activity, WebView webView, BooleanSupplier isLocalPage, Bundle state) {
        this.activity = activity;
        this.webView = webView;
        this.isLocalPage = isLocalPage;
        preferences = activity.getSharedPreferences(PREFERENCES, Activity.MODE_PRIVATE);
        pendingName = state == null ? null : state.getString(DRAFT_STATE);
        restored = pendingName != null;
        sweepDrafts();
    }

    void saveState(Bundle state) {
        if (pendingName != null) state.putString(DRAFT_STATE, pendingName);
    }

    void publishProfile() {
        String status = deferredStatus == null ? "profile" : deferredStatus;
        deferredStatus = null;
        sendStatus(status);
    }

    void requestSave() {
        if (busy || pendingName != null || !isLocalPage.getAsBoolean()) return;
        busy = true;
        webView.evaluateJavascript(
                "typeof window.takePendingExamHtmlExport === 'function' && window.takePendingExamHtmlExport()",
                this::launchPicker);
    }

    void forgetProfile() {
        if (busy || pendingName != null || !isLocalPage.getAsBoolean()) return;
        sendStatus(writeProfile(null) ? "forgot" : "forget-error");
    }

    private void launchPicker(String payload) {
        busy = false;
        if (activity.isDestroyed() || activity.isFinishing() || !isLocalPage.getAsBoolean()) return;

        sweepDrafts();
        try {
            Report report = Report.read(payload);
            pendingName = "exam-export-" + UUID.randomUUID() + ".json";
            File file = draftFile(pendingName);
            Files.write(file.toPath(), payload.getBytes(StandardCharsets.UTF_8));
            Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .setType("text/html")
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_TITLE, report.filename);
            activity.startActivityForResult(picker, CREATE_DOCUMENT_REQUEST);
        } catch (IOException | JSONException | RuntimeException failure) {
            deleteDraft(draftFile(pendingName));
            pendingName = null;
            sendStatus("save-error");
        }
    }

    void deliverResult(int resultCode, Intent data) {
        File draft = draftFile(pendingName);
        pendingName = null;
        if (resultCode != Activity.RESULT_OK) {
            deleteDraft(draft);
            sendStatus("cancelled");
            return;
        }
        Uri destination = resultUri(data);
        if (destination == null || !recent(draft)) {
            deleteDraft(draft);
            notifyResult("save-error");
            return;
        }

        busy = true;
        new Thread(() -> {
            String status;
            try {
                if (draft.length() > MAX_DRAFT_BYTES) throw new IOException("Draft exceeds its limit");
                Report report = Report.read(new String(Files.readAllBytes(draft.toPath()), StandardCharsets.UTF_8));
                try (OutputStream out = activity.getContentResolver().openOutputStream(destination, "wt")) {
                    if (out == null) throw new IOException("Document stream unavailable");
                    out.write(report.html);
                }
                status = writeProfile(report) ? "saved" : "saved-profile-error";
            } catch (IOException | JSONException | RuntimeException failure) {
                status = "save-error";
            } finally {
                deleteDraft(draft);
            }
            String outcome = status;
            activity.runOnUiThread(() -> {
                busy = false;
                if (!activity.isDestroyed() && !activity.isFinishing()) notifyResult(outcome);
            });
        }, "exam-html-export").start();
    }

    private void notifyResult(String status) {
        if (restored || !isLocalPage.getAsBoolean()) {
            String text = "saved".equals(status) ? "HTML-файл сохранен"
                    : "saved-profile-error".equals(status) ? "HTML-файл сохранен. Имя и фамилию запомнить не удалось"
                    : "Не удалось сохранить HTML-файл. Начните экспорт снова";
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
        }
        restored = false;
        sendStatus(status);
    }

    private void sendStatus(String status) {
        if (!isLocalPage.getAsBoolean()) {
            deferredStatus = status;
            return;
        }
        try {
            String first = preferences.getString(FIRST, "");
            String last = preferences.getString(LAST, "");
            JSONObject state = new JSONObject().put("status", status)
                    .put("profile", validName(first) && validName(last)
                            ? new JSONObject().put(FIRST, first).put(LAST, last) : JSONObject.NULL);
            webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('radioexam-export-result', {detail:"
                    + state + "}));", null);
        } catch (JSONException failure) {
            throw new IllegalStateException("Could not encode export state", failure);
        }
    }

    private boolean writeProfile(Report report) {
        try {
            SharedPreferences.Editor edit = preferences.edit();
            if (report == null) edit.remove(FIRST).remove(LAST);
            else edit.putString(FIRST, report.first).putString(LAST, report.last);
            return edit.commit();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static Uri resultUri(Intent data) {
        if (data == null) return null;
        Uri uri = data.getData();
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()) || uri.getAuthority() == null) return null;
        ClipData clip = data.getClipData();
        if (clip != null && (clip.getItemCount() != 1 || !uri.equals(clip.getItemAt(0).getUri()))) return null;
        return uri;
    }

    private File draftFile(String name) {
        return name != null && DRAFT_NAME.matcher(name).matches() ? new File(activity.getCacheDir(), name) : null;
    }

    private static boolean recent(File draft) {
        if (draft == null || !draft.isFile()) return false;
        long age = System.currentTimeMillis() - draft.lastModified();
        return age >= 0 && age <= DRAFT_MAX_AGE_MS;
    }

    private static void deleteDraft(File draft) {
        if (draft != null) draft.delete();
    }

    private void sweepDrafts() {
        File[] expired = activity.getCacheDir().listFiles((directory, name) ->
                DRAFT_NAME.matcher(name).matches() && !recent(new File(directory, name)));
        if (expired != null) {
            for (File draft : expired) deleteDraft(draft);
        }
    }

    private static boolean validName(String name) {
        return !name.isEmpty() && name.equals(name.trim()) && name.codePointCount(0, name.length()) <= 80
                && !INVALID_NAME.matcher(name).find();
    }

    private static final class Report {
        final String first;
        final String last;
        final String filename;
        final byte[] html;

        private Report(String first, String last, String filename, byte[] html) {
            this.first = first;
            this.last = last;
            this.filename = filename;
            this.html = html;
        }

        static Report read(String payload) throws JSONException, IOException {
            if (payload == null || payload.length() > MAX_DRAFT_BYTES || payload.getBytes(StandardCharsets.UTF_8).length > MAX_DRAFT_BYTES) {
                throw new IOException("Invalid export payload length");
            }
            JSONObject data = new JSONObject(payload);
            String first = data.getString(FIRST);
            String last = data.getString(LAST);
            String filename = data.getString("filename");
            byte[] html = data.getString("html").getBytes(StandardCharsets.UTF_8);
            if (!validName(first) || !validName(last) || !REPORT_NAME.matcher(filename).matches()
                    || html.length == 0 || html.length > MAX_HTML_BYTES) {
                throw new IOException("Invalid export payload");
            }
            return new Report(first, last, filename, html);
        }
    }
}
