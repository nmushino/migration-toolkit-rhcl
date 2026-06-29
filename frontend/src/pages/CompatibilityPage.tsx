import React, { useState, useEffect } from 'react';
import {
  PageSection,
  PageSectionVariants,
  Title,
  Card,
  CardBody,
  CardTitle,
  Grid,
  GridItem,
  Progress,
  ProgressVariant,
  Label,
  Button,
  Alert,
  Spinner,
  Stack,
  StackItem,
} from '@patternfly/react-core';
import { CheckCircleIcon, ExclamationTriangleIcon, TimesCircleIcon } from '@patternfly/react-icons';
import { useTranslation } from 'react-i18next';
import { servicesApi } from '../api/client';
import { CompatibilityResult, CompatibilityItem } from '../api/types';
import { AppState } from '../App';
import { useNavigate } from 'react-router-dom';

interface Props {
  appState: AppState;
  setAppState: React.Dispatch<React.SetStateAction<AppState>>;
}

const StatusIcon: React.FC<{ status: string }> = ({ status }) => {
  switch (status) {
    case 'SUPPORTED': return <CheckCircleIcon color="#3e8635" />;
    case 'WARNING': return <ExclamationTriangleIcon color="#f0ab00" />;
    case 'UNSUPPORTED': return <TimesCircleIcon color="#c9190b" />;
    default: return null;
  }
};

const ScoreColor = (score: number): ProgressVariant => {
  if (score >= 80) return ProgressVariant.success;
  if (score >= 50) return ProgressVariant.warning;
  return ProgressVariant.danger;
};

const CompatibilityPage: React.FC<Props> = ({ appState, setAppState }) => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [results, setResults] = useState<CompatibilityResult[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (appState.selectedServices.length > 0) {
      checkAll();
    }
  }, []);

  const checkAll = async () => {
    setLoading(true);
    setError(null);
    const all: CompatibilityResult[] = [];
    for (const service of appState.selectedServices) {
      try {
        const resp = await servicesApi.checkCompatibility(
          service.id, appState.connection.url, appState.connection.accessToken
        );
        all.push(resp.data);
      } catch {
        all.push({
          serviceId: service.id,
          serviceName: service.name,
          score: 0,
          level: 'LOW',
          items: [{ name: 'Error', status: 'UNSUPPORTED', message: t('compatibility.errorCheck') }],
        });
      }
    }
    setResults(all);
    setLoading(false);
  };

  if (appState.selectedServices.length === 0) {
    return (
      <PageSection>
        <Alert variant="warning" title={t('compatibility.warningTitle')}>
          <Button variant="link" onClick={() => navigate('/services')}>{t('compatibility.goToApiList')}</Button>
        </Alert>
      </PageSection>
    );
  }

  return (
    <>
      <PageSection variant={PageSectionVariants.light}>
        <Title headingLevel="h1" size="2xl">{t('compatibility.title')}</Title>
        <p style={{ marginTop: '8px', color: '#6a6e73' }}>
          {t('compatibility.description', { count: appState.selectedServices.length })}
        </p>
      </PageSection>
      <PageSection>
        {loading ? (
          <div style={{ textAlign: 'center', padding: '60px' }}>
            <Spinner size="xl" />
            <p style={{ marginTop: '16px' }}>{t('compatibility.loading')}</p>
          </div>
        ) : error ? (
          <Alert variant="danger" title={error} />
        ) : (
          <Stack hasGutter>
            {results.map(result => (
              <StackItem key={result.serviceId}>
                <Card>
                  <CardTitle>
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                      <Title headingLevel="h3" size="lg">{result.serviceName}</Title>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                        <Label
                          color={result.level === 'HIGH' ? 'green' : result.level === 'MEDIUM' ? 'orange' : 'red'}
                        >
                          {result.level}
                        </Label>
                        <span style={{ fontSize: '1.5rem', fontWeight: 'bold' }}>
                          Migration Score: {result.score}%
                        </span>
                      </div>
                    </div>
                    <Progress
                      value={result.score}
                      variant={ScoreColor(result.score)}
                      style={{ marginTop: '8px' }}
                    />
                  </CardTitle>
                  <CardBody>
                    <Grid hasGutter sm={12} md={6} lg={4}>
                      {result.items.map((item, i) => (
                        <GridItem key={i}>
                          <div style={{
                            display: 'flex',
                            alignItems: 'flex-start',
                            gap: '8px',
                            padding: '8px',
                            borderRadius: '4px',
                            background: item.status === 'SUPPORTED' ? '#f4f4f4' :
                              item.status === 'WARNING' ? '#fff7e6' : '#fdf2f2',
                          }}>
                            <StatusIcon status={item.status} />
                            <div>
                              <div style={{ fontWeight: 'bold', fontSize: '0.9rem' }}>{item.name}</div>
                              <div style={{ fontSize: '0.8rem', color: '#6a6e73' }}>{item.message}</div>
                            </div>
                          </div>
                        </GridItem>
                      ))}
                    </Grid>
                  </CardBody>
                </Card>
              </StackItem>
            ))}
          </Stack>
        )}

        <div style={{ marginTop: '24px', display: 'flex', gap: '8px' }}>
          <Button variant="secondary" onClick={() => navigate('/services')}>{t('compatibility.btnBack')}</Button>
          <Button variant="secondary" onClick={checkAll} isDisabled={loading}>{t('compatibility.btnRecheck')}</Button>
          <Button
            variant="primary"
            onClick={() => navigate('/convert')}
            isDisabled={results.length === 0}
          >
            {t('compatibility.btnNext')}
          </Button>
        </div>
      </PageSection>
    </>
  );
};

export default CompatibilityPage;
