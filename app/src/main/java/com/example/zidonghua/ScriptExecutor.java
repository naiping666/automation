package com.example.zidonghua;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ScriptExecutor {

    public interface ExecutionCallback {
        void onStart();
        void onProgress(int stepIndex, int totalSteps);
        void onComplete();
        void onError(String message);
        /** 截图服务需要重新授权 */
        void onNeedReauthorize();
    }

    private static Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final String TAG = "ScriptExecutor";

    public static void executeScript(Context context, List<ScriptStep> steps, ExecutionCallback callback) {
        if (steps == null || steps.isEmpty()) {
            if (callback != null) callback.onError("脚本为空");
            return;
        }

        // 检查是否需要无障碍服务
        boolean needAccessibility = false;
        for (ScriptStep step : steps) {
            if (step.type == ScriptStep.TYPE_CLICK ||
                    step.type == ScriptStep.TYPE_SWIPE ||
                    step.type == ScriptStep.TYPE_BACK ||
                    step.type == ScriptStep.TYPE_HOME ||
                    step.type == ScriptStep.TYPE_TEXT ||
                    step.type == ScriptStep.TYPE_LONG_CLICK ||
                    step.type == ScriptStep.TYPE_IMAGE_CLICK) {
                needAccessibility = true;
                break;
            }
        }
        if (needAccessibility && !MyAccessibilityService.isServiceRunning()) {
            if (callback != null) callback.onError("无障碍服务未开启，请先开启");
            return;
        }

        if (callback != null) callback.onStart();

        new Thread(() -> {
            for (int i = 0; i < steps.size(); i++) {
                ScriptStep step = steps.get(i);
                Log.d(TAG, "执行步骤 " + i + "，类型: " + step.getTypeName());
                try {
                    switch (step.type) {
                        case ScriptStep.TYPE_LAUNCH_APP:
                            launchApp(context, step.packageName);
                            break;
                        case ScriptStep.TYPE_WAIT:
                            Thread.sleep(step.waitMs);
                            break;
                        case ScriptStep.TYPE_CLICK:
                            MyAccessibilityService.performClick(step.x, step.y);
                            Thread.sleep(200);
                            break;
                        case ScriptStep.TYPE_SWIPE:
                            MyAccessibilityService.performSwipe(step.x1, step.y1, step.x2, step.y2);
                            Thread.sleep(300);
                            break;
                        case ScriptStep.TYPE_BACK:
                            MyAccessibilityService.doGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                            Thread.sleep(200);
                            break;
                        case ScriptStep.TYPE_HOME:
                            MyAccessibilityService.doGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                            Thread.sleep(200);
                            break;
                        case ScriptStep.TYPE_TEXT:
                            MyAccessibilityService.inputText(step.text);
                            Thread.sleep(200);
                            break;
                        case ScriptStep.TYPE_LONG_CLICK:
                            MyAccessibilityService.performLongClick(step.x, step.y, step.longClickDuration);
                            Thread.sleep(200);
                            break;
                        // ========== OCR 文字识别点击 ==========
                        case ScriptStep.TYPE_IMAGE_CLICK:
                            // 截图前等待 500ms，确保界面稳定
                            Thread.sleep(500);

                            Log.d(TAG, "🔍 开始文字识别...");

                            // 获取目标文字
                            String targetText = step.ocrText;
                            if (targetText == null || targetText.isEmpty()) {
                                throw new Exception("未指定要识别的文字");
                            }
                            Log.d(TAG, "目标文字: [" + targetText + "]");

                            // ====== 截图（带重试） ======
                            Bitmap screen = null;
                            boolean needReauthorize = false;

                            for (int retry = 0; retry < 5; retry++) {
                                // 检查初始化状态
                                if (!ScreenCaptureHelper.isInitialized()) {
                                    Log.w(TAG, "ScreenCaptureHelper 未初始化，尝试重新初始化...");
                                    ScreenCaptureHelper.cleanup();
                                    // 通知上层重新授权
                                    if (callback != null) {
                                        mainHandler.post(callback::onNeedReauthorize);
                                    }
                                    needReauthorize = true;
                                    break;
                                }

                                try {
                                    screen = ScreenCaptureHelper.captureScreen();
                                    if (screen != null) {
                                        break;
                                    }
                                } catch (SecurityException e) {
                                    Log.e(TAG, "截图权限异常: " + e.getMessage());
                                    // 权限失效，需要重新授权
                                    if (callback != null) {
                                        mainHandler.post(callback::onNeedReauthorize);
                                    }
                                    needReauthorize = true;
                                    break;
                                } catch (Exception e) {
                                    Log.w(TAG, "截图异常，第 " + (retry + 1) + " 次: " + e.getMessage());
                                }

                                Log.w(TAG, "截图失败，第 " + (retry + 1) + " 次重试...");
                                Thread.sleep(500);
                            }

                            if (needReauthorize) {
                                throw new Exception("截图权限失效，请重新授权");
                            }

                            if (screen == null) {
                                Log.e(TAG, "❌ 截图失败");
                                showToast(context, "❌ 截图失败");
                                throw new Exception("截图失败，请检查截图权限");
                            }
                            Log.d(TAG, "截图成功，尺寸: " + screen.getWidth() + "x" + screen.getHeight());

                            // ====== OCR 识别并查找文字 ======
                            Point clickPoint = performOCRAndFindText(context, screen, targetText);

                            if (clickPoint == null) {
                                Log.d(TAG, "⏳ 首次未找到文字，开始重试...");
                                boolean found = false;
                                for (int retry = 0; retry < 3; retry++) {
                                    Thread.sleep(step.timeoutMs / 3);

                                    // 检查截图服务是否还活着
                                    if (!ScreenCaptureHelper.isInitialized()) {
                                        Log.w(TAG, "重试时截图服务已失效");
                                        if (callback != null) {
                                            mainHandler.post(callback::onNeedReauthorize);
                                        }
                                        throw new Exception("截图服务已失效，请重新授权");
                                    }

                                    // 重新截图
                                    try {
                                        screen = ScreenCaptureHelper.captureScreen();
                                    } catch (Exception e) {
                                        Log.w(TAG, "重试截图异常: " + e.getMessage());
                                        continue;
                                    }
                                    if (screen == null) {
                                        Log.w(TAG, "重试截图失败");
                                        continue;
                                    }

                                    clickPoint = performOCRAndFindText(context, screen, targetText);
                                    if (clickPoint != null) {
                                        found = true;
                                        break;
                                    }
                                }
                                if (!found) {
                                    Log.e(TAG, "❌ 未找到文字: " + targetText);
                                    showToast(context, "❌ 未找到文字: " + targetText);
                                    throw new Exception("未找到文字: " + targetText);
                                }
                            }

                            Log.d(TAG, "✅ 找到文字！坐标: (" + (int) clickPoint.x + ", " + (int) clickPoint.y + ")");
                            MyAccessibilityService.performClick((int) clickPoint.x, (int) clickPoint.y);
                            Thread.sleep(200);
                            break;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "执行步骤 " + i + " 出错", e);
                    if (callback != null) callback.onError(e.getMessage());
                    return;
                }
                if (callback != null) callback.onProgress(i + 1, steps.size());
            }
            if (callback != null) callback.onComplete();
        }).start();
    }

    // ========== OCR 辅助方法 ==========
    private static Point performOCRAndFindText(Context context, Bitmap bitmap, String targetText) {
        // 增加 null 检查
        if (bitmap == null) {
            Log.e(TAG, "performOCRAndFindText: bitmap 为 null");
            return null;
        }
        if (targetText == null || targetText.isEmpty()) {
            Log.e(TAG, "performOCRAndFindText: targetText 为空");
            return null;
        }

        try {
            // 调试：保存截图到本地
            try {
                File screenshotDir = context.getExternalFilesDir("screenshots");
                if (screenshotDir != null && !screenshotDir.exists()) {
                    screenshotDir.mkdirs();
                }
                if (screenshotDir != null) {
                    File file = new File(screenshotDir, "ocr_" + System.currentTimeMillis() + ".png");
                    FileOutputStream fos = new FileOutputStream(file);
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    fos.close();
                    Log.d(TAG, "📸 截图已保存: " + file.getAbsolutePath());
                }
            } catch (Exception e) {
                Log.e(TAG, "保存截图失败", e);
            }

            CountDownLatch latch = new CountDownLatch(1);
            final Point[] result = {null};
            final Exception[] error = {null};

            InputImage image = InputImage.fromBitmap(bitmap, 0);

            TextRecognizer recognizer = TextRecognition.getClient(
                    new ChineseTextRecognizerOptions.Builder().build()
            );

            recognizer.process(image)
                    .addOnSuccessListener(visionText -> {
                        // 打印所有识别到的文字
                        StringBuilder allText = new StringBuilder();
                        allText.append("=== OCR 识别结果 ===\n");
                        int lineCount = 0;
                        for (Text.TextBlock block : visionText.getTextBlocks()) {
                            for (Text.Line line : block.getLines()) {
                                String lineText = line.getText();
                                allText.append("  ").append(lineText).append("\n");
                                Log.d(TAG, "OCR 行: [" + lineText + "]");
                                lineCount++;
                            }
                        }
                        if (lineCount == 0) {
                            Log.d(TAG, "⚠️ OCR 未识别到任何文字");
                        }
                        Log.d(TAG, allText.toString());

                        // 去除空格后匹配，提高容错率
                        String targetClean = targetText.replaceAll("\\s+", "");

                        for (Text.TextBlock block : visionText.getTextBlocks()) {
                            for (Text.Line line : block.getLines()) {
                                String lineText = line.getText();
                                String lineClean = lineText.replaceAll("\\s+", "");

                                if (lineClean.contains(targetClean)) {
                                    android.graphics.Point[] corners = line.getCornerPoints();
                                    if (corners != null && corners.length >= 4) {
                                        int centerX = (corners[0].x + corners[2].x) / 2;
                                        int centerY = (corners[0].y + corners[2].y) / 2;
                                        result[0] = new Point(centerX, centerY);
                                        Log.d(TAG, "✅ 找到目标文字! 坐标: (" + centerX + ", " + centerY + ")");
                                        latch.countDown();
                                        return;
                                    }
                                }
                            }
                        }
                        Log.d(TAG, "❌ 未找到目标文字: [" + targetText + "]");
                        latch.countDown();
                    })
                    .addOnFailureListener(e -> {
                        error[0] = e;
                        Log.e(TAG, "OCR 识别失败", e);
                        latch.countDown();
                    });

            if (latch.await(5000, TimeUnit.MILLISECONDS)) {
                if (error[0] != null) {
                    Log.e(TAG, "OCR 识别失败", error[0]);
                    return null;
                }
                return result[0];
            } else {
                Log.e(TAG, "OCR 识别超时 (5秒)");
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "OCR 异常", e);
            return null;
        }
    }

    private static void showToast(Context context, String message) {
        mainHandler.post(() -> {
            try {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "显示 Toast 失败", e);
            }
        });
    }

    private static void launchApp(Context context, String packageName) {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
        } catch (Exception ignored) {}
    }
}