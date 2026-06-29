import React, { useState } from 'react';
import {
  PageSection,
  PageSectionVariants,
  Title,
  Card,
  CardBody,
  CardTitle,
  Button,
  Alert,
  Spinner,
  Stack,
  StackItem,
  Label,
} from '@patternfly/react-core';
import { CheckCircleIcon, ExclamationTriangleIcon, TimesCircleIcon } from '@patternfly/react-icons';
import { useTranslation } from 'react-i18next';
import { validationApi } from '../api/client';
import { ValidationResult } from '../api/types';
import { AppState } from '../App';
import { useNavigate } from 'react-router-dom';

interface Props {
  appState: AppState;
  setAppState: React.Dispatch<React.SetStateAction<AppState>>;
}

const StatusIcon: React.FC<{ status: string }> = ({ status }) => {
  switch (status) {
    case 'OK': return <CheckCircleIcon color="#3e8635" />;
    case 'WARNING': return <ExclamationTriangleIcon color="#f0ab00" />;
    case 'ERROR': return <TimesCircleIcon color="#c9190b" />;
    default: return null;
  }
};

const ValidationPage: React.FC<Props> = ({ appState }) => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [results, setResults] = useState<{ service: string; result: ValidationResult }[]>([]);
  const [error, setError] = useState<string | null>(null);

  const handleValidate = async () => {
    setLoading(true);
    setError(null);
    const all: { service: string; result: ValidationResult }[] = [];

    for (const convResult of appState.conversionResults) {
      if (!convResult.yamlFiles) continue;
      try {
        const resp = await validationApi.validate(convResult.yamlFiles);
        all.push({ service: convResult.serviceName, result: resp.data });
      } catch (e: any) {
        all.push({
          service: convResult.serviceName,
          result: {
            valid: false,
            items: [{ check: 'API Error', status: 'ERROR', message: e.message }],
          },
        });
      }
    }
    setResults(all);
    setLoading(false);
  };

  if (appState.conversionResults.length === 0) {
    return (
      <PageSection>
        <Alert variant="warning" title={t('validation.warningTitle')}>
          <Button variant="link" onClick={() => navigate('/convert')}>{t('validation.goToConvert')}</Button>
        </Alert>
      </PageSection>
    );
  }

  return (
    <>
      <PageSection variant={PageSectionVariants.light}>
        <Title headingLevel="h1" size="2xl">{t('validation.title')}</Title>
        <p style={{ marginTop: '8px', color: '#6a6e73' }}>
          {t('validation.description')}
        </p>
      </PageSection>
      <PageSection>
        <Stack hasGutter>
          <StackItem>
            <Card>
              <CardBody>
                <p>{t('validation.checkItems')}</p>
                <ul style={{ marginTop: '8px', paddingLeft: '20px' }}>
                  <li>{t('validation.checkYaml')}</li>
                  <li>{t('validation.checkCrd')}</li>
                  <li>{t('validation.checkNamespace')}</li>
                  <li>{t('validation.checkRef')}</li>
                  <li>{t('validation.checkSecret')}</li>
                </ul>
                {error && <Alert variant="danger" title={error} style={{ marginTop: '16px' }} />}
                <div style={{ marginTop: '16px' }}>
                  <Button variant="primary" onClick={handleValidate} isDisabled={loading}>
                    {loading ? <><Spinner size="sm" /> {t('validation.btnRunning')}</> : t('validation.btnRun')}
                  </Button>
                </div>
              </CardBody>
            </Card>
          </StackItem>

          {results.map(({ service, result }) => (
            <StackItem key={service}>
              <Card>
                <CardTitle>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                    {result.valid
                      ? <CheckCircleIcon color="#3e8635" size="md" />
                      : <TimesCircleIcon color="#c9190b" size="md" />}
                    <Title headingLevel="h3" size="lg">{service}</Title>
                    <Label color={result.valid ? 'green' : 'red'}>
                      {result.valid ? 'VALID' : 'INVALID'}
                    </Label>
                  </div>
                </CardTitle>
                <CardBody>
                  <Stack hasGutter>
                    {result.items.map((item, i) => (
                      <StackItem key={i}>
                        <div style={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: '12px',
                          padding: '8px 12px',
                          borderRadius: '4px',
                          background: item.status === 'OK' ? '#f4f4f4' :
                            item.status === 'WARNING' ? '#fff7e6' : '#fdf2f2',
                        }}>
                          <StatusIcon status={item.status} />
                          <div>
                            <strong>{item.check}</strong>
                            <span style={{ marginLeft: '8px', color: '#6a6e73' }}>{item.message}</span>
                          </div>
                        </div>
                      </StackItem>
                    ))}
                  </Stack>
                </CardBody>
              </Card>
            </StackItem>
          ))}

          <StackItem>
            <div style={{ display: 'flex', gap: '8px' }}>
              <Button variant="secondary" onClick={() => navigate('/yaml')}>{t('validation.btnBack')}</Button>
              <Button
                variant="primary"
                onClick={() => navigate('/download')}
                isDisabled={results.length === 0}
              >
                {t('validation.btnNext')}
              </Button>
            </div>
          </StackItem>
        </Stack>
      </PageSection>
    </>
  );
};

export default ValidationPage;
