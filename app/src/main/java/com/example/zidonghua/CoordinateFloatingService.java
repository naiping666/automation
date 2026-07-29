package com.example.zidonghua;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class CoordinateFloatingService extends Service {

    private WindowManager windowManager;
    private View floatingView;
    private TextView tvCoordinates;
    private int currentX = 0, currentY = 0;
    private boolean isVertical = false;

    private static final String ACTION_CAPTURE = "ACTION_CAPTURE";
    private static int captureType = 1;

    private WindowManager.LayoutParams params;
    private LayoutInflater inflater;

    private GestureDetector gestureDetector;  // 新增

    public static void startCapture(Context context, int type) {
        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, "请先开启悬浮窗权限", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return;
        }
        captureType = type;
        Intent serviceIntent = new Intent(context, CoordinateFloatingService.class);
        serviceIntent.setAction(ACTION_CAPTURE);
        context.startService(serviceIntent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CAPTURE.equals(intent.getAction())) {
            inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            createFloatingView(isVertical);
        }
        return START_NOT_STICKY;
    }

    private void createFloatingView(boolean vertical) {
        // 清除旧 View
        if (floatingView != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception ignored) {}
            floatingView = null;
        }

        // 选择布局
        int layoutRes = vertical ? R.layout.layout_floating_vertical : R.layout.layout_floating_horizontal;
        floatingView = inflater.inflate(layoutRes, null);

        tvCoordinates = floatingView.findViewById(R.id.tv_coords);
        ImageView ivConfirm = floatingView.findViewById(R.id.iv_confirm);
        ImageView ivCancel = floatingView.findViewById(R.id.iv_cancel);
        ImageView ivRotate = floatingView.findViewById(R.id.iv_rotate);

        // 初始化位置参数
        if (params == null) {
            params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 100;
            params.y = 100;
        } else {
            params.width = WindowManager.LayoutParams.WRAP_CONTENT;
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        }

        // 初始化 GestureDetector（用于识别单击）
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                // 单击悬浮窗（非按钮区域）触发捕获
                captureCoordinate();
                return true;
            }
        });

        // 触摸监听：同时处理拖动和单击
        floatingView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean isDragging = false;
            private boolean isMoving = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // 先交给 GestureDetector 处理（仅当不是拖动时）
                // 但需要区分拖动和单击，在 ACTION_DOWN 和 ACTION_MOVE 中判断
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isDragging = false;
                        isMoving = false;
                        // 让 GestureDetector 也接收 DOWN 事件
                        gestureDetector.onTouchEvent(event);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isDragging = true;
                            isMoving = true;
                        }
                        if (isDragging) {
                            params.x = initialX + (int) dx;
                            params.y = initialY + (int) dy;
                            windowManager.updateViewLayout(floatingView, params);
                            int centerX = params.x + floatingView.getWidth() / 2;
                            int centerY = params.y + floatingView.getHeight() / 2;
                            updateCoordinates(centerX, centerY);
                        }
                        // 把事件交给 GestureDetector，以便它判断是否是单击（但 MOVE 可能触发长按等，忽略）
                        gestureDetector.onTouchEvent(event);
                        return true;
                    case MotionEvent.ACTION_UP:
                        // 如果发生了拖动，则不触发单击
                        if (isDragging) {
                            // 不处理单击
                            return true;
                        }
                        // 没有拖动，交给 GestureDetector 处理 UP
                        gestureDetector.onTouchEvent(event);
                        return true;
                    default:
                        return false;
                }
            }
        });

        // 旋转按钮
        ivRotate.setOnClickListener(v -> {
            isVertical = !isVertical;
            createFloatingView(isVertical);
            updateCoordinates(currentX, currentY);
        });

        // 确认按钮（保留）
        ivConfirm.setOnClickListener(v -> {
            captureCoordinate();
        });

        // 取消按钮
        ivCancel.setOnClickListener(v -> {
            Intent broadcastIntent = new Intent("CAPTURE_RESULT");
            broadcastIntent.putExtra("cancelled", true);
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent);
            removeFloatingView();
            stopSelf();
            Toast.makeText(this, "已取消捕获", Toast.LENGTH_SHORT).show();
        });

        windowManager.addView(floatingView, params);
        updateCoordinates(currentX, currentY);
    }

    // 提取捕获坐标方法
    private void captureCoordinate() {
        int x = params.x + floatingView.getWidth() / 2;
        int y = params.y + floatingView.getHeight() / 2;

        Intent broadcastIntent = new Intent("CAPTURE_RESULT");
        broadcastIntent.putExtra("x", x);
        broadcastIntent.putExtra("y", y);
        broadcastIntent.putExtra("cancelled", false);
        broadcastIntent.putExtra("capture_type", captureType);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent);

        String typeName = getCaptureTypeName();
        Toast.makeText(this, "已捕获" + typeName + ": (" + x + ", " + y + ")", Toast.LENGTH_SHORT).show();

        removeFloatingView();
        stopSelf();
    }

    private void updateCoordinates(int x, int y) {
        currentX = x;
        currentY = y;
        String typeName = getCaptureTypeName();
        if (tvCoordinates != null) {
            tvCoordinates.setText(typeName + ": (" + x + ", " + y + ")");
        }
    }

    private String getCaptureTypeName() {
        switch (captureType) {
            case 1: return "点击";
            case 2: return "起点";
            case 3: return "终点";
            default: return "坐标";
        }
    }

    private void removeFloatingView() {
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception ignored) {}
            floatingView = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeFloatingView();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}