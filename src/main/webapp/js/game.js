'use strict';
// JavaScriptは表示だけを担当する。期限の最終判定はServlet側で行う。
document.querySelectorAll('form').forEach(form => {
  form.addEventListener('submit', event => {
    if (form.dataset.confirm && !window.confirm(form.dataset.confirm)) { event.preventDefault(); return; }
    form.classList.add('busy');
  });
});
window.addEventListener('pageshow', () => document.querySelectorAll('.busy').forEach(el => el.classList.remove('busy')));
const area = document.getElementById('playArea');
if (area) {
  const answer = document.getElementById('answerForm');
  const timeout = document.getElementById('timeoutMessage');
  const warning = document.getElementById('timeWarning');
  const key = 'shiritori-warning:' + area.dataset.endpoint + ':' + area.dataset.turn;
  let warned = false;
  try { warned = sessionStorage.getItem(key) === 'shown'; } catch (_) { /* 保存不可でも動作する。 */ }
  let deadline = performance.now() + Number(area.dataset.remaining);
  function tick() {
    const remaining = Math.max(0, deadline - performance.now());
    if (remaining <= 10000 && remaining > 0 && !warned) {
      warned = true;
      warning.textContent = '残り' + Math.ceil(remaining / 1000) + '秒である。詰まったらパスできる。';
      warning.hidden = false;
      try { sessionStorage.setItem(key, 'shown'); } catch (_) { }
    }
    if (remaining === 0) { answer.classList.add('hidden'); timeout.classList.remove('hidden'); warning.hidden = true; }
  }
  async function sync() {
    try {
      const response = await fetch(area.dataset.endpoint, {cache:'no-store', credentials:'same-origin'});
      if (!response.ok) return;
      const state = await response.json();
      if (state.turn !== Number(area.dataset.turn) || state.finished) {
        answer.classList.add('hidden'); timeout.textContent = '別の画面で対戦が進んでいる。ページを更新する。'; timeout.classList.remove('hidden'); return;
      }
      deadline = performance.now() + state.remainingMs;
      tick();
    } catch (_) { /* 通信失敗中も直近の期限で表示を続ける。 */ }
  }
  tick(); setInterval(tick, 250); setInterval(sync, 5000);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) sync(); });
}
