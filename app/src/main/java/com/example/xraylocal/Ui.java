package com.example.xraylocal;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.widget.ImageView;

import java.util.Locale;

final class Ui {
    static final int BG = 0xFFF4F5F7;
    static final int CARD = 0xFFFFFFFF;
    static final int TEXT = 0xFF1D1D1F;
    static final int SUB = 0xFF6B6F76;
    static final int ACCENT = 0xFF3F51B5;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static String duration(double seconds) {
        long s = Math.round(seconds);
        long h = s / 3600, m = (s % 3600) / 60, x = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, x)
                : String.format(Locale.US, "%d:%02d", m, x);
    }

    static String size(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " B";
        double k = bytes / 1024.0;
        if (k < 1024) return String.format(Locale.US, "%.0f KB", k);
        double m = k / 1024.0;
        if (m < 1024) return String.format(Locale.US, "%.1f MB", m);
        return String.format(Locale.US, "%.2f GB", m / 1024.0);
    }

    /** ImageView whose height is a fixed multiple of its width. */
    static final class RatioImageView extends ImageView {
        private final float ratio;

        RatioImageView(Context c, float heightOverWidth) {
            super(c);
            this.ratio = heightOverWidth;
            setScaleType(ScaleType.CENTER_CROP);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec);
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(w * ratio), MeasureSpec.EXACTLY));
        }
    }
}
