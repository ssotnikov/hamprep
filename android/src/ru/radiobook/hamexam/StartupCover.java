package ru.radiobook.hamexam;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class StartupCover extends FrameLayout {
    private static final int ICON_DP = 104;
    // Android places the 288 dp drawable in a 192 dp icon view.
    private static final float SYSTEM_ICON_FRACTION = ICON_DP / 192f;
    private final LinearLayout identity;
    private final ImageView icon;
    private final TextView name;
    private final TextView version;

    StartupCover(Context context, int background) {
        super(context);
        setClickable(true);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        identity = new LinearLayout(context);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.setGravity(Gravity.CENTER_HORIZONTAL);
        identity.setPadding(dp(10), 0, dp(10), 0);
        addView(identity, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        icon = new ImageView(context);
        icon.setImageResource(R.drawable.handbook_startup_icon);
        icon.setScaleType(ImageView.ScaleType.FIT_XY);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        identity.addView(icon, new LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)));

        name = addLabel(identity, 23, 18);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setText(context.getApplicationInfo().loadLabel(context.getPackageManager()));

        version = addLabel(identity, 15, 7);
        try {
            String versionName = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            version.setText(context.getString(R.string.handbook_startup_version, versionName));
        } catch (PackageManager.NameNotFoundException error) {
            Log.w("HamExam", "Unable to read installed version", error);
            version.setVisibility(View.GONE);
        }

        // The icon center anchors the composition, even if the name wraps.
        addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            float iconCenter = identity.getTop() + icon.getTop() + icon.getHeight() / 2f;
            identity.setTranslationY(getHeight() * .43f - iconCenter);
        });
        setPalette(background);
    }

    void setPalette(int background) {
        boolean dark = background == getContext().getColor(R.color.handbook_background_dark);
        setBackgroundColor(background);
        name.setTextColor(dark ? 0xffedf0f4 : 0xff101417);
        version.setTextColor(dark ? 0xffadb8c6 : 0xff49545e);
    }

    void alignIcon(View systemIcon) {
        if (systemIcon == null || systemIcon.getWidth() == 0 || systemIcon.getHeight() == 0
                || icon.getWidth() == 0 || icon.getHeight() == 0) return;
        int[] systemPosition = new int[2];
        int[] coverPosition = new int[2];
        systemIcon.getLocationInWindow(systemPosition);
        icon.getLocationInWindow(coverPosition);
        float dx = systemPosition[0] + systemIcon.getWidth() / 2f - coverPosition[0] - icon.getWidth() / 2f;
        float dy = systemPosition[1] + systemIcon.getHeight() / 2f - coverPosition[1] - icon.getHeight() / 2f;
        float diameter = Math.min(systemIcon.getWidth(), systemIcon.getHeight()) * SYSTEM_ICON_FRACTION;
        icon.setScaleX(diameter / icon.getWidth());
        icon.setScaleY(diameter / icon.getHeight());
        identity.setTranslationX(identity.getTranslationX() + dx);
        identity.setTranslationY(identity.getTranslationY() + dy);
    }

    private TextView addLabel(LinearLayout identity, int size, int topMargin) {
        TextView label = new TextView(getContext());
        label.setGravity(Gravity.CENTER);
        label.setTextSize(size);
        label.setIncludeFontPadding(false);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMargin);
        identity.addView(label, params);
        return label;
    }

    private int dp(int size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
    }
}
