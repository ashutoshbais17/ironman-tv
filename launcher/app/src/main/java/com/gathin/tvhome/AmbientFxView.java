package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.LinearInterpolator;

import java.io.File;
import java.util.Random;

/**
 * ambient_fx: particles over the wallpaper. "fire" sends embers up past the figure on a flickering glow from
 * below, "snow" drifts flakes down in three depths, "fireice" does both, and "storm" brings slanting rain with
 * lightning that lights the sky behind the figure. "cycle" plays each in turn, cross-fading to the next every
 * few minutes and on each return to Home. When the theme has a cut-out of the wallpaper's figure
 * ({@link #setForeground}), it is drawn over the particles, so they pass behind the figure; a few near ones
 * still cross in front. Glows are small bitmaps made once, tracks are fixed at start, and positions are worked
 * out from the clock, so a frame allocates nothing. {@link #start} and {@link #stop} follow Home's
 * onResume/onPause.
 */
final class AmbientFxView extends View {
    private static final String TAG = "TvHome.Fx";
    private static final String[] CYCLE = {"fire", "snow", "fireice", "storm"};
    private static final long FADE_MS = 1500;
    /** The wallpaper size that foreground_origin is measured in. */
    private static final float FRAME_W = 1920f, FRAME_H = 1080f;
    private static final float TAU = (float) (2 * Math.PI);
    private static final int[] EMBER_RGB = {0xFFB347, 0xFF7A1A, 0xFFD27A, 0xFF5A14};
    private static final int EMBERS = 100, TONGUES = 7, FLAKES = 130, DROPS = 110;

    private final float dp;
    private final long epoch = SystemClock.uptimeMillis();
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint wash = new Paint();
    private final RectF box = new RectF();
    private final Path bolt = new Path();
    private final Random rnd = new Random(7);
    private final Bitmap tongueGlow;
    /** Particles are drawn in batches (one draw call each) by colour and brightness, not one call apiece. */
    private static final int BATCHES = 12;
    private final float[][] batchPts = new float[BATCHES][Math.max(Math.max(EMBERS, FLAKES) * 2, DROPS * 4)];
    private final int[] batch = new int[BATCHES];
    private Shader heat, frost, sky;

    private final float[] eX = new float[EMBERS], ePhase = new float[EMBERS], eSpeed = new float[EMBERS],
            eSway = new float[EMBERS], eSwayRate = new float[EMBERS],
            eFlicker = new float[EMBERS];
    private final int[] eColor = new int[EMBERS];
    private final boolean[] eFront = new boolean[EMBERS];
    private final float[] tX = new float[TONGUES], tPhase = new float[TONGUES], tPeriod = new float[TONGUES],
            tScale = new float[TONGUES];
    private final float[] fX = new float[FLAKES], fPhase = new float[FLAKES], fDepth = new float[FLAKES],
            fSway = new float[FLAKES], fSwayRate = new float[FLAKES];
    private final float[] dX = new float[DROPS], dPhase = new float[DROPS], dDepth = new float[DROPS];

    private boolean cycle;
    private int cycleIndex;
    private long cycleMs = 3 * 60_000L, shownAt;
    private String mode = "", previous = "";
    private long fadeAt;
    private float strikeAt = -99f, strikeGap;
    /** The theme's cut-out of the wallpaper's figure, beside its config.json. */
    static final String FOREGROUND = "foreground.png";

    private Bitmap foreground;
    private boolean loading;
    private float fgLeft, fgTop;
    private ValueAnimator loop;
    private boolean started;

