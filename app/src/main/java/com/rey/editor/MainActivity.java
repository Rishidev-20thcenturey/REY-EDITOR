package com.rey.editor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private StudioView studio;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        studio = new StudioView(this);
        setContentView(studio);
        studio.restoreAutosave();
    }

    private void importPng() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, 10);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 10 || result != RESULT_OK || data == null) return;
        try {
            Bitmap image = BitmapFactory.decodeStream(getContentResolver().openInputStream(data.getData()));
            if (image == null) throw new IOException("Unsupported image");
            studio.replace(image.copy(Bitmap.Config.ARGB_8888, true));
        } catch (Exception error) {
            message("Could not import that image");
        }
    }

    private void message(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }

    private void newDocumentDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setPadding(32, 8, 32, 0);
        form.setOrientation(LinearLayout.VERTICAL);
        EditText width = field("Width (px)", "1200");
        EditText height = field("Height (px)", "800");
        form.addView(width); form.addView(height);
        new AlertDialog.Builder(this).setTitle("New document").setView(form)
            .setNegativeButton("Cancel", null).setPositiveButton("Create", (dialog, which) -> {
                try {
                    int w = Integer.parseInt(width.getText().toString());
                    int h = Integer.parseInt(height.getText().toString());
                    if (w < 1 || h < 1 || w > 12000 || h > 12000) throw new NumberFormatException();
                    studio.newDocument(w, h);
                } catch (NumberFormatException error) { message("Use dimensions from 1 to 12000 pixels"); }
            }).show();
    }

    private EditText field(String hint, String value) {
        EditText field = new EditText(this);
        field.setHint(hint); field.setText(value); field.setInputType(2);
        return field;
    }

    private void exportPng() {
        try {
            File file = new File(getExternalFilesDir(null), "rey-editor.png");
            FileOutputStream output = new FileOutputStream(file);
            studio.composite().compress(Bitmap.CompressFormat.PNG, 100, output);
            output.close(); message("Exported to app storage: " + file.getName());
        } catch (Exception error) { message("Export failed"); }
    }

    private void opacityDialog() {
        SeekBar slider = new SeekBar(this);
        slider.setMax(100);
        slider.setProgress(Math.round(studio.layerOpacity() * 100));
        LinearLayout box = new LinearLayout(this);
        box.setPadding(32, 8, 32, 0);
        box.addView(slider, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("Layer opacity").setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply", (dialog, which) -> studio.setLayerOpacity(slider.getProgress() / 100f))
            .show();
    }

    private static final class Layer {
        String name; Bitmap bitmap; boolean visible = true; float opacity = 1f;
        Layer(String name, Bitmap bitmap) { this.name = name; this.bitmap = bitmap; }
    }

    private final class StudioView extends View {
        private static final int TOOL_BRUSH = 0, TOOL_ERASER = 1, TOOL_EYEDROPPER = 2, TOOL_HAND = 3;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArrayList<Layer> layers = new ArrayList<>();
        private final ArrayDeque<Snapshot> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
        private final int dark = Color.rgb(11, 14, 21), panel = Color.rgb(23, 28, 39), tile = Color.rgb(38, 45, 60), blue = Color.rgb(111, 145, 255);
        private Bitmap base; private int selected; private int tool = TOOL_BRUSH;
        private float zoom = 1f, panX, panY, lastX, lastY; private boolean drawing, panning;
        private float brushSize = 24f; private int foreground = Color.BLACK;
        private long lastSave;

        private final class Snapshot {
            final ArrayList<Bitmap> bitmaps = new ArrayList<>(); final ArrayList<String> names = new ArrayList<>();
            final ArrayList<Boolean> visible = new ArrayList<>(); final ArrayList<Float> opacity = new ArrayList<>(); int selected;
            Snapshot() { for (Layer layer : layers) { bitmaps.add(layer.bitmap.copy(Bitmap.Config.ARGB_8888, true)); names.add(layer.name); visible.add(layer.visible); opacity.add(layer.opacity); } selected = StudioView.this.selected; }
            void restore() { layers.clear(); for (int i = 0; i < bitmaps.size(); i++) { Layer l = new Layer(names.get(i), bitmaps.get(i)); l.visible = visible.get(i); l.opacity = opacity.get(i); layers.add(l); } StudioView.this.selected = Math.min(selected, layers.size() - 1); invalidate(); }
        }

        StudioView(Context context) { super(context); setFocusable(true); stroke.setStrokeCap(Paint.Cap.ROUND); stroke.setStrokeJoin(Paint.Join.ROUND); newDocument(1200, 800); }

        void newDocument(int width, int height) {
            base = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); base.eraseColor(Color.WHITE);
            layers.clear(); layers.add(new Layer("Background", base)); selected = 0; undo.clear(); redo.clear(); zoom = 1f; panX = panY = 0; autosave(); invalidate();
        }

        void replace(Bitmap image) { newDocument(image.getWidth(), image.getHeight()); layers.set(0, new Layer("Imported image", image)); base = image; autosave(); invalidate(); }

        Bitmap composite() {
            Bitmap result = Bitmap.createBitmap(base.getWidth(), base.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result); canvas.drawColor(Color.TRANSPARENT);
            for (Layer layer : layers) if (layer.visible) { paint.setAlpha(Math.round(layer.opacity * 255)); canvas.drawBitmap(layer.bitmap, 0, 0, paint); }
            paint.setAlpha(255); return result;
        }

        private void checkpoint() { undo.push(new Snapshot()); if (undo.size() > 30) undo.removeLast(); redo.clear(); }
        private void undo() { if (undo.isEmpty()) return; redo.push(new Snapshot()); undo.pop().restore(); autosave(); }
        private void redo() { if (redo.isEmpty()) return; undo.push(new Snapshot()); redo.pop().restore(); autosave(); }

        float layerOpacity() { return layers.get(selected).opacity; }
        void setLayerOpacity(float value) { checkpoint(); layers.get(selected).opacity = Math.max(0f, Math.min(1f, value)); autosave(); invalidate(); }
        private void addLayer() { checkpoint(); Bitmap b = Bitmap.createBitmap(base.getWidth(), base.getHeight(), Bitmap.Config.ARGB_8888); layers.add(new Layer("Layer " + (layers.size() + 1), b)); selected = layers.size() - 1; autosave(); invalidate(); }
        private void duplicateLayer() { if (layers.isEmpty()) return; checkpoint(); Layer source = layers.get(selected); layers.add(selected + 1, new Layer(source.name + " copy", source.bitmap.copy(Bitmap.Config.ARGB_8888, true))); selected++; autosave(); invalidate(); }
        private void deleteLayer() { if (layers.size() <= 1) { message("A document needs one layer"); return; } checkpoint(); layers.remove(selected); selected = Math.min(selected, layers.size() - 1); autosave(); invalidate(); }
        private void moveLayer(int direction) { int target = selected + direction; if (target < 0 || target >= layers.size()) return; checkpoint(); Layer moving = layers.remove(selected); layers.add(target, moving); selected = target; autosave(); invalidate(); }
        private void toggleVisibility() { checkpoint(); layers.get(selected).visible = !layers.get(selected).visible; autosave(); invalidate(); }
        private void renameLayer() {
            EditText input = field("Layer name", layers.get(selected).name);
            new AlertDialog.Builder(MainActivity.this).setTitle("Rename layer").setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> { String name = input.getText().toString().trim(); if (!name.isEmpty()) { checkpoint(); layers.get(selected).name = name; autosave(); invalidate(); } }).show();
        }

        private RectF canvasRect() { return new RectF(72, 64, getWidth() - 270, getHeight() - 24); }
        private float scale() { RectF r = canvasRect(); return Math.min((r.width() - 30) / base.getWidth(), (r.height() - 30) / base.getHeight()) * zoom; }
        private float imageLeft() { RectF r = canvasRect(); return r.left + (r.width() - base.getWidth() * scale()) / 2 + panX; }
        private float imageTop() { RectF r = canvasRect(); return r.top + (r.height() - base.getHeight() * scale()) / 2 + panY; }
        private float imageX(float screenX) { return (screenX - imageLeft()) / scale(); }
        private float imageY(float screenY) { return (screenY - imageTop()) / scale(); }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(dark); RectF r = canvasRect(); paint.setColor(panel); canvas.drawRect(0, 0, getWidth(), 56, paint); canvas.drawRect(0, 56, 64, getHeight(), paint); canvas.drawRect(getWidth() - 270, 56, getWidth(), getHeight(), paint);
            paint.setColor(Color.rgb(44, 51, 67)); canvas.drawRect(r, paint);
            float s = scale(); canvas.save(); canvas.translate(imageLeft(), imageTop()); canvas.scale(s, s); canvas.drawBitmap(composite(), 0, 0, paint); canvas.restore();
            text(canvas, "REY EDITOR", 16, 28, Color.WHITE, 18); button(canvas, "New", 106, 28, 56); button(canvas, "Import", 168, 28, 68); button(canvas, "Export", 242, 28, 70); button(canvas, "Undo", 316, 28, 60); button(canvas, "Redo", 380, 28, 60);
            text(canvas, "TOOLS", 17, 82, Color.LTGRAY, 10); tool(canvas, "B", 16, 110, TOOL_BRUSH); tool(canvas, "E", 16, 158, TOOL_ERASER); tool(canvas, "I", 16, 206, TOOL_EYEDROPPER); tool(canvas, "H", 16, 254, TOOL_HAND);
            text(canvas, "LAYERS", getWidth() - 250, 84, Color.LTGRAY, 11); button(canvas, "+", getWidth() - 60, 82, 36);
            button(canvas, "Dup", getWidth() - 250, 112, 48); button(canvas, "Del", getWidth() - 198, 112, 48); button(canvas, "Up", getWidth() - 144, 112, 48); button(canvas, "Down", getWidth() - 90, 112, 54); button(canvas, "Opacity", getWidth() - 250, 145, 78);
            for (int i = layers.size() - 1; i >= 0; i--) { Layer layer = layers.get(i); float y = 166 + (layers.size() - 1 - i) * 52; paint.setColor(i == selected ? Color.rgb(58, 74, 108) : Color.rgb(31, 37, 50)); canvas.drawRoundRect(getWidth() - 260, y - 22, getWidth() - 10, y + 21, 8, 8, paint); text(canvas, layer.visible ? "●" : "○", getWidth() - 248, y + 5, Color.WHITE, 15); text(canvas, layer.name, getWidth() - 222, y + 1, Color.WHITE, 12); text(canvas, Math.round(layer.opacity * 100) + "%", getWidth() - 222, y + 17, Color.LTGRAY, 10); }
            text(canvas, "Tool: " + toolName(), 78, getHeight() - 8, Color.LTGRAY, 11); text(canvas, Math.round(zoom * 100) + "%", getWidth() - 90, getHeight() - 8, Color.LTGRAY, 11);
        }

        private String toolName() { return tool == TOOL_ERASER ? "Eraser" : tool == TOOL_EYEDROPPER ? "Eyedropper" : tool == TOOL_HAND ? "Hand / Pan" : "Brush"; }
        private void tool(Canvas c, String label, float x, float y, int id) { paint.setColor(tool == id ? blue : tile); c.drawRoundRect(x, y - 20, x + 34, y + 20, 8, 8, paint); text(c, label, x + 11, y + 6, Color.WHITE, 15); }
        private void text(Canvas c, String value, float x, float y, int color, float size) { paint.setColor(color); paint.setTextSize(size); paint.setTypeface(Typeface.DEFAULT); c.drawText(value, x, y, paint); }
        private void button(Canvas c, String label, float x, float y, float width) { paint.setColor(tile); c.drawRoundRect(x, y - 19, x + width, y + 16, 7, 7, paint); text(c, label, x + 9, y + 4, Color.WHITE, 11); }

        @Override public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX(), y = event.getY();
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                lastX = x; lastY = y;
                if (y < 56) { if (x > 100 && x < 162) newDocumentDialog(); else if (x < 236 && x > 162) importPng(); else if (x < 316 && x > 236) exportPng(); else if (x < 376 && x > 316) undo(); else if (x < 445 && x > 376) redo(); return true; }
                if (x < 64 && y > 80) { int id = Math.max(0, Math.min(3, (int)((y - 90) / 48))); tool = id; invalidate(); return true; }
                if (x > getWidth() - 270) { if (y < 108 && x > getWidth() - 80) addLayer(); else if (y >= 140 && y < 176 && x < getWidth() - 170) { opacityDialog(); } else if (y > 108 && y < 140) { if (x > getWidth() - 252 && x < getWidth() - 198) duplicateLayer(); else if (x < getWidth() - 144) deleteLayer(); else if (x < getWidth() - 85) moveLayer(1); else moveLayer(-1); } else if (y > 176) { int row = (int)((y - 145) / 52); int index = layers.size() - 1 - row; if (index >= 0 && index < layers.size()) { if (x < getWidth() - 220) { selected = index; invalidate(); } else { selected = index; toggleVisibility(); } } } return true; }
                if (tool == TOOL_HAND) { panning = true; return true; }
                if (!canvasRect().contains(x, y)) return true;
                if (tool == TOOL_EYEDROPPER) { int ix = Math.round(imageX(x)), iy = Math.round(imageY(y)); if (ix >= 0 && iy >= 0 && ix < base.getWidth() && iy < base.getHeight()) foreground = composite().getPixel(ix, iy); message("Color sampled"); return true; }
                checkpoint(); drawing = true; drawAt(x, y, x, y); return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) { if (panning) { panX += x - lastX; panY += y - lastY; lastX = x; lastY = y; invalidate(); return true; } if (drawing) { drawAt(lastX, lastY, x, y); lastX = x; lastY = y; invalidate(); return true; } }
            if (event.getAction() == MotionEvent.ACTION_UP) { if (drawing) { drawing = false; autosave(); } panning = false; return true; }
            return true;
        }

        private void drawAt(float sx, float sy, float ex, float ey) { Layer layer = layers.get(selected); Canvas c = new Canvas(layer.bitmap); stroke.setColor(tool == TOOL_ERASER ? Color.TRANSPARENT : foreground); stroke.setStrokeWidth(brushSize / scale()); stroke.setXfermode(tool == TOOL_ERASER ? new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) : null); c.drawLine(imageX(sx), imageY(sy), imageX(ex), imageY(ey), stroke); stroke.setXfermode(null); }

        private File autosaveDir() { return new File(getFilesDir(), "autosave"); }
        private void autosave() { if (System.currentTimeMillis() - lastSave < 250) return; lastSave = System.currentTimeMillis(); File dir = autosaveDir(); if (!dir.exists() && !dir.mkdirs()) return; try { for (int i = 0; i < layers.size(); i++) { FileOutputStream out = new FileOutputStream(new File(dir, "layer-" + i + ".png")); layers.get(i).bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.close(); } getPreferences(MODE_PRIVATE).edit().putInt("count", layers.size()).putInt("selected", selected).putInt("width", base.getWidth()).putInt("height", base.getHeight()).apply(); } catch (IOException ignored) { } }
        void restoreAutosave() { int count = getPreferences(MODE_PRIVATE).getInt("count", 0); if (count < 1) return; ArrayList<Layer> restored = new ArrayList<>(); try { for (int i = 0; i < count; i++) { Bitmap b = BitmapFactory.decodeFile(new File(autosaveDir(), "layer-" + i + ".png").getPath()); if (b == null) return; restored.add(new Layer(i == 0 ? "Background" : "Layer " + (i + 1), b)); } layers.clear(); layers.addAll(restored); base = restored.get(0).bitmap; selected = Math.min(getPreferences(MODE_PRIVATE).getInt("selected", 0), layers.size() - 1); invalidate(); } catch (Exception ignored) { } }
    }
}
