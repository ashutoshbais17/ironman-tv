package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import java.util.Random;

/**
 * repulsor_beam: a full-screen layer over a wallpaper of a figure with glowing palms. A cone of light runs from
 * whichever palm is nearer the focused item to that item, widening as it goes, with streaks of light flowing down
 * it, repulsor rings riding along it, and sparks inside. Each move fires a bright shot from the palm that ripples
 * out where it lands; switching hands fades the beam out and back in at the other palm. Palm positions are
 * fractions of the screen, matching a full-screen, centre-cropped 16:9 wallpaper ({@link #setOrigins}). Drawn
 * under the tiles, so the beam ends behind the one it points at. Everything is filled shapes and gradients on the
 * GPU, no blur; gradients and particle tracks are made once, so a frame allocates nothing. {@link #start} and
 * {@link #stop} follow Home's onResume/onPause.
 */
final class RepulsorBeamView extends View {
    /** The cone's layers, outside in: width at the palm and at the target (dp), colour, and alpha. */
    private static final float[] CONE_FROM = {44f, 30f, 19f, 11f, 6f};
    private static final float[] CONE_TO = {150f, 100f, 60f, 30f, 13f};
    private static final int[] CONE_RGB = {0x2882FF, 0x45A8FF, 0x6CC4FF, 0x9EDCFF, 0xD8F3FF};
    private static final float[] CONE_ALPHA = {0.09f, 0.14f, 0.22f, 0.36f, 0.6f};
    private static final int STREAKS = 26, SPARKS = 40, RINGS = 3;
    /** Radial gradients are made at this radius and scaled into place. */
    private static final float UNIT = 100f;
    private static final long SHOT_MS = 220, RIPPLE_MS = 500, CHARGE_MS = 420;
    private static final float TAU = (float) (2 * Math.PI);

    private final float dp;
    private final Paint cone = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flare = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint packet = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint impact = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shader[] coneShaders = new Shader[CONE_RGB.length];
    private final Shader coreShader;
    private final Matrix place = new Matrix();
    /** The cone's layers and the core (last), rebuilt only when the beam moves, so the renderer can reuse them. */
    private final Path[] shapes = new Path[CONE_RGB.length + 1];
    private float shapedX0 = Float.NaN, shapedY0, shapedX1, shapedY1, shapedBoost;
    private final RectF oval = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int[] loc = new int[2];
    private final long epoch = SystemClock.uptimeMillis();

    // Streaks and sparks: where along the beam each starts (0-1), how fast it travels (beam lengths a second),
    // and how far off the axis it runs (a fraction of the cone's half-width).
    private final float[] streakFrom = new float[STREAKS], streakSpeed = new float[STREAKS],
            streakSide = new float[STREAKS], streakLength = new float[STREAKS], streakAlpha = new float[STREAKS];
    private final float[] sparkFrom = new float[SPARKS], sparkSpeed = new float[SPARKS], sparkSide = new float[SPARKS],
            sparkWobble = new float[SPARKS];
    /** Streaks and sparks are drawn in LEVELS batches by brightness, a few draw calls instead of one each. */
    static final int LEVELS = 3;
    private final float[][] streakPts = new float[LEVELS][STREAKS * 4], sparkPts = new float[LEVELS][SPARKS * 2];
    private final int[] batch = new int[LEVELS];

    /** Palm centres as fractions of the screen: {x0, y0, x1, y1, ...}. */
    private float[] origins = new float[0];
    private int hand = -1;
    private float endX, endY;
    private boolean aimed;
    private float beamAlpha = 1f;
    private long shotAt, chargeAt;
    private ValueAnimator loop, moveAnim, swapAnim;
    private View target;

