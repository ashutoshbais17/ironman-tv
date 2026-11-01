package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
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
import android.util.DisplayMetrics;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * sharingan: a decorative eye, off by default. Its iris turns toward whatever has focus ({@link #lookAt}),
 * it blinks on a slow timer and when it looks somewhere new, and it sits inside a thin HUD ring with scan
 * lines. {@link #playBootIntro} spins it once for the boot splash. Pure Canvas: shaders and paths are built
 * in onSizeChanged, so a frame allocates nothing. {@link #start} and {@link #stop} follow Home's
 * onResume/onPause so nothing animates while another app is in front.
 */
final class SharinganView extends View {
    private static final long BLINK_GAP_MS = 1500;

    private final float dp;
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iris = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint irisLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tomoe = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudArc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudTick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scanLeft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scanRight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path eyePath = new Path();
    private final Path tomoePath = new Path();
    private final RectF arcOval = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int[] loc = new int[2];

    private float cx, cy, rx, ry, ir, ring, glowR, scanFrom, scanTo;
    private float spin;
    private float offX, offY;
    private float blink;
    private long lastBlink;
    private ValueAnimator spinAnim, lookAnim, blinkAnim;

    private final Runnable idleBlink = new Runnable() {
        @Override
        public void run() {
            blink();
            handler.postDelayed(this, 5200);
        }
    };

    public SharinganView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        dp = ctx.getResources().getDisplayMetrics().density;
        lid.setColor(0xFF0C0C0F);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(2f * dp);
        outline.setColor(0xCCFF4848);
        irisLine.setStyle(Paint.Style.STROKE);
        irisLine.setColor(0xCC1A0002);
        tomoe.setColor(0xFF080808);
        shine.setColor(0x40FFFFFF);
        hudRing.setStyle(Paint.Style.STROKE);
        hudRing.setStrokeWidth(1.2f * dp);
        hudRing.setColor(0x66FF5A5A);
        hudRing.setPathEffect(new DashPathEffect(new float[]{3f * dp, 5f * dp}, 0f));
        hudArc.setStyle(Paint.Style.STROKE);
        hudArc.setStrokeWidth(2f * dp);
        hudArc.setStrokeCap(Paint.Cap.ROUND);
        hudArc.setColor(0xAAFF6060);
        hudTick.setStrokeWidth(1.5f * dp);
        hudTick.setColor(0x88FF6A6A);
        scanLeft.setStrokeWidth(1.2f * dp);
        scanRight.setStrokeWidth(1.2f * dp);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cx = w / 2f;
        cy = h / 2f;
        rx = w * 0.30f;
        ry = h * 0.28f;
        ir = ry * 1.18f;
        ring = ry * 1.62f;
        glowR = h * 0.5f;
        scanFrom = ring + 8f * dp;
        scanTo = w / 2f - 4f * dp;

        eyePath.reset();
        eyePath.moveTo(cx - rx, cy);
        eyePath.quadTo(cx, cy - 2f * ry, cx + rx, cy);
        eyePath.quadTo(cx, cy + 2f * ry, cx - rx, cy);
        eyePath.close();

        glow.setShader(new RadialGradient(cx, cy, glowR,
                new int[]{0x66C8101A, 0x22C8101A, 0x00C8101A}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        iris.setShader(new RadialGradient(0f, 0f, ir,
                new int[]{0xFFF0303A, 0xFFB80E18, 0xFF4A0306}, new float[]{0f, 0.65f, 1f}, Shader.TileMode.CLAMP));
        irisLine.setStrokeWidth(Math.max(1f, ir * 0.04f));
        scanLeft.setShader(new LinearGradient(cx - scanFrom, 0f, cx - scanTo, 0f,
                0x99FF4A4A, 0x00FF4A4A, Shader.TileMode.CLAMP));
        scanRight.setShader(new LinearGradient(cx + scanFrom, 0f, cx + scanTo, 0f,
                0x99FF4A4A, 0x00FF4A4A, Shader.TileMode.CLAMP));

        // One tomoe, built like half a yin-yang: the left half of a disc, plus a small disc on top (the head),
        // minus one below (carving the tail). Turned so the head leads the spin, and set on the iris ring at
        // the top; drawn three times, rotated, around the iris centre (0, 0).
        float big = ir * 0.26f, half = big / 2f;
        Path disc = new Path();
        disc.addCircle(0f, 0f, big, Path.Direction.CW);
        Path left = new Path();
        left.addRect(-big, -big, 0f, big, Path.Direction.CW);
        disc.op(left, Path.Op.INTERSECT);
        Path head = new Path();
        head.addCircle(0f, -half, half, Path.Direction.CW);
        disc.op(head, Path.Op.UNION);
        Path cut = new Path();
        cut.addCircle(0f, half, half, Path.Direction.CW);
        disc.op(cut, Path.Op.DIFFERENCE);
        Matrix place = new Matrix();
        place.postRotate(90f);
        place.postTranslate(0f, -ir * 0.62f);
        tomoePath.reset();
        disc.transform(place, tomoePath);
    }

    /** Starts the idle spin and the periodic blink; call from onResume. */
    void start() {
        if (spinAnim != null) return;
        spinAnim = ValueAnimator.ofFloat(0f, 360f);
        spinAnim.setDuration(9000);
        spinAnim.setRepeatCount(ValueAnimator.INFINITE);
        spinAnim.setInterpolator(new LinearInterpolator());
        spinAnim.addUpdateListener(a -> {
            spin = (float) a.getAnimatedValue();
            invalidate();
        });
        spinAnim.start();
        handler.postDelayed(idleBlink, 3000);
    }

    /** Stops every animator and timer; call from onPause so nothing spins off-screen. */
    void stop() {
        handler.removeCallbacksAndMessages(null);
        if (spinAnim != null) spinAnim.cancel();
        if (lookAnim != null) lookAnim.cancel();
        if (blinkAnim != null) blinkAnim.cancel();
        spinAnim = null;
        lookAnim = null;
        blinkAnim = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }

    /** Turns the iris toward {@code target}'s centre, and again once a scroll it set off has settled. */
    void lookAt(View target) {
        aimAt(target);
        handler.postDelayed(() -> aimAt(target), 320);
    }

    private void aimAt(View target) {
        if (getWidth() == 0 || !target.isAttachedToWindow()) return;
        target.getLocationOnScreen(loc);
        float tx = loc[0] + target.getWidth() / 2f, ty = loc[1] + target.getHeight() / 2f;
        getLocationOnScreen(loc);
        DisplayMetrics m = getResources().getDisplayMetrics();
        float nx = clamp((tx - (loc[0] + cx)) / (m.widthPixels / 2f));
        float ny = clamp((ty - (loc[1] + cy)) / (m.heightPixels / 2f));
        // Far enough to read as looking, never so far the iris leaves the eye's corner.
        float toX = nx * (rx - ir * 0.8f), toY = ny * ry * 0.25f;
        if (Math.abs(toX - offX) < 1f && Math.abs(toY - offY) < 1f) return;
        float fromX = offX, fromY = offY;
        if (lookAnim != null) lookAnim.cancel();
        lookAnim = ValueAnimator.ofFloat(0f, 1f);
        lookAnim.setDuration(280);
        lookAnim.setInterpolator(new DecelerateInterpolator());
        lookAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            offX = fromX + (toX - fromX) * f;
            offY = fromY + (toY - fromY) * f;
            invalidate();
        });
        lookAnim.start();
        if (SystemClock.uptimeMillis() - lastBlink > BLINK_GAP_MS && Math.abs(toX - fromX) > rx * 0.2f) blink();
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    void blink() {
        lastBlink = SystemClock.uptimeMillis();
        if (blinkAnim != null) blinkAnim.cancel();
        blinkAnim = ValueAnimator.ofFloat(0f, 1f, 0f);
        blinkAnim.setDuration(360);
        blinkAnim.addUpdateListener(a -> {
            blink = (float) a.getAnimatedValue();
            invalidate();
        });
        blinkAnim.start();
    }

    /** A spin-and-fade intro for the boot splash; calls {@code onEnd} once fully faded out. */
    void playBootIntro(Runnable onEnd) {
        setAlpha(0f);
        setScaleX(0.4f);
        setScaleY(0.4f);
        setRotation(0f);
        animate().alpha(1f).scaleX(1.15f).scaleY(1.15f).rotationBy(1080f).setDuration(2200)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> animate().alpha(0f).setDuration(320).withEndAction(onEnd).start())
                .start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() == 0) return;

        canvas.drawCircle(cx, cy, glowR, glow);

        // HUD: a dashed ring turning against the tomoe, two bracket arcs, and scan lines with ticks.
        canvas.save();
        canvas.rotate(-spin * 0.6f, cx, cy);
        canvas.drawCircle(cx, cy, ring, hudRing);
        canvas.restore();
        float a = ring + 5f * dp;
        arcOval.set(cx - a, cy - a, cx + a, cy + a);
        canvas.save();
        canvas.rotate(spin * 0.35f, cx, cy);
        canvas.drawArc(arcOval, 200f, 40f, false, hudArc);
        canvas.drawArc(arcOval, 20f, 40f, false, hudArc);
        canvas.restore();
        canvas.drawLine(cx - scanFrom, cy, cx - scanTo, cy, scanLeft);
        canvas.drawLine(cx + scanFrom, cy, cx + scanTo, cy, scanRight);
        for (int i = 0; i < 3; i++) {
            float x = scanFrom + i * 12f * dp, th = (4f - i) * dp;
            canvas.drawLine(cx - x, cy - th, cx - x, cy + th, hudTick);
            canvas.drawLine(cx + x, cy - th, cx + x, cy + th, hudTick);
        }

        canvas.drawPath(eyePath, lid);

        canvas.save();
        canvas.clipPath(eyePath);
        canvas.translate(cx + offX, cy + offY);
        canvas.drawCircle(0f, 0f, ir, iris);
        canvas.drawCircle(0f, 0f, ir, irisLine);
        canvas.drawCircle(0f, 0f, ir * 0.62f, irisLine);
        canvas.save();
        canvas.rotate(spin);
        for (int i = 0; i < 3; i++) {
            canvas.drawPath(tomoePath, tomoe);
            canvas.rotate(120f);
        }
        canvas.restore();
        canvas.drawCircle(0f, 0f, ir * 0.2f, tomoe);
        canvas.drawCircle(-ir * 0.38f, -ir * 0.38f, ir * 0.11f, shine);
        canvas.restore();

        canvas.drawPath(eyePath, outline);

        if (blink > 0f) {
            canvas.save();
            canvas.clipPath(eyePath);
            canvas.scale(1f, blink, cx, cy);
            canvas.drawPath(eyePath, lid);
            canvas.restore();
        }
    }
}
