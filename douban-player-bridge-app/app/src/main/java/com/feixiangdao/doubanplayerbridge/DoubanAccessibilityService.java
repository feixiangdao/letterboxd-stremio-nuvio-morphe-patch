package com.feixiangdao.doubanplayerbridge;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DoubanAccessibilityService extends AccessibilityService {
    private static final String TAG = "DoubanPlayerBridge";
    private static final String DOUBAN_PACKAGE = "com.douban.frodo";

    private static final Pattern YEAR =
            Pattern.compile("(?:\\(|（)?\\b((?:18|19|20)\\d{2})\\b(?:\\)|）)?");

    private static final Set<String> EXACT_REJECT = new HashSet<>();
    static {
        Collections.addAll(EXACT_REJECT,
                "豆瓣", "首页", "书影音", "广播", "小组", "市集", "我的",
                "想看", "看过", "短评", "影评", "讨论", "简介", "演职员",
                "预告片", "剧照", "评分", "更多", "全部", "展开", "收起",
                "分享", "写短评", "写影评", "举报", "Stremio", "Nuvio");
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private WindowManager windowManager;
    private View overlay;
    private TextView stremioButton;
    private TextView nuvioButton;
    private MediaInfo currentInfo;
    private String lastDoubanWindowClass;

    private final Runnable scanRunnable = this::scanCurrentWindow;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        Log.d(TAG, "Accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        CharSequence pkgCs = event.getPackageName();
        String pkg = pkgCs == null ? "" : pkgCs.toString();

        if (!DOUBAN_PACKAGE.equals(pkg)) {
            hideOverlay();
            return;
        }

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence cls = event.getClassName();
            if (cls != null) {
                lastDoubanWindowClass = cls.toString();
            }
        }

        main.removeCallbacks(scanRunnable);
        main.postDelayed(scanRunnable, 280);
    }

    @Override
    public void onInterrupt() {
        hideOverlay();
    }

    @Override
    public void onDestroy() {
        hideOverlay();
        worker.shutdownNow();
        super.onDestroy();
    }

    private void scanCurrentWindow() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            hideOverlay();
            return;
        }

        List<NodeText> entries = new ArrayList<>();
        int[] counter = new int[]{0};
        collect(root, entries, counter, 0);

        if (!isMovieDetail(entries)) {
            hideOverlay();
            return;
        }

        MediaInfo info = extractMediaInfo(entries);
        if (info == null || TextUtils.isEmpty(info.title)) {
            hideOverlay();
            return;
        }

        currentInfo = info;
        showOrUpdateOverlay(info);
    }

    private boolean isMovieDetail(List<NodeText> entries) {
        boolean want = false;
        boolean seen = false;
        int markers = 0;

        for (NodeText entry : entries) {
            String t = entry.text;
            if ("想看".equals(t)) want = true;
            if ("看过".equals(t)) seen = true;

            if ("短评".equals(t) || "影评".equals(t) || "演职员".equals(t) ||
                    "预告片".equals(t) || "剧照".equals(t) || "简介".equals(t)) {
                markers++;
            }
        }

        boolean classMatch = lastDoubanWindowClass != null &&
                (lastDoubanWindowClass.contains("MovieActivity2") ||
                 lastDoubanWindowClass.contains(".subject."));

        return (want && seen && markers >= 1) || classMatch;
    }

    private MediaInfo extractMediaInfo(List<NodeText> entries) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenHeight = dm.heightPixels;

        NodeText best = null;
        int bestScore = Integer.MIN_VALUE;

        for (NodeText entry : entries) {
            String cleaned = cleanTitle(entry.text);
            if (!looksLikeTitle(cleaned)) continue;

            int score = 0;
            String id = entry.viewId == null ? "" :
                    entry.viewId.toLowerCase(Locale.US);

            if (id.contains("title")) score += 180;
            if (id.contains("subject")) score += 60;
            if (id.contains("name")) score += 35;

            int y = entry.bounds.centerY();
            int h = entry.bounds.height();

            if (y > 0 && y < screenHeight * 0.50) score += 45;
            if (y > 0 && y < screenHeight * 0.32) score += 25;

            score += Math.min(60, Math.max(0, h));

            int len = cleaned.length();
            if (len >= 2 && len <= 40) score += 25;
            if (len > 70) score -= 60;

            if (entry.text.contains("评分") || entry.text.contains("人评价") ||
                    entry.text.contains("条短评")) {
                score -= 150;
            }

            if (score > bestScore) {
                bestScore = score;
                best = entry;
            }
        }

        if (best == null) return null;

        MediaInfo info = new MediaInfo();
        info.title = cleanTitle(best.text);
        info.year = parseYear(best.text);

        // Add nearby upper-page strings as alternate search titles. Douban
        // often exposes the original/foreign title as a second TextView.
        List<NodeText> nearby = new ArrayList<>();
        for (NodeText entry : entries) {
            int y = entry.bounds.centerY();
            if (y <= 0 || y > screenHeight * 0.55) continue;
            String s = cleanTitle(entry.text);
            if (!looksLikeTitle(s)) continue;
            if (s.equals(info.title)) continue;
            nearby.add(entry);

            if (info.year == null) {
                Integer yv = parseYear(entry.text);
                if (yv != null) info.year = yv;
            }
        }

        nearby.sort(Comparator.comparingInt(a ->
                Math.abs(a.bounds.centerY() - best.bounds.centerY())));

        for (NodeText e : nearby) {
            String alias = cleanTitle(e.text);
            if (!info.aliases.contains(alias)) {
                info.aliases.add(alias);
            }
            if (info.aliases.size() >= 4) break;
        }

        // If year is still unknown, look through the upper half first, then all.
        if (info.year == null) {
            for (NodeText entry : entries) {
                if (entry.bounds.centerY() > screenHeight * 0.65) continue;
                Integer y = parseYear(entry.text);
                if (y != null) {
                    info.year = y;
                    break;
                }
            }
        }

        boolean series = false;
        for (NodeText entry : entries) {
            String t = entry.text;
            if (t.contains("集数") || t.contains("单集片长") ||
                    t.contains("季数") || t.contains("电视剧") ||
                    t.contains("剧集")) {
                series = true;
                break;
            }
        }
        info.preferredType = series ? "series" : "movie";
        return info;
    }

    private void collect(
            AccessibilityNodeInfo node,
            List<NodeText> out,
            int[] counter,
            int depth
    ) {
        if (node == null || counter[0] >= 650 || depth > 45) return;
        counter[0]++;

        String viewId = null;
        try {
            viewId = node.getViewIdResourceName();
        } catch (Throwable ignored) {}

        CharSequence text = node.getText();
        if (text != null) {
            addNodeText(text.toString(), viewId, node, out);
        }

        CharSequence desc = node.getContentDescription();
        if (desc != null && (text == null ||
                !desc.toString().equals(text.toString()))) {
            addNodeText(desc.toString(), viewId, node, out);
        }

        int childCount = Math.min(node.getChildCount(), 80);
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
                collect(child, out, counter, depth + 1);
            } catch (Throwable ignored) {
            } finally {
                if (child != null) {
                    try {
                        child.recycle();
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    private void addNodeText(
            String raw,
            String viewId,
            AccessibilityNodeInfo node,
            List<NodeText> out
    ) {
        if (raw == null) return;
        String text = raw.trim().replaceAll("\\s+", " ");
        if (text.isEmpty() || text.length() > 220) return;

        Rect bounds = new Rect();
        try {
            node.getBoundsInScreen(bounds);
        } catch (Throwable ignored) {}

        out.add(new NodeText(text, viewId, bounds));
    }

    private static boolean looksLikeTitle(String raw) {
        if (TextUtils.isEmpty(raw)) return false;
        String s = raw.trim();
        if (s.length() < 1 || s.length() > 120) return false;
        if (EXACT_REJECT.contains(s)) return false;
        if (s.startsWith("http://") || s.startsWith("https://") ||
                s.startsWith("douban://")) return false;
        if (s.matches("[0-9.,%分]+")) return false;
        if (s.startsWith("豆瓣评分") || s.startsWith("评分") ||
                s.contains("人看过") || s.contains("人想看")) return false;
        return true;
    }

    private static String cleanTitle(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replaceAll("\\s+", " ");
        s = s.replaceAll("\\s*[（(](?:18|19|20)\\d{2}[）)]\\s*$", "");
        s = s.replaceAll("\\s+(?:18|19|20)\\d{2}\\s*$", "");
        return s.trim();
    }

    private static Integer parseYear(String raw) {
        if (TextUtils.isEmpty(raw)) return null;
        Matcher m = YEAR.matcher(raw);
        if (!m.find()) return null;
        try {
            int year = Integer.parseInt(m.group(1));
            return year >= 1800 && year <= 2100 ? year : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void showOrUpdateOverlay(MediaInfo info) {
        if (windowManager == null) return;

        if (overlay == null) {
            overlay = buildOverlay();
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
            );
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(86);

            try {
                windowManager.addView(overlay, lp);
            } catch (Throwable error) {
                Log.w(TAG, "Failed to add accessibility overlay", error);
                overlay = null;
            }
        }
    }

    private View buildOverlay() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(7), dp(6), dp(7), dp(6));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEFFFFFFF);
        bg.setCornerRadius(dp(28));
        bg.setStroke(dp(1), 0x22000000);
        bar.setBackground(bg);
        bar.setElevation(dp(9));

        stremioButton = makeButton("Stremio", 0xFF7B5EA7);
        nuvioButton = makeButton("Nuvio", 0xFF25282D);

        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(
                dp(104), dp(44));
        first.rightMargin = dp(7);
        bar.addView(stremioButton, first);
        bar.addView(nuvioButton,
                new LinearLayout.LayoutParams(dp(92), dp(44)));

        stremioButton.setOnClickListener(v -> resolveAndOpen(false));
        nuvioButton.setOnClickListener(v -> resolveAndOpen(true));

        View.OnLongClickListener debug = v -> {
            MediaInfo info = currentInfo;
            Toast.makeText(
                    this,
                    info == null ? "尚未识别影片" : info.debugText(),
                    Toast.LENGTH_LONG
            ).show();
            return true;
        };
        stremioButton.setOnLongClickListener(debug);
        nuvioButton.setOnLongClickListener(debug);

        return bar;
    }

    private TextView makeButton(String label, int color) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setTextColor(Color.WHITE);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(22));
        v.setBackground(bg);
        return v;
    }

    private void resolveAndOpen(boolean nuvio) {
        MediaInfo info = currentInfo;
        if (info == null || TextUtils.isEmpty(info.title)) {
            Toast.makeText(this, "尚未识别当前影片", Toast.LENGTH_SHORT).show();
            return;
        }

        CinemetaResolver.Result cached = readCache(info);
        if (cached != null) {
            openResolved(cached, nuvio, info);
            return;
        }

        Toast.makeText(this, "正在匹配 IMDb…", Toast.LENGTH_SHORT).show();

        worker.execute(() -> {
            CinemetaResolver.Result result = null;
            try {
                result = CinemetaResolver.resolve(info);
            } catch (Throwable error) {
                Log.w(TAG, "Cinemeta resolve failed", error);
            }

            CinemetaResolver.Result finalResult = result;
            main.post(() -> {
                if (finalResult != null) {
                    writeCache(info, finalResult);
                    openResolved(finalResult, nuvio, info);
                } else if (!nuvio) {
                    openStremioSearch(info);
                } else {
                    Toast.makeText(
                            this,
                            "没有可靠匹配到 IMDb。长按按钮查看识别结果。",
                            Toast.LENGTH_LONG
                    ).show();
                }
            });
        });
    }

    private void openResolved(
            CinemetaResolver.Result result,
            boolean nuvio,
            MediaInfo info
    ) {
        String type = "series".equals(result.type) ? "series" : "movie";
        if (nuvio) {
            launch("nuvio://" + type + "/" + result.imdbId,
                    "没有检测到 Nuvio");
        } else if ("series".equals(type)) {
            launch("stremio:///detail/series/" + result.imdbId,
                    "没有检测到 Stremio");
        } else {
            launch("stremio:///detail/movie/" + result.imdbId +
                            "/" + result.imdbId,
                    "没有检测到 Stremio");
        }
    }

    private void openStremioSearch(MediaInfo info) {
        try {
            String query = info.title +
                    (info.year == null ? "" : " " + info.year);
            String encoded = URLEncoder.encode(query, "UTF-8")
                    .replace("+", "%20");
            launch("stremio:///search?search=" + encoded,
                    "没有检测到 Stremio");
        } catch (Exception e) {
            Toast.makeText(this, "无法生成 Stremio 搜索链接",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void launch(String uri, String errorMessage) {
        hideOverlay();
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
        }
    }

    private CinemetaResolver.Result readCache(MediaInfo info) {
        String raw = prefs().getString("r:" + info.cacheKey(), null);
        if (raw == null) return null;
        String[] parts = raw.split("\\|", 4);
        if (parts.length < 2 || !parts[0].matches("tt\\d{5,12}")) {
            return null;
        }
        Integer year = null;
        if (parts.length >= 4 && !parts[3].isEmpty()) {
            try { year = Integer.valueOf(parts[3]); } catch (Exception ignored) {}
        }
        return new CinemetaResolver.Result(
                parts[0],
                parts[1],
                parts.length >= 3 ? parts[2] : "",
                year
        );
    }

    private void writeCache(MediaInfo info, CinemetaResolver.Result result) {
        prefs().edit().putString(
                "r:" + info.cacheKey(),
                result.imdbId + "|" + result.type + "|" +
                        (result.name == null ? "" : result.name) + "|" +
                        (result.year == null ? "" : result.year)
        ).apply();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(
                "douban_player_bridge_cache",
                Context.MODE_PRIVATE);
    }

    private void hideOverlay() {
        main.removeCallbacks(scanRunnable);
        if (overlay != null && windowManager != null) {
            try {
                windowManager.removeView(overlay);
            } catch (Throwable ignored) {
            }
        }
        overlay = null;
        stremioButton = null;
        nuvioButton = null;
        currentInfo = null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class NodeText {
        final String text;
        final String viewId;
        final Rect bounds;

        NodeText(String text, String viewId, Rect bounds) {
            this.text = text;
            this.viewId = viewId;
            this.bounds = bounds;
        }
    }
}
