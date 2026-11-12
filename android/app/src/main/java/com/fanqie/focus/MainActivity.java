package com.fanqie.focus;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.webkit.WebViewAssetLoader;

import java.io.File;

/**
 * 番茄专注 —— 原生 WebView 壳。
 * 页面本体是 assets/www/index.html（设计原型原样放入），
 * 这里只负责：安全区适配、外部链接跳转、返回键、主题色状态栏。
 */
public class MainActivity extends Activity {

    public static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/www/index.html";
    private static final String BG = "#e7ebf2";

    /** 系统文件选择器（换头像）的请求码。 */
    private static final int REQ_PICK_IMAGE = 1001;
    /** 通知权限（前台服务常驻通知）的请求码。 */
    private static final int REQ_NOTICE_PERM = 1002;

    /**
     * 返回键先交给页面：从最上层开始找可见的关闭按钮，点到就返回 true。
     * 找不到则返回 false，由原生退出 App。
     */
    private static final String JS_HANDLE_BACK =
            "(function(){"
                    + "function vis(el){if(!el)return false;var r=el.getBoundingClientRect();"
                    + "if(r.width<=0||r.height<=0)return false;var s=getComputedStyle(el);"
                    + "return s.visibility!=='hidden'&&s.display!=='none'&&s.opacity!=='0';}"
                    + "var groups=['[data-action=\"close-dialog\"]','[data-action=\"close-player\"]'];"
                    + "for(var g=0;g<groups.length;g++){var els=document.querySelectorAll(groups[g]);"
                    + "for(var i=0;i<els.length;i++){if(vis(els[i])){els[i].click();return true;}}}"
                    + "return false;"
                    + "})();";

