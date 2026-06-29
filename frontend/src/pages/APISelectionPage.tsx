import React, { useState, useEffect } from 'react';
import {
  PageSection,
  PageSectionVariants,
  Title,
  Card,
  CardBody,
  DataList,
  DataListItem,
  DataListItemRow,
  DataListCheck,
  DataListItemCells,
  DataListCell,
  Button,
  Alert,
  Spinner,
  Badge,
  Toolbar,
  ToolbarContent,
  ToolbarItem,
  SearchInput,
  EmptyState,
  EmptyStateIcon,
  EmptyStateBody,
} from '@patternfly/react-core';
import { CubesIcon } from '@patternfly/react-icons';
import { useTranslation } from 'react-i18next';
import { servicesApi } from '../api/client';
import { ApiService } from '../api/types';
import { AppState } from '../App';
import { useNavigate } from 'react-router-dom';

interface Props {
  appState: AppState;
  setAppState: React.Dispatch<React.SetStateAction<AppState>>;
}

const APISelectionPage: React.FC<Props> = ({ appState, setAppState }) => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [services, setServices] = useState<ApiService[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(
    new Set(appState.selectedServices.map(s => s.id))
  );
  const [search, setSearch] = useState('');

  useEffect(() => {
    if (appState.connection.connected) {
      loadServices();
    }
  }, []);

  const loadServices = async () => {
    setLoading(true);
    setError(null);
    try {
      const resp = await servicesApi.list(appState.connection.url, appState.connection.accessToken);
      setServices(resp.data);
    } catch (e: any) {
      setError(t('apiSelection.errorFetch', { message: e.response?.data || e.message }));
    } finally {
      setLoading(false);
    }
  };

  const toggleSelect = (id: string) => {
    setSelectedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const handleNext = () => {
    const selected = services.filter(s => selectedIds.has(s.id));
    setAppState(prev => ({ ...prev, selectedServices: selected }));
    navigate('/compatibility');
  };

  const filtered = services.filter(s =>
    s.name.toLowerCase().includes(search.toLowerCase()) ||
    (s.systemName || '').toLowerCase().includes(search.toLowerCase())
  );

  if (!appState.connection.connected) {
    return (
      <PageSection>
        <Alert variant="warning" title={t('apiSelection.warningTitle')}>
          <Button variant="link" onClick={() => navigate('/')}>{t('apiSelection.goToConnection')}</Button>
        </Alert>
      </PageSection>
    );
  }

  return (
    <>
      <PageSection variant={PageSectionVariants.light}>
        <Title headingLevel="h1" size="2xl">{t('apiSelection.title')}</Title>
        <p style={{ marginTop: '8px', color: '#6a6e73' }}>
          {t('apiSelection.description')}
        </p>
      </PageSection>
      <PageSection>
        <Card>
          <CardBody>
            {error && <Alert variant="danger" title={error} style={{ marginBottom: '16px' }} />}
            <Toolbar>
              <ToolbarContent>
                <ToolbarItem>
                  <SearchInput
                    placeholder={t('apiSelection.searchPlaceholder')}
                    value={search}
                    onChange={(_e, val) => setSearch(val)}
                    onClear={() => setSearch('')}
                  />
                </ToolbarItem>
                <ToolbarItem>
                  <Button variant="secondary" onClick={loadServices} isDisabled={loading}>
                    {loading ? <Spinner size="sm" /> : t('apiSelection.btnRefresh')}
                  </Button>
                </ToolbarItem>
                <ToolbarItem align={{ default: 'alignRight' }}>
                  <Badge>{t('apiSelection.selectedCount', { count: selectedIds.size })}</Badge>
                </ToolbarItem>
              </ToolbarContent>
            </Toolbar>

            {loading ? (
              <div style={{ textAlign: 'center', padding: '40px' }}>
                <Spinner size="xl" />
                <p>{t('apiSelection.loading')}</p>
              </div>
            ) : filtered.length === 0 ? (
              <EmptyState>
                <EmptyStateIcon icon={CubesIcon} />
                <Title headingLevel="h4" size="lg">{t('apiSelection.emptyTitle')}</Title>
                <EmptyStateBody>{t('apiSelection.emptyBody')}</EmptyStateBody>
              </EmptyState>
            ) : (
              <DataList aria-label={t('apiSelection.ariaLabel')} style={{ marginTop: '16px' }}>
                {filtered.map(service => (
                  <DataListItem key={service.id} aria-labelledby={`service-${service.id}`}>
                    <DataListItemRow>
                      <DataListCheck
                        aria-labelledby={`service-${service.id}`}
                        checked={selectedIds.has(service.id)}
                        onChange={() => toggleSelect(service.id)}
                        id={`check-${service.id}`}
                        name={`check-${service.id}`}
                      />
                      <DataListItemCells
                        dataListCells={[
                          <DataListCell key="name" width={2}>
                            <span id={`service-${service.id}`} style={{ fontWeight: 'bold' }}>
                              {service.name}
                            </span>
                            <br />
                            <small style={{ color: '#6a6e73' }}>{service.systemName}</small>
                          </DataListCell>,
                          <DataListCell key="state">
                            <Badge
                              isRead={service.state !== 'published'}
                              style={{ backgroundColor: service.state === 'published' ? '#3e8635' : undefined }}
                            >
                              {service.state || 'unknown'}
                            </Badge>
                          </DataListCell>,
                          <DataListCell key="auth">
                            {service.authentication?.type || 'N/A'}
                          </DataListCell>,
                          <DataListCell key="backends">
                            {t('apiSelection.backendCount', { count: service.backends?.length || 0 })}
                          </DataListCell>,
                        ]}
                      />
                    </DataListItemRow>
                  </DataListItem>
                ))}
              </DataList>
            )}

            <div style={{ marginTop: '24px', display: 'flex', gap: '8px' }}>
              <Button variant="secondary" onClick={() => navigate('/')}>{t('apiSelection.btnBack')}</Button>
              <Button
                variant="primary"
                onClick={handleNext}
                isDisabled={selectedIds.size === 0}
              >
                {t('apiSelection.btnNext', { count: selectedIds.size })}
              </Button>
            </div>
          </CardBody>
        </Card>
      </PageSection>
    </>
  );
};

export default APISelectionPage;
