import React from 'react';
import ReactDOM from 'react-dom/client';
import '@patternfly/react-core/dist/styles/base.css';

/* ── グローバルエラー表示（どのモジュール初期化エラーも画面に出す） ── */
function showFatalError(msg: string) {
  const root = document.getElementById('root');
  if (root) {
    root.innerHTML = `
      <div style="padding:40px;font-family:monospace">
        <h2 style="color:#c9190b;margin-bottom:16px">アプリ起動エラー</h2>
        <pre style="background:#fff1f1;border:1px solid #c9190b;padding:16px;border-radius:6px;white-space:pre-wrap;word-break:break-word;font-size:13px">${msg}</pre>
        <button onclick="location.reload()" style="margin-top:16px;padding:8px 20px;background:#c9190b;color:#fff;border:none;border-radius:4px;cursor:pointer;font-size:14px">再読み込み</button>
      </div>`;
  }
}

window.addEventListener('error', e => {
  console.error('[global error]', e.error);
  showFatalError(`${e.message}\n\n${e.error?.stack ?? ''}`);
});

window.addEventListener('unhandledrejection', e => {
  console.error('[unhandled promise]', e.reason);
  showFatalError(String(e.reason?.stack ?? e.reason ?? 'Unhandled promise rejection'));
});

async function bootstrap() {
  try {
    await import('./i18n');
  } catch (e: any) {
    showFatalError(`i18n 初期化エラー:\n${e?.stack ?? e}`);
    return;
  }

  let App: React.ComponentType;
  try {
    const mod = await import('./App');
    App = mod.default;
  } catch (e: any) {
    showFatalError(`App モジュール読み込みエラー:\n${e?.stack ?? e}`);
    return;
  }

  try {
    const rootEl = document.getElementById('root') as HTMLElement;
    ReactDOM.createRoot(rootEl).render(
      <React.StrictMode>
        <App />
      </React.StrictMode>
    );
  } catch (e: any) {
    showFatalError(`React レンダリングエラー:\n${e?.stack ?? e}`);
  }
}

bootstrap();
