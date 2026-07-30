package com.example.zidonghua;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;

import java.nio.ByteBuffer;

public class ScreenCaptureHelper {
    private static final String TAG = "ScreenCaptureHelper";
    private static MediaProjection mediaProjection;
    private static ImageReader imageReader;
    private static HandlerThread backgroundThread;
    private static Handler backgroundHandler;
    private static boolean initialized = false;
    private static final Object lock = new Object();

    public static void init(MediaProjection projection, int width, int height) {
        synchronized (lock) {
            if (initialized) {
                cleanup();
            }
            mediaProjection = projection;

            // 创建后台线程和 Handler
            backgroundThread = new HandlerThread("ScreenCapture");
            backgroundThread.start();
            backgroundHandler = new Handler(backgroundThread.getLooper());

            // 注册 MediaProjection 回调
            if (mediaProjection != null) {
                mediaProjection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        Log.d(TAG, "MediaProjection stopped by system, cleaning up...");
                        cleanup();
                    }
                }, backgroundHandler);
            }

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            initialized = true;
            Log.d(TAG, "ScreenCaptureHelper initialized, width=" + width + ", height=" + height);
            Log.d(TAG, "MediaProjection 对象: " + (mediaProjection != null ? "有效" : ""));
        }
    }

    // 修改为抛出 SecurityException，便于调用层捕获权限失效
    public static Bitmap captureScreen() throws SecurityException {
        if (!initialized || mediaProjection == null || imageReader == null) {
            Log.e(TAG, "ScreenCaptureHelper not initialized");
            return null;
        }

        VirtualDisplay virtualDisplay = null;
        Image image = null;
        try {
            VirtualDisplay.Callback vdCallback = new VirtualDisplay.Callback() {
                @Override
                public void onPaused() {}
                @Override
                public void onResumed() {}
                @Override
                public void onStopped() {}
            };

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenCapture",
                    imageReader.getWidth(),
                    imageReader.getHeight(),
                    DisplayMetrics.DENSITY_DEFAULT,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    vdCallback,
                    backgroundHandler
            );

            if (virtualDisplay == null) {
                Log.e(TAG, "VirtualDisplay creation failed");
                return null;
            }

            // 等待图像（最多 500ms）
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < 500) {
                image = imageReader.acquireLatestImage();
                if (image != null) break;
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {}
            }

            if (image == null) {
                Log.e(TAG, "Failed to acquire image");
                virtualDisplay.release();
                return null;
            }

            Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length == 0) {
                Log.e(TAG, "Image planes is empty");
                image.close();
                virtualDisplay.release();
                return null;
            }

            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int width = imageReader.getWidth();
            int height = imageReader.getHeight();
            int rowPadding = rowStride - pixelStride * width;

            Bitmap bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
            );
            bitmap.copyPixelsFromBuffer(buffer);

            if (rowPadding > 0) {
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height);
            }

            image.close();
            virtualDisplay.release();

            Log.d(TAG, "Screenshot captured successfully, size=" + width + "x" + height);
            return bitmap;

        } catch (SecurityException e) {
            // 截图权限失效，先释放 VirtualDisplay 再清理全局资源
            Log.e(TAG, "截图权限失效: " + e.getMessage(), e);
            if (image != null) image.close();
            if (virtualDisplay != null) virtualDisplay.release();
            cleanup();
            throw e;
        } catch (Exception e) {
            Log.e(TAG, "截图失败: " + e.getMessage(), e);
            if (image != null) image.close();
            if (virtualDisplay != null) virtualDisplay.release();
            return null;
        }
    }

    public static void cleanup() {
        synchronized (lock) {
            if (mediaProjection != null) {
                mediaProjection.stop();
                mediaProjection = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            if (backgroundThread != null) {
                backgroundThread.quitSafely();
                backgroundThread = null;
                backgroundHandler = null;
            }
            initialized = false;
            Log.d(TAG, "ScreenCaptureHelper cleaned up");
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }
}