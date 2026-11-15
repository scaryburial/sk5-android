package com.leidian.sk5;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** 主界面的圆形开关。状态全部由 ProxyManager 的 State 驱动。 */
public class CircleButton extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();

    private ProxyManager.State state = ProxyManager.State.DISCONNECTED;
    private ValueAnimator spinner;
    private float spinAngle;

    public CircleButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        setClickable(true);
    }

    public void setState(ProxyManager.State value) {
        if (state == value) {
            return;
        }
        state = value;
        if (value == ProxyManager.State.CONNECTING) {
            startSpin();
        } else {
            stopSpin();
        }
        invalidate();
    }

    private void startSpin() {
        if (spinner != null) {
            return;
        }
        spinner = ValueAnimator.ofFloat(0f, 360f);
        spinner.setDuration(1100);
        spinner.setRepeatCount(ValueAnimator.INFINITE);
        spinner.setInterpolator(new LinearInterpolator());
        spinner.addUpdateListener(animation -> {
            spinAngle = (float) animation.getAnimatedValue();
            invalidate();
        });
        spinner.start();
    }

    private void stopSpin() {
        if (spinner != null) {
            spinner.cancel();
            spinner = null;
        }
        spinAngle = 0f;
    }

    @Override
    protected void onDetachedFromWindow() {
        stopSpin();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = UiKit.dp(getContext(), 208);
        int width = resolveSize(size, widthMeasureSpec);
        int height = resolveSize(size, heightMeasureSpec);
        int side = Math.min(width, height);
        setMeasuredDimension(side, side);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Context context = getContext();
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(getWidth(), getHeight()) / 2f - UiKit.dp(context, 22);

        boolean connected = state == ProxyManager.State.CONNECTED;
        boolean busy = state == ProxyManager.State.CONNECTING
                || state == ProxyManager.State.DISCONNECTING;

        int brand = UiKit.color(context, R.color.brand);
        int brandDeep = UiKit.color(context, R.color.brand_deep);
        int ok = UiKit.color(context, R.color.ok);
        int cardAlt = UiKit.color(context, R.color.card_alt);

        if (connected) {
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            for (int i = 3; i >= 1; i--) {
                paint.setColor(ok);
                paint.setAlpha(10 + i * 5);
                canvas.drawCircle(cx, cy, radius + UiKit.dp(context, 5) * i, paint);
            }
        }

        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);
        if (connected) {
            paint.setShader(new LinearGradient(cx - radius, cy - radius, cx + radius, cy + radius,
                    ok, 0xFF128A5A, Shader.TileMode.CLAMP));
        } else if (busy) {
            paint.setShader(new LinearGradient(cx - radius, cy - radius, cx + radius, cy + radius,
                    brand, brandDeep, Shader.TileMode.CLAMP));
        } else {
            paint.setShader(null);
            paint.setColor(cardAlt);
        }
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(UiKit.dp(context, 1.5f));
        paint.setColor(connected ? 0x00000000 : UiKit.color(context, R.color.divider));
        canvas.drawCircle(cx, cy, radius, paint);

        if (busy) {
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha(210);
            paint.setStrokeWidth(UiKit.dp(context, 2.5f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            arcBounds.set(cx - radius + UiKit.dp(context, 1), cy - radius + UiKit.dp(context, 1),
                    cx + radius - UiKit.dp(context, 1), cy + radius - UiKit.dp(context, 1));
            canvas.drawArc(arcBounds, spinAngle, 62f, false, paint);
            paint.setAlpha(255);
        }

        String label = connected ? context.getString(R.string.action_disconnect)
                : busy ? context.getString(R.string.state_connecting)
                : context.getString(R.string.action_connect);
        textPaint.setColor(connected || busy ? 0xFFFFFFFF : UiKit.color(context, R.color.text_primary));
        textPaint.setTextSize(UiKit.dp(context, 19));
        canvas.drawText(label, cx, cy + UiKit.dp(context, 7), textPaint);

        String hint = connected ? context.getString(R.string.state_sub_connected)
                : busy ? context.getString(R.string.state_sub_connecting)
                : context.getString(R.string.state_sub_idle);
        textPaint.setTextSize(UiKit.dp(context, 11));
        textPaint.setColor(connected ? 0xCCFFFFFF : UiKit.color(context, R.color.text_secondary));
        canvas.drawText(hint, cx, cy + UiKit.dp(context, 26), textPaint);

        paint.setStrokeCap(Paint.Cap.BUTT);
    }
}
