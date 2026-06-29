import React, { useState } from 'react';
import {
  PageSection,
  PageSectionVariants,
  Title,
  Card,
  CardBody,
  Tabs,
  Tab,
  TabTitleText,
  Button,
  Alert,
  Select,
  SelectOption,
  MenuToggle,
} from '@patternfly/react-core';
import { useTranslation } from 'react-i18next';
import { AppState } from '../App';
import { useNavigate } from 'react-router-dom';

interface Props {
  appState: AppState;
  setAppState: React.Dispatch<React.SetStateAction<AppState>>;
}

const YAMLViewerPage: React.FC<Props> = ({ appState }) => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [activeService, setActiveService] = useState(0);
  const [activeTab, setActiveTab] = useState(0);
  const [selectOpen, setSelectOpen] = useState(false);

  const results = appState.conversionResults.filter(r => r.yamlFiles);

  if (results.length === 0) {
    return (
      <PageSection>
        <Alert variant="warning" title={t('yamlViewer.warningTitle')}>
          <Button variant="link" onClick={() => navigate('/convert')}>{t('yamlViewer.goToConvert')}</Button>
        </Alert>
      </PageSection>
    );
  }

  const current = results[activeService];
  const files = Object.entries(current.yamlFiles || {});

  return (
    <>
      <PageSection variant={PageSectionVariants.light}>
        <Title headingLevel="h1" size="2xl">{t('yamlViewer.title')}</Title>
        <p style={{ marginTop: '8px', color: '#6a6e73' }}>
          {t('yamlViewer.description')}
        </p>
      </PageSection>
      <PageSection>
        <Card>
          <CardBody>
            {results.length > 1 && (
              <div style={{ marginBottom: '16px' }}>
                <Select
                  isOpen={selectOpen}
                  onOpenChange={setSelectOpen}
                  selected={current.serviceName}
                  onSelect={(_e, val) => {
                    const idx = results.findIndex(r => r.serviceName === val);
                    if (idx >= 0) setActiveService(idx);
                    setSelectOpen(false);
                    setActiveTab(0);
                  }}
                  toggle={(ref) => (
                    <MenuToggle ref={ref} onClick={() => setSelectOpen(!selectOpen)}>
                      {current.serviceName}
                    </MenuToggle>
                  )}
                >
                  {results.map((r, i) => (
                    <SelectOption key={i} value={r.serviceName}>{r.serviceName}</SelectOption>
                  ))}
                </Select>
              </div>
            )}

            <Tabs
              activeKey={activeTab}
              onSelect={(_e, key) => setActiveTab(Number(key))}
            >
              {files.map(([filename], i) => (
                <Tab key={i} eventKey={i} title={<TabTitleText>{filename}</TabTitleText>}>
                  <div style={{ marginTop: '16px' }}>
                    <pre style={{
                      background: '#1b1d21',
                      color: '#d4d4d4',
                      padding: '16px',
                      borderRadius: '4px',
                      overflow: 'auto',
                      maxHeight: '500px',
                      fontSize: '13px',
                      fontFamily: 'monospace',
                    }}>
                      {files[i][1]}
                    </pre>
                  </div>
                </Tab>
              ))}
            </Tabs>

            <div style={{ marginTop: '24px', display: 'flex', gap: '8px' }}>
              <Button variant="secondary" onClick={() => navigate('/convert')}>{t('yamlViewer.btnBack')}</Button>
              <Button variant="primary" onClick={() => navigate('/validate')}>
                {t('yamlViewer.btnNext')}
              </Button>
            </div>
          </CardBody>
        </Card>
      </PageSection>
    </>
  );
};

export default YAMLViewerPage;
