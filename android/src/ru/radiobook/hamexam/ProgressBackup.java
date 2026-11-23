package ru.radiobook.hamexam;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.BooleanSupplier;

final class ProgressBackup {
    static final String SAVE_PATH = "/__progress-backup/save";
    static final String OPEN_PATH = "/__progress-backup/open";
    static final int CREATE_REQUEST = 43;
    static final int OPEN_REQUEST = 44;
    private static final int MAX_BYTES = 256 * 1024;

    private final Activity activity;
    private final WebView webView;
    private final BooleanSupplier isLocalPage;
    private int pendingRequest;
    private byte[] pendingBackup;
    private boolean busy;

    ProgressBackup(Activity activity, WebView webView, BooleanSupplier isLocalPage) {
        this.activity = activity;
        this.webView = webView;
        this.isLocalPage = isLocalPage;
    }

    void requestSave() {
        if (busy || !canRespond()) return;
        busy = true;
        webView.evaluateJavascript(
                "typeof window.takePendingProgressBackup === 'function' && window.takePendingProgressBackup()",
                this::launchSave);
    }

    private void launchSave(String payload) {
        try {
            if (!canRespond()) { finish(null, null); return; }
            if (payload == null || payload.length() > MAX_BYTES * 6 + 2) throw new IOException();
            Object result = new JSONTokener(payload).nextValue();
            if (!(result instanceof String)) throw new IOException();
            byte[] bytes = ((String) result).getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException();
            pendingBackup = bytes;
            String date = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
            launchPicker(CREATE_REQUEST, Intent.ACTION_CREATE_DOCUMENT, "radioexam-progress-" + date + ".json");
        } catch (IOException | JSONException | RuntimeException failure) {
            finish("save-error", null);
        }
    }

    void requestOpen() {
        if (busy || !canRespond()) return;
        busy = true;
        try {
            launchPicker(OPEN_REQUEST, Intent.ACTION_OPEN_DOCUMENT, null);
        } catch (RuntimeException failure) {
            finish("open-error", null);
        }
    }

    private void launchPicker(int request, String action, String filename) {
        Intent picker = new Intent(action).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json");
        if (filename != null) picker.putExtra(Intent.EXTRA_TITLE, filename);
        pendingRequest = request;
        activity.startActivityForResult(picker, request);
    }

    void deliverResult(int request, int result, Intent intent) {
        if (pendingRequest != request) {
            if (result == Activity.RESULT_OK) {
                Toast.makeText(activity, "Откройте резервную копию и повторите действие", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        byte[] backup = pendingBackup;
        pendingRequest = 0;
        pendingBackup = null;
        if (result != Activity.RESULT_OK) {
            finish("cancelled", null);
            return;
        }
        boolean save = request == CREATE_REQUEST;
        String failureStatus = save ? "save-error" : "open-error";
        Uri uri = intent == null ? null : intent.getData();
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()) || uri.getAuthority() == null) {
            finish(failureStatus, null);
            return;
        }
        new Thread(() -> {
            try {
                if (save) write(uri, backup);
                String json = save ? null : read(uri);
                activity.runOnUiThread(() -> finish(save ? "saved" : "loaded", json));
            } catch (IOException | RuntimeException failure) {
                activity.runOnUiThread(() -> finish(failureStatus, null));
            }
        }, "progress-backup").start();
    }

    private void write(Uri uri, byte[] backup) throws IOException {
        if (backup == null) throw new IOException();
        try (OutputStream output = activity.getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IOException();
            output.write(backup);
        }
    }

    private String read(Uri uri) throws IOException {
        try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) != -1) {
                if (buffer.size() + count > MAX_BYTES) throw new IOException();
                buffer.write(chunk, 0, count);
            }
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(buffer.toByteArray())).toString();
        }
    }

    private boolean canRespond() {
        return !activity.isDestroyed() && !activity.isFinishing() && isLocalPage.getAsBoolean();
    }

    private void finish(String status, String json) {
        busy = false;
        pendingRequest = 0;
        pendingBackup = null;
        if (status == null || !canRespond()) return;
        String data = json == null ? "null" : JSONObject.quote(json)
                .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('radioexam-progress-backup', {detail:{status:"
                + JSONObject.quote(status) + ", json:" + data + "}}))", null);
    }
}
