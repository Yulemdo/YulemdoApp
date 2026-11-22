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

/** Menyu, Xatcho'plar va User-Agent (telefon/kompyuter) tanlash oynasi. */
abstract class MenuActivity extends DownloadsActivity {
    UaPreset currentUa() {
        String id = prefs == null ? "auto" : prefs.getString("uaId", "auto");
        for (UaPreset p : UA_PRESETS) if (p.id.equals(id)) return p;
        return UA_PRESETS[0];
    }

    String uaLabel(UaPreset p) { return p.label != null ? p.label : tr("uaAuto"); }

    void applyUa(GeckoSession s) {
        if (s == null) return;
        UaPreset p = currentUa();
        try {
            GeckoSessionSettings st = s.getSettings();
            st.setUserAgentOverride(p.ua);
            st.setUserAgentMode(p.desktop ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                    : GeckoSessionSettings.USER_AGENT_MODE_MOBILE);
            st.setViewportMode(p.desktop ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                    : GeckoSessionSettings.VIEWPORT_MODE_MOBILE);
        } catch (Throwable ignored) { }
    }

    void selectUa(UaPreset p) {
        prefs.edit().putString("uaId", p.id).apply();
        for (Tab t : tabs) applyUa(t.session);
        if (aliveNav()) {
            try {
                allowSameTab = true;
                active.session.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE);
            } catch (Throwable e) {
                try { allowSameTab = true; active.session.reload(); } catch (Throwable ignored) { }
            }
        }
        toast(tr("uaApplied") + ": " + uaLabel(p));
        refreshUi();
    }

    void showUaMenu() {
        if (menuOpen()) { closeMenu(); return; }
        hideKb();
        if (addr != null) addr.clearFocus();

        menuScrim = new FrameLayout(this);
        menuScrim.setBackgroundColor(0x59000000);
        menuScrim.setAlpha(0f);
        menuScrim.setOnClickListener(v -> closeMenu());

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(cCard);
        cg.setCornerRadius(dp(20));
        cg.setStroke(dp(1), cBorder);
        card.setBackground(cg);
        card.setElevation(dp(10));
        card.setPadding(0, dp(8), 0, dp(8));

        TextView title = new TextView(this);
        title.setText(tr("uaTitle"));
        title.setTextColor(cText);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setPadding(dp(18), dp(8), dp(18), dp(6));
        card.addView(title);
        card.addView(divider());

        UaPreset cur = currentUa();
        for (final UaPreset p : UA_PRESETS) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(48));
            row.setPadding(dp(18), dp(6), dp(18), dp(6));
            row.setBackgroundResource(ripple());

            ImageView ic = new ImageView(this);
            UaIcon ui = new UaIcon(p.desktop);
            ic.setImageDrawable(ui);
            ic.setColorFilter(p == cur ? ORANGE_DEEP : cMuted);
            row.addView(ic, new LinearLayout.LayoutParams(dp(22), dp(22)));

            TextView tv = new TextView(this);
            tv.setText(uaLabel(p));
            tv.setTextColor(cText);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tv.setTypeface(null, p == cur ? Typeface.BOLD : Typeface.NORMAL);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tp.leftMargin = dp(14);
            row.addView(tv, tp);

            View radio = new View(this);
            GradientDrawable rg = new GradientDrawable();
            rg.setShape(GradientDrawable.OVAL);
            if (p == cur) { rg.setColor(ORANGE_DEEP); rg.setStroke(dp(2), ORANGE_DEEP); }
            else { rg.setColor(Color.TRANSPARENT); rg.setStroke(dp(2), cBorder); }
            radio.setBackground(rg);
            row.addView(radio, new LinearLayout.LayoutParams(dp(16), dp(16)));

            row.setOnClickListener(v -> { closeMenu(); selectUa(p); });
            card.addView(row);
        }

        int top = (bar != null && bar.getBottom() > 0) ? bar.getBottom() + dp(6) : dp(58);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                Math.min(dp(308), getResources().getDisplayMetrics().widthPixels - dp(20)),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        clp.topMargin = top;
        clp.rightMargin = dp(8);
        menuScrim.addView(card, clp);
        host.addView(menuScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (splash != null) splash.bringToFront();

        card.setAlpha(0f);
        card.setScaleX(0.96f);
        card.setScaleY(0.96f);
        card.setTranslationY(-dp(10));
        menuScrim.animate().alpha(1f).setDuration(160).start();
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0).setDuration(200)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    List<JSONObject> loadBookmarks() {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(BOOKMARKS_KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getJSONObject(i));
        } catch (Exception ignored) { }
        return out;
    }

    void saveBookmarksList(List<JSONObject> list) {
        JSONArray arr = new JSONArray();
        for (JSONObject o : list) arr.put(o);
        prefs.edit().putString(BOOKMARKS_KEY, arr.toString()).apply();
    }

    boolean isBookmarked(String url) {
        if (url == null || url.isEmpty()) return false;
        for (JSONObject o : loadBookmarks()) if (url.equals(o.optString("url"))) return true;
        return false;
    }

    void toggleBookmark() {
        if (active == null || active.url == null || active.url.isEmpty()) return;
        List<JSONObject> list = loadBookmarks();
        boolean removed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (active.url.equals(list.get(i).optString("url"))) { list.remove(i); removed = true; }
        }
        if (!removed) {
            try {
                JSONObject o = new JSONObject();
                o.put("url", active.url);
                o.put("title", labelOf(active));
                list.add(0, o);
            } catch (Exception ignored) { }
        }
        saveBookmarksList(list);
        toast(removed ? tr("bookmarkRemoved") : tr("bookmarkAdded"));
    }

    void showBookmarks() {
        hideKb();
        if (addr != null) addr.clearFocus();
        // Agar eski popup hali ekranda bo'lsa (animatsiya tugamagan bo'lsa ham),
        // uni darhol olib tashlaymiz — shu tufayli popup "yopilmay qolish" bug'i
        // yuzaga kelgan edi.
        if (menuScrim != null) {
            View old = menuScrim;
            menuScrim = null;
            host.removeView(old);
        }

        menuScrim = new FrameLayout(this);
        menuScrim.setBackgroundColor(0x59000000);
        menuScrim.setAlpha(0f);
        menuScrim.setOnClickListener(v -> closeMenu());

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(cCard);
        cg.setCornerRadius(dp(20));
        cg.setStroke(dp(1), cBorder);
        card.setBackground(cg);
        card.setElevation(dp(10));
        card.setPadding(0, dp(6), 0, dp(8));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(8), dp(4), dp(14), dp(6));
        ImageButton backBtn = iconBtn(R.drawable.ic_back, cText, v -> { closeMenu(); showMenu(null); });
        backBtn.setPadding(dp(9), dp(9), dp(9), dp(9));
        head.addView(backBtn, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView title = new TextView(this);
        title.setText(tr("bookmarksTitle"));
        title.setTextColor(cText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(6);
        head.addView(title, tlp);
        card.addView(head);
        card.addView(divider());

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        List<JSONObject> bms = loadBookmarks();
        if (bms.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(tr("bookmarksEmpty"));
            empty.setTextColor(cMuted);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            empty.setPadding(dp(18), dp(22), dp(18), dp(22));
            list.addView(empty);
        } else {
            for (JSONObject o : bms) {
                final String url = o.optString("url");
                final String bTitle = o.optString("title");
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(dp(48));
                row.setPadding(dp(14), dp(5), dp(6), dp(5));
                row.setBackgroundResource(ripple());
                row.addView(iconBubble(R.drawable.ic_star), new LinearLayout.LayoutParams(dp(30), dp(30)));
                TextView tv = new TextView(this);
                tv.setText(bTitle == null || bTitle.isEmpty() ? url : bTitle);
                tv.setTextColor(cText);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                tp.leftMargin = dp(12);
                row.addView(tv, tp);
                ImageButton del = iconBtn(R.drawable.ic_close, cMuted, v -> {
                    List<JSONObject> l2 = loadBookmarks();
                    for (int i = l2.size() - 1; i >= 0; i--) if (url.equals(l2.get(i).optString("url"))) l2.remove(i);
                    saveBookmarksList(l2);
                    showBookmarks();
                });
                del.setPadding(dp(6), dp(6), dp(6), dp(6));
                row.addView(del, new LinearLayout.LayoutParams(dp(30), dp(30)));
                row.setOnClickListener(v -> { closeMenu(); newTab(url); });
                list.addView(row);
            }
        }

        sv.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.62f);
        card.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int top = (bar != null && bar.getBottom() > 0) ? bar.getBottom() + dp(6) : dp(58);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                Math.min(dp(308), getResources().getDisplayMetrics().widthPixels - dp(20)),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        clp.topMargin = top;
        clp.rightMargin = dp(8);
        menuScrim.addView(card, clp);
        host.addView(menuScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (splash != null) splash.bringToFront();
        card.post(() -> {
            if (card.getHeight() > maxH) {
                ViewGroup.LayoutParams lp = sv.getLayoutParams();
                lp.height = maxH;
                sv.setLayoutParams(lp);
            }
        });

        card.setAlpha(0f);
        card.setScaleX(0.96f);
        card.setScaleY(0.96f);
        menuScrim.animate().alpha(1f).setDuration(160).start();
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    boolean menuOpen() { return menuScrim != null; }

    void closeMenu() {
        if (menuScrim == null) return;
        final View scrim = menuScrim;
        menuScrim = null;
        scrim.animate().alpha(0f).setDuration(140).withEndAction(() -> host.removeView(scrim)).start();
    }

    void showMenu(View anchor) {
        if (menuOpen()) { closeMenu(); return; }
        hideKb();
        if (addr != null) addr.clearFocus();
        // Ehtiyot shart: oldingi popup biror sababdan hali ham ilashib qolgan
        // bo'lsa, uni zudlik bilan olib tashlaymiz, shunda yangi menyu ustiga
        // tushib, "yopilmaydi" degan taassurot qoldirmaydi.
        if (menuScrim != null) {
            View old = menuScrim;
            menuScrim = null;
            host.removeView(old);
        }

        menuScrim = new FrameLayout(this);
        menuScrim.setBackgroundColor(0x59000000);
        menuScrim.setAlpha(0f);
        menuScrim.setOnClickListener(v -> closeMenu());

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(cCard);
        cg.setCornerRadius(dp(20));
        cg.setStroke(dp(1), cBorder);
        card.setBackground(cg);
        card.setElevation(dp(10));
        card.setPadding(0, dp(6), 0, dp(8));

        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        iconRow.setGravity(Gravity.CENTER);
        iconRow.setPadding(dp(4), dp(6), dp(4), dp(2));
        iconRow.addView(circleIconBtn(R.drawable.ic_back, cText, active != null && active.canBack, () -> {
            closeMenu();
            if (aliveNav() && active.canBack) { allowSameTab = true; active.session.goBack(); }
        }));
        iconRow.addView(circleIconBtn(R.drawable.ic_fwd, cText, active != null && active.canFwd, () -> {
            closeMenu();
            if (aliveNav() && active.canFwd) { allowSameTab = true; active.session.goForward(); }
        }));
        boolean hasUrl = active != null && active.url != null && !active.url.isEmpty();
        boolean starred = hasUrl && isBookmarked(active.url);
        iconRow.addView(circleIconBtn(R.drawable.ic_star, starred ? ORANGE_DEEP : cMuted, hasUrl, () -> {
            closeMenu();
            toggleBookmark();
        }));
        iconRow.addView(circleIconBtn(active != null && active.loading ? R.drawable.ic_close : R.drawable.ic_reload,
                cText, active != null, () -> { closeMenu(); reloadOrStop(); }));
        card.addView(iconRow);
        card.addView(divider());

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        addRow(list, R.drawable.ic_plus, tr("newtab"), true, () -> newTab(HOME));
        addRow(list, R.drawable.ic_home, tr("home"), true, this::goHome);
        addRow(list, R.drawable.ic_star, tr("bookmarksTitle"), true, this::showBookmarks);
        addRow(list, R.drawable.ic_download, tr("downloadsTitle"), true, true, this::showDownloads);
        addRow(list, R.drawable.ic_share, tr("share"), active != null && active.url != null && !active.url.isEmpty(), () -> {
            if (active == null || active.url == null || active.url.isEmpty()) return;
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("text/plain");
            s.putExtra(Intent.EXTRA_TEXT, active.url);
            try { startActivity(Intent.createChooser(s, tr("share"))); }
            catch (Exception e) { toast(tr("openFail")); }
        });
        addRow(list, R.drawable.ic_tabs_close, tr("closeOthers"), tabs.size() > 1, () -> {
            for (Tab t : new ArrayList<>(tabs)) if (t != active) closeTab(t);
        });

        sv.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.72f);
        card.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int top = (bar != null && bar.getBottom() > 0) ? bar.getBottom() + dp(6) : dp(58);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                Math.min(dp(308), getResources().getDisplayMetrics().widthPixels - dp(20)),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        clp.topMargin = top;
        clp.rightMargin = dp(8);
        menuScrim.addView(card, clp);
        host.addView(menuScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (splash != null) splash.bringToFront();
        sv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.post(() -> {
            if (card.getHeight() > maxH) {
                ViewGroup.LayoutParams lp = sv.getLayoutParams();
                lp.height = maxH;
                sv.setLayoutParams(lp);
            }
        });

        card.setAlpha(0f);
        card.setScaleX(0.96f);
        card.setScaleY(0.96f);
        card.setTranslationY(-dp(10));
        menuScrim.animate().alpha(1f).setDuration(160).start();
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0).setDuration(200)
                .setInterpolator(new DecelerateInterpolator()).start();
    }
}
