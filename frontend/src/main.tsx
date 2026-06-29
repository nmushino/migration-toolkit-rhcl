import React from 'react';
import ReactDOM from 'react-dom/client';
import '@patternfly/react-core/dist/styles/base.css';
import './i18n';
import App from './App';

const rootEl = document.getElementById('root');

if (!rootEl) {
  document.body.innerHTML =
    '<div style="padding:40px;font-family:monospace;color:#c9190b">' +
    '<h2>起動エラー: #root 要素が見つかりません</h2>' +
    '<button onclick="location.reload()" style="margin-top:16px;padding:8px 20px;cursor:pointer">再読み込み</button>' +
    '</div>';
} else {
  ReactDOM.createRoot(rootEl).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>
  );
}
