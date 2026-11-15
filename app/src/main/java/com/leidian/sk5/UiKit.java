package com.leidian.sk5;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/** 界面小工具：统一间距、字号、圆角，避免每个页面各写一套。 */
public final class UiKit {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UiKit() {
    }

    public static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }

    public static int color(Context context, int res) {
        return context.getResources().getColor(res, context.getTheme());
    }

    public static void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            MAIN.post(action);
        }
    }

    public static TextView text(Context context, CharSequence value, float sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        return view;
    }

    public static GradientDrawable rounded(Context context, float radiusDp, int fill) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(context, radiusDp));
        drawable.setColor(fill);
        return drawable;
    }

    public static GradientDrawable roundedStroke(Context context, float radiusDp, int fill, int stroke) {
        GradientDrawable drawable = rounded(context, radiusDp, fill);
        drawable.setStroke(dp(context, 1), stroke);
        return drawable;
    }

    public static View divider(Context context) {
        View line = new View(context);
        line.setBackgroundColor(color(context, R.color.divider));
        return line;
    }

    public static void tint(ImageView view, int color) {
        view.setColorFilter(color);
    }

    public static void setPadding(View view, float left, float top, float right, float bottom) {
        Context context = view.getContext();
        view.setPadding(dp(context, left), dp(context, top), dp(context, right), dp(context, bottom));
    }

    public static TextView chip(Context context, CharSequence value) {
        TextView view = text(context, value, 12f, color(context, R.color.text_primary));
        view.setBackground(rounded(context, 100, color(context, R.color.card_alt)));
        setPadding(view, 10, 5, 10, 5);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    public static TextView button(Context context, CharSequence value, boolean primary) {
        TextView view = text(context, value, 14f, primary
                ? 0xFFFFFFFF : color(context, R.color.text_primary));
        view.setBackgroundResource(primary
                ? R.drawable.bg_button_primary : R.drawable.bg_button_ghost);
        setPadding(view, 18, 11, 18, 11);
        view.setGravity(Gravity.CENTER);
        view.setClickable(true);
        return view;
    }

    public static ImageView icon(Context context, int res, float sizeDp) {
        ImageView view = new ImageView(context);
        view.setImageResource(res);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int size = dp(context, sizeDp);
        view.setLayoutParams(new android.widget.LinearLayout.LayoutParams(size, size));
        return view;
    }
}
