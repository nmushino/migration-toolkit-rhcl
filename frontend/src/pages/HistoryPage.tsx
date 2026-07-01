import React, { useState, useEffect, useCallback } from 'react';
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
  Badge,
  Checkbox,
  Modal,
  ModalVariant,
} from '@patternfly/react-core';
import {
  HistoryIcon,
  DownloadIcon,
  CubesIcon,
  TrashIcon,
  AngleRightIcon,
  AngleDownIcon,
  ExclamationCircleIcon,
  CheckCircleIcon,
} from '@patternfly/react-icons';
import { useTranslation } from 'react-i18next';
import { historyApi } from '../api/client';
import { ConversionHistory, FailureDetail } from '../api/types';

const formatDate = (iso: string, locale: string): string => {
  try {
    const isJa = locale === 'ja';
    return new Date(iso).toLocaleString(isJa ? 'ja-JP' : 'en-US', {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
      timeZone: isJa ? 'Asia/Tokyo' : undefined,
    });
  } catch { return iso; }
};

const statusColor = (status: string): 'green' | 'red' | 'orange' | 'blue' => {
  switch (status?.toUpperCase()) {
    case 'COMPLETED': return 'green';
    case 'FAILED':    return 'red';
    case 'PARTIAL':   return 'orange';
    default:          return 'blue';
  }
};

const sourceLabel = (source?: string) => {
  if (source === 'IMPORT') return <Label isCompact color="purple">ZIP Import</Label>;
  if (source === 'CONVERT') return <Label isCompact color="blue">Convert</Label>;
  return <Label isCompact color="grey">{source ?? '—'}</Label>;
};

