package uz.yulemdo.browser;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Ilova ochilishidagi ekran.
 *  - Ilk kirishda: markazda qalin "Welcome" yozuvi. Qora-olovrang gradient ichida
 *    suyuqlik kabi oqib aylanadi, fade in -> fade out.
 *  - Keyingi kirishlarda: oddiy olovrang loader.
 */
public class SplashView extends View {
    static final int BG = 0xFFFFFFFF;
    /** Eng uzun variant (Welcome). MainActivity zaxira taymeri shu qiymatdan foydalanadi. */
    static final int TOTAL_MS = 2800;
    static final int RETURN_MS = 900;
    static final String WELCOME_TEXT = "Welcome";

    private static final int FADE_MS = 700;

    private final boolean first;
    private final float density;
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Matrix shaderMatrix = new Matrix();

    private LinearGradient gradient;
    private float gradientWidth = -1f;
    private ValueAnimator anim;
    private float progress;
    private boolean cancelled;

    public SplashView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        SharedPreferences prefs = ctx.getSharedPreferences("yulemdo", Context.MODE_PRIVATE);
        first = !prefs.getBoolean("welcomeShown", false);
        setBackgroundColor(BG);
        setClickable(true);

        textPaint.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        textPaint.setTextAlign(Paint.Align.LEFT);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(3f * density);
        ringPaint.setStrokeCap(Paint.Cap.BUTT);
    }

    public void start(final Runnable onDone) {
        if (first) {
            getContext().getSharedPreferences("yulemdo", Context.MODE_PRIVATE)
                    .edit().putBoolean("welcomeShown", true).apply();
        }
        final int dur = first ? TOTAL_MS : RETURN_MS;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(dur);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            progress = (Float) a.getAnimatedValue();
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (!cancelled && onDone != null) onDone.run();
            }
        });
        anim.start();
    }

    public void finish() {
        cancelled = true;
        if (anim != null) anim.cancel();
    }

    @Override
    protected void onDetachedFromWindow() {
        finish();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (first) drawWelcome(c); else drawLoader(c);
    }

    private static float smooth(float x) {
        x = Math.max(0f, Math.min(1f, x));
        return x * x * (3f - 2f * x);
    }

    private void drawWelcome(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float ms = progress * TOTAL_MS;

        float a;
        if (ms < FADE_MS) a = ms / FADE_MS;
        else if (ms > TOTAL_MS - FADE_MS) a = (TOTAL_MS - ms) / FADE_MS;
        else a = 1f;
        a = smooth(a);

        // Yozuv kengligi ekranning ~78% iga sig'adi.
        float size = 54f * density;
        textPaint.setTextSize(size);
        float tw = textPaint.measureText(WELCOME_TEXT);
        float maxW = w * 0.78f;
        if (tw > maxW) {
            size *= maxW / tw;
            textPaint.setTextSize(size);
            tw = textPaint.measureText(WELCOME_TEXT);
        }

        // Qora -> olovrang gradient (MIRROR: chegarasiz oqadi).
        float gw = tw * 1.1f;
        if (gradient == null || Math.abs(gw - gradientWidth) > 0.5f) {
            gradientWidth = gw;
            gradient = new LinearGradient(0, 0, gw, 0,
                    new int[]{0xFF0D0D0D, 0xFF2B1204, 0xFFFF6D00, 0xFFFF9800, 0xFFFF6D00, 0xFF2B1204, 0xFF0D0D0D},
                    null, Shader.TileMode.MIRROR);
            textPaint.setShader(gradient);
        }

        // Suyuqlik effekti: gradient egilib, tebranib, oldinga siljiydi.
        float t = ms / 1000f;
        shaderMatrix.reset();
        shaderMatrix.setRotate(22f + 14f * (float) Math.sin(t * 2.1f), gw / 2f, 0f);
        shaderMatrix.postTranslate(t * tw * 0.55f, 0f);
        gradient.setLocalMatrix(shaderMatrix);

        textPaint.setAlpha((int) (255 * a));

        float scale = 0.94f + 0.06f * a;
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float x = (w - tw) / 2f;
        float y = h / 2f - (fm.ascent + fm.descent) / 2f;
        c.save();
        c.scale(scale, scale, w / 2f, h / 2f);
        c.drawText(WELCOME_TEXT, x, y, textPaint);
        c.restore();
    }

    private void drawLoader(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float ms = progress * RETURN_MS;
        float a = smooth(ms / 180f);

        float size = 42f * density;
        float cx = w / 2f, cy = h / 2f, r = size / 2f - 1.5f * density;
        float angle = (ms % 850f) / 850f * 360f;

        c.save();
        c.rotate(angle, cx, cy);
        ringPaint.setShader(null);
        ringPaint.setColor(0xFFDFE1E5);
        ringPaint.setAlpha((int) (255 * a));
        c.drawCircle(cx, cy, r, ringPaint);
        box.set(cx - r, cy - r, cx + r, cy + r);
        ringPaint.setColor(0xFFFF6F00);
        ringPaint.setAlpha((int) (255 * a));
        c.drawArc(box, -90, 90, false, ringPaint);
        ringPaint.setColor(0xFFFF9800);
        ringPaint.setAlpha((int) (255 * a));
        c.drawArc(box, 0, 90, false, ringPaint);
        c.restore();
    }
}
