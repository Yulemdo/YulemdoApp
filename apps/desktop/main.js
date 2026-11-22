'use strict';
const {
  app, BrowserWindow, WebContentsView, ipcMain, Menu, shell, session, clipboard, nativeImage,
} = require('electron');
const path = require('path');
const fs = require('fs');

const HOME = 'https://yulemdo.uz';
const PARTITION = 'persist:yulemdo';
const CHROME_H = 92; // 40 tab strip + 52 toolbar

if (!app.requestSingleInstanceLock()) app.quit();

// Barcha so'rovlar (service worker, fon so'rovlari ham) uchun asosiy UA:
// Electron va ilova nomi (yulemdo-desktop/1.0.0) olib tashlanib, "Yulemdo/1.0" qo'shiladi.
const YULEMDO_TAG = 'Yulemdo/1.0';
const BASE_UA = app.userAgentFallback
  .replace(/\sElectron\/\S+/i, '')
  .replace(/\syulemdo-desktop\/\S+/i, '')
  .replace(/\sYulemdo\/\S+/i, '')
  .trim() + ' ' + YULEMDO_TAG;
app.userAgentFallback = BASE_UA;

// ── Matnlar (uz / ru / en) ───────────────────────────────────────────────────
const L = {
  uz: {
    newTab: 'Yangi tab', home: 'Bosh sahifa', reload: 'Yangilash', copyLink: 'Havolani nusxalash',
    downloads: 'Yuklanmalar', closeAll: 'Barcha tablarni yopish', terms: 'Foydalanish shartlari',
    privacy: 'Maxfiylik siyosati', about: 'Biz haqimizda', address: 'Qidirish yoki manzil kiriting',
    copied: 'Nusxalandi', back: 'Orqaga', forward: 'Oldinga', menu: 'Menyu', close: 'Yopish',
    downloaded: 'Yuklab olindi',
    userAgent: 'User agent', uaDefault: 'Standart',
    errTitle: "Sahifani ochib bo'lmadi",
    errDesc: "Ulanishda muammo yuz berdi. Internetingizni tekshirib, qayta urinib ko'ring.",
    errHome: 'Bosh sahifaga qaytish',
  },
  ru: {
    newTab: 'Новая вкладка', home: 'Главная страница', reload: 'Обновить', copyLink: 'Скопировать ссылку',
    downloads: 'Загрузки', closeAll: 'Закрыть все вкладки', terms: 'Условия использования',
    privacy: 'Политика конфиденциальности', about: 'О нас', address: 'Введите запрос или адрес',
    copied: 'Скопировано', back: 'Назад', forward: 'Вперёд', menu: 'Меню', close: 'Закрыть',
    downloaded: 'Загружено',
    userAgent: 'User agent', uaDefault: 'По умолчанию',
    errTitle: 'Не удалось открыть страницу',
    errDesc: 'Возникла проблема с подключением. Проверьте интернет и попробуйте снова.',
    errHome: 'Вернуться на главную',
  },
  en: {
    newTab: 'New tab', home: 'Home page', reload: 'Refresh', copyLink: 'Copy link',
    downloads: 'Downloads', closeAll: 'Close all tabs', terms: 'Terms of Use',
    privacy: 'Privacy Policy', about: 'About us', address: 'Search or enter address',
    copied: 'Copied', back: 'Back', forward: 'Forward', menu: 'Menu', close: 'Close',
    downloaded: 'Downloaded',
    userAgent: 'User agent', uaDefault: 'Default',
    errTitle: "Couldn't load the page",
    errDesc: 'There was a connection problem. Check your internet and try again.',
    errHome: 'Go back home',
  },
};

let win = null;
let ui = null;
let tabs = []; // { id, view, favicon }
let activeId = null;
let nextId = 1;
let dark = false;
let lang = 'uz';
let htmlFullscreen = false;
let saveTimer = null;
let uaKey = 'default';
let icons = {};

