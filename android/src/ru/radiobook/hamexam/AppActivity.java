package ru.radiobook.hamexam;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;

abstract class AppActivity extends Activity {
    @SuppressWarnings("deprecation")
    void setAppContent(View content) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        root.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                int types = WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.ime();
                Insets safeArea = insets.getInsets(types);
                view.setPadding(safeArea.left, safeArea.top, safeArea.right, safeArea.bottom);
                return new WindowInsets.Builder(insets).setInsets(types, Insets.NONE).build();
            });
        }
        setContentView(root);
        if (Build.VERSION.SDK_INT == 26) {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }
}
