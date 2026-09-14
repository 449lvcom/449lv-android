package com.ak449lv.clientapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private WebView web;
    private String lastUrl = "https://449lv.com/menu-admin";
    private int renderRecoverCount = 0;
    private boolean pageFinished = false;
    private String lastErr = null;
    private LinearLayout overlay;
    private TextView ovTitle;
    private boolean overlayVisible = false;

    private static boolean isAuthFlow(String u) {
        return u != null && (u.contains("accounts.google.com") || u.contains("/auth/callback") || u.contains("/api/auth/google"));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // حفظ أي خطأ قاتل في التطبيق لتشخيصه من زر «🔍 حالة الإشعارات»
        Thread.setDefaultUncaughtExceptionHandler((thread, t) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                String stack = sw.toString();
                getSharedPreferences("pushsrv", MODE_PRIVATE).edit()
                        .putString("crash", stack.substring(0, Math.min(1600, stack.length())))
                        .putLong("crash_ts", System.currentTimeMillis())
                        .commit();
            } catch (Throwable t2) {
            }
            android.os.Process.killProcess(android.os.Process.myPid());
        });

        web = new WebView(this);
        FrameLayout root = new FrameLayout(this);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        buildOverlay();
        root.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        overlay.setVisibility(View.GONE);
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setSupportZoom(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) {
            cm.setAcceptThirdPartyCookies(web, true);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageFinished = false;
                lastErr = null;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageFinished = true;
                saveSessionCookie();
                lastUrl = url;
                renderRecoverCount = 0;
                lastErr = null;
                // أثناء تسجيل الدخول عبر Google: لا نغطي الصفحة بشاشة الإنقاذ أبداً
                if (!isAuthFlow(url)) {
                    savePageState("تم تحميل الصفحة");
                    // تحقق من أن الصفحة تحتوي محتوى فعلي (ليست صفحة سوداء فارغة)
                    view.evaluateJavascript(
                            "(document.body ? document.body.innerText.trim().length : 0).toString()",
                            value -> runOnUiThread(() -> {
                                try {
                                    String raw = value == null ? "0" : value.replace("\"", "");
                                    int len = Integer.parseInt(raw);
                                    if (len > 20) {
                                        hideStall();
                                    } else {
                                        savePageState("صفحة فارغة (محتوى=" + len + ")");
                                        showStall("الصفحة فارغة — قد تكون بحاجة إلى تسجيل دخول أو إعادة تحميل");
                                    }
                                } catch (Throwable t) {
                                    hideStall();
                                }
                            })
                    );
                } else {
                    hideStall();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (Build.VERSION.SDK_INT >= 23 && request != null && request.isForMainFrame()) {
                    String err;
                    if (error == null) {
                        err = "خطأ غير معروف";
                    } else {
                        err = String.valueOf(error.getDescription());
                    }
                    lastErr = err;
                    savePageState("خطأ تحميل: " + err);
                    showStall("تعذر تحميل الصفحة\n" + err);
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // منح أذونات الويب (الإشعارات وغيرها) تلقائياً لموقع التطبيق فقط
                if (Build.VERSION.SDK_INT >= 21) {
                    try {
                        if (request.getOrigin() != null
                                && request.getOrigin().toString().startsWith("https://449lv.com")) {
                            request.grant(request.getResources());
                            return;
                        }
                    } catch (Throwable t) {
                    }
                    request.deny();
                }
            }
        });

        // جسر JavaScript → أندرويد: تتحكم أزرار الإشعارات في الموقع بالخدمة الأساسية داخل التطبيق
        web.addJavascriptInterface(new LvBridge(), "LvNative");

        // مسح ذاكرة WebView المؤقتة مرة واحدة عند أول تشغيل لهذه النسخة
        SharedPreferences boot = getSharedPreferences("pushsrv", MODE_PRIVATE);
        if (!boot.getBoolean("v2_9_boot_cleared", false)) {
            try {
                web.clearCache(true);
                web.clearHistory();
            } catch (Throwable t) {
            }
            boot.edit().putBoolean("v2_9_boot_cleared", true).apply();
        }

        // معالجة رابط الإشعار: فتح لوحة المنيو مباشرة (أو الرابط القادم من الإشعار)
        String url = null;
        if (getIntent() != null) {
            url = getIntent().getStringExtra("url");
        }
        if (url == null || url.isEmpty() || !url.startsWith("https://449lv.com")) {
            url = "https://449lv.com/menu-admin";
        }
        lastUrl = url;

        // استعادة جلسة الدخول المحفوظة حتى لا يُطلب تسجيل الدخول كل مرة
        try {
            String saved = getSharedPreferences("pushsrv", MODE_PRIVATE).getString("cookie", null);
            if (saved != null && !saved.isEmpty()) {
                CookieManager.getInstance().setCookie("https://449lv.com", saved);
            }
        } catch (Throwable t) {
        }

        web.loadUrl(url);
        startStallWatch();

        // طلب الأذونات بعد اكتمال بناء الواجهة (بعض الأجهزة تتأثر عند البناء المتزامن مع WebView)
        web.postDelayed(() -> ensurePermissions(), 800);
    }

    private void ensurePermissions() {
        try {
            requestNotificationPermission();
        } catch (Throwable t) {
        }
        // استثناء البطارية يُطلب بعد حل إذن الإشعارات (لا نُشبّك حوارين فوق بعضهما)
        if (Build.VERSION.SDK_INT < 33) {
            ensureBatteryOptExempt();
        }
        try {
            boolean ok = NotificationService.start(this);
            showStatusToast(ok);
            if (ok) {
                Toast.makeText(this, "تم تفعيل إشعارات الطلبات في الخلفية", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "تعذر تشغيل الخدمة الخلفية — سيُعاد التشغيل تلقائياً", Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
        }
        // Watchdog: بعض الأجهزة (Xiaomi/Huawei/Samsung) تمنع التشغيل الخلفي من أول مرة —
        // نعيد المحاولة بفترات متباعدة أثناء فتح التطبيق
        watchdogRun(1);
    }

    private void saveSessionCookie() {
        try {
            String c = CookieManager.getInstance().getCookie("https://449lv.com");
            if (c != null && !c.isEmpty()) {
                getSharedPreferences("pushsrv", MODE_PRIVATE).edit().putString("cookie", c).apply();
            }
        } catch (Throwable t) {
        }
    }

    private class LvBridge {
        @android.webkit.JavascriptInterface
        public boolean isEnabled() {
            return NotificationService.isActive() && areNotificationsEnabled();
        }

        @android.webkit.JavascriptInterface
        public String getPermission() {
            if (Build.VERSION.SDK_INT >= 33) {
                return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED ? "granted" : "denied";
            }
            return areNotificationsEnabled() ? "granted" : "denied";
        }

        @android.webkit.JavascriptInterface
        public void requestPermission() {
            runOnUiThread(() -> requestNotificationPermission());
        }

        @android.webkit.JavascriptInterface
        public void sendTest() {
            NotificationService.sendTestNotification(MainActivity.this);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "أُرسل إشعار تجريبي ✓", Toast.LENGTH_SHORT).show());
        }

        @android.webkit.JavascriptInterface
        public void setSound(String name) {
            NotificationService.applySound(MainActivity.this, name);
        }

        @android.webkit.JavascriptInterface
        public String getDiag() {
            try {
                SharedPreferences p = getSharedPreferences("pushsrv", MODE_PRIVATE);
                long ts = p.getLong("diag_ts", 0);
                String d = p.getString("diag", "لا يوجد تشخيص بعد — انتظر دقيقة");
                String crash = p.getString("crash", null);
                String cts = crash == null ? "" : ("\n--- سجل أعطال ---\n" + crash.substring(0, Math.min(400, crash.length())));
                long age = ts == 0 ? -1 : (System.currentTimeMillis() - ts) / 1000;
                String ago = age < 0 ? "لم يحدث بعد" : (age < 120 ? "قبل " + age + " ثانية" : "قبل " + (age / 60) + " دقيقة");
                String page = p.getString("page_state", "") + " " + p.getString("page_url", "");
                return "آخر فحص: " + ago + "\n" + d
                        + (page.isEmpty() ? "" : "\n--- حالة الصفحة ---\n" + page)
                        + cts;
            } catch (Throwable t) {
                return "getDiag error";
            }
        }

        @android.webkit.JavascriptInterface
        public void stop() {
            NotificationService.stopService(MainActivity.this);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                Toast.makeText(this, "تم السماح بالإشعارات", Toast.LENGTH_LONG).show();
            } else if (Build.VERSION.SDK_INT >= 33 && isPermanentlyDenied()) {
                // رفض نهائي (خيار "لا تسأل مجدداً") → نتجه مباشرة لإعدادات التطبيق
                Toast.makeText(this, "يرجى السماح بالإشعارات من إعدادات التطبيق حتى تصلك الطلبات", Toast.LENGTH_LONG).show();
                openNotificationSettings();
            } else {
                Toast.makeText(this, "لم يتم السماح بالإشعارات — سنعيد الطلب عند الفتح التالي", Toast.LENGTH_LONG).show();
            }
            // بعد انتهاء حوار الإذن اطلب استثناء البطارية (لا تداخل بين الحوارات)
            ensureBatteryOptExempt();

            // إبلاغ جافاسكريبت (زر الإشعارات في الموقع) بنتيجة طلب الإذن
            if (web != null) {
                web.evaluateJavascript(
                        "try{if(window.__lvPermCb){window.__lvPermCb(" + granted + ");window.__lvPermCb=null;}}catch(e){}",
                        null);
            }
        }
    }

    private boolean isPermanentlyDenied() {
        // true = رفض نهائي من قبل (لا يظهر حوار الإذن مجدداً)
        try {
            boolean canAsk = shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS);
            SharedPreferences p = getSharedPreferences("pushsrv", MODE_PRIVATE);
            return !canAsk && p.getBoolean("notif_permission_asked", false);
        } catch (Throwable t) {
            return false;
        }
    }

    private void showStatusToast(boolean ok) {
        String vName = "?";
        try {
            vName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
        }
        String os = Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
        String notif = areNotificationsEnabled() ? "الإشعارات مفعّلة" : "الإشعارات مقفلة";
        String svc = ok ? "الخدمة تعمل" : "الخدمة لم تُشغَّل";
        Toast.makeText(this,
                "449LV v" + vName + " · أندرويد " + os + " · " + notif + " · " + svc,
                Toast.LENGTH_LONG).show();
    }

    private void watchdogRun(final int step) {
        if (step > 6) return;
        web.postDelayed(() -> {
            NotificationService.start(this);
            watchdogRun(step + 1);
        }, 40_000L * step);
    }

    private void startStallWatch() {
        // لا تُغطى صفحة تسجيل الدخول عبر Google أبداً (قد يحتاج المستخدم وقتاً لكتابة بريده)
        stallChecks(1, 8000);
    }

    private void stallChecks(final int i, final long delay) {
        if (i > 4) return;
        web.postDelayed(() -> {
            if (pageFinished) return;
            String cur = null;
            try {
                cur = web.getUrl();
            } catch (Throwable t) {
            }
            if (isAuthFlow(cur)) {
                // ننتظر انتهاء المستخدم من تسجيل الدخول ثم نعود للفحص
                stallChecks(i + 1, 20000);
                return;
            }
            if (!overlayVisible) {
                showStall(i == 1 ? "جارٍ تحميل اللوحة…" : "الصفحة لم تظهر بعد — استخدم الأزرار بالأسفل");
            }
            if (i == 2) {
                // محاولة واحدة لإعادة التحميل لإنعاش المُصيّر إذا تجمّد
                try {
                    if (web != null) web.reload();
                } catch (Throwable t) {
                }
            }
            if (i >= 3) {
                ovTitle.setText("تعذر تحميل اللوحة — تأكد من الإنترنت أو جرّب إعادة التعيين");
            }
            stallChecks(i + 1, i >= 2 ? 25000 : 10000);
        }, delay);
    }

    private void buildOverlay() {
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(0xFFF3F4F8);
        overlay.setPadding(dp(24), dp(24), dp(24), dp(24));

        ovTitle = new TextView(this);
        ovTitle.setText("جارٍ تحميل الصفحة…");
        ovTitle.setTextSize(19);
        ovTitle.setGravity(Gravity.CENTER);
        ovTitle.setTextColor(0xFF222222);

        TextView ovSub = new TextView(this);
        ovSub.setText("إذا استمرت الشاشة السوداء استخدم الأزرار بالأسفل: أعد التحميل أو افتح الصفحة في المتصفح.");
        ovSub.setGravity(Gravity.CENTER);
        ovSub.setTextSize(14);
        ovSub.setTextColor(0xFF666666);
        ovSub.setPadding(0, dp(10), 0, dp(26));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.VERTICAL);
        btns.setGravity(Gravity.CENTER);
        btns.addView(makeBtn("🔄 إعادة تحميل", v -> {
            if (web != null) web.reload();
        }));
        btns.addView(makeBtn("🌐 فتح في المتصفح", v -> openBrowser()));
        btns.addView(makeBtn("🔍 حالة الإشعارات والتشخيص", v -> showDiagDialog()));
        btns.addView(makeBtn("🗑️ إعادة تعيين التطبيق", v -> resetApp()));

        overlay.addView(ovTitle);
        overlay.addView(ovSub);
        overlay.addView(btns);
    }

    private Button makeBtn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(15);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundColor(0xFF1E6FFF);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        lp.setMargins(0, dp(8), 0, dp(8));
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void showStall(String msg) {
        if (overlay == null) return;
        runOnUiThread(() -> {
            savePageState(msg);
            ovTitle.setText(msg);
            overlayVisible = true;
            if (web != null) web.setVisibility(View.INVISIBLE);
            overlay.setVisibility(View.VISIBLE);
        });
    }

    private void hideStall() {
        if (overlay == null || !overlayVisible) return;
        runOnUiThread(() -> {
            overlayVisible = false;
            overlay.setVisibility(View.GONE);
            if (web != null) web.setVisibility(View.VISIBLE);
        });
    }

    private void savePageState(String state) {
        try {
            getSharedPreferences("pushsrv", MODE_PRIVATE).edit()
                    .putString("page_state", state)
                    .putString("page_url", lastUrl)
                    .putLong("page_ts", System.currentTimeMillis())
                    .apply();
        } catch (Throwable t) {
        }
    }

    private void openBrowser() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(lastUrl)));
        } catch (Throwable t) {
        }
    }

    private void resetApp() {
        new AlertDialog.Builder(this)
                .setTitle("إعادة تعيين التطبيق")
                .setMessage("سيتم مسح بيانات المتصفح الداخلي والكاش والدخول، ثم نفتح لوحة المنيو لتسجيل الدخول من جديد عبر Google.")
                .setPositiveButton("نعم، أعد التعيين", (di, w) -> {
                    try {
                        web.clearCache(true);
                        web.clearHistory();
                        web.clearFormData();
                        CookieManager.getInstance().removeAllCookies(null);
                        CookieManager.getInstance().removeAllSessionCookies(null);
                        web.loadUrl("https://449lv.com/menu-admin");
                        lastUrl = "https://449lv.com/menu-admin";
                        overlayVisible = false;
                        overlay.setVisibility(View.GONE);
                        if (web != null) web.setVisibility(View.VISIBLE);
                        pageFinished = false;
                        startStallWatch();
                        Toast.makeText(this, "تمت إعادة التعيين — جارٍ تحميل الموقع", Toast.LENGTH_LONG).show();
                    } catch (Throwable t) {
                        Toast.makeText(this, "خطأ: " + t.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void showDiagDialog() {
        try {
            SharedPreferences p = getSharedPreferences("pushsrv", MODE_PRIVATE);
            long ts = p.getLong("diag_ts", 0);
            String d = p.getString("diag", "لا يوجد تشخيص بعد — انتظر دقيقة");
            String crash = p.getString("crash", null);
            String cr = crash == null ? "لا يوجد"
                    : "\n" + crash.substring(0, Math.min(1200, crash.length()));
            long age = ts == 0 ? -1 : (System.currentTimeMillis() - ts) / 1000;
            String ago = age < 0 ? "لم يحدث بعد" : (age < 120 ? "قبل " + age + " ثانية" : "قبل " + (age / 60) + " دقيقة");
            String page = p.getString("page_state", "لا توجد معلومات") + " · " + p.getString("page_url", "");
            String msg = "آخر فحص: " + ago + "\n" + d
                    + "\n\n--- حالة الصفحة ---\n" + page
                    + "\n\n--- سجل أعطال ---\n" + cr;
            new AlertDialog.Builder(this)
                    .setTitle("🔍 التشخيص")
                    .setMessage(msg)
                    .setPositiveButton("نسخ", (di, w) -> {
                        try {
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("diag", msg));
                            Toast.makeText(this, "نُسخ — الصقه وأرسله", Toast.LENGTH_LONG).show();
                        } catch (Throwable t) {
                        }
                    })
                    .setNegativeButton("إغلاق", null)
                    .show();
        } catch (Throwable t) {
            Toast.makeText(this, "diag: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null) {
            String url = intent.getStringExtra("url");
            if (url != null && !url.isEmpty() && url.startsWith("https://449lv.com")) {
                web.loadUrl(url);
                pageFinished = false;
                startStallWatch();
            }
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            int granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS);
            SharedPreferences p = getSharedPreferences("pushsrv", MODE_PRIVATE);
            boolean askedBefore = p.getBoolean("notif_permission_asked", false);
            if (granted == PackageManager.PERMISSION_GRANTED) {
                // ممنوح بالفعل → اطلب استثناء البطارية بعد قليل (لا حوار إشعارات هذه المرة)
                if (!p.getBoolean("battery_exempt_requested", false)) {
                    web.postDelayed(this::ensureBatteryOptExempt, 1500);
                }
            } else if (!askedBefore) {
                // أول فتح → طلب فوري وظاهر
                p.edit().putBoolean("notif_permission_asked", true).apply();
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            } else if (shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
                // رفض عادي سابق (غير نهائي) → نعيد الطلب
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            } else {
                // رفض نهائي → الإعدادات مباشرة
                Toast.makeText(this, "الرجاء تفعيل الإشعارات من إعدادات التطبيق", Toast.LENGTH_LONG).show();
                web.postDelayed(this::openNotificationSettings, 700);
            }
        } else if (!areNotificationsEnabled()) {
            // أندرويد 12 أو أقل: لا يوجد حوار إذن؛ إن كانت الإشعارات مقفلة نوجّه للإعدادات
            String k = "notif_settings_suggested";
            SharedPreferences p = getSharedPreferences("pushsrv", MODE_PRIVATE);
            if (!p.getBoolean(k, false)) {
                p.edit().putBoolean(k, true).apply();
                web.postDelayed(this::openNotificationSettings, 600);
            }
        }
    }

    private boolean areNotificationsEnabled() {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            return nm == null || nm.areNotificationsEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    private void openNotificationSettings() {
        try {
            Intent s = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            s.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(s);
        } catch (Throwable t) {
            try {
                Intent s = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
                startActivity(s);
            } catch (Throwable ignored) {
            }
        }
    }

    private void ensureBatteryOptExempt() {
        if (Build.VERSION.SDK_INT < 23) return;
        SharedPreferences p = getSharedPreferences("pushsrv", MODE_PRIVATE);
        if (p.getBoolean("battery_exempt_requested", false)) return;
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } catch (Throwable ignored) {
            // بعض الأجهزة تمنع هذا العرض
        }
        p.edit().putBoolean("battery_exempt_requested", true).apply();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}