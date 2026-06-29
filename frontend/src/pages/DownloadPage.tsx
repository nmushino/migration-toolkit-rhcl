import React, { useState } from 'react';
import {
  PageSection,
  PageSectionVariants,
  Title,
  Card,
  CardBody,
  Button,
  Alert,
  DataList,
  DataListItem,
  DataListItemRow,
  DataListItemCells,
  DataListCell,
  Label,
  Stack,
  StackItem,
} from '@patternfly/react-core';
import { DownloadIcon } from '@patternfly/react-icons';
import { useTranslation } from 'react-i18next';
import { downloadApi } from '../api/client';
import { AppState } from '../App';
import { useNavigate } from 'react-router-dom';

interface Props {
  appState: AppState;
  setAppState: React.Dispatch<React.SetStateAction<AppState>>;
}

const DownloadPage: React.FC<Props> = ({ appState }) => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [downloading, setDownloading] = useState<Record<string, boolean>>({});
  const [error, setError] = useState<string | null>(null);

  const results = appState.conversionResults.filter(r => r.yamlFiles && Object.keys(r.yamlFiles).length > 0);

  const handleDownload = async (result: typeof results[0]) => {
    setDownloading(prev => ({ ...prev, [result.serviceId]: true }));
    setError(null);
    try {
      const resp = await downloadApi.downloadZip(result.packageName, result.yamlFiles);
      const url = window.URL.createObjectURL(new Blob([resp.data]));
      const link = document.createElement('a');
      link.href = url;
      link.download = `${result.packageName}.zip`;
      link.click();
      window.URL.revokeObjectURL(url);
    } catch (e: any) {
      setError(t('download.errorDownload', { message: e.message }));
    } finally {
      setDownloading(prev => ({ ...prev, [result.serviceId]: false }));
    }
  };

  const handleDownloadAll = async () => {
    for (const result of results) {
      await handleDownload(result);
    }
  };

  if (results.length === 0) {
    return (
      <PageSection>
        <Alert variant="warning" title={t('download.warningTitle')}>
          <Button variant="link" onClick={() => navigate('/convert')}>{t('download.goToConvert')}</Button>
        </Alert>
      </PageSection>
    );
  }

  return (
    <>
      <PageSection variant={PageSectionVariants.light}>
        <Title headingLevel="h1" size="2xl">{t('download.title')}</Title>
        <p style={{ marginTop: '8px', color: '#6a6e73' }}>
          {t('download.description')}
        </p>
      </PageSection>
      <PageSection>
        <Stack hasGutter>
          {error && (
            <StackItem>
              <Alert variant="danger" title={error} />
            </StackItem>
          )}

          <StackItem>
            <Card>
              <CardBody>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
                  <Title headingLevel="h3" size="lg">{t('download.packageTitle')}</Title>
                  <Button variant="secondary" onClick={handleDownloadAll}>
                    <DownloadIcon /> {t('download.btnDownloadAll')}
                  </Button>
                </div>

                <DataList aria-label={t('download.ariaList')}>
                  {results.map(result => (
                    <DataListItem key={result.serviceId}>
                      <DataListItemRow>
                        <DataListItemCells
                          dataListCells={[
                            <DataListCell key="name" width={2}>
                              <div>
                                <strong>{result.serviceName}</strong>
                                <br />
                                <code style={{ fontSize: '0.85rem', color: '#6a6e73' }}>
                                  {result.packageName}.zip
                                </code>
                              </div>
                            </DataListCell>,
                            <DataListCell key="files">
                              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px' }}>
                                {Object.keys(result.yamlFiles).map(f => (
                                  <Label key={f} isCompact>{f}</Label>
                                ))}
                              </div>
                            </DataListCell>,
                            <DataListCell key="score">
                              Score: <strong>{result.compatibilityScore}%</strong>
                            </DataListCell>,
                            <DataListCell key="action">
                              <Button
                                variant="primary"
                                onClick={() => handleDownload(result)}
                                isDisabled={downloading[result.serviceId]}
                                icon={<DownloadIcon />}
                              >
                                {downloading[result.serviceId] ? t('download.btnDownloading') : t('download.btnDownloadZip')}
                              </Button>
                            </DataListCell>,
                          ]}
                        />
                      </DataListItemRow>
                    </DataListItem>
                  ))}
                </DataList>
              </CardBody>
            </Card>
          </StackItem>

          <StackItem>
            <Card>
              <CardBody>
                <Title headingLevel="h3" size="lg">{t('download.nextStepsTitle')}</Title>
                <ol style={{ marginTop: '12px', paddingLeft: '20px', lineHeight: '2' }}>
                  <li>{t('download.step1')}</li>
                  <li dangerouslySetInnerHTML={{ __html: t('download.step2') }} />
                  <li>{t('download.step3')}</li>
                  <li dangerouslySetInnerHTML={{ __html: t('download.step4') }} />
                  <li>{t('download.step5')}</li>
                </ol>
              </CardBody>
            </Card>
          </StackItem>

          <StackItem>
            <Button variant="secondary" onClick={() => navigate('/validate')}>{t('download.btnBack')}</Button>
          </StackItem>
        </Stack>
      </PageSection>
    </>
  );
};

export default DownloadPage;
