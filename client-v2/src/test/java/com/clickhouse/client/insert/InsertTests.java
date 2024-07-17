package com.clickhouse.client.insert;

import com.clickhouse.client.BaseIntegrationTest;
import com.clickhouse.client.ClickHouseClient;
import com.clickhouse.client.ClickHouseConfig;
import com.clickhouse.client.ClickHouseException;
import com.clickhouse.client.ClickHouseNode;
import com.clickhouse.client.ClickHouseNodeSelector;
import com.clickhouse.client.ClickHouseProtocol;
import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.enums.Protocol;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.client.api.metrics.ClientMetrics;
import com.clickhouse.client.api.metrics.OperationMetrics;
import com.clickhouse.client.api.metrics.ServerMetrics;
import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.data.ClickHouseFormat;
import org.testcontainers.shaded.org.apache.commons.lang3.RandomStringUtils;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class InsertTests extends BaseIntegrationTest {
    private Client client;
    private InsertSettings settings;

    @BeforeMethod(groups = { "integration" })
    public void setUp() throws IOException {
        ClickHouseNode node = getServer(ClickHouseProtocol.HTTP);
        client = new Client.Builder()
                .addEndpoint(Protocol.HTTP, node.getHost(), node.getPort(), false)
                .setUsername("default")
                .setPassword("")
                .useNewImplementation(System.getProperty("client.tests.useNewImplementation", "false").equals("true"))
                .compressClientRequest(false)
                .build();
        settings = new InsertSettings()
                .setDeduplicationToken(RandomStringUtils.randomAlphabetic(36))
                .setQueryId(String.valueOf(UUID.randomUUID()));
    }

    @AfterMethod(groups = { "integration" })
    public void tearDown() {
        client.close();
    }

    private void createTable(String tableQuery) throws ClickHouseException {
        try (ClickHouseClient client = ClickHouseClient.builder().config(new ClickHouseConfig())
                        .nodeSelector(ClickHouseNodeSelector.of(ClickHouseProtocol.HTTP))
                        .build()) {
            client.read(getServer(ClickHouseProtocol.HTTP)).query(tableQuery).executeAndWait().close();
        }
    }

    private void dropTable(String tableName) throws ClickHouseException {
        try (ClickHouseClient client = ClickHouseClient.builder().config(new ClickHouseConfig())
                .nodeSelector(ClickHouseNodeSelector.of(ClickHouseProtocol.HTTP))
                .build()) {
            String tableQuery = "DROP TABLE IF EXISTS " + tableName;
            client.read(getServer(ClickHouseProtocol.HTTP)).query(tableQuery).executeAndWait().close();
        }
    }

    @Test(groups = { "integration" }, enabled = true)
    public void insertSimplePOJOs() throws Exception {
        String tableName = "simple_pojo_table";
        String createSQL = SamplePOJO.generateTableCreateSQL(tableName);
        String uuid = UUID.randomUUID().toString();
        System.out.println(createSQL);
        dropTable(tableName);
        createTable(createSQL);
        client.register(SamplePOJO.class, client.getTableSchema(tableName, "default"));
        List<Object> simplePOJOs = new ArrayList<>();

        for (int i = 0; i < 1000; i++) {
            simplePOJOs.add(new SamplePOJO());
        }
        settings.setQueryId(uuid);
        InsertResponse response = client.insert(tableName, simplePOJOs, settings).get(30, TimeUnit.SECONDS);

        OperationMetrics metrics = response.getMetrics();
        assertEquals(simplePOJOs.size(), metrics.getMetric(ServerMetrics.NUM_ROWS_WRITTEN).getLong());
        assertEquals(simplePOJOs.size(), response.getWrittenRows());
        assertTrue(metrics.getMetric(ClientMetrics.OP_DURATION).getLong() > 0);
        assertTrue(metrics.getMetric(ClientMetrics.OP_SERIALIZATION).getLong() > 0);
        assertEquals(metrics.getQueryId(), uuid);
        assertEquals(response.getQueryId(), uuid);
    }

    @Test(groups = { "integration" }, enabled = true)
    public void insertRawData() throws Exception {
        final String tableName = "raw_data_table";
        final String createSQL = "CREATE TABLE " + tableName +
                " (Id UInt32, event_ts Timestamp, name String, p1 Int64, p2 String) ENGINE = MergeTree() ORDER BY ()";
        dropTable(tableName);
        createTable(createSQL);

        settings.setInputStreamCopyBufferSize(8198 * 2);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(data);
        for (int i = 0; i < 1000; i++) {
            writer.printf("%d\t%s\t%s\t%d\t%s\n", i, "2021-01-01 00:00:00", "name" + i, i, "p2");
        }
        writer.flush();
        InsertResponse response = client.insert(tableName, new ByteArrayInputStream(data.toByteArray()),
                ClickHouseFormat.TSV, settings).get(30, TimeUnit.SECONDS);
        OperationMetrics metrics = response.getMetrics();
        assertEquals((int)response.getWrittenRows(), 1000 );

        List<GenericRecord> records = client.queryAll("SELECT * FROM " + tableName);
        assertEquals(records.size(), 1000);
    }
}
