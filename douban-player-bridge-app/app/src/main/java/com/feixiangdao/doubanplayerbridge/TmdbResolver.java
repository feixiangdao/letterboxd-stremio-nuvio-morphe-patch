package com.feixiangdao.doubanplayerbridge;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TmdbResolver {
    private static final String BASE = "https://api.themoviedb.org/3";
    private static final Pattern YEAR = Pattern.compile("\\b(18|19|20)\\d{2}\\b");

    static final class Result {
        final long tmdbId;
        final String type;
        final String title;
        final Integer year;
        final String imdbId;

        Result(long tmdbId, String type, String title, Integer year, String imdbId) {
            this.tmdbId = tmdbId;
            this.type = type;
            this.title = title;
            this.year = year;
            this.imdbId = imdbId;
        }
    }

    private static final class Candidate {
        long id;
        String type;
        String title;
        String originalTitle;
        Integer year;
        int popularityRank;
        int score;
    }

    private TmdbResolver() {}

    static Result resolve(MediaInfo info, String credential) throws Exception {
        if (info == null || TextUtils.isEmpty(info.title) || TextUtils.isEmpty(credential)) {
            return null;
        }

        List<Candidate> candidates = new ArrayList<>();
        if ("series".equals(info.preferredType)) {
            candidates.addAll(search("tv", info, credential));
            candidates.addAll(search("movie", info, credential));
        } else {
            candidates.addAll(search("movie", info, credential));
            candidates.addAll(search("tv", info, credential));
        }

        Candidate best = null;
        for (Candidate c : candidates) {
            c.score = score(c, info);
            if (best == null || c.score > best.score) best = c;
        }

        // Fail closed. A wrong media page is much worse than no shortcut.
        if (best == null || best.score < 115) return null;

        String imdb = fetchImdbId(best, credential);
        String type = "tv".equals(best.type) ? "series" : "movie";
        return new Result(best.id, type, best.title, best.year, imdb);
    }

    private static List<Candidate> search(String type, MediaInfo info, String credential)
            throws Exception {
        String query = URLEncoder.encode(info.title, "UTF-8").replace("+", "%20");
        StringBuilder endpoint = new StringBuilder(BASE)
                .append("/search/").append(type)
                .append("?query=").append(query)
                .append("&include_adult=false")
                .append("&language=zh-CN")
                .append("&page=1");

        if (info.year != null) {
            if ("movie".equals(type)) {
                endpoint.append("&year=").append(info.year);
            } else {
                endpoint.append("&first_air_date_year=").append(info.year);
            }
        }

        JSONObject root = getJson(endpoint.toString(), credential);
        JSONArray results = root.optJSONArray("results");
        List<Candidate> out = new ArrayList<>();
        if (results == null) return out;

        for (int i = 0; i < Math.min(12, results.length()); i++) {
            JSONObject o = results.optJSONObject(i);
            if (o == null) continue;

            long id = o.optLong("id", 0);
            if (id <= 0) continue;

            Candidate c = new Candidate();
            c.id = id;
            c.type = type;
            c.title = o.optString("title", o.optString("name", ""));
            c.originalTitle = o.optString(
                    "original_title",
                    o.optString("original_name", "")
            );
            c.year = parseYear(o.optString(
                    "release_date",
                    o.optString("first_air_date", "")
            ));
            c.popularityRank = i;
            out.add(c);
        }
        return out;
    }

    private static int score(Candidate c, MediaInfo info) {
        int score = 0;

        // Year is a hard gate when Douban exposes it.
        if (info.year != null) {
            if (c.year == null) {
                score -= 50;
            } else {
                int delta = Math.abs(info.year - c.year);
                if (delta == 0) score += 80;
                else if (delta == 1) score += 35;
                else if (delta <= 2) score += 10;
                else return Integer.MIN_VALUE / 4;
            }
        }

        String candidate = normalize(c.title);
        String original = normalize(c.originalTitle);

        int bestTitle = titleScore(normalize(info.title), candidate, original);
        for (String alias : info.aliases) {
            bestTitle = Math.max(
                    bestTitle,
                    titleScore(normalize(alias), candidate, original)
            );
        }
        score += bestTitle;

        String expected = "series".equals(info.preferredType) ? "tv" : "movie";
        if (expected.equals(c.type)) score += 20;

        score += Math.max(0, 10 - c.popularityRank);
        return score;
    }

    private static int titleScore(String q, String title, String original) {
        if (TextUtils.isEmpty(q)) return 0;
        if (q.equals(title) || q.equals(original)) return 100;

        if (!TextUtils.isEmpty(title) &&
                (q.contains(title) || title.contains(q))) {
            return 55;
        }
        if (!TextUtils.isEmpty(original) &&
                (q.contains(original) || original.contains(q))) {
            return 55;
        }
        return 0;
    }

    private static String fetchImdbId(Candidate c, String credential)
            throws Exception {
        String endpoint;
        if ("tv".equals(c.type)) {
            endpoint = BASE + "/tv/" + c.id + "/external_ids";
        } else {
            endpoint = BASE + "/movie/" + c.id + "/external_ids";
        }
        JSONObject root = getJson(endpoint, credential);
        String imdb = root.optString("imdb_id", "");
        return imdb.matches("tt\\d{5,12}") ? imdb : null;
    }

    private static JSONObject getJson(String url, String credential) throws Exception {
        boolean bearer = credential.startsWith("eyJ") || credential.length() > 64;
        if (!bearer) {
            url += (url.contains("?") ? "&" : "?") +
                    "api_key=" + URLEncoder.encode(credential, "UTF-8");
        }

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4500);
        c.setReadTimeout(6000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "DoubanPlayerBridge/0.2");
        if (bearer) {
            c.setRequestProperty("Authorization", "Bearer " + credential);
        }

        int code = c.getResponseCode();
        InputStream input = code >= 200 && code < 300
                ? c.getInputStream() : c.getErrorStream();
        String body = readAll(input);
        c.disconnect();

        if (code < 200 || code >= 300) {
            throw new IllegalStateException("TMDB HTTP " + code);
        }
        return new JSONObject(body);
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        return Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .toLowerCase(Locale.US)
                .replaceAll("[^\\p{L}\\p{N}]+", "")
                .trim();
    }

    private static Integer parseYear(String value) {
        if (TextUtils.isEmpty(value)) return null;
        Matcher m = YEAR.matcher(value);
        if (!m.find()) return null;
        try {
            return Integer.parseInt(m.group());
        } catch (Exception e) {
            return null;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) != -1) {
            b.append(buf, 0, n);
            if (b.length() > 2_000_000) break;
        }
        return b.toString();
    }
}
