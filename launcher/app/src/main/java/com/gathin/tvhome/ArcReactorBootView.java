package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * The repulsor_beam boot splash: on black, an arc reactor's housing traces itself, its ten segments light one
 * by one, the core charges and flares, and a shockwave rolls out to the screen edges as the black fades away
 * and Home shows through. About 2.6 s, drawn on the GPU with shapes built once in onSizeChanged.
 */
final class ArcReactorBootView extends View {
    private static final int SEGMENTS = 10;
    private static final long DURATION_MS = 2600;

    private final float dp;
    private final Paint housing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint segment = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint segmentGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haze = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint housingGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path segmentPath = new Path();
    private final RectF oval = new RectF();
    private final Matrix place = new Matrix();

    private float cx, cy, r, far;
    private float t;
    private ValueAnimator anim;

    public ArcReactorBootView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        dp = ctx.getResources().getDisplayMetrics().density;
        housing.setStyle(Paint.Style.STROKE);
        housing.setStrokeCap(Paint.Cap.ROUND);
        housing.setStrokeWidth(2.5f * dp);
        housing.setColor(0xFF9EDCFF);
        housingGlow.setStyle(Paint.Style.STROKE);
        housingGlow.setColor(0xFF45A8FF);
        segment.setColor(0xFFE6F8FF);
        segmentGlow.setStyle(Paint.Style.STROKE);
        segmentGlow.setStrokeJoin(Paint.Join.ROUND);
        haze.setShader(new RadialGradient(0f, 0f, 1f,
                new int[]{0x6645A8FF, 0x2245A8FF, 0x0045A8FF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        innerRing.setStyle(Paint.Style.STROKE);
        innerRing.setColor(0xFF9EDCFF);
        wave.setStyle(Paint.Style.STROKE);
        wave.setColor(0xFFBDEBFF);
        core.setShader(new RadialGradient(0f, 0f, 1f,
                new int[]{0xFFFFFFFF, 0xEEBDEBFF, 0x8845A8FF, 0x0045A8FF},
                new float[]{0f, 0.25f, 0.6f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cx = w / 2f;
        cy = h / 2f;
        r = Math.min(w, h) * 0.16f;
        far = (float) Math.hypot(cx, cy);
        // One segment pointing up: a band between two radii, 26 degrees wide; the other nine are rotations of it.
        float in = r * 0.82f, out = r * 1.1f, half = 13f;
        segmentPath.reset();
        oval.set(-out, -out, out, out);
        segmentPath.arcTo(oval, -90f - half, 2 * half, true);
        oval.set(-in, -in, in, in);
        segmentPath.arcTo(oval, -90f + half, -2 * half, false);
        segmentPath.close();
        innerRing.setStrokeWidth(2f * dp);
        housingGlow.setStrokeWidth(9f * dp);
    }

    /** Shows the view, plays once, then hides it. A replay mid-run starts over without the old run hiding it. */
    void play() {
        if (anim != null) {
            anim.removeAllListeners();
            anim.cancel();
        }
        t = 0f;
        setVisibility(VISIBLE);
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(DURATION_MS);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                // Also runs on cancel (Home paused mid-intro), so black never stays over Home.
                setVisibility(GONE);
                android.util.Log.i("TvHome.Boot", "arc reactor intro " + (cancelled ? "cut short" : "finished")
                        + " at uptime " + android.os.SystemClock.uptimeMillis() + " ms");
            }
        });
        anim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (anim != null) anim.cancel();
    }

    private static float phase(float t, float from, float to) {
        return Math.max(0f, Math.min(1f, (t - from) / (to - from)));
    }

    private static float easeOut(float x) {
        return 1f - (1f - x) * (1f - x) * (1f - x);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() == 0) return;

        // Black until the shockwave, then fading out to reveal Home.
        int bg = (int) (255 * (1f - easeOut(phase(t, 0.7f, 1f))));
        canvas.drawARGB(bg, 0, 0, 0);
        float fade = 1f - phase(t, 0.78f, 1f);

        // A blue haze behind the reactor, building as the segments light.
        float lit = phase(t, 0.1f, 0.1f + SEGMENTS * 0.028f + 0.06f);
        float hazeR = r * 2.6f;
        place.setScale(hazeR, hazeR);
        place.postTranslate(cx, cy);
        haze.getShader().setLocalMatrix(place);
        haze.setAlpha((int) (255 * lit * fade));
        canvas.drawCircle(cx, cy, hazeR, haze);

        // The housing traces itself clockwise from the top.
        float trace = easeOut(phase(t, 0f, 0.3f));
        float hr = r * 1.24f;
        oval.set(cx - hr, cy - hr, cx + hr, cy + hr);
        housingGlow.setAlpha((int) (60 * fade));
        canvas.drawArc(oval, -90f, 360f * trace, false, housingGlow);
        housing.setAlpha((int) (230 * fade));
        canvas.drawArc(oval, -90f, 360f * trace, false, housing);

        // Segments light one by one, each with a two-layer blue glow under a white-hot fill.
        canvas.save();
        canvas.translate(cx, cy);
        for (int i = 0; i < SEGMENTS; i++) {
            float on = phase(t, 0.1f + i * 0.028f, 0.16f + i * 0.028f);
            if (on > 0f) {
                segmentGlow.setColor(0xFF45A8FF);
                segmentGlow.setStrokeWidth(14f * dp);
                segmentGlow.setAlpha((int) (70 * on * fade));
                canvas.drawPath(segmentPath, segmentGlow);
                segmentGlow.setColor(0xFF8FD8FF);
                segmentGlow.setStrokeWidth(6f * dp);
                segmentGlow.setAlpha((int) (150 * on * fade));
                canvas.drawPath(segmentPath, segmentGlow);
                segment.setAlpha((int) (245 * on * fade));
                canvas.drawPath(segmentPath, segment);
            }
            canvas.rotate(360f / SEGMENTS);
        }
        canvas.restore();

        // The inner ring, then the core charging and flaring.
        float ring = phase(t, 0.36f, 0.5f);
        innerRing.setAlpha((int) (210 * ring * fade));
        canvas.drawCircle(cx, cy, r * 0.62f, innerRing);
        float charge = easeOut(phase(t, 0.42f, 0.64f));
        float flare = phase(t, 0.62f, 0.72f) * (1f - phase(t, 0.72f, 0.9f));
        float cr = r * (0.55f * charge + 0.9f * flare);
        if (cr > 0f) {
            place.setScale(cr, cr);
            place.postTranslate(cx, cy);
            core.getShader().setLocalMatrix(place);
            core.setAlpha((int) (255 * Math.max(fade, flare)));
            canvas.drawCircle(cx, cy, cr, core);
        }

        // The shockwave rolls out to the corners as the reactor fires.
        float out = phase(t, 0.66f, 1f);
        if (out > 0f && out < 1f) {
            float e = easeOut(out);
            wave.setStrokeWidth((10f - 8f * e) * dp);
            wave.setAlpha((int) (230 * (1f - out)));
            canvas.drawCircle(cx, cy, r + (far - r) * e, wave);
        }
    }
}
