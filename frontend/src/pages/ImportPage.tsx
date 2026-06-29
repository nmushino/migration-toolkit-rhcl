import React, { useState, useRef, Component, ErrorInfo, ReactNode } from 'react';
import {
  PageSection,
  PageSectionVariants,
  Title,
  Card,
  CardBody,
  Button,
  Alert,
  Spinner,
  Form,
  FormGroup,
  TextInput,
  Flex,
  FlexItem,
  Stack,
  StackItem,
  Label,
} from '@patternfly/react-core';
import {
  UploadIcon,
  CheckCircleIcon,
  TimesCircleIcon,
  DownloadIcon,
  PlayIcon,
} from '@patternfly/react-icons';
import { useTranslation } from 'react-i18next';
import { importApi, downloadApi, applyApi } from '../api/client';

interface YamlFile { name: string; content: string; }
type EditMap = Record<string, string>;
interface ApplyResult { fileName: string; success: boolean; message: string; }

/* ── Error Boundary ── */
class ErrorBoundary extends Component<{ children: ReactNode }, { hasError: boolean; msg: string }> {
  state = { hasError: false, msg: '' };
  static getDerivedStateFromError(e: Error) { return { hasError: true, msg: e.message }; }
  componentDidCatch(e: Error, i: ErrorInfo) { console.error('[ImportPage]', e, i); }
  render() {
    if (this.state.hasError) {
      return (
        <div style={{ padding: 32 }}>
          <div style={{ background: '#fff1f1', border: '1px solid #c9190b', borderRadius: 6, padding: 20 }}>
            <p style={{ margin: 0, fontWeight: 700, color: '#c9190b' }}>エラーが発生しました</p>
            <pre style={{ marginTop: 8, fontSize: 12, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{this.state.msg}</pre>
            <button onClick={() => this.setState({ hasError: false, msg: '' })}
              style={{ marginTop: 12, padding: '6px 16px', background: '#c9190b', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer' }}>
              再試行
            </button>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}

/* ── シンプルなタブ（Tabs コンポーネントを使わない） ── */
interface SimpleTabs { files: YamlFile[]; edits: EditMap; onEdit: (name: string, val: string) => void; }
const SimpleYamlTabs: React.FC<SimpleTabs> = ({ files, edits, onEdit }) => {
  const [active, setActive] = useState(0);
  const [editMode, setEditMode] = useState(false);

  if (files.length === 0) return null;
  const current = files[active] ?? files[0];
  const content = edits[current.name] ?? current.content;

  return (
    <div>
      {/* タブヘッダー */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 2, borderBottom: '2px solid #d2d2d2', marginBottom: 12 }}>
        {files.map((f, i) => (
          <button
            key={f.name}
            onClick={() => { setActive(i); setEditMode(false); }}
            style={{
              padding: '8px 16px', fontSize: 13, cursor: 'pointer',
              border: 'none', borderBottom: i === active ? '2px solid #0066cc' : 'none',
              background: i === active ? '#f0f7ff' : 'transparent',
              color: i === active ? '#0066cc' : '#3c3f42',
              fontWeight: i === active ? 600 : 400,
              marginBottom: -2,
            }}
          >
            {f.name}
          </button>
        ))}
      </div>

      {/* 編集トグル */}
      <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 8 }}>
        <Button variant="secondary" size="sm" onClick={() => setEditMode(p => !p)}>
          {editMode ? '✓ 完了' : '✏ 編集'}
        </Button>
      </div>

      {/* コンテンツ */}
      {editMode ? (
        <textarea
          value={content}
          onChange={e => onEdit(current.name, e.target.value)}
          style={{
            width: '100%', minHeight: 480, fontFamily: 'monospace', fontSize: 13,
            background: '#1b1d21', color: '#d4d4d4', border: '1px solid #6a6e73',
            borderRadius: 4, padding: 12, boxSizing: 'border-box', resize: 'vertical',
          }}
        />
      ) : (
        <pre style={{
          background: '#1b1d21', color: '#d4d4d4', padding: 16, borderRadius: 4,
          overflow: 'auto', maxHeight: 480, fontSize: 13, fontFamily: 'monospace',
          margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
        }}>
          {content}
        </pre>
      )}
    </div>
  );
};

/* ── メインコンポーネント ── */
const ImportPageInner: React.FC = () => {
  const { t } = useTranslation();
  const fileRef = useRef<HTMLInputElement>(null);
  const [dragOver, setDragOver]         = useState(false);
  const [loading, setLoading]           = useState(false);
  const [error, setError]               = useState<string | null>(null);
  const [files, setFiles]               = useState<YamlFile[]>([]);
  const [edits, setEdits]               = useState<EditMap>({});
  const [namespace, setNamespace]       = useState('default');
  const [packageName, setPackageName]   = useState('');
  const [uploadedName, setUploadedName] = useState('');
  const [downloaded, setDownloaded]     = useState(false);
  const [applying, setApplying]         = useState(false);
  const [applyResults, setApplyResults] = useState<ApplyResult[] | null>(null);

  const reset = () => {
    setFiles([]); setEdits({}); setUploadedName('');
    setApplyResults(null); setError(null); setDownloaded(false);
  };

  const handleFile = async (file: File) => {
    if (!file.name.toLowerCase().endsWith('.zip')) {
      setError(t('import.errorZipOnly')); return;
    }
    setLoading(true); setError(null); reset();
    setUploadedName(file.name);
    setPackageName(file.name.replace(/\.zip$/i, ''));
    try {
      const res = await importApi.uploadZip(file);
      const yamlMap: Record<string, string> = res.data?.files ?? {};
      if (Object.keys(yamlMap).length === 0) {
        setError('ZIPにYAMLファイルが含まれていません'); setLoading(false); return;
      }
      const loaded = Object.entries(yamlMap)
        .map(([name, content]) => ({ name, content: String(content) }))
        .sort((a, b) => a.name.localeCompare(b.name));
      const init: EditMap = {};
      loaded.forEach(f => { init[f.name] = f.content; });
      setFiles(loaded); setEdits(init);
    } catch (e: any) {
      setError(t('import.errorUpload', { message: e.response?.data?.error ?? e.message }));
    } finally { setLoading(false); }
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault(); setDragOver(false);
    const f = e.dataTransfer.files[0]; if (f) handleFile(f);
  };

  const normalizeApiVersions = (yaml: string) => yaml
    .replace(/apiVersion: kuadrant\.io\/v1beta2/g, 'apiVersion: kuadrant.io/v1')
    .replace(/apiVersion: kuadrant\.io\/v1beta1/g, 'apiVersion: kuadrant.io/v1')
    .replace(/apiVersion: gateway\.networking\.k8s\.io\/v1beta1/g, 'apiVersion: gateway.networking.k8s.io/v1');

  const applyNamespace = () => {
    const updated: EditMap = {};
    files.forEach(f => {
      updated[f.name] = normalizeApiVersions(edits[f.name] ?? f.content)
        .replace(/^(\s*namespace:\s*).+$/gm, `$1${namespace}`);
    });
    setEdits(updated);
  };

  const handleDownload = async () => {
    const yamlFiles: Record<string, string> = {};
    files.forEach(f => { yamlFiles[f.name] = edits[f.name] ?? f.content; });
    try {
      const resp = await downloadApi.downloadZip(packageName || 'package', yamlFiles);
      const url = URL.createObjectURL(new Blob([resp.data], { type: 'application/zip' }));
      const a = document.createElement('a');
      a.href = url; a.download = `${packageName || 'package'}.zip`;
      a.click(); URL.revokeObjectURL(url);
      setDownloaded(true);
    } catch (e: any) { setError(t('import.errorDownload', { message: e.message })); }
  };

  const handleApply = async () => {
    setApplying(true); setApplyResults(null); setError(null);
    const yamlFiles: Record<string, string> = {};
    files.forEach(f => { yamlFiles[f.name] = edits[f.name] ?? f.content; });
    try {
      const res = await applyApi.apply(namespace, yamlFiles);
      setApplyResults(res.data?.results ?? []);
    } catch (e: any) {
      const data = e.response?.data;
      if (Array.isArray(data?.results)) setApplyResults(data.results);
      else setError(t('import.errorApply', { message: data?.error ?? e.message }));
    } finally { setApplying(false); }
  };

  const successCount = applyResults?.filter(r => r.success).length ?? 0;
  const errorCount   = applyResults?.filter(r => !r.success).length ?? 0;

  return (
    <>
      <PageSection variant={PageSectionVariants.light}>
        <Title headingLevel="h1" size="2xl">{t('import.title')}</Title>
        <p style={{ marginTop: 8, color: '#6a6e73' }}>{t('import.description')}</p>
      </PageSection>

      <PageSection>
        <Stack hasGutter>

          {error && (
            <StackItem>
              <Alert variant="danger" title={error} isInline
                actionClose={<Button variant="plain" onClick={() => setError(null)}>×</Button>} />
            </StackItem>
          )}

          {/* ── アップロードゾーン ── */}
          {files.length === 0 && (
            <StackItem>
              <Card>
                <CardBody>
                  <div
                    onDragOver={e => { e.preventDefault(); setDragOver(true); }}
                    onDragLeave={() => setDragOver(false)}
                    onDrop={handleDrop}
                    onClick={() => !loading && fileRef.current?.click()}
                    style={{
                      border: `2px dashed ${dragOver ? '#0066cc' : '#8a8d90'}`,
                      borderRadius: 8, padding: '48px 24px', textAlign: 'center',
                      cursor: loading ? 'default' : 'pointer',
                      background: dragOver ? '#e7f1fa' : '#fafafa', transition: 'all 0.2s',
                    }}
                  >
                    {loading ? (
                      <><Spinner size="lg" /><p style={{ marginTop: 16 }}>{t('import.analyzing')}</p></>
                    ) : (
                      <>
                        <UploadIcon style={{ fontSize: '3rem', color: '#6a6e73' }} />
                        <p style={{ marginTop: 16, fontSize: '1.1rem', fontWeight: 500 }}>
                          {t('import.dropZone')}
                        </p>
                        <p style={{ color: '#6a6e73', marginTop: 8 }}>{t('import.orClick')}</p>
                        <Button variant="primary" style={{ marginTop: 16 }}
                          onClick={e => { e.stopPropagation(); fileRef.current?.click(); }}>
                          {t('import.btnSelectFile')}
                        </Button>
                      </>
                    )}
                    <input ref={fileRef} type="file" accept=".zip" style={{ display: 'none' }}
                      onChange={e => { const f = e.target.files?.[0]; if (f) handleFile(f); e.target.value = ''; }} />
                  </div>
                </CardBody>
              </Card>
            </StackItem>
          )}

          {/* ── ファイル読み込み後 ── */}
          {files.length > 0 && (
            <>
              {/* ファイル情報バー */}
              <StackItem>
                <Card>
                  <CardBody>
                    <Flex alignItems={{ default: 'alignItemsCenter' }}>
                      <FlexItem>
                        <CheckCircleIcon color="var(--pf-v5-global--success-color--100)" />
                        {' '}<strong>{uploadedName}</strong>{' — '}
                        <Label isCompact color="blue">{t('import.fileCount', { count: files.length })}</Label>
                      </FlexItem>
                      <FlexItem align={{ default: 'alignRight' }}>
                        <Button variant="link" onClick={reset}>{t('import.btnUploadAnother')}</Button>
                      </FlexItem>
                    </Flex>
                  </CardBody>
                </Card>
              </StackItem>

              {/* Namespace & 適用 */}
              <StackItem>
                <Card>
                  <CardBody>
                    <Title headingLevel="h3" size="md" style={{ marginBottom: 12 }}>
                      {t('import.namespaceSection')}
                    </Title>
                    <Form isHorizontal>
                      <FormGroup label="Namespace" fieldId="imp-ns">
                        <Flex>
                          <FlexItem>
                            <TextInput id="imp-ns" value={namespace}
                              onChange={(_e, v) => setNamespace(v)}
                              placeholder="default" style={{ width: 260 }} />
                          </FlexItem>
                          <FlexItem>
                            <Button variant="secondary" onClick={applyNamespace}>
                              {t('import.btnApplyNamespace')}
                            </Button>
                          </FlexItem>
                        </Flex>
                      </FormGroup>
                    </Form>
                    <div style={{ marginTop: 16, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                      <Button variant="primary"
                        icon={applying ? <Spinner size="sm" /> : <PlayIcon />}
                        onClick={handleApply} isDisabled={applying}>
                        {applying ? t('import.btnApplying') : t('import.btnApplyOc', { namespace })}
                      </Button>
                      <Button variant="secondary" icon={<DownloadIcon />} onClick={handleDownload}>
                        {t('import.btnDownloadZip')}
                      </Button>
                    </div>
                  </CardBody>
                </Card>
              </StackItem>

              {/* 適用結果 */}
              {applyResults && (
                <StackItem>
                  <Card>
                    <CardBody>
                      <Title headingLevel="h3" size="md" style={{ marginBottom: 12 }}>
                        {t('import.resultTitle')}
                        {' '}<Label isCompact color="green">{t('import.successCount', { count: successCount })}</Label>
                        {errorCount > 0 && <>{' '}<Label isCompact color="red">{t('import.failCount', { count: errorCount })}</Label></>}
                      </Title>
                      {errorCount === 0 && <Alert variant="success" isInline style={{ marginBottom: 12 }}
                        title={t('import.allSuccessAlert', { count: successCount, namespace })} />}
                      {errorCount > 0 && successCount === 0 && <Alert variant="danger" isInline style={{ marginBottom: 12 }}
                        title={t('import.allFailAlert')} />}
                      {errorCount > 0 && successCount > 0 && <Alert variant="warning" isInline style={{ marginBottom: 12 }}
                        title={t('import.partialAlert', { success: successCount, error: errorCount })} />}
                      <div style={{ overflowX: 'auto' }}>
                        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                          <thead>
                            <tr style={{ background: '#f5f5f5' }}>
                              <th style={{ padding: '8px 12px', borderBottom: '2px solid #d2d2d2', textAlign: 'left' }}>{t('import.colFileName')}</th>
                              <th style={{ padding: '8px 12px', borderBottom: '2px solid #d2d2d2', width: 90, textAlign: 'left' }}>{t('import.colResult')}</th>
                              <th style={{ padding: '8px 12px', borderBottom: '2px solid #d2d2d2', textAlign: 'left' }}>{t('import.colMessage')}</th>
                            </tr>
                          </thead>
                          <tbody>
                            {applyResults.map(r => (
                              <tr key={r.fileName} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                <td style={{ padding: '8px 12px', fontFamily: 'monospace' }}>{r.fileName}</td>
                                <td style={{ padding: '8px 12px', whiteSpace: 'nowrap' }}>
                                  {r.success
                                    ? <CheckCircleIcon color="var(--pf-v5-global--success-color--100)" />
                                    : <TimesCircleIcon color="var(--pf-v5-global--danger-color--100)" />}
                                  {' '}{r.success ? t('import.resultSuccess') : t('import.resultFail')}
                                </td>
                                <td style={{ padding: '8px 12px', color: r.success ? '#3e8635' : '#c9190b', fontSize: 12, wordBreak: 'break-word', maxWidth: 500 }}>
                                  {r.message}
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    </CardBody>
                  </Card>
                </StackItem>
              )}

              {/* YAML ビューア（カスタムタブ） */}
              <StackItem>
                <Card>
                  <CardBody>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12, flexWrap: 'wrap', gap: 8 }}>
                      <Title headingLevel="h3" size="md">{t('import.yamlEditorTitle')}</Title>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <label style={{ fontSize: 13, color: '#6a6e73' }}>{t('import.labelPackageName')}</label>
                        <TextInput id="imp-pkg" value={packageName}
                          onChange={(_e, v) => setPackageName(v)} style={{ width: 180 }} />
                      </div>
                    </div>
                    {downloaded && <Alert variant="success" isInline title={t('import.downloadSuccess')} style={{ marginBottom: 12 }} />}
                    <SimpleYamlTabs files={files} edits={edits}
                      onEdit={(name, val) => setEdits(prev => ({ ...prev, [name]: val }))} />
                  </CardBody>
                </Card>
              </StackItem>

              {/* 手動適用手順 */}
              <StackItem>
                <Card>
                  <CardBody>
                    <Title headingLevel="h3" size="md" style={{ marginBottom: 12 }}>
                      {t('import.manualStepsTitle')}
                    </Title>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 8, fontSize: 14 }}>
                      <div><strong>{t('import.step1Term')}</strong> — {t('import.step1Desc')}</div>
                      <div><strong>{t('import.step2Term')}</strong> — <span dangerouslySetInnerHTML={{ __html: t('import.step2Desc') }} /></div>
                      <div><strong>{t('import.step3Term')}</strong> — <code>oc apply -n {namespace} -f ./</code></div>
                    </div>
                  </CardBody>
                </Card>
              </StackItem>
            </>
          )}
        </Stack>
      </PageSection>
    </>
  );
};

const ImportPage: React.FC = () => (
  <ErrorBoundary><ImportPageInner /></ErrorBoundary>
);

export default ImportPage;
