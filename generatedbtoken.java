package examples;

import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider;
import com.oracle.bmc.auth.ResourcePrincipalAuthenticationDetailsProvider;
import com.oracle.bmc.auth.okeworkloadidentity.OkeWorkloadIdentityAuthenticationDetailsProvider;
import com.oracle.bmc.identitydataplane.DataplaneClient;
import com.oracle.bmc.identitydataplane.model.GenerateScopedAccessTokenDetails;
import com.oracle.bmc.identitydataplane.requests.GenerateScopedAccessTokenRequest;
import com.oracle.bmc.identitydataplane.responses.GenerateScopedAccessTokenResponse;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import oracle.ucp.jdbc.PoolDataSource;
import oracle.ucp.jdbc.PoolDataSourceFactory;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Base64;
import oracle.jdbc.AccessToken;
import oracle.jdbc.datasource.impl.OracleDataSource;

public class GenerateDbToken {

    private PoolDataSource poolDataSource;

    private static final String DATABASE_URL =
            "jdbc:oracle:thin@" +
                    "(description=" +
                    "(retry_count=20)" +
                    "(retry_delay=3)" +
                    "(address=" +
                    "(protocol=tcps)" +
                    "(port=1521)" +
                    "(host=seuPrivateHost.adb.sa-saopaulo-1.oraclecloud.com)" +
                    ")" +
                    "(connect_data=" +
                    "(service_name=seuServiceName.adb.oraclecloud.com)" +
                    ")" +
                    "(security=" +
                    "(ssl_server_dn_match=yes)" +
                    "(ssl_server_cert_dn=\"CN=adb.sa-saopaulo-1.oraclecloud.com, O=Oracle Corporation, L=Redwood City, ST=California, C=US\")" +
                    ")" +
                    ")";

    private static final String CONN_FACTORY_CLASS_NAME =
            "oracle.jdbc.pool.OracleDataSource";

    private String dbToken;

    public static void main(String[] args) throws Exception {
        OkeWorkloadIdentityAuthenticationDetailsProvider provider =
                OkeWorkloadIdentityAuthenticationDetailsProvider.builder().build();

        DataplaneClient identityClient = DataplaneClient.builder()
                .build(provider);

        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();

        String publicKeyPem = convertToPem(keyPair.getPublic());

        GenerateScopedAccessTokenDetails details =
                GenerateScopedAccessTokenDetails.builder()
                        .scope("urn:oracle:db::id::*")
                        .publicKey(publicKeyPem)
                        .build();

        GenerateScopedAccessTokenRequest request =
                GenerateScopedAccessTokenRequest.builder()
                        .generateScopedAccessTokenDetails(details)
                        .build();

        GenerateScopedAccessTokenResponse response =
                identityClient.generateScopedAccessToken(request);

        String dbToken = response
                .getSecurityToken()
                .getToken();

        System.out.println("DB TOKEN:");
        System.out.println(dbToken);

        AccessToken accessToken = AccessToken.createJsonWebToken(
                dbToken.toCharArray(),
                keyPair.getPrivate());

        try (Connection connection = connectToDatabase(accessToken)) {
            System.out.println("   ✓ Conexão estabelecida com sucesso!\n");

            String currentUser = queryCurrentUser(connection);
            System.out.println("4. Usuário autenticado: " + currentUser + "\n");

            testDatabaseAccess(connection);

            System.out.println("\n=================================================");
            System.out.println("✓ TESTE CONCLUÍDO COM SUCESSO!");
            System.out.println("=================================================");
        }

        identityClient.close();
    }

    private static String convertToPem(PublicKey publicKey) {
        String encoded = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n"
                + encoded.replaceAll("(.{64})", "$1\n")
                + "\n-----END PUBLIC KEY-----\n";
    }

    private static Connection connectToDatabase(AccessToken accessToken) throws Exception {
        OracleDataSource dataSource = new OracleDataSource();
        dataSource.setURL(DATABASE_URL);

        return dataSource.createConnectionBuilder()
                .accessToken(accessToken)
                .build();
    }

