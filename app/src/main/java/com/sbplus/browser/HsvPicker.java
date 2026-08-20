package com.sbplus.browser;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ViewFlipper;

/**
 * HsvPicker — 简单 HSV 色盘: 上方 SV(饱和-明度) 面, 下方色相条.
 * 拖动改变颜色, 同步回传 hex 输入框与预览块.
 */
public class HsvPicker extends View {
    private float hue = 220f;          // 0-360
    private float sat = 0.6f;          // 0-1
    private float val = 0.7f;          // 0-1
    private boolean dragging = false;

    private final Paint paint = new Paint();
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    private EditText hexEt;
    private View preview;
    private boolean selfUpdate = false;

    private int svH = 200;   // SV 面高度(px), 更大更好操作
    private int hueH = 44;   // 色相条高度(px)
    private int pad = 12;

    public HsvPicker(Context c) { super(c); init(); }

    private void init() {
        paint.setAntiAlias(true);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f);
        stroke.setColor(0xFF000000);
    }

    public void attach(EditText hexEt, View preview) {
        this.hexEt = hexEt;
        this.preview = preview;
        if (hexEt != null) {
            hexEt.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    if (selfUpdate) return;
                    int col = ThemeColorHelper.parseHex(s.toString());
                    if (col != -1) {
                        selfUpdate = true;
                        try { setColor(col); } finally { selfUpdate = false; }
                        if (preview != null) preview.setBackgroundColor(col);
                        invalidate();
                    }
                }
            });
        }
    }

    public void setColor(int argb) {
        float[] hsv = new float[3];
        Color.colorToHSV(argb, hsv);
        hue = hsv[0];
        sat = hsv[1];
        val = hsv[2];
        invalidate();
    }

    public int getColor() {
        return Color.HSVToColor(new float[]{hue, sat, val});
    }

    @Override
    protected void onMeasure(int wspec, int hspec) {
        int w = MeasureSpec.getSize(wspec);
        if (w == 0) w = 400;
        setMeasuredDimension(w, svH + hueH + pad * 4 + dp(20));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        // SV 面
        int left = pad, top = pad, right = w - pad, bottom = pad + svH;
        // 水平:饱和(0..sat), 垂直:明度(1..0)
        LinearGradient satGrad = new LinearGradient(left, 0, right, 0,
                Color.HSVToColor(new float[]{hue, 0f, val}),
                Color.HSVToColor(new float[]{hue, 1f, val}), Shader.TileMode.CLAMP);
        paint.setShader(satGrad);
        canvas.drawRect(left, top, right, bottom, paint);
        LinearGradient valGrad = new LinearGradient(0, top, 0, bottom,
                Color.HSVToColor(new float[]{hue, 1f, 1f}),
                Color.HSVToColor(new float[]{hue, 1f, 0f}), Shader.TileMode.CLAMP);
        paint.setShader(valGrad);
        canvas.drawRect(left, top, right, bottom, paint);
        paint.setShader(null);
        // 选中点
        float dotX = left + sat * (right - left);
        float dotY = top + (1f - val) * (bottom - top);
        stroke.setColor(0xFFFFFFFF);
        canvas.drawCircle(dotX, dotY, dp(9), stroke);
        stroke.setColor(0xFF000000);
        canvas.drawCircle(dotX, dotY, dp(12), stroke);

        // 色相条
        int hueTop = bottom + pad;
        int hueBottom = hueTop + hueH;
        LinearGradient hueGrad = new LinearGradient(left, 0, right, 0,
                new int[]{0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
                            0xFF0000FF, 0xFFFF00FF, 0xFFFF0000},
                null, Shader.TileMode.CLAMP);
        paint.setShader(hueGrad);
        canvas.drawRect(left, hueTop, right, hueBottom, paint);
        paint.setShader(null);
        // 色相游标
        float hueX = left + (hue / 360f) * (right - left);
        stroke.setColor(0xFFFFFFFF);
        canvas.drawCircle(hueX, (hueTop + hueBottom) / 2f, dp(11), stroke);
        stroke.setColor(0xFF000000);
        canvas.drawCircle(hueX, (hueTop + hueBottom) / 2f, dp(14), stroke);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        float x = ev.getX(), y = ev.getY();
        int w = getWidth();
        boolean inSv = (y > pad && y < pad + svH);
        boolean inHue = (y > pad + svH + pad && y < pad + svH + pad + hueH);
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            dragging = inSv || inHue;
        }
        if (dragging && (ev.getAction() == MotionEvent.ACTION_MOVE
                || ev.getAction() == MotionEvent.ACTION_DOWN)) {
            if (inSv) {
                sat = Math.max(0f, Math.min(1f, (x - pad) / (w - pad * 2f)));
                val = Math.max(0f, Math.min(1f, 1f - (y - pad) / svH));
            } else if (inHue) {
                hue = Math.max(0f, Math.min(359f, (x - pad) / (w - pad * 2f) * 360f));
            }
            refresh();
            return true;
        }
        if (ev.getAction() == MotionEvent.ACTION_UP) {
            dragging = false;
        }
        return dragging || inSv || inHue;
    }

    private void refresh() {
        int col = getColor();
        if (preview != null) preview.setBackgroundColor(col);
        if (hexEt != null) {
            selfUpdate = true;
            try { hexEt.setText("#" + ThemeColorHelper.hex6(col)); } finally { selfUpdate = false; }
        }
        invalidate();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
