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

/** Tablar, GeckoSession, favicon, havola ushlash (intercept), tab almashtirgich va manzil satri navigatsiyasi. */
abstract class TabsActivity extends ThemeActivity {
    void loadFavicon(final Tab t) {
        final String host = hostOf(t.url);
        if (host == null) { t.fav = null; t.favHost = ""; return; }
        if (host.equals(t.favHost) && t.fav != null) return;
        t.favHost = host;
        Bitmap cached = FAV.get(host);
        if (cached != null) { t.fav = cached; return; }
        t.fav = letterIcon(host);
        if (!FAV_BUSY.add(host)) return;
        IO.execute(() -> {
            Bitmap bm = null;
            HttpURLConnection h = null;
            try {
                h = (HttpURLConnection) new URL(
                        "https://www.google.com/s2/favicons?sz=64&domain=" + host).openConnection();
                h.setConnectTimeout(2500);
                h.setReadTimeout(2500);
                h.setInstanceFollowRedirects(true);
                bm = BitmapFactory.decodeStream(h.getInputStream());
            } catch (Exception ignored) {
            } finally {
                if (h != null) try { h.disconnect(); } catch (Exception ignored) { }
                FAV_BUSY.remove(host);
            }
            if (bm == null) return;
            FAV.put(host, bm);
            final Bitmap fb = bm;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                for (Tab tab : tabs) {
                    if (host.equals(hostOf(tab.url))) {
                        tab.fav = fb;
                        tab.favHost = host;
                    }
                }
                refreshUi();
                if (switcherOpen()) rebuildGrid(false);
            });
        });
    }

    void onLoc(Tab t, String url) {
        if (url != null && !url.startsWith("yulemdotheme:") && !url.startsWith("yulemdolang:")
                && !url.startsWith("Yulemdotheme:") && !url.startsWith("Yulemdolang:")) {
            t.url = url;
        }
        loadFavicon(t);
        refreshUi();
    }

    static String hdr(Map<String, String> h, String name) {
        if (h == null) return null;
        for (Map.Entry<String, String> e : h.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    GeckoResult<AllowOrDeny> intercept(String uri) {
        if (uri == null) return null;
        Uri u;
        try { u = Uri.parse(uri); } catch (Exception e) { return null; }
        String scheme = u.getScheme();
        if (scheme == null) return null;
        // Sayt "Yuklab olish" tugmasi: backend rasmni Content-Disposition: attachment
        // bilan beradi. Bu havola yangi tabda ochilmasligi kerak — joriy sessiyada
        // qoladi va onExternalResponse orqali "Yuklamalar"ga saqlanadi.
        String host = u.getHost();
        String path = u.getPath();
        if ((scheme.equalsIgnoreCase("https")) && host != null && path != null
                && host.equalsIgnoreCase("api.yulemdo.uz")
                && path.startsWith("/api/download-image")) {
            return GeckoResult.fromValue(AllowOrDeny.ALLOW);
        }
        // Sayt rasm/videoni "Yuklab olish" bosilganda yashirin iframe orqali
        // Yulemdodownload://get?url=...&name=... yuboradi. Faylni o'zimiz yuklab,
        // "Yuklamalar"ga saqlaymiz; sahifa/yangi tab ochilmaydi.
        if (scheme.equalsIgnoreCase("yulemdodownload")) {
            final String src = u.getQueryParameter("url");
            final String nm = u.getQueryParameter("name");
            if (src != null) startDirectDownload(src, nm);
            return GeckoResult.fromValue(AllowOrDeny.DENY);
        }
        // Sayt "Ulashish" tugmasi: Yulemdoshare://send?url=...&title=...
        // Android'ning standart ulashish oynasi (ilovalar ro'yxati) ochiladi.
        if (scheme.equalsIgnoreCase("yulemdoshare")) {
            final String shareUrl = u.getQueryParameter("url");
            final String shareTitle = u.getQueryParameter("title");
            if (shareUrl != null && !shareUrl.isEmpty()) {
                runOnUiThread(() -> {
                    try {
                        Intent s = new Intent(Intent.ACTION_SEND);
                        s.setType("text/plain");
                        s.putExtra(Intent.EXTRA_TEXT, shareUrl);
                        if (shareTitle != null && !shareTitle.isEmpty()) s.putExtra(Intent.EXTRA_SUBJECT, shareTitle);
                        startActivity(Intent.createChooser(s, tr("share")));
                    } catch (Exception e) {
                        toast(tr("openFail"));
                    }
                });
            }
            return GeckoResult.fromValue(AllowOrDeny.DENY);
        }
        if (scheme.equalsIgnoreCase("yulemdotheme")) {
            String d = u.getQueryParameter("dark");
            boolean night = "1".equals(d) || "true".equalsIgnoreCase(d);
            runOnUiThread(() -> onThemeFromSite(night));
            return GeckoResult.fromValue(AllowOrDeny.DENY);
        }
        if (scheme.equalsIgnoreCase("yulemdolang")) {
            String next = u.getQueryParameter("lang");
            if (next != null) runOnUiThread(() -> onLangFromSite(next));
            return GeckoResult.fromValue(AllowOrDeny.DENY);
        }
        if (scheme.equalsIgnoreCase("intent")) {
            final String target = uri;
            runOnUiThread(() -> openIntentUri(target));
            return GeckoResult.fromValue(AllowOrDeny.DENY);
        }
        if (scheme.equalsIgnoreCase("mailto") || scheme.equalsIgnoreCase("tel")
                || scheme.equalsIgnoreCase("sms") || scheme.equalsIgnoreCase("smsto")
                || scheme.equalsIgnoreCase("market")) {
            final String target = uri;
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
                } catch (Exception e) {
                    toast(tr("openFail"));
                }
            });
            return GeckoResult.fromValue(AllowOrDeny.DENY);
        }
        return null;
    }

    void openIntentUri(String raw) {
        try {
            Intent intent = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setComponent(null);
            intent.setSelector(null);
            try {
                startActivity(intent);
                return;
            } catch (android.content.ActivityNotFoundException notFound) {
                String fallback = intent.getStringExtra("browser_fallback_url");
                if (fallback != null && !fallback.isEmpty()) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fallback)));
                        return;
                    } catch (Exception ignored) { }
                }
                String pkg = intent.getPackage();
                if (pkg != null) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse("market://details?id=" + pkg)));
                        return;
                    } catch (Exception ignored) { }
                }
                toast(tr("openFail"));
            }
        } catch (Exception e) {
            toast(tr("openFail"));
        }
    }

    Tab createTab() {
        final Tab t = new Tab();
        final GeckoSession s = new GeckoSession();
        t.session = s;
        applyUa(s);

        s.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession session, LoadRequest request) {
                GeckoResult<AllowOrDeny> hit = intercept(request == null ? null : request.uri);
                if (hit != null) return hit;
                // Brauzer chrome'i (address bar / home / orqaga-oldinga / reload)
                // sodir qilgan navigatsiya joriy tabda qoladi.
                if (allowSameTab) {
                    allowSameTab = false;
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                }
                // Foydalanuvchi sahifa ichida bosgan har qanday link yangi tabda ochiladi.
                if (request != null && request.hasUserGesture && request.uri != null) {
                    final String uri = request.uri;
                    runOnUiThread(() -> newTab(uri));
                    return GeckoResult.fromValue(AllowOrDeny.DENY);
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW);
            }

            @Override
            public GeckoResult<AllowOrDeny> onSubframeLoadRequest(GeckoSession session, LoadRequest request) {
                // Sayt Yulemdotheme:// / Yulemdolang:// havolasini yashirin <iframe>
                // orqali yuboradi — bu subframe navigatsiya bo'lib, onLoadRequest
                // emas, aynan shu metod orqali keladi.
                GeckoResult<AllowOrDeny> hit = intercept(request == null ? null : request.uri);
                if (hit != null) return hit;
                return GeckoResult.fromValue(AllowOrDeny.ALLOW);
            }

            public void onLocationChange(GeckoSession session, String url,
                                         List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                                         Boolean hasUserGesture) {
                onLoc(t, url);
            }

            public void onLocationChange(GeckoSession session, String url,
                                         List<GeckoSession.PermissionDelegate.ContentPermission> perms) {
                onLoc(t, url);
            }

            @Override
            public void onCanGoBack(GeckoSession session, boolean can) {
                t.canBack = can;
                refreshUi();
            }

            @Override
            public void onCanGoForward(GeckoSession session, boolean can) {
                t.canFwd = can;
                refreshUi();
            }

            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession session, String uri) {
                final Tab nt = createTab();
                nt.url = (uri == null || uri.isEmpty()) ? HOME : uri;
                runOnUiThread(() -> {
                    tabs.add(nt);
                    loadFavicon(nt);
                    host.post(() -> select(nt));
                });
                return GeckoResult.fromValue(nt.session);
            }
        });

        s.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onTitleChange(GeckoSession session, String title) {
                t.title = title == null ? "" : title;
                refreshUi();
            }

            @Override
            public void onCloseRequest(GeckoSession session) {
                runOnUiThread(() -> closeTab(t));
            }

            @Override
            public void onExternalResponse(GeckoSession session, WebResponse response) {
                final String mime = hdr(response.headers, "content-type");
                final String cd = hdr(response.headers, "content-disposition");
                final String name = URLUtil.guessFileName(response.uri, cd, mime);
                final InputStream in = response.body;
                if (in == null) { toast(tr("downloadFail")); return; }
                toast(tr("downloading"));
                IO.execute(() -> saveDownload(in, name, mime));
            }
        });

        s.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(GeckoSession session, String url) {
                t.loading = true;
                if (url != null) t.url = url;
                loadFavicon(t);
                refreshUi();
            }

            @Override
            public void onPageStop(GeckoSession session, boolean success) {
                t.loading = false;
                if (t == active && progress != null) progress.setVisibility(View.INVISIBLE);
                refreshUi();
                if (success && host != null) host.postDelayed(() -> captureThumb(t), 600);
            }

            @Override
            public void onProgressChange(GeckoSession session, int p) {
                if (t != active || progress == null) return;
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }
        });
        return t;
    }

    void newTab(String url) {
        Tab t = createTab();
        tabs.add(t);
        t.url = (url == null || url.isEmpty()) ? HOME : url;
        loadFavicon(t);
        select(t);
        allowSameTab = true;
        try { t.session.loadUri(t.url); } catch (Exception ignored) { }
        refreshUi();
    }

    // Tabning oxirgi holati (skrinshot) — tablar ro'yxatida Chrome'dagidek ko'rinadi.
    void captureThumb(final Tab t) {
        if (t == null || t.session == null || geckoView == null) return;
        if (t != active || bound != t.session) return;
        try {
            geckoView.capturePixels().accept(bm -> {
                if (bm == null || isFinishing()) return;
                // Kattaroq olingan skrinshot — tab kartasi kattalashganda xira/kesilgan bo'lib ko'rinmasin.
                int w = 640;
                int h = Math.max(1, Math.round(bm.getHeight() * (w / (float) bm.getWidth())));
                try {
                    t.thumb = Bitmap.createScaledBitmap(bm, w, h, true);
                } catch (Throwable ignored) { return; }
                if (switcherOpen()) rebuildGrid(false);
            }, err -> { });
        } catch (Throwable ignored) { }
    }

    void select(Tab t) {
        if (t == null || t.session == null) return;
        if (active != null && active != t) captureThumb(active);
        active = t;
        try {
            if (!t.session.isOpen()) t.session.open(runtime);
        } catch (Exception ignored) { }
        if (bound != t.session) {
            try {
                geckoView.setSession(t.session);
                bound = t.session;
                geckoView.setAlpha(0f);
                geckoView.animate().alpha(1f).setDuration(160).start();
            } catch (Exception ex) {
                host.postDelayed(() -> {
                    if (active == t && bound != t.session) {
                        try {
                            if (!t.session.isOpen()) t.session.open(runtime);
                            geckoView.setSession(t.session);
                            bound = t.session;
                        } catch (Exception ignored) { }
                    }
                }, 48);
            }
        }
        if (progress != null) progress.setVisibility(View.INVISIBLE);
        refreshUi();
    }

    void closeSession(GeckoSession s) {
        if (s == null) return;
        try { if (s.isOpen()) s.close(); } catch (Exception ignored) { }
    }

    void closeTab(Tab t) {
        if (t == null) return;
        int i = tabs.indexOf(t);
        if (i < 0) return;
        boolean was = t == active;
        tabs.remove(t);
        GeckoSession dying = t.session;
        t.session = null;
        if (bound == dying) bound = null;
        if (tabs.isEmpty()) {
            newTab(HOME);
            closeSession(dying);
        } else if (was) {
            select(tabs.get(Math.max(0, i - 1)));
            closeSession(dying);
        } else {
            closeSession(dying);
        }
        refreshUi();
        if (switcherOpen()) rebuildGrid(false);
    }

    boolean isHome(String u) {
        try {
            String h = Uri.parse(u).getHost();
            return h != null && h.replaceFirst("^www\\.", "").equals("yulemdo.uz");
        } catch (Exception e) {
            return false;
        }
    }

    String shown(String u) {
        if (u == null || u.isEmpty() || u.startsWith("data:") || u.equals("about:blank")) return "";
        if (isHome(u)) {
            String p = Uri.parse(u).getPath();
            if (p == null || p.isEmpty() || p.equals("/")) return "";
        }
        return u;
    }

    String labelOf(Tab t) {
        if (t.title != null && !t.title.isEmpty()) return t.title;
        return isHome(t.url) ? "Yulemdo" : tr("newtab");
    }

    void refreshUi() {
        if (host == null || uiQueued) return;
        uiQueued = true;
        host.post(this::refreshUiNow);
    }

    void refreshUiNow() {
        uiQueued = false;
        if (isFinishing() || active == null || addr == null) return;
        UaPreset up = currentUa();
        if (uaIcon != null && uaIcon.desktop != up.desktop) { uaIcon.desktop = up.desktop; uaIcon.invalidateSelf(); }
        btnUa.setColorFilter(up.ua == null ? cText : ORANGE_DEEP);
        tabCount.setText(tabs.size() > 99 ? ":)" : String.valueOf(tabs.size()));
        if (active.fav != null) {
            favView.clearColorFilter();
            favView.setImageBitmap(active.fav);
        } else {
            favView.setImageResource(R.drawable.ic_home);
            favView.setColorFilter(ORANGE_DEEP);
        }
        if (!addr.hasFocus()) addr.setText(shown(active.url));
    }

    void buildSwitcher() {
        switcher = new FrameLayout(this);
        switcher.setBackgroundColor(cSwitch);
        switcher.setVisibility(View.GONE);
        switcher.setClickable(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(18), dp(10), dp(14), dp(10));
        swTitle = new TextView(this);
        swTitle.setTextColor(cText);
        swTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        swTitle.setTypeface(null, Typeface.BOLD);
        head.addView(swTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        swPlus = new TextView(this);
        swPlus.setText(tr("newTabPlus"));
        swPlus.setTextColor(Color.WHITE);
        swPlus.setTypeface(null, Typeface.BOLD);
        swPlus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        swPlus.setPadding(dp(16), dp(9), dp(16), dp(9));
        GradientDrawable pg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFFF9100, 0xFFFF3D00});
        pg.setCornerRadius(dp(22));
        swPlus.setBackground(pg);
        pressAnim(swPlus);
        swPlus.setOnClickListener(v -> { newTab(HOME); closeSwitcher(); });
        head.addView(swPlus);
        col.addView(head);

        ScrollView sv = new ScrollView(this);
        grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setPadding(dp(8), dp(4), dp(8), dp(16));
        sv.addView(grid, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        switcher.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        host.addView(switcher, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    boolean switcherOpen() { return switcher != null && switcher.getVisibility() == View.VISIBLE; }

    void openSwitcher() {
        closeMenu();
        hideKb();
        if (addr != null) addr.clearFocus();
        captureThumb(active);
        rebuildGrid(true);
        switcher.setAlpha(0f);
        switcher.setScaleX(0.94f);
        switcher.setScaleY(0.94f);
        switcher.setVisibility(View.VISIBLE);
        switcher.bringToFront();
        switcher.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    void closeSwitcher() {
        if (!switcherOpen()) return;
        switcher.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(160)
                .withEndAction(() -> switcher.setVisibility(View.GONE)).start();
    }

    void rebuildGrid(boolean animate) {
        if (grid == null) return;
        grid.removeAllViews();
        swTitle.setText(tr("tabs") + " (" + tabs.size() + ")");
        swTitle.setTextColor(cText);
        int cardW = (getResources().getDisplayMetrics().widthPixels - dp(16)) / 2 - dp(12);
        int i = 0;
        for (Tab t : tabs) {
            View card = makeCard(t);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED));
            lp.width = cardW; // aniq kenglik: 1 ta tab bo'lsa ham butun ekranga yoyilmaydi
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            grid.addView(card, lp);
            if (animate) {
                card.setAlpha(0f);
                card.setTranslationY(dp(28));
                card.animate().alpha(1f).translationY(0).setStartDelay(i * 40L).setDuration(260)
                        .setInterpolator(new DecelerateInterpolator()).start();
            }
            i++;
        }
    }

    View makeCard(final Tab t) {
        boolean on = t == active;
        final LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cCard);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(on ? 2 : 1), on ? ORANGE_DEEP : cBorder);
        c.setBackground(bg);
        c.setPadding(dp(8), dp(6), dp(4), dp(8));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView fv = new ImageView(this);
        if (t.fav != null) fv.setImageBitmap(t.fav);
        else { fv.setImageResource(R.drawable.ic_home); fv.setColorFilter(ORANGE_DEEP); }
        row.addView(fv, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView tt = new TextView(this);
        tt.setText(labelOf(t));
        tt.setSingleLine(true);
        tt.setEllipsize(TextUtils.TruncateAt.END);
        tt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tt.setTextColor(cText);
        tt.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.setMargins(dp(8), 0, dp(2), 0);
        row.addView(tt, tp);
        ImageButton x = iconBtn(R.drawable.ic_close, cMuted, v -> {
            c.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(150)
                    .withEndAction(() -> { closeTab(t); rebuildGrid(false); }).start();
        });
        x.setPadding(dp(5), dp(5), dp(5), dp(5));
        x.setContentDescription(tr("close"));
        row.addView(x, new LinearLayout.LayoutParams(dp(28), dp(28)));
        c.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER);
        GradientDrawable bb = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                dark ? new int[]{0xFF3A2414, 0xFF26262B} : new int[]{0xFFFFE9D0, 0xFFFFF8F1});
        bb.setCornerRadius(dp(12));
        body.setBackground(bb);
        body.setClipToOutline(true);
        if (t.thumb != null) {
            final Bitmap tb = t.thumb;
            ImageView shot = new ImageView(this) {
                @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
                    super.onSizeChanged(w, h, ow, oh);
                    if (w <= 0) return;
                    float s = w / (float) tb.getWidth();
                    android.graphics.Matrix m = new android.graphics.Matrix();
                    m.setScale(s, s); // yuqoriga tekislangan, Chrome kabi
                    setImageMatrix(m);
                }
            };
            shot.setScaleType(ImageView.ScaleType.MATRIX);
            shot.setImageBitmap(tb);
            body.addView(shot, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            ImageView big = new ImageView(this);
            if (t.fav != null) big.setImageBitmap(t.fav);
            else { big.setImageResource(R.drawable.ic_home); big.setColorFilter(ORANGE_DEEP); }
            body.addView(big, new LinearLayout.LayoutParams(dp(40), dp(40)));
            TextView hs = new TextView(this);
            String h = hostOf(t.url);
            hs.setText(h == null ? tr("newtab") : h.replaceFirst("^www\\.", ""));
            hs.setTextColor(cMuted);
            hs.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            hs.setSingleLine(true);
            hs.setEllipsize(TextUtils.TruncateAt.END);
            hs.setPadding(dp(8), dp(8), dp(8), 0);
            body.addView(hs);
        }
        // Chrome'dagi tab kartalari kabi uzunroq va kattaroq ko'rinish uchun balandlik
        // ekran kengligiga qarab hisoblanadi (taxminan 2 ustunli kartaning ~0.72 nisbati).
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int cardBodyH = Math.round((screenW / 2f) * 0.95f);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cardBodyH);
        bp.setMargins(0, dp(2), dp(4), 0);
        c.addView(body, bp);

        pressAnim(c);
        c.setOnClickListener(v -> { if (t != active) select(t); closeSwitcher(); });
        return c;
    }

    void go(String text) {
        if (active == null || active.session == null) return;
        String q = text == null ? "" : text.trim();
        if (q.isEmpty()) return;
        String url;
        if (q.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) url = q;
        else if (!q.contains(" ") && q.contains(".")) url = "https://" + q;
        else {
            try { url = "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8"); }
            catch (Exception e) { url = HOME; }
        }
        try {
            if (!active.session.isOpen()) active.session.open(runtime);
            allowSameTab = true;
            active.session.loadUri(url);
        } catch (Exception ignored) { }
    }

    void goHome() {
        if (switcherOpen()) closeSwitcher();
        closeMenu();
        if (active == null || active.session == null) { newTab(HOME); return; }
        try {
            if (!active.session.isOpen()) active.session.open(runtime);
            allowSameTab = true;
            active.session.loadUri(HOME);
        } catch (Exception e) { toast(tr("openFail")); }
    }

    void reloadOrStop() {
        if (active == null || active.session == null || !active.session.isOpen()) return;
        try {
            if (active.loading) active.session.stop();
            else { allowSameTab = true; active.session.reload(); }
        } catch (Exception ignored) { }
    }

    boolean aliveNav() {
        return active != null && active.session != null && active.session.isOpen();
    }
}
