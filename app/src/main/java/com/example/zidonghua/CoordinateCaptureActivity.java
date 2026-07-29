package com.example.zidonghua;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.TextView;

public class CoordinateCaptureActivity extends Activity {

    public static final String EXTRA_X = "extra_x";
    public static final String EXTRA_Y = "extra_y";
    public static final String EXTRA_CANCELLED = "extra_cancelled";

    private TextView tvCoords;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_coordinate_capture);

        // 全屏，无标题
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        tvCoords = findViewById(R.id.tv_coords);
        tvCoords.setText("请点击屏幕上要捕获的位置\n点击返回键取消");
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float x = event.getRawX();
            float y = event.getRawY();

            Intent result = new Intent();
            result.putExtra(EXTRA_X, (int) x);
            result.putExtra(EXTRA_Y, (int) y);
            result.putExtra(EXTRA_CANCELLED, false);
            setResult(RESULT_OK, result);
            finish();
            return true;
        }
        // 实时显示坐标（用于调试，可选）
        if (event.getAction() == MotionEvent.ACTION_MOVE) {
            float x = event.getRawX();
            float y = event.getRawY();
            tvCoords.setText("X: " + (int) x + ", Y: " + (int) y);
        }
        return super.onTouchEvent(event);
    }

    @Override
    public void onBackPressed() {
        Intent result = new Intent();
        result.putExtra(EXTRA_CANCELLED, true);
        setResult(RESULT_OK, result);
        finish();
        super.onBackPressed();
    }
}