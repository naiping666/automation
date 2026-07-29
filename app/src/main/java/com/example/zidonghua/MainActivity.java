package com.example.zidonghua;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        CardView cardAccessibility = findViewById(R.id.card_accessibility);
        CardView cardRoot = findViewById(R.id.card_root);
        CardView cardRunScript = findViewById(R.id.card_run_script);

        // 点击"无障碍模式"卡片 → 跳转到无障碍模式界面
        cardAccessibility.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, AccessibilityModeActivity.class);
            startActivity(intent);
        });

        // 点击"Root 模式"卡片 → 跳转到 Root 模式界面
        cardRoot.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, RootModeActivity.class);
            startActivity(intent);
        });

        // 点击"运行脚本"卡片 → 弹出脚本选择列表
        cardRunScript.setOnClickListener(v -> showRunScriptDialog());
    }

    // ========== 显示可运行的脚本列表 ==========
    private void showRunScriptDialog() {
        ScriptManager sm = new ScriptManager(this);
        List<String> names = sm.getScriptNames();

        if (names.isEmpty()) {
            Toast.makeText(this, "没有已保存的脚本", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("选择要运行的脚本")
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    String selected = names.get(which);
                    try {
                        List<ScriptStep> steps = sm.loadScript(selected);
                        if (steps.isEmpty()) {
                            Toast.makeText(this, "脚本为空", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        // 检查是否需要无障碍服务
                        boolean needAccess = false;
                        for (ScriptStep step : steps) {
                            if (step.type == ScriptStep.TYPE_CLICK ||
                                    step.type == ScriptStep.TYPE_SWIPE ||
                                    step.type == ScriptStep.TYPE_BACK ||
                                    step.type == ScriptStep.TYPE_HOME ||
                                    step.type == ScriptStep.TYPE_TEXT ||
                                    step.type == ScriptStep.TYPE_LONG_CLICK ||
                                    step.type == ScriptStep.TYPE_IMAGE_CLICK) {
                                needAccess = true;
                                break;
                            }
                        }

                        if (needAccess && !MyAccessibilityService.isServiceRunning()) {
                            Toast.makeText(this, "需要无障碍服务，请前往开启", Toast.LENGTH_LONG).show();
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                            return;
                        }

                        // 执行脚本
                        Toast.makeText(this, "开始执行: " + selected, Toast.LENGTH_SHORT).show();
                        ScriptExecutor.executeScript(this, steps, new ScriptExecutor.ExecutionCallback() {
                            @Override
                            public void onStart() {
                                runOnUiThread(() -> Toast.makeText(MainActivity.this, "脚本开始执行", Toast.LENGTH_SHORT).show());
                            }

                            @Override
                            public void onProgress(int stepIndex, int totalSteps) {
                                // 可选：显示进度
                            }

                            @Override
                            public void onComplete() {
                                runOnUiThread(() -> Toast.makeText(MainActivity.this, "脚本执行完成", Toast.LENGTH_SHORT).show());
                            }

                            @Override
                            public void onError(String message) {
                                runOnUiThread(() -> Toast.makeText(MainActivity.this, "执行失败: " + message, Toast.LENGTH_SHORT).show());
                            }

                            // ✅ 新增：截图权限失效，需要重新授权
                            @Override
                            public void onNeedReauthorize() {
                                runOnUiThread(() -> {
                                    Toast.makeText(MainActivity.this, "截图权限已失效，请前往主界面重新授权", Toast.LENGTH_LONG).show();
                                    // 跳转到 ScriptEditorActivity 重新授权
                                    Intent intent = new Intent(MainActivity.this, ScriptEditorActivity.class);
                                    startActivity(intent);
                                });
                            }
                        });
                    } catch (Exception e) {
                        Toast.makeText(this, "加载脚本失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}