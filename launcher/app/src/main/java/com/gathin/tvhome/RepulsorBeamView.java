package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * repulsor_beam: a full-screen layer over a wallpaper of a figure with glowing palms. A beam of light runs
 * from whichever palm is nearer the focused item to that item, slides when focus moves, and switches hands
 * with a quick flash. The palms pulse. Palm positions are fractions of the screen, matching a full-screen,
 * centre-cropped 16:9 wallpaper ({@link #setOrigins}). Drawn under the tiles, so the beam ends behind the one
 * it points at. The glow is stacked translucent strokes, not a blur, so it stays on the GPU; gradients are
 * built once and placed with a matrix, so a frame allocates nothing. {@link #start} and {@link #stop} follow
 * Home's onResume/onPause.
 */
final class RepulsorBeamView extends View {
    /** Glow layers from the outside in: width in dp and alpha; the last is the white-hot core. */
    private static final float[] WIDTHS = {30f, 18f, 10f, 5f};
    private static final int[] ALPHAS = {0x16, 0x2E, 0x60, 0xB0};
    private static final int[] COLORS = {0x45A8FF, 0x6CC4FF, 0x9EDCFF, 0xD8F3FF};
    private static final float FLOW_GAP_DP = 30f;

    private final float dp;
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flare = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint impact = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix place = new Matrix();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int[] loc = new int[2];

    /** Palm centres as fractions of the screen: {x0, y0, x1, y1, ...}. */
    private float[] origins = new float[0];
    private int hand = -1;
    private float endX, endY;
    private boolean aimed;
    private float beamAlpha = 1f;
    private float pulse;
    private float flowShift;
    private float charge;
    private ValueAnimator loop, moveAnim, swapAnim, chargeAnim;
    private View target;

    public RepulsorBeamView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        dp = ctx.getResources().getDisplayMetrics().density;
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        core.setStyle(Paint.Style.STROKE);
        core.setStrokeCap(Paint.Cap.ROUND);
        core.setColor(0xFFFFFFFF);
        spark.setColor(0xFFFFFFFF);
        // Unit-radius gradients, scaled and moved into place per draw.
        flare.setShader(new RadialGradient(0f, 0f, 1f,
                new int[]{0xFFFFFFFF, 0xAA9EE0FF, 0x0045A8FF}, new float[]{0f, 0.28f, 1f}, Shader.TileMode.CLAMP));
        impact.setShader(new RadialGradient(0f, 0f, 1f,
                new int[]{0x88BDEBFF, 0x3345A8FF, 0x0045A8FF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
    }

    void setOrigins(float[] fractions) {
        origins = fractions;
        invalidate();
    }

    void start() {
        if (loop != null) return;
        loop = ValueAnimator.ofFloat(0f, 1f);
        loop.setDuration(1400);
        loop.setRepeatCount(ValueAnimator.INFINITE);
        loop.setInterpolator(new LinearInterpolator());
        loop.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            pulse = (float) (0.5 + 0.5 * Math.sin(f * 2 * Math.PI));
            flowShift = (f * 2f) % 1f;
            invalidate();
        });
        loop.start();
        if (target != null) aim(target, false);
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
        for (ValueAnimator a : new ValueAnimator[]{loop, moveAnim, swapAnim, chargeAnim}) {
            if (a != null) a.cancel();
        }
        loop = null;
        moveAnim = null;
        swapAnim = null;
        chargeAnim = null;
        beamAlpha = 1f;
        charge = 0f;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }

    /** Points the beam at {@code v}, and again once a scroll it set off has settled. */
    void pointAt(View v) {
        target = v;
        aim(v, true);
        handler.postDelayed(() -> aim(v, true), 320);
    }

    private void aim(View v, boolean animate) {
        if (getWidth() == 0 || origins.length < 2 || !v.isAttachedToWindow()) return;
        v.getLocationOnScreen(loc);
        float tx = loc[0] + v.getWidth() / 2f, ty = loc[1] + v.getHeight() / 2f;
        getLocationOnScreen(loc);
        tx -= loc[0];
        ty -= loc[1];
        int nearest = 0;
        float best = Float.MAX_VALUE;
        for (int i = 0; i + 1 < origins.length; i += 2) {
            float d = Math.abs(origins[i] * getWidth() - tx);
            if (d < best) {
                best = d;
                nearest = i / 2;
            }
        }
        if (aimed && Math.abs(tx - endX) < 1f && Math.abs(ty - endY) < 1f && nearest == hand) return;
        if (!aimed || !animate) {
            hand = nearest;
            endX = tx;
            endY = ty;
            aimed = true;
            invalidate();
            return;
        }
        if (nearest != hand) swapHands(nearest, tx, ty);
        else slideTo(tx, ty);
        charge();
    }

    private void slideTo(float tx, float ty) {
        float fx = endX, fy = endY;
        if (moveAnim != null) moveAnim.cancel();
        moveAnim = ValueAnimator.ofFloat(0f, 1f);
        moveAnim.setDuration(260);
        moveAnim.setInterpolator(new DecelerateInterpolator());
        moveAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            endX = fx + (tx - fx) * f;
            endY = fy + (ty - fy) * f;
            invalidate();
        });
        moveAnim.start();
    }

    /** The other hand fires: the beam fades out, jumps to the new palm and target, and fades back in. */
    private void swapHands(int next, float tx, float ty) {
        if (moveAnim != null) moveAnim.cancel();
        if (swapAnim != null) swapAnim.cancel();
        swapAnim = ValueAnimator.ofFloat(1f, 0f, 1f);
        swapAnim.setDuration(240);
        final boolean[] switched = {false};
        swapAnim.addUpdateListener(a -> {
            beamAlpha = (float) a.getAnimatedValue();
            if (!switched[0] && a.getAnimatedFraction() >= 0.5f) {
                switched[0] = true;
                hand = next;
                endX = tx;
                endY = ty;
            }
            invalidate();
        });
        swapAnim.start();
    }

    /** A short brightening of the beam and the palm each time it moves, like a shot being fired. */
    private void charge() {
        if (chargeAnim != null) chargeAnim.cancel();
        chargeAnim = ValueAnimator.ofFloat(1f, 0f);
        chargeAnim.setDuration(420);
        chargeAnim.setInterpolator(new DecelerateInterpolator());
        chargeAnim.addUpdateListener(a -> {
            charge = (float) a.getAnimatedValue();
            invalidate();
        });
        chargeAnim.start();
    }

    private void drawRadial(Canvas canvas, Paint paint, float x, float y, float r, int alpha) {
        place.setScale(r, r);
        place.postTranslate(x, y);
        paint.getShader().setLocalMatrix(place);
        paint.setAlpha(alpha);
        canvas.drawCircle(x, y, r, paint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (origins.length < 2 || getWidth() == 0) return;
        float w = getWidth(), h = getHeight();

        // Every palm glows and pulses, the firing one brighter.
        for (int i = 0; i + 1 < origins.length; i += 2) {
            boolean firing = aimed && i / 2 == hand;
            float r = (firing ? 46f : 30f) * dp * (0.9f + 0.2f * pulse + (firing ? 0.35f * charge : 0f));
            drawRadial(canvas, flare, origins[i] * w, origins[i + 1] * h, r, firing ? 0xEE : 0x99);
        }
        if (!aimed || hand < 0 || hand * 2 + 1 >= origins.length) return;

        float sx = origins[hand * 2] * w, sy = origins[hand * 2 + 1] * h;
        float boost = 1f + 0.6f * charge;
        for (int i = 0; i < WIDTHS.length; i++) {
            glow.setColor(COLORS[i]);
            glow.setAlpha((int) (ALPHAS[i] * beamAlpha));
            glow.setStrokeWidth(WIDTHS[i] * dp * boost);
            canvas.drawLine(sx, sy, endX, endY, glow);
        }
        core.setAlpha((int) (255 * beamAlpha));
        core.setStrokeWidth((2f + 1.2f * pulse) * dp * boost);
        canvas.drawLine(sx, sy, endX, endY, core);

        // Bright sparks running down the beam toward the target.
        float dx = endX - sx, dy = endY - sy;
        float len = (float) Math.hypot(dx, dy);
        float gap = FLOW_GAP_DP * dp;
        if (len > gap) {
            spark.setAlpha((int) (220 * beamAlpha));
            for (float d = flowShift * gap; d < len; d += gap) {
                float t = d / len;
                canvas.drawCircle(sx + dx * t, sy + dy * t, 2.2f * dp, spark);
            }
        }

        // Where it lands, mostly hidden behind the tile, a halo spills around the tile's edges.
        drawRadial(canvas, impact, endX, endY, 80f * dp * (0.9f + 0.15f * pulse) * boost, (int) (255 * beamAlpha));
    }
}
