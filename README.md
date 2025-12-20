# Guia - Conexão OKE ao Autonomous DB via IAM Workload Identity

# 1. Visão Geral

Este documento descreve o processo para conectar aplicações Java executadas no Oracle Kubernetes Engine (OKE) ao Oracle Autonomous Database (ADB) utilizando autenticação IAM via Workload Identity, eliminando a necessidade de gerenciamento de wallets ou senhas.

---

# 2. Requisitos necessários

## 2.1. Policies a nível de Tenancy 

Para permitir a geração de tokens de autenticação para o banco de dados, é necessário configurar policies específicas a nivel de **Tenancy**. Para mais informações é possível consultar [For Oracle Database Connections](https://docs.oracle.com/en-us/iaas/database-tools/doc/oracle-database-connections.html#OCDBT-GUID-C81428B6-36D9-4CE3-8CAC-45F0EDBFDC1B) e [Connecting (OKE) Workload Identity to ADB with OCI IAM](https://www.ateam-oracle.com/connecting-oracle-kubernetes-engine-oke-namespaces-to-autonomous-database-with-oci-iamconnecting-oracle-kubernetes-engine-oke-namespaces-to-autonomous-database-with-oci-iam).

```sql
Allow any-user to {db_connect} in tenancy where all { request.principal.type = 'workload', request.principal.namespace = '<seu-namespace>', request.principal.service_account = '<seu-service-account>', request.principal.cluster_id = '<ocid-do-cluster>'}

Allow any-user to use database-tools-family in tenancy where all { request.principal.type = 'workload', request.principal.namespace = '<seu-namespace>', request.principal.service_account = '<seu-service-account>', request.principal.cluster_id = '<ocid-do-cluster>'}
```

## 2.2. Cluster OKE
Requisitos do Cluster:
1. Cluster no mesmo compartment que será usado para o ADB.
2. Versão Kubernetes: 1.30 ou superior.
3. Workload Identity habilitado no cluster.
4. Acesso via Cloud Shell ou kubectl local configurado.
5. VCN e subnet que serão compartilhadas com o ADB.

### 2.2.1 Workload Identity OCID
O Workload Identity OCID só pode ser obtido após a decodificação do token JWT gerado pelo workload. O processo recomendado é:

1. Faça deploy inicial da aplicação no OKE
2. Execute o comando para gerar o token
```sql
oci iam db-token get --auth oke_workload_identity --region sa-saopaulo-1
```
3. Decodifique o token JWT (disponível em ~/.oci/db-token/token) usando uma ferramenta como [jwt.io](https://www.jwt.io/)
4. Localize o claim sub no payload - este contém o Workload Identity OCID no formato: ocid1.workload.oc1.sa-saopaulo-1...
5. **Alternativa**: Adicione código Java na aplicação para logar o OCID do token decodificado (exemplo fornecido na seção 3.2).

## 2.3. Autonomous Database Configurado
Para que seja possível conectar no banco da forma descrita nesse guia, os pontos a seguir devem ser observados.

### 2.3.1. Provisionar o Autonomous Database
1. O compartment do banco deve ser o mesmo em que o cluster OKE estará alocado (Dev, Test, Prod, Sandbox, etc.).
2. Versão recomendada: **26ai** ou superior.
3. Network access: **Private endpoint access only**. 
4. Para utilizar o Database Actions SQL (recomendado para esse teste), habilite provisoriamente o acesso público e adicione o IP da sua máquina local à ACL, também é possível alterar essa opção após criar o banco.
5. A VCN e subnet devem ser as mesmas utilizadas pelo cluster OKE.

### 2.3.2. Habilitar autenticação IAM no banco
É necessário conectar-se ao banco como usuário **ADMIN** para que este e os próximos passos possam ser executados (utilize o Database Actions SQL). 

Para habilitar a autenticação externa via IAM execute:

```sql
BEGIN
   DBMS_CLOUD_ADMIN.ENABLE_EXTERNAL_AUTHENTICATION(
      type => 'OCI_IAM' );
END;

-- Para verificar o método de autenticação
SELECT NAME, VALUE FROM V$PARAMETER WHERE NAME='identity_provider_type';
```

#### 2.3.3. Criar um usuário global mapeado com o OCID do Workload Identity
Para criar usuário global mapeado com o OCID do Workload Identity (obtido em 2.2.1 ou 3.2), execute o bloco de código abaixo, conforme demonstrado em [Connecting (OKE) Workload Identity to ADB with OCI IAM](https://www.ateam-oracle.com/connecting-oracle-kubernetes-engine-oke-namespaces-to-autonomous-database-with-oci-iamconnecting-oracle-kubernetes-engine-oke-namespaces-to-autonomous-database-with-oci-iam).

```sql
-- Substitua o OCID pelo obtido no passo 2.2
CREATE USER app_workload_user IDENTIFIED GLOBALLY AS 'IAM_PRINCIPAL_OCID¹=ocid1.workload.oc1..........';

-- Verificar o usuário criado
SELECT username, authentication_type, external_name
FROM dba_users
WHERE username = 'APP_WORKLOAD_USER';
```

#### 2.3.4. Atribuir Roles e Grants Necessários
Para que o usuário possa se conectar e executar as operações que desejar no banco, é necessário que as permissões abaixo sejam concedidas.

```sql
-- Permissões básicas
GRANT DWROLE TO app_workload_user;
GRANT UNLIMITED TABLESPACE TO app_workload_user;

-- Verificar permissões de sistema
SELECT privilege
FROM dba_sys_privs
WHERE grantee = 'app_workload_user'

-- Verificar roles concedidas
SELECT granted_role
FROM dba_role_privs
WHERE grantee = 'app_workload_user';
```

Mais detalhes das grants utilizadas estão descritos na tabela abaixo.

| Grant/Role       | Tipo     |  O que garante | Importância |
| ------|-----|-----|-----|
| DWROLE	| Role pré-definida do ADB 	| Role especializada do Autonomous Database que fornece privilégios comuns aos usuários do banco | Recomendada pelo provedor para usuários de desenvolvimento/análise de dados no ADB oracle, na [documentação oficial](https://docs.oracle.com/en/cloud/paas/autonomous-database/serverless/adbsb/manage-users-privileges.html#GUID-50450FAD-9769-4CF7-B0D1-EC14B465B873) é possível verificar todos os privilégios que essa role garante​ |
| UNLIMITED TABLESPACE	| Privilégio de sistema 	| Espaço ilimitado em todos os tablespaces | Obrigatório - DWROLE não inclui quota de tablespace¹​ |

**Obs¹**: Os tablespaces são unidades lógicas de armazenamento no Oracle Database que funcionam como "contêineres virtuais" para organizar e gerenciar onde os dados são armazenados fisicamente. Um banco de dados Oracle é dividido em uma ou mais tablespaces, e cada tablespace agrupa objetos relacionados (tabelas, índices, views, procedures, etc.) de forma lógica. Para utilizá-los é necessário atribuir uma quota de espaço ou liberar seu uso de forma ilimitada.

## 3. Aplicação Java

### 3.1. Dependências Maven (pom.xml)
As dependências maven utilizadas podem ser encontradas [nesse repositório](https://github.com/maricvm/poc-conexao-oke-adb/blob/main/pom.xml).

### 3.2 Código Java da Aplicação
O código usado encontra-se disponível [nesse repositório](https://github.com/maricvm/poc-conexao-oke-adb/blob/main/generatedbtoken.java). Atualize a connection string com os valores do seu ADB.

```java
 // ATUALIZAR COM OS DADOS DO SEU ADB
    private static final String DATABASE_URL = 
        "jdbc:oracle:thin:@" +
        "(description=" +
          "(retry_count=20)" +
          "(retry_delay=3)" +
          "(address=" +
            "(protocol=tcps)" +
            "(port=1521)" +
            "(host=<seu-host>.adb.sa-saopaulo-1.oraclecloud.com)" +
          ")" +
          "(connect_data=" +
            "(service_name=<seu-service-name>_medium.adb.oraclecloud.com)" +
          ")" +
          "(security=" +
            "(ssl_server_dn_match=yes)" +
            "(ssl_server_cert_dn=\"CN=adb.sa-saopaulo-1.oraclecloud.com, O=Oracle Corporation, L=Redwood City, ST=California, C=US\")" +
          ")" +
        ")";
```

### 3.3. Script de Build e Deploy
Nesse repositório é possível encontrar um [exemplo](inserir link) de script de build e deploy. Ou crie a partir do código a seguir:

```bash
#!/bin/bash
set -e

VERSION=${1:-1.0}
REGISTRY="<seu-registry-ocir>"  # Ex: gru.ocir.io/namespace/adb-oke-app
NAMESPACE="app-teste"
SERVICE_ACCOUNT="sa-adb-app"
APP_NAME="java-adb-test"

echo "=== Build e Deploy da Aplicação ADB-OKE ==="
echo "Versão: ${VERSION}"

# 1. Compilar aplicação
echo "1. Compilando aplicação Java..."
mvn clean package

# 2. Criar imagem Docker
echo "2. Criando imagem Docker..."
docker build -t ${REGISTRY}:${VERSION} .

# 3. Push da imagem
echo "3. Fazendo push da imagem..."
docker push ${REGISTRY}:${VERSION}

# 4. Deploy no OKE
echo "4. Fazendo deploy no OKE..."
cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ${APP_NAME}
  namespace: ${NAMESPACE}
spec:
  replicas: 1
  selector:
    matchLabels:
      app: ${APP_NAME}
  template:
    metadata:
      labels:
        app: ${APP_NAME}
    spec:
      serviceAccountName: ${SERVICE_ACCOUNT}
      containers:
      - name: app
        image: ${REGISTRY}:${VERSION}
        imagePullPolicy: Always
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
EOF

echo "✓ Deploy concluído com sucesso"
echo ""
echo "Para acompanhar os logs:"
echo "kubectl logs deployment/${APP_NAME} -n ${NAMESPACE} -f"
```

### 3.4. Dockerfile
```bash
FROM openjdk:21-jdk-slim

WORKDIR /app

COPY target/adb-oke-iam-connection-1.0.jar app.jar

CMD ["java", "-jar", "app.jar"]
```

## 4. Testes e Validação

### 4.1. Preparar Ambiente
```bash
# Dar permissão de execução ao script
chmod +x build-and-deploy.sh

# Executar build e deploy
./build-and-deploy.sh 1.0
```

### 4.2. Acompanhar Logs
```bash
# Logs em tempo real
kubectl logs deployment/java-adb-test -n app-teste -f

# Verificar status do pod
kubectl get pods -n app-teste

# Descrever pod para troubleshooting
kubectl describe pod -l app=java-adb-test -n app-teste
```

### 4.3. Saída Esperada
```bash
=== Iniciando conexão ao ADB via OKE Workload Identity ===
Workload Identity OCID: ocid1.workload.oc1.sa-saopaulo-1...
✓ Token IAM gerado com sucesso
✓ Conexão estabelecida com sucesso

=== Executando Testes de Validação ===

1. Usuário conectado: APP_WORKLOAD_USER
2. Método de autenticação: TOKEN_GLOBAL
3. Roles atribuídas:
   - DWROLE
4. Privilégios de sistema:
   - CREATE SESSION
   - UNLIMITED TABLESPACE

✓ Todos os testes executados com sucesso
✓ Conexão fechada
```

## 5. Troubleshooting

### 5.1. Erro: NotAuthorizedOrNotFound (404)

**Causa:** Policies de IAM incorretas ou ausentes.

**Solução:** Verificar se as policies em 2.1 estão aplicadas corretamente a nível de Tenancy.

### 5.2. Erro: ORA-01017 (usuário/senha inválidos)

**Causa:** Mapeamento incorreto entre Workload Identity OCID e usuário do banco.

**Solução:** Decodificar o token JWT e verificar o claim sub e recriar o usuário com o OCID correto.

