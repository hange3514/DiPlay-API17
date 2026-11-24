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
    private static final int TOTAL_STEPS = 12;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ring = new RectF();
    private Shader background;
    private Shader glow;
    private final float density;
    private float progress;
    private float targetProgress = 1f / TOTAL_STEPS;
    private int currentStep = 1;
    private final long startedAt = SystemClock.uptimeMillis();
    private String title = "\u6b63\u5728\u542f\u52a8 CM591";
    private String detail = "\u6b63\u5728\u521d\u59cb\u5316 USB \u84dd\u7259\u9002\u914d\u5668";
    private String probeAddress;

    ConnectionProgressView(Context context) { this(context, null); }

    ConnectionProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setClickable(true);
        setContentDescription("\u6b63\u5728\u8fde\u63a5 CarPlay");
    }

    private float dp(float value) { return value * density; }

    private void drawWrappedCenteredText(Canvas canvas, String value, float centerX, float baseline, float maxWidth) {
        if (value == null || value.length() == 0) return;
        StringBuilder line = new StringBuilder();
        int lines = 0;
        float lineHeight = dp(18);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\n') {
                drawCenteredLine(canvas, line.toString(), centerX, baseline + lines * lineHeight);
                line.setLength(0);
                if (++lines >= 2) return;
                continue;
            }
            if (line.length() > 0 && paint.measureText(line.toString() + ch) > maxWidth) {
                drawCenteredLine(canvas, line.toString(), centerX, baseline + lines * lineHeight);
                line.setLength(0);
                if (++lines >= 2) return;
            }
            line.append(ch);
        }
        if (line.length() > 0 && lines < 2) drawCenteredLine(canvas, line.toString(), centerX, baseline + lines * lineHeight);
    }

    private void drawCenteredLine(Canvas canvas, String line, float centerX, float baseline) {
        canvas.drawText(line, centerX, baseline, paint);
    }

    void resetForAttempt() {
        progress = 0f;
        currentStep = 1;
        targetProgress = 1f / TOTAL_STEPS;
        title = "\u6b63\u5728\u542f\u52a8 CM591";
        detail = "\u6b63\u5728\u521d\u59cb\u5316 USB \u84dd\u7259\u9002\u914d\u5668";
        probeAddress = null;
        setContentDescription(title + "\uff0c\u9636\u6bb5 1 / " + TOTAL_STEPS + "\uff0c" + detail);
        invalidate();
    }

    void setStage(String stage, String rawMessage) {
        String nextTitle = title;
        String nextDetail = detail;
        int nextStep = currentStep;
        boolean newAttempt = "CONNECT_START".equals(stage);
        if (newAttempt) {
            nextTitle = "准备无线 CarPlay";
            nextDetail = "正在检查车载热点和 CM591 蓝牙适配器";
            nextStep = 1;
            probeAddress = null;
        }
        else if ("PAIRING".equals(stage) || "PAIRING_CARPLAY".equals(stage)) {
            nextTitle = "\u7b49\u5f85 iPhone \u84dd\u7259\u914d\u5bf9";
            String bluetoothName = MainActivity.bluetoothName(getContext());
            nextDetail = ("PAIRING_CARPLAY".equals(stage)
                ? "请在 iPhone 设置 > 通用 > CarPlay 车载中选择\n"
                : "\u8bf7\u5728 iPhone \u8bbe\u7f6e > \u84dd\u7259\u4e2d\u641c\u7d22\u5e76\u914d\u5bf9\u8f66\u673a\u540d\u79f0\uff1a\n") + bluetoothName;
            nextStep = 2;
        }
        else if ("BT_PHONE_INQUIRY".equals(stage)) {
            nextTitle = "\u6b63\u5728\u641c\u7d22 iPhone";
            nextDetail = "\u8bf7\u6253\u5f00 iPhone \u7684\u300c\u8bbe\u7f6e \u2192 \u84dd\u7259\u300d\u5e76\u505c\u7559\u5728\u8be5\u9875\u9762";
            nextStep = 2;
        }
        else if ("BT_PHONE_SELECT".equals(stage)) {
            nextTitle = "\u5728\u8f66\u673a\u4e0a\u9009\u62e9 iPhone";
            nextDetail = "\u8bf7\u5728\u641c\u7d22\u7ed3\u679c\u4e2d\u70b9\u9009\u8981\u8fde\u63a5\u7684\u624b\u673a";
            nextStep = 2;
        }
        else if ("BT_PHONE_CONNECT".equals(stage)) {
            nextTitle = "\u8f66\u673a\u6b63\u5728\u8fde\u63a5 iPhone";
            nextDetail = "\u9996\u6b21\u914d\u5bf9\u65f6\uff0c\u8bf7\u786e\u8ba4\u624b\u673a\u4e0a\u7684\u914d\u5bf9\u8bf7\u6c42";
            nextStep = 2;
        }
        else if ("BT_PHONE_RECONNECT".equals(stage)) {
            nextTitle = "正在直连已保存的 iPhone";
            nextDetail = "请保持 iPhone 蓝牙开启";
            nextStep = 2;
        }
        else if ("BT_PHONE_RETRY".equals(stage)) {
            nextTitle = "正在恢复 iPhone 蓝牙连接";
            nextDetail = rawMessage == null || rawMessage.isEmpty() ? "即将重试已保存的 iPhone" : rawMessage;
            nextStep = 2;
        }
        else if ("CARKIT".equals(stage)) {
            nextTitle = "\u5efa\u7acb iAP2 \u63a7\u5236\u8fde\u63a5";
            nextDetail = "\u6b63\u901a\u8fc7 CM591 \u84dd\u7259\u901a\u9053\u4e0e iPhone \u5efa\u7acb CarPlay \u63a7\u5236\u4f1a\u8bdd";
            nextStep = 3;
        }
        else if ("WIFI_WAIT".equals(stage)) {
            nextTitle = "准备车载热点";
            nextDetail = rawMessage == null || rawMessage.isEmpty() ? "正在检查车载热点地址" : rawMessage;
            nextStep = 1;
        }
        else if ("WIFI_READY".equals(stage)) {
            nextTitle = "车载热点已就绪";
            nextDetail = "正在准备热点的投屏数据通道";
            nextStep = 5;
        }
        else if ("HYBRID_WIFI_HANDOFF".equals(stage)) {
            nextTitle = "准备 Wi-Fi 投屏服务";
            nextDetail = "正在启动 AirPlay 监听和发现服务";
            nextStep = 6;
        }
        else if ("IAP2_AUTH".equals(stage)) {
            nextTitle = "\u9a8c\u8bc1 CarPlay \u8eab\u4efd";
            nextDetail = "\u6b63\u5728\u4ea4\u6362\u914d\u4ef6\u4fe1\u606f\uff0c\u51c6\u5907\u8fdb\u884c iAP2 \u8ba4\u8bc1";
            nextStep = 7;
        }
        else if ("AUTH_COMPLETE".equals(stage)) {
            nextTitle = "\u8bf7\u6c42\u542f\u52a8\u65e0\u7ebf CarPlay";
            nextDetail = "iAP2 认证完成，正在等待 iPhone 加入车载热点并接入投屏";
            nextStep = 8;
        }
        else if ("PHONE_START".equals(stage)) {
            nextTitle = "\u7b49\u5f85 iPhone \u63a5\u5165\u6295\u5c4f";
            probeAddress = null;
            if (rawMessage != null && rawMessage.startsWith("PHONE_PROBE http://")) {
                String candidate = rawMessage.substring("PHONE_PROBE ".length()).trim();
                if (candidate.length() <= 96 && candidate.endsWith(":7001") && candidate.indexOf(' ') < 0) {
                    probeAddress = candidate;
                }
            }
            nextDetail = probeAddress != null
                    ? "\u5df2\u53d1\u9001 CarPlayStartSession\uff0c\u7b49\u5f85 AirPlay \u63a5\u5165\uff1b\u5361\u4f4f\u65f6\u518d\u7528\u4e0b\u65b9\u5730\u5740\u8bca\u65ad"
                    : rawMessage != null && rawMessage.startsWith("PHONE_PROBE_UNAVAILABLE ")
                    ? rawMessage.substring("PHONE_PROBE_UNAVAILABLE ".length()).trim()
                    : "\u5df2\u901a\u8fc7 iAP2 \u53d1\u9001 CarPlayStartSession\uff0c\u6b63\u7b49\u5f85 iPhone \u8fde\u63a5 AirPlay Wi-Fi \u6570\u636e\u901a\u9053";
            nextStep = 9;
        }
        else if ("AIRPLAY_HANDSHAKE".equals(stage)) { probeAddress = null; nextTitle = "\u5efa\u7acb AirPlay \u6295\u5c4f\u4f1a\u8bdd"; nextDetail = "iPhone \u5df2\u63a5\u5165\u65e0\u7ebf\u6570\u636e\u901a\u9053\uff0c\u6b63\u5728\u534f\u5546\u753b\u9762"; nextStep = 10; }
        else if ("AIRPLAY_ACTIVE".equals(stage)) { probeAddress = null; nextTitle = "\u6295\u5c4f\u901a\u9053\u5df2\u8fde\u63a5"; nextDetail = "\u6b63\u5728\u89e3\u7801\u9996\u5e27 CarPlay \u753b\u9762"; nextStep = 11; }
        else if ("VIDEO_READY".equals(stage)) { probeAddress = null; nextTitle = "CarPlay \u5df2\u8fde\u63a5"; nextDetail = "\u6b63\u5728\u663e\u793a\u753b\u9762"; nextStep = 12; }
        else if ("IAP2_CHANNEL_CLOSED_WIFI_WAIT".equals(stage) || "USB_DETACHED_WIFI_WAIT".equals(stage)) { nextTitle = "\u7b49\u5f85 Wi-Fi \u4f1a\u8bdd\u63a5\u7ba1"; nextDetail = "Bluetooth RFCOMM \u63a7\u5236\u901a\u9053\u5df2\u7ed3\u675f\uff0c\u6b63\u68c0\u67e5 AirPlay \u662f\u5426\u5df2\u901a\u8fc7 Wi-Fi \u5efa\u7acb"; nextStep = 9; }
        else if ("AIRPLAY_ENDED".equals(stage)) { nextTitle = "iPhone \u5df2\u7ed3\u675f\u6295\u5c4f"; nextDetail = "\u5f53\u524d CarPlay \u4f1a\u8bdd\u5df2\u7ed3\u675f"; }
        else if ("FAILED".equals(stage)) { probeAddress = null; nextTitle = "\u8fde\u63a5\u672a\u5b8c\u6210"; nextDetail = "\u68c0\u67e5 CM591 USB \u6388\u6743\u3001iPhone \u84dd\u7259\u914d\u5bf9和 Wi-Fi 连接\uff0c\u7136\u540e\u91cd\u8bd5\u6216\u63d0\u4ea4\u65e5\u5fd7"; }
        else if ("DISCONNECTED".equals(stage)) { probeAddress = null; nextTitle = "\u8fde\u63a5\u5df2\u65ad\u5f00"; nextDetail = "\u786e\u8ba4 CM591 \u4ecd\u63d2\u63a5\uff0ciPhone \u84dd\u7259和 Wi-Fi 在线\u540e\u91cd\u8fde"; }
        else if ("STOPPED".equals(stage)) { probeAddress = null; nextTitle = "\u5df2\u505c\u6b62"; nextDetail = "\u672c\u6b21\u8fde\u63a5\u5df2\u91ca\u653e\uff0c\u53ef\u5728\u9002\u914d\u5668\u3001蓝牙和 Wi-Fi 就绪\u540e\u91cd\u65b0\u8fde\u63a5"; }
        title = nextTitle;
        detail = nextDetail;
        currentStep = newAttempt ? nextStep : Math.max(currentStep, nextStep);
        if (newAttempt) {
            progress = 0f;
            targetProgress = currentStep / (float) TOTAL_STEPS;
        } else {
            targetProgress = Math.max(targetProgress, currentStep / (float) TOTAL_STEPS);
        }
        setContentDescription(title + "\uff0c\u9636\u6bb5 " + currentStep + " / " + TOTAL_STEPS + "\uff0c" + detail + (probeAddress == null ? "" : "\uff0c" + probeAddress));
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
        float cardHeight = Math.min(dp(380), height - dp(24));
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
        canvas.drawText("DIPLAY  ·  CM591 WIRELESS CARPLAY", cx, card.top + dp(30), paint);

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
        canvas.drawText("\u9636\u6bb5 " + currentStep + " / " + TOTAL_STEPS, cx, ringCy + radius + dp(24), paint);
        paint.setTextSize(dp(20));
        paint.setColor(0xfff0f6fc);
        canvas.drawText(title, cx, ringCy + radius + dp(60), paint);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif", 0));
        if (probeAddress != null) {
            paint.setTextSize(dp(11));
            paint.setColor(0xff83a0b7);
            canvas.drawText("\u53ef\u9009\u7f51\u7edc\u8bca\u65ad\u5730\u5740", cx, ringCy + radius + dp(84), paint);
            float boxWidth = Math.min(cardWidth - dp(40), dp(430));
            float boxTop = ringCy + radius + dp(94);
            RectF addressBox = new RectF(cx - boxWidth / 2, boxTop, cx + boxWidth / 2, boxTop + dp(38));
            paint.setColor(0xff102b3a);
            canvas.drawRoundRect(addressBox, dp(10), dp(10), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0xff28728a);
            canvas.drawRoundRect(addressBox, dp(10), dp(10), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(android.graphics.Typeface.create("monospace", 0));
            float textSize = dp(15);
            paint.setTextSize(textSize);
            while (textSize > dp(10) && paint.measureText(probeAddress) > boxWidth - dp(20)) {
                textSize -= dp(1);
                paint.setTextSize(textSize);
            }
            paint.setColor(0xffdffaff);
            float baseline = addressBox.centerY() - (paint.ascent() + paint.descent()) / 2f;
            canvas.drawText(probeAddress, cx, baseline, paint);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif", 0));
            paint.setTextSize(dp(10));
            paint.setColor(0xff8199ad);
            canvas.drawText("\u4ec5\u5728\u6295\u5c4f\u5361\u4f4f\u65f6\u4f7f\u7528\uff0c\u4e0d\u5f71\u54cd\u81ea\u52a8\u8fde\u63a5", cx, addressBox.bottom + dp(20), paint);
        } else {
            paint.setTextSize(dp(13));
            paint.setColor(0xffa4b6c7);
            drawWrappedCenteredText(canvas, detail, cx, ringCy + radius + dp(86), cardWidth - dp(40));
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        if (getVisibility() == VISIBLE) postInvalidateDelayed(50);
    }
}
