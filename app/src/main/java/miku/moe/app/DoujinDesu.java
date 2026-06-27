package miku.moe.app;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class DoujinDesu extends KomikcastClient {
    private static final String DEFAULT_BASE = "https://doujin.desu.xxx";
    private static final String API_SECRET = "dfdf72051dbfdc7d76889ebd31324e74";
    private static final String DECRYPT_SALT = "doujindesu-scrapers-cannot-read-this-super-secret-salt-2026-v2";
    private static final int LIMIT = 24;
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final OkHttpClient CLIENT = MangaHttpClient.newBuilder().cache(null).connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MangaMemoryCache<String, ArrayList<MangaPost>> LIST_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(96, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(96, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(48, CACHE_TTL);
    private static final MangaMemoryCache<String, String> CHAPTER_ID_CACHE = new MangaMemoryCache<>(600, CACHE_TTL);
    private static final ArrayList<GenreItem> GENRE_CACHE = new ArrayList<>();
    private final OkHttpClient client = CLIENT;
    private final Handler main = MAIN;

    protected static String base() { return DEFAULT_BASE; }

    private static String apiBase() { return base() + "/api"; }

    @Override protected String sourceLabel() { return "DoujinDesu"; }

    @Override public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        try {
            int safePage = Math.max(1, page);
            DoujinFilterSpec spec = parseFilterSpec(genre);
            String safeQuery = query == null ? "" : query.trim();
            String mode = sort == null || sort.trim().isEmpty() ? "latest" : sort.trim().toLowerCase(Locale.ROOT);
            boolean useTaxonomy = safeQuery.isEmpty() && !spec.genre.isEmpty() && spec.type.isEmpty() && !isMainTypeTab(mode);
            String url = useTaxonomy ? taxonomyUrl(spec.genre, safePage, taxonomySort(mode)) : mangaUrl(safePage, mode, safeQuery, spec.genre, spec.status, spec.type);
            String cacheKey = url;
            ArrayList<MangaPost> cached = LIST_CACHE.get(cacheKey);
            if (cached != null) { cb.onSuccess(new ArrayList<>(cached), cached.size() >= LIMIT); return; }
            getJson(url, new Result<JsonElement>() {
                @Override public void onSuccess(JsonElement root, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            ArrayList<MangaPost> out = new ArrayList<>();
                            LinkedHashSet<String> seen = new LinkedHashSet<>();
                            JsonArray items = mangaArray(root);
                            for (JsonElement element : items) {
                                if (element == null || !element.isJsonObject()) continue;
                                MangaPost post = parsePost(element.getAsJsonObject());
                                if (post == null || post.slug == null || post.slug.trim().isEmpty()) continue;
                                if (hasTypeFilter(spec.type) && !matchesType(post, spec.type) && !isMainTypeTab(mode)) continue;
                                if (seen.add(post.slug)) out.add(post);
                            }
                            boolean hasNext = hasNext(root, out.size(), safePage);
                            LIST_CACHE.put(cacheKey, new ArrayList<>(out));
                            MangaCoroutines.main(() -> cb.onSuccess(out, hasNext));
                        } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar DoujinDesu gagal dibaca")); }
                    });
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
        } catch(Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        synchronized (GENRE_CACHE) {
            if (!GENRE_CACHE.isEmpty()) { cb.onSuccess(new ArrayList<>(GENRE_CACHE), false); return; }
        }
        HttpUrl url = HttpUrl.parse(apiBase() + "/taxonomy/genres").newBuilder().addQueryParameter("page", "1").addQueryParameter("search", "").addQueryParameter("limit", "60").build();
        getJson(url.toString(), new Result<JsonElement>() {
            @Override public void onSuccess(JsonElement root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<GenreItem> out = new ArrayList<>();
                        JsonObject obj = root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
                        JsonArray terms = getArray(obj, "terms");
                        LinkedHashSet<String> seen = new LinkedHashSet<>();
                        for (JsonElement element : terms) {
                            if (element == null || !element.isJsonObject()) continue;
                            JsonObject item = element.getAsJsonObject();
                            String name = getString(item, "name");
                            String slug = getString(item, "slug");
                            if (name.isEmpty() || slug.isEmpty() || !seen.add(slug)) continue;
                            out.add(new GenreItem(name, slug));
                        }
                        if (out.isEmpty()) out = fallbackGenres();
                        synchronized (GENRE_CACHE) { GENRE_CACHE.clear(); GENRE_CACHE.addAll(out); }
                        ArrayList<GenreItem> result = out;
                        MangaCoroutines.main(() -> cb.onSuccess(result, false));
                    } catch(Exception e) {
                        ArrayList<GenreItem> fallback = fallbackGenres();
                        synchronized (GENRE_CACHE) { GENRE_CACHE.clear(); GENRE_CACHE.addAll(fallback); }
                        MangaCoroutines.main(() -> cb.onSuccess(fallback, false));
                    }
                });
            }
            @Override public void onError(String message) {
                ArrayList<GenreItem> fallback = fallbackGenres();
                synchronized (GENRE_CACHE) { GENRE_CACHE.clear(); GENRE_CACHE.addAll(fallback); }
                cb.onSuccess(fallback, false);
            }
        });
    }

    @Override public void enrichLatest(ArrayList<MangaPost> list, Runnable done) {
        if (list == null || list.isEmpty()) { if (done != null) MangaCoroutines.main(done); return; }
        boolean needs = false;
        for (MangaPost post : list) {
            if (post == null) continue;
            if (post.latestChapter == null || post.latestChapter.trim().isEmpty() || post.typeLabel == null || post.typeLabel.trim().isEmpty()) { needs = true; break; }
        }
        if (!needs) { if (done != null) MangaCoroutines.main(done); return; }
        super.enrichLatest(list, done);
    }

    @Override public void detail(String slug, Result<MangaPost> cb) {
        String clean = cleanSlug(slug);
        MangaPost cached = DETAIL_CACHE.get(clean);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        getJson(apiBase() + "/manga/" + urlSegment(clean), new Result<JsonElement>() {
            @Override public void onSuccess(JsonElement root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        JsonObject obj = root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
                        if (obj == null) { MangaCoroutines.main(() -> cb.onError("Detail DoujinDesu kosong")); return; }
                        MangaPost post = parsePost(obj);
                        if (post == null || post.slug.isEmpty()) { MangaCoroutines.main(() -> cb.onError("Detail DoujinDesu kosong")); return; }
                        DETAIL_CACHE.put(clean, post);
                        ArrayList<MangaChapter> chapters = parseChapters(post.slug, obj);
                        if (!chapters.isEmpty()) CHAPTER_CACHE.put(post.slug, chapters);
                        MangaCoroutines.main(() -> cb.onSuccess(post, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail DoujinDesu gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { loadDetailFromSearch(clean, cb); }
        });
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        String clean = cleanSlug(slug);
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(clean);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        getJson(apiBase() + "/manga/" + urlSegment(clean), new Result<JsonElement>() {
            @Override public void onSuccess(JsonElement root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        JsonObject obj = root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
                        if (obj == null) { MangaCoroutines.main(() -> cb.onError("Daftar chapter DoujinDesu kosong")); return; }
                        MangaPost post = parsePost(obj);
                        if (post != null && !post.slug.isEmpty()) DETAIL_CACHE.put(clean, post);
                        ArrayList<MangaChapter> out = parseChapters(clean, obj);
                        CHAPTER_CACHE.put(clean, new ArrayList<>(out));
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar chapter DoujinDesu gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { loadChaptersFromSearch(clean, cb); }
        });
    }

    @Override public void pages(String slug, float index, Result<ArrayList<String>> cb) {
        String clean = cleanSlug(slug);
        String key = clean + ":" + MangaChapter.formatIndex(index);
        ArrayList<String> cached = PAGE_CACHE.get(key);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        String chapterId = findChapterId(clean, index);
        if (chapterId.isEmpty()) {
            chapters(clean, new Result<ArrayList<MangaChapter>>() {
                @Override public void onSuccess(ArrayList<MangaChapter> data, boolean hasNext) { loadPages(clean, index, cb); }
                @Override public void onError(String message) { cb.onError(message); }
            });
            return;
        }
        loadPages(clean, index, cb);
    }

    private void loadPages(String slug, float index, Result<ArrayList<String>> cb) {
        String chapterId = findChapterId(slug, index);
        if (chapterId.isEmpty()) { cb.onError("ID chapter DoujinDesu tidak ditemukan"); return; }
        String key = slug + ":" + MangaChapter.formatIndex(index);
        getJson(apiBase() + "/chapters/" + urlSegment(chapterId), new Result<JsonElement>() {
            @Override public void onSuccess(JsonElement root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        JsonObject obj = root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
                        if (obj == null) { MangaCoroutines.main(() -> cb.onError("Chapter DoujinDesu kosong")); return; }
                        ArrayList<String> out = new ArrayList<>();
                        LinkedHashSet<String> seen = new LinkedHashSet<>();
                        JsonArray urls = getArray(obj, "content_urls");
                        for (JsonElement element : urls) {
                            if (element == null || element.isJsonNull()) continue;
                            String url = normalizeChapterImageUrl(element.getAsString());
                            if (url.startsWith("http") && seen.add(url)) out.add(url);
                        }
                        if (out.isEmpty()) { MangaCoroutines.main(() -> cb.onError("Halaman DoujinDesu kosong")); return; }
                        PAGE_CACHE.put(key, new ArrayList<>(out));
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman DoujinDesu gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private void loadDetailFromSearch(String slug, Result<MangaPost> cb) {
        String url = mangaSearchUrl(slug, 1);
        getJson(url, new Result<JsonElement>() {
            @Override public void onSuccess(JsonElement root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        MangaPost found = null;
                        JsonArray items = mangaArray(root);
                        for (JsonElement element : items) {
                            if (element == null || !element.isJsonObject()) continue;
                            MangaPost post = parsePost(element.getAsJsonObject());
                            if (post != null && slug.equalsIgnoreCase(post.slug)) { found = post; break; }
                            if (found == null) found = post;
                        }
                        if (found == null) { MangaCoroutines.main(() -> cb.onError("Detail DoujinDesu kosong")); return; }
                        DETAIL_CACHE.put(slug, found);
                        MangaPost result = found;
                        MangaCoroutines.main(() -> cb.onSuccess(result, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail DoujinDesu gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private void loadChaptersFromSearch(String slug, Result<ArrayList<MangaChapter>> cb) {
        getJson(mangaSearchUrl(slug, 1), new Result<JsonElement>() {
            @Override public void onSuccess(JsonElement root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<MangaChapter> out = new ArrayList<>();
                        JsonArray items = mangaArray(root);
                        for (JsonElement element : items) {
                            if (element == null || !element.isJsonObject()) continue;
                            JsonObject obj = element.getAsJsonObject();
                            String itemSlug = getString(obj, "slug");
                            if (!slug.equalsIgnoreCase(itemSlug)) continue;
                            out = parseChapters(slug, obj);
                            MangaPost post = parsePost(obj);
                            if (post != null) DETAIL_CACHE.put(slug, post);
                            break;
                        }
                        CHAPTER_CACHE.put(slug, new ArrayList<>(out));
                        ArrayList<MangaChapter> result = out;
                        MangaCoroutines.main(() -> cb.onSuccess(result, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar chapter DoujinDesu gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private String mangaUrl(int page, String mode, String query, String genre, String status, String filterType) {
        String type = typeForMode(mode, filterType);
        String sort = sortForMode(mode, filterType);
        HttpUrl.Builder builder = HttpUrl.parse(apiBase() + "/manga").newBuilder();
        builder.addQueryParameter("search", query == null ? "" : query);
        builder.addQueryParameter("genre", genre == null ? "" : genre);
        builder.addQueryParameter("status", status == null ? "" : status);
        builder.addQueryParameter("type", type);
        builder.addQueryParameter("sort", sort);
        builder.addQueryParameter("limit", String.valueOf(LIMIT));
        builder.addQueryParameter("offset", String.valueOf((Math.max(1, page) - 1) * LIMIT));
        if (isPopularMode(mode)) builder.addQueryParameter("orderBy", "m.rating DESC");
        return builder.build().toString();
    }

    private String mangaSearchUrl(String search, int page) {
        HttpUrl.Builder builder = HttpUrl.parse(apiBase() + "/manga").newBuilder();
        builder.addQueryParameter("search", search == null ? "" : search);
        builder.addQueryParameter("genre", "");
        builder.addQueryParameter("status", "");
        builder.addQueryParameter("type", "");
        builder.addQueryParameter("sort", "newest");
        builder.addQueryParameter("limit", String.valueOf(LIMIT));
        builder.addQueryParameter("offset", String.valueOf((Math.max(1, page) - 1) * LIMIT));
        return builder.build().toString();
    }

    private String taxonomyUrl(String genre, int page, String sort) {
        return HttpUrl.parse(apiBase() + "/taxonomy/genres/" + urlSegment(genre)).newBuilder().addQueryParameter("page", String.valueOf(Math.max(1, page))).addQueryParameter("sort", sort).addQueryParameter("limit", String.valueOf(LIMIT)).build().toString();
    }

    private static boolean isMainTypeTab(String mode) {
        return "manga".equals(mode) || "manhwa".equals(mode) || "doujinshi".equals(mode);
    }

    private static boolean isPopularMode(String mode) {
        return "popular".equals(mode) || "popularity".equals(mode) || "views".equals(mode) || "rating".equals(mode);
    }

    private static String typeForMode(String mode, String filterType) {
        if ("manga".equals(mode)) return "doujinshi,manga";
        if ("manhwa".equals(mode)) return "manhwa";
        if ("doujinshi".equals(mode)) return "doujinshi";
        String type = normalizeApiType(filterType);
        return type == null ? "" : type;
    }

    private static String sortForMode(String mode, String filterType) {
        if ("manga".equals(mode) || "manhwa".equals(mode)) return "latest_chapter";
        if ("doujinshi".equals(mode)) return "newest";
        if (isPopularMode(mode)) return "rating";
        if ("oldest".equals(mode)) return "oldest";
        if ("title_asc".equals(mode)) return "title_asc";
        if ("latest_chapter".equals(mode)) return "latest_chapter";
        return "newest";
    }

    private static String taxonomySort(String mode) {
        if (isPopularMode(mode)) return "popular";
        if ("oldest".equals(mode)) return "oldest";
        return "latest";
    }

    private static String normalizeApiType(String type) {
        if (type == null) return "";
        String clean = type.trim();
        if (clean.isEmpty()) return "";
        clean = clean.replace("type:", "").trim().toLowerCase(Locale.ROOT);
        if (clean.equals("manga")) return "manga";
        if (clean.equals("manhwa")) return "manhwa";
        if (clean.equals("doujinshi") || clean.equals("doujin")) return "doujinshi";
        return clean;
    }

    private static boolean hasTypeFilter(String type) {
        return type != null && !type.trim().isEmpty();
    }

    private static boolean matchesType(MangaPost post, String type) {
        String expected = normalizeApiType(type);
        if (expected.isEmpty()) return true;
        String actual = normalizeApiType(post == null ? "" : post.typeLabel);
        if (actual.isEmpty() && post != null) actual = normalizeApiType(post.genre + " " + post.info);
        return expected.equals(actual) || actual.contains(expected) || expected.contains(actual);
    }

    private void getJson(String url, Result<JsonElement> cb) {
        getJson(url, cb, false);
    }

    private void getJson(String url, Result<JsonElement> cb, boolean retried) {
        Request req = new Request.Builder().url(url).headers(headers()).cacheControl(new CacheControl.Builder().noCache().noStore().build()).build();
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MangaCoroutines.main(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if ((response.code() == 403 || response.code() == 503) && !retried) {
                    warmupThenRetry(url, cb);
                    return;
                }
                if (!response.isSuccessful()) { MangaCoroutines.main(() -> cb.onError("HTTP " + response.code() + " DoujinDesu")); return; }
                try {
                    JsonElement element = JsonParser.parseString(body);
                    if (element != null && element.isJsonObject() && element.getAsJsonObject().has("_enc_resp_")) {
                        String enc = getString(element.getAsJsonObject(), "_enc_resp_");
                        long timestamp = parseHttpDate(response.header("Date"));
                        String decoded = decryptEncResp(enc, timestamp > 0L ? timestamp : System.currentTimeMillis());
                        element = JsonParser.parseString(decoded);
                    }
                    JsonElement finalElement = element;
                    MangaCoroutines.main(() -> cb.onSuccess(finalElement, false));
                } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Data DoujinDesu gagal didecrypt")); }
            }
        });
    }

    private void warmupThenRetry(String url, Result<JsonElement> cb) {
        Request warmup = new Request.Builder().url(base() + "/explore").headers(headers()).cacheControl(new CacheControl.Builder().noCache().noStore().build()).build();
        CloudflareHelper.enqueue(client, warmup, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MangaCoroutines.main(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                if (response.body() != null) response.body().close();
                getJson(url, cb, true);
            }
        });
    }

    private okhttp3.Headers headers() {
        return new okhttp3.Headers.Builder()
                .add("Referer", base() + "/explore")
                .add("Origin", base())
                .add("X-App-Secret", API_SECRET)
                .add("Accept", "application/json, text/plain, */*")
                .add("Accept-Language", "id-ID,id;q=0.5")
                .add("Cache-Control", "no-cache")
                .add("Pragma", "no-cache")
                .add("Sec-Fetch-Dest", "empty")
                .add("Sec-Fetch-Mode", "cors")
                .add("Sec-Fetch-Site", "same-origin")
                .add("Sec-GPC", "1")
                .add("sec-ch-ua", "\"Brave\";v=\"149\", \"Chromium\";v=\"149\", \"Not)A;Brand\";v=\"24\"")
                .add("sec-ch-ua-mobile", "?0")
                .add("sec-ch-ua-platform", "\"Linux\"")
                .add("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36")
                .build();
    }

    private MangaPost parsePost(JsonObject item) {
        if (item == null) return null;
        JsonObject d = getObject(item, "data");
        if (d == null) d = item;
        String slug = firstNonEmpty(getString(d, "slug"), getString(item, "slug"));
        if (slug.isEmpty()) return null;
        String title = cleanDisplay(firstNonEmpty(getString(d, "title"), getString(item, "title")));
        String cover = firstNonEmpty(getString(d, "cover_url"), getString(d, "coverImage"), getString(d, "cover"), getString(item, "cover_url"));
        String genres = parseGenres(d);
        String type = displayType(firstNonEmpty(getString(d, "type"), inferTypeFromText(genres + " " + title)));
        String author = authorName(d);
        String status = cleanDisplay(getString(d, "status"));
        String description = cleanSynopsis(firstNonEmpty(getString(d, "description"), getString(d, "synopsis")));
        LatestInfo latest = latestInfo(d);
        MangaPost post = new MangaPost(slug, title, cover, author, status, description, genres, type, latest.chapter, prettyDate(latest.date)).withSource(MangaSettingsManager.MANGA_SOURCE_DOUJINDESU, "DoujinDesu");
        post.totalChapters = getInt(d, "chapter_count", getInt(getObject(d, "_count"), "chapters", getArray(d, "chapters").size()));
        post.info = buildInfo(d, post);
        return post;
    }

    private ArrayList<MangaChapter> parseChapters(String slug, JsonObject obj) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        if (obj == null) return out;
        JsonObject d = getObject(obj, "data");
        if (d == null) d = obj;
        JsonArray chapters = getArray(d, "chapters");
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (JsonElement element : chapters) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            float number = getFloat(item, "chapter_number", getFloat(item, "index", -1f));
            if (number < 0f) number = numberFrom(getString(item, "title"));
            if (number < 0f) continue;
            String key = MangaChapter.formatIndex(number);
            if (!seen.add(key)) continue;
            String date = firstNonEmpty(getString(item, "created_at"), getString(item, "updated_at"));
            MangaChapter chapter = new MangaChapter(slug, number, "", date);
            chapter.chapterId = getString(item, "id");
            out.add(chapter);
            if (!chapter.chapterId.isEmpty()) {
                CHAPTER_ID_CACHE.put(slug + ":" + key, chapter.chapterId);
                CHAPTER_ID_CACHE.put(slug + ":" + number, chapter.chapterId);
            }
        }
        Collections.sort(out, (a, b) -> Float.compare(b.index, a.index));
        return out;
    }

    private LatestInfo latestInfo(JsonObject item) {
        JsonArray chapters = getArray(item, "chapters");
        float best = -1f;
        String bestDate = "";
        for (JsonElement element : chapters) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject chapter = element.getAsJsonObject();
            float number = getFloat(chapter, "chapter_number", -1f);
            if (number >= best) {
                best = number;
                bestDate = firstNonEmpty(getString(chapter, "created_at"), getString(chapter, "updated_at"));
            }
        }
        if (best >= 0f) return new LatestInfo("Chapter " + MangaChapter.formatIndex(best), bestDate);
        return new LatestInfo("", firstNonEmpty(getString(item, "updated_at"), getString(item, "created_at")));
    }

    private String parseGenres(JsonObject item) {
        ArrayList<String> list = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        addNames(list, seen, termsByType(item, "genre"));
        JsonArray mangaGenres = getArray(item, "manga_genres");
        for (JsonElement element : mangaGenres) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject wrapper = element.getAsJsonObject();
            JsonObject genre = getObject(wrapper, "genres");
            String name = firstNonEmpty(getString(genre, "name"), getString(wrapper, "name"));
            addName(list, seen, name);
        }
        String terms = getString(item, "terms");
        if (!terms.isEmpty()) {
            String[] parts = terms.split(",");
            for (String part : parts) {
                String value = part == null ? "" : part.trim();
                int idx = value.indexOf(":");
                if (idx >= 0) value = value.substring(0, idx).trim();
                addName(list, seen, value);
            }
        }
        return TextUtils.join(", ", list);
    }

    private String buildInfo(JsonObject item, MangaPost post) {
        ArrayList<String> rows = new ArrayList<>();
        addInfo(rows, "Tipe", displayType(getString(item, "type")));
        addInfo(rows, "Author", authorName(item));
        addInfo(rows, "Status", post == null ? getString(item, "status") : post.status);
        addInfo(rows, "Serialisasi", firstNonEmpty(joinNames(termsByType(item, "series")), getString(item, "serialization")));
        addInfo(rows, "Rating", getString(item, "rating"));
        addInfo(rows, "Views", getString(item, "views"));
        addInfo(rows, "Alt Title", cleanDisplay(getString(item, "alt_titles").replace("|", ", ")));
        return TextUtils.join("||", rows);
    }

    private static void addInfo(ArrayList<String> rows, String label, String value) {
        if (value == null) return;
        String clean = cleanDisplay(value);
        if (!clean.isEmpty() && !"null".equalsIgnoreCase(clean) && !"-".equals(clean)) rows.add(label + ": " + clean);
    }

    private static String authorName(JsonObject item) {
        String fromTerms = joinNames(termsByType(item, "author"));
        if (!fromTerms.isEmpty()) return fromTerms;
        String fromArtists = joinNames(termsByType(item, "artist"));
        if (!fromArtists.isEmpty()) return fromArtists;
        String author = cleanDisplay(getString(item, "author"));
        if (!author.isEmpty() && !"-".equals(author)) return author;
        String artist = cleanDisplay(getString(item, "artist"));
        if (!artist.isEmpty() && !"-".equals(artist)) return artist;
        return "";
    }

    private static ArrayList<String> termsByType(JsonObject item, String type) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (item == null || type == null) return out;
        String expected = type.trim().toLowerCase(Locale.ROOT);
        String termList = getString(item, "term_list");
        if (!termList.isEmpty()) {
            String[] parts = termList.split("\\|");
            for (String part : parts) {
                if (part == null) continue;
                String[] columns = part.split(":");
                if (columns.length < 2) continue;
                String name = cleanDisplay(columns[0]);
                String termType = columns[1] == null ? "" : columns[1].trim().toLowerCase(Locale.ROOT);
                if (name.isEmpty() || !expected.equals(termType)) continue;
                addName(out, seen, name);
            }
        }
        JsonArray array = getArray(item, expected);
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) continue;
            if (element.isJsonObject()) addName(out, seen, getString(element.getAsJsonObject(), "name"));
            else addName(out, seen, element.getAsString());
        }
        return out;
    }

    private static void addNames(ArrayList<String> out, LinkedHashSet<String> seen, ArrayList<String> names) {
        if (names == null) return;
        for (String name : names) addName(out, seen, name);
    }

    private static void addName(ArrayList<String> out, LinkedHashSet<String> seen, String raw) {
        if (out == null || seen == null) return;
        String clean = cleanDisplay(raw);
        if (clean.isEmpty() || "null".equalsIgnoreCase(clean) || "-".equals(clean)) return;
        String key = clean.toLowerCase(Locale.ROOT);
        if (seen.add(key)) out.add(clean);
    }

    private static String joinNames(ArrayList<String> list) {
        if (list == null || list.isEmpty()) return "";
        return TextUtils.join(", ", list);
    }

    private static String displayType(String raw) {
        String clean = cleanDisplay(raw).toLowerCase(Locale.ROOT);
        if (clean.contains("manhwa")) return "Manhwa";
        if (clean.contains("manhua")) return "Manhua";
        if (clean.contains("doujinshi")) return "Doujinshi";
        if (clean.contains("doujin")) return "Doujinshi";
        if (clean.contains("manga")) return "Manga";
        return cleanDisplay(raw);
    }

    private static String cleanSynopsis(String raw) {
        String value = decodeHtmlEntities(raw);
        value = value.replaceAll("(?is)<p[^>]*>\\s*<strong>\\s*Download\\s+Batch\\s*</strong>.*?</p>", "");
        value = value.replaceAll("(?is)<strong>\\s*Download\\s+Batch\\s*</strong>.*", "");
        value = value.replaceAll("(?is)<p[^>]*>\\s*<b>\\s*Download\\s+Batch\\s*</b>.*?</p>", "");
        value = value.replaceAll("(?is)<b>\\s*Download\\s+Batch\\s*</b>.*", "");
        value = value.replaceAll("(?is)<strong>\\s*Sinopsis\\s*:?\\s*</strong>\\s*(<br\\s*/?>)?", "");
        value = value.replaceAll("(?is)<b>\\s*Sinopsis\\s*:?\\s*</b>\\s*(<br\\s*/?>)?", "");
        value = cleanDisplay(value);
        value = value.replaceAll("(?im)^\\s*Sinopsis\\s*:?\\s*", "");
        value = value.replaceAll("(?im)^\\s*Download\\s+Batch.*", "");
        value = value.replaceAll("\n{3,}", "\n\n").trim();
        return value;
    }

    private static String cleanDisplay(String raw) {
        if (raw == null) return "";
        String value = decodeHtmlEntities(raw).replace('\u00A0', ' ');
        value = value.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        value = value.replaceAll("(?is)<style[^>]*>.*?</style>", "");
        value = value.replaceAll("(?i)<br\\s*/?>", "\n");
        value = value.replaceAll("(?i)</div>\\s*<div[^>]*>", "\n");
        value = value.replaceAll("(?i)</p>\\s*<p[^>]*>", "\n\n");
        value = value.replaceAll("(?i)</li>\\s*<li[^>]*>", "\n");
        value = value.replaceAll("<[^>]+>", "");
        value = decodeHtmlEntities(value);
        value = value.replaceAll("[ \t]+", " ").replaceAll(" *\n *", "\n").trim();
        return value;
    }

    private static String decodeHtmlEntities(String raw) {
        if (raw == null) return "";
        String value = raw;
        for (int i = 0; i < 3; i++) {
            String before = value;
            value = value.replace("&nbsp;", " ").replace("&#160;", " ");
            value = value.replace("&quot;", "\"").replace("&#34;", "\"").replace("&apos;", "'").replace("&#039;", "'");
            value = value.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
            value = decodeNumericEntities(value);
            if (before.equals(value)) break;
        }
        return value;
    }

    private static String decodeNumericEntities(String value) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("&#(x?[0-9A-Fa-f]+);").matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String code = matcher.group(1);
            try {
                int radix = code.startsWith("x") || code.startsWith("X") ? 16 : 10;
                String number = radix == 16 ? code.substring(1) : code;
                matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(number, radix))));
            } catch(Exception e) {
                matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String normalizeChapterImageUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) return "";
        if (url.contains("/uploads/") && !url.contains("/storage/uploads/")) url = url.replace("/uploads/", "/storage/uploads/");
        url = url.replace(" ", "%20").replace("[", "%5B").replace("]", "%5D").replace("|", "%7C");
        return url;
    }

    private JsonArray mangaArray(JsonElement root) {
        if (root != null && root.isJsonArray()) return root.getAsJsonArray();
        JsonObject obj = root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
        if (obj == null) return new JsonArray();
        JsonArray mangaList = getArray(obj, "mangaList");
        if (mangaList.size() > 0) return mangaList;
        JsonArray data = getArray(obj, "data");
        if (data.size() > 0) return data;
        JsonArray items = getArray(obj, "items");
        if (items.size() > 0) return items;
        return new JsonArray();
    }

    private boolean hasNext(JsonElement root, int itemCount, int page) {
        if (root != null && root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            JsonObject pagination = getObject(obj, "pagination");
            if (pagination != null) {
                int totalPages = getInt(pagination, "totalPages", page);
                int currentPage = getInt(pagination, "page", page);
                return currentPage < totalPages;
            }
        }
        return itemCount >= LIMIT;
    }

    private static String decryptEncResp(String encResp, long timestampMillis) throws Exception {
        ArrayList<Long> buckets = new ArrayList<>();
        addBuckets(buckets, timestampMillis);
        addBuckets(buckets, System.currentTimeMillis());
        Exception last = null;
        for (long value : buckets) {
            try {
                String raw = decryptRaw(encResp, makeKey(value));
                return URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8");
            } catch(Exception e) { last = e; }
        }
        if (last != null) throw last;
        throw new IllegalArgumentException("Gagal decrypt _enc_resp_");
    }

    private static void addBuckets(ArrayList<Long> buckets, long timestampMillis) {
        if (timestampMillis <= 0L) return;
        long bucket = timestampMillis / 3600000L;
        long[] candidates = new long[]{bucket, bucket - 1L, bucket + 1L, bucket - 2L, bucket + 2L};
        for (long value : candidates) if (!buckets.contains(value)) buckets.add(value);
    }

    private static String makeKey(long hourBucket) {
        String input = DECRYPT_SALT + "_" + hourBucket;
        int hash = 0;
        for (int i = 0; i < input.length(); i++) hash = (hash << 5) - hash + input.charAt(i);
        long seed = Math.abs((long) hash);
        if (seed == 0L) seed = 123456789L;
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            seed = (seed * 1664525L + 1013904223L) % 4294967296L;
            key.append((char) (33 + seed % 93));
        }
        return key.toString();
    }

    private static String decryptRaw(String hex, String key) {
        ArrayList<Integer> bytes = new ArrayList<>();
        for (int i = 0; i + 1 < hex.length(); i += 2) bytes.add(Integer.parseInt(hex.substring(i, i + 2), 16));
        StringBuilder output = new StringBuilder();
        int state = 42;
        for (int i = 0; i < bytes.size(); i++) {
            int b = bytes.get(i);
            int keyByte = key.charAt(i % key.length());
            int value = b ^ keyByte ^ (i * 13) ^ state;
            output.append((char) (value & 255));
            state = (state + b) % 256;
        }
        return output.toString();
    }

    private static long parseHttpDate(String value) {
        if (value == null || value.trim().isEmpty()) return 0L;
        try {
            SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("GMT"));
            return format.parse(value).getTime();
        } catch(Exception e) { return 0L; }
    }

    public static String prettyDate(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String value = raw.trim();
        String[] patterns = new String[]{"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX"};
        for (String pattern : patterns) {
            try {
                SimpleDateFormat in = new SimpleDateFormat(pattern, Locale.US);
                in.setTimeZone(TimeZone.getTimeZone("UTC"));
                return new SimpleDateFormat("dd MMM yyyy", new Locale("id", "ID")).format(in.parse(value));
            } catch(Exception ignored) { }
        }
        return value.length() > 10 ? value.substring(0, 10) : value;
    }

    private DoujinFilterSpec parseFilterSpec(String raw) {
        DoujinFilterSpec spec = new DoujinFilterSpec();
        if (raw == null || raw.trim().isEmpty()) return spec;
        String[] parts = raw.split("\\|");
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (value.isEmpty()) continue;
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith("type:")) spec.type = value.substring(value.indexOf(":") + 1).trim();
            else if (lower.startsWith("status:")) spec.status = value.substring(value.indexOf(":") + 1).trim();
            else {
                if (lower.startsWith("genre:")) value = value.substring(value.indexOf(":") + 1).trim();
                if (lower.startsWith("genre/")) value = value.substring("genre/".length()).trim();
                spec.genre = value;
            }
        }
        return spec;
    }

    private String findChapterId(String slug, float index) {
        String key = slug + ":" + MangaChapter.formatIndex(index);
        String cached = CHAPTER_ID_CACHE.get(key);
        if (cached != null && !cached.trim().isEmpty()) return cached.trim();
        cached = CHAPTER_ID_CACHE.get(slug + ":" + index);
        if (cached != null && !cached.trim().isEmpty()) return cached.trim();
        ArrayList<MangaChapter> chapters = CHAPTER_CACHE.get(slug);
        if (chapters != null) {
            for (MangaChapter chapter : chapters) {
                if (chapter != null && Math.abs(chapter.index - index) < 0.001f && chapter.chapterId != null && !chapter.chapterId.trim().isEmpty()) return chapter.chapterId.trim();
            }
        }
        return "";
    }

    private static String cleanSlug(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.startsWith("http")) {
            int idx = value.indexOf("/manga/");
            if (idx >= 0) value = value.substring(idx + "/manga/".length());
        }
        value = value.split("\\?")[0];
        while (value.startsWith("/")) value = value.substring(1);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.startsWith("manga/")) value = value.substring("manga/".length());
        return value;
    }

    private static String urlSegment(String value) {
        return value == null ? "" : value.trim().replace(" ", "%20");
    }

    private static float numberFrom(String raw) {
        if (raw == null) return -1f;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(raw);
        if (!matcher.find()) return -1f;
        try { return Float.parseFloat(matcher.group(1)); } catch(Exception e) { return -1f; }
    }

    private static String inferTypeFromText(String raw) {
        String text = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (text.contains("manhwa")) return "manhwa";
        if (text.contains("doujinshi")) return "doujinshi";
        if (text.contains("manga")) return "manga";
        return "";
    }

    private static JsonObject getObject(JsonObject object, String key) {
        try { return object != null && object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : null; } catch(Exception e) { return null; }
    }

    private static JsonArray getArray(JsonObject object, String key) {
        try { return object != null && object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray(); } catch(Exception e) { return new JsonArray(); }
    }

    private static String getString(JsonObject object, String key) {
        try { return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : ""; } catch(Exception e) { return ""; }
    }

    private static int getInt(JsonObject object, String key, int def) {
        try { return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : def; } catch(Exception e) { return def; }
    }

    private static float getFloat(JsonObject object, String key, float def) {
        try { return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsFloat() : def; } catch(Exception e) { return def; }
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty() && !"null".equalsIgnoreCase(value.trim())) return value.trim();
        return "";
    }

    private static ArrayList<GenreItem> fallbackGenres() {
        ArrayList<GenreItem> out = new ArrayList<>();
        String[][] items = new String[][]{{"Ahegao","ahegao"},{"Anal","anal"},{"Big Breast","big-breast"},{"Blowjob","blowjob"},{"Bondage","bondage"},{"Cheating","cheating"},{"Dark Skin","dark-skin"},{"Elf","elf"},{"Femdom","femdom"},{"Futanari","futanari"},{"Group","group"},{"Harem","harem"},{"Lactation","lactation"},{"Maid","maid"},{"MILF","milf"},{"Mind Control","mind-control"},{"Netorare","netorare"},{"Paizuri","paizuri"},{"Schoolgirl Uniform","schoolgirl-uniform"},{"Tentacles","tentacles"},{"Vanilla","vanilla"},{"Yuri","yuri"}};
        for (String[] item : items) out.add(new GenreItem(item[0], item[1]));
        return out;
    }

    private static class DoujinFilterSpec {
        String genre = "";
        String type = "";
        String status = "";
    }

    private static class LatestInfo {
        final String chapter;
        final String date;
        LatestInfo(String chapter, String date) {
            this.chapter = chapter == null ? "" : chapter;
            this.date = date == null ? "" : date;
        }
    }
}
