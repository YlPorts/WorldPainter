package com.ylports.worldpainter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

public final class WorldEditorView extends View {
    private final WorldModel model;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint brushPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaleDetector;
    private final Matrix bitmapMatrix = new Matrix();
    private final RectF worldRect = new RectF();

    private Bitmap preview;
    private int[] previewPixels;
    private boolean previewDirty = true;
    private int mode = WorldModel.MODE_RAISE;
    private int brushRadius = 7;
    private float zoom = 1f;
    private float offsetX;
    private float offsetY;
    private float lastTwoFingerX;
    private float lastTwoFingerY;
    private boolean painting;

    public WorldEditorView(Context context, WorldModel model) {
        super(context);
        this.model = model;
        setBackgroundColor(Color.rgb(30, 32, 34));
        setFocusable(true);
        setClickable(true);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setColor(Color.argb(70, 255, 255, 255));

        brushPaint.setStyle(Paint.Style.STROKE);
        brushPaint.setStrokeWidth(2.5f);
        brushPaint.setColor(Color.WHITE);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float before = zoom;
                zoom = Math.max(1f, Math.min(12f, zoom * detector.getScaleFactor()));
                if (Math.abs(before - zoom) > 0.0001f) {
                    invalidate();
                }
                return true;
            }
        });
    }

    public void setMode(int mode) {
        this.mode = mode;
    }

    public int getMode() {
        return mode;
    }

    public void setBrushRadius(int brushRadius) {
        this.brushRadius = Math.max(1, Math.min(32, brushRadius));
        invalidate();
    }

    public int getBrushRadius() {
        return brushRadius;
    }

    public void markWorldDirty() {
        previewDirty = true;
        invalidate();
    }

    public void resetView() {
        zoom = 1f;
        offsetX = 0f;
        offsetY = 0f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        ensurePreview();

        float scale = currentScale();
        float drawWidth = model.width * scale;
        float drawHeight = model.depth * scale;
        float left = (getWidth() - drawWidth) * 0.5f + offsetX;
        float top = (getHeight() - drawHeight) * 0.5f + offsetY;

        worldRect.set(left, top, left + drawWidth, top + drawHeight);
        bitmapMatrix.reset();
        bitmapMatrix.postScale(scale, scale);
        bitmapMatrix.postTranslate(left, top);
        canvas.drawBitmap(preview, bitmapMatrix, bitmapPaint);

        if (scale >= 10f) {
            for (int x = 0; x <= model.width; x += 16) {
                float px = left + x * scale;
                canvas.drawLine(px, top, px, top + drawHeight, gridPaint);
            }
            for (int z = 0; z <= model.depth; z += 16) {
                float pz = top + z * scale;
                canvas.drawLine(left, pz, left + drawWidth, pz, gridPaint);
            }
        }
    }

    private void ensurePreview() {
        if (preview == null || preview.getWidth() != model.width || preview.getHeight() != model.depth) {
            preview = Bitmap.createBitmap(model.width, model.depth, Bitmap.Config.ARGB_8888);
            previewPixels = new int[model.width * model.depth];
            previewDirty = true;
        }
        if (!previewDirty) {
            return;
        }
        for (int z = 0; z < model.depth; z++) {
            for (int x = 0; x < model.width; x++) {
                int i = model.index(x, z);
                int h = model.heights[i];
                int color;
                switch (model.materials[i]) {
                    case WorldModel.MAT_SAND:
                        color = shade(Color.rgb(214, 199, 136), h);
                        break;
                    case WorldModel.MAT_STONE:
                        color = shade(Color.rgb(128, 132, 136), h);
                        break;
                    case WorldModel.MAT_GRASS:
                    default:
                        color = shade(Color.rgb(82, 154, 72), h);
                        break;
                }
                if (model.water[i] != 0 || h < model.seaLevel - 1) {
                    color = mix(color, Color.rgb(48, 110, 180), 0.62f);
                }
                previewPixels[i] = color;
            }
        }
        preview.setPixels(previewPixels, 0, model.width, 0, 0, model.width, model.depth);
        previewDirty = false;
    }

    private static int shade(int color, int height) {
        int delta = Math.max(-36, Math.min(44, (height - 62) * 2));
        return Color.rgb(
                clamp(Color.red(color) + delta),
                clamp(Color.green(color) + delta),
                clamp(Color.blue(color) + delta));
    }

    private static int mix(int a, int b, float amount) {
        float inv = 1f - amount;
        return Color.rgb(
                clamp(Math.round(Color.red(a) * inv + Color.red(b) * amount)),
                clamp(Math.round(Color.green(a) * inv + Color.green(b) * amount)),
                clamp(Math.round(Color.blue(a) * inv + Color.blue(b) * amount)));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private float currentScale() {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return 1f;
        }
        float base = Math.min(getWidth() / (float) model.width, getHeight() / (float) model.depth);
        return Math.max(0.1f, base * zoom);
    }

    private float screenToWorldX(float sx) {
        float scale = currentScale();
        float drawWidth = model.width * scale;
        float left = (getWidth() - drawWidth) * 0.5f + offsetX;
        return (sx - left) / scale;
    }

    private float screenToWorldZ(float sy) {
        float scale = currentScale();
        float drawHeight = model.depth * scale;
        float top = (getHeight() - drawHeight) * 0.5f + offsetY;
        return (sy - top) / scale;
    }

    private boolean pointInsideWorld(float sx, float sy) {
        float x = screenToWorldX(sx);
        float z = screenToWorldZ(sy);
        return x >= 0 && z >= 0 && x < model.width && z < model.depth;
    }

    private void paintAt(float sx, float sy) {
        float wx = screenToWorldX(sx);
        float wz = screenToWorldZ(sy);
        if (wx < 0 || wz < 0 || wx >= model.width || wz >= model.depth) {
            return;
        }
        model.applyBrush(wx, wz, brushRadius, mode);
        previewDirty = true;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (pointInsideWorld(event.getX(), event.getY())) {
                    painting = true;
                    model.beginStroke();
                    paintAt(event.getX(), event.getY());
                }
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (painting) {
                    model.endStroke();
                    painting = false;
                }
                if (event.getPointerCount() >= 2) {
                    lastTwoFingerX = midpointX(event);
                    lastTwoFingerY = midpointY(event);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() >= 2) {
                    float midX = midpointX(event);
                    float midY = midpointY(event);
                    offsetX += midX - lastTwoFingerX;
                    offsetY += midY - lastTwoFingerY;
                    lastTwoFingerX = midX;
                    lastTwoFingerY = midY;
                    invalidate();
                } else if (painting && !scaleDetector.isInProgress()) {
                    paintAt(event.getX(), event.getY());
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (painting) {
                    model.endStroke();
                    painting = false;
                }
                performClick();
                return true;

            default:
                return true;
        }
    }

    private static float midpointX(MotionEvent e) {
        return (e.getX(0) + e.getX(1)) * 0.5f;
    }

    private static float midpointY(MotionEvent e) {
        return (e.getY(0) + e.getY(1)) * 0.5f;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