    private static String queryCurrentUser(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT USER FROM sys.dual")) {
            if (resultSet.next()) {
                return resultSet.getString(1);
            }
            return "UNKNOWN";
        }
    }

    private static void testDatabaseAccess(Connection connection) throws Exception {
        System.out.println("5. Executando testes de acesso ao banco:\n");

        // Teste 1: Query simples
        System.out.println("   Teste 1: Query simples");
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT 'Conexão via IAM Token funcionando!' AS mensagem FROM dual")) {
            if (resultSet.next()) {
                System.out.println("   ✓ " + resultSet.getString(1));
            }
        }

        // Teste 2: Verificar roles
        System.out.println("\n   Teste 2: Roles atribuídas");
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT granted_role FROM user_role_privs ORDER BY granted_role")) {
            while (resultSet.next()) {
                System.out.println("   ✓ Role: " + resultSet.getString(1));
            }
        }

        // Teste 3: Verificar privilégios de sistema
        System.out.println("\n   Teste 3: Privilégios de sistema");
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT privilege FROM user_sys_privs ORDER BY privilege")) {
            int count = 0;
            while (resultSet.next() && count < 5) {
                System.out.println("   ✓ Privilege: " + resultSet.getString(1));
                count++;
            }
            if (resultSet.next()) {
                System.out.println("   ✓ ... e outros privilégios");
            }
        }

        // Teste 4: Operações CRUD completas
        System.out.println("\n   Teste 4: Operações CRUD Completas");
        performCrudOperations(connection);
    }

    private static void performCrudOperations(Connection connection) throws Exception {
        String tableName = "teste_iam_connection";

        try (Statement statement = connection.createStatement()) {

            // Passo 1: Limpar tabela antiga se existir
            System.out.println("\n   Passo 1: Verificando tabela existente");
            try {
                statement.execute("DROP TABLE " + tableName + " PURGE");
                System.out.println("   ✓ Tabela antiga removida");
            } catch (SQLException e) {
                System.out.println("   ℹ Nenhuma tabela antiga para remover");
            }

            // Passo 2: Criar tabela
            System.out.println("\n   Passo 2: Criando nova tabela");
            String createTableSQL =
                    "CREATE TABLE " + tableName + " (" +
                            "  id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY, " +
                            "  nome VARCHAR2(100) NOT NULL, " +
                            "  email VARCHAR2(100), " +
                            "  idade NUMBER, " +
                            "  ativo VARCHAR2(1) DEFAULT 'S', " +
                            "  data_criacao TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                            "  data_modificacao TIMESTAMP " +
                            ")";
            statement.execute(createTableSQL);
            System.out.println("   ✓ Tabela '" + tableName + "' criada com sucesso!");

            // Passo 3: Inserir registros
            System.out.println("\n   Passo 3: Inserindo registros");

            String insertSQL = "INSERT INTO " + tableName +
                    " (nome, email, idade, ativo) VALUES (?, ?, ?, ?)";

            try (PreparedStatement pstmt = connection.prepareStatement(insertSQL)) {
                // Registro 1
                pstmt.setString(1, "João Silva");
                pstmt.setString(2, "joao.silva@example.com");
                pstmt.setInt(3, 30);
                pstmt.setString(4, "S");
                pstmt.executeUpdate();
                System.out.println("   ✓ Inserido: João Silva");

                // Registro 2
                pstmt.setString(1, "Maria Santos");
                pstmt.setString(2, "maria.santos@example.com");
                pstmt.setInt(3, 25);
                pstmt.setString(4, "S");
                pstmt.executeUpdate();
                System.out.println("   ✓ Inserido: Maria Santos");

                // Registro 3
                pstmt.setString(1, "Pedro Costa");
                pstmt.setString(2, "pedro.costa@example.com");
                pstmt.setInt(3, 35);
                pstmt.setString(4, "S");
                pstmt.executeUpdate();
                System.out.println("   ✓ Inserido: Pedro Costa");

                // Registro 4
                pstmt.setString(1, "Ana Oliveira");
                pstmt.setString(2, "ana.oliveira@example.com");
                pstmt.setInt(3, 28);
                pstmt.setString(4, "S");
                pstmt.executeUpdate();
                System.out.println("   ✓ Inserido: Ana Oliveira");

                // Registro 5
                pstmt.setString(1, "Carlos Ferreira");
                pstmt.setString(2, "carlos.ferreira@example.com");
                pstmt.setInt(3, 42);
                pstmt.setString(4, "S");
                pstmt.executeUpdate();
                System.out.println("   ✓ Inserido: Carlos Ferreira");
            }

            System.out.println("   ✓ Total: 5 registros inseridos com sucesso!");

            // Passo 4: Consultar registros
            System.out.println("\n   Passo 4: Consultando registros inseridos");
            String selectSQL = "SELECT id, nome, email, idade, ativo, " +
                    "TO_CHAR(data_criacao, 'DD/MM/YYYY HH24:MI:SS') as data_criacao " +
                    "FROM " + tableName + " ORDER BY id";

            try (ResultSet rs = statement.executeQuery(selectSQL)) {
                System.out.println("\n   Dados da tabela:");
                System.out.println("   " + "=".repeat(80));
                int count = 0;
                while (rs.next()) {
                    count++;
                    System.out.println("   Registro " + count + ":");
                    System.out.println("     - ID: " + rs.getInt("id"));
                    System.out.println("     - Nome: " + rs.getString("nome"));
                    System.out.println("     - Email: " + rs.getString("email"));
                    System.out.println("     - Idade: " + rs.getInt("idade"));
                    System.out.println("     - Ativo: " + rs.getString("ativo"));
                    System.out.println("     - Data Criação: " + rs.getString("data_criacao"));
                    System.out.println();
                }
                System.out.println("   Total de registros: " + count);
                System.out.println("   " + "=".repeat(80));
            }

            // Passo 5: Atualizar registros
            System.out.println("\n   Passo 5: Atualizando registros");

            // Atualizar idade do João
            String updateSQL1 = "UPDATE " + tableName +
                    " SET idade = 31, data_modificacao = CURRENT_TIMESTAMP " +
                    "WHERE nome = 'João Silva'";
            int rowsUpdated = statement.executeUpdate(updateSQL1);
            System.out.println("   ✓ Atualizada idade de João Silva (31 anos) - " +
                    rowsUpdated + " registro(s) modificado(s)");

            // Atualizar email da Maria
            String updateSQL2 = "UPDATE " + tableName +
                    " SET email = 'maria.santos.novo@example.com', " +
                    "data_modificacao = CURRENT_TIMESTAMP " +
                    "WHERE nome = 'Maria Santos'";
            rowsUpdated = statement.executeUpdate(updateSQL2);
            System.out.println("   ✓ Atualizado email de Maria Santos - " +
                    rowsUpdated + " registro(s) modificado(s)");

            // Desativar Pedro
            String updateSQL3 = "UPDATE " + tableName +
                    " SET ativo = 'N', data_modificacao = CURRENT_TIMESTAMP " +
                    "WHERE nome = 'Pedro Costa'";
            rowsUpdated = statement.executeUpdate(updateSQL3);
            System.out.println("   ✓ Status de Pedro Costa alterado para inativo - " +
                    rowsUpdated + " registro(s) modificado(s)");

            System.out.println("   ✓ Atualizações confirmadas!");

            // Consultar após updates
            System.out.println("\n   Verificando dados após atualizações:");
            String selectAfterUpdate = "SELECT id, nome, email, idade, ativo, " +
                    "TO_CHAR(data_modificacao, 'DD/MM/YYYY HH24:MI:SS') as data_mod " +
                    "FROM " + tableName + " WHERE nome IN ('João Silva', 'Maria Santos', 'Pedro Costa') " +
                    "ORDER BY id";

            try (ResultSet rs = statement.executeQuery(selectAfterUpdate)) {
                while (rs.next()) {
                    System.out.println("   - " + rs.getString("nome") +
                            " | Email: " + rs.getString("email") +
                            " | Idade: " + rs.getInt("idade") +
                            " | Ativo: " + rs.getString("ativo") +
                            " | Modificado: " + rs.getString("data_mod"));
                }
            }

            // Passo 6: Deletar registros específicos
            System.out.println("\n   Passo 6: Deletando registros específicos");

            // Deletar Ana Oliveira
            String deleteSQL1 = "DELETE FROM " + tableName + " WHERE nome = 'Ana Oliveira'";
            int rowsDeleted = statement.executeUpdate(deleteSQL1);
            System.out.println("   ✓ Deletado registro de Ana Oliveira - " +
                    rowsDeleted + " registro(s) removido(s)");

            // Deletar registros inativos
            String deleteSQL2 = "DELETE FROM " + tableName + " WHERE ativo = 'N'";
            rowsDeleted = statement.executeUpdate(deleteSQL2);
            System.out.println("   ✓ Deletados registros inativos - " +
                    rowsDeleted + " registro(s) removido(s)");

            System.out.println("   ✓ Deleções confirmadas!");

            // Consultar registros restantes
            System.out.println("\n   Registros restantes na tabela:");
            try (ResultSet rs = statement.executeQuery(
                    "SELECT COUNT(*) as total FROM " + tableName)) {
                if (rs.next()) {
                    System.out.println("   ✓ Total de registros: " + rs.getInt("total"));
                }
            }

            try (ResultSet rs = statement.executeQuery(
                    "SELECT nome FROM " + tableName + " ORDER BY id")) {
                while (rs.next()) {
                    System.out.println("   - " + rs.getString("nome"));
                }
            }

            // Passo 7: Deletar a tabela
            System.out.println("\n   Passo 7: Removendo tabela");
            statement.execute("DROP TABLE " + tableName + " PURGE");
            System.out.println("   ✓ Tabela '" + tableName + "' removida com sucesso!");
            System.out.println("   ✓ Teste CRUD completo finalizado!");

        } catch (SQLException e) {
            System.err.println("   ✗ Erro durante operações CRUD: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
}
