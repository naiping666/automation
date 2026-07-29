package com.example.zidonghua;

import java.util.ArrayList;
import java.util.List;

public class ScriptStep {
    public static final int TYPE_LAUNCH_APP = 0;
    public static final int TYPE_WAIT = 1;
    public static final int TYPE_CLICK = 2;
    public static final int TYPE_SWIPE = 3;
    public static final int TYPE_BACK = 4;
    public static final int TYPE_HOME = 5;
    public static final int TYPE_TEXT = 6;
    public static final int TYPE_LONG_CLICK = 7;
    public static final int TYPE_IMAGE_CLICK = 8;  // 文字识别点击
    public static final int TYPE_IF = 9;           // ✅ 新增：条件分支

    public int type;
    public String packageName;      // TYPE_LAUNCH_APP
    public int waitMs;              // TYPE_WAIT
    public int x, y;                // TYPE_CLICK, TYPE_LONG_CLICK
    public int x1, y1, x2, y2;      // TYPE_SWIPE
    public String text;             // TYPE_TEXT
    public int longClickDuration;   // TYPE_LONG_CLICK (毫秒)

    // ========== OCR 文字识别相关字段 ==========
    public String ocrText;          // 要识别的目标文字（如："确认"、"取消"）
    public int timeoutMs = 5000;    // 超时毫秒

    // ========== ✅ 条件分支相关字段 (TYPE_IF) ==========
    public String conditionText;            // 条件文字（OCR 目标）
    public List<ScriptStep> thenSteps;      // 条件成立时执行的步骤
    public List<ScriptStep> elseSteps;      // 条件不成立时执行的步骤
    public boolean isExpanded = true;       // UI 展开状态（不持久化，仅用于内存）

    // ========== 以下为已废弃字段（仅用于兼容旧脚本，不再使用） ==========
    @Deprecated
    public String imagePath;        // 已废弃，不再使用
    @Deprecated
    public float matchThreshold = 0.8f; // 已废弃，不再使用

    public ScriptStep(int type) {
        this.type = type;
        this.longClickDuration = 500;
        // ✅ 初始化子步骤列表
        this.thenSteps = new ArrayList<>();
        this.elseSteps = new ArrayList<>();
    }

    public String getTypeName() {
        switch (type) {
            case TYPE_LAUNCH_APP: return "启动应用";
            case TYPE_WAIT: return "等待";
            case TYPE_CLICK: return "点击";
            case TYPE_SWIPE: return "滑动";
            case TYPE_BACK: return "返回键";
            case TYPE_HOME: return "Home键";
            case TYPE_TEXT: return "文本输入";
            case TYPE_LONG_CLICK: return "长按";
            case TYPE_IMAGE_CLICK: return "文字识别点击";
            case TYPE_IF: return "条件分支";
            default: return "未知";
        }
    }

    public String getDescription() {
        switch (type) {
            case TYPE_LAUNCH_APP: return "启动 " + packageName;
            case TYPE_WAIT: return "等待 " + waitMs + "ms";
            case TYPE_CLICK: return "点击 (" + x + ", " + y + ")";
            case TYPE_SWIPE: return "滑动 (" + x1 + "," + y1 + ") -> (" + x2 + "," + y2 + ")";
            case TYPE_BACK: return "返回键";
            case TYPE_HOME: return "Home键";
            case TYPE_TEXT: return "输入文字: " + text;
            case TYPE_LONG_CLICK: return "长按 (" + x + ", " + y + ") " + longClickDuration + "ms";
            case TYPE_IMAGE_CLICK:
                return "识别文字: " + (ocrText != null && !ocrText.isEmpty() ? ocrText : "未设置");
            case TYPE_IF:
                return "IF 识别到 \"" + (conditionText != null ? conditionText : "未设置") + "\"";
            default: return "未知";
        }
    }

    // ========== ✅ 辅助方法 ==========

    /**
     * 判断是否为条件步骤（包含子步骤）
     */
    public boolean isConditionStep() {
        return type == TYPE_IF;
    }

    /**
     * 获取所有子步骤（扁平化）
     */
    public List<ScriptStep> getAllSubSteps() {
        List<ScriptStep> all = new ArrayList<>();
        if (thenSteps != null) all.addAll(thenSteps);
        if (elseSteps != null) all.addAll(elseSteps);
        return all;
    }

    /**
     * 检查是否包含任何子步骤
     */
    public boolean hasSubSteps() {
        return (thenSteps != null && !thenSteps.isEmpty()) ||
                (elseSteps != null && !elseSteps.isEmpty());
    }

    /**
     * 获取子步骤总数
     */
    public int getSubStepCount() {
        int count = 0;
        if (thenSteps != null) count += thenSteps.size();
        if (elseSteps != null) count += elseSteps.size();
        return count;
    }
}