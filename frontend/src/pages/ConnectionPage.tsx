import React, { useState } from 'react';
import {
  PageSection,
  PageSectionVariants,
  Title,
  Card,
  CardBody,
  Form,
  FormGroup,
  TextInput,
  InputGroup,
  InputGroupItem,
  ActionGroup,
  Button,
  Alert,
  AlertVariant,
  Spinner,
  DescriptionList,
  DescriptionListGroup,
  DescriptionListTerm,
  DescriptionListDescription,
} from '@patternfly/react-core';
import { CheckCircleIcon, EyeIcon, EyeSlashIcon } from '@patternfly/react-icons';
import { useTranslation } from 'react-i18next';
import { connectionApi } from '../api/client';
import { AppState } from '../App';
import { useNavigate } from 'react-router-dom';

interface Props {
  appState: AppState;
  setAppState: React.Dispatch<React.SetStateAction<AppState>>;
}

const ConnectionPage: React.FC<Props> = ({ appState, setAppState }) => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [url, setUrl] = useState(appState.connection.url);
  const [accessToken, setAccessToken] = useState(appState.connection.accessToken);
  const [tenant, setTenant] = useState(appState.connection.tenant || '');
  const [namespace, setNamespace] = useState(appState.namespace);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);
  const [showToken, setShowToken] = useState(false);

  const handleTest = async () => {
    setLoading(true);
    setError(null);
    setSuccess(false);
    try {
      await connectionApi.test({ url, accessToken, tenant });
      setSuccess(true);
      setAppState(prev => ({
        ...prev,
        connection: { url, accessToken, tenant, connected: true },
        namespace,
      }));
    } catch (e: any) {
      setError(e.response?.data?.message || t('connection.errorDefault'));
      setAppState(prev => ({
        ...prev,
        connection: { url, accessToken, tenant, connected: false },
      }));
    } finally {
      setLoading(false);
    }
  };

  return (
    <>
      <PageSection variant={PageSectionVariants.light}>
        <Title headingLevel="h1" size="2xl">{t('connection.title')}</Title>
        <p style={{ marginTop: '8px', color: '#6a6e73' }}>
          {t('connection.description')}
        </p>
      </PageSection>
      <PageSection>
        <Card>
          <CardBody>
            {error && (
              <Alert variant={AlertVariant.danger} title={error} style={{ marginBottom: '16px' }} />
            )}
            {success && (
              <Alert
                variant={AlertVariant.success}
                title={t('connection.successAlert')}
                style={{ marginBottom: '16px' }}
                actionLinks={
                  <Button variant="link" onClick={() => navigate('/services')}>
                    {t('connection.goToApiList')}
                  </Button>
                }
              />
            )}
            <Form>
              <FormGroup label="3scale URL" isRequired fieldId="url"
                helperText={t('connection.urlHelper')}>
                <TextInput
                  id="url"
                  type="url"
                  value={url}
                  onChange={(_e, val) => setUrl(val)}
                  placeholder="https://your-admin.3scale.net"
                  isRequired
                />
              </FormGroup>
              <FormGroup
                label={t('connection.labelToken')}
                isRequired
                fieldId="token"
                helperText={
                  <span>
                    {t('connection.tokenHelper')}
                    {' — '}
                    <a
                      href="https://3scale-admin.apps.cluster-ghj25.ghj25.sandbox5408.opentlc.com/p/admin/user/access_tokens"
                      target="_blank"
                      rel="noopener noreferrer"
                      style={{ color: '#0066cc' }}
                    >
                      {t('connection.openTokenPage')}
                    </a>
                  </span>
                }
              >
                <InputGroup>
                  <InputGroupItem isFill>
                    <TextInput
                      id="token"
                      type={showToken ? 'text' : 'password'}
                      value={accessToken}
                      onChange={(_e, val) => setAccessToken(val)}
                      placeholder={t('connection.tokenPlaceholder')}
                      isRequired
                      autoComplete="off"
                    />
                  </InputGroupItem>
                  <InputGroupItem>
                    <Button
                      variant="control"
                      onClick={() => setShowToken(!showToken)}
                      aria-label={showToken ? t('connection.hideToken') : t('connection.showToken')}
                    >
                      {showToken ? <EyeSlashIcon /> : <EyeIcon />}
                    </Button>
                  </InputGroupItem>
                </InputGroup>
              </FormGroup>
              <FormGroup label={t('connection.labelTenant')} fieldId="tenant"
                helperText={t('connection.tenantHelper')}>
                <TextInput
                  id="tenant"
                  value={tenant}
                  onChange={(_e, val) => setTenant(val)}
                  placeholder={t('connection.tenantPlaceholder')}
                />
              </FormGroup>
              <FormGroup label={t('connection.labelNamespace')} isRequired fieldId="namespace"
                helperText={t('connection.namespaceHelper')}>
                <TextInput
                  id="namespace"
                  value={namespace}
                  onChange={(_e, val) => setNamespace(val)}
                  placeholder="default"
                  isRequired
                />
              </FormGroup>
              <ActionGroup>
                <Button
                  variant="primary"
                  onClick={handleTest}
                  isDisabled={loading || !url || !accessToken}
                >
                  {loading ? <><Spinner size="sm" /> {t('connection.btnTesting')}</> : t('connection.btnTest')}
                </Button>
                {success && (
                  <Button variant="secondary" onClick={() => navigate('/services')}>
                    {t('connection.btnNext')}
                  </Button>
                )}
              </ActionGroup>
            </Form>
          </CardBody>
        </Card>
        {appState.connection.connected && (
          <Card style={{ marginTop: '16px' }}>
            <CardBody>
              <Title headingLevel="h3" size="lg">
                <CheckCircleIcon color="green" /> {t('connection.infoTitle')}
              </Title>
              <DescriptionList style={{ marginTop: '16px' }}>
                <DescriptionListGroup>
                  <DescriptionListTerm>3scale URL</DescriptionListTerm>
                  <DescriptionListDescription>{appState.connection.url}</DescriptionListDescription>
                </DescriptionListGroup>
                <DescriptionListGroup>
                  <DescriptionListTerm>Tenant</DescriptionListTerm>
                  <DescriptionListDescription>{appState.connection.tenant || '-'}</DescriptionListDescription>
                </DescriptionListGroup>
                <DescriptionListGroup>
                  <DescriptionListTerm>Namespace</DescriptionListTerm>
                  <DescriptionListDescription>{appState.namespace}</DescriptionListDescription>
                </DescriptionListGroup>
              </DescriptionList>
            </CardBody>
          </Card>
        )}
      </PageSection>
    </>
  );
};

export default ConnectionPage;
