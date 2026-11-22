function connect() {
  var port;
  try {
    port = browser.runtime.connectNative("yulemdo");
  } catch (e) {
    setTimeout(connect, 600);
    return;
  }
  port.onDisconnect.addListener(function () {
    setTimeout(connect, 600);
  });
  port.onMessage.addListener(function (msg) {
    if (!msg || (msg.theme !== "dark" && msg.theme !== "light")) return;
    browser.tabs.query({}).then(function (tabs) {
      tabs.forEach(function (tab) {
        browser.tabs.sendMessage(tab.id, msg).catch(function () {});
      });
    }).catch(function () {});
  });
}
connect();
