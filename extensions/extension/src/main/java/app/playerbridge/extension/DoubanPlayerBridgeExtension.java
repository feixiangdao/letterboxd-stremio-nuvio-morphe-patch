package app.playerbridge.extension;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runtime-only Douban bridge.
 *
 * Douban 7.135.0 ships behind the NetEase NIS shell. The real subject classes
 * do not exist in the APK dex table until runtime, so this extension is called
 * by the wrapper Instrumentation and operates on the live Activity object.
 */
public final class DoubanPlayerBridgeExtension {
    private static final String TAG = "DoubanPlayerBridge";

    private static final String MOVIE_ACTIVITY =
            "com.douban.frodo.subject.struct2.MovieActivity2";

    private static final String CONTAINER_TAG = "douban_player_bridge_v1";

    private static final int STREMIO_COLOR = 0xFF7B5EA7;
    private static final int NUVIO_COLOR = 0xFF25282D;

    private static final Pattern DEEP_LINK_PATTERN = Pattern.compile(
            "(?:douban://douban\\.com/)?(movie|tv)/(\\d+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SUBJECT_URL_PATTERN = Pattern.compile(
            "(?:movie\\.douban\\.com/subject/|subject/)(\\d+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern IMDB_PATTERN =
            Pattern.compile("\\btt\\d{5,12}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR_PATTERN =
            Pattern.compile("\\b(18|19|20)\\d{2}\\b");

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService NETWORK =
            Executors.newSingleThreadExecutor();

    private DoubanPlayerBridgeExtension() {}

    /**
     * Injected into NetEase NIS InstrumentationProxy.callActivityOnCreate(...).
     */
    public static void onActivityCreated(Activity activity) {
        if (activity == null) return;

        final String className = activity.getClass().getName();
        if (!MOVIE_ACTIVITY.equals(className)) return;

        // The activity has only just entered creation. Post several attempts so
        // the overlay survives Douban's asynchronous view inflation/rebinding.
        scheduleAttach(activity, 350);
        scheduleAttach(activity, 1000);
        scheduleAttach(activity, 2200);
    }

    private static void scheduleAttach(final Activity activity, long delayMs) {
        MAIN.postDelayed(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            attachButtons(activity);
        }, delayMs);
    }

    private static void attachButtons(final Activity activity) {
        View rootView = activity.findViewById(android.R.id.content);
        if (!(rootView instanceof FrameLayout)) return;

        FrameLayout root = (FrameLayout) rootView;
        if (root.findViewWithTag(CONTAINER_TAG) != null) return;

        final LinearLayout bar = new LinearLayout(activity);
        bar.setTag(CONTAINER_TAG);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(activity, 8), dp(activity, 6),
                dp(activity, 8), dp(activity, 6));

        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(0xCCFFFFFF);
        barBg.setCornerRadius(dp(activity, 28));
        bar.setBackground(barBg);
        bar.setElevation(dp(activity, 8));

        TextView stremio = makeButton(
                activity, "Stremio", STREMIO_COLOR, "Open in Stremio");
        TextView nuvio = makeButton(
                activity, "Nuvio", NUVIO_COLOR, "Open in Nuvio");

        LinearLayout.LayoutParams firstLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(activity, 42)
        );
        firstLp.rightMargin = dp(activity, 8);
        stremio.setLayoutParams(firstLp);

