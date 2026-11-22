'use strict';
const $ = (id) => document.getElementById(id);
const api = window.yulemdo;

const P = {
  back: 'M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z',
  fwd: 'M12 4l-1.41 1.41L16.17 11H4v2h12.17l-5.58 5.59L12 20l8-8z',
  reload: 'M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z',
  home: 'M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z',
  menu: 'M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z',
  close: 'M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
  add: 'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z',
  globe: 'M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zm6.93 6h-2.95a15.65 15.65 0 0 0-1.38-3.56A8.03 8.03 0 0 1 18.92 8zM12 4.04c.83 1.2 1.48 2.53 1.91 3.96h-3.82c.43-1.43 1.08-2.76 1.91-3.96zM4.26 14C4.1 13.36 4 12.69 4 12s.1-1.36.26-2h3.38c-.08.66-.14 1.32-.14 2s.06 1.34.14 2H4.26zm.82 2h2.95c.32 1.25.78 2.45 1.38 3.56A7.987 7.987 0 0 1 5.08 16zm2.95-8H5.08a7.987 7.987 0 0 1 4.33-3.56A15.65 15.65 0 0 0 8.03 8zM12 19.96c-.83-1.2-1.48-2.53-1.91-3.96h3.82c-.43 1.43-1.08 2.76-1.91 3.96zM14.34 14H9.66c-.09-.66-.16-1.32-.16-2s.07-1.35.16-2h4.68c.09.65.16 1.32.16 2s-.07 1.34-.16 2zm.25 5.56c.6-1.11 1.06-2.31 1.38-3.56h2.95a8.03 8.03 0 0 1-4.33 3.56zM16.36 14c.08-.66.14-1.32.14-2s-.06-1.34-.14-2h3.38c.16.64.26 1.31.26 2s-.1 1.36-.26 2h-3.38z',
};
const svg = (d, s = 20) =>
  `<svg viewBox="0 0 24 24" width="${s}" height="${s}" aria-hidden="true"><path fill="currentColor" d="${d}"/></svg>`;

$('back').innerHTML = svg(P.back);
$('fwd').innerHTML = svg(P.fwd);
$('reload').innerHTML = svg(P.reload);
$('home').innerHTML = svg(P.home);
$('menu').innerHTML = svg(P.menu);
$('newtab').innerHTML = svg(P.add);

$('back').addEventListener('click', () => api.nav('back'));
$('fwd').addEventListener('click', () => api.nav('forward'));
$('reload').addEventListener('click', () => api.nav('reload'));
$('home').addEventListener('click', () => api.nav('home'));
$('newtab').addEventListener('click', () => api.newTab());
$('menu').addEventListener('click', () => {
  const r = $('menu').getBoundingClientRect();
  api.menu(r.right - 4, r.bottom);
});

const addr = $('addr');
addr.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { api.go(addr.value); addr.blur(); }
  else if (e.key === 'Escape') { addr.blur(); render(); }
});
addr.addEventListener('focus', () => addr.select());
addr.addEventListener('blur', () => render());

let st = null;
const isHome = (u) => { try { const h = new URL(u).hostname.replace(/^www\./, ''); return h === 'yulemdo.uz'; } catch { return false; } };
const shown = (u) => (!u || u.startsWith('data:') || isHome(u) && new URL(u).pathname === '/' ? '' : u);

function render() {
  if (!st) return;
  const t = st.t;
  document.documentElement.dataset.theme = st.dark ? 'dark' : 'light';

  $('back').title = t.back; $('back').setAttribute('aria-label', t.back);
  $('fwd').title = t.forward; $('fwd').setAttribute('aria-label', t.forward);
  $('reload').title = t.reload; $('reload').setAttribute('aria-label', t.reload);
  $('home').title = t.home; $('home').setAttribute('aria-label', t.home);
  $('menu').title = t.menu; $('menu').setAttribute('aria-label', t.menu);
  $('newtab').title = t.newTab; $('newtab').setAttribute('aria-label', t.newTab);
  addr.placeholder = t.address;
  $('back').disabled = !st.canGoBack;
  $('fwd').disabled = !st.canGoForward;

  const list = $('tablist');
  list.textContent = '';
  for (const tab of st.tabs) {
    const el = document.createElement('div');
    el.className = 'tab' + (tab.id === st.activeId ? ' active' : '');
    el.title = tab.title || tab.url;

    const fav = document.createElement('span');
    fav.className = 'fav' + (tab.loading ? ' loading' : '');
    const globe = () => { fav.classList.add('globe'); fav.innerHTML = svg(P.globe, 16); };
    const homeTab = isHome(tab.url) && new URL(tab.url).pathname === '/';
    if (homeTab) {
      fav.classList.remove('loading');
      globe();
    } else if (tab.favicon && !tab.loading) {
      const im = document.createElement('img');
      im.src = tab.favicon; im.alt = '';
      im.addEventListener('error', () => { im.remove(); globe(); });
      fav.append(im);
    } else if (!tab.loading) {
      globe();
    }

    const title = document.createElement('span');
    title.className = 'title';
    title.textContent = homeTab || !tab.title ? t.newTab : tab.title;

    const x = document.createElement('button');
    x.className = 'icon x'; x.type = 'button'; x.title = t.close;
    x.setAttribute('aria-label', t.close);
    x.innerHTML = svg(P.close, 14);
    x.addEventListener('click', (e) => { e.stopPropagation(); api.close(tab.id); });

    el.addEventListener('click', () => api.activate(tab.id));
    el.addEventListener('auxclick', (e) => { if (e.button === 1) api.close(tab.id); });
    el.append(fav, title, x);
    list.append(el);
  }

  if (document.activeElement !== addr) {
    const a = st.tabs.find((x) => x.id === st.activeId);
    addr.value = a ? shown(a.url) : '';
  }
}

api.onState((s) => { st = s; render(); });
api.onFocusAddress(() => { addr.focus(); addr.select(); });

let toastTimer = null;
api.onToast((m) => {
  const el = $('toast');
  el.textContent = m;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), 2200);
});
