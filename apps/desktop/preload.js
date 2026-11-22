'use strict';
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('yulemdo', {
  newTab: () => ipcRenderer.send('tab:new'),
  activate: (id) => ipcRenderer.send('tab:activate', id),
  close: (id) => ipcRenderer.send('tab:close', id),
  nav: (action) => ipcRenderer.send('nav', action),
  go: (text) => ipcRenderer.send('go', text),
  menu: (x, y) => ipcRenderer.send('menu', { x, y }),
  onState: (fn) => ipcRenderer.on('state', (_e, s) => fn(s)),
  onFocusAddress: (fn) => ipcRenderer.on('focus-address', () => fn()),
  onToast: (fn) => ipcRenderer.on('toast', (_e, m) => fn(m)),
});
