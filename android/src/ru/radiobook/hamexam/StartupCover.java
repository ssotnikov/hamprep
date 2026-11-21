package ru.radiobook.hamexam;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class StartupCover extends FrameLayout {
    private final ImageView icon;
    private final TextView name;
    private final TextView version;

    StartupCover(Context context, int background) {
        super(context);
        setClickable(true);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        LinearLayout identity = new LinearLayout(context);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.setGravity(Gravity.CENTER_HORIZONTAL);
        identity.setPadding(dp(10), 0, dp(10), 0);
        addView(identity, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        icon = new ImageView(context);
        icon.setImageResource(R.drawable.handbook_startup_icon);
        icon.setScaleType(ImageView.ScaleType.FIT_XY);
        GradientDrawable mask = iconShape();
        mask.setColor(0xff1249dc);
        icon.setBackground(mask);
        icon.setClipToOutline(true);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        identity.addView(icon, new LinearLayout.LayoutParams(dp(104), dp(104)));

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
        GradientDrawable outline = iconShape();
        outline.setStroke(dp(1), dark ? 0xff7488a9 : Color.TRANSPARENT);
        icon.setForeground(outline);
    }

    private GradientDrawable iconShape() {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.TRANSPARENT);
        shape.setCornerRadius(dp(25));
        return shape;
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
