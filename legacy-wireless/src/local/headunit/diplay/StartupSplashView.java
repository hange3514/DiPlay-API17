package local.headunit.diplay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/** Short branded transition while process-wide resources warm up in the background. */
final class StartupSplashView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final Bitmap logo;
    private final long startedAt = SystemClock.uptimeMillis();
    private Shader background;
    private Shader glow;
    private final float density;

    StartupSplashView(Context context) { this(context, null); }

    StartupSplashView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        Bitmap decoded = null;
        try {
            java.io.InputStream input = context.getAssets().open("carplay_mark.png");
            try { decoded = BitmapFactory.decodeStream(input); }
            finally { input.close(); }
        } catch (Exception ignored) { }
        logo = decoded;
        setClickable(true);
        setContentDescription("DiPlay \u6b63\u5728\u51c6\u5907\u8fde\u63a5\u73af\u5883");
    }

    private float dp(float value) { return value * density; }

    Bitmap logoBitmap() { return logo; }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        background = new LinearGradient(0, 0, width, height,
                new int[]{0xff102238, 0xff081321, 0xff040811}, null, Shader.TileMode.CLAMP);
        glow = new RadialGradient(width * .5f, height * .42f, Math.max(width, height) * .55f,
                new int[]{0x3320b8d8, 0x11277fa5, 0x0008111f}, null, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float cx = getWidth() * .5f;
        final float cy = getHeight() * .43f;
        paint.setShader(background);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(glow);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(null);

        final float elapsed = (SystemClock.uptimeMillis() - startedAt) / 1000f;
        final float pulse = .5f + .5f * (float)Math.sin(elapsed * 2.2f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(2));
        paint.setColor(0x2238c9e8);
        arc.set(cx - dp(62), cy - dp(62), cx + dp(62), cy + dp(62));
        canvas.drawArc(arc, 0, 360, false, paint);
        paint.setStrokeWidth(dp(3));
        paint.setColor(0xff35c6e2);
        canvas.drawArc(arc, elapsed * 105f, 88f + pulse * 18f, false, paint);
        paint.setColor(0x5535c6e2);
        paint.setStrokeWidth(dp(1));
        arc.set(cx - dp(76), cy - dp(76), cx + dp(76), cy + dp(76));
        canvas.drawArc(arc, -elapsed * 62f, 48f, false, paint);
        paint.setStyle(Paint.Style.FILL);

        if (logo != null && !logo.isRecycled()) {
            float size = dp(70) * (1f + pulse * .035f);
            paint.setShadowLayer(dp(20), 0, dp(4), 0x9935c6e2);
            canvas.drawBitmap(logo, null, new RectF(cx - size / 2, cy - size / 2,
                    cx + size / 2, cy + size / 2), paint);
            paint.clearShadowLayer();
        }

        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        paint.setTextSize(dp(30));
        paint.setColor(0xfff5f9ff);
        canvas.drawText("DiPlay", cx, cy + dp(126), paint);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif", 0));
        paint.setTextSize(dp(14));
        paint.setColor(0xffa8bacd);
        canvas.drawText("\u6709\u7ebf CarPlay", cx, cy + dp(151), paint);

        paint.setColor(0xff35c6e2);
        canvas.drawCircle(cx - dp(57), cy + dp(205), dp(3), paint);
        paint.setColor(0xff8ba2b9);
        paint.setTextSize(dp(12));
        canvas.drawText("\u6b63\u5728\u51c6\u5907\u8fde\u63a5\u73af\u5883", cx, cy + dp(209), paint);
        if (getVisibility() == VISIBLE) postInvalidateDelayed(50);
    }
}
