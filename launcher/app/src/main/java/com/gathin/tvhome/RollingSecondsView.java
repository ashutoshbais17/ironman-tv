package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.Calendar;

/**
 * clock_seconds: a ":SS" readout beside the Home clock where each digit rolls into place like an
 * odometer instead of jumping. Ticks on real second boundaries, not a fixed interval, so it never
 * drifts from the TextClock beside it.
 */
final class RollingSecondsView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Rect glyph = new Rect();
    private float digitHeight;
    /** The band the digits roll through: from just above the tallest digit to just under the baseline. */
    private float bandTop, bandBottom;
    private int tens, units;
    private int tensFrom = -1, unitsFrom = -1;
    private float tensOffset, unitsOffset; // 1 = just rolled, 0 = settled
    private ValueAnimator tensAnim, unitsAnim;
    private boolean running;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
            long delay = 1000 - (System.currentTimeMillis() % 1000);
            handler.postDelayed(this, delay);
        }
    };

    public RollingSecondsView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        paint.setColor(0xFFFFFFFF);
        paint.setTextAlign(Paint.Align.CENTER);
        // The Home clock's own letterSpacing, so the seconds sit as tight as its minutes.
        paint.setLetterSpacing(-0.04f);
        setTextSizePx(ctx.getResources().getDisplayMetrics().scaledDensity * 52f);
    }

    void setTextSizePx(float px) {
        paint.setTextSize(px);
        measureBand();
    }

    void setTypeface(Typeface tf) {
        paint.setTypeface(tf);
        measureBand();
    }

    private void measureBand() {
        digitHeight = paint.descent() - paint.ascent();
        paint.getTextBounds("0123456789", 0, 10, glyph);
        float baseline = -paint.ascent();
        float pad = paint.getTextSize() * 0.04f;
        bandTop = baseline + glyph.top - pad;
        bandBottom = baseline + glyph.bottom + pad;
        requestLayout();
        invalidate();
    }

    /** Starts ticking on the next real second boundary; call from onResume. */
    void start() {
        if (running) return;
        running = true;
        int s = Calendar.getInstance().get(Calendar.SECOND);
        tens = s / 10;
        units = s % 10;
        tensFrom = -1;
        unitsFrom = -1;
        handler.post(ticker);
    }

    /** Stops ticking; call from onPause so nothing animates off-screen. */
    void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (tensAnim != null) tensAnim.cancel();
        if (unitsAnim != null) unitsAnim.cancel();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }

    private void tick() {
        int s = Calendar.getInstance().get(Calendar.SECOND);
        int nt = s / 10, nu = s % 10;
        if (nu != units) {
            unitsFrom = units;
            units = nu;
            unitsAnim = animateRoll(unitsAnim, v -> {
                unitsOffset = v;
                invalidate();
            });
        }
        if (nt != tens) {
            tensFrom = tens;
            tens = nt;
            tensAnim = animateRoll(tensAnim, v -> {
                tensOffset = v;
                invalidate();
            });
        }
    }

    private interface FloatSink {
        void accept(float v);
    }

    private ValueAnimator animateRoll(ValueAnimator old, FloatSink sink) {
        if (old != null) old.cancel();
        ValueAnimator anim = ValueAnimator.ofFloat(1f, 0f);
        anim.setDuration(320);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> sink.accept((float) a.getAnimatedValue()));
        anim.start();
        return anim;
    }

    @Override
    public int getBaseline() {
        return (int) -paint.ascent();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        // One column per digit, each exactly one digit wide: measuring "00" and halving it (instead of a
        // single "0") keeps every digit 0-9 the same column width, since digits are not always monospaced.
        int w = (int) Math.ceil(paint.measureText("00"));
        int h = (int) Math.ceil(digitHeight);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float baseline = -paint.ascent();
        canvas.save();
        canvas.clipRect(0f, bandTop, getWidth(), bandBottom);
        float colW = getWidth() / 2f;
        drawDigitColumn(canvas, colW * 0.5f, tens, tensFrom, tensOffset, baseline);
        drawDigitColumn(canvas, colW * 1.5f, units, unitsFrom, unitsOffset, baseline);
        canvas.restore();
    }

    private void drawDigitColumn(Canvas canvas, float x, int digit, int from, float offset, float baseline) {
        if (offset <= 0f || from < 0) {
            canvas.drawText(String.valueOf(digit), x, baseline, paint);
            return;
        }
        float travel = bandBottom - bandTop;
        canvas.drawText(String.valueOf(from), x, baseline - travel * (1 - offset), paint);
        canvas.drawText(String.valueOf(digit), x, baseline + travel * offset, paint);
    }
}
