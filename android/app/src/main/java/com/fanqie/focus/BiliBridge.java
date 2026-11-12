package com.fanqie.focus;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网页和 B 站之间的原生桥。
 *
 * 网页里跑在 WebView 的 https://appassets 域下，直接 fetch b23.tv / api.bilibili.com 会被 CORS 拦住，
 * 短链也没法跟随 302。所以这几件事全部挪到原生：
 *   1. 解析分享文本（b23.tv 短链 → BV 号 → 标题/时长/封面）
 *   2. 把封面缓存成本地文件，图片不走外网
 *   3. 取音轨并下载到 App 私有目录，离线可播
 */
public class BiliBridge {

    private static final String TAG = "BiliBridge";
    /**
     * 必须用桌面浏览器的 UA，不要改回 Android 手机 UA。
     *
     * 2026-11-05 实测：同一个音轨地址，带 Android UA 请求 upos / mountaintoys 节点
     * 一律 403，换成桌面 Chrome UA 立刻 206，只加 Cookie、换 Referer 都没用。
     * 也就是说之前「只有 mcdn 放行」的结论是 UA 造成的假象：mcdn 不校验 UA，
     * 一旦接口只给 upos 节点（比如现在这条合集），下载就必然全 403。
     *
     * 已验证换成桌面 UA 后 finger/spi、web-interface/view、b23.tv 短链 302 都正常。
     */
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.bilibili.com/";
    private static final String ORIGIN = "https://www.bilibili.com";

