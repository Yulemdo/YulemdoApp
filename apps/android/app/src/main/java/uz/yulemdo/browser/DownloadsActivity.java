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

/** Yuklamalar: yozuvlar, filtr oynasi, to'g'ridan-to'g'ri yuklab olish va bildirishnoma. */
abstract class DownloadsActivity extends TabsActivity {
    boolean hasUnseenDownloads() { return prefs.getBoolean(DOWNLOADS_UNSEEN_KEY, false); }

    void setUnseenDownloads(boolean v) {
        prefs.edit().putBoolean(DOWNLOADS_UNSEEN_KEY, v).apply();
        runOnUiThread(() -> {
            if (menuScrim != null && downloadBadge != null) {
                downloadBadge.setVisibility(v ? View.VISIBLE : View.GONE);
            }
        });
        updateMenuAlert();
    }

    /** Uch nuqta (menyu) tugmasi: yuklanayotgan yoki ko'rilmagan yuklama bo'lsa
     *  cheti (ring) va ikonkasi qizaradi; yuklanayotganda yengil pulsatsiya qiladi. */
    void updateMenuAlert() {
        runOnUiThread(() -> {
            if (btnMenu == null || isFinishing()) return;
            boolean downloading = activeDownloads.get() > 0;
            boolean on = downloading || hasUnseenDownloads();
            if (on) {
                GradientDrawable ring = new GradientDrawable();
                ring.setShape(GradientDrawable.OVAL);
                ring.setColor(Color.TRANSPARENT);
                ring.setStroke(dp(2), 0xFFE53935);
                btnMenu.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22E53935), ring, null));
                btnMenu.setColorFilter(0xFFE53935);
            } else {
                btnMenu.setBackgroundResource(ripple());
                btnMenu.setColorFilter(cText);
            }
            if (downloading) {
                if (menuPulse == null) {
                    menuPulse = ValueAnimator.ofFloat(1f, 0.45f);
                    menuPulse.setDuration(700);
                    menuPulse.setRepeatMode(ValueAnimator.REVERSE);
                    menuPulse.setRepeatCount(ValueAnimator.INFINITE);
                    menuPulse.addUpdateListener(a -> { if (btnMenu != null) btnMenu.setAlpha((Float) a.getAnimatedValue()); });
                    menuPulse.start();
                }
            } else if (menuPulse != null) {
                menuPulse.cancel();
                menuPulse = null;
                btnMenu.setAlpha(1f);
            }
        });
    }

    List<JSONObject> loadDownloads() {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(DOWNLOADS_KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getJSONObject(i));
        } catch (Exception ignored) { }
        return out;
    }

    void saveDownloadsList(List<JSONObject> list) {
        JSONArray arr = new JSONArray();
        for (JSONObject o : list) arr.put(o);
        prefs.edit().putString(DOWNLOADS_KEY, arr.toString()).apply();
    }

    void addDownloadRecord(String name, String uriOrPath, String mime, boolean isContentUri) {
        List<JSONObject> list = loadDownloads();
        try {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("uri", uriOrPath);
            o.put("mime", mime == null ? "*/*" : mime);
            o.put("isContentUri", isContentUri);
            o.put("time", System.currentTimeMillis());
            list.add(0, o);
        } catch (Exception ignored) { }
        saveDownloadsList(list);
        setUnseenDownloads(true);
    }

    void openDownload(JSONObject o) {
        try {
            String name = o.optString("name");
            String mime = o.optString("mime", "*/*");
            boolean isContentUri = o.optBoolean("isContentUri", false);
            Uri uri;
            if (isContentUri) {
                uri = Uri.parse(o.optString("uri"));
            } else {
                java.io.File f = new java.io.File(o.optString("uri"));
                if (!f.exists()) { toast(tr("openFail")); return; }
                uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            }
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast(tr("openFail"));
        }
    }

        /** Fayl turini mime va kengaytma bo'yicha aniqlaydi. */
    static String downloadCategory(String mime, String name) {
        String m = mime == null ? "" : mime.toLowerCase();
        String n = name == null ? "" : name.toLowerCase();
        if (m.startsWith("image/") || n.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp|heic|svg)$")) return "image";
        if (m.startsWith("audio/") || n.matches(".*\\.(mp3|m4a|wav|ogg|flac|aac|opus)$")) return "music";
        if (m.equals("application/vnd.android.package-archive") || n.endsWith(".apk")) return "apk";
        return "other";
    }

    /** Joriy filtr: all / image / music / apk / other */
    String downloadsFilter = "all";

    void showDownloads() {
        downloadsFilter = "all";
        showDownloadsFiltered();
    }

    void showDownloadsFiltered() {
        hideKb();
        setUnseenDownloads(false);
        if (addr != null) addr.clearFocus();
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
        title.setText(tr("downloadsTitle"));
        title.setTextColor(cText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(6);
        head.addView(title, tlp);
        card.addView(head);

        // ---- Saralash tugmalari ----
        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setPadding(dp(10), 0, dp(10), dp(6));
        android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.addView(filterRow, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addFilterChip(filterRow, tr("filterAll"), "all");
        addFilterChip(filterRow, tr("filterImage"), "image");
        addFilterChip(filterRow, tr("filterMusic"), "music");
        addFilterChip(filterRow, tr("filterApk"), "apk");
        addFilterChip(filterRow, tr("filterOther"), "other");
        card.addView(hsv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(divider());

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        List<JSONObject> dls = new ArrayList<>();
        for (JSONObject d : loadDownloads()) {
            String c = downloadCategory(d.optString("mime"), d.optString("name"));
            if (downloadsFilter.equals("all") || downloadsFilter.equals(c)) dls.add(d);
        }
        if (dls.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(tr("downloadsEmpty"));
            empty.setTextColor(cMuted);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            empty.setPadding(dp(18), dp(22), dp(18), dp(22));
            list.addView(empty);
        } else {
            for (final JSONObject o : dls) {
                final long rowTime = o.optLong("time", -1);
                final String dName = o.optString("name");
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(dp(48));
                row.setPadding(dp(14), dp(5), dp(6), dp(5));
                row.setBackgroundResource(ripple());
                row.addView(iconBubble(R.drawable.ic_download), new LinearLayout.LayoutParams(dp(30), dp(30)));
                TextView tv = new TextView(this);
                tv.setText(dName == null || dName.isEmpty() ? "?" : dName);
                tv.setTextColor(cText);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                tp.leftMargin = dp(12);
                row.addView(tv, tp);
                ImageButton del = iconBtn(R.drawable.ic_close, cMuted, v -> {
                    List<JSONObject> l2 = loadDownloads();
                    // Nom bo'yicha emas, unique "time" bo'yicha: faqat bitta yozuv o'chadi
                    for (int i = 0; i < l2.size(); i++) {
                        if (rowTime != -1 && l2.get(i).optLong("time", -2) == rowTime) {
                            l2.remove(i);
                            break;
                        }
                    }
                    saveDownloadsList(l2);
                    showDownloadsFiltered();
                });
                del.setPadding(dp(6), dp(6), dp(6), dp(6));
                row.addView(del, new LinearLayout.LayoutParams(dp(30), dp(30)));
                row.setOnClickListener(v -> { closeMenu(); openDownload(o); });
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

        void addFilterChip(LinearLayout row, String label, final String key) {
        boolean sel = downloadsFilter.equals(key);
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        chip.setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
        chip.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        if (sel) {
            bg.setColor(ORANGE_DEEP);
            chip.setTextColor(Color.WHITE);
        } else {
            bg.setColor(Color.TRANSPARENT);
            bg.setStroke(dp(1), cBorder);
            chip.setTextColor(cText);
        }
        chip.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), 0, dp(4), 0);
        pressAnim(chip);
        chip.setOnClickListener(v -> { downloadsFilter = key; showDownloadsFiltered(); });
        row.addView(chip, lp);
    }

    void startDirectDownload(final String src, final String rawName) {
        String sc = null;
        try { sc = Uri.parse(src).getScheme(); } catch (Exception ignored) { }
        if (sc == null || !(sc.equalsIgnoreCase("http") || sc.equalsIgnoreCase("https"))) {
            toast(tr("downloadFail"));
            return;
        }
        toast(tr("downloading"));
        final int notifyId = NOTIFY_ID.incrementAndGet();
        final String initialLabel = (rawName == null || rawName.isEmpty()) ? "Yulemdo" : rawName;
        DownloadNotifier.showProgress(getApplicationContext(), notifyId, initialLabel);
        activeDownloads.incrementAndGet();
        updateMenuAlert();
        IO.execute(() -> {
            HttpURLConnection c = null;
            try {
                String cur = src;
                for (int hop = 0; hop < 6; hop++) {
                    c = (HttpURLConnection) new URL(cur).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    c.setInstanceFollowRedirects(false);
                    c.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Android 13; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0");
                    c.setRequestProperty("Accept", "*/*");
                    int code = c.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String loc = c.getHeaderField("Location");
                        c.disconnect();
                        if (loc == null) throw new java.io.IOException("redirect");
                        cur = new URL(new URL(cur), loc).toString();
                        c = null;
                        continue;
                    }
                    if (code != 200) throw new java.io.IOException("HTTP " + code);
                    break;
                }
                if (c == null) throw new java.io.IOException("redirects");

                String name = rawName == null ? "" : rawName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
                if (name.isEmpty()) name = URLUtil.guessFileName(cur, null, c.getContentType());
                String mime = c.getContentType();
                if (mime != null) mime = mime.split(";")[0].trim();
                if (mime == null || mime.isEmpty() || mime.equals("application/octet-stream")) {
                    String ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(name);
                    String g = ext == null || ext.isEmpty() ? null
                            : android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
                    mime = g != null ? g : "application/octet-stream";
                }
                saveDownload(c.getInputStream(), name, mime, notifyId);
            } catch (Exception e) {
                toast(tr("downloadFail"));
                DownloadNotifier.showFailed(getApplicationContext(), notifyId, initialLabel);
            } finally {
                if (c != null) try { c.disconnect(); } catch (Exception ignored) { }
                activeDownloads.decrementAndGet();
                updateMenuAlert();
            }
        });
    }

    /** Sahifadan kelgan (onExternalResponse) yuklamalar uchun. */
    void saveDownload(InputStream src, String name, String mime) {
        int notifyId = NOTIFY_ID.incrementAndGet();
        DownloadNotifier.showProgress(getApplicationContext(), notifyId, name);
        activeDownloads.incrementAndGet();
        updateMenuAlert();
        try {
            saveDownload(src, name, mime, notifyId);
        } finally {
            activeDownloads.decrementAndGet();
            updateMenuAlert();
        }
    }

    void saveDownload(InputStream src, String name, String mime, int notifyId) {
        String type = mime == null ? "application/octet-stream" : mime.split(";")[0].trim();
        try (InputStream in = src) {
            byte[] buf = new byte[16384];
            int n;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(android.provider.MediaStore.Downloads.MIME_TYPE, type);
                v.put(android.provider.MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new java.io.IOException("MediaStore");
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new java.io.IOException("stream");
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                v.clear();
                v.put(android.provider.MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(uri, v, null, null);
                addDownloadRecord(name, uri.toString(), type, true);
                toast(tr("downloaded") + name);
                DownloadNotifier.showDone(getApplicationContext(), notifyId, name, uri, type, true);
            } else {
                java.io.File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir != null && !dir.exists()) dir.mkdirs();
                java.io.File f = new java.io.File(dir, name);
                try (OutputStream out = new java.io.FileOutputStream(f)) {
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                addDownloadRecord(name, f.getAbsolutePath(), type, false);
                toast(tr("downloaded") + name);
                DownloadNotifier.showDone(getApplicationContext(), notifyId, name, Uri.fromFile(f), type, false);
            }
        } catch (Exception e) {
            toast(tr("downloadFail"));
            DownloadNotifier.showFailed(getApplicationContext(), notifyId, name);
        }
    }
}
