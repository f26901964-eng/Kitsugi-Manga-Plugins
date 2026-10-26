package com.kitsugi.manga.smoketest;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import dalvik.system.PathClassLoader;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** No recreated host models: load the actual, unmodified, published Kitsugi APK. */
public final class SmokeInstrumentation extends Instrumentation {
    private ClassLoader host;
    private JSONObject report;
    private final JSONArray images = new JSONArray();
    private final JSONArray checks = new JSONArray();
    private final String id = "com_kitsugi_extension_tr_mangadenizi";

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Context app = getTargetContext();
        report = new JSONObject();
        boolean passed = false;
        try {
            report.put("androidApi", Build.VERSION.SDK_INT).put("testAppTargetSdk", app.getApplicationInfo().targetSdkVersion).put("hostRelease", "v2.4.381");
            report.put("checks", checks).put("images", images);
            File hostApk = new File(app.getFilesDir(), "host.apk");
            // Only the reference APK is read-only. Leave MEX writable, like the real installer.
            require(hostApk.setReadOnly(), "Reference APK is readable and read-only");
            host = new PathClassLoader(hostApk.getAbsolutePath(), app.getClassLoader());
            final ClassLoader realHost = host;
            Context context = new ContextWrapper(app) {
                @Override public ClassLoader getClassLoader() { return realHost; }
            };
            Class<?> contract = host.loadClass("com.kitsugi.animelist.data.manga.MangaSource");
            Class<?> loader = host.loadClass("com.kitsugi.animelist.data.manga.MangaExtensionLoader");
            Object singleton = loader.getField("INSTANCE").get(null);
            Method load = loader.getMethod("loadExtension", Context.class, String.class);
            Object source = load.invoke(singleton, context, id);
            boolean original = source != null;
            report.put("originalInstallerWritableMexLoaded", original);
            if (!original) {
                // Diagnose, don't conceal, the Android 14+ dynamic-code installer bug.
                File mex = new File(app.getFilesDir(), "manga_extensions/" + id + ".mex");
                report.put("explicitReadOnlyDiagnosticApplied", mex.setReadOnly());
                source = load.invoke(singleton, context, id);
                report.put("readOnlyDiagnosticMexLoaded", source != null);
            }
            require(source != null && contract.isInstance(source), "Actual host loader creates native MangaSource (no duplicate API classes)");
            require("MangaDenizi".equals(get(source, "getName")), "Source identity");
            require("tr".equals(get(source, "getLang")), "Turkish source language");
            require(Boolean.FALSE.equals(get(source, "isArchive")), "Normal network reader, not archive adapter");
            // All interface defaults are implemented by the plugin, rather than compile-only stubs.
            require("https://mangadenizi.net".equals(get(source, "getOriginalBaseUrl")), "Original URL ABI");
            Object popular = call(source, "fetchPopularManga", 1);
            require(!((List<?>) get(popular, "getMangas")).isEmpty(), "Popular catalogue in native source");
            Object second = call(source, "fetchPopularManga", 2);
            require(!((List<?>) get(second, "getMangas")).isEmpty(), "Native catalogue pagination");
            JSONObject expected = new JSONObject(readText(new File(app.getFilesDir(), "reference.json")));
            for (String slug : new String[]{"black-clover", "shadow-slave"}) {
                String title = slug.equals("black-clover") ? "Black Clover" : "Shadow Slave";
                String chapterSlug = slug.equals("black-clover") ? "392" : "07";
                String url = "https://mangadenizi.net/manga/" + slug;
                Object search = call(source, "fetchSearchManga", 1, title);
                boolean found = false;
                for (Object manga : (List<?>) get(search, "getMangas")) if (url.equals(get(manga, "getUrl"))) found = true;
                require(found, slug + ": native search returns the selected title");
                Object details = call(source, "fetchMangaDetails", url);
                require(title.equals(get(details, "getTitle")) && url.equals(get(details, "getUrl")), slug + ": native details belong to selected manga");
                require(get(details, "getThumbnailUrl") != null && get(details, "getDescription") != null, slug + ": cover and description present");
                List<?> chapters = (List<?>) call(source, "fetchChapterList", url);
                JSONObject summary = null;
                JSONArray summaries = expected.getJSONArray("series");
                for (int i = 0; i < summaries.length(); i++) if (slug.equals(summaries.getJSONObject(i).getString("slug"))) summary = summaries.getJSONObject(i);
                require(summary != null && chapters.size() == summary.getInt("chapterCount"), slug + ": full native chapter list");
                Object selected = null;
                float previous = Float.POSITIVE_INFINITY;
                for (Object chapter : chapters) {
                    float number = ((Number) get(chapter, "getChapterNumber")).floatValue();
                    require(number <= previous, slug + ": descending chapter order"); previous = number;
                    require(url.equals(get(chapter, "getMangaUrl")), slug + ": chapter belongs to selected manga");
                    if (((String) get(chapter, "getUrl")).endsWith("/" + chapterSlug)) selected = chapter;
                }
                require(selected != null, slug + ": requested chapter exists");
                List<?> pages = (List<?>) call(source, "fetchPageList", selected);
                require(pages.size() == summary.getInt("pageCount"), slug + ": all expected native pages");
                for (int i = 0; i < pages.size(); i++) {
                    Object page = pages.get(i);
                    require(((Number) get(page, "getIndex")).intValue() == i, slug + ": contiguous zero-based native page index");
                    require(call(source, "fetchImageUrl", page).equals(get(page, "getImageUrl")), slug + ": image URL override ABI");
                    Bitmap bitmap;
                    long started = System.currentTimeMillis();
                    try (InputStream input = (InputStream) call(source, "getImage", page)) { bitmap = BitmapFactory.decodeStream(input); }
                    require(bitmap != null, slug + ": page " + (i + 1) + " fully decoded by Android");
                    JSONObject reference = null;
                    JSONArray references = expected.getJSONArray("images");
                    for (int j = 0; j < references.length(); j++) {
                        JSONObject item = references.getJSONObject(j);
                        if (slug.equals(item.getString("slug")) && i == item.getInt("index")) reference = item;
                    }
                    require(reference != null && bitmap.getWidth() == reference.getInt("width") && bitmap.getHeight() == reference.getInt("height"), slug + ": page dimensions");
                    String sha = pixelHash(bitmap);
                    JSONObject image = new JSONObject().put("slug", slug).put("index", i).put("width", bitmap.getWidth()).put("height", bitmap.getHeight()).put("pixelSha256", sha).put("elapsedMs", System.currentTimeMillis() - started).put("matchesPublicReaderReference", sha.equals(reference.getString("pixelSha256")));
                    images.put(image);
                    bitmap.recycle();
                    require(image.getBoolean("matchesPublicReaderReference"), slug + ": Android page " + (i + 1) + " pixels equal validated public-reader reference");
                }
            }
            passed = original;
            report.put("status", original ? "passed" : "images_passed_requires_host_readonly_fix");
        } catch (Throwable error) {
            Throwable cause = unwrap(error);
            try { report.put("status", "failed").put("error", Log.getStackTraceString(cause)); } catch (Exception ignored) { }
            Log.e("MangaDeniziSmoke", "Native integration failed", cause);
        } finally {
            try {
                try (FileOutputStream file = new FileOutputStream(new File(app.getFilesDir(), "report.json"))) { file.write(report.toString(2).getBytes("UTF-8")); }
            } catch (Exception error) { Log.e("MangaDeniziSmoke", "Cannot save evidence", error); }
            Bundle results = new Bundle(); results.putString("status", passed ? "passed" : "failed");
            finish(passed ? -1 : 0, results);
        }
    }
    private Object get(Object value, String name) throws Exception { return value.getClass().getMethod(name).invoke(value); }
    private Object call(Object source, String name, Object... args) throws Exception {
        Class<?> continuation = host.loadClass("kotlin.coroutines.Continuation");
        Object empty = host.loadClass("kotlin.coroutines.EmptyCoroutineContext").getField("INSTANCE").get(null);
        Object suspended = host.loadClass("kotlin.coroutines.intrinsics.IntrinsicsKt").getMethod("getCOROUTINE_SUSPENDED").invoke(null);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Object> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Object callback = Proxy.newProxyInstance(host, new Class<?>[]{continuation}, (proxy, method, values) -> {
            if (method.getName().equals("getContext")) return empty;
            if (method.getName().equals("resumeWith")) {
                try { host.loadClass("kotlin.ResultKt").getMethod("throwOnFailure", Object.class).invoke(null, values[0]); result.set(values[0]); }
                catch (Throwable error) { failure.set(unwrap(error)); }
                done.countDown(); return null;
            }
            if (method.getName().equals("toString")) return "MangaSmokeContinuation";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == values[0];
            return null;
        });
        Method selected = null;
        for (Method method : source.getClass().getMethods()) if (method.getName().equals(name) && method.getParameterTypes().length == args.length + 1) { selected = method; break; }
        if (selected == null) throw new NoSuchMethodException(name);
        Object[] parameters = new Object[args.length + 1]; System.arraycopy(args, 0, parameters, 0, args.length); parameters[args.length] = callback;
        Object returned = selected.invoke(source, parameters);
        if (returned != suspended) return returned;
        if (!done.await(120, TimeUnit.SECONDS)) throw new IllegalStateException("Native coroutine timed out: " + name);
        if (failure.get() != null) throw new RuntimeException("Native source failed: " + name, failure.get());
        return result.get();
    }
    private void require(boolean condition, String name) throws Exception {
        // Keep repeated chapter/page invariants compact without claiming unexecuted checks.
        if (!condition) { checks.put(new JSONObject().put("name", name).put("passed", false)); throw new IllegalStateException(name); }
        if (checks.length() < 35) checks.put(new JSONObject().put("name", name).put("passed", true));
    }
    private static Throwable unwrap(Throwable error) { while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause(); return error; }
    private static String readText(File file) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(file)) { byte[] buffer = new byte[16384]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n); }
        return out.toString("UTF-8");
    }
    private static String pixelHash(Bitmap bitmap) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256"); int width = bitmap.getWidth();
        int[] row = new int[width]; byte[] rgba = new byte[width * 4];
        for (int y = 0; y < bitmap.getHeight(); y++) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) { int p = row[x]; rgba[x * 4] = (byte) (p >>> 16); rgba[x * 4 + 1] = (byte) (p >>> 8); rgba[x * 4 + 2] = (byte) p; rgba[x * 4 + 3] = (byte) (p >>> 24); }
            hash.update(rgba);
        }
        StringBuilder text = new StringBuilder(); for (byte b : hash.digest()) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return text.toString();
    }
}