const HistoryPage: React.FC = () => {
  const { t, i18n } = useTranslation();
  const [history, setHistory]         = useState<ConversionHistory[]>([]);
  const [loading, setLoading]         = useState(true);
  const [error, setError]             = useState<string | null>(null);
  const [selected, setSelected]       = useState<Set<number>>(new Set());
  const [expanded, setExpanded]       = useState<Set<number>>(new Set());
  const [downloading, setDownloading] = useState<Record<number, boolean>>({});
  const [deleteModal, setDeleteModal] = useState(false);
  const [deleting, setDeleting]       = useState(false);
  const [toast, setToast]             = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    historyApi.list()
      .then(r => setHistory(Array.isArray(r.data) ? r.data : []))
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { load(); }, [load]);

  const showToast = (msg: string) => {
    setToast(msg);
    setTimeout(() => setToast(null), 3000);
  };

  const toggleSelect = (id: number) => {
    setSelected(prev => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  };

  const toggleAll = () => {
    if (selected.size === history.length) {
      setSelected(new Set());
    } else {
      setSelected(new Set(history.map(h => h.id)));
    }
  };

  const toggleExpand = (id: number) => {
    setExpanded(prev => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  };

  const handleDownload = async (entry: ConversionHistory) => {
    setDownloading(prev => ({ ...prev, [entry.id]: true }));
    try {
      const resp = await historyApi.downloadZip(entry.id);
      const blob = new Blob([resp.data], { type: 'application/zip' });
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      const dateStr = new Date(entry.createdAt).toISOString().slice(0, 16).replace(/[T:]/g, '-');
      link.href = url;
      link.download = `history-${entry.id}-${dateStr}.zip`;
      link.click();
      window.URL.revokeObjectURL(url);
    } catch (e: any) {
      showToast(t('history.downloadError2', { message: e.message }));
    } finally {
      setDownloading(prev => ({ ...prev, [entry.id]: false }));
    }
  };

  const handleDelete = async () => {
    setDeleting(true);
    try {
      await historyApi.deleteByIds(Array.from(selected));
      setSelected(new Set());
      setDeleteModal(false);
      showToast(t('history.deleteSuccess', { count: selected.size }));
      load();
    } catch (e: any) {
      showToast(t('history.deleteError', { message: e.message }));
    } finally {
      setDeleting(false);
    }
  };

  const parseFailures = (json?: string): FailureDetail[] => {
    if (!json) return [];
    try { return JSON.parse(json); } catch { return []; }
  };

  const allChecked = history.length > 0 && selected.size === history.length;
  const someChecked = selected.size > 0 && selected.size < history.length;

  return (
    <>
      {/* トースト通知 */}
      {toast && (
        <div style={{
          position: 'fixed', top: 16, right: 16, zIndex: 9999,
          background: '#151515', color: '#fff', padding: '10px 18px',
          borderRadius: 6, fontSize: 13, boxShadow: '0 4px 12px rgba(0,0,0,0.3)',
        }}>
          {toast}
        </div>
      )}

      <PageSection variant={PageSectionVariants.light}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
            <HistoryIcon style={{ fontSize: '1.8rem', color: '#6a6e73' }} />
            <div>
              <Title headingLevel="h1" size="2xl">{t('history.titlePage')}</Title>
              <p style={{ margin: '4px 0 0', color: '#6a6e73', fontSize: '14px' }}>
                {t('history.descriptionPage')}
              </p>
            </div>
          </div>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            {selected.size > 0 && (
              <Button
                variant="danger"
                icon={<TrashIcon />}
                onClick={() => setDeleteModal(true)}
              >
                {t('history.btnDeleteSelected', { count: selected.size })}
              </Button>
            )}
            <Button variant="secondary" onClick={load} isDisabled={loading}>
              {t('history.btnReload')}
            </Button>
          </div>
        </div>
      </PageSection>

      <PageSection>
        {error && (
          <Alert variant="danger" title={error} isInline style={{ marginBottom: 16 }}
            actionClose={<Button variant="plain" onClick={() => setError(null)}>×</Button>} />
        )}

        <Card>
          <CardBody style={{ padding: 0 }}>
            {loading ? (
              <div style={{ textAlign: 'center', padding: '60px' }}><Spinner size="xl" /></div>
            ) : history.length === 0 ? (
              <EmptyState style={{ padding: '60px 24px' }}>
                <EmptyStateIcon icon={CubesIcon} />
                <Title headingLevel="h3" size="lg">{t('history.emptyTitle')}</Title>
                <EmptyStateBody>{t('history.emptyBody')}</EmptyStateBody>
              </EmptyState>
            ) : (
              <>
                {/* ツールバー */}
                <div style={{
                  display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                  padding: '8px 16px', borderBottom: '1px solid #d2d2d2', background: '#f5f5f5',
                }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                    <Checkbox
                      id="select-all"
                      isChecked={allChecked}
                      onChange={toggleAll}
                      aria-label={t('history.ariaSelectAll')}
                      style={{ marginRight: 4 }}
                    />
                    <span style={{ fontSize: 13, color: '#6a6e73' }}>
                      {someChecked || allChecked
                        ? t('history.selectedCount2', { count: selected.size })
                        : t('history.selectAll')}
                    </span>
                  </div>
                  <Badge isRead>{t('history.countBadge', { count: history.length })}</Badge>
                </div>

                {/* テーブル */}
                <div style={{ overflowX: 'auto' }}>
                  <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '14px' }}>
                    <thead>
                      <tr style={{ borderBottom: '2px solid #d2d2d2' }}>
                        <th style={{ ...thS, width: 40 }}></th>
                        <th style={{ ...thS, width: 32 }}></th>
                        <th style={thS}>{t('history.colDateTime')}</th>
                        <th style={thS}>{t('history.colType')}</th>
                        <th style={thS}>Namespace</th>
                        <th style={{ ...thS, textAlign: 'center' }}>{t('history.colStatus')}</th>
                        <th style={{ ...thS, textAlign: 'center' }}>{t('history.colSuccessFail')}</th>
                        <th style={{ ...thS, textAlign: 'center', width: 120 }}>{t('history.colOps')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {history.map((entry, idx) => {
                        const failures = parseFailures(entry.failureDetails);
                        const hasFailures = failures.length > 0;
                        const isExpanded = expanded.has(entry.id);
                        const isSelected = selected.has(entry.id);

                        return (
                          <React.Fragment key={entry.id}>
                            <tr
                              style={{
                                borderBottom: isExpanded ? 'none' : '1px solid #f0f0f0',
                                background: isSelected ? '#e8f1fb'
                                  : idx % 2 === 0 ? '#ffffff' : '#fafafa',
                              }}
                            >
                              {/* チェックボックス */}
                              <td style={{ ...tdS, textAlign: 'center' }}>
                                <Checkbox
                                  id={`chk-${entry.id}`}
                                  isChecked={isSelected}
                                  onChange={() => toggleSelect(entry.id)}
                                  aria-label={t('history.ariaSelectEntry', { id: entry.id })}
                                />
                              </td>

                              {/* 展開ボタン（失敗がある場合のみ） */}
                              <td style={{ ...tdS, textAlign: 'center' }}>
                                {hasFailures && (
                                  <Button variant="plain" size="sm"
                                    onClick={() => toggleExpand(entry.id)}
                                    aria-label={t('history.ariaDetails')}>
                                    {isExpanded ? <AngleDownIcon /> : <AngleRightIcon />}
                                  </Button>
                                )}
                              </td>

                              {/* 実行日時 */}
                              <td style={tdS}>
                                <span style={{ fontFamily: 'monospace', fontSize: 13, whiteSpace: 'nowrap' }}>
                                  {formatDate(entry.createdAt, i18n.language)}
                                </span>
                              </td>

                              {/* 種別 */}
                              <td style={tdS}>{sourceLabel(entry.source)}</td>

                              {/* Namespace */}
                              <td style={tdS}>
                                <code style={{ fontSize: 12 }}>{entry.namespace ?? '—'}</code>
                              </td>

                              {/* ステータス */}
                              <td style={{ ...tdS, textAlign: 'center' }}>
                                <Label color={statusColor(entry.status)}>
                                  {entry.status === 'COMPLETED' ? t('history.statusSuccess')
                                    : entry.status === 'FAILED' ? t('history.statusFailed')
                                    : entry.status === 'PARTIAL' ? t('history.statusPartial')
                                    : entry.status}
                                </Label>
                              </td>

                              {/* 成功/失敗カウント */}
                              <td style={{ ...tdS, textAlign: 'center' }}>
                                {entry.totalCount != null ? (
                                  <span style={{ fontSize: 13 }}>
                                    <span style={{ color: '#3e8635', fontWeight: 600 }}>
                                      <CheckCircleIcon style={{ marginRight: 3, fontSize: 12 }} />
                                      {entry.successCount ?? 0}
                                    </span>
                                    <span style={{ color: '#6a6e73', margin: '0 4px' }}>/</span>
                                    <span style={{ color: (entry.failureCount ?? 0) > 0 ? '#c9190b' : '#6a6e73', fontWeight: (entry.failureCount ?? 0) > 0 ? 600 : 400 }}>
                                      <ExclamationCircleIcon style={{ marginRight: 3, fontSize: 12 }} />
                                      {entry.failureCount ?? 0}
                                    </span>
                                    <span style={{ color: '#8a8d90', fontSize: 11, marginLeft: 4 }}>
                                      {t('history.totalOf', { count: entry.totalCount })}
                                    </span>
                                  </span>
                                ) : '—'}
                              </td>

                              {/* ダウンロード */}
                              <td style={{ ...tdS, textAlign: 'center' }}>
                                <Button
                                  variant="secondary"
                                  icon={<DownloadIcon />}
                                  size="sm"
                                  onClick={() => handleDownload(entry)}
                                  isDisabled={downloading[entry.id] || entry.status === 'FAILED'}
                                >
                                  {downloading[entry.id] ? '...' : 'YAML'}
                                </Button>
                              </td>
                            </tr>

                            {/* 展開: 失敗詳細 */}
                            {isExpanded && hasFailures && (
                              <tr style={{ borderBottom: '1px solid #f0f0f0' }}>
                                <td colSpan={8} style={{ padding: '0 0 12px 72px', background: '#fff8f7' }}>
                                  <div style={{ fontSize: 13, fontWeight: 600, color: '#c9190b', marginBottom: 8 }}>
                                    {t('history.failedResources', { count: failures.length })}
                                  </div>
                                  <table style={{ width: '100%', fontSize: 12, borderCollapse: 'collapse' }}>
                                    <thead>
                                      <tr style={{ color: '#6a6e73' }}>
                                        <th style={{ ...thS, fontWeight: 600, padding: '4px 12px' }}>{t('history.colFile')}</th>
                                        <th style={{ ...thS, fontWeight: 600, padding: '4px 12px' }}>Kind</th>
                                        <th style={{ ...thS, fontWeight: 600, padding: '4px 12px' }}>{t('history.colName')}</th>
                                        <th style={{ ...thS, fontWeight: 600, padding: '4px 12px' }}>{t('history.colError')}</th>
                                      </tr>
                                    </thead>
                                    <tbody>
                                      {failures.map((f, i) => (
                                        <tr key={i} style={{ borderTop: '1px solid #f5c6c6' }}>
                                          <td style={{ padding: '4px 12px', fontFamily: 'monospace' }}>{f.fileName}</td>
                                          <td style={{ padding: '4px 12px' }}>
                                            <Label isCompact color="red">{f.kind}</Label>
                                          </td>
                                          <td style={{ padding: '4px 12px', fontFamily: 'monospace' }}>{f.name}</td>
                                          <td style={{ padding: '4px 12px', color: '#c9190b', wordBreak: 'break-all' }}>
                                            {f.error}
                                          </td>
                                        </tr>
                                      ))}
                                    </tbody>
                                  </table>
                                </td>
                              </tr>
                            )}
                          </React.Fragment>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </>
            )}
          </CardBody>
        </Card>
      </PageSection>

      {/* 削除確認モーダル */}
      <Modal
        variant={ModalVariant.small}
        title={t('history.deleteTitle')}
        isOpen={deleteModal}
        onClose={() => setDeleteModal(false)}
        actions={[
          <Button key="del" variant="danger" onClick={handleDelete} isLoading={deleting}>
            {t('history.btnDelete')}
          </Button>,
          <Button key="cancel" variant="link" onClick={() => setDeleteModal(false)}>
            {t('history.btnCancel')}
          </Button>,
        ]}
      >
        <span dangerouslySetInnerHTML={{ __html: t('history.deleteConfirm', { count: selected.size }) }} />
        {' '}{t('history.deleteWarn')}
      </Modal>
    </>
  );
};

const thS: React.CSSProperties = {
  textAlign: 'left',
  padding: '10px 12px',
  fontWeight: 600,
  fontSize: 13,
  color: '#3c3f42',
  whiteSpace: 'nowrap',
  background: '#f5f5f5',
};

const tdS: React.CSSProperties = {
  padding: '10px 12px',
  verticalAlign: 'middle',
};

export default HistoryPage;