    private WebView webView;
    private BiliBridge bridge;
    private int lastTop = -1;
    private int lastBottom = -1;
    /** 换头像时系统选择器的回调，选完要把结果交回页面。 */
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        goEdgeToEdge();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor(BG));

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        webView.setBackgroundColor(Color.parseColor(BG));
        root.addView(webView);
        setContentView(root);

        configureWebView();
        listenInsets();

        applyLightSystemBars();
        registerBackHandler();

        webView.loadUrl(START_URL);
    }

    /** 让页面一直铺到状态栏和导航栏下面，再用 CSS 变量把安全区内缩回来。 */
    private void goEdgeToEdge() {
        android.view.Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    /** 深色状态栏图标（页面是浅色底）。必须等 DecorView 建好之后再调。 */
    private void applyLightSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            View decor = getWindow().getDecorView();
            WindowInsetsController c = decor.getWindowInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(light, light);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        // 去掉 UA 里的 "; wv" 标记，B 站公开接口对普通 Chrome UA 更友好
        String ua = s.getUserAgentString();
        if (ua != null && ua.contains("; wv")) {
            s.setUserAgentString(ua.replace("; wv", ""));
        }

        final File mediaDir = new File(getFilesDir(), "media");
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        bridge = new BiliBridge(this, webView);
        webView.addJavascriptInterface(bridge, "PotatoNative");
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            /**
             * 页面里的「更换头像」是一个 <input type="file">。
             * WebView 默认不处理文件选择，这里不接管的话点按钮毫无反应，
             * 所以把请求转给系统的图库/文件选择器。
             */
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                }
                filePathCallback = callback;

                Intent intent;
                try {
                    // 页面写了 accept="image/*"，createIntent() 会带上对应的 MIME 类型
                    intent = params.createIntent();
                } catch (Exception e) {
                    intent = null;
                }
                if (intent == null) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("image/*");
                }
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivityForResult(intent, REQ_PICK_IMAGE);
                    return true;
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    return false;
                }
            }

            /**
             * 页面里的「在 B 站打开」是 target="_blank"，
             * WebView 不下发新窗口请求就会静默失败，这里接住并用系统打开。
             */
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, android.os.Message resultMsg) {
                final WebView proxy = new WebView(view.getContext());
                proxy.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        openExternal(request.getUrl());
                        v.destroy();
                        return true;
                    }
                });
                android.webkit.WebView.WebViewTransport transport =
                        (android.webkit.WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(proxy);
                resultMsg.sendToTarget();
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (url != null && HOST.equals(url.getHost()) && url.getPath() != null
                        && url.getPath().startsWith("/media/")) {
                    String rel = url.getPath().substring("/media/".length());
                    return serveMedia(mediaDir, rel);
                }
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (url == null || HOST.equals(url.getHost())) {
                    return false;
                }
                openExternal(url);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pushSafeArea(true);
            }
        });
    }

    /** 系统栏尺寸变化时（旋转、手势条显示/隐藏）重新通知页面。 */
    private void listenInsets() {
        webView.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            if (top != lastTop || bottom != lastBottom) {
                lastTop = top;
                lastBottom = bottom;
                pushSafeArea(false);
            }
            return insets;
        });
        webView.requestApplyInsets();
    }

    private void pushSafeArea(boolean force) {
        if (webView == null || (lastTop < 0 && lastBottom < 0 && !force)) {
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        int topDp = Math.max(0, Math.round(Math.max(lastTop, 0) / d));
        int bottomDp = Math.max(0, Math.round(Math.max(lastBottom, 0) / d));

        String js = "(function(t,b){"
                + "var h=document.documentElement;if(!h)return;"
                + "h.style.setProperty('--native-safe-top',t+'px');"
                + "h.style.setProperty('--native-safe-bottom',b+'px');"
                + "var st=document.getElementById('__native_safe_area__');"
                + "if(!st){st=document.createElement('style');st.id='__native_safe_area__';"
                + "(document.head||h).appendChild(st);}"
                + "st.textContent='@media (max-width:480px){'"
                + "+':root{--phone-safe-top:calc(max(env(safe-area-inset-top,0px),'+t+'px) + 12px);'"
                + "+'--phone-safe-bottom:0px;}'"
                + "+'.tabbar{padding-bottom:calc(max(env(safe-area-inset-bottom,0px),'+b+'px) + 5px);}'"
                + "+'.screen-player{padding-bottom:calc(18px + max(env(safe-area-inset-bottom,0px),'+b+'px));}'"
                + "+'}';"
                + "})(" + topDp + "," + bottomDp + ");";
        webView.evaluateJavascript(js, null);
    }

    /**
     * 前台服务的常驻通知需要通知权限。Android 13 起默认不授予，
     * 在用户第一次点「开始专注」时申请，比一启动就弹窗更容易被接受。
     */
    void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        // 只问一次：拒绝了就不再弹，免得每次开始专注都被拦一下。
        // 之后想开可以去系统设置里手动打开。
        android.content.SharedPreferences sp =
                getSharedPreferences("potato_focus", MODE_PRIVATE);
        if (sp.getBoolean("notice_asked", false)) return;
        sp.edit().putBoolean("notice_asked", true).apply();
        try {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTICE_PERM);
        } catch (Exception ignored) {
        }
    }

    /** 授权后重发一次前台通知，否则这一颗番茄的通知要等下次开始才出现。 */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_NOTICE_PERM) {
            if (grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                FocusService.refresh(this);
            }
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void openExternal(Uri url) {
        if (url == null) return;
        String scheme = url.getScheme();
        String host = url.getHost();
        if (scheme != null && scheme.startsWith("http")) {
            // B 站链接优先交给 B 站 App
            if (bridge != null && host != null
                    && (host.contains("bilibili.com") || host.contains("b23.tv"))) {
                bridge.openBili(url.toString());
                return;
            }
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    /**
     * 音轨直接从本地文件读出来整段返回。
     *
     * 不用 Range：Chromium 对 shouldInterceptRequest 返回的 206 响应校验很严，
     * 偏移量偏大时整条响应会被丢掉（net::ERR_FAILED），audio 元素就 seek 不动。
     * 音轨只有几 MB，页面那边 fetch 成 Blob 播放，时长和拖动都正常。
     */
    private WebResourceResponse serveMedia(File base, String path) {
        byte[] empty = new byte[0];
        try {
            File f = new File(base, path);
            String canonical = f.getCanonicalPath();
            if (!canonical.startsWith(base.getCanonicalPath() + File.separator) || !f.isFile()) {
                return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                        java.util.Collections.<String, String>emptyMap(),
                        new java.io.ByteArrayInputStream(empty));
            }
            // 音轨文件不大（几 MB），整段读进内存再给浏览器；
            // 直接把文件流交出去时，超过一定大小的响应体会在读取途中断掉。
            byte[] body = new byte[(int) f.length()];
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            try {
                raf.readFully(body);
            } finally {
                raf.close();
            }
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Content-Length", String.valueOf(body.length));
            headers.put("Cache-Control", "no-store");
            return new WebResourceResponse(BiliBridge.mimeOf(path), null, 200, "OK",
                    headers, new java.io.ByteArrayInputStream(body));
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                    java.util.Collections.<String, String>emptyMap(),
                    new java.io.ByteArrayInputStream(empty));
        }
    }

    /** 系统选择器返回后，把选中的图片交回页面。 */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_PICK_IMAGE) {
            if (filePathCallback != null) {
                Uri[] result = resultCode == RESULT_OK
                        ? android.webkit.WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                        : null;
                filePathCallback.onReceiveValue(result);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    /** Android 13 起返回键走 OnBackInvokedCallback，老系统走 onBackPressed，这里统一处理。 */
    private void registerBackHandler() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    new android.window.OnBackInvokedCallback() {
                        @Override
                        public void onBackInvoked() {
                            handleBack();
                        }
                    });
        }
    }

    private void handleBack() {
        if (webView == null) {
            finish();
            return;
        }
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }
        // 先让页面自己消费返回：有弹窗就关弹窗，在播放页就收回播放页
        webView.evaluateJavascript(JS_HANDLE_BACK, value -> {
            boolean handled = value != null && value.replace("\"", "").trim().equals("true");
            if (!handled) {
                finish();
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 故意不调 webView.onPause()：切到后台时音轨和计时都要继续
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        // 界面没了就没有 WebView 在放音频了，前台服务没有继续跑的理由
        FocusService.stop(this);
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
