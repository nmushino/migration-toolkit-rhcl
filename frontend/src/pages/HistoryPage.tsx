import React, { useState, useEffect } from 'react';
import {
  PageSection,
  PageSectionVariants,
  Title,
  Card,
  CardBody,
  Spinner,
  Alert,
  Label,
  Button,
  EmptyState,
  EmptyStateIcon,
  EmptyStateBody,
  Toolbar,
  ToolbarContent,
  ToolbarItem,
  Badge,
} from '@patternfly/react-core';
import { HistoryIcon, DownloadIcon, CubesIcon } from '@patternfly/react-icons';
import { useTranslation } from 'react-i18next';
import { historyApi } from '../api/client';
import { ConversionHistory } from '../api/types';

const statusColor = (status: string): 'green' | 'red' | 'orange' | 'blue' => {
  switch (status?.toUpperCase()) {
    case 'COMPLETED': return 'green';
    case 'FAILED':    return 'red';
    case 'IN_PROGRESS': return 'orange';
    default:          return 'blue';
  }
};

const formatDate = (iso: string, locale: string): string => {
  try {
    return new Date(iso).toLocaleString(locale === 'ja' ? 'ja-JP' : 'en-US', {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    });
  } catch {
    return iso;
  }
};

const HistoryPage: React.FC = () => {
  const { t, i18n } = useTranslation();
  const [history, setHistory] = useState<ConversionHistory[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [downloading, setDownloading] = useState<Record<number, boolean>>({});
  const [downloadError, setDownloadError] = useState<string | null>(null);

  useEffect(() => {
    historyApi.list()
      .then(r => {
        const data = Array.isArray(r.data) ? r.data : [];
        setHistory(data);
      })
      .catch(e => setError(t('history.loadError', { message: e.message })))
      .finally(() => setLoading(false));
  }, []);

  const handleDownload = async (entry: ConversionHistory) => {
    setDownloading(prev => ({ ...prev, [entry.id]: true }));
    setDownloadError(null);
    try {
      const resp = await historyApi.downloadZip(entry.id);
      const blob = new Blob([resp.data], { type: 'application/zip' });
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      const safeName = (entry.serviceName || 'service')
        .toLowerCase().replace(/[^a-z0-9]+/g, '-');
      const dateStr = new Date(entry.createdAt)
        .toISOString().slice(0, 16).replace(/[T:]/g, '-');
      link.href = url;
      link.download = `${safeName}-${dateStr}.zip`;
      link.click();
      window.URL.revokeObjectURL(url);
    } catch (e: any) {
      setDownloadError(t('history.downloadError', { message: e.message }));
    } finally {
      setDownloading(prev => ({ ...prev, [entry.id]: false }));
    }
  };

  const statusLabel = (status: string) => {
    const key = status?.toUpperCase();
    if (key === 'COMPLETED') return t('history.statusCompleted');
    if (key === 'FAILED')    return t('history.statusFailed');
    if (key === 'IN_PROGRESS') return t('history.statusInProgress');
    return status;
  };

  return (
    <>
      <PageSection variant={PageSectionVariants.light}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <HistoryIcon style={{ fontSize: '1.8rem', color: '#6a6e73' }} />
          <div>
            <Title headingLevel="h1" size="2xl">{t('history.title')}</Title>
            <p style={{ margin: '4px 0 0', color: '#6a6e73', fontSize: '14px' }}>
              {t('history.description')}
            </p>
          </div>
        </div>
      </PageSection>

      <PageSection>
        {downloadError && (
          <Alert
            variant="danger"
            title={downloadError}
            isInline
            style={{ marginBottom: '16px' }}
            actionClose={<Button variant="plain" onClick={() => setDownloadError(null)}>×</Button>}
          />
        )}

        <Card>
          <CardBody style={{ padding: 0 }}>
            {loading ? (
              <div style={{ textAlign: 'center', padding: '60px' }}>
                <Spinner size="xl" />
              </div>
            ) : error ? (
              <div style={{ padding: '24px' }}>
                <Alert variant="danger" title={error} isInline />
              </div>
            ) : history.length === 0 ? (
              <EmptyState style={{ padding: '60px 24px' }}>
                <EmptyStateIcon icon={CubesIcon} />
                <Title headingLevel="h3" size="lg">{t('history.empty')}</Title>
                <EmptyStateBody>{t('history.emptyHint')}</EmptyStateBody>
              </EmptyState>
            ) : (
              <>
                <Toolbar style={{ borderBottom: '1px solid #d2d2d2', padding: '8px 16px' }}>
                  <ToolbarContent>
                    <ToolbarItem align={{ default: 'alignRight' }}>
                      <Badge isRead>{t('history.totalCount', { count: history.length })}</Badge>
                    </ToolbarItem>
                  </ToolbarContent>
                </Toolbar>

                <div style={{ overflowX: 'auto' }}>
                  <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '14px' }}>
                    <thead>
                      <tr style={{ background: '#f5f5f5', borderBottom: '2px solid #d2d2d2' }}>
                        <th style={thStyle}>{t('history.colDate')}</th>
                        <th style={thStyle}>{t('history.colService')}</th>
                        <th style={{ ...thStyle, textAlign: 'center' }}>{t('history.colStatus')}</th>
                        <th style={{ ...thStyle, textAlign: 'center' }}>{t('history.colScore')}</th>
                        <th style={{ ...thStyle, textAlign: 'center', width: '160px' }}>{t('history.colAction')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {history.map((entry, idx) => (
                        <tr
                          key={entry.id}
                          style={{
                            borderBottom: '1px solid #f0f0f0',
                            background: idx % 2 === 0 ? '#ffffff' : '#fafafa',
                            transition: 'background 0.1s',
                          }}
                          onMouseEnter={e => (e.currentTarget.style.background = '#f0f7ff')}
                          onMouseLeave={e => (e.currentTarget.style.background = idx % 2 === 0 ? '#ffffff' : '#fafafa')}
                        >
                          {/* 生成日時 */}
                          <td style={tdStyle}>
                            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                              <HistoryIcon style={{ color: '#8a8d90', fontSize: '14px', flexShrink: 0 }} />
                              <span style={{ fontFamily: 'monospace', fontSize: '13px', whiteSpace: 'nowrap' }}>
                                {formatDate(entry.createdAt, i18n.language)}
                              </span>
                            </div>
                          </td>

                          {/* サービス名 */}
                          <td style={tdStyle}>
                            <div style={{ fontWeight: 600 }}>{entry.serviceName || '—'}</div>
                            {entry.serviceId && (
                              <div style={{ fontSize: '12px', color: '#8a8d90', marginTop: '2px' }}>
                                ID: {entry.serviceId}
                              </div>
                            )}
                          </td>

                          {/* ステータス */}
                          <td style={{ ...tdStyle, textAlign: 'center' }}>
                            <Label color={statusColor(entry.status)}>
                              {statusLabel(entry.status)}
                            </Label>
                          </td>

                          {/* スコア */}
                          <td style={{ ...tdStyle, textAlign: 'center' }}>
                            {entry.compatibilityScore != null ? (
                              <span style={{
                                fontWeight: 700,
                                fontSize: '15px',
                                color: entry.compatibilityScore >= 80 ? '#3e8635'
                                     : entry.compatibilityScore >= 50 ? '#c46100'
                                     : '#c9190b',
                              }}>
                                {entry.compatibilityScore}{t('history.scoreUnit')}
                              </span>
                            ) : '—'}
                          </td>

                          {/* ダウンロード */}
                          <td style={{ ...tdStyle, textAlign: 'center' }}>
                            <Button
                              variant="secondary"
                              icon={<DownloadIcon />}
                              onClick={() => handleDownload(entry)}
                              isDisabled={downloading[entry.id] || entry.status?.toUpperCase() === 'FAILED'}
                              size="sm"
                            >
                              {downloading[entry.id]
                                ? t('history.btnDownloading')
                                : t('history.btnDownload')}
                            </Button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </>
            )}
          </CardBody>
        </Card>
      </PageSection>
    </>
  );
};

const thStyle: React.CSSProperties = {
  textAlign: 'left',
  padding: '10px 16px',
  fontWeight: 600,
  fontSize: '13px',
  color: '#3c3f42',
  whiteSpace: 'nowrap',
};

const tdStyle: React.CSSProperties = {
  padding: '12px 16px',
  verticalAlign: 'middle',
};

export default HistoryPage;
