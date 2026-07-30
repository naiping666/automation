package com.example.zidonghua;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.LinkedList;
import java.util.Queue;

public class MyAccessibilityService extends AccessibilityService {

    private static MyAccessibilityService instance;
    private static volatile boolean isConnected = false;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        isConnected = true;
    }

    @Override
    public void onDestroy() {
        instance = null;
        isConnected = false;
        super.onDestroy();
    }

    public static boolean isServiceRunning() {
        return isConnected && instance != null;
    }

    // ---------- 点击 ----------
    public static void performClick(int x, int y) throws Exception {
        if (!isServiceRunning()) throw new Exception("无障碍服务未运行");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 1));
            instance.dispatchGesture(builder.build(), null, null);
        } else {
            throw new Exception("系统版本低于 Android N，不支持手势模拟");
        }
    }

    // ---------- 长按 ----------
    public static void performLongClick(int x, int y, int durationMs) throws Exception {
        if (!isServiceRunning()) throw new Exception("无障碍服务未运行");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs));
            instance.dispatchGesture(builder.build(), null, null);
        } else {
            throw new Exception("系统版本低于 Android N，不支持手势模拟");
        }
    }

    // ---------- 滑动 ----------
    public static void performSwipe(int startX, int startY, int endX, int endY) throws Exception {
        if (!isServiceRunning()) throw new Exception("无障碍服务未运行");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path path = new Path();
            path.moveTo(startX, startY);
            path.lineTo(endX, endY);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 300));
            instance.dispatchGesture(builder.build(), null, null);
        } else {
            throw new Exception("系统版本低于 Android N，不支持手势模拟");
        }
    }

    // ---------- 全局动作 ----------
    public static void doGlobalAction(int action) throws Exception {
        if (!isServiceRunning()) throw new Exception("无障碍服务未运行");
        instance.performGlobalAction(action);
    }

    // ---------- 文本输入 ----------
    public static void inputText(String text) throws Exception {
        if (!isServiceRunning()) throw new Exception("无障碍服务未运行");
        if (text == null) throw new Exception("输入文本不能为空");

        Log.d("MyAccessibility", "inputText 开始，目标文字: " + text);

        AccessibilityNodeInfo target = null;

        // 1. 先尝试获取当前焦点节点
        target = instance.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        Log.d("MyAccessibility", "findFocus 结果: " + (target != null ? "找到节点，className=" + target.getClassName() : ""));

        // 2. 如果焦点节点无效，则从根节点查找
        if (target == null || target.getClassName() == null || !target.isEditable()) {
            if (target != null) {
                Log.d("MyAccessibility", "回收无效焦点节点");
                target.recycle();
                target = null;
            }
            Log.d("MyAccessibility", "尝试获取根节点...");
            AccessibilityNodeInfo root = instance.getRootInActiveWindow();
            Log.d("MyAccessibility", "root 结果: " + (root != null ? "获取成功" : " (可能当前窗口无焦点或权限不足)"));
            if (root != null) {
                // 打印节点树信息（调试用，只打印前2层）
                debugNodeTree(root, 0);
                // 查找真正的输入框
                target = findRealInputNode(root);
                root.recycle();
            } else {
                Log.e("MyAccessibility", "无法获取根节点，请确保无障碍服务已开启且当前应用有可访问节点");
            }
        }

        if (target == null) {
            throw new Exception("未找到输入框，请确保已点击输入框使其获得焦点");
        }

        Log.d("MyAccessibility", "最终目标节点: className=" + target.getClassName()
                + ", isFocused=" + target.isFocused()
                + ", isEditable=" + target.isEditable());

        // 优先 SET_TEXT
        if (target.isEditable()) {
            if (!target.isFocused()) {
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                Thread.sleep(100);
            }
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            boolean success = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            if (success) {
                target.recycle();
                Log.d("MyAccessibility", "SET_TEXT 成功");
                return;
            } else {
                Log.w("MyAccessibility", "SET_TEXT 失败，尝试粘贴");
            }
        }

        // 降级：粘贴
        Log.d("MyAccessibility", "使用剪贴板粘贴方式");
        if (!target.isFocused()) {
            boolean focused = target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            if (!focused) {
                focused = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            if (!focused) {
                Rect bounds = new Rect();
                target.getBoundsInScreen(bounds);
                if (!bounds.isEmpty()) {
                    performClick(bounds.centerX(), bounds.centerY());
                    Thread.sleep(200);
                } else {
                    target.recycle();
                    throw new Exception("无法让输入框获得焦点");
                }
            } else {
                Thread.sleep(100);
            }
        }

        // 复制到剪贴板
        ClipboardManager clipboard = (ClipboardManager) instance.getSystemService(Context.CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("script_text", text);
        clipboard.setPrimaryClip(clip);
        Log.d("MyAccessibility", "已复制到剪贴板");

        boolean pasted = target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        if (!pasted) {
            Rect bounds = new Rect();
            target.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) {
                performLongClick(bounds.centerX(), bounds.centerY(), 600);
                Thread.sleep(300);
                pasted = target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            }
        }
        target.recycle();

        if (!pasted) {
            throw new Exception("粘贴失败，输入框可能不支持粘贴操作");
        }
        Log.d("MyAccessibility", "粘贴成功");
    }

    // ---------- 调试：按层级打印节点树（最多3层） ----------
    private static void debugNodeTree(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 3) return;
        CharSequence className = node.getClassName();
        if (className != null) {
            Log.d("MyAccessibility", "节点[" + depth + "] className=" + className
                    + ", isEditable=" + node.isEditable()
                    + ", isFocusable=" + node.isFocusable()
                    + ", text=" + node.getText());
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                debugNodeTree(child, depth + 1);
                child.recycle();
            }
        }
    }

    // ---------- 查找真正的输入节点（宽松匹配） ----------
    private static AccessibilityNodeInfo findRealInputNode(AccessibilityNodeInfo root) {
        if (root == null) return null;
        Queue<AccessibilityNodeInfo> queue = new LinkedList<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            CharSequence className = node.getClassName();
            boolean isEdit = className != null && className.toString().toLowerCase().contains("edit");
            boolean isInput = className != null && className.toString().toLowerCase().contains("input");
            if (node.isEditable() || isEdit || isInput) {
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
        return null;
    }
}