browser.runtime.onMessage.addListener(function (msg) {
  if (!msg || (msg.theme !== "dark" && msg.theme !== "light")) return;
  var dark = msg.theme === "dark";
  try { localStorage.setItem("Yulemdo-theme", msg.theme); } catch (e) {}
  document.documentElement.setAttribute("data-theme", msg.theme);
  var cb = document.getElementById("themeToggleInput");
  if (cb) cb.checked = dark;
  var sun = document.getElementById("themeIconSun");
  var moon = document.getElementById("themeIconMoon");
  if (sun) sun.classList.toggle("hidden", dark);
  if (moon) moon.classList.toggle("hidden", !dark);
  var lab = document.getElementById("themeLabel");
  if (lab) {
    var dict = {
      uz: ["Kun rejimi", "Tun rejimi"],
      ru: ["Дневной режим", "Ночной режим"],
      en: ["Day mode", "Night mode"]
    };
    var lang = "uz";
    try { lang = localStorage.getItem("Yulemdo-lang") || "uz"; } catch (e) {}
    var pair = dict[lang] || dict.uz;
    lab.textContent = dark ? pair[1] : pair[0];
  }
});