        nuvio.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(activity, 42)
        ));

        bar.addView(stremio);
        bar.addView(nuvio);

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        barLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        barLp.bottomMargin = dp(activity, 78);
        root.addView(bar, barLp);

        stremio.setOnClickListener(v -> resolveAndOpen(activity, Target.STREMIO));
        nuvio.setOnClickListener(v -> resolveAndOpen(activity, Target.NUVIO));

        View.OnLongClickListener debugListener = v -> {
            SubjectInfo info = extractSubjectInfo(activity);
            Toast.makeText(
                    activity,
                    info.debugSummary(),
                    Toast.LENGTH_LONG
            ).show();
            return true;
        };
        stremio.setOnLongClickListener(debugListener);
        nuvio.setOnLongClickListener(debugListener);

        Log.d(TAG, "Player buttons attached to " + activity.getClass().getName());
    }

    private static TextView makeButton(
            Context context,
            String text,
            int backgroundColor,
            String contentDescription
    ) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setGravity(Gravity.CENTER);
        view.setMinWidth(dp(context, 92));
        view.setPadding(dp(context, 18), 0, dp(context, 18), 0);
        view.setContentDescription(contentDescription);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(backgroundColor);
        bg.setCornerRadius(dp(context, 22));
        view.setBackground(bg);
        return view;
    }

    private static void resolveAndOpen(
            final Activity activity,
            final Target target
    ) {
        final SubjectInfo info = extractSubjectInfo(activity);

        if (TextUtils.isEmpty(info.title) &&
                TextUtils.isEmpty(info.originalTitle) &&
                info.alternateTitles.isEmpty()) {
            Toast.makeText(
                    activity,
                    "暂时无法读取当前影片标题，长按按钮可查看识别信息",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        Resolved cached = readCache(activity, info);
        if (cached != null) {
            openResolved(activity, target, cached, info);
            return;
        }

        Toast.makeText(activity, "正在匹配 IMDb…", Toast.LENGTH_SHORT).show();

        NETWORK.execute(() -> {
            Resolved resolved = null;
            try {
                resolved = resolveWithCinemeta(info);
            } catch (Throwable error) {
                Log.w(TAG, "Cinemeta resolve failed", error);
            }

            final Resolved result = resolved;
            MAIN.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;

                if (result != null) {
                    writeCache(activity, info, result);
                    openResolved(activity, target, result, info);
                } else if (target == Target.STREMIO) {
                    openStremioSearch(activity, info);
                } else {
                    Toast.makeText(
                            activity,
                            "没有可靠匹配到 IMDb。长按按钮可查看豆瓣识别信息。",
                            Toast.LENGTH_LONG
                    ).show();
                }
            });
        });
    }

    private static void openResolved(
            Activity activity,
            Target target,
            Resolved resolved,
            SubjectInfo info
    ) {
        if (target == Target.STREMIO) {
            String uri;
            if ("series".equals(resolved.type)) {
                uri = "stremio:///detail/series/" + resolved.imdbId;
            } else {
                uri = "stremio:///detail/movie/" + resolved.imdbId +
                        "/" + resolved.imdbId;
            }
            launch(activity, uri,
                    "没有检测到可处理 Stremio 链接的应用");
            return;
        }

        String nuvioType = "series".equals(resolved.type)
                ? "series" : "movie";
        launch(
                activity,
                "nuvio://" + nuvioType + "/" + resolved.imdbId,
                "没有检测到可处理 Nuvio 链接的应用"
        );
    }

    private static void openStremioSearch(Activity activity, SubjectInfo info) {
        String query = firstNonBlank(
                info.originalTitle,
                info.title,
                info.alternateTitles.isEmpty()
                        ? null : info.alternateTitles.get(0)
        );
        if (query == null) return;

        if (info.year != null) {
            query = query + " " + info.year;
        }

        String encoded;
        try {
            encoded = URLEncoder.encode(query, "UTF-8")
                    .replace("+", "%20");
        } catch (Exception e) {
            encoded = Uri.encode(query);
        }

        launch(
                activity,
                "stremio:///search?search=" + encoded,
                "没有检测到可处理 Stremio 链接的应用"
        );
    }

    private static void launch(
            Activity activity,
            String uri,
            String missingMessage
    ) {
        try {
            activity.startActivity(
                    new Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            );
        } catch (ActivityNotFoundException error) {
            Toast.makeText(
                    activity,
                    missingMessage,
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    // ---------------------------------------------------------------------
    // Subject extraction
    // ---------------------------------------------------------------------

    private static SubjectInfo extractSubjectInfo(Activity activity) {
        SubjectInfo info = new SubjectInfo();

        try {
            Intent intent = activity.getIntent();
            if (intent != null) {
                scanString(intent.getDataString(), null, info);

                Bundle extras = intent.getExtras();
                if (extras != null) {
                    for (String key : extras.keySet()) {
                        Object value;
                        try {
                            value = extras.get(key);
                        } catch (Throwable ignored) {
                            continue;
                        }
                        consumeNamedValue(key, value, info, 0);
                    }
                }
            }
        } catch (Throwable error) {
            Log.d(TAG, "Intent extraction failed: " + error);
        }

        scanActivityFields(activity, info);

        if (TextUtils.isEmpty(info.title)) {
            scanViewTree(activity, info);
        }

        if (TextUtils.isEmpty(info.mediaType)) {
            info.mediaType = "movie";
        }

        return info;
    }

    private static void scanActivityFields(Activity activity, SubjectInfo info) {
        Class<?> cls = activity.getClass();

        while (cls != null && cls != Object.class) {
            Field[] fields;
            try {
                fields = cls.getDeclaredFields();
            } catch (Throwable error) {
                break;
            }

            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) continue;

                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(activity);
                } catch (Throwable ignored) {
                    continue;
                }

                consumeNamedValue(field.getName(), value, info, 0);
            }

            cls = cls.getSuperclass();
        }
    }

    private static void consumeNamedValue(
            String name,
            Object value,
            SubjectInfo info,
            int depth
    ) {
        if (value == null || depth > 2) return;

        String key = normalizeKey(name);

        if (value instanceof CharSequence) {
            String text = value.toString().trim();
            scanString(text, key, info);

            if (isTitleKey(key)) {
                addTitleByKey(key, text, info);
            } else if (isYearKey(key)) {
                Integer year = parseYear(text);
                if (year != null) info.year = year;
            } else if (isTypeKey(key)) {
                applyMediaType(text, info);
            }
            return;
        }

        if (value instanceof Number) {
            long number = ((Number) value).longValue();
            if (isYearKey(key) && number >= 1800 && number <= 2100) {
                info.year = (int) number;
            } else if (isIdKey(key) && number > 1000 &&
                    TextUtils.isEmpty(info.subjectId)) {
                info.subjectId = Long.toString(number);
            }
            return;
        }

        if (value instanceof Boolean) {
            if (("istv".equals(key) || "isseries".equals(key)) &&
                    (Boolean) value) {
                info.mediaType = "series";
            }
            return;
        }

        if (value instanceof Iterable) {
            if (key.contains("aka") || key.contains("title")) {
                for (Object item : (Iterable<?>) value) {
                    if (item instanceof CharSequence) {
                        addAlternateTitle(item.toString(), info);
                    }
                }
            }
            return;
        }

        String className = value.getClass().getName();
        boolean subjectLike =
                className.startsWith("com.douban.frodo.subject.") ||
                className.contains(".subject.") ||
                className.endsWith(".Movie") ||
                key.contains("subject") ||
                key.contains("movie");

        if (!subjectLike) return;

        inspectSubjectObject(value, info, depth + 1);
    }

    private static void inspectSubjectObject(
            Object object,
            SubjectInfo info,
            int depth
    ) {
        if (object == null || depth > 2) return;

        // Known no-arg getters are safer than invoking arbitrary methods.
        String[] getterNames = {
                "getId", "getUri", "getUrl",
                "getTitle", "getOriginalTitle", "getOriginal_title",
                "getYear", "getType", "getSubtype",
                "getAka", "isTv", "isTV", "isSeries"
        };

        for (String getter : getterNames) {
            try {
                Method method = object.getClass().getMethod(getter);
                if (method.getParameterTypes().length != 0) continue;
                Object value = method.invoke(object);
                consumeNamedValue(getter, value, info, depth);
            } catch (Throwable ignored) {
            }
        }

        Class<?> cls = object.getClass();
        while (cls != null && cls != Object.class) {
            Field[] fields;
            try {
                fields = cls.getDeclaredFields();
            } catch (Throwable error) {
                break;
            }

            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                String key = normalizeKey(field.getName());

                if (!isInterestingModelKey(key)) continue;

                try {
                    field.setAccessible(true);
                    consumeNamedValue(
                            field.getName(),
                            field.get(object),
                            info,
                            depth
                    );
                } catch (Throwable ignored) {
                }
            }

            cls = cls.getSuperclass();
        }
    }

    private static void scanString(
            String text,
            String key,
            SubjectInfo info
    ) {
        if (TextUtils.isEmpty(text)) return;

        Matcher imdb = IMDB_PATTERN.matcher(text);
        if (imdb.find()) {
            info.imdbId = imdb.group().toLowerCase(Locale.US);
        }

        Matcher deepLink = DEEP_LINK_PATTERN.matcher(text);
        if (deepLink.find()) {
            info.mediaType =
                    "tv".equalsIgnoreCase(deepLink.group(1))
                            ? "series" : "movie";
            info.subjectId = deepLink.group(2);
        }

        Matcher subject = SUBJECT_URL_PATTERN.matcher(text);
        if (subject.find() && TextUtils.isEmpty(info.subjectId)) {
            info.subjectId = subject.group(1);
        }

        if (key != null && isTypeKey(key)) {
            applyMediaType(text, info);
        }

        if (key != null && isYearKey(key)) {
            Integer year = parseYear(text);
            if (year != null) info.year = year;
        }
    }

    private static void addTitleByKey(
            String key,
            String text,
            SubjectInfo info
    ) {
        if (!isPlausibleTitle(text)) return;

        if (key.contains("original")) {
            if (TextUtils.isEmpty(info.originalTitle)) {
                info.originalTitle = text;
            }
        } else if (TextUtils.isEmpty(info.title)) {
            info.title = text;
        } else if (!text.equals(info.title)) {
            addAlternateTitle(text, info);
        }
    }

    private static void addAlternateTitle(String text, SubjectInfo info) {
        if (!isPlausibleTitle(text)) return;
        if (text.equals(info.title) || text.equals(info.originalTitle)) return;
        if (!info.alternateTitles.contains(text)) {
            info.alternateTitles.add(text);
        }
    }

    private static boolean isPlausibleTitle(String text) {
        if (TextUtils.isEmpty(text)) return false;
        String v = text.trim();
        if (v.length() < 1 || v.length() > 140) return false;
        if (v.startsWith("http://") || v.startsWith("https://") ||
                v.startsWith("douban://")) return false;
        return true;
    }

    private static void applyMediaType(String raw, SubjectInfo info) {
        if (TextUtils.isEmpty(raw)) return;
        String value = raw.toLowerCase(Locale.US);
        if (value.contains("tv") ||
                value.contains("series") ||
                value.contains("television")) {
            info.mediaType = "series";
        } else if (value.contains("movie") ||
                value.contains("film")) {
            info.mediaType = "movie";
        }
    }

    private static boolean isInterestingModelKey(String key) {
        return isTitleKey(key) ||
                isYearKey(key) ||
                isTypeKey(key) ||
                isIdKey(key) ||
                key.contains("uri") ||
                key.contains("url") ||
                key.contains("aka") ||
                key.contains("imdb") ||
                key.contains("subject");
    }

    private static boolean isTitleKey(String key) {
        return "title".equals(key) ||
                "name".equals(key) ||
                key.contains("originaltitle") ||
                key.contains("othertitle") ||
                key.contains("foreigntitle");
    }

    private static boolean isYearKey(String key) {
        return "year".equals(key) ||
                key.endsWith("year") ||
                key.contains("releaseyear");
    }

    private static boolean isTypeKey(String key) {
        return "type".equals(key) ||
                "subtype".equals(key) ||
                key.contains("mediatype") ||
                key.contains("subjecttype");
    }

    private static boolean isIdKey(String key) {
        return "id".equals(key) ||
                "subjectid".equals(key) ||
                key.endsWith("subjectid");
    }

    private static String normalizeKey(String key) {
        if (key == null) return "";
        return key.toLowerCase(Locale.US)
                .replace("_", "")
                .replace("$", "");
    }

    private static Integer parseYear(String text) {
        if (TextUtils.isEmpty(text)) return null;
        Matcher matcher = YEAR_PATTERN.matcher(text);
        if (!matcher.find()) return null;

        try {
            int year = Integer.parseInt(matcher.group());
            return year >= 1800 && year <= 2100 ? year : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void scanViewTree(Activity activity, SubjectInfo info) {
        View decor;
        try {
            decor = activity.getWindow().getDecorView();
        } catch (Throwable error) {
            return;
        }

        List<TextCandidate> candidates = new ArrayList<>();
        collectTextCandidates(decor, candidates);

        Collections.sort(
                candidates,
                Comparator.comparingDouble((TextCandidate c) -> c.textSize)
                        .reversed()
                        .thenComparingInt(c -> c.y)
        );

        for (TextCandidate candidate : candidates) {
            Integer year = parseYear(candidate.text);
            if (info.year == null && year != null) {
                info.year = year;
            }

            if (TextUtils.isEmpty(info.title) &&
                    looksLikeVisibleTitle(candidate.text)) {
                info.title = cleanVisibleTitle(candidate.text);
            }
        }
    }

    private static void collectTextCandidates(
            View view,
            List<TextCandidate> out
    ) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;

        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            CharSequence cs = tv.getText();
            if (cs != null) {
                String text = cs.toString().trim();
                if (!TextUtils.isEmpty(text) && text.length() <= 160) {
                    int[] location = new int[2];
                    try {
                        tv.getLocationOnScreen(location);
                    } catch (Throwable ignored) {
                    }
                    out.add(new TextCandidate(
                            text,
                            tv.getTextSize(),
                            location[1]
                    ));
                }
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectTextCandidates(group.getChildAt(i), out);
            }
        }
    }

    private static boolean looksLikeVisibleTitle(String raw) {
        if (!isPlausibleTitle(raw)) return false;
        String text = raw.trim();

        String[] rejected = {
                "想看", "看过", "写短评", "影评", "讨论",
                "简介", "演职员", "预告片", "剧照", "评分",
                "Stremio", "Nuvio"
        };
        for (String value : rejected) {
            if (text.equals(value)) return false;
        }

        if (text.matches("[0-9.]+")) return false;
        return true;
    }

    private static String cleanVisibleTitle(String raw) {
        if (raw == null) return null;
        return raw.replaceAll("\\s*\\((18|19|20)\\d{2}\\)\\s*$", "")
                .trim();
    }

    // ---------------------------------------------------------------------
    // Cinemeta resolution
    // ---------------------------------------------------------------------

    private static Resolved resolveWithCinemeta(SubjectInfo info)
            throws Exception {
        if (!TextUtils.isEmpty(info.imdbId)) {
            return new Resolved(
                    info.imdbId,
                    firstNonBlank(info.mediaType, "movie"),
                    firstNonBlank(info.title, info.originalTitle)
            );
        }

        LinkedHashSet<String> queries = new LinkedHashSet<>();
        if (!TextUtils.isEmpty(info.originalTitle)) {
            queries.add(info.originalTitle);
        }
        if (!TextUtils.isEmpty(info.title)) {
            queries.add(info.title);
        }
        for (String aka : info.alternateTitles) {
            if (queries.size() >= 4) break;
            if (!TextUtils.isEmpty(aka)) queries.add(aka);
        }

        if (queries.isEmpty()) return null;

        List<String> types = new ArrayList<>();
        if ("series".equals(info.mediaType)) {
            types.add("series");
            types.add("movie");
        } else {
            types.add("movie");
            types.add("series");
        }

        Candidate best = null;
        int queryIndex = 0;

        for (String query : queries) {
            if (queryIndex >= 3) break;
            queryIndex++;

            for (String type : types) {
                List<Candidate> candidates = searchCinemeta(type, query);
                for (int i = 0; i < candidates.size(); i++) {
                    Candidate candidate = candidates.get(i);
                    candidate.score = scoreCandidate(
                            candidate,
                            query,
                            info,
                            type,
                            i
                    );
                    if (best == null || candidate.score > best.score) {
                        best = candidate;
                    }
                }

                if (best != null && best.score >= 125) {
                    break;
                }
            }

            if (best != null && best.score >= 125) {
                break;
            }
        }

        if (best == null || best.score < 40) return null;

        return new Resolved(best.imdbId, best.type, best.name);
    }

    private static List<Candidate> searchCinemeta(
            String type,
            String query
    ) throws Exception {
        String encoded = URLEncoder.encode(query, "UTF-8")
                .replace("+", "%20");

        URL url = new URL(
                "https://v3-cinemeta.strem.io/catalog/" +
                        type + "/top/search=" + encoded + ".json"
        );

        HttpURLConnection connection =
                (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(4500);
        connection.setReadTimeout(5500);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty(
                "User-Agent",
                "DoubanPlayerBridge/1.0 Android"
        );
        connection.setRequestProperty(
                "Accept",
                "application/json"
        );

        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            connection.disconnect();
            return Collections.emptyList();
        }

        String body;
        try (InputStream input = connection.getInputStream()) {
            body = readAll(input);
        } finally {
            connection.disconnect();
        }

        JSONObject root = new JSONObject(body);
        JSONArray metas = root.optJSONArray("metas");
        if (metas == null) return Collections.emptyList();

        List<Candidate> out = new ArrayList<>();

        int limit = Math.min(metas.length(), 12);
        for (int i = 0; i < limit; i++) {
            JSONObject meta = metas.optJSONObject(i);
            if (meta == null) continue;

            String id = meta.optString("id", "");
            if (!IMDB_PATTERN.matcher(id).matches()) continue;

            String name = meta.optString("name", "");
            String releaseInfo = meta.optString("releaseInfo", "");
            Integer year = parseYear(releaseInfo);

            if (year == null) {
                Object yearValue = meta.opt("year");
                if (yearValue instanceof Number) {
                    int y = ((Number) yearValue).intValue();
                    if (y >= 1800 && y <= 2100) year = y;
                } else if (yearValue != null) {
                    year = parseYear(String.valueOf(yearValue));
                }
            }

            String candidateType = normalizeCinemetaType(
                    meta.optString("type", type)
            );

            out.add(new Candidate(
                    id.toLowerCase(Locale.US),
                    candidateType,
                    name,
                    year
            ));
        }

        return out;
    }

    private static String readAll(InputStream input) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8)
        );
        StringBuilder builder = new StringBuilder();
        char[] buffer = new char[4096];
        int count;
        while ((count = reader.read(buffer)) != -1) {
            builder.append(buffer, 0, count);
            if (builder.length() > 2_000_000) break;
        }
        return builder.toString();
    }

    private static int scoreCandidate(
            Candidate candidate,
            String query,
            SubjectInfo info,
            String requestedType,
            int rank
    ) {
        int score = 0;

        String q = normalizeTitle(query);
        String name = normalizeTitle(candidate.name);

        if (!q.isEmpty() && q.equals(name)) {
            score += 90;
        } else if (!q.isEmpty() &&
                (name.contains(q) || q.contains(name))) {
            score += 50;
        } else {
            score += tokenOverlapScore(q, name);
        }

        if (info.year != null && candidate.year != null) {
            int delta = Math.abs(info.year - candidate.year);
            if (delta == 0) score += 60;
            else if (delta == 1) score += 30;
            else if (delta <= 2) score += 10;
            else score -= 25;
        }

        if (requestedType.equals(candidate.type)) {
            score += 20;
        }

        if (!TextUtils.isEmpty(info.mediaType) &&
                info.mediaType.equals(candidate.type)) {
            score += 15;
        }

        score += Math.max(0, 12 - rank);
        return score;
    }

    private static int tokenOverlapScore(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;

        Set<String> left = new HashSet<>();
        Collections.addAll(left, a.split(" "));

        Set<String> right = new HashSet<>();
        Collections.addAll(right, b.split(" "));

        left.remove("");
        right.remove("");

        if (left.isEmpty() || right.isEmpty()) return 0;

        int common = 0;
        for (String token : left) {
            if (right.contains(token)) common++;
        }

        double ratio = common /
                (double) Math.max(left.size(), right.size());

        return (int) Math.round(ratio * 40.0);
    }

    private static String normalizeTitle(String raw) {
        if (raw == null) return "";

        String value = Normalizer.normalize(raw, Normalizer.Form.NFKD)
                .toLowerCase(Locale.US)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");

        return value;
    }

    private static String normalizeCinemetaType(String raw) {
        if (raw == null) return "movie";
        String value = raw.toLowerCase(Locale.US);
        if (value.contains("series") ||
                value.contains("show") ||
                value.contains("tv")) {
            return "series";
        }
        return "movie";
    }

    // ---------------------------------------------------------------------
    // Cache
    // ---------------------------------------------------------------------

    private static String cacheKey(SubjectInfo info) {
        if (!TextUtils.isEmpty(info.subjectId)) {
            return "subject:" + info.subjectId;
        }

        String title = firstNonBlank(
                info.originalTitle,
                info.title,
                info.alternateTitles.isEmpty()
                        ? null : info.alternateTitles.get(0)
        );
        return "title:" + normalizeTitle(title == null ? "" : title) +
                ":" + (info.year == null ? "" : info.year);
    }

    private static Resolved readCache(
            Context context,
            SubjectInfo info
    ) {
        String raw = prefs(context).getString(cacheKey(info), null);
        if (raw == null) return null;

        String[] parts = raw.split("\\|", 3);
        if (parts.length < 2 ||
                !IMDB_PATTERN.matcher(parts[0]).matches()) {
            return null;
        }

        return new Resolved(
                parts[0],
                normalizeCinemetaType(parts[1]),
                parts.length >= 3 ? parts[2] : ""
        );
    }

    private static void writeCache(
            Context context,
            SubjectInfo info,
            Resolved resolved
    ) {
        prefs(context).edit()
                .putString(
                        cacheKey(info),
                        resolved.imdbId + "|" +
                                resolved.type + "|" +
                                (resolved.name == null ? "" : resolved.name)
                )
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(
                "douban_player_bridge",
                Context.MODE_PRIVATE
        );
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (!TextUtils.isEmpty(value) && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private static int dp(Context context, int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                context.getResources().getDisplayMetrics()
        );
    }

    private enum Target {
        STREMIO,
        NUVIO
    }

    private static final class SubjectInfo {
        String subjectId;
        String title;
        String originalTitle;
        String mediaType;
        String imdbId;
        Integer year;
        final List<String> alternateTitles = new ArrayList<>();

        String debugSummary() {
            return "豆瓣识别结果\n" +
                    "ID: " + safe(subjectId) + "\n" +
                    "标题: " + safe(title) + "\n" +
                    "原名: " + safe(originalTitle) + "\n" +
                    "年份: " + (year == null ? "?" : year) + "\n" +
                    "类型: " + safe(mediaType) + "\n" +
                    "IMDb: " + safe(imdbId);
        }

        private static String safe(String value) {
            return TextUtils.isEmpty(value) ? "?" : value;
        }
    }

    private static final class Resolved {
        final String imdbId;
        final String type;
        final String name;

        Resolved(String imdbId, String type, String name) {
            this.imdbId = imdbId;
            this.type = type;
            this.name = name;
        }
    }

    private static final class Candidate {
        final String imdbId;
        final String type;
        final String name;
        final Integer year;
        int score;

        Candidate(
                String imdbId,
                String type,
                String name,
                Integer year
        ) {
            this.imdbId = imdbId;
            this.type = type;
            this.name = name;
            this.year = year;
        }
    }

    private static final class TextCandidate {
        final String text;
        final float textSize;
        final int y;

        TextCandidate(String text, float textSize, int y) {
            this.text = text;
            this.textSize = textSize;
            this.y = y;
        }
    }
}