    public RepulsorBeamView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        dp = ctx.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        dot.setStrokeCap(Paint.Cap.ROUND);
        // Along the beam: brightest at the palm, dimmer mid-way, picking up again at the target. Unit length,
        // stretched and turned into place per draw.
        float[] stops = {0f, 0.45f, 1f};
        for (int i = 0; i < CONE_RGB.length; i++) {
            coneShaders[i] = new LinearGradient(0f, 0f, 1f, 0f,
                    new int[]{0xFF000000 | CONE_RGB[i], 0x8C000000 | CONE_RGB[i], 0xE6000000 | CONE_RGB[i]}, stops,
                    Shader.TileMode.CLAMP);
        }
        coreShader = new LinearGradient(0f, 0f, 1f, 0f, new int[]{0xFFFFFFFF, 0x8CFFFFFF, 0xE6FFFFFF}, stops,
                Shader.TileMode.CLAMP);
        flare.setShader(new RadialGradient(0f, 0f, UNIT,
                new int[]{0xFFFFFFFF, 0xAA9EE0FF, 0x0045A8FF}, new float[]{0f, 0.28f, 1f}, Shader.TileMode.CLAMP));
        packet.setShader(new RadialGradient(0f, 0f, UNIT,
                new int[]{0xF2FFFFFF, 0x8CAAE4FF, 0x0045A8FF}, new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        impact.setShader(new RadialGradient(0f, 0f, UNIT,
                new int[]{0x88BDEBFF, 0x3345A8FF, 0x0045A8FF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        for (int i = 0; i < shapes.length; i++) shapes[i] = new Path();
        Random rnd = new Random(7);
        for (int i = 0; i < STREAKS; i++) {
            streakFrom[i] = rnd.nextFloat();
            streakSpeed[i] = 1.1f + rnd.nextFloat() * 1.1f;
            streakSide[i] = (rnd.nextFloat() * 2f - 1f) * 0.55f;
            streakLength[i] = 0.05f + rnd.nextFloat() * 0.09f;
            streakAlpha[i] = 0.35f + rnd.nextFloat() * 0.45f;
        }
        for (int i = 0; i < SPARKS; i++) {
            sparkFrom[i] = rnd.nextFloat();
            sparkSpeed[i] = 0.55f + rnd.nextFloat() * 0.7f;
            sparkSide[i] = rnd.nextFloat() * 2f - 1f;
            sparkWobble[i] = rnd.nextFloat();
        }
    }

    void setOrigins(float[] fractions) {
        origins = fractions;
        invalidate();
    }

    void start() {
        if (loop != null) return;
        loop = ValueAnimator.ofFloat(0f, 1f);
        loop.setDuration(1000);
        loop.setRepeatCount(ValueAnimator.INFINITE);
        loop.setInterpolator(new LinearInterpolator());
        loop.addUpdateListener(a -> {
            if (AmbientFxView.ambientFrame()) invalidate();
        });
        loop.start();
        if (target != null) aim(target, false);
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
        for (ValueAnimator a : new ValueAnimator[]{loop, moveAnim, swapAnim}) {
            if (a != null) a.cancel();
        }
        loop = null;
        moveAnim = null;
        swapAnim = null;
        beamAlpha = 1f;
        shotAt = 0;
        chargeAt = 0;
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
        chargeAt = SystemClock.uptimeMillis();
        if (nearest != hand) {
            swapHands(nearest, tx, ty);
        } else {
            shotAt = chargeAt;
            slideTo(tx, ty);
        }
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

    /** The other hand fires: the beam fades out, jumps to the new palm and target, and fades back in with a shot. */
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
                shotAt = SystemClock.uptimeMillis();
            }
            invalidate();
        });
        swapAnim.start();
    }

    private static float easeOut(float f) {
        float r = 1f - f;
        return 1f - r * r * r;
    }

    private static float frac(float x) {
        return x - (float) Math.floor(x);
    }

    /** Which of the LEVELS batches a brightness from 0 to 1 falls in. */
    static int level(float v) {
        return Math.max(0, Math.min(LEVELS - 1, (int) (v * LEVELS)));
    }

    /** Half the cone's outer width, a fraction {@code s} of the way from the palm. */
    private float halfAt(float s) {
        return (22f + 53f * s) * dp;
    }

    /** A radial gradient {@code paint} stretched to rx by ry, turned {@code deg}, centred on x, y. */
    private void blob(Canvas canvas, Paint paint, float x, float y, float rx, float ry, float deg, float alpha) {
        if (alpha <= 0f) return;
        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(deg);
        canvas.scale(rx / UNIT, ry / UNIT);
        paint.setAlpha((int) (255 * Math.min(1f, alpha)));
        canvas.drawCircle(0f, 0f, UNIT, paint);
        canvas.restore();
    }

    /** Makes {@code shape} a rod from (x0, y0) to (x1, y1), w0 wide at the start and w1 at the end, with round ends. */
    private void taper(Path shape, float x0, float y0, float x1, float y1, float w0, float w1, float deg,
                       float nx, float ny) {
        shape.reset();
        shape.moveTo(x0 + nx * w0 / 2f, y0 + ny * w0 / 2f);
        shape.lineTo(x1 + nx * w1 / 2f, y1 + ny * w1 / 2f);
        oval.set(x1 - w1 / 2f, y1 - w1 / 2f, x1 + w1 / 2f, y1 + w1 / 2f);
        shape.arcTo(oval, deg + 90f, -180f);
        shape.lineTo(x0 - nx * w0 / 2f, y0 - ny * w0 / 2f);
        oval.set(x0 - w0 / 2f, y0 - w0 / 2f, x0 + w0 / 2f, y0 + w0 / 2f);
        shape.arcTo(oval, deg - 90f, -180f);
        shape.close();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (origins.length < 2 || getWidth() == 0) return;
        float w = getWidth(), h = getHeight();
        long now = SystemClock.uptimeMillis();
        float t = (now - epoch) / 1000f;
        float pulse = 0.5f + 0.5f * (float) Math.sin(t * TAU / 1.4f);
        float charge = chargeAt == 0 ? 0f : 1f - easeOut(Math.min(1f, (now - chargeAt) / (float) CHARGE_MS));

        // Every palm glows and pulses, with a lens streak; the firing one brighter.
        for (int i = 0; i + 1 < origins.length; i += 2) {
            boolean firing = aimed && i / 2 == hand;
            float px = origins[i] * w, py = origins[i + 1] * h;
            float r = (firing ? 46f : 30f) * dp * (0.9f + 0.2f * pulse + (firing ? 0.35f * charge : 0f));
            blob(canvas, flare, px, py, r, r, 0f, firing ? 0.93f : 0.6f);
            blob(canvas, flare, px, py, (firing ? 120f : 70f) * dp * (1f + 0.3f * charge), 3.5f * dp, 0f,
                    (firing ? 0.55f : 0.3f) * (0.8f + 0.2f * pulse));
        }
        if (!aimed || hand < 0 || hand * 2 + 1 >= origins.length) return;

        float sx = origins[hand * 2] * w, sy = origins[hand * 2 + 1] * h;
        float dx = endX - sx, dy = endY - sy;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1f) return;
        float nx = -dy / len, ny = dx / len;
        float deg = (float) Math.toDegrees(Math.atan2(dy, dx));
        float boost = 1f + 0.5f * charge;

        // The cone, layer by layer. The shapes stay the same while the beam rests, so they are kept.
        if (sx != shapedX0 || sy != shapedY0 || endX != shapedX1 || endY != shapedY1 || boost != shapedBoost) {
            for (int i = 0; i < CONE_RGB.length; i++) {
                taper(shapes[i], sx, sy, endX, endY, CONE_FROM[i] * dp * boost, CONE_TO[i] * dp * boost, deg, nx, ny);
            }
            taper(shapes[CONE_RGB.length], sx, sy, endX, endY, 6f * dp * boost, 2f * dp * boost, deg, nx, ny);
            shapedX0 = sx;
            shapedY0 = sy;
            shapedX1 = endX;
            shapedY1 = endY;
            shapedBoost = boost;
        }
        place.setScale(len, len);
        place.postRotate(deg);
        place.postTranslate(sx, sy);
        for (int i = 0; i < coneShaders.length; i++) {
            coneShaders[i].setLocalMatrix(place);
            cone.setShader(coneShaders[i]);
            cone.setAlpha((int) (255 * Math.min(1f, CONE_ALPHA[i] * boost) * beamAlpha));
            canvas.drawPath(shapes[i], cone);
        }

        // Streaks of light racing down the beam at different depths, drawn in a few batches by brightness.
        for (int b = 0; b < LEVELS; b++) batch[b] = 0;
        for (int i = 0; i < STREAKS; i++) {
            float s0 = frac(streakFrom[i] + t * streakSpeed[i]), s1 = Math.min(1f, s0 + streakLength[i]);
            float sm = (s0 + s1) / 2f;
            float o0 = streakSide[i] * halfAt(s0) * 0.6f, o1 = streakSide[i] * halfAt(s1) * 0.6f;
            int b = level(streakAlpha[i] * (float) Math.sin(Math.PI * sm) / 0.8f);
            float[] pts = streakPts[b];
            int n = batch[b];
            pts[n] = sx + dx * s0 + nx * o0;
            pts[n + 1] = sy + dy * s0 + ny * o0;
            pts[n + 2] = sx + dx * s1 + nx * o1;
            pts[n + 3] = sy + dy * s1 + ny * o1;
            batch[b] = n + 4;
        }
        line.setColor(0xFFE1F6FF);
        for (int b = 0; b < LEVELS; b++) {
            if (batch[b] == 0) continue;
            line.setAlpha((int) (255 * (0.2f + 0.25f * b) * beamAlpha));
            line.setStrokeWidth((1.4f + 0.6f * b) * dp);
            canvas.drawLines(streakPts[b], 0, batch[b], line);
        }

        // The white-hot core, thinning away from the hand, pulsing in brightness.
        coreShader.setLocalMatrix(place);
        cone.setShader(coreShader);
        cone.setAlpha((int) ((200 + 55 * pulse) * beamAlpha));
        canvas.drawPath(shapes[CONE_RGB.length], cone);

        // Repulsor rings, each with a glow, riding down the beam.
        line.setColor(0xFFC8EEFF);
        line.setStrokeWidth(2f * dp);
        for (int k = 0; k < RINGS; k++) {
            float s = frac(t * 0.75f + k / (float) RINGS), half = halfAt(s) * 0.62f;
            float fade = (float) Math.sin(Math.PI * s);
            float cx = sx + dx * s, cy = sy + dy * s;
            blob(canvas, packet, cx, cy, 40f * dp, half, deg, 0.6f * fade * beamAlpha);
            canvas.save();
            canvas.translate(cx, cy);
            canvas.rotate(deg);
            float rx = (5f + 6f * s) * dp;
            oval.set(-rx, -half, rx, half);
            line.setAlpha((int) (140 * fade * beamAlpha));
            canvas.drawOval(oval, line);
            canvas.restore();
        }

        // Sparks streaming inside the cone, batched by brightness: bright mid-way, dim at the ends.
        for (int b = 0; b < LEVELS; b++) batch[b] = 0;
        for (int i = 0; i < SPARKS; i++) {
            float s = frac(sparkFrom[i] + t * sparkSpeed[i]);
            float off = sparkSide[i] * halfAt(s) * 0.7f + (float) Math.sin(t * 6f + sparkWobble[i] * 20f) * 2f * dp;
            int b = level((float) Math.sin(Math.PI * s));
            float[] pts = sparkPts[b];
            int n = batch[b];
            pts[n] = sx + dx * s + nx * off;
            pts[n + 1] = sy + dy * s + ny * off;
            batch[b] = n + 2;
        }
        dot.setColor(0xFFEAF8FF);
        for (int b = 0; b < LEVELS; b++) {
            if (batch[b] == 0) continue;
            dot.setAlpha((int) (217 * (0.3f + 0.35f * b) * beamAlpha));
            dot.setStrokeWidth((2.4f + 0.8f * b) * dp);
            canvas.drawPoints(sparkPts[b], 0, batch[b], dot);
        }

        // The shot: a bright bolt racing from the palm, with a ring bursting off the hand.
        float shot = shotAt == 0 ? 1f : (now - shotAt) / (float) SHOT_MS;
        if (shot < 1f) {
            float s = easeOut(shot);
            blob(canvas, packet, sx + dx * s, sy + dy * s, 60f * dp, 22f * dp, deg, 1f - shot * 0.3f);
            line.setColor(0xFFBEE8FF);
            line.setStrokeWidth(3f * dp);
            line.setAlpha((int) (204 * (1f - shot)));
            canvas.drawCircle(sx, sy, (12f + 70f * shot) * dp, line);
        }

        // Where it lands, mostly hidden behind the tile, a halo spills round the tile's edges, and the shot ripples.
        float halo = 90f * dp * (0.9f + 0.15f * pulse) * boost;
        blob(canvas, impact, endX, endY, halo, halo, 0f, beamAlpha);
        float ripple = shotAt == 0 ? -1f : (now - shotAt - SHOT_MS) / (float) RIPPLE_MS;
        if (ripple >= 0f && ripple < 1f) {
            float e = easeOut(ripple);
            line.setColor(0xFFC8EEFF);
            line.setStrokeWidth((4f - 3f * ripple) * dp);
            line.setAlpha((int) (178 * (1f - ripple)));
            oval.set(endX - (60f + 140f * e) * dp, endY - (40f + 90f * e) * dp,
                    endX + (60f + 140f * e) * dp, endY + (40f + 90f * e) * dp);
            canvas.drawOval(oval, line);
        }
    }
}
