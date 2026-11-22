package uz.yulemdo.browser;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.URLUtil;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebResponse;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Kun/tun mavzusi, til, splash, status bar, bridge kengaytmasi va standart brauzer dialogi. */
abstract class ThemeActivity extends BaseActivity {
    void assignPalette() {
        if (dark) {
            cBg = 0xFF121314; cBar = 0xFF1A1B1E; cText = 0xFFE8EAED; cMuted = 0xFF9AA0A6;
            cBorder = 0xFF3C4043; cField = 0xFF2B2B30; cSwitch = 0xFF0E0E10; cCard = 0xFF292A2D;
        } else {
            cBg = 0xFFFFFFFF; cBar = 0xFFFFFFFF; cText = 0xFF202124; cMuted = 0xFF70757A;
            cBorder = 0xFFFFCC80; cField = 0xFFFFF3E6; cSwitch = 0xFFFFF8F1; cCard = 0xFFFFFFFF;
        }
    }

    void paintChrome() {
        if (host != null) host.setBackgroundColor(cBg);
        if (root != null) root.setBackgroundColor(cBg);
        if (bar != null) bar.setBackgroundColor(cBar);
        if (addr != null) {
            addr.setTextColor(cText);
            addr.setHintTextColor(cMuted);
        }
        if (pill != null) pill.setBackground(fieldBg(addr != null && addr.hasFocus()));
        if (btnHome != null) btnHome.setColorFilter(ORANGE_DEEP);
        updateMenuAlert();
        if (tabCount != null) tabCount.setTextColor(cText);
        if (switcher != null) switcher.setBackgroundColor(cSwitch);
        if (swTitle != null) swTitle.setTextColor(cText);
        applyBars();
        refreshUi();
        if (switcherOpen()) rebuildGrid(false);
    }

    void applyTheme(boolean night, boolean animate) {
        dark = night;
        themeKnown = true;
        prefs.edit().putBoolean("dark", night).apply();
        assignPalette();
        paintChrome();
        if (animate && splash == null) showThemeVeil();
    }

    void onThemeFromSite(boolean night) {
        if (!themeKnown) {
            themeKnown = true;
            prefs.edit().putBoolean("dark", night).apply();
            if (night != dark) applyTheme(night, false);
            return;
        }
        if (night == dark) return;
        applyTheme(night, true);
    }

    void onLangFromSite(String next) {
        if (next == null) return;
        if (!next.equals("uz") && !next.equals("ru") && !next.equals("en")) return;
        if (!next.equals(lang)) {
            lang = next;
            prefs.edit().putString("lang", lang).apply();
            applyLang();
        }
        // Sayt (intro yoki akkaunt sahifasi) til tanlaganini belgilab qo'yamiz —
        // keyingi safar ilova ochilganda ham (intro endi chiqmasa-da) so'rovni
        // qayta ko'rsatish mumkin bo'lsin.
        prefs.edit().putBoolean("langEverSetFromSite", true).apply();
        // Sayt ichida til tanlash tugagach — standart brauzer qilib
        // belgilashni taklif qilamiz (mos tilda, chunki applyLang() yuqorida
        // chaqirildi). Hali default bo'lmasa ko'rsatiladi; "Keyinroq" bosilsa
        // ham doimiy o'chirilmaydi — ilova keyingi safar ochilganda yana so'raydi.
        host.postDelayed(this::maybeShowDefaultBrowserPrompt, 350);
    }

