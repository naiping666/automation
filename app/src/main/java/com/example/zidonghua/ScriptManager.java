package com.example.zidonghua;

import android.content.Context;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class ScriptManager {
    private static final String SCRIPTS_DIR = "scripts";
    private final File scriptsDir;
    private final Gson gson = new Gson();

    public ScriptManager(Context context) {
        scriptsDir = new File(context.getFilesDir(), SCRIPTS_DIR);
        if (!scriptsDir.exists()) {
            scriptsDir.mkdirs();
        }
    }

    // 保存脚本
    public void saveScript(String name, List<ScriptStep> steps) throws IOException {
        String fileName = name + ".json";
        File file = new File(scriptsDir, fileName);
        String json = gson.toJson(steps);
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(json);
        }
    }

    // 加载脚本
    public List<ScriptStep> loadScript(String name) throws IOException {
        String fileName = name + ".json";
        File file = new File(scriptsDir, fileName);
        if (!file.exists()) return new ArrayList<>();
        Type type = new TypeToken<List<ScriptStep>>(){}.getType();
        try (FileReader reader = new FileReader(file)) {
            List<ScriptStep> steps = gson.fromJson(reader, type);
            return steps != null ? steps : new ArrayList<>();
        }
    }

    // 获取所有脚本名称
    public List<String> getScriptNames() {
        List<String> names = new ArrayList<>();
        File[] files = scriptsDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                names.add(file.getName().replace(".json", ""));
            }
        }
        return names;
    }

    // 判断脚本是否存在
    public boolean scriptExists(String name) {
        return new File(scriptsDir, name + ".json").exists();
    }

    // 删除脚本
    public boolean deleteScript(String name) {
        return new File(scriptsDir, name + ".json").delete();
    }
}