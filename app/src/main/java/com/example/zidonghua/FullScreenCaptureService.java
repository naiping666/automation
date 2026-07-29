package com.example.zidonghua;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class FullScreenCaptureService extends Service {

    private static final String TAG = "FullScreenCapture";
    private WindowManager windowManager;
    private View fullScreenView;
    private static int captureType = 1; // 1=点击, 2=起点, 3=终点, 4=长按

    public static void startCapture(Context context, int type) {
        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, "请先开启悬浮窗权限", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return;
        }
        captureType = type;
        Intent serviceIntent = new Intent(context, FullScreenCaptureService.class);
        context.startService(serviceIntent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            createFullScreenView();
        }
        return START_NOT_STICKY;
    }

    private void createFullScreenView() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // 创建一个全屏透明的 View
        fullScreenView = new View(this) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                Log.d(TAG, "onTouchEvent: action=" + event.getAction());
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    float x = event.getRawX();
                    float y = event.getRawY();

                    Log.d(TAG, "捕获坐标: (" + x + ", " + y + ")");
                    // 发送广播回传坐标
                    Intent broadcastIntent = new Intent("CAPTURE_RESULT");
                    broadcastIntent.putExtra("x", (int) x);
                    broadcastIntent.putExtra("y", (int) y);
                    broadcastIntent.putExtra("cancelled", false);
                    broadcastIntent.putExtra("capture_type", captureType);
                    LocalBroadcastManager.getInstance(FullScreenCaptureService.this).sendBroadcast(broadcastIntent);

                    String typeName = getCaptureTypeName();
                    Toast.makeText(FullScreenCaptureService.this, "已捕获" + typeName + ": (" + (int) x + ", " + (int) y + ")", Toast.LENGTH_SHORT).show();

                    removeView();
                    stopSelf();
                    return true;
                }
                // 消耗所有触摸事件，防止穿透
                return true;
            }
        };

        // 设置全屏参数
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;

        // 设置背景为半透明，让用户知道在捕获模式
        fullScreenView.setBackgroundColor(0x80000000); // 半透明黑色

        // 添加一个提示文本（可选），通过 Toast 显示
        windowManager.addView(fullScreenView, params);

        Toast.makeText(this, "点击屏幕任意位置捕获坐标", Toast.LENGTH_SHORT).show();
        Log.d(TAG, "全屏捕获已启动");
    }

    private String getCaptureTypeName() {
        switch (captureType) {
            case 1: return "点击";
            case 2: return "起点";
            case 3: return "终点";
            case 4: return "长按";
            default: return "坐标";
        }
    }

    private void removeView() {
        if (fullScreenView != null && windowManager != null) {
            try {
                windowManager.removeView(fullScreenView);
            } catch (Exception ignored) {}
            fullScreenView = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeView();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}