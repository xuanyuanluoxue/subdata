package com.xavier.subdata;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 模块自己的配置界面（不参与 hook）。
 * 保存白名单到 SharedPreferences，由 {@link SubDataModule} 在注入时读取。
 */
public class MainActivity extends Activity {

    private static final String PREFS_NAME = SubDataModule.PREFS_NAME;
    private static final String KEY_TARGETS = "targets";

    private EditText targetsInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        addTitle(root, "SubData 多开（数据目录隔离）");
        addBody(root,
                "原理：LSPosed 注入目标 App 进程，把 ContextImpl 的 getDataDir / getFilesDir / "
                        + "getCacheDir / getDatabasePath / getSharedPreferencesPath 等重定向到\n"
                        + "  /data/data/<包名>/subdata/<slot>/\n"
                        + "同一个 APK、同一个 uid，用不同 slot 即可拥有互不干扰的数据。");

        addTitle(root, "① 白名单（每行或用逗号分隔）");
        targetsInput = new EditText(this);
        targetsInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        targetsInput.setMinLines(2);
        targetsInput.setText(loadTargets());
        root.addView(targetsInput, matchWrap());

        Button save = new Button(this);
        save.setText("保存白名单");
        save.setOnClickListener(v -> {
            saveTargets(targetsInput.getText().toString());
            Toast.makeText(this, "已保存，重启目标 App 生效", Toast.LENGTH_SHORT).show();
        });
        root.addView(save, matchWrap());

        addTitle(root, "② 切 slot（需要 root / KernelSU，执行后 App 会被杀掉重启）");
        final String pkg = defaultPackage();
        final String cmd = "su -c 'echo b > /data/data/" + pkg + "/subdata/slot; "
                + "am force-stop " + pkg + "'";
        TextView code = addBody(root, cmd);
        code.setTextIsSelectable(true);
        code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);

        Button copy = new Button(this);
        copy.setText("复制命令");
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("subdata", cmd));
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(copy, matchWrap());

        addBody(root,
                "把 echo b 里的 b 换成你要切到的 slot 名（只允许字母数字下划线短横线）。\n"
                        + "slot 文件首次运行由模块自动创建，内容默认是 a。");

        addTitle(root, "③ 每个 App 的可选配置");
        addBody(root,
                "/data/data/<包名>/subdata/subdata.conf\n"
                        + "  enabled=true            # 关掉就是完全不重定向\n"
                        + "  clone_on_first_run=true # 新 slot 首次使用从默认目录克隆\n"
                        + "  redirect_external=true  # 连 /sdcard/Android/data/<包名> 一起重定向\n"
                        + "  hook_file_paths=true    # 兜底 hook File 构造（App 自己拼绝对路径时）\n"
                        + "  slot=a                  # 没有 slot 文件时用这个\n\n"
                        + "日志：/data/data/<包名>/subdata/subdata.log");

        addTitle(root, "④ 回滚");
        addBody(root,
                "1) LSPosed 里取消勾选本模块作用域，或直接停用模块；\n"
                        + "2) 想彻底清掉：su -c 'rm -rf /data/data/<包名>/subdata'；\n"
                        + "3) 本模块从不修改 App 的默认数据目录，取消注入后 App 立刻回到原数据。");

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private String defaultPackage() {
        String t = loadTargets();
        String first = t.split("[,;\\s]+")[0].trim();
        return first.isEmpty() ? SubDataModule.DEFAULT_TARGETS : first;
    }

    private String loadTargets() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return sp.getString(KEY_TARGETS, SubDataModule.DEFAULT_TARGETS);
    }

    private void saveTargets(String v) {
        StringBuilder sb = new StringBuilder();
        for (String s : v.split("[,;\\s]+")) {
            String t = s.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(t);
        }
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sp.edit().putString(KEY_TARGETS, sb.toString()).apply();
    }

    private TextView addTitle(LinearLayout root, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setPadding(0, dp(18), 0, dp(6));
        root.addView(tv, matchWrap());
        return tv;
    }

    private TextView addBody(LinearLayout root, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setLineSpacing(0, 1.25f);
        root.addView(tv, matchWrap());
        return tv;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
