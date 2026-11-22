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

/** Umumiy holat (maydonlar), Tab, UA ikonka/presetlar, kichik UI yordamchilari va tarjima tr(). */
abstract class BaseActivity extends Activity {
    // ---- Keyingi qatlamlarda yozilgan metodlar (shu yerda e'lon qilinadi) ----
    abstract boolean hasUnseenDownloads();
    abstract void closeMenu();
    abstract void refreshUi();
    abstract boolean switcherOpen();
    abstract void rebuildGrid(boolean animate);
    abstract void updateMenuAlert();
    abstract UaPreset currentUa();
    abstract void applyUa(GeckoSession s);
    abstract void startDirectDownload(final String src, final String rawName);
    abstract void saveDownload(InputStream src, String name, String mime);
    abstract void saveDownload(InputStream src, String name, String mime, int notifyId);
    abstract void showMenu(View anchor);

    static final String HOME = "https://yulemdo.uz/";

    static final int ORANGE = 0xFFFF9800, ORANGE_DEEP = 0xFFFF6D00, ORANGE_SOFT = 0xFFFFE0B2;

    static GeckoRuntime runtime;

    static boolean splashDone;

    /** Shu ilova sessiyasida (jarayon ishga tushgandan beri) standart brauzer
     *  so'rovi bir marta ko'rsatildimi. Yangi jarayon (ilova qayta ochilsa)
     *  bilan nolga tushadi — shu sabab har safar ilova ochilganda, agar hali
     *  standart qilib belgilanmagan bo'lsa, so'rov qayta chiqaveradi. */
    static boolean defaultPromptShownThisSession;

    static final ExecutorService IO = Executors.newFixedThreadPool(3);

    static final LruCache<String, Bitmap> FAV = new LruCache<>(100);

    static final Set<String> FAV_BUSY = Collections.synchronizedSet(new HashSet<String>());

    static class Tab {
        GeckoSession session;
        String title = "";
        String url = "";
        String favHost = "";
        Bitmap fav;
        Bitmap thumb;
        boolean loading, canBack, canFwd;
    }

    final List<Tab> tabs = new ArrayList<>();

    Tab active;

    GeckoSession bound;

    boolean dark, themeKnown;

    // Chrome navigatsiyasi (address bar, home, orqaga/oldinga, reload) joriy
    // tabda qolishi uchun bayroq: true bo'lsa, keyingi onLoadRequest yangi tab
    // ochmasdan joriy sessiyada davom etadi.
    boolean allowSameTab = false;

    String lang = "uz";

    int cBg, cBar, cText, cMuted, cBorder, cField, cSwitch, cCard;

    SharedPreferences prefs;

    GeckoView geckoView;

    EditText addr;

    ImageButton btnHome, btnUa, btnMenu;

    FrameLayout menuHolder;

    UaIcon uaIcon;

    ValueAnimator menuPulse;

    /** Hozir davom etayotgan yuklamalar soni (menyu tugmasi qizarishi uchun). */
    final java.util.concurrent.atomic.AtomicInteger activeDownloads = new java.util.concurrent.atomic.AtomicInteger();

    ImageView favView;

    TextView tabCount, swTitle, swPlus;

    LinearLayout pill, root, bar;

    ProgressBar progress;

    FrameLayout host, switcher, menuScrim, themeVeil;

    GridLayout grid;

    View spinner;

    SplashView splash;

    ValueAnimator spinAnim;

    Runnable hideVeil;

    WebExtension.Port bridgePort;

    Boolean pendingPush;

    boolean uiQueued;

    FrameLayout browserPromptOverlay;

    View downloadBadge;

    static final int REQ_SET_DEFAULT_BROWSER = 7421;

    int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    GradientDrawable fieldBg(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(cField);
        g.setCornerRadius(dp(22));
        g.setStroke(dp(2), focused ? ORANGE_DEEP : Color.TRANSPARENT);
        return g;
    }

