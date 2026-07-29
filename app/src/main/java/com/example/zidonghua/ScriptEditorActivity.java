package com.example.zidonghua;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.util.Log;
import com.google.gson.Gson;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.io.File;
import android.graphics.drawable.Drawable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class ScriptEditorActivity extends AppCompatActivity {

    private RecyclerView rvSteps;
    private ScriptStepAdapter adapter;
    private List<ScriptStep> steps = new ArrayList<>();
    private boolean isRunning = false;
    private Handler handler = new Handler(Looper.getMainLooper());

    // ========== 脚本管理相关 ==========
    private ScriptManager scriptManager;
    private String scriptName = null;
    private boolean isModified = false;

    // ========== 用于坐标捕获的 EditText 引用 ==========
    private EditText capturedClickX, capturedClickY;
    private EditText capturedSwipeX1, capturedSwipeY1, capturedSwipeX2, capturedSwipeY2;

    // ========== 请求码常量 ==========
    private static final int REQUEST_CAPTURE_CLICK = 1001;
    private static final int REQUEST_CAPTURE_LONG_CLICK = 1002;
    private static final int REQUEST_PICK_IMAGE = 1003;
    private static final int REQUEST_SCREEN_CAPTURE = 1004;
    private int currentStepIndex = 0; // 记录当前执行的步骤，用于断点续传
    // ========== 图像识别临时变量 ==========
    private String selectedImagePath;

    // ========== 截图权限相关 ==========
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;

    // ========== 广播接收器（接收悬浮窗坐标） ==========
    private BroadcastReceiver captureReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!"CAPTURE_RESULT".equals(intent.getAction())) return;

            boolean cancelled = intent.getBooleanExtra("cancelled", false);
            if (cancelled) {
                Toast.makeText(ScriptEditorActivity.this, "已取消捕获", Toast.LENGTH_SHORT).show();
                return;
            }

            int x = intent.getIntExtra("x", -1);
            int y = intent.getIntExtra("y", -1);
            int type = intent.getIntExtra("capture_type", 1);

            if (x < 0 || y < 0) {
                Toast.makeText(ScriptEditorActivity.this, "捕获失败，请重试", Toast.LENGTH_SHORT).show();
                return;
            }

            switch (type) {
                case 1:
                    if (capturedClickX != null && capturedClickY != null) {
                        capturedClickX.setText(String.valueOf(x));
                        capturedClickY.setText(String.valueOf(y));
                        Toast.makeText(ScriptEditorActivity.this, "已捕获点击坐标: (" + x + ", " + y + ")", Toast.LENGTH_SHORT).show();
                        markModified();
                    }
                    break;
                case 2:
                    if (capturedSwipeX1 != null && capturedSwipeY1 != null) {
                        capturedSwipeX1.setText(String.valueOf(x));
                        capturedSwipeY1.setText(String.valueOf(y));
                        Toast.makeText(ScriptEditorActivity.this, "已捕获起点: (" + x + ", " + y + ")", Toast.LENGTH_SHORT).show();
                        markModified();
                    }
                    break;
                case 3:
                    if (capturedSwipeX2 != null && capturedSwipeY2 != null) {
                        capturedSwipeX2.setText(String.valueOf(x));
                        capturedSwipeY2.setText(String.valueOf(y));
                        Toast.makeText(ScriptEditorActivity.this, "已捕获终点: (" + x + ", " + y + ")", Toast.LENGTH_SHORT).show();
                        markModified();
                    }
                    break;
                default:
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_script_editor);

        scriptManager = new ScriptManager(this);
        scriptName = getIntent().getStringExtra("script_name");

        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        IntentFilter filter = new IntentFilter("CAPTURE_RESULT");
        LocalBroadcastManager.getInstance(this).registerReceiver(captureReceiver, filter);

        rvSteps = findViewById(R.id.rv_steps);
        rvSteps.setLayoutManager(new LinearLayoutManager(this));

        // ========== 适配器初始化（包含 IF 监听器） ==========
        adapter = new ScriptStepAdapter(this, steps, new ScriptStepAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(int position) {
                ScriptStep step = steps.get(position);
                if (step.isConditionStep()) {
                    // IF 步骤点击由 onIfStepExpandToggle 处理
                } else {
                    Toast.makeText(ScriptEditorActivity.this, "长按拖动排序", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onItemDelete(int position) {
                steps.remove(position);
                adapter.notifyItemRemoved(position);
                markModified();
            }
        });

        // ✅ 设置 IF 步骤的监听器
        adapter.setIfStepListener(new ScriptStepAdapter.OnIfStepListener() {
            @Override
            public void onIfStepExpandToggle(int position) {
                ScriptStep step = steps.get(position);
                step.isExpanded = !step.isExpanded;
                adapter.notifyItemChanged(position);
            }

            @Override
            public void onAddSubStep(int position, boolean isThen) {
                showAddSubStepDialog(position, isThen);
            }

            @Override
            public void onEditIfStep(int position) {
                editIfStep(position);
            }

            @Override
            public void onSubStepClick(int parentPosition, int subPosition, boolean isThen) {
                Toast.makeText(ScriptEditorActivity.this, "长按可拖动排序", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onSubStepDelete(int parentPosition, int subPosition, boolean isThen) {
                ScriptStep parent = steps.get(parentPosition);
                List<ScriptStep> targetList = isThen ? parent.thenSteps : parent.elseSteps;
                if (targetList != null && subPosition < targetList.size()) {
                    targetList.remove(subPosition);
                    adapter.notifyItemChanged(parentPosition);
                    markModified();
                }
            }
        });

        rvSteps.setAdapter(adapter);

        // ========== 拖拽排序 ==========
        ItemTouchHelper.Callback callback = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                int from = viewHolder.getAdapterPosition();
                int to = target.getAdapterPosition();
                if (from < to) {
                    for (int i = from; i < to; i++) {
                        ScriptStep temp = steps.get(i);
                        steps.set(i, steps.get(i + 1));
                        steps.set(i + 1, temp);
                    }
                } else {
                    for (int i = from; i > to; i--) {
                        ScriptStep temp = steps.get(i);
                        steps.set(i, steps.get(i - 1));
                        steps.set(i - 1, temp);
                    }
                }
                adapter.notifyItemMoved(from, to);
                markModified();
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                // 不实现滑动删除
            }
        };
        ItemTouchHelper touchHelper = new ItemTouchHelper(callback);
        touchHelper.attachToRecyclerView(rvSteps);

        // ========== 按钮监听 ==========
        findViewById(R.id.btn_back).setOnClickListener(v -> handleBack());
        findViewById(R.id.btn_add_step).setOnClickListener(v -> showAddStepDialog());
        findViewById(R.id.btn_clear_all).setOnClickListener(v -> {
            if (steps.isEmpty()) {
                Toast.makeText(this, "已是空脚本", Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("确认清空")
                    .setMessage("确定要清空所有步骤吗？")
                    .setPositiveButton("清空", (dialog, which) -> {
                        steps.clear();
                        adapter.notifyDataSetChanged();
                        markModified();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        findViewById(R.id.btn_run).setOnClickListener(v -> runScript());

        findViewById(R.id.btn_save).setOnClickListener(v -> {
            if (scriptName == null || scriptName.isEmpty()) {
                showRenameDialog();
                return;
            }
            saveScript();
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_load).setOnClickListener(v -> showOpenScriptDialog());

        // ========== 加载脚本 ==========
        if (scriptName != null && !scriptName.isEmpty()) {
            try {
                steps.clear();
                steps.addAll(scriptManager.loadScript(scriptName));
                adapter.notifyDataSetChanged();
                setTitle("📜 " + scriptName);
                Toast.makeText(this, "已加载: " + scriptName, Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                Toast.makeText(this, "加载失败", Toast.LENGTH_SHORT).show();
            }
        } else {
            setTitle("脚本编辑器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                final int finalResultCode = resultCode;
                final Intent finalData = data;

                try {
                    Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
                    startService(serviceIntent);

                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            mediaProjection = projectionManager.getMediaProjection(finalResultCode, finalData);
                            if (mediaProjection != null) {
                                DisplayMetrics metrics = getResources().getDisplayMetrics();
                                ScreenCaptureHelper.init(mediaProjection, metrics.widthPixels, metrics.heightPixels);
                                Toast.makeText(this, "截图权限已获取", Toast.LENGTH_SHORT).show();
                                Log.d("ScriptEditor", "MediaProjection 重新初始化成功");

                                // ✅ 修改：重置运行状态，然后重新执行脚本
                                if (!steps.isEmpty()) {
                                    isRunning = false;          // 关键：重置运行状态
                                    currentStepIndex = 0;       // 重置断点，从头开始
                                    runScript();                // 重新执行 runScript，它会检查权限并执行脚本
                                } else {
                                    runScript();
                                }
                            } else {
                                Toast.makeText(this, "获取截图权限失败", Toast.LENGTH_SHORT).show();
                                Log.e("ScriptEditor", "MediaProjection 为 null");
                            }
                        } catch (Exception e) {
                            Log.e("ScriptEditor", "初始化截图失败", e);
                            Toast.makeText(this, "初始化截图失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }, 300);

                } catch (Exception e) {
                    Log.e("ScriptEditor", "启动服务失败", e);
                    Toast.makeText(this, "启动截图服务失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "用户取消了截图授权", Toast.LENGTH_SHORT).show();
                isRunning = false;
            }
            return;
        }

        // 处理图片选择
        if (requestCode == REQUEST_PICK_IMAGE) {
            if (resultCode == RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    String path = saveImageToInternalStorage(uri);
                    if (path != null) {
                        selectedImagePath = path;
                        Toast.makeText(this, "图片已选择", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "保存图片失败", Toast.LENGTH_SHORT).show();
                    }
                }
            }
            return;
        }

        // 处理坐标捕获
        if (resultCode != RESULT_OK || data == null) return;

        if (data.getBooleanExtra(CoordinateCaptureActivity.EXTRA_CANCELLED, false)) {
            Toast.makeText(this, "已取消捕获", Toast.LENGTH_SHORT).show();
            return;
        }

        int x = data.getIntExtra(CoordinateCaptureActivity.EXTRA_X, -1);
        int y = data.getIntExtra(CoordinateCaptureActivity.EXTRA_Y, -1);

        if (x < 0 || y < 0) {
            Toast.makeText(this, "捕获失败，请重试", Toast.LENGTH_SHORT).show();
            return;
        }

        if (requestCode == REQUEST_CAPTURE_CLICK) {
            if (capturedClickX != null && capturedClickY != null) {
                capturedClickX.setText(String.valueOf(x));
                capturedClickY.setText(String.valueOf(y));
                Toast.makeText(this, "已捕获点击坐标: (" + x + ", " + y + ")", Toast.LENGTH_SHORT).show();
                markModified();
            }
        } else if (requestCode == REQUEST_CAPTURE_LONG_CLICK) {
            if (capturedClickX != null && capturedClickY != null) {
                capturedClickX.setText(String.valueOf(x));
                capturedClickY.setText(String.valueOf(y));
                Toast.makeText(this, "已捕获长按坐标: (" + x + ", " + y + ")", Toast.LENGTH_SHORT).show();
                markModified();
            }
        }
    }

    // ========== 请求截图权限 ==========
    private void requestScreenCapturePermission() {
        if (projectionManager == null) {
            Toast.makeText(this, "系统不支持屏幕截图", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(intent, REQUEST_SCREEN_CAPTURE);
    }

    // ========== ✅ 等待截图服务初始化完成 ==========
    private void waitForScreenCaptureInit() {
        new Thread(() -> {
            int waitCount = 0;
            while (!ScreenCaptureHelper.isInitialized() && waitCount < 50) {
                try {
                    Thread.sleep(100);
                    waitCount++;
                } catch (InterruptedException e) {
                    break;
                }
            }
            runOnUiThread(() -> {
                if (ScreenCaptureHelper.isInitialized()) {
                    Log.d("ScriptEditor", "截图服务初始化成功");
                    runScript();
                } else {
                    Toast.makeText(this, "截图权限初始化超时，请重试", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    // ========== 执行脚本 ==========
    private void runScript() {
        if (isRunning) {
            Toast.makeText(this, "正在执行中，请勿重复运行", Toast.LENGTH_SHORT).show();
            return;
        }
        if (steps.isEmpty()) {
            Toast.makeText(this, "请先添加步骤", Toast.LENGTH_SHORT).show();
            return;
        }
        // 打印所有步骤
        for (int i = 0; i < steps.size(); i++) {
            Log.d("ScriptEditor", "步骤[" + i + "] = " + steps.get(i).getTypeName()
                    + (steps.get(i).type == ScriptStep.TYPE_LAUNCH_APP ? " pkg=" + steps.get(i).packageName : ""));
        }

        // ====== 递归检查是否需要无障碍服务 ======
        boolean needAccessibility = checkNeedAccessibility(steps);
        Log.d("ScriptEditor", "needAccessibility = " + needAccessibility);
        Log.d("ScriptEditor", "MyAccessibilityService.isServiceRunning() = " + MyAccessibilityService.isServiceRunning());

        if (needAccessibility && !MyAccessibilityService.isServiceRunning()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }

        // ====== 递归检查是否需要截图权限 ======
        boolean needScreenCapture = checkNeedScreenCapture(steps);

        if (needScreenCapture) {
            if (ScreenCaptureHelper.isInitialized()) {
                Log.d("ScriptEditor", "截图服务已就绪，直接执行脚本");
            } else {
                if (mediaProjection != null) {
                    // ... 尝试重新初始化
                }
                if (!ScreenCaptureHelper.isInitialized()) {
                    new AlertDialog.Builder(this)
                            .setTitle("需要截图权限")
                            .setMessage("文字识别功能需要获取屏幕截图权限，点击「确定」后系统会弹出授权窗口。")
                            .setPositiveButton("确定", (dialog, which) -> {
                                requestScreenCapturePermission();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                    return;
                }
            }
        }

        isRunning = true;
        Toast.makeText(this, "开始执行脚本...", Toast.LENGTH_SHORT).show();
        executeStep(0);
    }
    private boolean checkNeedScreenCapture(List<ScriptStep> steps) {
        for (ScriptStep step : steps) {
            // ✅ TYPE_IF 本身需要截图权限（条件判断需要 OCR）
            if (step.type == ScriptStep.TYPE_IF || step.type == ScriptStep.TYPE_IMAGE_CLICK) {
                return true;
            }
            if (step.type == ScriptStep.TYPE_IF) {
                // 递归检查子步骤
                if (checkNeedScreenCapture(step.thenSteps) || checkNeedScreenCapture(step.elseSteps)) {
                    return true;
                }
            }
        }
        return false;
    }
    // ========== 递归检查是否需要无障碍服务 ==========
    private boolean checkNeedAccessibility(List<ScriptStep> steps) {
        for (ScriptStep step : steps) {
            if (step.type == ScriptStep.TYPE_CLICK ||
                    step.type == ScriptStep.TYPE_SWIPE ||
                    step.type == ScriptStep.TYPE_BACK ||
                    step.type == ScriptStep.TYPE_HOME ||
                    step.type == ScriptStep.TYPE_TEXT ||
                    step.type == ScriptStep.TYPE_LONG_CLICK ||
                    step.type == ScriptStep.TYPE_IMAGE_CLICK) {
                return true;
            }
            if (step.type == ScriptStep.TYPE_IF) {
                // 递归检查 thenSteps 和 elseSteps
                if (checkNeedAccessibility(step.thenSteps) || checkNeedAccessibility(step.elseSteps)) {
                    return true;
                }
            }
        }
        return false;
    }
    // ========== 执行步骤 ==========

    private void executeStep(final int index) {
        if (!isRunning || index >= steps.size()) {
            isRunning = false;
            currentStepIndex = 0;
            runOnUiThread(() -> Toast.makeText(this, "脚本执行完成", Toast.LENGTH_SHORT).show());
            return;
        }

        final ScriptStep step = steps.get(index);
        new Thread(() -> {
            try {
                switch (step.type) {
                    case ScriptStep.TYPE_LAUNCH_APP:
                        Log.d("ScriptEditor", "▶️ 执行启动应用: " + step.packageName);
                        launchApp(step.packageName);
                        Log.d("ScriptEditor", "✅ 启动应用调用完成");
                        break;

                    case ScriptStep.TYPE_WAIT:
                        Log.d("ScriptEditor", "⏳ 执行等待: " + step.waitMs + "ms");
                        Thread.sleep(step.waitMs);
                        Log.d("ScriptEditor", "✅ 等待完成");
                        break;

                    case ScriptStep.TYPE_CLICK:
                        Log.d("ScriptEditor", "🖱️ 执行点击: (" + step.x + "," + step.y + ")");
                        MyAccessibilityService.performClick(step.x, step.y);
                        Thread.sleep(200);
                        break;

                    case ScriptStep.TYPE_SWIPE:
                        Log.d("ScriptEditor", "👆 执行滑动: (" + step.x1 + "," + step.y1 + ") -> (" + step.x2 + "," + step.y2 + ")");
                        MyAccessibilityService.performSwipe(step.x1, step.y1, step.x2, step.y2);
                        Thread.sleep(300);
                        break;

                    case ScriptStep.TYPE_TEXT:
                        Log.d("ScriptEditor", "⌨️ 执行文本输入: " + step.text);
                        try {
                            MyAccessibilityService.inputText(step.text);
                            Thread.sleep(200);
                            Log.d("ScriptEditor", "✅ 文本输入成功");
                        } catch (Exception e) {
                            Log.e("ScriptEditor", "❌ 文本输入失败", e);
                            runOnUiThread(() -> {
                                Toast.makeText(ScriptEditorActivity.this, "文本输入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                                isRunning = false;
                            });
                            return;
                        }
                        break;

                    case ScriptStep.TYPE_LONG_CLICK:
                        Log.d("ScriptEditor", "🖱️ 执行长按: (" + step.x + "," + step.y + ") 持续 " + step.longClickDuration + "ms");
                        MyAccessibilityService.performLongClick(step.x, step.y, step.longClickDuration);
                        Thread.sleep(200);
                        break;

                    case ScriptStep.TYPE_BACK:
                        Log.d("ScriptEditor", "◀️ 执行返回键");
                        MyAccessibilityService.doGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                        Thread.sleep(200);
                        break;

                    case ScriptStep.TYPE_HOME:
                        Log.d("ScriptEditor", "🏠 执行 Home 键");
                        MyAccessibilityService.doGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
                        Thread.sleep(200);
                        break;

                    case ScriptStep.TYPE_IMAGE_CLICK:
                        // 原有的截图识别逻辑（不变，但注意内部的异常处理已存在）
                        try {
                            if (!ScreenCaptureHelper.isInitialized()) {
                                throw new Exception("截图服务未初始化，请重新授权");
                            }
                            Bitmap screen = ScreenCaptureHelper.captureScreen();
                            if (screen == null) {
                                throw new Exception("截图失败，请重试");
                            }
                            String targetText = step.ocrText;
                            if (targetText == null || targetText.isEmpty()) {
                                throw new Exception("未指定要识别的文字");
                            }
                            Point clickPoint = performOCRAndFindText(screen, targetText);
                            if (clickPoint == null) {
                                boolean found = false;
                                for (int retry = 0; retry < 3; retry++) {
                                    Thread.sleep(step.timeoutMs / 3);
                                    screen = ScreenCaptureHelper.captureScreen();
                                    if (screen == null) continue;
                                    clickPoint = performOCRAndFindText(screen, targetText);
                                    if (clickPoint != null) {
                                        found = true;
                                        break;
                                    }
                                }
                                if (!found) {
                                    throw new Exception("未找到文字: " + targetText);
                                }
                            }
                            MyAccessibilityService.performClick((int) clickPoint.x, (int) clickPoint.y);
                            Thread.sleep(200);
                        } catch (SecurityException e) {
                            // 截图权限失效
                            currentStepIndex = index;
                            runOnUiThread(() -> {
                                Toast.makeText(ScriptEditorActivity.this, "截图权限已失效，请重新授权", Toast.LENGTH_SHORT).show();
                                ScreenCaptureHelper.cleanup();
                                mediaProjection = null;
                                isRunning = false;
                                requestScreenCapturePermission();
                            });
                            return;
                        } catch (Exception e) {
                            e.printStackTrace();
                            Log.e("ScriptEditor", "执行步骤出错 (index=" + index + ")", e);
                            runOnUiThread(() -> {
                                Toast.makeText(ScriptEditorActivity.this, "执行出错: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                isRunning = false;
                            });
                            return;
                        }
                        break;

                    // ====== 条件分支（不变） ======
                    case ScriptStep.TYPE_IF:
                        Log.d("ScriptEditor", "🔍 执行 IF 步骤，条件文字: " + step.conditionText);
                        try {
                            if (!ScreenCaptureHelper.isInitialized()) {
                                throw new Exception("截图服务未初始化，请重新授权");
                            }
                            Bitmap ifScreen = ScreenCaptureHelper.captureScreen();
                            if (ifScreen == null) {
                                throw new Exception("截图失败");
                            }
                            boolean conditionMet = performOCRCheck(ifScreen, step.conditionText);
                            Log.d("ScriptEditor", "条件判断结果: " + conditionMet);

                            List<ScriptStep> targetSubSteps = conditionMet ? step.thenSteps : step.elseSteps;
                            if (targetSubSteps != null && !targetSubSteps.isEmpty()) {
                                // 异步执行子步骤，完成后继续下一步
                                executeSubSteps(targetSubSteps, () -> {
                                    Log.d("ScriptEditor", "IF 子步骤执行完毕，继续下一步");
                                    runOnUiThread(() -> executeStep(index + 1));
                                });
                                // 子步骤异步执行，当前线程直接返回，由回调驱动后续流程
                                return;
                            } else {
                                Log.d("ScriptEditor", "IF 没有子步骤，直接继续");
                                // 没有子步骤，直接继续下一步
                                runOnUiThread(() -> executeStep(index + 1));
                            }
                        } catch (Exception e) {
                            Log.e("ScriptEditor", "IF 步骤执行失败", e);
                            runOnUiThread(() -> {
                                Toast.makeText(ScriptEditorActivity.this, "条件分支执行失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                isRunning = false;
                            });
                            return;
                        }
                        break; // 如果前面没有 return，这里 break 防止执行到最后一段

                    default:
                        break;
                }
            } catch (Exception e) {
                Log.e("ScriptEditor", "执行步骤出错 (index=" + index + ")", e);
                // 其他未捕获的异常（如无障碍服务未运行等）
                e.printStackTrace();
                runOnUiThread(() -> {
                    Toast.makeText(ScriptEditorActivity.this, "执行出错: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    isRunning = false;
                });
                return;
            }

            // 非 IF 步骤才继续下一步（IF 步骤内部自己控制）
            if (step.type != ScriptStep.TYPE_IF) {
                runOnUiThread(() -> executeStep(index + 1));
            }
        }).start();
    }
    // ========== 执行子步骤列表（递归） ==========
    private void executeSubSteps(List<ScriptStep> subSteps, Runnable onComplete) {
        if (subSteps == null || subSteps.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        executeSubStepRecursive(subSteps, 0, onComplete);
    }

    private void executeSubStepRecursive(List<ScriptStep> subSteps, int index, Runnable onComplete) {
        if (!isRunning || index >= subSteps.size()) {
            Log.d("ScriptEditor", "🔍 executeSubStepRecursive: 子步骤总数=" + subSteps.size() + ", 当前索引=" + index);
            if (onComplete != null) onComplete.run();
            return;
        }

        final ScriptStep step = subSteps.get(index);
        new Thread(() -> {
            try {
                switch (step.type) {
                    case ScriptStep.TYPE_LAUNCH_APP:
                        Log.d("ScriptEditor", "▶️ 执行启动应用: " + step.packageName);
                        launchApp(step.packageName);
                        Log.d("ScriptEditor", "✅ 启动应用调用完成");
                        break;

                    case ScriptStep.TYPE_WAIT:
                        Log.d("ScriptEditor", "⏳ 执行等待: " + step.waitMs + "ms");
                        Thread.sleep(step.waitMs);
                        Log.d("ScriptEditor", "✅ 等待完成");
                        break;

                    case ScriptStep.TYPE_CLICK:
                        Log.d("ScriptEditor", "🖱️ 执行点击: (" + step.x + "," + step.y + ")");
                        MyAccessibilityService.performClick(step.x, step.y);
                        Thread.sleep(200);
                        break;

                    case ScriptStep.TYPE_SWIPE:
                        Log.d("ScriptEditor", "👆 执行滑动: (" + step.x1 + "," + step.y1 + ") -> (" + step.x2 + "," + step.y2 + ")");
                        MyAccessibilityService.performSwipe(step.x1, step.y1, step.x2, step.y2);
                        Thread.sleep(300);
                        break;

                    case ScriptStep.TYPE_BACK:
                        Log.d("ScriptEditor", "◀️ 执行返回键");
                        MyAccessibilityService.doGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                        Thread.sleep(200);
                        break;

                    case ScriptStep.TYPE_HOME:
                        Log.d("ScriptEditor", "🏠 执行 Home 键");
                        MyAccessibilityService.doGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
                        Thread.sleep(200);
                        break;

                    case ScriptStep.TYPE_TEXT:
                        Log.d("ScriptEditor", "⌨️ 执行文本输入: " + step.text);
                        try {
                            MyAccessibilityService.inputText(step.text);
                            Thread.sleep(200);
                            Log.d("ScriptEditor", "✅ 文本输入成功");
                        } catch (Exception e) {
                            Log.e("ScriptEditor", "❌ 文本输入失败", e);
                            runOnUiThread(() -> {
                                Toast.makeText(ScriptEditorActivity.this, "文本输入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                                isRunning = false;
                            });
                            return;
                        }
                        break;

                    case ScriptStep.TYPE_LONG_CLICK:
                        Log.d("ScriptEditor", "🖱️ 执行长按: (" + step.x + "," + step.y + ") 持续 " + step.longClickDuration + "ms");
                        MyAccessibilityService.performLongClick(step.x, step.y, step.longClickDuration);
                        Thread.sleep(200);
                        break;

                    case ScriptStep.TYPE_IMAGE_CLICK:
                        Log.d("ScriptEditor", "📷 执行文字识别点击: " + step.ocrText);
                        try {
                            if (!ScreenCaptureHelper.isInitialized()) {
                                throw new Exception("截图服务未初始化，请重新授权");
                            }
                            Bitmap screen = ScreenCaptureHelper.captureScreen();
                            if (screen == null) {
                                throw new Exception("截图失败，请重试");
                            }
                            String targetText = step.ocrText;
                            if (targetText == null || targetText.isEmpty()) {
                                throw new Exception("未指定要识别的文字");
                            }
                            Point clickPoint = performOCRAndFindText(screen, targetText);
                            if (clickPoint == null) {
                                boolean found = false;
                                for (int retry = 0; retry < 3; retry++) {
                                    Thread.sleep(step.timeoutMs / 3);
                                    screen = ScreenCaptureHelper.captureScreen();
                                    if (screen == null) continue;
                                    clickPoint = performOCRAndFindText(screen, targetText);
                                    if (clickPoint != null) {
                                        found = true;
                                        break;
                                    }
                                }
                                if (!found) {
                                    throw new Exception("未找到文字: " + targetText);
                                }
                            }
                            MyAccessibilityService.performClick((int) clickPoint.x, (int) clickPoint.y);
                            Thread.sleep(200);
                        } catch (SecurityException e) {
                            Log.e("ScriptEditor", "截图权限失效", e);
                            currentStepIndex = index;
                            runOnUiThread(() -> {
                                Toast.makeText(ScriptEditorActivity.this, "截图权限已失效，请重新授权", Toast.LENGTH_SHORT).show();
                                ScreenCaptureHelper.cleanup();
                                mediaProjection = null;
                                isRunning = false;
                                requestScreenCapturePermission();
                            });
                            return;
                        } catch (Exception e) {
                            Log.e("ScriptEditor", "执行步骤出错 (index=" + index + ")", e);
                            e.printStackTrace();
                            runOnUiThread(() -> {
                                Toast.makeText(ScriptEditorActivity.this, "执行出错: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                isRunning = false;
                            });
                            return;
                        }
                        break;

                    case ScriptStep.TYPE_IF:
                        Log.d("ScriptEditor", "🔍 执行 IF 步骤，条件文字: " + step.conditionText);
                        try {
                            if (!ScreenCaptureHelper.isInitialized()) {
                                throw new Exception("截图服务未初始化，请重新授权");
                            }
                            Bitmap ifScreen = ScreenCaptureHelper.captureScreen();
                            if (ifScreen == null) {
                                throw new Exception("截图失败");
                            }
                            boolean conditionMet = performOCRCheck(ifScreen, step.conditionText);
                            Log.d("ScriptEditor", "条件判断结果: " + conditionMet);

                            List<ScriptStep> targetSubSteps = conditionMet ? step.thenSteps : step.elseSteps;
                            if (targetSubSteps != null && !targetSubSteps.isEmpty()) {
                                // 异步执行子步骤，完成后继续下一步
                                executeSubSteps(targetSubSteps, () -> {
                                    Log.d("ScriptEditor", "IF 子步骤执行完毕，继续下一步");
                                    runOnUiThread(() -> executeStep(index + 1));
                                });
                                // 子步骤异步执行，当前线程直接返回，由回调驱动后续流程
                                return;
                            } else {
                                Log.d("ScriptEditor", "IF 没有子步骤，直接继续");
                                // 没有子步骤，直接继续下一步
                                runOnUiThread(() -> executeStep(index + 1));
                            }
                        } catch (Exception e) {
                            Log.e("ScriptEditor", "IF 步骤执行失败", e);
                            runOnUiThread(() -> {
                                Toast.makeText(ScriptEditorActivity.this, "条件分支执行失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                isRunning = false;
                            });
                            return;
                        }
                        break; // 如果前面没有 return，这里 break 防止执行到最后一段

                    default:
                        Log.w("ScriptEditor", "⚠️ 未知步骤类型: " + step.type);
                        break;
                }
            } catch (Exception e) {
                Log.e("ScriptEditor", "子步骤执行失败", e);
                runOnUiThread(() -> {
                    Toast.makeText(ScriptEditorActivity.this, "子步骤执行失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    isRunning = false;
                });
                return;
            }
            // 递归到下一个子步骤
            runOnUiThread(() -> executeSubStepRecursive(subSteps, index + 1, onComplete));
        }).start();
    }
    // ========== OCR 条件检查（返回 true/false） ==========
    private boolean performOCRCheck(Bitmap bitmap, String targetText) {
        if (bitmap == null || targetText == null) return false;
        try {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final boolean[] result = {false};
            final Exception[] error = {null};

            InputImage image = InputImage.fromBitmap(bitmap, 0);
            TextRecognizer recognizer = TextRecognition.getClient(
                    new ChineseTextRecognizerOptions.Builder().build()
            );

            recognizer.process(image)
                    .addOnSuccessListener(visionText -> {
                        String targetClean = targetText.replaceAll("\\s+", "");
                        for (Text.TextBlock block : visionText.getTextBlocks()) {
                            for (Text.Line line : block.getLines()) {
                                String lineClean = line.getText().replaceAll("\\s+", "");
                                if (lineClean.contains(targetClean)) {
                                    result[0] = true;
                                    latch.countDown();
                                    return;
                                }
                            }
                        }
                        latch.countDown();
                    })
                    .addOnFailureListener(e -> {
                        error[0] = e;
                        latch.countDown();
                    });

            if (latch.await(5000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return result[0];
            } else {
                Log.e("ScriptEditor", "OCR 条件检查超时");
                return false;
            }
        } catch (Exception e) {
            Log.e("ScriptEditor", "OCR 条件检查异常", e);
            return false;
        }
    }
    // ========== OCR 辅助方法 ==========
    private Point performOCRAndFindText(Bitmap bitmap, String targetText) {
        // ✅ 1. 参数检查
        if (bitmap == null) {
            Log.e("ScriptEditor", "❌ bitmap 为 null");
            return null;
        }
        if (targetText == null || targetText.isEmpty()) {
            Log.e("ScriptEditor", "❌ targetText 为空");
            return null;
        }

        // ✅ 2. 保存截图到本地（用于调试，让你亲眼看到截了什么图）
        try {
            // 创建 screenshots 目录
            File dir = new File(getExternalFilesDir(null), "screenshots");
            if (!dir.exists()) {
                boolean created = dir.mkdirs();
                Log.d("ScriptEditor", "创建截图目录: " + dir.getAbsolutePath() + ", 成功: " + created);
            }
            // 保存截图
            String fileName = "ocr_" + System.currentTimeMillis() + ".png";
            File file = new File(dir, fileName);
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            Log.d("ScriptEditor", "📸 截图已保存: " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e("ScriptEditor", "保存截图失败: " + e.getMessage(), e);
        }

        try {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final Point[] result = {null};
            final Exception[] error = {null};

            Log.d("ScriptEditor", "🔍 开始 OCR 识别，目标文字: [" + targetText + "]");

            InputImage image = InputImage.fromBitmap(bitmap, 0);
            TextRecognizer recognizer = TextRecognition.getClient(
                    new ChineseTextRecognizerOptions.Builder().build()
            );

            recognizer.process(image)
                    .addOnSuccessListener(visionText -> {
                        // ✅ 3. 打印所有识别到的文字
                        StringBuilder allText = new StringBuilder();
                        allText.append("=== OCR 识别结果 ===\n");
                        int lineCount = 0;

                        for (Text.TextBlock block : visionText.getTextBlocks()) {
                            for (Text.Line line : block.getLines()) {
                                String lineText = line.getText();
                                allText.append("  [" + lineText + "]\n");
                                Log.d("ScriptEditor", "OCR 行: [" + lineText + "]");
                                lineCount++;
                            }
                        }

                        if (lineCount == 0) {
                            Log.d("ScriptEditor", "⚠️ OCR 未识别到任何文字！请检查截图是否包含文字");
                        } else {
                            Log.d("ScriptEditor", allText.toString());
                        }

                        // ✅ 4. 去除空格后匹配（提高容错率）
                        String targetClean = targetText.replaceAll("\\s+", "");
                        Log.d("ScriptEditor", "🎯 目标文字(去除空格): [" + targetClean + "]");

                        for (Text.TextBlock block : visionText.getTextBlocks()) {
                            for (Text.Line line : block.getLines()) {
                                String lineText = line.getText();
                                String lineClean = lineText.replaceAll("\\s+", "");

                                // ✅ 5. 打印每一步匹配过程
                                Log.d("ScriptEditor", "比较: [" + lineClean + "] 包含 [" + targetClean + "]? " + lineClean.contains(targetClean));

                                if (lineClean.contains(targetClean)) {
                                    android.graphics.Point[] corners = line.getCornerPoints();
                                    if (corners != null && corners.length >= 4) {
                                        // ✅ 6. 计算中心点
                                        int centerX = (corners[0].x + corners[2].x) / 2;
                                        int centerY = (corners[0].y + corners[2].y) / 2;
                                        result[0] = new Point(centerX, centerY);
                                        Log.d("ScriptEditor", "✅ 找到目标文字! 坐标: (" + centerX + ", " + centerY + ")");
                                        latch.countDown();
                                        return;
                                    } else {
                                        Log.d("ScriptEditor", "⚠️ corners 长度不足: " + (corners != null ? corners.length : "null"));
                                    }
                                }
                            }
                        }
                        Log.d("ScriptEditor", "❌ 未找到目标文字: [" + targetText + "]");
                        latch.countDown();
                    })
                    .addOnFailureListener(e -> {
                        error[0] = e;
                        Log.e("ScriptEditor", "❌ OCR 识别失败", e);
                        latch.countDown();
                    });

            // ✅ 7. 等待识别完成（超时 5 秒）
            if (latch.await(5000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (error[0] != null) {
                    Log.e("ScriptEditor", "OCR 识别失败", error[0]);
                    return null;
                }
                return result[0];
            } else {
                Log.e("ScriptEditor", "⏰ OCR 识别超时 (5秒)");
                return null;
            }
        } catch (Exception e) {
            Log.e("ScriptEditor", "❌ OCR 异常: " + e.getMessage(), e);
            return null;
        }
    }

    private void launchApp(String packageName) {
        Log.d("ScriptEditor", "launchApp() 开始, pkg=" + packageName);
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Log.d("ScriptEditor", "✅ 启动应用成功 (startActivity 已调用)");
            } else {
                Log.e("ScriptEditor", "❌ 未找到应用: " + packageName);
                runOnUiThread(() -> Toast.makeText(ScriptEditorActivity.this, "未找到应用: " + packageName, Toast.LENGTH_SHORT).show());
            }
        } catch (Exception e) {
            Log.e("ScriptEditor", "❌ 启动应用异常", e);
            // 不抛出，避免中断脚本（由外层 catch 处理）
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

    // ========== 以下为所有对话框和其他辅助方法 ==========
    // ========== 添加步骤对话框 ==========
    private void showAddStepDialog() {
        String[] types = {"启动应用", "等待", "点击", "滑动", "返回键", "Home键", "文本输入", "长按", "文字识别点击", "📦 条件分支"};
        new AlertDialog.Builder(this)
                .setTitle("选择步骤类型")
                .setItems(types, (dialog, which) -> {
                    switch (which) {
                        case 0: showAppPickerDialog(); break;
                        case 1: showWaitDialog(); break;
                        case 2: showClickDialog(); break;
                        case 3: showSwipeDialog(); break;
                        case 4: addGlobalActionStep(ScriptStep.TYPE_BACK, "返回键"); break;
                        case 5: addGlobalActionStep(ScriptStep.TYPE_HOME, "Home键"); break;
                        case 6: showTextInputDialog(); break;
                        case 7: showLongClickDialog(); break;
                        case 8: showImageClickDialog(); break;
                        case 9: showIfStepDialog(); break;  // ✅ 新增
                    }
                })
                .show();
    }

    private void addGlobalActionStep(int type, String name) {
        ScriptStep step = new ScriptStep(type);
        steps.add(step);
        adapter.notifyItemInserted(steps.size() - 1);
        markModified();
    }

    private void showTextInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("输入文本");
        final EditText input = new EditText(this);
        input.setHint("请输入要输入的文字");
        builder.setView(input);
        builder.setPositiveButton("确定", (dialog, which) -> {
            String text = input.getText().toString();
            if (text.isEmpty()) {
                Toast.makeText(this, "文字不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            ScriptStep step = new ScriptStep(ScriptStep.TYPE_TEXT);
            step.text = text;
            steps.add(step);
            adapter.notifyItemInserted(steps.size() - 1);
            markModified();
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void showLongClickDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("长按坐标");

        capturedClickX = new EditText(this);
        capturedClickX.setHint("X 坐标");
        capturedClickY = new EditText(this);
        capturedClickY.setHint("Y 坐标");
        final EditText inputDuration = new EditText(this);
        inputDuration.setHint("持续时间（毫秒）");
        inputDuration.setText("500");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 20);
        layout.addView(capturedClickX);
        layout.addView(capturedClickY);
        layout.addView(inputDuration);

        Button btnCaptureTouch = new Button(this);
        btnCaptureTouch.setText("📌 点击屏幕捕获");
        btnCaptureTouch.setOnClickListener(v -> {
            Intent intent = new Intent(ScriptEditorActivity.this, CoordinateCaptureActivity.class);
            startActivityForResult(intent, REQUEST_CAPTURE_LONG_CLICK);
        });

        Button btnCaptureFloating = new Button(this);
        btnCaptureFloating.setText("📌 悬浮窗捕获");
        btnCaptureFloating.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            }
            CoordinateFloatingService.startCapture(ScriptEditorActivity.this, 4);
        });

        Button btnCaptureFullScreen = new Button(this);
        btnCaptureFullScreen.setText("📌 全屏捕获");
        btnCaptureFullScreen.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            }
            FullScreenCaptureService.startCapture(ScriptEditorActivity.this, 4);
        });

        layout.addView(btnCaptureTouch);
        layout.addView(btnCaptureFloating);
        layout.addView(btnCaptureFullScreen);
        builder.setView(layout);

        builder.setPositiveButton("确定", (dialog, which) -> {
            try {
                int x = Integer.parseInt(capturedClickX.getText().toString().trim());
                int y = Integer.parseInt(capturedClickY.getText().toString().trim());
                int duration = Integer.parseInt(inputDuration.getText().toString().trim());
                if (duration <= 0) duration = 500;
                ScriptStep step = new ScriptStep(ScriptStep.TYPE_LONG_CLICK);
                step.x = x;
                step.y = y;
                step.longClickDuration = duration;
                steps.add(step);
                adapter.notifyItemInserted(steps.size() - 1);
                markModified();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    // ========== 文字识别点击对话框（OCR） ==========
    private void showImageClickDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("文字识别点击");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 20);

        final EditText inputText = new EditText(this);
        inputText.setHint("请输入要识别的文字（如：确认、取消）");
        layout.addView(inputText);

        final EditText inputTimeout = new EditText(this);
        inputTimeout.setHint("超时 (毫秒)");
        inputTimeout.setText("5000");
        layout.addView(inputTimeout);

        builder.setView(layout);

        builder.setPositiveButton("确定", (dialog, which) -> {
            String text = inputText.getText().toString().trim();
            if (text.isEmpty()) {
                Toast.makeText(this, "请输入要识别的文字", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                int timeout = Integer.parseInt(inputTimeout.getText().toString().trim());
                if (timeout < 1000) timeout = 1000;

                ScriptStep step = new ScriptStep(ScriptStep.TYPE_IMAGE_CLICK);
                step.ocrText = text;
                step.timeoutMs = timeout;
                steps.add(step);
                adapter.notifyItemInserted(steps.size() - 1);
                markModified();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }
    // ========== 添加条件分支步骤 ==========
    private void showIfStepDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📦 条件分支");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 20);

        final EditText inputText = new EditText(this);
        inputText.setHint("请输入要识别的文字（如：爱加速）");
        layout.addView(inputText);

        TextView tip = new TextView(this);
        tip.setText("提示：添加后点击 IF 步骤可编辑子步骤");
        tip.setTextSize(12);
        tip.setTextColor(0xFF888888);
        tip.setPadding(0, 16, 0, 0);
        layout.addView(tip);

        builder.setView(layout);

        builder.setPositiveButton("添加", (dialog, which) -> {
            String text = inputText.getText().toString().trim();
            if (text.isEmpty()) {
                Toast.makeText(this, "请输入要识别的文字", Toast.LENGTH_SHORT).show();
                return;
            }

            ScriptStep step = new ScriptStep(ScriptStep.TYPE_IF);
            step.conditionText = text;
            step.thenSteps = new ArrayList<>();
            step.elseSteps = new ArrayList<>();
            steps.add(step);
            adapter.notifyItemInserted(steps.size() - 1);
            markModified();
            Toast.makeText(this, "✅ 条件分支已添加，点击可编辑子步骤", Toast.LENGTH_LONG).show();
        });

        builder.setNegativeButton("取消", null);
        builder.show();
    }
    // ========== 在 IF 中添加子步骤 ==========
    private void showAddSubStepDialog(final int parentPosition, final boolean isThen) {
        ScriptStep parent = steps.get(parentPosition);
        if (!parent.isConditionStep()) return;

        final List<ScriptStep> targetList = isThen ? parent.thenSteps : parent.elseSteps;

        String[] types = {"启动应用", "等待", "点击", "滑动", "返回键", "Home键", "文本输入", "长按", "文字识别点击"};
        new AlertDialog.Builder(this)
                .setTitle(isThen ? "条件成立时 - 添加步骤" : "条件不成立时 - 添加步骤")
                .setItems(types, (dialog, which) -> {
                    ScriptStep subStep = null;
                    switch (which) {
                        case 0: // 启动应用
                            showAppPickerSubDialog(parentPosition, isThen);
                            return;
                        case 1: // 等待
                            showWaitSubDialog(parentPosition, isThen);
                            return;
                        case 2: // 点击
                            showClickSubDialog(parentPosition, isThen);
                            return;
                        case 3: // 滑动
                            showSwipeSubDialog(parentPosition, isThen);
                            return;
                        case 4: // 返回键
                            subStep = new ScriptStep(ScriptStep.TYPE_BACK);
                            break;
                        case 5: // Home键
                            subStep = new ScriptStep(ScriptStep.TYPE_HOME);
                            break;
                        case 6: // 文本输入
                            showTextInputSubDialog(parentPosition, isThen);
                            return;
                        case 7: // 长按
                            showLongClickSubDialog(parentPosition, isThen);
                            return;
                        case 8: // 文字识别点击
                            showImageClickSubDialog(parentPosition, isThen);
                            return;
                    }
                    if (subStep != null) {
                        targetList.add(subStep);
                        adapter.notifyItemChanged(parentPosition);
                        markModified();
                    }
                })
                .show();
    }

// ========== 子步骤的各种添加对话框（简化版，复用现有逻辑） ==========

    private void showWaitSubDialog(final int parentPosition, final boolean isThen) {
        final List<ScriptStep> targetList = isThen ? steps.get(parentPosition).thenSteps : steps.get(parentPosition).elseSteps;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("等待 (毫秒)");
        final EditText input = new EditText(this);
        input.setHint("请输入等待毫秒，如 1000");
        builder.setView(input);
        builder.setPositiveButton("确定", (dialog, which) -> {
            try {
                int ms = Integer.parseInt(input.getText().toString().trim());
                ScriptStep step = new ScriptStep(ScriptStep.TYPE_WAIT);
                step.waitMs = ms;
                targetList.add(step);
                adapter.notifyItemChanged(parentPosition);
                markModified();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void showClickSubDialog(final int parentPosition, final boolean isThen) {
        final List<ScriptStep> targetList = isThen ? steps.get(parentPosition).thenSteps : steps.get(parentPosition).elseSteps;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("点击坐标");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 20);

        final EditText inputX = new EditText(this);
        inputX.setHint("X 坐标");
        final EditText inputY = new EditText(this);
        inputY.setHint("Y 坐标");
        layout.addView(inputX);
        layout.addView(inputY);

        // ✅ 关键：将局部变量赋值给类成员变量，让广播接收器能找到它们
        capturedClickX = inputX;
        capturedClickY = inputY;

        // ========== 添加全屏捕获按钮 ==========
        Button btnCaptureFullScreen = new Button(this);
        btnCaptureFullScreen.setText("📌 全屏捕获");
        btnCaptureFullScreen.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            }
            FullScreenCaptureService.startCapture(ScriptEditorActivity.this, 1);
        });
        layout.addView(btnCaptureFullScreen);

        builder.setView(layout);

        builder.setPositiveButton("确定", (dialog, which) -> {
            try {
                int x = Integer.parseInt(inputX.getText().toString().trim());
                int y = Integer.parseInt(inputY.getText().toString().trim());
                ScriptStep step = new ScriptStep(ScriptStep.TYPE_CLICK);
                step.x = x;
                step.y = y;
                targetList.add(step);
                adapter.notifyItemChanged(parentPosition);
                markModified();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "请输入有效坐标", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void showSwipeSubDialog(final int parentPosition, final boolean isThen) {
        final List<ScriptStep> targetList = isThen ? steps.get(parentPosition).thenSteps : steps.get(parentPosition).elseSteps;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("滑动 (起点→终点)");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 20);
        final EditText inputX1 = new EditText(this);
        inputX1.setHint("起点 X");
        final EditText inputY1 = new EditText(this);
        inputY1.setHint("起点 Y");
        final EditText inputX2 = new EditText(this);
        inputX2.setHint("终点 X");
        final EditText inputY2 = new EditText(this);
        inputY2.setHint("终点 Y");
        layout.addView(inputX1);
        layout.addView(inputY1);
        layout.addView(inputX2);
        layout.addView(inputY2);
        builder.setView(layout);
        builder.setPositiveButton("确定", (dialog, which) -> {
            try {
                int x1 = Integer.parseInt(inputX1.getText().toString().trim());
                int y1 = Integer.parseInt(inputY1.getText().toString().trim());
                int x2 = Integer.parseInt(inputX2.getText().toString().trim());
                int y2 = Integer.parseInt(inputY2.getText().toString().trim());
                ScriptStep step = new ScriptStep(ScriptStep.TYPE_SWIPE);
                step.x1 = x1;
                step.y1 = y1;
                step.x2 = x2;
                step.y2 = y2;
                targetList.add(step);
                adapter.notifyItemChanged(parentPosition);
                markModified();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void showTextInputSubDialog(final int parentPosition, final boolean isThen) {
        final List<ScriptStep> targetList = isThen ? steps.get(parentPosition).thenSteps : steps.get(parentPosition).elseSteps;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("输入文本");
        final EditText input = new EditText(this);
        input.setHint("请输入要输入的文字");
        builder.setView(input);
        builder.setPositiveButton("确定", (dialog, which) -> {
            String text = input.getText().toString();
            if (text.isEmpty()) {
                Toast.makeText(this, "文字不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            ScriptStep step = new ScriptStep(ScriptStep.TYPE_TEXT);
            step.text = text;
            targetList.add(step);
            adapter.notifyItemChanged(parentPosition);
            markModified();
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void showLongClickSubDialog(final int parentPosition, final boolean isThen) {
        final List<ScriptStep> targetList = isThen ? steps.get(parentPosition).thenSteps : steps.get(parentPosition).elseSteps;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("长按坐标");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 20);

        final EditText inputX = new EditText(this);
        inputX.setHint("X 坐标");
        final EditText inputY = new EditText(this);
        inputY.setHint("Y 坐标");
        final EditText inputDuration = new EditText(this);
        inputDuration.setHint("持续时间（毫秒）");
        inputDuration.setText("500");
        layout.addView(inputX);
        layout.addView(inputY);
        layout.addView(inputDuration);

        // ✅ 关键：将局部变量赋值给类成员变量
        capturedClickX = inputX;
        capturedClickY = inputY;

        // ========== 添加全屏捕获按钮 ==========
        Button btnCaptureFullScreen = new Button(this);
        btnCaptureFullScreen.setText("📌 全屏捕获");
        btnCaptureFullScreen.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            }
            FullScreenCaptureService.startCapture(ScriptEditorActivity.this, 4);
        });
        layout.addView(btnCaptureFullScreen);

        builder.setView(layout);

        builder.setPositiveButton("确定", (dialog, which) -> {
            try {
                int x = Integer.parseInt(inputX.getText().toString().trim());
                int y = Integer.parseInt(inputY.getText().toString().trim());
                int duration = Integer.parseInt(inputDuration.getText().toString().trim());
                if (duration <= 0) duration = 500;
                ScriptStep step = new ScriptStep(ScriptStep.TYPE_LONG_CLICK);
                step.x = x;
                step.y = y;
                step.longClickDuration = duration;
                targetList.add(step);
                adapter.notifyItemChanged(parentPosition);
                markModified();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void showImageClickSubDialog(final int parentPosition, final boolean isThen) {
        final List<ScriptStep> targetList = isThen ? steps.get(parentPosition).thenSteps : steps.get(parentPosition).elseSteps;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("文字识别点击");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 20);

        final EditText inputText = new EditText(this);
        inputText.setHint("请输入要识别的文字");
        layout.addView(inputText);

        final EditText inputTimeout = new EditText(this);
        inputTimeout.setHint("超时 (毫秒)");
        inputTimeout.setText("5000");
        layout.addView(inputTimeout);

        builder.setView(layout);

        builder.setPositiveButton("确定", (dialog, which) -> {
            String text = inputText.getText().toString().trim();
            if (text.isEmpty()) {
                Toast.makeText(this, "请输入文字", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                int timeout = Integer.parseInt(inputTimeout.getText().toString().trim());
                if (timeout < 1000) timeout = 1000;

                ScriptStep subStep = new ScriptStep(ScriptStep.TYPE_IMAGE_CLICK);
                subStep.ocrText = text;
                subStep.timeoutMs = timeout;
                targetList.add(subStep);
                adapter.notifyItemChanged(parentPosition);
                markModified();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void showAppPickerSubDialog(final int parentPosition, final boolean isThen) {
        final List<ScriptStep> targetList = isThen ? steps.get(parentPosition).thenSteps : steps.get(parentPosition).elseSteps;
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        List<ApplicationInfo> userApps = new ArrayList<>();
        for (ApplicationInfo info : apps) {
            if ((info.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                userApps.add(info);
            }
        }

        ArrayAdapter<ApplicationInfo> appAdapter = new ArrayAdapter<ApplicationInfo>(
                this, android.R.layout.simple_list_item_1, userApps) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext())
                            .inflate(android.R.layout.simple_list_item_1, parent, false);
                }
                TextView tv = convertView.findViewById(android.R.id.text1);
                ApplicationInfo info = getItem(position);
                tv.setText(info.loadLabel(pm).toString() + "\n" + info.packageName);

                Drawable icon = info.loadIcon(pm);
                if (icon != null) {
                    int size = (int) (32 * getResources().getDisplayMetrics().density);
                    icon.setBounds(0, 0, size, size);
                    tv.setCompoundDrawables(icon, null, null, null);
                    tv.setCompoundDrawablePadding((int) (8 * getResources().getDisplayMetrics().density));
                }
                return convertView;
            }
        };

        new AlertDialog.Builder(this)
                .setTitle("选择应用")
                .setAdapter(appAdapter, (dialog, which) -> {
                    ApplicationInfo selected = userApps.get(which);
                    ScriptStep step = new ScriptStep(ScriptStep.TYPE_LAUNCH_APP);
                    step.packageName = selected.packageName;
                    targetList.add(step);
                    adapter.notifyItemChanged(parentPosition);
                    markModified();
                })
                .show();
    }

    // ========== 编辑 IF 步骤（跳转到子编辑器） ==========
    private void editIfStep(int position) {
        // 简单实现：用 AlertDialog 显示子步骤列表
        ScriptStep parent = steps.get(position);
        if (!parent.isConditionStep()) return;

        StringBuilder msg = new StringBuilder();
        msg.append("🔍 条件文字: ").append(parent.conditionText).append("\n\n");
        msg.append("✅ 条件成立时 (").append(parent.thenSteps != null ? parent.thenSteps.size() : 0).append("步):\n");
        if (parent.thenSteps != null) {
            for (int i = 0; i < parent.thenSteps.size(); i++) {
                msg.append("  ").append(i + 1).append(". ").append(parent.thenSteps.get(i).getDescription()).append("\n");
            }
        }
        msg.append("\n❌ 条件不成立时 (").append(parent.elseSteps != null ? parent.elseSteps.size() : 0).append("步):\n");
        if (parent.elseSteps != null) {
            for (int i = 0; i < parent.elseSteps.size(); i++) {
                msg.append("  ").append(i + 1).append(". ").append(parent.elseSteps.get(i).getDescription()).append("\n");
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("📦 条件分支详情")
                .setMessage(msg.toString())
                .setPositiveButton("关闭", null)
                .show();
    }
    // ========== 保存图片到内部存储 ==========
    private String saveImageToInternalStorage(Uri uri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) return null;

            File dir = new File(getFilesDir(), "templates");
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String fileName = "template_" + System.currentTimeMillis() + ".png";
            File destFile = new File(dir, fileName);

            FileOutputStream out = new FileOutputStream(destFile);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = inputStream.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            out.close();
            inputStream.close();

            return destFile.getAbsolutePath();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    // ========== 应用列表选择器 ==========
    private void showAppPickerDialog() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        List<ApplicationInfo> userApps = new ArrayList<>();
        for (ApplicationInfo info : apps) {
            if ((info.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                userApps.add(info);
            }
        }

        ArrayAdapter<ApplicationInfo> appAdapter = new ArrayAdapter<ApplicationInfo>(
                this, android.R.layout.simple_list_item_1, userApps) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext())
                            .inflate(android.R.layout.simple_list_item_1, parent, false);
                }
                TextView tv = convertView.findViewById(android.R.id.text1);
                ApplicationInfo info = getItem(position);
                tv.setText(info.loadLabel(pm).toString() + "\n" + info.packageName);

                android.graphics.drawable.Drawable icon = info.loadIcon(pm);
                if (icon != null) {
                    int size = (int) (32 * getResources().getDisplayMetrics().density);
                    icon.setBounds(0, 0, size, size);
                    tv.setCompoundDrawables(icon, null, null, null);
                    tv.setCompoundDrawablePadding((int) (8 * getResources().getDisplayMetrics().density));
                }
                return convertView;
            }
        };

        new AlertDialog.Builder(this)
                .setTitle("选择应用")
                .setAdapter(appAdapter, (dialog, which) -> {
                    ApplicationInfo selected = userApps.get(which);
                    ScriptStep step = new ScriptStep(ScriptStep.TYPE_LAUNCH_APP);
                    step.packageName = selected.packageName;
                    steps.add(step);
                    ScriptEditorActivity.this.adapter.notifyItemInserted(steps.size() - 1);
                    markModified();
                })
                .show();
    }

    private void showWaitDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("等待 (毫秒)");
        final EditText input = new EditText(this);
        input.setHint("请输入等待毫秒，如 1000");
        builder.setView(input);
        builder.setPositiveButton("确定", (dialog, which) -> {
            try {
                int ms = Integer.parseInt(input.getText().toString().trim());
                ScriptStep step = new ScriptStep(ScriptStep.TYPE_WAIT);
                step.waitMs = ms;
                steps.add(step);
                adapter.notifyItemInserted(steps.size() - 1);
                markModified();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void showClickDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("点击坐标");

        capturedClickX = new EditText(this);
        capturedClickX.setHint("X 坐标");
        capturedClickY = new EditText(this);
        capturedClickY.setHint("Y 坐标");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 20);

        Button btnCaptureTouch = new Button(this);
        btnCaptureTouch.setText("📌 点击屏幕捕获");
        btnCaptureTouch.setOnClickListener(v -> {
            Intent intent = new Intent(ScriptEditorActivity.this, CoordinateCaptureActivity.class);
            startActivityForResult(intent, REQUEST_CAPTURE_CLICK);
        });

        Button btnCaptureFloating = new Button(this);
        btnCaptureFloating.setText("📌 悬浮窗捕获");
        btnCaptureFloating.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            }
            CoordinateFloatingService.startCapture(ScriptEditorActivity.this, 1);
        });

        Button btnCaptureFullScreen = new Button(this);
        btnCaptureFullScreen.setText("📌 全屏捕获");
        btnCaptureFullScreen.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            }
            FullScreenCaptureService.startCapture(ScriptEditorActivity.this, 1);
        });

        layout.addView(capturedClickX);
        layout.addView(capturedClickY);
        layout.addView(btnCaptureTouch);
        layout.addView(btnCaptureFloating);
        layout.addView(btnCaptureFullScreen);
        builder.setView(layout);

        builder.setPositiveButton("确定", (dialog, which) -> {
            try {
                int x = Integer.parseInt(capturedClickX.getText().toString().trim());
                int y = Integer.parseInt(capturedClickY.getText().toString().trim());
                ScriptStep step = new ScriptStep(ScriptStep.TYPE_CLICK);
                step.x = x;
                step.y = y;
                steps.add(step);
                adapter.notifyItemInserted(steps.size() - 1);
                markModified();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "请输入有效坐标", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void showSwipeDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("滑动 (起点→终点)");

        capturedSwipeX1 = new EditText(this);
        capturedSwipeX1.setHint("起点 X");
        capturedSwipeY1 = new EditText(this);
        capturedSwipeY1.setHint("起点 Y");
        capturedSwipeX2 = new EditText(this);
        capturedSwipeX2.setHint("终点 X");
        capturedSwipeY2 = new EditText(this);
        capturedSwipeY2.setHint("终点 Y");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 20);

        Button btnCaptureStart = new Button(this);
        btnCaptureStart.setText("📌 捕获起点");
        btnCaptureStart.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            }
            CoordinateFloatingService.startCapture(ScriptEditorActivity.this, 2);
        });

        Button btnCaptureEnd = new Button(this);
        btnCaptureEnd.setText("📌 捕获终点");
        btnCaptureEnd.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            }
            CoordinateFloatingService.startCapture(ScriptEditorActivity.this, 3);
        });

        layout.addView(capturedSwipeX1);
        layout.addView(capturedSwipeY1);
        layout.addView(capturedSwipeX2);
        layout.addView(capturedSwipeY2);
        layout.addView(btnCaptureStart);
        layout.addView(btnCaptureEnd);
        builder.setView(layout);

        builder.setPositiveButton("确定", (dialog, which) -> {
            try {
                int x1 = Integer.parseInt(capturedSwipeX1.getText().toString().trim());
                int y1 = Integer.parseInt(capturedSwipeY1.getText().toString().trim());
                int x2 = Integer.parseInt(capturedSwipeX2.getText().toString().trim());
                int y2 = Integer.parseInt(capturedSwipeY2.getText().toString().trim());
                ScriptStep step = new ScriptStep(ScriptStep.TYPE_SWIPE);
                step.x1 = x1;
                step.y1 = y1;
                step.x2 = x2;
                step.y2 = y2;
                steps.add(step);
                adapter.notifyItemInserted(steps.size() - 1);
                markModified();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void showRenameDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("保存脚本");
        final EditText input = new EditText(this);
        input.setHint("请输入脚本名称");
        builder.setView(input);
        builder.setPositiveButton("保存", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            if (scriptManager.scriptExists(name) && !name.equals(scriptName)) {
                Toast.makeText(this, "同名脚本已存在", Toast.LENGTH_SHORT).show();
                return;
            }
            if (scriptName != null && !scriptName.equals(name)) {
                scriptManager.deleteScript(scriptName);
            }
            scriptName = name;
            setTitle("📜 " + scriptName);
            saveScript();
            Toast.makeText(this, "已保存: " + scriptName, Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void showOpenScriptDialog() {
        List<String> names = scriptManager.getScriptNames();
        if (names.isEmpty()) {
            Toast.makeText(this, "没有已保存的脚本", Toast.LENGTH_SHORT).show();
            return;
        }

        android.widget.ListView listView = new android.widget.ListView(this);
        ArrayAdapter<String> listAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names);
        listView.setAdapter(listAdapter);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("打开脚本")
                .setView(listView)
                .setNegativeButton("取消", null)
                .create();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            String selected = names.get(position);
            dialog.dismiss();
            if (isModified) {
                new AlertDialog.Builder(this)
                        .setTitle("未保存的修改")
                        .setMessage("当前脚本有未保存的修改，是否保存？")
                        .setPositiveButton("保存", (d, w) -> {
                            saveScript();
                            loadScriptByName(selected);
                        })
                        .setNegativeButton("不保存", (d, w) -> loadScriptByName(selected))
                        .show();
            } else {
                loadScriptByName(selected);
            }
        });

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            String selected = names.get(position);
            new AlertDialog.Builder(this)
                    .setTitle("删除脚本")
                    .setMessage("确定要删除脚本 \"" + selected + "\" 吗？")
                    .setPositiveButton("删除", (d, w) -> {
                        scriptManager.deleteScript(selected);
                        if (scriptName != null && scriptName.equals(selected)) {
                            steps.clear();
                            adapter.notifyDataSetChanged();
                            scriptName = null;
                            setTitle("脚本编辑器");
                            isModified = false;
                        }
                        names.clear();
                        names.addAll(scriptManager.getScriptNames());
                        listAdapter.notifyDataSetChanged();
                        if (names.isEmpty()) {
                            dialog.dismiss();
                            Toast.makeText(this, "没有已保存的脚本", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        });

        dialog.show();
    }

    private void loadScriptByName(String name) {
        try {
            steps.clear();
            steps.addAll(scriptManager.loadScript(name));
            adapter.notifyDataSetChanged();
            scriptName = name;
            isModified = false;
            setTitle("📜 " + scriptName);
            Toast.makeText(this, "已加载: " + name, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "加载失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void markModified() {
        isModified = true;
        if (scriptName != null && !scriptName.isEmpty()) {
            saveScript();
        }
    }

    private void saveScript() {
        if (scriptName == null || scriptName.isEmpty()) return;
        try {
            scriptManager.saveScript(scriptName, steps);
            isModified = false;
        } catch (IOException e) {
            Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void handleBack() {
        if (isModified && scriptName != null && !scriptName.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("未保存的修改")
                    .setMessage("脚本 \"" + scriptName + "\" 有未保存的修改，是否保存？")
                    .setPositiveButton("保存", (d, w) -> {
                        saveScript();
                        finish();
                    })
                    .setNegativeButton("不保存", (d, w) -> finish())
                    .show();
        } else if (scriptName == null || scriptName.isEmpty()) {
            finish();
        } else {
            finish();
        }
    }
}