    boolean isDefaultBrowser() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"));
            ResolveInfo ri = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
        } catch (Exception e) {
            return false;
        }
    }

    void requestDefaultBrowser() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                RoleManager rm = getSystemService(RoleManager.class);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_BROWSER) && !rm.isRoleHeld(RoleManager.ROLE_BROWSER)) {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER), REQ_SET_DEFAULT_BROWSER);
                    return;
                }
            }
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SET_DEFAULT_BROWSER) {
            toast(isDefaultBrowser() ? tr("setDefaultDone") : tr("setDefaultSkipped"));
        }
    }

    void maybeShowDefaultBrowserPrompt() {
        if (isFinishing()) return;
        if (defaultPromptShownThisSession) return;
        if (isDefaultBrowser()) return;
        showDefaultBrowserDialog();
    }

    void showDefaultBrowserDialog() {
        if (browserPromptOverlay != null) return;
        defaultPromptShownThisSession = true;

        FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0x99000000);
        scrim.setClickable(true);
        browserPromptOverlay = scrim;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(cCard);
        cg.setCornerRadius(dp(20));
        cg.setStroke(dp(1), cBorder);
        card.setBackground(cg);
        card.setElevation(dp(12));
        card.setPadding(dp(22), dp(22), dp(22), dp(16));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_home);
        icon.setColorFilter(ORANGE_DEEP);
        card.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));

        TextView title = new TextView(this);
        title.setText(tr("setDefaultTitle"));
        title.setTextColor(cText);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        LinearLayout.LayoutParams ttp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ttp.topMargin = dp(14);
        card.addView(title, ttp);

        TextView msg = new TextView(this);
        msg.setText(tr("setDefaultMsg"));
        msg.setTextColor(cMuted);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams mtp = new LinearLayout.LayoutParams(
                dp(240), ViewGroup.LayoutParams.WRAP_CONTENT);
        mtp.topMargin = dp(8);
        card.addView(msg, mtp);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brlp.topMargin = dp(20);

        TextView later = new TextView(this);
        later.setText(tr("setDefaultLater"));
        later.setTextColor(cMuted);
        later.setTypeface(null, Typeface.BOLD);
        later.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        later.setPadding(dp(14), dp(10), dp(14), dp(10));
        later.setBackgroundResource(ripple());
        pressAnim(later);
        later.setOnClickListener(v -> closeDefaultBrowserDialog());
        btnRow.addView(later);

        TextView yes = new TextView(this);
        yes.setText(tr("setDefaultYes"));
        yes.setTextColor(Color.WHITE);
        yes.setTypeface(null, Typeface.BOLD);
        yes.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        yes.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable yg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFFF9100, 0xFFFF3D00});
        yg.setCornerRadius(dp(18));
        yes.setBackground(yg);
        LinearLayout.LayoutParams ylp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ylp.leftMargin = dp(8);
        pressAnim(yes);
        yes.setOnClickListener(v -> {
            closeDefaultBrowserDialog();
            requestDefaultBrowser();
        });
        btnRow.addView(yes, ylp);
        card.addView(btnRow, brlp);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                dp(280), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        scrim.addView(card, clp);
        host.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (splash != null) splash.bringToFront();

        card.setAlpha(0f);
        card.setScaleX(0.9f);
        card.setScaleY(0.9f);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(180).start();
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    void closeDefaultBrowserDialog() {
        if (browserPromptOverlay == null) return;
        final View v = browserPromptOverlay;
        browserPromptOverlay = null;
        v.animate().alpha(0f).setDuration(150).withEndAction(() -> host.removeView(v)).start();
    }

    void applyLang() {
        if (addr != null) addr.setHint(tr("hint"));
        if (btnHome != null) btnHome.setContentDescription(tr("home"));
        if (btnUa != null) btnUa.setContentDescription(tr("uaTitle"));
        if (btnMenu != null) btnMenu.setContentDescription(tr("settings"));
        if (swPlus != null) swPlus.setText(tr("newTabPlus"));
        if (switcherOpen()) rebuildGrid(false);
    }

    void showThemeVeil() {
        int bg = dark ? 0xFF202124 : 0xFFFFFFFF;
        if (themeVeil == null) {
            themeVeil = new FrameLayout(this);
            themeVeil.setClickable(true);
            spinner = new View(this) {
                final Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
                final RectFHolder box = new RectFHolder();

                @Override protected void onDraw(Canvas c) {
                    float s = Math.min(getWidth(), getHeight());
                    if (s <= 0) return;
                    float cx = getWidth() / 2f, cy = getHeight() / 2f, r = s / 2f - dp(1.5f);
                    sp.setStyle(Paint.Style.STROKE);
                    sp.setStrokeWidth(dp(3));
                    sp.setStrokeCap(Paint.Cap.BUTT);
                    sp.setColor(dark ? 0xFF3C4043 : 0xFFDFE1E5);
                    c.drawCircle(cx, cy, r, sp);
                    box.set(cx - r, cy - r, cx + r, cy + r);
                    sp.setColor(0xFFFF6F00);
                    c.drawArc(box.rf, -90, 90, false, sp);
                    sp.setColor(0xFFFF9800);
                    c.drawArc(box.rf, 0, 90, false, sp);
                }
            };
            themeVeil.addView(spinner, new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER));
            host.addView(themeVeil, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        themeVeil.setBackgroundColor(bg);
        themeVeil.setVisibility(View.VISIBLE);
        themeVeil.setAlpha(1f);
        themeVeil.bringToFront();
        if (splash != null) splash.bringToFront();
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (!dark) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
        if (spinAnim != null) spinAnim.cancel();
        spinAnim = ValueAnimator.ofFloat(0f, 360f);
        spinAnim.setDuration(850);
        spinAnim.setRepeatCount(ValueAnimator.INFINITE);
        spinAnim.setInterpolator(new LinearInterpolator());
        spinAnim.addUpdateListener(a -> {
            if (spinner != null) spinner.setRotation((Float) a.getAnimatedValue());
        });
        spinAnim.start();
        if (hideVeil != null) host.removeCallbacks(hideVeil);
        hideVeil = () -> {
            if (themeVeil == null) return;
            themeVeil.animate().alpha(0f).setDuration(280).withEndAction(() -> {
                if (themeVeil != null) themeVeil.setVisibility(View.GONE);
                if (spinAnim != null) spinAnim.cancel();
                applyBars();
            }).start();
        };
        host.postDelayed(hideVeil, 760);
    }

    static final class RectFHolder {
        final android.graphics.RectF rf = new android.graphics.RectF();
        void set(float l, float t, float r, float b) { rf.set(l, t, r, b); }
    }

    void endSplash() {
        if (splash == null) return;
        splashDone = true;
        SplashView v = splash;
        splash = null;
        host.removeView(v);
        v.finish();
        applyBars();
        // Qaytib kelgan foydalanuvchi: intro endi chiqmaydi (til ilgari tanlangan),
        // shuning uchun standart brauzer so'rovini shu yerda, splash tugagach
        // qayta ko'rsatamiz — albatta hali default qilib belgilanmagan bo'lsa.
        if (prefs.getBoolean("langEverSetFromSite", false)) {
            host.postDelayed(this::maybeShowDefaultBrowserPrompt, 600);
        }
    }

    void applyBars() {
        if (splash != null) return;
        if (themeVeil != null && themeVeil.getVisibility() == View.VISIBLE && themeVeil.getAlpha() > 0.85f) return;
        getWindow().setStatusBarColor(cBar);
        getWindow().setNavigationBarColor(cBg);
        getWindow().setBackgroundDrawable(new ColorDrawable(cBg));
        if (!dark) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
    }

    void initExtension() {
        if (runtime == null) return;
        try {
            runtime.getWebExtensionController()
                    .ensureBuiltIn("resource://android/assets/bridge/", "yulemdo-bridge@yulemdo.uz")
                    .accept(ext -> {
                        if (ext == null) return;
                        runOnUiThread(() -> ext.setMessageDelegate(new WebExtension.MessageDelegate() {
                            @Override public void onConnect(WebExtension.Port port) {
                                bridgePort = port;
                                if (pendingPush != null) pushThemeToPage(pendingPush);
                            }
                        }, "yulemdo"));
                    }, err -> { });
        } catch (Throwable ignored) { }
    }

    void pushThemeToPage(boolean night) {
        pendingPush = night;
        WebExtension.Port port = bridgePort;
        if (port == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("theme", night ? "dark" : "light");
            port.postMessage(o);
            pendingPush = null;
        } catch (Throwable ignored) { }
    }
}