    public AmbientFxView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        dp = ctx.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        dot.setStrokeCap(Paint.Cap.ROUND);
        tongueGlow = sprite(64, new int[]{0x8CFF781E, 0x40E63C0A, 0x00A01400}, new float[]{0f, 0.45f, 1f});
        for (int i = 0; i < EMBERS; i++) {
            eX[i] = rnd.nextFloat();
            ePhase[i] = rnd.nextFloat();
            eSpeed[i] = 40f + rnd.nextFloat() * 90f;
            eSway[i] = 8f + rnd.nextFloat() * 26f;
            eSwayRate[i] = 0.3f + rnd.nextFloat() * 0.7f;
            eFlicker[i] = 4f + rnd.nextFloat() * 8f;
            eColor[i] = rnd.nextInt(EMBER_RGB.length);
            eFront[i] = rnd.nextFloat() < 0.12f;
        }
        for (int i = 0; i < TONGUES; i++) {
            tX[i] = (i + 0.5f) / TONGUES + (rnd.nextFloat() - 0.5f) * 0.08f;
            tPhase[i] = rnd.nextFloat();
            tPeriod[i] = 2.6f + rnd.nextFloat() * 1.8f;
            tScale[i] = 0.7f + rnd.nextFloat() * 0.6f;
        }
        for (int i = 0; i < FLAKES; i++) {
            fX[i] = rnd.nextFloat();
            fPhase[i] = rnd.nextFloat();
            fDepth[i] = rnd.nextFloat();
            fSway[i] = 6f + fDepth[i] * 20f;
            fSwayRate[i] = 0.2f + rnd.nextFloat() * 0.5f;
        }
        for (int i = 0; i < DROPS; i++) {
            dX[i] = rnd.nextFloat();
            dPhase[i] = rnd.nextFloat();
            dDepth[i] = rnd.nextFloat();
        }
    }

    /**
     * True on every other display frame, the same frames for every view that asks. Each frame the renderer redraws
     * all of Home, so the steady motion (these particles, the beam's flow) redraws at 30 frames a second, together;
     * the beam's moves still run at the full rate.
     */
    static boolean ambientFrame() {
        long now = AnimationUtils.currentAnimationTimeMillis();
        // Every view asking within one frame sees the same frame time, so they all redraw on the same frames.
        if (now == lastAmbientFrame) return true;
        if (now - lastAmbientFrame < AMBIENT_FRAME_MS) return false;
        lastAmbientFrame = now;
        return true;
    }

    /** Just under two frames at 60 Hz, so a slightly early frame still counts. */
    private static final long AMBIENT_FRAME_MS = 30;
    private static long lastAmbientFrame;

    /** A soft round glow, size x size, in the given colours from the centre out. */
    private static Bitmap sprite(int size, int[] colors, float[] stops) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(size / 2f, size / 2f, size / 2f, colors, stops, Shader.TileMode.CLAMP));
        new Canvas(b).drawCircle(size / 2f, size / 2f, size / 2f, p);
        return b;
    }

    /** One of "fire", "snow", "fireice", "storm" or "cycle"; minutes is how long each lasts with "cycle". */
    void setMode(String mode, int minutes) {
        cycle = "cycle".equals(mode);
        cycleMs = Math.max(1, minutes) * 60_000L;
        cycleIndex = 0;
        this.mode = cycle ? CYCLE[0] : mode;
        previous = "";
    }

    /** Loads the theme's cut-out of the figure off the UI thread; origin is its top-left in the 1920x1080 picture. */
    void setForeground(File png, String origin) {
        if (foreground != null || loading || png == null || !png.isFile()) return;
        loading = true;
        String[] xy = origin == null ? new String[0] : origin.split(",");
        try {
            fgLeft = xy.length == 2 ? Float.parseFloat(xy[0].trim()) : 0f;
            fgTop = xy.length == 2 ? Float.parseFloat(xy[1].trim()) : 0f;
        } catch (NumberFormatException e) {
            fgLeft = 0f;
            fgTop = 0f;
        }
        new Thread(() -> {
            Bitmap decoded;
            try {
                decoded = BitmapFactory.decodeFile(png.getPath());
            } catch (OutOfMemoryError e) {
                decoded = null;
            }
            if (decoded == null) {
                Log.w(TAG, "foreground not readable: " + png);
                post(() -> loading = false);
                return;
            }
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu != null) decoded.recycle();
            Bitmap ready = gpu != null ? gpu : decoded;
            post(() -> {
                foreground = ready;
                loading = false;
                invalidate();
            });
        }, "fx-foreground").start();
    }

    void start() {
        if (loop != null) return;
        long now = SystemClock.uptimeMillis();
        // Each return to Home moves the cycle on.
        if (cycle && started) next(now);
        started = true;
        shownAt = now;
        loop = ValueAnimator.ofFloat(0f, 1f);
        loop.setDuration(1000);
        loop.setRepeatCount(ValueAnimator.INFINITE);
        loop.setInterpolator(new LinearInterpolator());
        loop.addUpdateListener(a -> {
            if (ambientFrame()) invalidate();
        });
        loop.start();
    }

    void stop() {
        if (loop != null) loop.cancel();
        loop = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }

    private void next(long now) {
        cycleIndex = (cycleIndex + 1) % CYCLE.length;
        previous = mode;
        mode = CYCLE[cycleIndex];
        fadeAt = now;
        shownAt = now;
        Log.i(TAG, "ambient fx now " + mode);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        heat = new LinearGradient(0f, h, 0f, h * 0.25f, new int[]{0x80FF5A14, 0x33D2370A, 0x00780000},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        frost = new LinearGradient(0f, 0f, 0f, h * 0.5f, 0x1A96C8FF, 0x0096C8FF, Shader.TileMode.CLAMP);
        sky = new LinearGradient(0f, 0f, 0f, h, 0x471E325A, 0x1A0A1428, Shader.TileMode.CLAMP);
    }

    private static float frac(float x) {
        return x - (float) Math.floor(x);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() == 0 || heat == null) return;
        long now = SystemClock.uptimeMillis();
        if (cycle && loop != null && now - shownAt > cycleMs) next(now);
        float t = (now - epoch) / 1000f;
        float in = previous.isEmpty() ? 1f : Math.min(1f, (now - fadeAt) / (float) FADE_MS);
        if (in >= 1f) previous = "";

        if (!previous.isEmpty()) draw(canvas, previous, t, false, 1f - in);
        draw(canvas, mode, t, false, in);
        if (foreground != null) {
            float sx = getWidth() / FRAME_W, sy = getHeight() / FRAME_H;
            box.set(fgLeft * sx, fgTop * sy, (fgLeft + foreground.getWidth()) * sx, (fgTop + foreground.getHeight()) * sy);
            glow.setAlpha(255);
            canvas.drawBitmap(foreground, null, box, glow);
        }
        if (!previous.isEmpty()) draw(canvas, previous, t, true, 1f - in);
        draw(canvas, mode, t, true, in);
    }

    private void draw(Canvas canvas, String which, float t, boolean front, float scale) {
        if (scale <= 0f) return;
        switch (which) {
            case "fire":
                fire(canvas, t, front, scale);
                break;
            case "snow":
                snow(canvas, t, front, scale);
                break;
            case "fireice":
                fire(canvas, t, front, scale * 0.75f);
                snow(canvas, t, front, scale * 0.7f);
                break;
            case "storm":
                storm(canvas, t, front, scale);
                break;
            default:
                break;
        }
    }

    private void sprite(Canvas canvas, Bitmap b, float x, float y, float rx, float ry, float alpha) {
        if (alpha <= 0f) return;
        box.set(x - rx, y - ry, x + rx, y + ry);
        glow.setAlpha((int) (255 * Math.min(1f, alpha)));
        canvas.drawBitmap(b, null, box, glow);
    }

    /** Empties the batches; particles are then added with {@link #add} and drawn a batch at a time. */
    private void clearBatches() {
        for (int b = 0; b < BATCHES; b++) batch[b] = 0;
    }

    private void add(int b, float x, float y) {
        float[] pts = batchPts[b];
        int n = batch[b];
        pts[n] = x;
        pts[n + 1] = y;
        batch[b] = n + 2;
    }

    private void points(Canvas canvas, int b, float diameterDp, int argb, float alpha) {
        if (batch[b] == 0 || alpha <= 0f) return;
        dot.setColor(argb);
        dot.setAlpha((int) (255 * Math.min(1f, alpha)));
        dot.setStrokeWidth(diameterDp * dp);
        canvas.drawPoints(batchPts[b], 0, batch[b], dot);
    }

    private void fire(Canvas canvas, float t, boolean front, float scale) {
        float w = getWidth(), h = getHeight();
        if (!front) {
            // A glow from below that breathes, and soft tongues of flame rising into it.
            float breathe = 0.75f + 0.25f * (float) (Math.sin(t * 1.7f) * Math.sin(t * 0.63f + 1f));
            wash.setShader(heat);
            wash.setAlpha((int) (255 * breathe * scale));
            canvas.drawRect(0f, h * 0.25f, w, h, wash);
            wash.setShader(null);
            for (int i = 0; i < TONGUES; i++) {
                float k = frac(t / tPeriod[i] + tPhase[i]);
                float y = h + 20f * dp - k * 350f * dp * tScale[i], r = (70f + 100f * k) * dp * tScale[i];
                float x = tX[i] * w + (float) Math.sin(t * 1.3f + tPhase[i] * 9f) * 15f * dp;
                sprite(canvas, tongueGlow, x, y, r * 0.7f, r, (float) Math.sin(Math.PI * k) * 0.9f * scale);
            }
        }
        // Embers, batched by colour and by how bright each is right now (it flickers, and dims as it rises).
        clearBatches();
        float span = h + 40f * dp;
        for (int i = 0; i < EMBERS; i++) {
            if (eFront[i] != front) continue;
            float k = frac(ePhase[i] + t * eSpeed[i] * dp / span);
            float y = h + 20f * dp - k * span;
            float x = eX[i] * w + (float) Math.sin(t * eSwayRate[i] * TAU + ePhase[i] * 30f) * eSway[i] * dp + k * 60f * dp;
            float a = (0.55f + 0.45f * (float) Math.sin(t * eFlicker[i] + ePhase[i] * 50f)) * Math.min(1f, (1f - k) * 1.8f);
            add(eColor[i] * RepulsorBeamView.LEVELS + RepulsorBeamView.level(a), x, y);
        }
        for (int c = 0; c < EMBER_RGB.length; c++) {
            for (int l = 0; l < RepulsorBeamView.LEVELS; l++) {
                int b = c * RepulsorBeamView.LEVELS + l;
                float a = (0.35f + 0.3f * l) * scale, size = 0.8f + 0.25f * l;
                points(canvas, b, 16f * size, 0xFF000000 | EMBER_RGB[c], a * 0.07f);
                points(canvas, b, 11f * size, 0xFF000000 | EMBER_RGB[c], a * 0.12f);
                points(canvas, b, 7f * size, 0xFF000000 | EMBER_RGB[c], a * 0.24f);
                points(canvas, b, 2.6f * size, 0xFFFFECBE, a);
            }
        }
    }

    private void snow(Canvas canvas, float t, boolean front, float scale) {
        float w = getWidth(), h = getHeight();
        if (!front) {
            wash.setShader(frost);
            wash.setAlpha((int) (255 * scale));
            canvas.drawRect(0f, 0f, w, h * 0.5f, wash);
            wash.setShader(null);
        }
        // Three depths: far flakes small, faint and slow; near ones bigger, brighter and faster.
        clearBatches();
        float span = h + 20f * dp;
        for (int i = 0; i < FLAKES; i++) {
            float z = fDepth[i];
            if ((z > 0.86f) != front) continue;
            float k = frac(fPhase[i] + t * (22f + z * 90f) * dp / span);
            float y = k * span - 10f * dp;
            float x = fX[i] * w + (float) Math.sin(t * fSwayRate[i] * TAU + fPhase[i] * 40f) * fSway[i] * dp - k * 80f * dp;
            add(z < 0.4f ? 0 : z < 0.7f ? 1 : 2, ((x % w) + w) % w, y);
        }
        points(canvas, 0, 2.6f, 0xFFEBF5FF, 0.42f * scale);
        points(canvas, 1, 4.2f, 0xFFEBF5FF, 0.63f * scale);
        points(canvas, 2, 6.2f, 0xFFEBF5FF, 0.85f * scale);
    }

    private void storm(Canvas canvas, float t, boolean front, float scale) {
        float w = getWidth(), h = getHeight();
        if (!front) {
            wash.setShader(sky);
            wash.setAlpha((int) (255 * scale));
            canvas.drawRect(0f, 0f, w, h, wash);
            wash.setShader(null);
            if (t - strikeAt > strikeGap) strike(t, w, h);
            float since = t - strikeAt;
            float flash = since < 0.08f ? 1f : since < 0.16f ? 0.25f : since < 0.24f ? 0.8f
                    : Math.max(0f, 0.8f - (since - 0.24f) * 2.2f);
            flash *= scale;
            if (flash > 0f) {
                wash.setColor(0xAACDFF);
                wash.setAlpha((int) (84 * flash));
                canvas.drawRect(0f, 0f, w, h, wash);
                line.setColor(0xFFD7E8FF);
                for (float[] s : BOLT_STROKES) {
                    line.setStrokeWidth(s[0] * dp);
                    line.setAlpha((int) (255 * s[1] * flash));
                    canvas.drawPath(bolt, line);
                }
            }
        }
        // Rain in three depths, each drawn as one batch of lines.
        clearBatches();
        float span = h + 50f * dp;
        for (int i = 0; i < DROPS; i++) {
            float z = dDepth[i];
            if ((z > 0.85f) != front) continue;
            float k = frac(dPhase[i] + t * (700f + z * 600f) * dp / span);
            float y = k * span - 30f * dp, x = dX[i] * (w + 150f * dp) - k * 130f * dp;
            float l = (14f + z * 26f) * dp;
            int b = z < 0.4f ? 0 : z < 0.7f ? 1 : 2;
            add(b, x, y);
            add(b, x - l * 0.25f, y + l);
        }
        line.setColor(0xFFBED7FF);
        for (int b = 0; b < 3; b++) {
            if (batch[b] == 0) continue;
            line.setStrokeWidth((0.8f + 0.4f * b) * dp);
            line.setAlpha((int) (255 * (0.18f + 0.1f * b) * scale));
            canvas.drawLines(batchPts[b], 0, batch[b], line);
        }
    }

    /** Lightning: width (dp) and alpha of each pass, outer glow first. */
    private static final float[][] BOLT_STROKES = {{14f, 0.12f}, {6f, 0.3f}, {2.2f, 0.95f}};

    /** A new jagged bolt from the top of the sky, and how long until the next. */
    private void strike(float t, float w, float h) {
        strikeAt = t;
        strikeGap = 4.5f + rnd.nextFloat() * 4f;
        float x = w * (0.1f + rnd.nextFloat() * 0.8f), y = -5f * dp;
        bolt.reset();
        bolt.moveTo(x, y);
        while (y < h * 0.62f) {
            y += (15f + rnd.nextFloat() * 25f) * dp;
            x += (rnd.nextFloat() - 0.5f) * 60f * dp;
            bolt.lineTo(x, y);
        }
    }
}
