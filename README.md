# Red Hat Connectivity Link Migration Toolkit

3scale から Red Hat Connectivity Link へ移行するための GUI ツールキットです。  
Quarkus バックエンド + React/PatternFly フロントエンド で構成されています。

---

## 目次 / Table of Contents

- [前提条件・必要ツール](#前提条件必要ツール)
- [クイックスタート](#クイックスタート)
- [アーキテクチャ](#アーキテクチャ)
- [処理フロー](#処理フロー)
- [機能一覧](#機能一覧)
- [ディレクトリ構成](#ディレクトリ構成)
- [API一覧](#api一覧)
- [コンバータポリシー](#コンバータポリシー)
- [データモデル](#データモデル)
- [国際化対応 (i18n)](#国際化対応-i18n)
- [English Documentation](#english-documentation)

---

## 前提条件・必要ツール

### ローカル開発環境

| ツール | バージョン | 用途 |
|--------|------------|------|
| Java (OpenJDK) | 21 以上 | バックエンドビルド |
| Apache Maven | 3.9.x 以上 | バックエンドビルド |
| Node.js | 18 以上 | フロントエンドビルド |
| npm | 9 以上 | フロントエンド依存関係管理 |
| Docker / Podman | 最新版 | コンテナイメージビルド（ローカル検証時） |

### OpenShift クラスター

| ツール / コンポーネント | バージョン | 用途 |
|------------------------|------------|------|
| OpenShift Container Platform | 4.14 以上 | デプロイ先クラスター |
| `oc` CLI | クラスターに対応したバージョン | クラスター操作 |
| CrunchyData PostgreSQL Operator | 最新版 | データベース管理（OperatorHub から事前インストール） |
| Red Hat Connectivity Link (Kuadrant) | 最新版 | 移行対象コンポーネント |

> **注意**: CrunchyData PostgreSQL Operator は `openshift-operators` Namespace へ  
> 事前にインストールしてください。インストールスクリプトが自動検出します。

### 3scale 環境

- 3scale Admin Portal への接続 URL と Personal Access Token

---

## クイックスタート

### OpenShift へのフルデプロイ

```bash
# 任意の Namespace を指定してインストール（デフォルト: migration-toolkit）
NAMESPACE=migration-toolkit ./deploy/install.sh

# バックエンドのみデプロイ
NAMESPACE=migration-toolkit ./deploy/install.sh --backend-only

# フロントエンドのみデプロイ
NAMESPACE=migration-toolkit ./deploy/install.sh --frontend-only

# DB のみセットアップ
NAMESPACE=migration-toolkit ./deploy/install.sh --db-only
```

インストールスクリプトが以下を自動処理します:

1. Namespace 作成
2. CrunchyData PostgreSQL Operator インストール待機
3. PostgreSQL クラスター作成
4. バックエンド Maven ビルド → S2I → デプロイ
5. フロントエンド npm ビルド → S2I (nginx) → デプロイ
6. アクセス URL 表示

### ローカル開発

```bash
# バックエンド起動（PostgreSQL が localhost:5432 で起動していること）
cd backend
mvn quarkus:dev

# フロントエンド起動（別ターミナル）
cd frontend
npm install --legacy-peer-deps
VITE_API_URL=http://localhost:8080 npm run dev
```

---

## アーキテクチャ

```
               +----------------------+
               |      Web UI          |
               |  (React/PatternFly)  |
               +----------+-----------+
                          |
                    REST API (JSON)
                          |
        +-----------------+------------------+
        |     Quarkus Backend (Java 21)      |
        |                                    |
        | ① 3scale Export                    |
        | ② Parser / Compatibility Checker   |
        | ③ Converter (YAML Generator)       |
        | ④ Validation                       |
        | ⑤ Package Download (ZIP)           |
        | ⑥ Import / Apply to Cluster        |
        | ⑦ Conversion History               |
        +-----------------+------------------+
                    |               |
       from-3scale-to-connectivity  PostgreSQL
            -link (Adapter)         (CrunchyData)
                    |
          Connectivity Link YAML
         (Gateway / HTTPRoute / AuthPolicy /
          RateLimitPolicy / Secret / ConfigMap)
```

**主要技術スタック**

| レイヤー | 技術 |
|---------|------|
| フロントエンド | React 18, PatternFly 5, Vite, TypeScript, react-i18next |
| バックエンド | Quarkus 3.8.4 (Java 21), RESTEasy Reactive, Hibernate ORM Panache |
| データベース | PostgreSQL (CrunchyData Operator 管理) |
| Kubernetes クライアント | Fabric8 Kubernetes Client 6.7.x |
| OpenAPI | SmallRye OpenAPI + Swagger UI (`/q/swagger-ui`) |
| マイグレーション | Flyway |
| デプロイ | OpenShift S2I, nginx (フロントエンド静的配信) |

---

## 処理フロー

```
① 3scale接続設定 (URL / Access Token / Tenant / Namespace 入力)
      ↓
② API一覧取得 (Service / Backend / MappingRule / Metrics / Policies / Authentication)
      ↓
③ 変換対象選択
      ↓
④ Compatibility Check (スコアリング: JWT / Rewrite / Lua Policy / SOAP など)
      ↓
⑤ YAML生成 (from-3scale-to-connectivity-link アダプタ経由)
      ↓
⑥ YAMLプレビュー / 編集
      ↓
⑦ Validation (YAML構文 / CRD / Namespace / Secret / Reference 整合性)
      ↓
⑧ ZIPダウンロード
      ↓
⑨ ZIP Import → Connectivity Link 画面から適用 (oc apply / 直接適用)
      ↓
⑩ 変換履歴確認 / 過去ZIPの再ダウンロード
```

---

## 機能一覧

### 1. 3scale 接続設定

画面入力項目:
- 3scale Admin Portal URL
- Personal Access Token
- Tenant 名（オプション）
- 対象 OpenShift Namespace

バックエンドが呼び出す 3scale API:
```
GET /admin/api/services.json
GET /admin/api/backends.json
GET /admin/api/proxy_configs
GET /admin/api/policies
```

### 2. API 一覧取得

取得情報:
- Service 基本情報 (ID / 名称 / デプロイオプション)
- Backend (Private Endpoint)
- Mapping Rules
- Metrics
- Policies
- Authentication 方式 (API Key / OIDC / JWT など)

### 3. Compatibility Check

各 API ポリシー・機能を Connectivity Link でサポートできるか判定:

| 判定 | 意味 |
|------|------|
| ✔ SUPPORTED | そのまま移行可能 |
| ⚠ WARNING | 手動調整が必要 |
| × UNSUPPORTED | 非対応（要カスタム対応） |

Migration Score (0–100%) でトータルの移行難易度を数値化します。

### 4. YAML 生成

`from-3scale-to-connectivity-link` アダプタを経由して以下のリソースを生成:

```
{service-name}/
  gateway.yaml       # Kuadrant Gateway
  httproute.yaml     # HTTPRoute
  policy.yaml        # AuthPolicy / RateLimitPolicy
  secret.yaml        # 認証情報 (REPLACE_ME プレースホルダー)
  configmap.yaml     # 設定値
  README.md
```

> `secret.yaml` 内の `REPLACE_ME` は手動で実際の値に置き換えてください。

### 5. YAML プレビュー

生成した全ファイルをブラウザ上でコードビューア形式で確認できます。

### 6. Validation

生成 YAML に対して以下を自動チェック:
- ✔ YAML 構文チェック
- ✔ CRD (API バージョン) チェック
- ✔ Namespace 設定チェック
- ✔ リソース参照整合性チェック
- ✔ Secret プレースホルダーチェック

### 7. ZIP ダウンロード

全ファイルを ZIP アーカイブとしてダウンロード。  
ファイル名例: `customer-api.zip`

### 8. ZIP Import / Connectivity Link 設定適用

アップロードした ZIP の YAML を:
- ブラウザ上でプレビュー・編集
- Namespace を一括置換
- `oc apply` コマンドでクラスターへ直接適用（バックエンド経由）

AuthPolicy など Kuadrant CRD への適用は Server-Side Apply (`PatchType.SERVER_SIDE_APPLY`) で処理します。

### 9. 変換履歴

- 過去の変換実行を日時・サービス名・ステータス・互換性スコアとともに一覧表示
- 各エントリから ZIP を再ダウンロード可能（過去の状態への復元）
- ステータス: `完了 / 失敗 / 処理中`
- ダウンロードファイル名: `{サービス名}-{生成日時}.zip`

---

## ディレクトリ構成

```
migration-toolkit-rhcl/
├── backend/                    # Quarkus バックエンド (Java 21)
│   └── src/main/java/com/example/migrationtool/
│       ├── controller/         # REST エンドポイント
│       │   ├── ConnectionController.java
│       │   ├── ServiceController.java
│       │   ├── ConversionController.java
│       │   ├── ValidationController.java
│       │   ├── PackageController.java  (ZIP ダウンロード / 履歴ZIP)
│       │   ├── ApplyController.java    (oc apply 相当)
│       │   ├── ImportController.java
│       │   └── HistoryController.java
│       ├── service/            # ビジネスロジック
│       ├── entity/             # Panache エンティティ (JPA)
│       │   ├── ProjectEntity.java
│       │   └── ConversionHistoryEntity.java
│       ├── model/              # DTO / ドメインモデル
│       ├── util/
│       │   └── Messages.java   # i18n ResourceBundle ラッパー
│       └── resources/
│           ├── application.properties
│           ├── messages_ja.properties  # バックエンド日本語メッセージ
│           └── messages_en.properties  # バックエンド英語メッセージ
├── frontend/                   # React + PatternFly フロントエンド
│   └── src/
│       ├── pages/              # 各画面コンポーネント
│       │   ├── ConnectionPage.tsx
│       │   ├── APISelectionPage.tsx
│       │   ├── CompatibilityPage.tsx
│       │   ├── ConversionPage.tsx
│       │   ├── YAMLViewerPage.tsx
│       │   ├── ValidationPage.tsx
│       │   ├── DownloadPage.tsx
│       │   ├── ImportPage.tsx
│       │   └── HistoryPage.tsx
│       ├── api/
│       │   ├── client.ts       # Axios API クライアント
│       │   └── types.ts        # TypeScript 型定義
│       ├── locales/
│       │   ├── ja.json         # 日本語 UI 文字列
│       │   └── en.json         # 英語 UI 文字列
│       ├── i18n.ts             # react-i18next 設定
│       └── App.tsx             # レイアウト・ルーティング
├── deploy/                     # OpenShift プロビジョニング
│   ├── install.sh              # 一括インストールスクリプト
│   ├── backend/                # Backend OpenShift リソース YAML
│   ├── frontend/               # Frontend OpenShift リソース YAML
│   └── postgres/               # PostgreSQL Operator / Cluster YAML
├── from-3scale-to-connectivity-link/  # 変換アダプタ
└── README.md
```

---

## API 一覧

| Method | Path | 説明 |
|--------|------|------|
| POST | `/api/connection/test` | 3scale 接続テスト |
| GET | `/api/services` | API サービス一覧取得 |
| GET | `/api/services/{id}` | サービス詳細取得 |
| GET | `/api/services/{id}/compatibility` | 互換性チェック |
| POST | `/api/convert` | YAML 生成 (変換実行) |
| POST | `/api/validate` | YAML バリデーション |
| POST | `/api/download/zip` | ZIP ダウンロード |
| GET | `/api/download/history/{historyId}` | 履歴 ZIP ダウンロード |
| POST | `/api/apply` | クラスターへ適用 (Server-Side Apply) |
| POST | `/api/import/zip` | ZIP インポート |
| GET | `/api/history` | 変換履歴一覧 |
| GET | `/api/history/{id}` | 変換履歴詳細 |

Swagger UI: `https://<backend-route>/q/swagger-ui`

---

## コンバータポリシー

- `from-3scale-to-connectivity-link` はアダプタとして抽象化し、将来的に差し替え可能にする
- API は REST で統一
- データベースは PostgreSQL (CrunchyData Operator 管理)
- アプリケーションはコンテナ化し OpenShift 上で動作することを前提とする
- プロビジョニングはスクリプト一発で完結（アプリ + DB オペレータ含む）
- 実装言語は Java 21 + Quarkus
- UI は OpenShift コンソールと統一感のある PatternFly 5 を使用
- 変換履歴を DB に保持し、過去状態への復元を可能にする

---

## データモデル

```
Project
  ├── id (PK)
  ├── name
  ├── namespace
  ├── threescaleUrl
  ├── createdAt
  └── ConversionHistory[]
        ├── id (PK)
        ├── serviceId
        ├── serviceName
        ├── status         (COMPLETED / FAILED / IN_PROGRESS)
        ├── compatibilityScore
        ├── yamlContent    (生成 YAML 全文 - ZIP 復元に使用)
        └── createdAt
```

将来的には他の API 管理ツール（AWS API Gateway など）からの変換も  
この汎用モデルで受け入れられるよう設計しています。

---

## 国際化対応 (i18n)

### フロントエンド

- `react-i18next` を使用
- デフォルト言語: **日本語 (ja)**
- `frontend/src/locales/ja.json` / `en.json` で文字列管理
- マストヘッド右端の **JA / EN** タブで実行時に切り替え可能

### バックエンド

- Java 標準 `ResourceBundle` を使用
- `backend/src/main/resources/messages_ja.properties` / `messages_en.properties`
- `Messages` Bean (`@ApplicationScoped`) が各コントローラに注入される

---

---

# English Documentation

## Red Hat Connectivity Link Migration Toolkit

A GUI toolkit for migrating from 3scale to Red Hat Connectivity Link.  
Built with a Quarkus backend and a React/PatternFly frontend.

---

## Prerequisites & Required Tools

### Local Development

| Tool | Version | Purpose |
|------|---------|---------|
| Java (OpenJDK) | 21+ | Backend build |
| Apache Maven | 3.9.x+ | Backend build |
| Node.js | 18+ | Frontend build |
| npm | 9+ | Frontend dependency management |
| Docker / Podman | Latest | Container image build (local testing) |

### OpenShift Cluster

| Tool / Component | Version | Purpose |
|-----------------|---------|---------|
| OpenShift Container Platform | 4.14+ | Target deployment cluster |
| `oc` CLI | Matching cluster version | Cluster operations |
| CrunchyData PostgreSQL Operator | Latest | Database management (pre-install from OperatorHub) |
| Red Hat Connectivity Link (Kuadrant) | Latest | Migration target component |

> **Note**: Install the CrunchyData PostgreSQL Operator into the `openshift-operators`  
> namespace **before** running the install script. The script detects it automatically.

### 3scale Environment

- 3scale Admin Portal URL and a Personal Access Token

---

## Quick Start

### Full Deploy to OpenShift

```bash
# Deploy to a specific namespace (default: migration-toolkit)
NAMESPACE=migration-toolkit ./deploy/install.sh

# Backend only
NAMESPACE=migration-toolkit ./deploy/install.sh --backend-only

# Frontend only
NAMESPACE=migration-toolkit ./deploy/install.sh --frontend-only

# Database only
NAMESPACE=migration-toolkit ./deploy/install.sh --db-only
```

The install script handles:

1. Namespace creation
2. Waiting for CrunchyData PostgreSQL Operator
3. PostgreSQL cluster creation
4. Backend Maven build → S2I → deployment
5. Frontend npm build → S2I (nginx) → deployment
6. Printing access URLs

### Local Development

```bash
# Start backend (PostgreSQL must be running on localhost:5432)
cd backend
mvn quarkus:dev

# Start frontend (separate terminal)
cd frontend
npm install --legacy-peer-deps
VITE_API_URL=http://localhost:8080 npm run dev
```

---

## Architecture

```
               +----------------------+
               |      Web UI          |
               |  (React/PatternFly)  |
               +----------+-----------+
                          |
                    REST API (JSON)
                          |
        +-----------------+------------------+
        |     Quarkus Backend (Java 21)      |
        |                                    |
        | ① 3scale Export                    |
        | ② Parser / Compatibility Checker   |
        | ③ Converter (YAML Generator)       |
        | ④ Validation                       |
        | ⑤ Package Download (ZIP)           |
        | ⑥ Import / Apply to Cluster        |
        | ⑦ Conversion History               |
        +-----------------+------------------+
                    |               |
       from-3scale-to-connectivity  PostgreSQL
            -link (Adapter)         (CrunchyData)
                    |
          Connectivity Link YAML
         (Gateway / HTTPRoute / AuthPolicy /
          RateLimitPolicy / Secret / ConfigMap)
```

**Technology Stack**

| Layer | Technology |
|-------|-----------|
| Frontend | React 18, PatternFly 5, Vite, TypeScript, react-i18next |
| Backend | Quarkus 3.8.4 (Java 21), RESTEasy Reactive, Hibernate ORM Panache |
| Database | PostgreSQL (managed by CrunchyData Operator) |
| Kubernetes client | Fabric8 Kubernetes Client 6.7.x |
| OpenAPI | SmallRye OpenAPI + Swagger UI (`/q/swagger-ui`) |
| DB Migrations | Flyway |
| Deployment | OpenShift S2I, nginx (frontend static serving) |

---

## Workflow

```
① Configure 3scale connection (URL / Access Token / Tenant / Namespace)
      ↓
② Fetch API list (Service / Backend / MappingRule / Metrics / Policies / Auth)
      ↓
③ Select APIs to migrate
      ↓
④ Compatibility Check (scoring: JWT / Rewrite / Lua Policy / SOAP, etc.)
      ↓
⑤ Generate YAML (via from-3scale-to-connectivity-link adapter)
      ↓
⑥ Preview / edit YAML in browser
      ↓
⑦ Validation (YAML syntax / CRD / Namespace / Secret / Reference)
      ↓
⑧ Download as ZIP
      ↓
⑨ ZIP Import → apply to cluster (oc apply / direct apply via backend)
      ↓
⑩ Review conversion history / re-download past ZIP archives
```

---

## Features

### 1. 3scale Connection Setup

Input fields:
- 3scale Admin Portal URL
- Personal Access Token
- Tenant name (optional)
- Target OpenShift Namespace

3scale APIs called by the backend:
```
GET /admin/api/services.json
GET /admin/api/backends.json
GET /admin/api/proxy_configs
GET /admin/api/policies
```

### 2. API List

Information retrieved:
- Service basics (ID / name / deployment option)
- Backend (Private Endpoint)
- Mapping Rules
- Metrics
- Policies
- Authentication type (API Key / OIDC / JWT, etc.)

### 3. Compatibility Check

Evaluates whether each API policy/feature can be migrated to Connectivity Link:

| Result | Meaning |
|--------|---------|
| ✔ SUPPORTED | Migrates as-is |
| ⚠ WARNING | Manual adjustment required |
| × UNSUPPORTED | Not supported (requires custom handling) |

A Migration Score (0–100%) quantifies the overall migration effort.

### 4. YAML Generation

Resources generated via the `from-3scale-to-connectivity-link` adapter:

```
{service-name}/
  gateway.yaml       # Kuadrant Gateway
  httproute.yaml     # HTTPRoute
  policy.yaml        # AuthPolicy / RateLimitPolicy
  secret.yaml        # Credentials (REPLACE_ME placeholders)
  configmap.yaml     # Configuration values
  README.md
```

> Replace `REPLACE_ME` placeholders in `secret.yaml` with actual values before applying.

### 5. YAML Preview

View all generated files in a code viewer inside the browser.

### 6. Validation

Automated checks on generated YAML:
- ✔ YAML syntax
- ✔ CRD (API version)
- ✔ Namespace configuration
- ✔ Resource reference consistency
- ✔ Secret placeholder detection

### 7. ZIP Download

Download all generated files as a ZIP archive.  
Example filename: `customer-api.zip`

### 8. ZIP Import / Apply Connectivity Link Config

Upload a ZIP and:
- Preview and edit YAML in the browser
- Bulk-replace the target Namespace
- Apply directly to the cluster via `oc apply` (through backend)

Kuadrant CRDs such as `AuthPolicy` are applied using Server-Side Apply (`PatchType.SERVER_SIDE_APPLY`) to bypass Fabric8's unregistered handler limitation.

### 9. Conversion History

- Lists all past conversion runs with date/time, service name, status, and compatibility score
- Re-download the ZIP for any historical entry (restore past state)
- Statuses: `Completed / Failed / In Progress`
- Download filename: `{service-name}-{generated-datetime}.zip`

---

## Directory Structure

```
migration-toolkit-rhcl/
├── backend/                    # Quarkus backend (Java 21)
│   └── src/main/java/com/example/migrationtool/
│       ├── controller/         # REST endpoints
│       │   ├── ConnectionController.java
│       │   ├── ServiceController.java
│       │   ├── ConversionController.java
│       │   ├── ValidationController.java
│       │   ├── PackageController.java  (ZIP download / history ZIP)
│       │   ├── ApplyController.java    (oc apply equivalent)
│       │   ├── ImportController.java
│       │   └── HistoryController.java
│       ├── service/            # Business logic
│       ├── entity/             # Panache entities (JPA)
│       │   ├── ProjectEntity.java
│       │   └── ConversionHistoryEntity.java
│       ├── model/              # DTOs / domain models
│       ├── util/
│       │   └── Messages.java   # i18n ResourceBundle wrapper
│       └── resources/
│           ├── application.properties
│           ├── messages_ja.properties
│           └── messages_en.properties
├── frontend/                   # React + PatternFly frontend
│   └── src/
│       ├── pages/              # Page components
│       ├── api/
│       │   ├── client.ts       # Axios API client
│       │   └── types.ts        # TypeScript type definitions
│       ├── locales/
│       │   ├── ja.json         # Japanese UI strings
│       │   └── en.json         # English UI strings
│       ├── i18n.ts             # react-i18next configuration
│       └── App.tsx             # Layout & routing
├── deploy/                     # OpenShift provisioning
│   ├── install.sh              # All-in-one install script
│   ├── backend/                # Backend OpenShift resource YAMLs
│   ├── frontend/               # Frontend OpenShift resource YAMLs
│   └── postgres/               # PostgreSQL Operator / Cluster YAMLs
├── from-3scale-to-connectivity-link/  # Conversion adapter
└── README.md
```

---

## API Reference

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/connection/test` | Test 3scale connection |
| GET | `/api/services` | List API services |
| GET | `/api/services/{id}` | Get service details |
| GET | `/api/services/{id}/compatibility` | Run compatibility check |
| POST | `/api/convert` | Generate YAML (run conversion) |
| POST | `/api/validate` | Validate generated YAML |
| POST | `/api/download/zip` | Download ZIP |
| GET | `/api/download/history/{historyId}` | Download ZIP from history |
| POST | `/api/apply` | Apply to cluster (Server-Side Apply) |
| POST | `/api/import/zip` | Import ZIP |
| GET | `/api/history` | List conversion history |
| GET | `/api/history/{id}` | Get conversion history entry |

Swagger UI: `https://<backend-route>/q/swagger-ui`

---

## Design Policies

- The `from-3scale-to-connectivity-link` adapter is abstracted so it can be swapped for other converters
- All APIs use REST (JSON)
- Database: PostgreSQL managed by CrunchyData Operator
- Applications run as containers on OpenShift
- Full provisioning (app + DB including operator) via a single script
- Backend: Java 21 + Quarkus
- UI: PatternFly 5, consistent with the OpenShift console look and feel
- Conversion history is persisted in DB; past states can be restored by re-downloading ZIP archives

---

## Data Model

```
Project
  ├── id (PK)
  ├── name
  ├── namespace
  ├── threescaleUrl
  ├── createdAt
  └── ConversionHistory[]
        ├── id (PK)
        ├── serviceId
        ├── serviceName
        ├── status         (COMPLETED / FAILED / IN_PROGRESS)
        ├── compatibilityScore
        ├── yamlContent    (full YAML text — used for ZIP restore)
        └── createdAt
```

The generic data model is designed to support future adapters from other API management platforms (e.g., AWS API Gateway).

---

## Internationalization (i18n)

### Frontend

- Powered by `react-i18next`
- Default language: **Japanese (ja)**
- Strings managed in `frontend/src/locales/ja.json` / `en.json`
- Runtime language switch via the **JA / EN** toggle in the masthead

### Backend

- Java standard `ResourceBundle`
- `backend/src/main/resources/messages_ja.properties` / `messages_en.properties`
- `Messages` bean (`@ApplicationScoped`) injected into controllers

---

*Maintained by Noriaki Mushino — nmushino@redhat.com*
