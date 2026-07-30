package com.example.zidonghua;

import android.graphics.Bitmap;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OCRHelper {
    private static final String TAG = "OCRHelper";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    // 创建支持中文的识别器
    private static final TextRecognizer recognizer =
            TextRecognition.getClient(
                    new ChineseTextRecognizerOptions.Builder().build()
            );

    /**
     * 识别图片中的文字
     * @param bitmap 要识别的图片
     * @param listener 回调接口
     */
    public static void recognizeText(Bitmap bitmap, OnOCRListener listener) {
        if (bitmap == null) {
            listener.onFailure(new IllegalArgumentException("Bitmap is null"));
            return;
        }

        InputImage image = InputImage.fromBitmap(bitmap, 0);

        recognizer.process(image)
                .addOnSuccessListener(visionText -> {
                    String fullText = visionText.getText();
                    Log.d(TAG, "识别成功，共 " + visionText.getTextBlocks().size() + " 个文本块");

                    StringBuilder detail = new StringBuilder();
                    for (Text.TextBlock block : visionText.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            detail.append(line.getText()).append("\n");
                        }
                    }

                    listener.onSuccess(fullText, detail.toString());
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "识别失败", e);
                    listener.onFailure(e);
                });
    }

    /**
     * 识别指定区域的文字（通过坐标）
     * @param bitmap 原图
     * @param left 左边界
     * @param top 上边界
     * @param right 右边界
     * @param bottom 下边界
     */
    public static void recognizeTextInRegion(Bitmap bitmap, int left, int top, int right, int bottom, OnOCRListener listener) {
        if (bitmap == null) {
            listener.onFailure(new IllegalArgumentException("Bitmap is null"));
            return;
        }
        Bitmap cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top);
        recognizeText(cropped, listener);
    }

    public interface OnOCRListener {
        void onSuccess(String fullText, String detailText);
        void onFailure(Exception e);
    }
}