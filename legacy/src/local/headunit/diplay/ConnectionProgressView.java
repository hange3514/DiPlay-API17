package local.headunit.diplay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/** Full-screen phase progress shown while waiting for the first CarPlay frame. */
final class ConnectionProgressView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ring = new RectF();
    private Shader background;
    private Shader glow;
    private final float density;
    private float progress;
    private float targetProgress = .04f;
    private final long startedAt = SystemClock.uptimeMillis();
    private String title = "\u6b63\u5728\u51c6\u5907\u8fde\u63a5";
    private String detail = "\u8bf7\u4fdd\u6301 iPhone \u5df2\u89e3\u9501\u5e76\u8fde\u63a5 USB";

    ConnectionProgressView(Context context) { this(context, null); }

    ConnectionProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setClickable(true);
        setContentDescription("\u6b63\u5728\u8fde\u63a5 CarPlay");
    }

    private float dp(float value) { return value * density; }

    void resetForAttempt() {
        progress = 0f;
        targetProgress = .04f;
        title = "\u6b63\u5728\u51c6\u5907\u8fde\u63a5";
        detail = "\u8bf7\u4fdd\u6301 iPhone \u5df2\u89e3\u9501\u5e76\u8fde\u63a5 USB";
        setContentDescription(title + "\uff0c" + detail);
        invalidate();
    }

    void setStage(String stage, String rawMessage) {
        String nextTitle = title;
        String nextDetail = "\u8bf7\u4fdd\u6301 iPhone \u5df2\u89e3\u9501\u5e76\u8fde\u63a5 USB";
        float nextProgress = targetProgress;
        boolean newAttempt = "CONNECT_START".equals(stage);
        if (newAttempt) { nextTitle = "\u6b63\u5728\u51c6\u5907\u8fde\u63a5"; nextProgress = .04f; }
        else if ("WAIT_USB".equals(stage)) { nextTitle = "\u7b49\u5f85 iPhone"; nextDetail = "\u8bf7\u68c0\u67e5\u6570\u636e\u7ebf\u5df2\u8fde\u63a5"; nextProgress = .08f; }
        else if ("USB_PERMISSION".equals(stage)) { nextTitle = "\u7b49\u5f85 USB \u6388\u6743"; nextDetail = "\u8bf7\u5728\u8f66\u673a\u63d0\u793a\u4e2d\u9009\u62e9 DiPlay"; nextProgress = .13f; }
        else if ("REENUMERATION".equals(stage)) { nextTitle = "\u5207\u6362 CarPlay USB \u901a\u9053"; nextProgress = .22f; }
        else if ("USB_TAKEOVER".equals(stage)) { nextTitle = "\u6b63\u5728\u5efa\u7acb USB \u8fde\u63a5"; nextProgress = .29f; }
        else if ("PAIRING".equals(stage)) { nextTitle = "\u8bf7\u5728 iPhone \u786e\u8ba4"; nextDetail = "\u9996\u6b21\u8fde\u63a5\u65f6\u8bf7\u70b9\u51fb\u201c\u4fe1\u4efb\u201d"; nextProgress = .39f; }
        else if ("PAIRING_RETRY".equals(stage)) { nextTitle = "\u6b63\u5728\u91cd\u65b0\u8fde\u63a5"; nextDetail = "\u8bf7\u6309 iPhone \u5c4f\u5e55\u63d0\u793a\u64cd\u4f5c"; nextProgress = .42f; }
        else if ("CARKIT".equals(stage)) { nextTitle = "\u6b63\u5728\u8fde\u63a5 iPhone"; nextProgress = .48f; }
        else if ("IAP2_AUTH".equals(stage)) { nextTitle = "\u6b63\u5728\u9a8c\u8bc1 CarPlay"; nextProgress = .58f; }
        else if ("AUTH_COMPLETE".equals(stage)) { nextTitle = "\u6388\u6743\u5b8c\u6210"; nextDetail = "\u7b49\u5f85 iPhone \u542f\u52a8\u6295\u5c4f"; nextProgress = .68f; }
        else if ("PHONE_START".equals(stage)) { nextTitle = "\u6b63\u5728\u542f\u52a8\u6295\u5c4f"; nextDetail = "\u6b63\u5728\u51c6\u5907\u753b\u9762\u4f1a\u8bdd"; nextProgress = .75f; }
        else if ("AIRPLAY_HANDSHAKE".equals(stage)) { nextTitle = "\u6b63\u5728\u5efa\u7acb\u753b\u9762\u4f1a\u8bdd"; nextProgress = .86f; }
        else if ("AIRPLAY_ACTIVE".equals(stage)) { nextTitle = "\u6295\u5c4f\u901a\u9053\u5df2\u5c31\u7eea"; nextDetail = "\u5373\u5c06\u663e\u793a CarPlay \u753b\u9762"; nextProgress = .94f; }
        else if ("VIDEO_READY".equals(stage)) { nextTitle = "CarPlay \u5df2\u8fde\u63a5"; nextDetail = "\u6b63\u5728\u663e\u793a\u753b\u9762"; nextProgress = 1f; }
        else if ("FAILED".equals(stage)) { nextTitle = "\u8fde\u63a5\u672a\u5b8c\u6210"; nextDetail = "\u53ef\u91cd\u65b0\u63d2\u62d4 USB \u6216\u68c0\u67e5\u6388\u6743"; }
        else if ("DISCONNECTED".equals(stage)) { nextTitle = "\u8fde\u63a5\u5df2\u65ad\u5f00"; nextDetail = "\u8bf7\u91cd\u65b0\u63d2\u5165 iPhone"; }
        title = nextTitle;
        detail = nextDetail;
        if (newAttempt) {
            progress = 0f;
            targetProgress = nextProgress;
        } else {
            targetProgress = Math.max(targetProgress, nextProgress);
        }
        setContentDescription(title + "\uff0c" + detail);
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        background = new LinearGradient(0, 0, width, height,
                new int[]{0xff0d1b2c, 0xff07111e, 0xff03070e}, null, Shader.TileMode.CLAMP);
        glow = new RadialGradient(width * .5f, height * .44f, Math.max(width, height) * .52f,
                new int[]{0x3313a5c3, 0x1121758d, 0x00030a12}, null, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float width = getWidth(), height = getHeight();
        final float cx = width * .5f, cy = height * .5f;
        paint.setShader(background);
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(glow);
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);

        float cardWidth = Math.min(width - dp(36), dp(470));
        float cardHeight = dp(340);
        RectF card = new RectF(cx - cardWidth / 2, cy - cardHeight / 2,
                cx + cardWidth / 2, cy + cardHeight / 2);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xe8111d2c);
        canvas.drawRoundRect(card, dp(24), dp(24), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0x4439c4df);
        canvas.drawRoundRect(card, dp(24), dp(24), paint);
        paint.setStyle(Paint.Style.FILL);

        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        paint.setTextSize(dp(11));
        paint.setColor(0xff64cbe0);
        canvas.drawText("DIPLAY  ·  WIRED CARPLAY", cx, card.top + dp(30), paint);

        final long now = SystemClock.uptimeMillis();
        float delta = targetProgress - progress;
        if (Math.abs(delta) > .002f) progress += delta * .11f;
        else progress = targetProgress;
        float radius = dp(55);
        float ringCy = cy - dp(39);
        ring.set(cx - radius, ringCy - radius, cx + radius, ringCy + radius);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(7));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xff223548);
        canvas.drawArc(ring, -90, 360, false, paint);
        paint.setColor(0xff36c2dc);
        canvas.drawArc(ring, -90, Math.max(8, progress * 360f), false, paint);
        paint.setColor(0xffb6f3ff);
        float rotation = ((now - startedAt) * .09f) % 360f;
        canvas.drawArc(ring, rotation, 27f, false, paint);
        paint.setStyle(Paint.Style.FILL);

        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        paint.setTextSize(dp(13));
        paint.setColor(0xff83a0b7);
        canvas.drawText(Math.round(progress * 100f) + "%", cx, ringCy + radius + dp(24), paint);
        paint.setTextSize(dp(20));
        paint.setColor(0xfff0f6fc);
        canvas.drawText(title, cx, ringCy + radius + dp(60), paint);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif", 0));
        paint.setTextSize(dp(13));
        paint.setColor(0xffa4b6c7);
        canvas.drawText(detail, cx, ringCy + radius + dp(86), paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        if (getVisibility() == VISIBLE) postInvalidateDelayed(32);
    }
}
