import React, { useState, useRef } from 'react';
import {
  PageSection,
  PageSectionVariants,
  Title,
  Card,
  CardBody,
  Button,
  Alert,
  AlertVariant,
  Spinner,
  Tabs,
  Tab,
  TabTitleText,
  Form,
  FormGroup,
  TextInput,
  Flex,
  FlexItem,
  Stack,
  StackItem,
  Label,
  DescriptionList,
  DescriptionListGroup,
  DescriptionListTerm,
  DescriptionListDescription,
} from '@patternfly/react-core';
import {
  UploadIcon,
  CheckCircleIcon,
  TimesCircleIcon,
  EditIcon,
  DownloadIcon,
  PlayIcon,
} from '@patternfly/react-icons';
import { useTranslation } from 'react-i18next';
import { importApi, downloadApi, applyApi } from '../api/client';

interface YamlFile { name: string; content: string; }
type EditMap = Record<string, string>;
interface ApplyResult { fileName: string; success: boolean; message: string; }

const ImportPage: React.FC = () => {
  const { t } = useTranslation();
  const fileRef = useRef<HTMLInputElement>(null);
  const [dragOver, setDragOver] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [files, setFiles] = useState<YamlFile[]>([]);
  const [edits, setEdits] = useState<EditMap>({});
  const [activeTab, setActiveTab] = useState(0);
  const [editMode, setEditMode] = useState<Record<string, boolean>>({});
  const [namespace, setNamespace] = useState('default');
  const [packageName, setPackageName] = useState('');
  const [fileName, setFileName] = useState('');
  const [downloaded, setDownloaded] = useState(false);
  const [applying, setApplying] = useState(false);
  const [applyResults, setApplyResults] = useState<ApplyResult[] | null>(null);

  const handleFile = async (file: File) => {
    if (!file.name.endsWith('.zip')) { setError(t('import.errorZipOnly')); return; }
    setLoading(true); setError(null); setFiles([]); setEdits({});
    setFileName(file.name); setPackageName(file.name.replace('.zip', ''));
    setApplyResults(null);
    try {
      const res = await importApi.uploadZip(file);
      const yamlMap: Record<string, string> = res.data.files;
      const loaded = Object.entries(yamlMap).map(([name, content]) => ({ name, content }));
      loaded.sort((a, b) => a.name.localeCompare(b.name));
      setFiles(loaded);
      const initEdits: EditMap = {};
      loaded.forEach(f => { initEdits[f.name] = f.content; });
      setEdits(initEdits);
      setActiveTab(0);
    } catch (e: any) {
      setError(e.response?.data?.error || t('import.errorUpload', { message: e.message }));
    } finally {
      setLoading(false);
    }
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault(); setDragOver(false);
    const file = e.dataTransfer.files[0];
    if (file) handleFile(file);
  };

  const applyNamespace = () => {
    const updated: EditMap = {};
    files.forEach(f => {
      updated[f.name] = (edits[f.name] ?? f.content).replace(/namespace:\s*.+/g, `namespace: ${namespace}`);
    });
    setEdits(updated);
  };

  const handleDownload = async () => {
    const yamlFiles: Record<string, string> = {};
    files.forEach(f => { yamlFiles[f.name] = edits[f.name] ?? f.content; });
    try {
      const resp = await downloadApi.downloadZip(packageName || 'imported-package', yamlFiles);
      const url = window.URL.createObjectURL(new Blob([resp.data]));
      const link = document.createElement('a');
      link.href = url; link.download = `${packageName || 'imported-package'}.zip`;
      link.click(); window.URL.revokeObjectURL(url);
      setDownloaded(true);
    } catch (e: any) { setError(t('import.errorDownload', { message: e.message })); }
  };

  const handleApply = async () => {
    setApplying(true); setApplyResults(null); setError(null);
    const yamlFiles: Record<string, string> = {};
    files.forEach(f => { yamlFiles[f.name] = edits[f.name] ?? f.content; });
    try {
      const res = await applyApi.apply(namespace, yamlFiles);
      setApplyResults(res.data.results);
    } catch (e: any) {
      const data = e.response?.data;
      if (data?.results) {
        setApplyResults(data.results);
      } else {
        setError(t('import.errorApply', { message: data?.error || e.message }));
      }
    } finally {
      setApplying(false);
    }
  };

  const successCount = applyResults?.filter(r => r.success).length ?? 0;
  const errorCount = applyResults?.filter(r => !r.success).length ?? 0;

  return (
    <>
      <PageSection variant={PageSectionVariants.light}>
        <Title headingLevel="h1" size="2xl">{t('import.title')}</Title>
        <p style={{ marginTop: '8px', color: '#6a6e73' }}>
          {t('import.description')}
        </p>
      </PageSection>

      <PageSection>
        <Stack hasGutter>
          {error && (
            <StackItem>
              <Alert variant={AlertVariant.danger} title={error} isInline />
            </StackItem>
          )}

          {files.length === 0 && (
            <StackItem>
              <Card>
                <CardBody>
                  <div
                    onDragOver={e => { e.preventDefault(); setDragOver(true); }}
                    onDragLeave={() => setDragOver(false)}
                    onDrop={handleDrop}
                    onClick={() => fileRef.current?.click()}
                    style={{
                      border: `2px dashed ${dragOver ? '#0066cc' : '#8a8d90'}`,
                      borderRadius: '8px',
                      padding: '48px 24px',
                      textAlign: 'center',
                      cursor: 'pointer',
                      background: dragOver ? '#e7f1fa' : '#fafafa',
                      transition: 'all 0.2s',
                    }}
                  >
                    {loading ? (
                      <><Spinner size="lg" /><p style={{ marginTop: '16px' }}>{t('import.analyzing')}</p></>
                    ) : (
                      <>
                        <UploadIcon style={{ fontSize: '3rem', color: '#6a6e73' }} />
                        <p style={{ marginTop: '16px', fontSize: '1.1rem', fontWeight: 500 }}>
                          {t('import.dropZone')}
                        </p>
                        <p style={{ color: '#6a6e73', marginTop: '8px' }}>{t('import.orClick')}</p>
                        <Button variant="primary" style={{ marginTop: '16px' }}
                          onClick={e => { e.stopPropagation(); fileRef.current?.click(); }}>
                          {t('import.btnSelectFile')}
                        </Button>
                      </>
                    )}
                    <input ref={fileRef} type="file" accept=".zip" style={{ display: 'none' }} onChange={e => { const f = e.target.files?.[0]; if (f) handleFile(f); }} />
                  </div>
                </CardBody>
              </Card>
            </StackItem>
          )}

          {files.length > 0 && (
            <>
              <StackItem>
                <Card>
                  <CardBody>
                    <Flex alignItems={{ default: 'alignItemsCenter' }}>
                      <FlexItem>
                        <CheckCircleIcon color="var(--pf-v5-global--success-color--100)" />
                        {' '}<strong>{fileName}</strong> {t('import.analyzedFile', { name: '' }).replace('<1></1> ', '')}{' '}
                        <Label isCompact color="blue">{t('import.fileCount', { count: files.length })}</Label>
                      </FlexItem>
                      <FlexItem align={{ default: 'alignRight' }}>
                        <Button variant="link" onClick={() => { setFiles([]); setEdits({}); setFileName(''); setApplyResults(null); }}>
                          {t('import.btnUploadAnother')}
                        </Button>
                      </FlexItem>
                    </Flex>
                  </CardBody>
                </Card>
              </StackItem>

              <StackItem>
                <Card>
                  <CardBody>
                    <Title headingLevel="h3" size="md" style={{ marginBottom: '12px' }}>
                      {t('import.namespaceSection')}
                    </Title>
                    <Form isHorizontal>
                      <FormGroup label="Namespace" fieldId="ns">
                        <Flex>
                          <FlexItem>
                            <TextInput id="ns" value={namespace} onChange={(_e, v) => setNamespace(v)} placeholder="default" style={{ width: '260px' }} />
                          </FlexItem>
                          <FlexItem>
                            <Button variant="secondary" onClick={applyNamespace}>{t('import.btnApplyNamespace')}</Button>
                          </FlexItem>
                        </Flex>
                      </FormGroup>
                    </Form>

                    <div style={{ marginTop: '16px', display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
                      <Button
                        variant="primary"
                        icon={applying ? <Spinner size="sm" /> : <PlayIcon />}
                        onClick={handleApply}
                        isDisabled={applying}
                      >
                        {applying ? t('import.btnApplying') : t('import.btnApplyOc', { namespace })}
                      </Button>
                      <Button variant="secondary" icon={<DownloadIcon />} onClick={handleDownload}>
                        {t('import.btnDownloadZip')}
                      </Button>
                    </div>
                  </CardBody>
                </Card>
              </StackItem>

              {applyResults && (
                <StackItem>
                  <Card>
                    <CardBody>
                      <Title headingLevel="h3" size="md" style={{ marginBottom: '12px' }}>
                        {t('import.resultTitle')}
                        {' '}
                        <Label isCompact color="green">{t('import.successCount', { count: successCount })}</Label>
                        {errorCount > 0 && <>{' '}<Label isCompact color="red">{t('import.failCount', { count: errorCount })}</Label></>}
                      </Title>
                      {errorCount === 0 && (
                        <Alert variant={AlertVariant.success} isInline
                          title={t('import.allSuccessAlert', { count: successCount, namespace })}
                          style={{ marginBottom: '12px' }} />
                      )}
                      {errorCount > 0 && successCount === 0 && (
                        <Alert variant={AlertVariant.danger} isInline
                          title={t('import.allFailAlert')} style={{ marginBottom: '12px' }} />
                      )}
                      {errorCount > 0 && successCount > 0 && (
                        <Alert variant={AlertVariant.warning} isInline
                          title={t('import.partialAlert', { success: successCount, error: errorCount })} style={{ marginBottom: '12px' }} />
                      )}
                      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '13px' }}>
                        <thead>
                          <tr style={{ background: '#f0f0f0', textAlign: 'left' }}>
                            <th style={{ padding: '8px 12px', borderBottom: '1px solid #d2d2d2' }}>{t('import.colFileName')}</th>
                            <th style={{ padding: '8px 12px', borderBottom: '1px solid #d2d2d2', width: '80px' }}>{t('import.colResult')}</th>
                            <th style={{ padding: '8px 12px', borderBottom: '1px solid #d2d2d2' }}>{t('import.colMessage')}</th>
                          </tr>
                        </thead>
                        <tbody>
                          {applyResults.map(r => (
                            <tr key={r.fileName} style={{ borderBottom: '1px solid #f0f0f0' }}>
                              <td style={{ padding: '8px 12px', fontFamily: 'monospace' }}>{r.fileName}</td>
                              <td style={{ padding: '8px 12px' }}>
                                {r.success
                                  ? <CheckCircleIcon color="var(--pf-v5-global--success-color--100)" />
                                  : <TimesCircleIcon color="var(--pf-v5-global--danger-color--100)" />}
                                {' '}{r.success ? t('import.resultSuccess') : t('import.resultFail')}
                              </td>
                              <td style={{ padding: '8px 12px', color: r.success ? '#3e8635' : '#c9190b', wordBreak: 'break-word', maxWidth: '500px' }}>
                                {r.message}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </CardBody>
                  </Card>
                </StackItem>
              )}

              <StackItem>
                <Card>
                  <CardBody>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
                      <Title headingLevel="h3" size="md">{t('import.yamlEditorTitle')}</Title>
                      <Form isHorizontal style={{ margin: 0 }}>
                        <FormGroup label={t('import.labelPackageName')} fieldId="pkgname" style={{ margin: 0 }}>
                          <TextInput id="pkgname" value={packageName} onChange={(_e, v) => setPackageName(v)} style={{ width: '200px' }} />
                        </FormGroup>
                      </Form>
                    </div>
                    {downloaded && (
                      <Alert variant={AlertVariant.success} isInline title={t('import.downloadSuccess')} style={{ marginBottom: '12px' }} />
                    )}
                    <Tabs activeKey={activeTab} onSelect={(_e, k) => setActiveTab(Number(k))}>
                      {files.map((f, i) => (
                        <Tab key={f.name} eventKey={i} title={<TabTitleText>{f.name}</TabTitleText>}>
                          <div style={{ marginTop: '12px' }}>
                            <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: '8px' }}>
                              <Button variant="plain" icon={<EditIcon />}
                                onClick={() => setEditMode(prev => ({ ...prev, [f.name]: !prev[f.name] }))}>
                                {editMode[f.name] ? t('import.btnEditDone') : t('import.btnEdit')}
                              </Button>
                            </div>
                            {editMode[f.name] ? (
                              <textarea
                                value={edits[f.name] ?? f.content}
                                onChange={e => setEdits(prev => ({ ...prev, [f.name]: e.target.value }))}
                                style={{
                                  width: '100%', minHeight: '480px', fontFamily: 'monospace', fontSize: '13px',
                                  background: '#1b1d21', color: '#d4d4d4', border: '1px solid #6a6e73',
                                  borderRadius: '4px', padding: '12px', boxSizing: 'border-box', resize: 'vertical',
                                }}
                              />
                            ) : (
                              <pre style={{
                                background: '#1b1d21', color: '#d4d4d4', padding: '16px', borderRadius: '4px',
                                overflow: 'auto', maxHeight: '480px', fontSize: '13px', fontFamily: 'monospace', margin: 0,
                              }}>
                                {edits[f.name] ?? f.content}
                              </pre>
                            )}
                          </div>
                        </Tab>
                      ))}
                    </Tabs>
                  </CardBody>
                </Card>
              </StackItem>

              <StackItem>
                <Card>
                  <CardBody>
                    <Title headingLevel="h3" size="md" style={{ marginBottom: '12px' }}>{t('import.manualStepsTitle')}</Title>
                    <DescriptionList isHorizontal>
                      <DescriptionListGroup>
                        <DescriptionListTerm>{t('import.step1Term')}</DescriptionListTerm>
                        <DescriptionListDescription>{t('import.step1Desc')}</DescriptionListDescription>
                      </DescriptionListGroup>
                      <DescriptionListGroup>
                        <DescriptionListTerm>{t('import.step2Term')}</DescriptionListTerm>
                        <DescriptionListDescription dangerouslySetInnerHTML={{ __html: t('import.step2Desc') }} />
                      </DescriptionListGroup>
                      <DescriptionListGroup>
                        <DescriptionListTerm>{t('import.step3Term')}</DescriptionListTerm>
                        <DescriptionListDescription><code>oc apply -n {namespace} -f ./</code></DescriptionListDescription>
                      </DescriptionListGroup>
                    </DescriptionList>
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

export default ImportPage;
