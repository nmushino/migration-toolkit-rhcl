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
  ProgressVariant,
  DataList,
  DataListItem,
  DataListItemRow,
  DataListItemCells,
  DataListCell,
  Label,
  Stack,
  StackItem,
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
                                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px' }}>
                                    {result.files.map(f => (
                                      <Label key={f} isCompact>{f}</Label>
                                    ))}
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
