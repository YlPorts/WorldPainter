package com.ylports.worldpainter;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

public final class MainActivity extends Activity {
    private WorldModel model;
    private WorldEditorView editor;
    private TextView status;
    private TextView brushLabel;
    private EditText worldName;
    private File projectFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectFile = new File(getFilesDir(), "current-world.wpba");
        model = WorldModel.loadOrNew(projectFile);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(24, 26, 28));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), dp(6), dp(10), dp(6));

        worldName = new EditText(this);
        worldName.setSingleLine(true);
        worldName.setText("WorldPainter Bedrock");
        worldName.setHint("Nombre del mundo");
        worldName.setTextColor(Color.WHITE);
        worldName.setHintTextColor(Color.LTGRAY);
        header.addView(worldName, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button save = button("Guardar");
        save.setOnClickListener(v -> saveProject(true));
        header.addView(save);

        Button newWorld = button("Nuevo");
        newWorld.setOnClickListener(v -> {
            model.reset();
            editor.markWorldDirty();
            editor.resetView();
            setStatus("Mundo nuevo 128×128");
        });
        header.addView(newWorld);

        Button export = button("Exportar .mcworld");
        export.setOnClickListener(v -> exportWorld());
        header.addView(export);

        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        editor = new WorldEditorView(this, model);
        root.addView(editor, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(6), dp(4), dp(6), dp(6));
        bottom.setBackgroundColor(Color.rgb(36, 38, 41));

        HorizontalScrollView toolsScroll = new HorizontalScrollView(this);
        toolsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);

        addTool(tools, "Subir", WorldModel.MODE_RAISE);
        addTool(tools, "Bajar", WorldModel.MODE_LOWER);
        addTool(tools, "Suavizar", WorldModel.MODE_SMOOTH);
        addTool(tools, "Césped", WorldModel.MODE_GRASS);
        addTool(tools, "Arena", WorldModel.MODE_SAND);
        addTool(tools, "Piedra", WorldModel.MODE_STONE);
        addTool(tools, "Agua", WorldModel.MODE_WATER);

        Button undo = button("↶ Deshacer");
        undo.setOnClickListener(v -> {
            if (model.undo()) {
                editor.markWorldDirty();
                setStatus("Deshecho");
            }
        });
        tools.addView(undo);

        Button redo = button("↷ Rehacer");
        redo.setOnClickListener(v -> {
            if (model.redo()) {
                editor.markWorldDirty();
                setStatus("Rehecho");
            }
        });
        tools.addView(redo);

        Button resetView = button("Centrar vista");
        resetView.setOnClickListener(v -> editor.resetView());
        tools.addView(resetView);

        toolsScroll.addView(tools);
        bottom.addView(toolsScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        brushLabel = new TextView(this);
        brushLabel.setTextColor(Color.WHITE);
        brushLabel.setText(String.format(Locale.ROOT, "Pincel: %d", editor.getBrushRadius()));
        controls.addView(brushLabel, new LinearLayout.LayoutParams(dp(105), LinearLayout.LayoutParams.WRAP_CONTENT));

        SeekBar radius = new SeekBar(this);
        radius.setMax(31);
        radius.setProgress(editor.getBrushRadius() - 1);
        radius.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                editor.setBrushRadius(progress + 1);
                brushLabel.setText(String.format(Locale.ROOT, "Pincel: %d", progress + 1));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        controls.addView(radius, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        status = new TextView(this);
        status.setTextColor(Color.LTGRAY);
        status.setText("Listo — 1 dedo pinta, 2 dedos mueven/zoom");
        status.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        controls.addView(status, new LinearLayout.LayoutParams(dp(330), LinearLayout.LayoutParams.WRAP_CONTENT));

        bottom.addView(controls);
        root.addView(bottom, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private void addTool(LinearLayout tools, String label, int mode) {
        Button button = button(label);
        button.setOnClickListener(v -> {
            editor.setMode(mode);
            setStatus("Herramienta: " + label);
        });
        tools.addView(button);
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinHeight(dp(42));
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setStatus(String text) {
        if (status != null) {
            status.setText(text);
        }
    }

    private void saveProject(boolean showToast) {
        try {
            model.save(projectFile);
            if (showToast) {
                Toast.makeText(this, "Proyecto guardado", Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            Toast.makeText(this, "Error al guardar: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void exportWorld() {
        saveProject(false);
        if (!NativeBedrock.isLoaded()) {
            Toast.makeText(this, "El exportador nativo Bedrock no está cargado", Toast.LENGTH_LONG).show();
            return;
        }

        final String name = sanitizeName(worldName.getText().toString());
        setStatus("Exportando chunks Bedrock…");

        new Thread(() -> {
            File work = new File(getCacheDir(), "bedrock-export");
            McworldExporter.deleteRecursively(work);
            if (!work.mkdirs() && !work.isDirectory()) {
                runOnUiThread(() -> showExportError("No se pudo crear la carpeta temporal"));
                return;
            }

            String error = NativeBedrock.exportWorld(
                    work.getAbsolutePath(),
                    name,
                    model.width,
                    model.depth,
                    model.seaLevel,
                    model.heights,
                    model.materials,
                    model.water);

            if (error != null && !error.isEmpty()) {
                runOnUiThread(() -> showExportError(error));
                return;
            }

            File documents = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (documents == null) {
                documents = getExternalFilesDir(null);
            }
            if (documents == null) {
                runOnUiThread(() -> showExportError("Android no proporcionó una carpeta de exportación"));
                return;
            }

            File output = new File(documents, name + ".mcworld");
            try {
                McworldExporter.zipWorld(work, output);
            } catch (IOException e) {
                runOnUiThread(() -> showExportError(e.getMessage()));
                return;
            }

            File finalOutput = output;
            runOnUiThread(() -> {
                setStatus("Exportado: " + finalOutput.getName());
                Toast.makeText(this, "Mundo Bedrock creado", Toast.LENGTH_SHORT).show();
                openInMinecraft(finalOutput);
            });
        }, "bedrock-export").start();
    }

    private void showExportError(String error) {
        setStatus("Error de exportación");
        Toast.makeText(this, "No se pudo exportar: " + error, Toast.LENGTH_LONG).show();
    }

    private void openInMinecraft(File file) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/octet-stream")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            Intent minecraft = new Intent(intent);
            minecraft.setPackage("com.mojang.minecraftpe");
            startActivity(minecraft);
        } catch (ActivityNotFoundException notInstalled) {
            try {
                startActivity(Intent.createChooser(intent, "Abrir mundo con…"));
            } catch (ActivityNotFoundException noHandler) {
                Toast.makeText(this, "El .mcworld quedó guardado en Documentos de la app", Toast.LENGTH_LONG).show();
            }
        }
    }

    private static String sanitizeName(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            value = "WorldPainter Bedrock";
        }
        value = value.replaceAll("[\\\\/:*?\"<>|]", "_");
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveProject(false);
    }
}
