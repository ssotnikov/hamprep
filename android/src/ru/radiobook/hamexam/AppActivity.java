package ru.radiobook.hamexam;

import android.app.Activity;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;

abstract class AppActivity extends Activity {
    @SuppressWarnings("deprecation")
    FrameLayout setAppContent(View content, int backgroundColor) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(backgroundColor);
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
        return root;
    }

    @SuppressWarnings("deprecation")
    void setSystemBarPalette(int background, boolean light) {
        if (Build.VERSION.SDK_INT < 35) {
            getWindow().setStatusBarColor(background);
            getWindow().setNavigationBarColor(background);
        }
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            if (controller != null) controller.setSystemBarsAppearance(light ? mask : 0, mask);
        } else {
            View decor = getWindow().getDecorView();
            int mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility((decor.getSystemUiVisibility() & ~mask) | (light ? mask : 0));
        }
    }
}
