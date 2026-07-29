package com.example.zidonghua;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;

public class RootModeActivity extends AppCompatActivity {

    private TextView tvRootStatus, tvOutput;
    private EditText etCommand;
    private Button btnExecute;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_root_mode);

        tvRootStatus = findViewById(R.id.tv_root_status);
        tvOutput = findViewById(R.id.tv_output);
        etCommand = findViewById(R.id.et_command);
        btnExecute = findViewById(R.id.btn_execute);

        // 返回按钮
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        // 检查 Root 状态
        checkRootStatus();

        // 执行命令
        btnExecute.setOnClickListener(v -> executeCommand());
    }

    private void checkRootStatus() {
        if (isRooted()) {
            tvRootStatus.setText("已授予 ✅");
            tvRootStatus.setTextColor(getColor(android.R.color.holo_green_dark));
        } else {
            tvRootStatus.setText("未授予 ❌");
            tvRootStatus.setTextColor(getColor(android.R.color.holo_red_dark));
        }
    }

    private boolean isRooted() {
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("exit\n");
            os.flush();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void executeCommand() {
        String command = etCommand.getText().toString().trim();
        if (command.isEmpty()) {
            Toast.makeText(this, "请输入命令", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!isRooted()) {
            tvOutput.setText("错误：设备未 Root，无法执行命令");
            return;
        }

        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append("\n");
            }
            process.waitFor();

            if (result.length() == 0) {
                result.append("命令执行成功（无输出）");
            }
            tvOutput.setText(result.toString());
        } catch (Exception e) {
            tvOutput.setText("执行失败：" + e.getMessage());
        }
    }
}
