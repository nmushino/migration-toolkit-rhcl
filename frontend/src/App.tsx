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
import {
  BarsIcon,
  PluggedIcon,
  ListIcon,
  CheckCircleIcon,
  CodeIcon,
  EyeIcon,
  SecurityIcon,
  DownloadIcon,
  UploadIcon,
  HistoryIcon,
} from '@patternfly/react-icons';

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

/* ── Red Hat 公式ハットアイコン SVG（共通コンポーネント） ──
   パスデータ出典: https://upload.wikimedia.org/wikipedia/commons/d/d8/Red_Hat_logo.svg
   dark 背景用に色を反転（white）して使用する。 */
const RHHatIcon: React.FC<{ size?: number }> = ({ size = 32 }) => (
  <svg
    xmlns="http://www.w3.org/2000/svg"
    viewBox="0 0 192.3 146"
    width={size}
    height={Math.round(size * 146 / 192.3)}
    aria-hidden="true"
    style={{ flexShrink: 0 }}
  >
    {/* 上部（ハット本体）— 元は #ee0000、dark 背景で白に反転 */}
    <path fill="#ffffff" d="m128,84c12.5,0 30.6,-2.6 30.6,-17.5a19.53,19.53 0 0 0-0.3,-3.4L150.9,30.7C149.2,23.6 147.7,20.3 135.2,14.1 125.5,9.1 104.4,1 98.1,1 92.2,1 90.5,8.5 83.6,8.5 76.9,8.5 72,2.9 65.7,2.9c-6,0-9.9,4.1-12.9,12.5 0,0-8.4,23.7-9.5,27.2a6.15,6.15 0 0 0-0.2,1.9C43,53.7 79.3,83.9 128,84m32.5,-11.4c1.7,8.2 1.7,9.1 1.7,10.1 0,14-15.7,21.8-36.4,21.8C79,104.5 38.1,77.1 38.1,59a18.35,18.35 0 0 1 1.5,-7.3C22.8,52.5 1,55.5 1,74.7 1,106.2 75.6,145 134.6,145c45.3,0 56.7,-20.5 56.7,-36.7 0,-12.7-11,-27.1-30.8,-35.7"/>
    {/* 下部（ブリム影）— 元は黒、dark 背景で半透明白に */}
    <path fill="rgba(255,255,255,0.55)" d="m160.5,72.6c1.7,8.2 1.7,9.1 1.7,10.1 0,14-15.7,21.8-36.4,21.8C79,104.5 38.1,77.1 38.1,59a18.35,18.35 0 0 1 1.5,-7.3l3.7,-9.1a6.15,6.15 0 0 0-0.2,1.9c0,9.2 36.3,39.4 84.9,39.4 12.5,0 30.6,-2.6 30.6,-17.5A19.53,19.53 0 0 0 158.3,63Z"/>
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
    <div style={{ borderTop: '1px solid #3c3f42', paddingTop: '10px' }}>
      <p style={{
        margin: 0, fontSize: '10.5px', fontWeight: 500,
        color: '#b8bbbe', lineHeight: 1.45, letterSpacing: '0.01em',
      }}>
        Migration Toolkit for Red Hat Connectivity Link
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
    <span>Copyright © 2026 Red Hat</span>
    <span> | </span>
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
    { path: '/',            label: t('nav.connection'),   icon: <PluggedIcon /> },
    { path: '/services',   label: t('nav.apiList'),       icon: <ListIcon /> },
    { path: '/compatibility', label: t('nav.compatibility'), icon: <CheckCircleIcon /> },
    { path: '/convert',    label: t('nav.convert'),       icon: <CodeIcon /> },
    { path: '/yaml',       label: t('nav.yamlPreview'),   icon: <EyeIcon /> },
    { path: '/validate',   label: t('nav.validation'),    icon: <SecurityIcon /> },
    { path: '/download',   label: t('nav.download'),      icon: <DownloadIcon /> },
  ];

  const toolItems = [
    { path: '/import',  label: t('nav.import'),  icon: <UploadIcon /> },
    { path: '/history', label: t('nav.history'), icon: <HistoryIcon /> },
  ];

  const isWorkflowActive = workflowItems.some(i => i.path === location.pathname);
  const isToolsActive = toolItems.some(i => i.path === location.pathname);

  const sidebar = (
    <PageSidebar isSidebarOpen={isSidebarOpen}>
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
                <span style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <span style={{ width: '16px', flexShrink: 0, opacity: 0.85 }}>{item.icon}</span>
                  {item.label}
                </span>
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
                <span style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <span style={{ width: '16px', flexShrink: 0, opacity: 0.85 }}>{item.icon}</span>
                  {item.label}
                </span>
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
        header={masthead}
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