    private static final Pattern BV_PATTERN = Pattern.compile("BV[0-9A-Za-z]{10}");
    private static final Pattern AV_PATTERN = Pattern.compile("(?:^|[^0-9A-Za-z])av(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORT_PATTERN = Pattern.compile("https?://b23\\.tv/[0-9A-Za-z]+");

    private final Context context;
    // 通知权限要在 Activity 上申请，单独留一份引用（Application context 拿不到）
    private final Activity activity;
    private final WebView webView;
    private final File mediaDir;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    // 下载串行执行：B 站音频 CDN 对并发连接很敏感，并发多了会直接 403
    private final ExecutorService downloadPool = Executors.newSingleThreadExecutor();
    private final Set<String> downloading = Collections.synchronizedSet(new HashSet<String>());
    // 设备标识：B 站接口和音频 CDN 没有 buvid3 时会直接回 403，取一次缓存下来复用
    private volatile String buvidCookie = "";

    public BiliBridge(Context context, WebView webView) {
        this.activity = context instanceof Activity ? (Activity) context : null;
        this.context = context.getApplicationContext();
        this.webView = webView;
        this.mediaDir = new File(this.context.getFilesDir(), "media");
        //noinspection ResultOfMethodCallIgnored
        new File(mediaDir, "covers").mkdirs();
        //noinspection ResultOfMethodCallIgnored
        new File(mediaDir, "audio").mkdirs();
    }

    public File getMediaDir() {
        return mediaDir;
    }

    // ---------------------------------------------------------------- 解析链接

    /**
     * 解析一段分享文本。结果通过 window.__potatoResolve(reqId, json) 回传。
     */
    @JavascriptInterface
    public void resolve(final String text, final String reqId) {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                JSONObject out = new JSONObject();
                try {
                    String bvid = findBvid(text);
                    String resolvedUrl = text;
                    if (bvid == null) {
                        String shortUrl = findShortUrl(text);
                        if (shortUrl != null) {
                            resolvedUrl = followRedirects(shortUrl);
                            bvid = findBvid(resolvedUrl);
                        }
                    }
                    if (bvid == null) {
                        String aid = findAid(text);
                        if (aid != null) {
                            JSONObject byAid = fetchViewByAid(aid);
                            if (byAid != null) bvid = byAid.optString("bvid", "");
                        }
                    }
                    if (bvid == null) {
                        out.put("ok", false);
                        out.put("error", "没认出视频号，试试粘贴完整的分享链接");
                        emitResolve(reqId, out);
                        return;
                    }
                    JSONObject data = fetchView(bvid);
                    if (data == null) {
                        out.put("ok", false);
                        out.put("bvid", bvid);
                        out.put("error", "视频信息读取失败，请检查网络后重试");
                        emitResolve(reqId, out);
                        return;
                    }
                    out.put("ok", true);
                    out.put("bvid", data.optString("bvid", bvid));
                    out.put("title", data.optString("title", ""));
                    long cid = data.optLong("cid", 0L);
                    int duration = data.optInt("duration", 0);
                    // 多 P 视频按链接里的 ?p= 取对应分 P 的音轨和时长
                    JSONArray pages = data.optJSONArray("pages");
                    if (pages != null && pages.length() > 0) {
                        int page = pageOf(resolvedUrl);
                        int index = Math.min(Math.max(page, 1), pages.length()) - 1;
                        JSONObject pg = pages.optJSONObject(index);
                        if (pg != null) {
                            cid = pg.optLong("cid", cid);
                            duration = pg.optInt("duration", duration);
                            out.put("page", index + 1);
                            out.put("pages", pages.length());
                        }
                    }
                    out.put("duration", duration);
                    out.put("cid", cid);
                    String pic = data.optString("pic", "");
                    if (pic.startsWith("http://")) pic = "https://" + pic.substring(7);
                    if (!pic.isEmpty()) {
                        String local = cacheCover(out.optString("bvid", bvid), pic);
                        out.put("cover", local != null ? local : pic);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "resolve failed", e);
                    try {
                        out.put("ok", false);
                        out.put("error", "解析失败：" + e.getClass().getSimpleName());
                    } catch (Exception ignored) {
                    }
                }
                emitResolve(reqId, out);
            }
        });
    }

    /**
     * 问一下这个视频的音轨下载好了没有。同步返回，页面渲染时直接调。
     * 顺带把音频真实时长读出来，页面不用等 loadedmetadata 才知道总长度。
     */
    @JavascriptInterface
    public String audioInfo(String bvid, String cidStr) {
        JSONObject out = new JSONObject();
        try {
            File f = findAudioFile(bvid, parseLong(cidStr));
            boolean exists = f != null && f.exists() && f.length() > 1024;
            out.put("exists", exists);
            if (exists) {
                out.put("size", f.length());
                out.put("url", mediaUrl("audio/" + f.getName()));
                out.put("duration", audioDurationMs(f));
                // 告诉页面实际用的是哪个 cid，页面把它记进合集，下次就能直接命中
                out.put("cid", cidOfFile(f.getName()));
            }
        } catch (Exception ignored) {
        }
        return out.toString();
    }

    /** 读音频真实时长（毫秒）。读不出来就返回 0，让页面退回自己的估算。 */
    private long audioDurationMs(File f) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(f.getAbsolutePath());
            String raw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (raw == null) return 0L;
            try {
                return Long.parseLong(raw.trim());
            } catch (NumberFormatException e) {
                return 0L;
            }
        } catch (Exception e) {
            return 0L;
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** 从 "BVxxx_123.m4a" 里抠出 cid。 */
    private static long cidOfFile(String name) {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        int under = stem.lastIndexOf('_');
        if (under < 0) return 0L;
        try {
            return Long.parseLong(stem.substring(under + 1));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 下载音轨。进度走 window.__potatoAudioProgress(collectionId, percent)，
     * 结束走 window.__potatoAudioDone(collectionId, json)。
     */
    @JavascriptInterface
    public void downloadAudio(final String bvid, final String cidStr, final String tag) {
        if (bvid == null || bvid.isEmpty()) {
            emitAudioDone(tag, false, "缺少视频号");
            return;
        }
        if (!downloading.add(bvid)) {
            emitAudioDone(tag, false, "这个音轨正在下载中");
            return;
        }
        downloadPool.execute(new Runnable() {
            @Override
            public void run() {
                long cid = parseLong(cidStr);
                IOException lastErr = null;
                try {
                    if (cid <= 0) {
                        JSONObject view = fetchView(bvid);
                        if (view == null) throw new IOException("视频信息读取失败");
                        cid = view.optLong("cid", 0L);
                    }
                    if (cid <= 0) throw new IOException("没有找到分P信息");

                    File target = audioFile(bvid, cid);
                    if (target == null) throw new IOException("无法创建音频目录");
                    if (target.exists() && target.length() > 1024) {
                        JSONObject done = new JSONObject();
                        done.put("ok", true);
                        done.put("size", target.length());
                        done.put("url", mediaUrl("audio/" + target.getName()));
                        done.put("cid", cid);
                        done.put("duration", audioDurationMs(target));
                        done.put("message", "已经下载过了");
                        emit(js("__potatoAudioDone", tag, done.toString()));
                        return;
                    }

                    File tmp = new File(target.getParentFile(), target.getName() + ".part");
                    // 上次没下完的残留，别接着写，从头来过
                    safeDelete(tmp);
                    long downloaded = 0;
                    // 接口每次返回的 CDN 节点不一样，而且只有一部分节点是放行的。
                    // 多调几次把地址攒起来，挨个试，谁能用就用谁。
                    List<String> allUrls = new ArrayList<>();
                    // 每个节点最后一次的真实结果（403 / 连接被拒 / 超时）。
                    // 1.1.4 及以前这里一律写成「403」，连不上网也报 403，除了误导没别的用处。
                    Map<String, String> lastByHost = new LinkedHashMap<>();
                    Map<String, Integer> failedTimes = new HashMap<>();
                    // 播放地址带过期签名，403 基本都是地址失效了：每次失败都重取一遍地址，
                    // 再把主地址和备用地址挨个试一遍。
                    for (int attempt = 0; attempt < 6 && downloaded <= 1024; attempt++) {
                        if (attempt > 0) {
                            safeDelete(tmp);
                            Thread.sleep(Math.min(6000L, 1200L * attempt));
                        }
                        // 连续 403 也可能是这个 buvid 被 CDN 短时拉黑了，
                        // 换一个设备标识再试，比一直用同一个 ID 硬撞更容易过。
                        if (attempt == 2 || attempt == 4) {
                            resetBuvid();
                            Log.w(TAG, "retry with a fresh buvid");
                        }
                        List<String> urls = fetchAudioUrls(bvid, cid);
                        if (urls.isEmpty()) {
                            lastErr = new IOException("这个视频取不到音轨（可能是付费或番剧）");
                            continue;
                        }
                        for (String u : urls) {
                            if (!allUrls.contains(u)) allUrls.add(u);
                        }
                        // 直接下，不再先发一个 Range: bytes=0-1 的探测请求。
                        // 有的 mcdn 节点见到这种超小 Range 会直接 403，于是探测判死 →
                        // 唯一能用的节点被跳过 → 整个下载注定失败（1.1.5 修掉这个假阴性）。
                        // 真正下载时第一件事就是读响应码，403 一样很快返回，探测其实省不了什么。
                        for (String audioUrl : allUrls) {
                            Integer bad = failedTimes.get(audioUrl);
                            if (bad != null && bad >= 2) continue;
                            try {
                                downloaded = download(audioUrl, tmp, tag);
                                if (downloaded > 1024) break;
                                lastErr = new IOException("下载到的文件不完整");
                            } catch (IOException e) {
                                lastErr = e;
                                failedTimes.put(audioUrl, bad == null ? 1 : bad + 1);
                                lastByHost.put(hostOf(audioUrl), shortError(e));
                                Log.w(TAG, "audio failed at " + hostOf(audioUrl) + ": " + e.getMessage());
                                safeDelete(tmp);
                            }
                        }
                    }
                    if (downloaded <= 1024) {
                        appendDownloadLog(bvid, cid, lastByHost, lastErr);
                        throw new IOException(describeFailures(lastByHost, lastErr));
                    }

                    if (target.exists()) //noinspection ResultOfMethodCallIgnored
                        target.delete();
                    if (!tmp.renameTo(target)) throw new IOException("保存文件失败");

                    JSONObject ok = new JSONObject();
                    ok.put("ok", true);
                    ok.put("size", target.length());
                    ok.put("url", mediaUrl("audio/" + target.getName()));
                    // 把实际用的 cid 告诉页面，页面回填进合集，下次能直接命中文件
                    ok.put("cid", cid);
                    ok.put("duration", audioDurationMs(target));
                    emit(js("__potatoAudioDone", tag, ok.toString()));
                } catch (Exception e) {
                    Log.w(TAG, "download failed", e);
                    File part = audioFile(bvid, cid);
                    if (part != null) {
                        File f = new File(part.getParentFile(), part.getName() + ".part");
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                    emitAudioDone(tag, false, friendlyError(e));
                } finally {
                    downloading.remove(bvid);
                }
            }
        });
    }

    @JavascriptInterface
    public void deleteAudio(String bvid, String cidStr) {
        File f = findAudioFile(bvid, parseLong(cidStr));
        if (f != null && f.exists()) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    /**
     * 页面同步专注状态，用来起停前台服务。
     * state 取 "playing"（专注中）、"paused"（已暂停）、"stop"（结束）。
     */
    @JavascriptInterface
    public void setFocusState(String state) {
        if (state == null) return;
        if ("stop".equals(state)) {
            FocusService.stop(context);
            return;
        }
        boolean playing = !"paused".equals(state);
        // 前台服务的常驻通知要通知权限，第一次开始专注时问一次最自然
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).ensureNotificationPermission();
        }
        FocusService.start(context, playing);
    }

    /** 用系统里已安装的 B 站 App 打开，没装就交给浏览器。 */
    @JavascriptInterface
    public void openBili(String url) {        if (url == null || url.isEmpty()) return;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (isPackageInstalled("tv.danmaku.bili")) {
                Intent app = new Intent(intent);
                app.setPackage("tv.danmaku.bili");
                context.startActivity(app);
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "open bili failed", e);
        }
    }

    private boolean isPackageInstalled(String pkg) {
        try {
            return context.getPackageManager().getLaunchIntentForPackage(pkg) != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 网络

    private String findBvid(String text) {
        if (text == null) return null;
        Matcher m = BV_PATTERN.matcher(text);
        if (m.find()) return "BV" + m.group().substring(2);
        return null;
    }

    private String findShortUrl(String text) {
        if (text == null) return null;
        Matcher m = SHORT_PATTERN.matcher(text);
        return m.find() ? m.group() : null;
    }

    private String findAid(String text) {
        if (text == null) return null;
        Matcher m = AV_PATTERN.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** 分享链接里的分 P 序号，默认第 1 P。 */
    private int pageOf(String text) {
        if (text == null) return 1;
        Matcher m = Pattern.compile("[?&]p=(\\d+)", Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 1;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 手动跟随 302，b23.tv 的最终地址里才有 BV 号。 */
    private String followRedirects(String url) {
        String current = url;
        for (int i = 0; i < 6; i++) {
            HttpURLConnection conn = null;
            try {
                conn = open(current);
                conn.setInstanceFollowRedirects(false);
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isEmpty()) break;
                    current = new URL(new URL(current), location).toString();
                    if (findBvid(current) != null) return current;
                    continue;
                }
                if (code == 200) {
                    String body = readAll(conn.getInputStream());
                    Matcher m = BV_PATTERN.matcher(body);
                    if (m.find()) return "https://www.bilibili.com/video/" + m.group();
                }
                break;
            } catch (Exception e) {
                Log.w(TAG, "redirect failed at " + current, e);
                break;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return current;
    }

    private JSONObject fetchView(String bvid) {
        return fetchViewQuery("bvid=" + Uri.encode(bvid));
    }

    private JSONObject fetchViewByAid(String aid) {
        return fetchViewQuery("aid=" + Uri.encode(aid));
    }

    private JSONObject fetchViewQuery(String query) {
        String url = "https://api.bilibili.com/x/web-interface/view?" + query;
        JSONObject root = getJson(url);
        if (root == null || root.optInt("code", -1) != 0) return null;
        return root.optJSONObject("data");
    }

    /**
     * CDN 校验 buvid：既要在 Cookie 里，也要在 URL 的查询参数里。
     * 接口返回的地址没带 buvid 时，这里自己补上，
     * 探测和真正下载都要用补全后的同一个 URL，结果才准。
     */
    private String withBuvid(String url) {
        String cookie = ensureBuvid();
        if (cookie.isEmpty() || url.indexOf("buvid=") >= 0) return url;
        int semi = cookie.indexOf(';');
        String b3 = semi > 0
                ? cookie.substring("buvid3=".length(), semi)
                : cookie.substring("buvid3=".length());
        return url + (url.indexOf('?') >= 0 ? "&" : "?") + "buvid=" + Uri.encode(b3);
    }

    /**
     * 探一下这个 CDN 地址能不能取到东西（只请求首字节，很快）。
     * B 站同一个视频会给出好几个 CDN 节点，有的放行、有的回 403，
     * 先探一遍能省下大量无效下载。
     *
     * 注意：1.1.5 起下载流程不再调用它。实测部分 mcdn 节点会直接拒绝
     * Range: bytes=0-1 这种超小范围请求，把唯一能用的节点判成死的。
     * 留着是给需要单独体检的地方用。
     */
    private boolean isReachable(String url) {
        HttpURLConnection conn = null;
        try {
            conn = open(withBuvid(url));
            conn.setRequestProperty("Range", "bytes=0-");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            int code = conn.getResponseCode();
            return code == 200 || code == 206;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 把一次失败压成几个字，用来告诉用户到底是哪一步被拒的。 */
    private static String shortError(IOException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.startsWith("音轨服务器返回 ")) return msg.substring("音轨服务器返回 ".length());
        if (msg.contains("ENOSPC")) return "空间不足";
        if (msg.contains("Connection refused") || msg.contains("ECONNREFUSED")) return "连接被拒";
        if (msg.contains("timeout") || msg.contains("timed out")) return "超时";
        if (msg.contains("ENETUNREACH") || msg.contains("EHOSTUNREACH")) return "网络不可达";
        if (msg.contains("Unable to resolve host") || msg.contains("UnknownHost")) return "域名解析失败";
        if (msg.contains("SSL") || msg.contains("handshake")) return "TLS 失败";
        return msg.isEmpty() ? "失败" : msg;
    }

    /** 节点名太长，取一眼能认出来的前一段。 */
    private static String shortHost(String host) {
        if (host == null || host.isEmpty()) return "?";
        if (host.contains("mcdn.bilivideo.cn")) return "mcdn";
        if (host.contains("mountaintoys")) return "edge";
        if (host.contains("bilivideo.com")) {
            int dot = host.indexOf('.');
            return dot > 0 ? host.substring(0, dot) : host;
        }
        return host;
    }

    /**
     * 把「哪个节点回了什么」拼成一句话。
     * 以前不管三七二十一都说 403，连不上网也说 403，等于把排查线索全掐了。
     */
    private static String describeFailures(Map<String, String> lastByHost, Exception lastErr) {
        if (lastByHost == null || lastByHost.isEmpty()) {
            String msg = lastErr == null ? null : lastErr.getMessage();
            if (msg == null || msg.isEmpty()) return "下载失败，请重试";
            if (msg.contains("ENOSPC")) return "手机空间不足";
            return msg;
        }
        StringBuilder body = new StringBuilder();
        boolean all403 = true;
        int shown = 0;
        for (Map.Entry<String, String> en : lastByHost.entrySet()) {
            String err = en.getValue() == null ? "" : en.getValue();
            if (!"403".equals(err)) all403 = false;
            if (shown < 3) {
                if (shown > 0) body.append("、");
                body.append(shortHost(en.getKey())).append(' ').append(err);
                shown++;
            }
        }
        if (lastByHost.size() > shown) {
            body.append(" 等 ").append(lastByHost.size()).append(" 个节点");
        }
        if (all403) {
            return "音轨服务器拒绝访问（403）：" + body
                    + "。切一下网络（Wi-Fi ↔ 流量）或等 1-2 分钟再点一次下载";
        }
        return "音轨下载失败：" + body + "。这次不是 403，先看下手机的网络";
    }

    /** 把失败原因记一行到 App 私有目录，事后能翻，也方便截图对照。 */
    private void appendDownloadLog(String bvid, long cid,
                                   Map<String, String> lastByHost, Exception lastErr) {
        StringBuilder sb = new StringBuilder();
        sb.append(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append("  ").append(bvid).append(" cid=").append(cid)
                .append("  ").append(lastErr == null || lastErr.getMessage() == null
                        ? "" : lastErr.getMessage()).append('\n');
        if (lastByHost != null) {
            for (Map.Entry<String, String> en : lastByHost.entrySet()) {
                sb.append("    ").append(en.getKey()).append(" -> ").append(en.getValue()).append('\n');
            }
        }
        File log = new File(mediaDir, "audio-download.log");
        try {
            // 只留最近的记录，别让它无限长
            if (log.exists() && log.length() > 64 * 1024) //noinspection ResultOfMethodCallIgnored
                log.delete();
            FileOutputStream out = new FileOutputStream(log, true);
            out.write(sb.toString().getBytes("UTF-8"));
            out.close();
        } catch (Exception e) {
            Log.w(TAG, "write download log failed", e);
        }
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * 取这个视频的音轨地址（未登录也能拿到，付费内容除外）。
     * 主地址（baseUrl）经常单独失效，所以把 backupUrl 一起收集成候选列表，
     * 挨个试，谁能用就用谁。
     */
    private List<String> fetchAudioUrls(String bvid, long cid) {
        // 关键：必须先拿到 buvid3 再调 playurl。
        // B 站 CDN 校验 buvid，接口返回的播放地址里会带上 buvid 参数，
        // 匿名（没带 buvid）调用接口拿到的地址不带这个参数，
        // CDN 一律回 403 —— 表现就是「前几个合集能下，第 5 个开始就 403」。
        String buvid = ensureBuvid();
        String url = "https://api.bilibili.com/x/player/playurl?bvid=" + Uri.encode(bvid)
                + "&cid=" + cid + "&fnval=16&fourk=1";
        if (!buvid.isEmpty()) {
            // buvid3=xxx; buvid4=yyy → 取 buvid3 的值
            int semi = buvid.indexOf(';');
            String b3 = semi > 0 ? buvid.substring("buvid3=".length(), semi) : buvid.substring("buvid3=".length());
            url += "&buvid3=" + Uri.encode(b3);
        }
        JSONObject root = getJson(url);
        Log.w(TAG, "playurl bvid=" + bvid + " cookie=" + buvid + " rootCode="
                + (root == null ? "null" : root.optInt("code", -1))
                + " msg=" + (root == null ? "-" : root.optString("message", "")));
        if (root == null) return Collections.emptyList();
        JSONObject data = root.optJSONObject("data");
        if (data == null) return Collections.emptyList();
        JSONObject dash = data.optJSONObject("dash");
        if (dash == null) return Collections.emptyList();
        JSONArray audio = dash.optJSONArray("audio");
        if (audio == null || audio.length() == 0) return Collections.emptyList();

        // 按码率从高到低排，优先用清晰的
        List<JSONObject> tracks = new ArrayList<>();
        for (int i = 0; i < audio.length(); i++) {
            JSONObject a = audio.optJSONObject(i);
            if (a != null) tracks.add(a);
        }
        Collections.sort(tracks, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject x, JSONObject y) {
                return y.optInt("bandwidth", 0) - x.optInt("bandwidth", 0);
            }
        });

        List<String> out = new ArrayList<>();
        for (JSONObject a : tracks) {
            addUrl(out, a.optString("baseUrl", a.optString("base_url", "")));
            JSONArray backups = a.optJSONArray("backupUrl");
            if (backups == null) backups = a.optJSONArray("backup_url");
            if (backups != null) {
                for (int i = 0; i < backups.length(); i++) {
                    addUrl(out, backups.optString(i, ""));
                }
            }
        }
        // 同一个接口会返回好几个 CDN 节点。1.1.1 时以为 upos-*、*.edge.mountaintoys.cn
        // 一律 403、只有 mcdn 放行，其实那是手机 UA 被 CDN 拒了（见 UA 处的说明）。
        // 现在三类节点都能下，排序只是让 mcdn 先试——它通常最快，失败也不影响别的节点。
        Collections.sort(out, new Comparator<String>() {
            @Override
            public int compare(String x, String y) {
                // cdnRank 越小越优先，所以这里是升序
                return Integer.compare(cdnRank(x), cdnRank(y));
            }
        });
        return out;
    }

    /** CDN 优先级：0=优先尝试，1=备用。 */
    private static int cdnRank(String url) {
        if (url == null) return 2;
        if (url.contains("mcdn.bilivideo.cn")) return 0;
        if (url.contains("bilivideo.cn")) return 1;
        return 2;
    }

    private static void addUrl(List<String> list, String url) {
        if (url == null || url.isEmpty()) return;
        if (list.contains(url)) return;
        list.add(url);
    }

    private String cacheCover(String bvid, String picUrl) {
        if (bvid == null || bvid.isEmpty()) return null;
        File dir = new File(mediaDir, "covers");
        File f = new File(dir, bvid + ".jpg");
        if (f.exists() && f.length() > 1024) return mediaUrl("covers/" + bvid + ".jpg");
        HttpURLConnection conn = null;
        try {
            conn = open(picUrl);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            if (conn.getResponseCode() != 200) return null;
            File tmp = new File(dir, bvid + ".part");
            copy(conn.getInputStream(), new FileOutputStream(tmp));
            if (tmp.length() < 1024) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return null;
            }
            if (f.exists()) //noinspection ResultOfMethodCallIgnored
                f.delete();
            return tmp.renameTo(f) ? mediaUrl("covers/" + bvid + ".jpg") : null;
        } catch (Exception e) {
            Log.w(TAG, "cover failed", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 下载音频，返回文件大小。进度按百分比回调。 */
    private long download(String url, File target, String tag) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = open(withBuvid(url));
            // B 站音频 CDN 不加 Range 头经常直接回 403，带上后正常返回 206
            conn.setRequestProperty("Range", "bytes=0-");
            // 连接阶段短一点：节点被墙/端口被封时要快速换下一个，别让用户干等
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(30000);
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) {
                Log.w(TAG, "download http " + code + " host=" + conn.getURL().getHost()
                        + " msg=" + conn.getResponseMessage());
                throw new IOException("音轨服务器返回 " + code);
            }
            long total = conn.getContentLengthLong();
            InputStream in = new BufferedInputStream(conn.getInputStream(), 64 * 1024);
            FileOutputStream out = new FileOutputStream(target);
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int lastPercent = -1;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int percent = (int) (done * 100 / total);
                    if (percent != lastPercent && percent % 2 == 0) {
                        lastPercent = percent;
                        emit(js("__potatoAudioProgress", tag, String.valueOf(percent)));
                    }
                }
            }
            out.flush();
            out.close();
            in.close();
            return done;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private JSONObject getJson(String url) {
        HttpURLConnection conn = null;
        try {
            conn = open(url);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            if (conn.getResponseCode() != 200) return null;
            String body = readAll(conn.getInputStream());
            return new JSONObject(body);
        } catch (Exception e) {
            Log.w(TAG, "http failed: " + url, e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private HttpURLConnection open(String url) throws IOException {
        URLConnection c = new URL(url).openConnection();
        HttpURLConnection conn = (HttpURLConnection) c;
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Referer", REFERER);
        conn.setRequestProperty("Origin", ORIGIN);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        conn.setRequestProperty("Accept-Encoding", "identity");
        String cookie = ensureBuvid();
        if (!cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        return conn;
    }

    /**
     * 拿一个 buvid3 设备标识。没有它，B 站的音频 CDN 会把请求当成爬虫直接回 403。
     * 走的是公开的 spi 接口，拿到一次就一直复用。
     */
    private String ensureBuvid() {
        String cached = buvidCookie;
        if (!cached.isEmpty()) return cached;
        synchronized (this) {
            if (!buvidCookie.isEmpty()) return buvidCookie;
            String b3 = "";
            String b4 = "";
            HttpURLConnection conn = null;
            try {
                // 这里不能用 open()，否则会递归回自己
                conn = (HttpURLConnection) new URL("https://api.bilibili.com/x/frontend/finger/spi")
                        .openConnection();
                conn.setRequestProperty("User-Agent", UA);
                conn.setRequestProperty("Referer", REFERER);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(10000);
                if (conn.getResponseCode() == 200) {
                    JSONObject root = new JSONObject(readAll(conn.getInputStream()));
                    JSONObject data = root.optJSONObject("data");
                    if (data != null) {
                        b3 = data.optString("b_3", "");
                        b4 = data.optString("b_4", "");
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "buvid fetch failed", e);
            } finally {
                if (conn != null) conn.disconnect();
            }
            if (b3.isEmpty()) return "";
            buvidCookie = "buvid3=" + b3 + (b4.isEmpty() ? "" : "; buvid4=" + b4);
            return buvidCookie;
        }
    }

    /** 丢掉缓存的 buvid3，下一次请求会重新领一个。 */
    private void resetBuvid() {
        buvidCookie = "";
    }

    private static void safeDelete(File f) {
        if (f != null && f.exists()) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return new String(out.toByteArray(), "UTF-8");
    }

    private static void copy(InputStream in, java.io.OutputStream out) throws IOException {
        try {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            try {
                out.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- 工具

    private File audioFile(String bvid, long cid) {
        if (bvid == null || bvid.isEmpty()) return null;
        String name = cid > 0 ? bvid + "_" + cid : bvid;
        return new File(new File(mediaDir, "audio"), name + ".m4a");
    }

    /**
     * 找已下载的音轨文件。
     * 老合集里没存 cid，页面查的时候只会传 0，而下载时用的是真实 cid，
     * 所以 cid 对不上时要按 bvid 前缀在目录里扫一遍，不能直接说「没下载过」。
     */
    private File findAudioFile(String bvid, long cid) {
        if (bvid == null || bvid.isEmpty()) return null;
        File dir = new File(mediaDir, "audio");
        if (cid > 0) {
            File exact = new File(dir, bvid + "_" + cid + ".m4a");
            if (exact.exists()) return exact;
        }
        File plain = new File(dir, bvid + ".m4a");
        if (plain.exists()) return plain;

        File[] files = dir.listFiles();
        if (files == null) return null;
        File best = null;
        long bestCid = Long.MAX_VALUE;
        for (File f : files) {
            String name = f.getName();
            if (!name.startsWith(bvid + "_") || !name.endsWith(".m4a")) continue;
            long fileCid = cidOfFile(name);
            if (fileCid <= 0) continue;
            if (cid > 0 && fileCid == cid) return f;
            // 同一个视频存了多个分 P 的音轨，取 cid 最小的那条（通常是第 1 P）
            if (best == null || fileCid < bestCid) {
                best = f;
                bestCid = fileCid;
            }
        }
        return best;
    }

    private String mediaUrl(String path) {
        return "https://" + MainActivity.HOST + "/media/" + path;
    }

    private String friendlyError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return "下载失败，请重试";
        if (msg.contains("ENOSPC")) return "手机空间不足";
        // 403 的具体说明由 describeFailures() 拼好，这里不要再用笼统文案盖掉它
        if (msg.contains("timeout") || msg.contains("Timeout")) return "网络超时，请重试";
        return msg;
    }

    private void emitResolve(String reqId, JSONObject payload) {
        emit(js("__potatoResolve", reqId, payload.toString()));
    }

    private void emitAudioDone(String tag, boolean ok, String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", ok);
            o.put("message", message == null ? "" : message);
            emit(js("__potatoAudioDone", tag, o.toString()));
        } catch (Exception ignored) {
        }
    }

    private static String js(String fn, String arg, String payload) {
        return "(function(){if(typeof window." + fn + "==='function'){window." + fn + "("
                + JSONObject.quote(arg) + "," + JSONObject.quote(payload) + ");}})();";
    }

    private void emit(final String script) {
        if (webView == null) return;
        webView.post(new Runnable() {
            @Override
            public void run() {
                try {
                    webView.evaluateJavascript(script, null);
                } catch (Exception e) {
                    Log.w(TAG, "evaluate failed", e);
                }
            }
        });
    }

    static String mimeOf(String path) {
        String p = path.toLowerCase(Locale.US);
        if (p.endsWith(".m4a") || p.endsWith(".mp4") || p.endsWith(".m4s")) return "audio/mp4";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }
}