    int ripple() {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    void pressAnim(View v) {
        v.setOnTouchListener((view, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80).start();
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(new OvershootInterpolator(3f)).start();
            }
            return false;
        });
    }

    ImageButton iconBtn(int res, int tint, View.OnClickListener l) {
        ImageButton ib = new ImageButton(this);
        ib.setImageResource(res);
        ib.setColorFilter(tint);
        ib.setPadding(dp(8), dp(8), dp(8), dp(8));
        ib.setScaleType(ImageButton.ScaleType.FIT_CENTER);
        ib.setBackgroundResource(ripple());
        ib.setOnClickListener(l);
        pressAnim(ib);
        return ib;
    }

    void hideKb() {
        if (addr == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(addr.getWindowToken(), 0);
    }

    static String hostOf(String url) {
        try {
            if (url == null) return null;
            Uri u = Uri.parse(url);
            String sc = u.getScheme();
            if (sc == null || !(sc.equals("http") || sc.equals("https"))) return null;
            return u.getHost();
        } catch (Exception e) {
            return null;
        }
    }

    static Bitmap letterIcon(String host) {
        Bitmap b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(ORANGE_SOFT);
        c.drawCircle(32, 32, 32, p);
        p.setColor(0xFFE65100);
        p.setTextSize(36);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.CENTER);
        String h = host.startsWith("www.") ? host.substring(4) : host;
        c.drawText(h.isEmpty() ? "?" : h.substring(0, 1).toUpperCase(), 32, 45, p);
        return b;
    }

    // ===================== User agent =====================
    static final class UaPreset {
        final String id, label, ua;
        final boolean desktop;
        UaPreset(String id, String label, String ua, boolean desktop) {
            this.id = id; this.label = label; this.ua = ua; this.desktop = desktop;
        }
    }

    /** ua == null — Gecko'ning o'z standart UA'si. Versiyalarni xohlagancha yangilab turing. */
    static final UaPreset[] UA_PRESETS = {
        new UaPreset("auto", null, null, false),
        new UaPreset("android_chrome", "Android — Chrome",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36", false),
        new UaPreset("iphone_safari", "iPhone — Safari",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Mobile/15E148 Safari/604.1", false),
        new UaPreset("win_chrome", "Windows — Chrome",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36", true),
        new UaPreset("mac_safari", "macOS — Safari",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Safari/605.1.15", true),
        new UaPreset("linux_firefox", "Linux — Firefox",
                "Mozilla/5.0 (X11; Linux x86_64; rv:143.0) Gecko/20100101 Firefox/143.0", true),
    };

    /** Minimal chiziqli ikonka: telefon yoki monitor (res fayl kerak emas). */
    static final class UaIcon extends Drawable {
        boolean desktop;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        UaIcon(boolean desktop) {
            this.desktop = desktop;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(Color.BLACK);
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float s = b.width() / 24f;
            c.save();
            c.translate(b.left, b.top);
            c.scale(s, s);
            paint.setStrokeWidth(1.8f);
            if (desktop) {
                r.set(3f, 4.5f, 21f, 16f);
                c.drawRoundRect(r, 2.2f, 2.2f, paint);
                c.drawLine(12f, 16f, 12f, 19.5f, paint);
                c.drawLine(8f, 19.5f, 16f, 19.5f, paint);
            } else {
                r.set(7f, 3f, 17f, 21f);
                c.drawRoundRect(r, 2.4f, 2.4f, paint);
                c.drawLine(10.6f, 18f, 13.4f, 18f, paint);
            }
            c.restore();
        }

        @Override public int getIntrinsicWidth() { return 24; }
        @Override public int getIntrinsicHeight() { return 24; }
        @Override public void setAlpha(int a) { paint.setAlpha(a); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static final String BOOKMARKS_KEY = "bookmarks";

    static final String DOWNLOADS_KEY = "downloads";

    static final String DOWNLOADS_UNSEEN_KEY = "downloadsUnseen";

    static final java.util.concurrent.atomic.AtomicInteger NOTIFY_ID = new java.util.concurrent.atomic.AtomicInteger(5000);

    View divider() {
        View d = new View(this);
        d.setBackgroundColor(dark ? 0xFF3C4043 : 0xFFF1E4D4);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(dp(16), dp(4), dp(16), dp(4));
        d.setLayoutParams(lp);
        return d;
    }

    ImageButton circleIconBtn(int icon, int tint, boolean enabled, Runnable action) {
        ImageButton ib = new ImageButton(this);
        ib.setImageResource(icon);
        ib.setColorFilter(enabled ? tint : cMuted);
        ib.setAlpha(enabled ? 1f : 0.4f);
        ib.setPadding(dp(11), dp(11), dp(11), dp(11));
        ib.setScaleType(ImageButton.ScaleType.FIT_CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.TRANSPARENT);
        ib.setBackground(bg);
        if (enabled) {
            ib.setBackgroundResource(ripple());
            ib.setOnClickListener(v -> action.run());
            pressAnim(ib);
        } else {
            ib.setEnabled(false);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.setMargins(dp(4), 0, dp(4), 0);
        ib.setLayoutParams(lp);
        return ib;
    }

    FrameLayout iconBubble(int icon) {
        return iconBubble(icon, false);
    }

    FrameLayout iconBubble(int icon, boolean withBadge) {
        FrameLayout bubble = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(dark ? 0xFF2C2C31 : 0xFFF3F4F6);
        bubble.setBackground(g);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(cText);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        bubble.addView(iv, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        if (withBadge) {
            View dot = new View(this);
            GradientDrawable dg = new GradientDrawable();
            dg.setShape(GradientDrawable.OVAL);
            dg.setColor(0xFFE53935);
            dg.setStroke(dp(1), dark ? 0xFF2C2C31 : 0xFFF3F4F6);
            dot.setBackground(dg);
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(9), dp(9), Gravity.TOP | Gravity.END);
            dlp.topMargin = dp(1);
            dlp.rightMargin = dp(1);
            dot.setLayoutParams(dlp);
            dot.setVisibility(hasUnseenDownloads() ? View.VISIBLE : View.GONE);
            downloadBadge = dot;
            bubble.addView(dot);
        }
        return bubble;
    }

    void addRow(LinearLayout list, int icon, String label, boolean enabled, Runnable action) {
        addRow(list, icon, label, enabled, false, action);
    }

    void addRow(LinearLayout list, int icon, String label, boolean enabled, boolean withBadge, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setPadding(dp(14), dp(6), dp(14), dp(6));
        row.addView(iconBubble(icon, withBadge), new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(cText);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(12);
        row.addView(tv, tp);
        if (enabled) {
            row.setBackgroundResource(ripple());
            row.setOnClickListener(v -> {
                closeMenu();
                action.run();
            });
        } else {
            row.setAlpha(0.38f);
        }
        list.addView(row);
    }

    View makeToggle(boolean on) {
        FrameLayout toggle = new FrameLayout(this);
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(dp(14));
        track.setColor(on ? ORANGE_DEEP : 0xFFDADCE0);
        toggle.setBackground(track);
        View thumb = new View(this);
        GradientDrawable th = new GradientDrawable();
        th.setShape(GradientDrawable.OVAL);
        th.setColor(Color.WHITE);
        thumb.setBackground(th);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(dp(22), dp(22),
                Gravity.CENTER_VERTICAL | (on ? Gravity.END : Gravity.START));
        tlp.setMargins(dp(3), dp(3), dp(3), dp(3));
        toggle.addView(thumb, tlp);
        toggle.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(28)));
        return toggle;
    }

    void toast(String s) {
        runOnUiThread(() -> {
            if (!isFinishing()) Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        });
    }

    String tr(String k) {
        boolean en = "en".equals(lang);
        boolean ru = "ru".equals(lang);
        switch (k) {
            case "back": return en ? "Back" : ru ? "Назад" : "Orqaga";
            case "fwd": return en ? "Forward" : ru ? "Вперёд" : "Oldinga";
            case "reload": return en ? "Reload" : ru ? "Обновить" : "Yangilash";
            case "uaTitle": return en ? "User agent" : ru ? "User agent" : "User agent";
            case "uaAuto": return en ? "Default (Yulemdo)" : ru ? "По умолчанию (Yulemdo)" : "Standart (Yulemdo)";
            case "uaApplied": return en ? "User agent changed" : ru ? "User agent изменён" : "User agent o'zgartirildi";
            case "stop": return en ? "Stop" : ru ? "Стоп" : "To'xtatish";
            case "newtab": return en ? "New tab" : ru ? "Новая вкладка" : "Yangi tab";
            case "newTabPlus": return en ? "+  New tab" : ru ? "+  Новая вкладка" : "+  Yangi tab";
            case "home": return en ? "Home" : ru ? "Главная" : "Bosh sahifa";
            case "share": return en ? "Share" : ru ? "Поделиться" : "Ulashish";
            case "closeOthers": return en ? "Close other tabs" : ru ? "Закрыть другие вкладки" : "Boshqa tablarni yopish";
            case "bookmarksTitle": return en ? "Bookmarks" : ru ? "Закладки" : "Xatcho'plar";
            case "bookmarksEmpty": return en ? "No bookmarks yet" : ru ? "Пока нет закладок" : "Hali xatcho'plar yo'q";
            case "bookmarkAdded": return en ? "Added to bookmarks" : ru ? "Добавлено в закладки" : "Xatcho'plarga qo'shildi";
            case "bookmarkRemoved": return en ? "Removed from bookmarks" : ru ? "Удалено из закладок" : "Xatcho'plardan olib tashlandi";
            case "downloadsTitle": return en ? "Downloads" : ru ? "Загрузки" : "Yuklamalar";
            case "downloadsEmpty": return en ? "No downloads yet" : ru ? "Пока нет загрузок" : "Hali yuklamalar yo'q";
            case "filterAll": return en ? "All" : ru ? "Все" : "Barchasi";
            case "filterImage": return en ? "Images" : ru ? "Изображения" : "Rasmlar";
            case "filterMusic": return en ? "Music" : ru ? "Музыка" : "Musiqa";
            case "filterApk": return en ? "Apps" : ru ? "Приложения" : "Ilovalar";
            case "filterOther": return en ? "Other" : ru ? "Другое" : "Boshqa";
            case "setDefaultTitle": return en ? "Make Yulemdo your default browser?" : ru ? "Сделать Yulemdo браузером по умолчанию?" : "Yulemdo standart brauzer qilib belgilansinmi?";
            case "setDefaultMsg": return en ? "Open links instantly without choosing a browser every time." : ru ? "Открывайте ссылки мгновенно, не выбирая браузер каждый раз." : "Havolalarni har safar brauzer tanlamasdan darhol ochish uchun.";
            case "setDefaultYes": return en ? "Set as default" : ru ? "Сделать по умолчанию" : "Standart qilish";
            case "setDefaultLater": return en ? "Not now" : ru ? "Не сейчас" : "Hozir emas";
            case "setDefaultDone": return en ? "Yulemdo is now your default browser" : ru ? "Yulemdo теперь браузер по умолчанию" : "Yulemdo endi standart brauzer";
            case "setDefaultSkipped": return en ? "Default browser not changed" : ru ? "Браузер по умолчанию не изменён" : "Standart brauzer o'zgartirilmadi";
            case "settings": return en ? "Settings" : ru ? "Параметры" : "Parametrlar";
            case "appearance": return en ? "Appearance" : ru ? "Оформление" : "Ko'rinish";
            case "day": return en ? "Day mode" : ru ? "Дневной режим" : "Kun rejimi";
            case "night": return en ? "Night mode" : ru ? "Ночной режим" : "Tun rejimi";
            case "tabs": return en ? "Tabs" : ru ? "Вкладки" : "Tablar";
            case "hint": return en ? "Search or type a URL" : ru ? "Поиск или адрес" : "Qidirish yoki manzil kiriting";
            case "close": return en ? "Close" : ru ? "Закрыть" : "Yopish";
            case "openFail": return en ? "Couldn't open" : ru ? "Не удалось открыть" : "Ochib bo'lmadi";
            case "downloading": return en ? "Downloading..." : ru ? "Загрузка..." : "Yuklanmoqda...";
            case "downloaded": return en ? "Saved: " : ru ? "Сохранено: " : "Yuklab olindi: ";
            case "downloadFail": return en ? "Download failed" : ru ? "Не удалось скачать" : "Yuklab bo'lmadi";
            default: return k;
        }
    }
}
