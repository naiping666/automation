package com.example.zidonghua;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.util.List;

public class AccessibilityModeActivity extends AppCompatActivity {

    private TextView tvServiceStatus;
    private EditText etClickInterval;
    private Button btnStart, btnStop;
    private boolean isRunning = false;

    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable clickRunnable;

    // ========== 请求码 ==========
    private static final int REQUEST_IMPORT_SCRIPT = 1000;
    private static final int REQUEST_EXPORT_SCRIPT = 1001;

    // ========== 导出临时变量 ==========
    private String exportJsonData;
    private String exportScriptName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_accessibility_mode);

        tvServiceStatus = findViewById(R.id.tv_service_status);
        etClickInterval = findViewById(R.id.et_click_interval);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);

        // ========== 脚本管理按钮 ==========
        Button btnNewScript = findViewById(R.id.btn_new_script);
        btnNewScript.setOnClickListener(v -> {
            Intent intent = new Intent(AccessibilityModeActivity.this, ScriptEditorActivity.class);
            startActivity(intent);
        });

        Button btnOpenScript = findViewById(R.id.btn_open_script);
        btnOpenScript.setOnClickListener(v -> {
            ScriptManager sm = new ScriptManager(this);
            List<String> names = sm.getScriptNames();
            if (names.isEmpty()) {
                Toast.makeText(this, "没有已保存的脚本", Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("打开脚本")
                    .setItems(names.toArray(new String[0]), (dialog, which) -> {
                        String selected = names.get(which);
                        Intent intent = new Intent(AccessibilityModeActivity.this, ScriptEditorActivity.class);
                        intent.putExtra("script_name", selected);
                        startActivity(intent);
                    })
                    .show();
        });

        // ========== 导入脚本按钮 ==========
        Button btnImport = findViewById(R.id.btn_import_script);
        btnImport.setOnClickListener(v -> importScript());

        // ========== 导出脚本按钮 ==========
        Button btnExport = findViewById(R.id.btn_export_script);
        btnExport.setOnClickListener(v -> exportScript());

        // ========== 其他逻辑（原有） ==========
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        checkAccessibilityStatus();

        findViewById(R.id.btn_open_settings).setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });

        btnStart.setOnClickListener(v -> {
            // ✅ 使用更严格的服务运行检查（MyAccessibilityService.isServiceRunning）
            if (!MyAccessibilityService.isServiceRunning()) {
                Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show();
                return;
            }
            if (isRunning) {
                Toast.makeText(this, "已在运行中", Toast.LENGTH_SHORT).show();
                return;
            }

            int intervalTemp;
            try {
                intervalTemp = Integer.parseInt(etClickInterval.getText().toString());
            } catch (NumberFormatException e) {
                intervalTemp = 500;
            }
            final int interval = intervalTemp;

            isRunning = true;
            Toast.makeText(this, "自动化已启动", Toast.LENGTH_SHORT).show();

            clickRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!isRunning) return;

                    int centerX = getResources().getDisplayMetrics().widthPixels / 2;
                    int centerY = getResources().getDisplayMetrics().heightPixels / 2;

                    // ✅ 捕获 performClick 可能抛出的异常
                    try {
                        MyAccessibilityService.performClick(centerX, centerY);
                    } catch (Exception e) {
                        // 如果无障碍服务未运行或点击失败，停止自动化并提示
                        isRunning = false;
                        handler.removeCallbacks(this);
                        Toast.makeText(AccessibilityModeActivity.this, "点击失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        return;
                    }

                    handler.postDelayed(this, interval);
                }
            };

            handler.postDelayed(clickRunnable, interval);
        });

        btnStop.setOnClickListener(v -> {
            if (!isRunning) {
                Toast.makeText(this, "没有正在运行的自动化", Toast.LENGTH_SHORT).show();
                return;
            }
            isRunning = false;
            handler.removeCallbacks(clickRunnable);
            Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show();
        });
    }

    // ========== 导入脚本 ==========
    private void importScript() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimeTypes = {"application/json", "text/plain"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        startActivityForResult(intent, REQUEST_IMPORT_SCRIPT);
    }

    // ========== 导出脚本（保存为 JSON 文件） ==========
    private void exportScript() {
        ScriptManager sm = new ScriptManager(this);
        List<String> names = sm.getScriptNames();
        if (names.isEmpty()) {
            Toast.makeText(this, "没有可导出的脚本", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择要导出的脚本")
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    String selected = names.get(which);
                    try {
                        List<ScriptStep> steps = sm.loadScript(selected);
                        Gson gson = new Gson();
                        String json = gson.toJson(steps);
                        // 保存到临时变量
                        exportJsonData = json;
                        exportScriptName = selected;

                        // 使用 ACTION_CREATE_DOCUMENT 让用户选择保存位置
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("application/json");
                        intent.putExtra(Intent.EXTRA_TITLE, selected + ".json");
                        startActivityForResult(intent, REQUEST_EXPORT_SCRIPT);
                    } catch (IOException e) {
                        Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    // ========== 处理导入/导出结果 ==========
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQUEST_IMPORT_SCRIPT) {
            // ====== 导入处理 ======
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    InputStream inputStream = getContentResolver().openInputStream(uri);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    inputStream.close();
                    String json = sb.toString();
                    Gson gson = new Gson();
                    Type type = new TypeToken<List<ScriptStep>>(){}.getType();
                    List<ScriptStep> importedSteps = gson.fromJson(json, type);
                    if (importedSteps == null || importedSteps.isEmpty()) {
                        Toast.makeText(this, "无效的脚本数据", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // 弹窗输入脚本名称
                    AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle("导入脚本");
                    final EditText input = new EditText(this);
                    input.setHint("请输入脚本名称");
                    builder.setView(input);
                    builder.setPositiveButton("导入", (dialog, which) -> {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty()) {
                            Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        ScriptManager sm = new ScriptManager(this);
                        if (sm.scriptExists(name)) {
                            new AlertDialog.Builder(this)
                                    .setTitle("脚本已存在")
                                    .setMessage("是否覆盖同名脚本？")
                                    .setPositiveButton("覆盖", (d, w) -> {
                                        saveImportedScript(name, importedSteps);
                                    })
                                    .setNegativeButton("取消", null)
                                    .show();
                        } else {
                            saveImportedScript(name, importedSteps);
                        }
                    });
                    builder.setNegativeButton("取消", null);
                    builder.show();
                } catch (IOException e) {
                    Toast.makeText(this, "读取文件失败", Toast.LENGTH_SHORT).show();
                }
            }
        } else if (requestCode == REQUEST_EXPORT_SCRIPT) {
            // ====== 导出处理 ======
            Uri uri = data.getData();
            if (uri != null && exportJsonData != null) {
                try {
                    OutputStream out = getContentResolver().openOutputStream(uri);
                    out.write(exportJsonData.getBytes());
                    out.close();
                    Toast.makeText(this, "导出成功: " + exportScriptName + ".json", Toast.LENGTH_SHORT).show();
                    exportJsonData = null;
                    exportScriptName = null;
                } catch (IOException e) {
                    Toast.makeText(this, "写入文件失败", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void saveImportedScript(String name, List<ScriptStep> steps) {
        try {
            ScriptManager sm = new ScriptManager(this);
            sm.saveScript(name, steps);
            Toast.makeText(this, "导入成功: " + name, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAccessibilityStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (handler != null) {
            handler.removeCallbacks(clickRunnable);
        }
    }

    private void checkAccessibilityStatus() {
        // ✅ 使用 MyAccessibilityService.isServiceRunning() 更准确
        if (MyAccessibilityService.isServiceRunning()) {
            tvServiceStatus.setText("已开启 ✅");
            tvServiceStatus.setTextColor(getColorCompat(android.R.color.holo_green_dark));
        } else {
            tvServiceStatus.setText("未开启 ❌");
            tvServiceStatus.setTextColor(getColorCompat(android.R.color.holo_red_dark));
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        String service = getPackageName() + "/" + MyAccessibilityService.class.getCanonicalName();
        try {
            String enabledServices = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            );
            return enabledServices != null && enabledServices.contains(service);
        } catch (Exception e) {
            return false;
        }
    }

    private int getColorCompat(int colorId) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            return getColor(colorId);
        } else {
            return getResources().getColor(colorId);
        }
    }
}