package com.feixiangdao.doubanplayerbridge;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(42), dp(24), dp(32));
        scroll.addView(root);

        TextView title = text("Douban Player Bridge", 28, true);
        root.addView(title);

        TextView subtitle = text(
                "不修改豆瓣 APK。进入豆瓣影视详情页时，通过无障碍服务识别标题和年份，并显示 Stremio / Nuvio 两个快捷按钮。",
                16, false);
        subtitle.setPadding(0, dp(12), 0, dp(24));
        root.addView(subtitle);

        statusView = text("", 16, true);
        statusView.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.addView(statusView, matchWrap());

        Button accessibility = button("1. 开启无障碍服务");
        accessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, matchWrapWithTop(20));

        Button openDouban = button("2. 打开豆瓣");
        openDouban.setOnClickListener(v -> openDouban());
        root.addView(openDouban, matchWrapWithTop(12));

        TextView instructions = text(
                "使用方法\n\n" +
                "① 开启“豆瓣播放器桥接”无障碍服务。\n" +
                "② 正常打开官方豆瓣 App。\n" +
                "③ 进入电影或电视剧详情页。\n" +
                "④ 页面底部会出现 Stremio / Nuvio 按钮。\n" +
                "⑤ 长按任意按钮可以查看当前识别到的标题、年份和类型。\n\n" +
                "隐私说明：服务会接收窗口切换事件，但只在包名为 com.douban.frodo 时读取页面节点；其他 App 的页面内容不会被读取或上传。",
                15, false);
        instructions.setPadding(0, dp(28), 0, 0);
        root.addView(instructions);

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean enabled = isServiceEnabled();
        statusView.setText(enabled ? "状态：已开启 ✓" : "状态：尚未开启");
        statusView.setTextColor(enabled ? Color.rgb(0, 140, 60) : Color.rgb(180, 45, 45));
        statusView.setBackgroundColor(enabled ? 0xFFE7F7EC : 0xFFFFEEEE);
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;

        ComponentName component = new ComponentName(
                this, DoubanAccessibilityService.class);
        String full = component.flattenToString();
        String shortName = component.flattenToShortString();

        for (String item : enabled.split(":")) {
            if (full.equalsIgnoreCase(item) ||
                    shortName.equalsIgnoreCase(item)) {
                return true;
            }
        }
        return false;
    }

    private void openDouban() {
        Intent launch = getPackageManager()
                .getLaunchIntentForPackage("com.douban.frodo");
        if (launch != null) {
            startActivity(launch);
            return;
        }
        try {
            startActivity(new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=com.douban.frodo")));
        } catch (Exception ignored) {
        }
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(Color.rgb(32, 32, 32));
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setLineSpacing(0, 1.15f);
        return tv;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextSize(16);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapWithTop(int topDp) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(topDp);
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