const UA_LIST = [
  { key: 'default', label: null, ua: null },
  { key: 'yulemdoWin', label: 'Yulemdo (Windows)', ua: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Yulemdo/1.0' },
  { key: 'chromeWin', label: 'Chrome (Windows)', ua: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36' },
  { key: 'chromeMac', label: 'Safari (macOS)', ua: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15' },
  { key: 'firefox', label: 'Firefox (Windows)', ua: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0' },
  { key: 'android', label: 'Chrome (Android)', ua: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36' },
  { key: 'iphone', label: 'Safari (iPhone)', ua: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1' },
];

function currentUA() {
  const item = UA_LIST.find((u) => u.key === uaKey);
  if (item && item.ua) return item.ua;
  return BASE_UA;
}

function applyUA(key) {
  uaKey = key;
  session.fromPartition(PARTITION).setUserAgent(currentUA());
  for (const t of tabs) {
    const wc = t.view.webContents;
    wc.setUserAgent(currentUA(wc));
    wc.reload();
  }
  scheduleSave();
}

// Menyu ikonkalari (SVG path -> PNG, interfeys oynasidagi canvas orqali)
const ICON_PATHS = {
  reload: 'M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z',
  copyLink: 'M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z',
  newTab: 'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z',
  home: 'M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z',
  downloads: 'M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z',
  closeAll: 'M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
  terms: 'M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z',
  privacy: 'M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm0 10.99h7c-.53 4.12-3.28 7.79-7 8.94V12H5V6.3l7-3.11v8.8z',
  about: 'M11 7h2v2h-2zm0 4h2v6h-2zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z',
  userAgent: 'M21 2H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h7v2H8v2h8v-2h-2v-2h7c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm0 14H3V4h18v12z',
};

async function buildIcons() {
  if (!ui || ui.webContents.isDestroyed()) return;
  try {
    const out = await ui.webContents.executeJavaScript(`(() => {
      const paths = ${JSON.stringify(ICON_PATHS)};
      const res = {};
      for (const k in paths) {
        const c = document.createElement('canvas');
        c.width = 32; c.height = 32;
        const x = c.getContext('2d');
        x.scale(32 / 24, 32 / 24);
        x.fillStyle = '#4A4A4A';
        x.fill(new Path2D(paths[k]));
        res[k] = c.toDataURL('image/png');
      }
      return res;
    })()`);
    icons = {};
    for (const k in out) icons[k] = nativeImage.createFromDataURL(out[k]).resize({ width: 16, height: 16 });
  } catch { icons = {}; }
}
const ic = (k) => (icons[k] ? { icon: icons[k] } : {});

const stateFile = () => path.join(app.getPath('userData'), 'yulemdo-state.json');
const T = () => L[lang] || L.uz;

// ── Yordamchilar ─────────────────────────────────────────────────────────────
function toUrl(input) {
  const s = String(input || '').trim();
  if (!s) return HOME;
  if (/^https?:\/\//i.test(s)) return s;
  if (!/\s/.test(s) && /^[\w-]+(\.[\w-]+)+(:\d+)?(\/.*)?$/.test(s)) return 'https://' + s;
  return HOME + '/search?q=' + encodeURIComponent(s);
}

function errorPage() {
  const t = T();
  return `<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<style>body{font-family:sans-serif;background:#FFF8F0;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0;padding:24px;box-sizing:border-box;text-align:center}
h1{color:#3A3A3A;font-size:20px;margin:0 0 8px}p{color:#7A7A7A;font-size:14px;line-height:20px;margin:0 0 24px}
a{background:#FF9800;color:#fff;text-decoration:none;padding:12px 24px;border-radius:14px;font-weight:600;font-size:14px}</style></head>
<body><h1>${t.errTitle}</h1><p>${t.errDesc}</p><a href="${HOME}">${t.errHome}</a></body></html>`;
}

function toast(msg) {
  if (ui && !ui.webContents.isDestroyed()) ui.webContents.send('toast', msg);
}

// Sayt bilan ko'prik: yulemdotheme:// , yulemdolang:// , yulemdoshare://
// URL sxemasi avtomatik kichik harfga o'tadi, shuning uchun saytdagi
// 'Yulemdotheme://' ham mos keladi.
function handleBridge(raw) {
  let u;
  try { u = new URL(raw); } catch { return false; }
  switch (u.protocol) {
    case 'yulemdotheme:':
      dark = ['1', 'true'].includes(u.searchParams.get('dark'));
      push();
      return true;
    case 'yulemdolang:': {
      const l = u.searchParams.get('lang');
      if (l === 'uz' || l === 'ru' || l === 'en') { lang = l; buildAppMenu(); push(); }
      return true;
    }
    case 'yulemdoshare:': {
      const link = u.searchParams.get('url') || '';
      if (link) { clipboard.writeText(link); toast(T().copied); }
      return true;
    }
    default:
      return false;
  }
}

// ── Joylashuv ────────────────────────────────────────────────────────────────
function layout() {
  if (!win || win.isDestroyed()) return;
  const [w, h] = win.getContentSize();
  const top = htmlFullscreen ? 0 : CHROME_H;
  ui.setVisible(!htmlFullscreen);
  ui.setBounds({ x: 0, y: 0, width: w, height: CHROME_H });
  for (const t of tabs) {
    const on = t.id === activeId;
    t.view.setVisible(on);
    if (on) t.view.setBounds({ x: 0, y: top, width: w, height: Math.max(0, h - top) });
  }
}

function push() {
  if (!ui || ui.webContents.isDestroyed()) return;
  const active = tabs.find((t) => t.id === activeId);
  const nh = active ? active.view.webContents.navigationHistory : null;
  ui.webContents.send('state', {
    tabs: tabs.map((t) => ({
      id: t.id,
      title: t.view.webContents.getTitle(),
      url: t.view.webContents.getURL(),
      favicon: t.favicon,
      loading: t.view.webContents.isLoading(),
    })),
    activeId,
    canGoBack: !!nh && nh.canGoBack(),
    canGoForward: !!nh && nh.canGoForward(),
    dark,
    t: T(),
  });
  scheduleSave();
}

function scheduleSave() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(saveState, 500);
}

function saveState() {
  try {
    const urls = tabs.map((t) => t.view.webContents.getURL()).filter((u) => /^https?:/i.test(u));
    const idx = Math.max(0, tabs.findIndex((t) => t.id === activeId));
    fs.writeFileSync(stateFile(), JSON.stringify({ urls, idx, dark, lang, uaKey }));
  } catch { /* ignore */ }
}

function loadState() {
  try {
    const s = JSON.parse(fs.readFileSync(stateFile(), 'utf8'));
    if (typeof s.dark === 'boolean') dark = s.dark;
    if (['uz', 'ru', 'en'].includes(s.lang)) lang = s.lang;
    if (UA_LIST.some((u) => u.key === s.uaKey)) uaKey = s.uaKey;
    return s;
  } catch { return null; }
}

// ── Tablar ───────────────────────────────────────────────────────────────────
function createTab(url = HOME, activate = true) {
  const view = new WebContentsView({
    webPreferences: {
      partition: PARTITION, sandbox: true, contextIsolation: true, nodeIntegration: false,
    },
  });
  const tab = { id: nextId++, view, favicon: null };
  tabs.push(tab);
  win.contentView.addChildView(view);

  const wc = view.webContents;
  wc.setUserAgent(currentUA(wc));

  wc.on('page-title-updated', push);
  wc.on('page-favicon-updated', (_e, favs) => { tab.favicon = favs && favs[0] ? favs[0] : null; push(); });
  wc.on('did-navigate', push);
  wc.on('did-navigate-in-page', push);
  wc.on('did-start-loading', push);
  wc.on('did-stop-loading', push);

  wc.on('will-navigate', (e, navUrl) => {
    if (handleBridge(navUrl)) { e.preventDefault(); return; }
    if (/^(mailto|tel):/i.test(navUrl)) { e.preventDefault(); shell.openExternal(navUrl); return; }
    if (!/^(https?|data|about):/i.test(navUrl)) e.preventDefault();
  });

  wc.setWindowOpenHandler(({ url: openUrl }) => {
    if (handleBridge(openUrl)) return { action: 'deny' };
    if (/^https?:/i.test(openUrl)) createTab(openUrl, true);
    return { action: 'deny' };
  });

  wc.on('did-fail-load', (_e, code, _desc, failedUrl, isMainFrame) => {
    if (!isMainFrame || code === -3 || String(failedUrl).startsWith('data:')) return;
    wc.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(errorPage())).catch(() => {});
  });

  wc.on('enter-html-full-screen', () => { htmlFullscreen = true; win.setFullScreen(true); layout(); });
  wc.on('leave-html-full-screen', () => { htmlFullscreen = false; win.setFullScreen(false); layout(); });

  wc.loadURL(url, { userAgent: currentUA() }).catch(() => {});
  if (activate) activateTab(tab.id);
  else { layout(); push(); }
  return tab;
}

function activateTab(id) {
  const tab = tabs.find((t) => t.id === id);
  if (!tab) return;
  activeId = id;
  layout();
  push();
  tab.view.webContents.focus();
}

function closeTab(id) {
  const i = tabs.findIndex((t) => t.id === id);
  if (i < 0) return;
  const [tab] = tabs.splice(i, 1);
  win.contentView.removeChildView(tab.view);
  tab.view.webContents.close();
  if (!tabs.length) { createTab(HOME, true); return; }
  if (activeId === id) activeId = tabs[Math.min(i, tabs.length - 1)].id;
  layout();
  push();
  const a = tabs.find((t) => t.id === activeId);
  if (a) a.view.webContents.focus();
}

function closeAllTabs() {
  for (const t of tabs.splice(0)) {
    win.contentView.removeChildView(t.view);
    t.view.webContents.close();
  }
  createTab(HOME, true);
}

const activeTab = () => tabs.find((t) => t.id === activeId);

function cycleTab(step) {
  if (tabs.length < 2) return;
  const i = tabs.findIndex((t) => t.id === activeId);
  activateTab(tabs[(i + step + tabs.length) % tabs.length].id);
}

// ── Menyular ─────────────────────────────────────────────────────────────────
function buildAppMenu() {
  const t = T();
  Menu.setApplicationMenu(Menu.buildFromTemplate([{
    label: 'Yulemdo',
    submenu: [
      { label: t.newTab, accelerator: 'CmdOrCtrl+T', click: () => createTab(HOME, true) },
      { label: t.close, accelerator: 'CmdOrCtrl+W', click: () => activeId && closeTab(activeId) },
      { label: t.reload, accelerator: 'CmdOrCtrl+R', click: () => activeTab()?.view.webContents.reload() },
      { label: t.reload, accelerator: 'F5', click: () => activeTab()?.view.webContents.reload() },
      { label: 'Address', accelerator: 'CmdOrCtrl+L', click: () => ui.webContents.send('focus-address') },
      { label: t.back, accelerator: 'Alt+Left', click: () => activeTab()?.view.webContents.navigationHistory.goBack() },
      { label: t.forward, accelerator: 'Alt+Right', click: () => activeTab()?.view.webContents.navigationHistory.goForward() },
      { label: 'Next', accelerator: 'Ctrl+Tab', click: () => cycleTab(1) },
      { label: 'Previous', accelerator: 'Ctrl+Shift+Tab', click: () => cycleTab(-1) },
    ],
  }]));
}

function popupMenu(x, y) {
  const t = T();
  const open = (p) => createTab(HOME + p, true);
  const cur = activeTab()?.view.webContents.getURL() || '';
  Menu.buildFromTemplate([
    { label: t.reload, ...ic('reload'), click: () => activeTab()?.view.webContents.reload() },
    ...(cur.startsWith('http') ? [{ label: t.copyLink, ...ic('copyLink'), click: () => { clipboard.writeText(cur); toast(t.copied); } }] : []),
    { label: t.newTab, ...ic('newTab'), click: () => createTab(HOME, true) },
    { label: t.home, ...ic('home'), click: () => activeTab()?.view.webContents.loadURL(HOME).catch(() => {}) },
    { label: t.downloads, ...ic('downloads'), click: () => shell.openPath(app.getPath('downloads')) },
    { label: t.closeAll, ...ic('closeAll'), click: closeAllTabs },
    {
      label: t.userAgent,
      ...ic('userAgent'),
      submenu: UA_LIST.map((u) => ({
        label: u.label || t.uaDefault,
        type: 'radio',
        checked: u.key === uaKey,
        click: () => applyUA(u.key),
      })),
    },
    { type: 'separator' },
    { label: t.terms, ...ic('terms'), click: () => open('/terms') },
    { label: t.privacy, ...ic('privacy'), click: () => open('/privacy') },
    { label: t.about, ...ic('about'), click: () => open('/about') },
  ]).popup({ window: win, x: Math.round(x), y: Math.round(y) });
}

// ── IPC (faqat o'z interfeysimizdan) ─────────────────────────────────────────
const fromUi = (e) => ui && e.sender === ui.webContents;

ipcMain.on('tab:new', (e) => { if (fromUi(e)) createTab(HOME, true); });
ipcMain.on('tab:activate', (e, id) => { if (fromUi(e) && Number.isInteger(id)) activateTab(id); });
ipcMain.on('tab:close', (e, id) => { if (fromUi(e) && Number.isInteger(id)) closeTab(id); });
ipcMain.on('go', (e, text) => {
  if (!fromUi(e) || typeof text !== 'string') return;
  const a = activeTab();
  if (a) { a.view.webContents.loadURL(toUrl(text)).catch(() => {}); a.view.webContents.focus(); }
});
ipcMain.on('nav', (e, action) => {
  if (!fromUi(e)) return;
  const a = activeTab();
  if (!a) return;
  const wc = a.view.webContents;
  if (action === 'back') wc.navigationHistory.goBack();
  else if (action === 'forward') wc.navigationHistory.goForward();
  else if (action === 'reload') wc.reload();
  else if (action === 'home') wc.loadURL(HOME).catch(() => {});
});
ipcMain.on('menu', (e, pos) => {
  if (fromUi(e) && pos && Number.isFinite(pos.x) && Number.isFinite(pos.y)) popupMenu(pos.x, pos.y);
});

// ── Oyna ─────────────────────────────────────────────────────────────────────
function setupSession() {
  const ses = session.fromPartition(PARTITION);
  ses.setUserAgent(currentUA());
  // Har bir so'rovda User-Agent sarlavhasi tanlangan agentga teng bo'lsin
  ses.webRequest.onBeforeSendHeaders((details, cb) => {
    const h = details.requestHeaders;
    for (const k of Object.keys(h)) if (k.toLowerCase() === 'user-agent') delete h[k];
    h['User-Agent'] = currentUA();
    cb({ requestHeaders: h });
  });
  const allowed = ['fullscreen', 'clipboard-sanitized-write', 'notifications', 'pointerLock'];
  ses.setPermissionRequestHandler((_wc, perm, cb) => cb(allowed.includes(perm)));
  ses.on('will-download', (_e, item) => {
    item.once('done', (_ev, state) => { if (state === 'completed') toast(`${T().downloaded}: ${item.getFilename()}`); });
  });
}

function createWindow() {
  const saved = loadState();
  if (!saved) {
    const loc = app.getLocale();
    lang = loc.startsWith('ru') ? 'ru' : loc.startsWith('en') ? 'en' : 'uz';
  }

  win = new BrowserWindow({
    width: 1280, height: 820, minWidth: 640, minHeight: 480, backgroundColor: '#ffffff', title: 'Yulemdo',
    icon: path.join(__dirname, 'build', 'icon.png'),
  });
  win.setMenuBarVisibility(false);

  ui = new WebContentsView({
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'), sandbox: true, contextIsolation: true, nodeIntegration: false,
    },
  });
  win.contentView.addChildView(ui);
  ui.webContents.on('will-navigate', (e) => e.preventDefault());
  ui.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  ui.webContents.loadFile(path.join(__dirname, 'ui', 'index.html'));
  ui.webContents.once('did-finish-load', () => {
    buildIcons();
    const urls = saved && Array.isArray(saved.urls) && saved.urls.length ? saved.urls : [HOME];
    urls.forEach((u, i) => createTab(u, false));
    const idx = saved && Number.isInteger(saved.idx) ? Math.min(saved.idx, tabs.length - 1) : 0;
    activateTab(tabs[Math.max(0, idx)].id);
  });

  win.on('resize', layout);
  win.on('enter-full-screen', layout);
  win.on('leave-full-screen', layout);
  win.on('closed', () => { win = null; ui = null; tabs = []; });
  buildAppMenu();
}

app.whenReady().then(() => {
  setupSession();
  createWindow();
});

app.on('second-instance', (_e, argv) => {
  if (!win) return;
  if (win.isMinimized()) win.restore();
  win.focus();
  const link = argv.find((a) => /^https?:\/\//i.test(a));
  if (link) createTab(link, true);
});

app.on('before-quit', saveState);
app.on('window-all-closed', () => app.quit());
