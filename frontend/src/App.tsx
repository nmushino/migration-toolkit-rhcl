import React, { useState, Component, ErrorInfo, ReactNode } from 'react';
import { BrowserRouter, Routes, Route, useNavigate, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import i18n from './i18n';
import {
  Page,
  PageSidebar,
  PageSidebarBody,
  Nav,
  NavItem,
  NavExpandable,
  Masthead,
  MastheadToggle,
  MastheadMain,
  MastheadBrand,
  MastheadContent,
  PageToggleButton,
  Toolbar,
  ToolbarContent,
  ToolbarItem,
  TextContent,
  Text,
  TextVariants,
} from '@patternfly/react-core';
import { BarsIcon } from '@patternfly/react-icons';

import ConnectionPage from './pages/ConnectionPage';
import APISelectionPage from './pages/APISelectionPage';
import CompatibilityPage from './pages/CompatibilityPage';
import ConversionPage from './pages/ConversionPage';
import YAMLViewerPage from './pages/YAMLViewerPage';
import ValidationPage from './pages/ValidationPage';
import DownloadPage from './pages/DownloadPage';
import HistoryPage from './pages/HistoryPage';
import ImportPage from './pages/ImportPage';

import { ConnectionRequest, ApiService, ConversionResultItem } from './api/types';

/* ── ルートレベル Error Boundary ──
   いずれかのページコンポーネントがクラッシュしても
   ナビゲーション（サイドバー・マストヘッド）は消えない。 */
interface EBState { hasError: boolean; message: string; path: string; }
class RouteErrorBoundary extends Component<{ children: ReactNode }, EBState> {
  state: EBState = { hasError: false, message: '', path: '' };
  static getDerivedStateFromError(e: Error): Partial<EBState> {
    return { hasError: true, message: e.message };
  }
  componentDidCatch(e: Error, info: ErrorInfo) {
    console.error('[RouteErrorBoundary]', e, info);
    this.setState({ path: window.location.pathname });
  }
  componentDidUpdate(_: unknown, prev: EBState) {
    if (prev.path && window.location.pathname !== prev.path) {
      this.setState({ hasError: false, message: '', path: '' });
    }
  }
  render() {
    if (this.state.hasError) {
      return (
        <div style={{ padding: '32px' }}>
          <div style={{
            background: '#fff1f1', border: '1px solid #c9190b', borderRadius: '6px',
            padding: '20px 24px',
          }}>
            <p style={{ margin: 0, fontWeight: 700, color: '#c9190b', fontSize: '15px' }}>
              ページの表示中にエラーが発生しました
            </p>
            <p style={{ margin: '8px 0 0', fontFamily: 'monospace', fontSize: '13px', color: '#3c3f42', wordBreak: 'break-word' }}>
              {this.state.message}
            </p>
            <button
              onClick={() => this.setState({ hasError: false, message: '', path: '' })}
              style={{
                marginTop: '14px', padding: '6px 16px', fontSize: '13px', cursor: 'pointer',
                background: '#c9190b', color: '#fff', border: 'none', borderRadius: '4px',
              }}
            >
              再試行
            </button>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}

export interface AppState {
  connection: ConnectionRequest & { connected: boolean };
  selectedServices: ApiService[];
  conversionResults: ConversionResultItem[];
  namespace: string;
}

/* ── 言語切替タブ ── */
const LangSwitcher: React.FC = () => {
  const { i18n: i18nInst } = useTranslation();
  const current = i18nInst.language;

  const btn = (lang: string, label: string) => (
    <button
      key={lang}
      onClick={() => i18n.changeLanguage(lang)}
      style={{
        padding: '4px 12px',
        fontSize: '13px',
        fontWeight: current === lang ? 700 : 400,
        color: current === lang ? '#ffffff' : '#b8bbbe',
        background: current === lang ? '#ee0000' : 'transparent',
        border: `1px solid ${current === lang ? '#ee0000' : '#6a6e73'}`,
        borderRadius: lang === 'ja' ? '4px 0 0 4px' : '0 4px 4px 0',
        cursor: 'pointer',
        transition: 'all 0.15s',
        lineHeight: '1.4',
      }}
    >
      {label}
    </button>
  );

  return (
    <div style={{ display: 'flex' }}>
      {btn('ja', 'JA')}
      {btn('en', 'EN')}
    </div>
  );
};

/* ── Red Hat フェドーラハット SVG（共通：白・透過背景） ──
   公式ブランド「赤背景用ロゴ（Logo A reverse）」の白ハット部分のみを抽出し、
   透過背景で使用する。サイドバー・マストヘッド共用。 */
const RHHatIcon: React.FC<{ size?: number }> = ({ size = 32 }) => (
  <svg
    xmlns="http://www.w3.org/2000/svg"
    viewBox="0 0 100 80"
    width={size}
    height={size * 0.8}
    aria-hidden="true"
    style={{ flexShrink: 0 }}
  >
    {/* クラウン（左右の側面） */}
    <path d="M28 48 C26 30 34 10 50 7 C66 10 74 30 72 48 Z" fill="#ffffff" />
    {/* クラウン頂部ドーム */}
    <ellipse cx="50" cy="7" rx="13" ry="7" fill="#ffffff" />
    {/* ハットバンド（赤：ブランドカラー #EE0000） */}
    <rect x="27" y="46" width="46" height="7" rx="1" fill="#ee0000" />
    {/* ブリム上面 */}
    <ellipse cx="50" cy="59" rx="44" ry="10.5" fill="#ffffff" />
    {/* ブリム下面の影（奥行き表現） */}
    <path d="M6 59 Q50 73 94 59 Q50 67 6 59Z" fill="rgba(0,0,0,0.18)" />
  </svg>
);

/* ── Masthead 用 Red Hat ロゴ ── */
const RedHatLogo: React.FC = () => (
  <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
    <RHHatIcon size={30} />
    <span style={{
      fontFamily: "'Red Hat Display','Liberation Sans','Arial Black',sans-serif",
      fontSize: '15px', fontWeight: 800, color: '#ffffff', letterSpacing: '-0.2px',
    }}>
      Red Hat
    </span>
  </div>
);

/* ── サイドバー用 Red Hat ロゴ ──
   公式ブランド「赤背景用ロゴ (Logo A reverse)」の白ハット + ワードマークを
   透過背景で使用。背景はメニューと同じ濃いグレー (#212427) に統一。 */
const SidebarRedHatBrand: React.FC = () => (
  <div style={{
    background: '#212427',
    padding: '20px 20px 16px',
    borderBottom: '1px solid #3c3f42',
  }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '10px' }}>
      <RHHatIcon size={44} />
      <span style={{
        fontFamily: "'Red Hat Display','Liberation Sans','Arial Black',sans-serif",
        fontWeight: 800,
        fontSize: '22px',
        color: '#ffffff',
        letterSpacing: '-0.3px',
      }}>
        Red Hat
      </span>
    </div>
    <div style={{ borderTop: '1px solid #3c3f42', paddingTop: '10px' }}>
      <p style={{
        margin: 0, fontSize: '10.5px', fontWeight: 500,
        color: '#b8bbbe', lineHeight: 1.45, letterSpacing: '0.01em',
      }}>
        Connectivity Link<br />Migration Toolkit
      </p>
    </div>
  </div>
);

/* ── フッター ── */
const Footer: React.FC = () => (
  <div style={{
    borderTop: '1px solid #3c3f42',
    padding: '12px 24px',
    background: '#212427',
    color: '#8a8d90',
    fontSize: '12px',
    display: 'flex',
    justifyContent: 'flex-end',
    alignItems: 'center',
    gap: '16px',
  }}>
    <span>Contact:</span>
    <a href="mailto:nmushino@redhat.com" style={{ color: '#73bcf7', textDecoration: 'none' }}>
      Noriaki Mushino | nmushino@redhat.com
    </a>
  </div>
);

const AppContent: React.FC = () => {
  const { t } = useTranslation();
  const [isSidebarOpen, setIsSidebarOpen] = useState(true);
  const [workflowExpanded, setWorkflowExpanded] = useState(true);
  const [toolsExpanded, setToolsExpanded] = useState(true);
  const navigate = useNavigate();
  const location = useLocation();

  const [appState, setAppState] = useState<AppState>({
    connection: { url: '', accessToken: '', tenant: '', connected: false },
    selectedServices: [],
    conversionResults: [],
    namespace: 'default',
  });

  const workflowItems = [
    { path: '/', label: t('nav.connection') },
    { path: '/services', label: t('nav.apiList') },
    { path: '/compatibility', label: t('nav.compatibility') },
    { path: '/convert', label: t('nav.convert') },
    { path: '/yaml', label: t('nav.yamlPreview') },
    { path: '/validate', label: t('nav.validation') },
    { path: '/download', label: t('nav.download') },
  ];

  const toolItems = [
    { path: '/import', label: t('nav.import') },
    { path: '/history', label: t('nav.history') },
  ];

  const isWorkflowActive = workflowItems.some(i => i.path === location.pathname);
  const isToolsActive = toolItems.some(i => i.path === location.pathname);

  const sidebar = (
    <PageSidebar isSidebarOpen={isSidebarOpen}>
      <SidebarRedHatBrand />
      <PageSidebarBody>
        <Nav theme="dark">
          <NavExpandable
            title={t('nav.workflow')}
            isExpanded={workflowExpanded}
            onExpand={(_e, val) => setWorkflowExpanded(val)}
            isActive={isWorkflowActive}
          >
            {workflowItems.map(item => (
              <NavItem
                key={item.path}
                isActive={location.pathname === item.path}
                onClick={() => navigate(item.path)}
              >
                {item.label}
              </NavItem>
            ))}
          </NavExpandable>

          <NavExpandable
            title={t('nav.tools')}
            isExpanded={toolsExpanded}
            onExpand={(_e, val) => setToolsExpanded(val)}
            isActive={isToolsActive}
          >
            {toolItems.map(item => (
              <NavItem
                key={item.path}
                isActive={location.pathname === item.path}
                onClick={() => navigate(item.path)}
              >
                {item.label}
              </NavItem>
            ))}
          </NavExpandable>
        </Nav>
      </PageSidebarBody>
    </PageSidebar>
  );

  const masthead = (
    <Masthead>
      <MastheadToggle>
        <PageToggleButton
          variant="plain"
          aria-label="Global navigation"
          isSidebarOpen={isSidebarOpen}
          onSidebarToggle={() => setIsSidebarOpen(!isSidebarOpen)}
          id="nav-toggle"
        >
          <BarsIcon />
        </PageToggleButton>
      </MastheadToggle>
      <MastheadMain>
        <MastheadBrand>
          <RedHatLogo />
        </MastheadBrand>
      </MastheadMain>
      <MastheadContent>
        <Toolbar>
          <ToolbarContent>
            <ToolbarItem>
              <TextContent>
                <Text component={TextVariants.p} style={{ color: '#ffffff', fontSize: '14px', fontWeight: 500, marginLeft: '16px' }}>
                  {t('nav.appTitle')}
                </Text>
              </TextContent>
            </ToolbarItem>
            <ToolbarItem align={{ default: 'alignRight' }}>
              {appState.connection.connected && (
                <TextContent>
                  <Text component={TextVariants.small} style={{ color: '#8a8d90', marginRight: '16px' }}>
                    {t('nav.connected', { url: appState.connection.url })}
                  </Text>
                </TextContent>
              )}
            </ToolbarItem>
            <ToolbarItem>
              <LangSwitcher />
            </ToolbarItem>
          </ToolbarContent>
        </Toolbar>
      </MastheadContent>
    </Masthead>
  );

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh' }}>
      <Page
        masthead={masthead}
        sidebar={isSidebarOpen ? sidebar : undefined}
        isManagedSidebar={false}
        style={{ flex: 1, minHeight: 0 }}
      >
        <RouteErrorBoundary>
          <Routes>
            <Route path="/" element={<ConnectionPage appState={appState} setAppState={setAppState} />} />
            <Route path="/services" element={<APISelectionPage appState={appState} setAppState={setAppState} />} />
            <Route path="/compatibility" element={<CompatibilityPage appState={appState} setAppState={setAppState} />} />
            <Route path="/convert" element={<ConversionPage appState={appState} setAppState={setAppState} />} />
            <Route path="/yaml" element={<YAMLViewerPage appState={appState} setAppState={setAppState} />} />
            <Route path="/validate" element={<ValidationPage appState={appState} setAppState={setAppState} />} />
            <Route path="/download" element={<DownloadPage appState={appState} setAppState={setAppState} />} />
            <Route path="/import" element={<ImportPage />} />
            <Route path="/history" element={<HistoryPage />} />
          </Routes>
        </RouteErrorBoundary>
      </Page>
      <Footer />
    </div>
  );
};

const App: React.FC = () => (
  <BrowserRouter>
    <AppContent />
  </BrowserRouter>
);

export default App;
