import React, { useState } from 'react';
import {
  PageSection,
  PageSectionVariants,
  Title,
  Card,
  CardBody,
  Button,
  Alert,
  Spinner,
  Progress,
  DataList,
  DataListItem,
  DataListItemRow,
  DataListItemCells,
  DataListCell,
  Label,
  Stack,
  StackItem,
  Checkbox,
  TextInput,
  FormGroup,
  Form,
} from '@patternfly/react-core';
import { CheckCircleIcon, TimesCircleIcon } from '@patternfly/react-icons';
import { useTranslation } from 'react-i18next';
import { conversionApi } from '../api/client';
import { ConversionResultItem } from '../api/types';
import { AppState } from '../App';
import { useNavigate } from 'react-router-dom';

interface Props {
  appState: AppState;
  setAppState: React.Dispatch<React.SetStateAction<AppState>>;
}

const ConversionPage: React.FC<Props> = ({ appState, setAppState }) => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [results, setResults] = useState<ConversionResultItem[]>(appState.conversionResults);
  const [error, setError] = useState<string | null>(null);
  const [progress, setProgress] = useState(0);
  const [isExternal, setIsExternal] = useState(false);
  const [externalBackendUrl, setExternalBackendUrl] = useState('');

  const handleConvert = async () => {
    setLoading(true);
    setError(null);
    setProgress(10);

    try {
      const resp = await conversionApi.convert({
        threescaleUrl: appState.connection.url,
        accessToken: appState.connection.accessToken,
        tenant: appState.connection.tenant,
        namespace: appState.namespace,
        serviceIds: appState.selectedServices.map(s => s.id),
        externalBackendUrl: isExternal && externalBackendUrl ? externalBackendUrl : undefined,
      });
      setProgress(100);
      const convResults: ConversionResultItem[] = resp.data.results;
      setResults(convResults);
      setAppState(prev => ({ ...prev, conversionResults: convResults }));
    } catch (e: any) {
      setError(t('conversion.errorConvert', { message: e.response?.data?.error || e.message }));
    } finally {
      setLoading(false);
    }
  };

  if (appState.selectedServices.length === 0) {
    return (
      <PageSection>
        <Alert variant="warning" title={t('conversion.warningTitle')}>
          <Button variant="link" onClick={() => navigate('/services')}>{t('conversion.goToApiList')}</Button>
        </Alert>
      </PageSection>
    );
  }

  return (
    <>
      <PageSection variant={PageSectionVariants.light}>
        <Title headingLevel="h1" size="2xl">{t('conversion.title')}</Title>
        <p style={{ marginTop: '8px', color: '#6a6e73' }}>
          {t('conversion.description', { count: appState.selectedServices.length })}
        </p>
      </PageSection>
      <PageSection>
        <Stack hasGutter>
          <StackItem>
            <Card>
              <CardBody>
                <Title headingLevel="h3" size="lg">{t('conversion.targetTitle')}</Title>
                <DataList aria-label={t('conversion.ariaTarget')} style={{ marginTop: '16px' }}>
                  {appState.selectedServices.map(svc => (
                    <DataListItem key={svc.id}>
                      <DataListItemRow>
                        <DataListItemCells
                          dataListCells={[
                            <DataListCell key="name" width={3}>
                              <strong>{svc.name}</strong>
                              <br />
                              <small>{svc.systemName}</small>
                            </DataListCell>,
                            <DataListCell key="auth">
                              Auth: {svc.authentication?.type || 'none'}
                            </DataListCell>,
                            <DataListCell key="backends">
                              Backends: {svc.backends?.length || 0}
                            </DataListCell>,
                          ]}
                        />
                      </DataListItemRow>
                    </DataListItem>
                  ))}
                </DataList>

                <div style={{ marginTop: '20px', padding: '16px', background: '#f0f4f8', border: '1px solid #bee1f4', borderRadius: '6px' }}>
                  <div style={{ fontWeight: 600, fontSize: '14px', marginBottom: '12px', color: '#004080' }}>
                    {t('conversion.backendType', 'バックエンド設定')}
                  </div>
                  <Form>
                    <FormGroup>
                      <Checkbox
                        id="external-backend"
                        label={t('conversion.externalBackend', 'バックエンドはクラスター外部のサービス (AWS ECS / 外部 HTTPS エンドポイント)')}
                        isChecked={isExternal}
                        onChange={(_e, checked) => setIsExternal(checked)}
                      />
                    </FormGroup>
                    {isExternal && (
                      <>
                        <FormGroup
                          label={t('conversion.externalBackendUrl', '外部バックエンド URL')}
                          fieldId="external-backend-url"
                          isRequired
                          helperText={t('conversion.externalBackendUrlHelp', '例: https://foo.ecs.us-east-2.on.aws')}
                        >
                          <TextInput
                            id="external-backend-url"
                            type="url"
                            value={externalBackendUrl}
                            onChange={(_e, val) => setExternalBackendUrl(val)}
                            placeholder="https://your-service.ecs.us-east-2.on.aws"
                          />
                        </FormGroup>
                        <div style={{
                          marginTop: '12px',
                          padding: '12px',
                          background: '#fff8e1',
                          border: '1px solid #f0ab00',
                          borderRadius: '4px',
                          fontSize: '13px',
                          color: '#795600',
                        }}>
                          <div style={{ fontWeight: 600, marginBottom: '6px' }}>
                            {t('conversion.externalNote', '外部サービス向けに以下のリソースが追加生成されます：')}
                          </div>
                          <ul style={{ margin: 0, paddingLeft: '18px', lineHeight: '1.8' }}>
                            <li dangerouslySetInnerHTML={{ __html: t('conversion.externalNoteEnvoy') }} />
                            <li dangerouslySetInnerHTML={{ __html: t('conversion.externalNoteRoute') }} />
                          </ul>
                        </div>
                      </>
                    )}
                  </Form>
                </div>

                {error && <Alert variant="danger" title={error} style={{ marginTop: '16px' }} />}

                {loading && (
                  <div style={{ marginTop: '16px' }}>
                    <Progress value={progress} title={t('conversion.progressTitle')} />
                    <div style={{ textAlign: 'center', marginTop: '8px' }}>
                      <Spinner size="md" /> {t('conversion.converting')}
                    </div>
                  </div>
                )}

                <div style={{ marginTop: '24px', display: 'flex', gap: '8px' }}>
                  <Button variant="secondary" onClick={() => navigate('/compatibility')}>{t('conversion.btnBack')}</Button>
                  <Button
                    variant="primary"
                    onClick={handleConvert}
                    isDisabled={loading}
                  >
                    {loading ? t('conversion.btnConverting') : results.length > 0 ? t('conversion.btnReconvert') : t('conversion.btnConvert')}
                  </Button>
                </div>
              </CardBody>
            </Card>
          </StackItem>

          {results.length > 0 && (
            <StackItem>
              <Card>
                <CardBody>
                  <Title headingLevel="h3" size="lg">{t('conversion.resultTitle')}</Title>
                  <DataList aria-label={t('conversion.ariaResult')} style={{ marginTop: '16px' }}>
                    {results.map(result => (
                      <DataListItem key={result.serviceId}>
                        <DataListItemRow>
                          <DataListItemCells
                            dataListCells={[
                              <DataListCell key="icon">
                                {result.status === 'FAILED'
                                  ? <TimesCircleIcon color="red" />
                                  : <CheckCircleIcon color="green" />}
                              </DataListCell>,
                              <DataListCell key="name" width={2}>
                                <strong>{result.serviceName}</strong>
                              </DataListCell>,
                              <DataListCell key="score">
                                Score: {result.compatibilityScore}%
                              </DataListCell>,
                              <DataListCell key="files">
                                {result.files ? (
                                  <div>
                                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px' }}>
                                      {result.files.map(f => {
                                        const isExternalFile = f === 'envoyfilter.yaml';
                                        return (
                                          <Label
                                            key={f}
                                            isCompact
                                            color={isExternalFile ? 'orange' : 'blue'}
                                            title={isExternalFile ? t('conversion.externalFileTitle') : undefined}
                                          >
                                            {f}
                                          </Label>
                                        );
                                      })}
                                    </div>
                                    {result.files.includes('envoyfilter.yaml') && (
                                      <div style={{
                                        marginTop: '6px',
                                        fontSize: '12px',
                                        color: '#795600',
                                        display: 'flex',
                                        alignItems: 'center',
                                        gap: '4px',
                                      }}>
                                        <span style={{ color: '#f0ab00' }}>●</span>
                                        {t('conversion.externalFilesNote', '外部サービス向けリソースを含みます（EnvoyFilter + Host rewrite）')}
                                      </div>
                                    )}
                                  </div>
                                ) : result.error}
                              </DataListCell>,
                            ]}
                          />
                        </DataListItemRow>
                      </DataListItem>
                    ))}
                  </DataList>
                  <div style={{ marginTop: '16px' }}>
                    <Button variant="primary" onClick={() => navigate('/yaml')}>
                      {t('conversion.btnNext')}
                    </Button>
                  </div>
                </CardBody>
              </Card>
            </StackItem>
          )}
        </Stack>
      </PageSection>
    </>
  );
};

export default ConversionPage;
