package org.dbsp.sqlCompiler.compiler.sql.streaming;

import org.dbsp.sqlCompiler.CompilerMain;
import org.dbsp.sqlCompiler.circuit.operator.DBSPIntegrateTraceRetainKeysOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPPartitionedRollingAggregateWithWaterlineOperator;
import org.dbsp.sqlCompiler.compiler.DBSPCompiler;
import org.dbsp.sqlCompiler.compiler.StderrErrorReporter;
import org.dbsp.sqlCompiler.compiler.errors.CompilerMessages;
import org.dbsp.sqlCompiler.compiler.sql.tools.BaseSQLTests;
import org.dbsp.sqlCompiler.compiler.sql.StreamingTestBase;
import org.dbsp.sqlCompiler.compiler.visitors.outer.CircuitVisitor;
import org.dbsp.util.Linq;
import org.dbsp.util.Utilities;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/** Tests that exercise streaming features. */
public class StreamingTests extends StreamingTestBase {
    @Test
    public void issue2004() {
        // Based on Q9
        String sql = """
                CREATE TABLE auction (
                   date_time TIMESTAMP NOT NULL LATENESS INTERVAL 1 MINUTE,
                   expires   TIMESTAMP NOT NULL,
                   id        INT
                );
                
                CREATE TABLE bid (
                   date_time TIMESTAMP NOT NULL LATENESS INTERVAL 1 MINUTE,
                   price INT,
                   auction INT
                );
                
                CREATE VIEW Q9 AS
                SELECT A.*, B.price, B.date_time AS bid_dateTime
                FROM auction A, bid B
                WHERE A.id = B.auction AND B.date_time BETWEEN A.date_time AND A.expires""";
        DBSPCompiler compiler = this.testCompiler();
        compiler.compileStatements(sql);
        CompilerCircuitStream ccs = new CompilerCircuitStream(compiler);
        // Insert an auction. No bids => no output
        ccs.step("""
                INSERT INTO auction VALUES('2024-01-01 00:00:00', '2024-01-01 01:00:00', 0);
                """, """
                 date_time | expires | id | price | bid_dateTime | weight
                ----------------------------------------------------------""");
        // Insert a bid matching auction 0 in the expected time range.  Should produce output.
        ccs.step("""
                INSERT INTO bid VALUES('2024-01-01 00:00:01', 100, 0);
                """, """
                 date_time | expires | id | price | bid_dateTime | weight
                ----------------------------------------------------------
                 2024-01-01 00:00:00 | 2024-01-01 01:00:00 | 0 | 100 | 2024-01-01 00:00:01 | 1""");
        // Insert a second bid matching auction 0 in the expected time range.  Should produce output.
        ccs.step("""
                INSERT INTO bid VALUES('2024-01-01 00:00:10', 200, 0);
                """, """
                 date_time | expires | id | price | bid_dateTime | weight
                ----------------------------------------------------------
                 2024-01-01 00:00:00 | 2024-01-01 01:00:00 | 0 | 200 | 2024-01-01 00:00:10 | 1""");
        // Insert a bid matching auction 1, which doesn't exist yet.  No output.
        ccs.step("""
                INSERT INTO bid VALUES('2024-01-01 00:00:20', 50, 1);
                """, """
                 date_time | expires | id | price | bid_dateTime | weight
                ----------------------------------------------------------""");
        // Insert auction 1, which matches the previous bid.  Should produce output.
        ccs.step("""
                INSERT INTO auction VALUES('2024-01-01 00:00:10', '2024-01-01 01:00:00', 1);
                """, """
                 date_time | expires | id | price | bid_dateTime | weight
                ----------------------------------------------------------
                 2024-01-01 00:00:10 | 2024-01-01 01:00:00 | 1 | 50 | 2024-01-01 00:00:20 | 1""");
        // Insert bid for auction 1 which is out of the auction time range.  No output.
        ccs.step("""
                INSERT INTO bid VALUES('2024-01-01 00:00:00', 50, 1);
                """, """
                 date_time | expires | id | price | bid_dateTime | weight
                ----------------------------------------------------------""");
        // Insert legal bid for auction 1.  Should produce output.
        ccs.step("""
                INSERT INTO bid VALUES('2024-01-01 00:00:30', 80, 1);
                """, """
                 date_time | expires | id | price | bid_dateTime | weight
                ----------------------------------------------------------
                2024-01-01 00:00:10 | 2024-01-01 01:00:00 | 1 | 80 | 2024-01-01 00:00:30 | 1""");
        // Insert auction and before auction LATENESS, no output.
        ccs.step("""
                INSERT INTO auction VALUES('2023-12-12 23:59:59', '2024-01-01 01:00:00', 3);
                INSERT INTO bid VALUES('2024-01-01 00:02:00', 1000, 3);
                """, """
                 date_time | expires | id | price | bid_dateTime | weight
                ----------------------------------------------------------""");
        // Insert legal bid for auction 1 but before bid LATENESS, no output.
        ccs.step("""
                INSERT INTO bid VALUES('2024-01-01 00:00:30', 3000, 1);
                """, """
                 date_time | expires | id | price | bid_dateTime | weight
                ----------------------------------------------------------""");
        this.addRustTestCase("issue2004", ccs);
    }

    @Test
    public void profileRetainValues() throws IOException, InterruptedException, SQLException {
        // Based on Q9.  Check whether integrate_trace_retain_values works as expected.
        String sql = """
                CREATE TABLE auction (
                   date_time TIMESTAMP NOT NULL LATENESS INTERVAL 1 MINUTE,
                   expires   TIMESTAMP NOT NULL,
                   id        INT
                );
                
                CREATE TABLE bid (
                   date_time TIMESTAMP NOT NULL LATENESS INTERVAL 1 MINUTE,
                   price INT,
                   auction INT
                );
                
                CREATE VIEW Q9 AS
                SELECT A.*, B.price, B.date_time AS bid_dateTime
                FROM auction A, bid B
                WHERE A.id = B.auction AND B.date_time BETWEEN A.date_time AND A.expires;
                """;
        // Rust program which profiles the circuit.
        String main = this.createMain("""
                    // Initial data value for timestamp
                    let mut timestamp = cast_to_Timestamp_s("2024-01-10 10:10:10".to_string());
                    for i in 0..1000000 {
                        let expire = timestamp.add(1000000);
                        timestamp = timestamp.add(20000);
                        let auction = zset!(Tup3::new(timestamp, expire, Some(i)) => 1);
                        append_to_collection_handle(&auction, &streams.0);
                        let bid = zset!(Tup3::new(timestamp.add(100), Some(i), Some(i)) => 1);
                        append_to_collection_handle(&bid, &streams.1);
                        if i % 100 == 0 {
                            let _ = circuit.step().expect("could not run circuit");
                            let _ = &read_output_handle(&streams.2);
                            /*
                            let end = SystemTime::now();
                            let profile = circuit.retrieve_profile().expect("could not get profile");
                            let duration = end.duration_since(start).expect("could not get time");
                            println!("{:?},{:?}", duration.as_millis(), profile.total_used_bytes().unwrap().bytes);
                            */
                        }
                    }""");
        this.profile(sql, main);
    }

    @Test
    public void issue1973() {
        String sql = """
                create table t (
                    id bigint not null,
                    ts bigint not null LATENESS 0
                );
                
                CREATE VIEW v1 AS
                SELECT ts, COUNT(*)
                FROM t
                GROUP BY ts;
                
                CREATE VIEW v2 as
                select ts, count(*) from v1
                group by ts;""";
        DBSPCompiler compiler = this.testCompiler();
        compiler.compileStatements(sql);
        CompilerCircuitStream ccs = new CompilerCircuitStream(compiler);
        this.addRustTestCase("issue1973", ccs);
        CircuitVisitor visitor = new CircuitVisitor(new StderrErrorReporter()) {
            int integrate_trace = 0;

            @Override
            public void postorder(DBSPIntegrateTraceRetainKeysOperator operator) {
                this.integrate_trace++;
            }

            @Override
            public void endVisit() {
                Assert.assertEquals(4, this.integrate_trace);
            }
        };
        visitor.apply(ccs.circuit);
    }

    @Test
    public void testNow() {
        String sql = """
                CREATE VIEW V AS SELECT 1, NOW() < TIMESTAMP '2025-12-12 00:00:00';""";
        DBSPCompiler compiler = this.testCompiler();
        compiler.compileStatements(sql);
        CompilerCircuitStream ccs = new CompilerCircuitStream(compiler);
        ccs.step("INSERT INTO now VALUES ('2024-12-12 00:00:00')",
                """
                         c | compare | weight
                        ----------------------
                         1 | true    | 1""");
        this.addRustTestCase("testNow", ccs);
    }

    @Test
    public void testNow2() {
        String sql = """
                CREATE TABLE T(value INT);
                CREATE VIEW V AS SELECT *, NOW() FROM T;""";
        DBSPCompiler compiler = this.testCompiler();
        compiler.compileStatements(sql);
        CompilerCircuitStream ccs = new CompilerCircuitStream(compiler);
        ccs.step("""
                        INSERT INTO T VALUES (2), (3);
                        INSERT INTO now VALUES ('2024-12-12 00:00:00');
                        """,
                """
                         value | now                 | weight
                        --------------------------------------
                         2     | 2024-12-12 00:00:00 | 1
                         3     | 2024-12-12 00:00:00 | 1""");
        ccs.step("""
                        REMOVE FROM now VALUES ('2024-12-12 00:00:00');
                        INSERT INTO now VALUES ('2024-12-12 00:01:00');
                        """,
                """
                         value | now                 | weight
                        --------------------------------------
                         2     | 2024-12-12 00:00:00 | -1
                         3     | 2024-12-12 00:00:00 | -1
                         2     | 2024-12-12 00:01:00 | 1
                         3     | 2024-12-12 00:01:00 | 1""");
        this.addRustTestCase("testNow2", ccs);
    }

    @Test
    public void testNow3() {
        String sql = """
                CREATE TABLE T(value INT);
                CREATE VIEW V AS SELECT SUM(value) + MINUTE(NOW()) FROM T;""";
        DBSPCompiler compiler = this.testCompiler();
        compiler.compileStatements(sql);
        CompilerCircuitStream ccs = new CompilerCircuitStream(compiler);
        ccs.step("""
                        INSERT INTO T VALUES (2), (3);
                        INSERT INTO now VALUES ('2024-12-12 00:00:00');
                        """,
                """
                         value | weight
                        ----------------
                         5     | 1""");
        ccs.step("""
                        REMOVE FROM now VALUES ('2024-12-12 00:00:00');
                        INSERT INTO now VALUES ('2024-12-12 00:01:00');
                        """,
                """
                         value | weight
                        ----------------
                         5     | -1
                         6     | 1""");
        this.addRustTestCase("testNow3", ccs);
    }

    @Test
    public void testNow4() {
        // now() used in WHERE
        String sql = """
                CREATE TABLE transactions (
                  id INT PRIMARY KEY,
                  ts TIMESTAMP,
                  users INT,
                  AMOUNT DECIMAL
                );
                CREATE VIEW window_computation AS
                SELECT
                  users,
                  COUNT(*) AS transaction_count_by_user
                FROM transactions
                WHERE ts >= now() - INTERVAL 1 DAY
                GROUP BY users""";
        DBSPCompiler compiler = this.testCompiler();
        compiler.compileStatements(sql);
        new CompilerCircuitStream(compiler);
    }

    @Test
    public void issue2003() {
        String sql = """
                CREATE TABLE event(
                    end   TIMESTAMP,
                    start TIMESTAMP NOT NULL LATENESS INTERVAL '1' HOURS
                );
                
                -- This is monotone because of the filter
                CREATE VIEW event_duration AS SELECT DISTINCT end
                FROM event
                WHERE end > start;""";
        DBSPCompiler compiler = this.testCompiler();
        compiler.compileStatements(sql);
        CompilerCircuitStream ccs = new CompilerCircuitStream(compiler);
        this.addRustTestCase("issue2003", ccs);
        CircuitVisitor visitor = new CircuitVisitor(new StderrErrorReporter()) {
            int integrate_trace = 0;

            @Override
            public void postorder(DBSPIntegrateTraceRetainKeysOperator operator) {
                this.integrate_trace++;
            }

            @Override
            public void endVisit() {
                Assert.assertEquals(1, this.integrate_trace);
            }
        };
        visitor.apply(ccs.circuit);
    }

    @Test
    public void issue1963() {
        String sql = """
                CREATE TABLE event(
                    id  BIGINT,
                    start   TIMESTAMP NOT NULL LATENESS INTERVAL '1' HOURS
                );
                
                CREATE VIEW event_duration AS SELECT DISTINCT
                    start,
                    id
                FROM event;
                
                CREATE VIEW filtered_events AS
                SELECT DISTINCT * FROM event_duration;""";
        this.compileRustTestCase(sql);
    }

    @Test
    public void issue1964() {
        String sql = """
                CREATE TABLE event(
                    start   TIMESTAMP NOT NULL LATENESS INTERVAL 1 HOURS
                );
                
                LATENESS slotted_events.start 96;
                
                CREATE VIEW slotted_events AS
                SELECT start
                FROM event;""";
        this.statementsFailingInCompilation(sql, "Cannot apply operation '-'");
    }

    @Test
    public void issue1965() {
        String sql = """
                CREATE TABLE event(
                    eve_key     VARCHAR,
                    eve_start   TIMESTAMP NOT NULL LATENESS INTERVAL 1 HOURS
                );
                
                CREATE VIEW filtered_events AS
                SELECT DISTINCT * FROM event
                WHERE eve_key IN ('foo', 'bar');
                
                CREATE VIEW slotted_events AS
                SELECT eve_start, eve_key
                FROM filtered_events;
                
                LATENESS slotted_events.eve_start INTERVAL 96 MINUTES;""";
        this.compileRustTestCase(sql);
    }

    @Test
    public void hoppingTest() {
        String sql = """
                CREATE TABLE series (
                    pickup TIMESTAMP NOT NULL
                );
                CREATE VIEW V AS
                SELECT * FROM TABLE(
                  HOP(
                    TABLE series,
                    DESCRIPTOR(pickup),
                    INTERVAL '2' MINUTE,
                    INTERVAL '5' MINUTE));""";
        DBSPCompiler compiler = this.testCompiler();
        compiler.compileStatements(sql);
        CompilerCircuitStream ccs = new CompilerCircuitStream(compiler);
        this.addRustTestCase("hoppingTest", ccs);
    }

    @Test
    public void smallTaxiTest() {
        String sql = """
                CREATE TABLE tripdata (
                  t TIMESTAMP NOT NULL LATENESS INTERVAL 1 HOUR,
                  location INT NOT NULL
                );
                
                CREATE VIEW V AS
                SELECT
                *,
                COUNT(*) OVER(
                   PARTITION BY  location
                   ORDER BY  t
                   RANGE BETWEEN INTERVAL 1 HOUR PRECEDING AND INTERVAL 1 MINUTE PRECEDING ) AS c
                FROM tripdata;""";
        DBSPCompiler compiler = this.testCompiler();
        compiler.compileStatements(sql);
        CompilerCircuitStream ccs = new CompilerCircuitStream(compiler);
        this.addRustTestCase("smallTaxiTest", ccs);
        CircuitVisitor visitor = new CircuitVisitor(new StderrErrorReporter()) {
            int rolling_waterline = 0;
            int integrate_trace = 0;

            @Override
            public void postorder(DBSPPartitionedRollingAggregateWithWaterlineOperator operator) {
                this.rolling_waterline++;
            }

            @Override
            public void postorder(DBSPIntegrateTraceRetainKeysOperator operator) {
                this.integrate_trace++;
            }

            @Override
            public void endVisit() {
                Assert.assertEquals(1, this.rolling_waterline);
                Assert.assertEquals(2, this.integrate_trace);
            }
        };
        visitor.apply(ccs.circuit);
    }

    @Test
    public void taxiTest() {
        String sql = """
                CREATE TABLE green_tripdata
                (
                        lpep_pickup_datetime TIMESTAMP NOT NULL LATENESS INTERVAL '1:00' HOURS TO MINUTES,
                        lpep_dropoff_datetime TIMESTAMP NOT NULL LATENESS INTERVAL '1:00' HOURS TO MINUTES,
                        pickup_location_id BIGINT NOT NULL,
                        dropoff_location_id BIGINT NOT NULL,
                        trip_distance DOUBLE PRECISION,
                        fare_amount DOUBLE PRECISION
                );
                CREATE VIEW V AS SELECT
                *,
                COUNT(*) OVER(
                   PARTITION BY  pickup_location_id
                   ORDER BY  extract (EPOCH from  CAST (lpep_pickup_datetime AS TIMESTAMP) )
                   -- 1 hour is 3600  seconds
                   RANGE BETWEEN 3600  PRECEDING AND 1 PRECEDING ) AS count_trips_window_1h_pickup_zip,
                AVG(fare_amount) OVER(
                   PARTITION BY  pickup_location_id
                   ORDER BY  extract (EPOCH from  CAST (lpep_pickup_datetime AS TIMESTAMP) )
                   -- 1 hour is 3600  seconds
                   RANGE BETWEEN 3600  PRECEDING AND 1 PRECEDING ) AS mean_fare_window_1h_pickup_zip,
                COUNT(*) OVER(
                   PARTITION BY  dropoff_location_id
                   ORDER BY  extract (EPOCH from  CAST (lpep_dropoff_datetime AS TIMESTAMP) )
                   -- 0.5 hour is 1800  seconds
                   RANGE BETWEEN 1800  PRECEDING AND 1 PRECEDING ) AS count_trips_window_30m_dropoff_zip,
                case when extract (ISODOW from  CAST (lpep_dropoff_datetime AS TIMESTAMP))  > 5
                     then 1 else 0 end as dropoff_is_weekend
                FROM green_tripdata""";
        this.compileRustTestCase(sql);
    }

    @Test
    public void unionTest() {
        // Tests the monotone analyzer for the sum and distinct operators
        String sql = """
                CREATE TABLE series (
                    pickup TIMESTAMP NOT NULL LATENESS INTERVAL 1 HOUR
                );
                CREATE VIEW V AS SELECT DISTINCT * FROM
                ((SELECT * FROM series) UNION ALL
                 (SELECT pickup + INTERVAL 5 MINUTES FROM series));""";
        DBSPCompiler compiler = this.testCompiler();
        compiler.compileStatements(sql);
        CompilerCircuitStream ccs = new CompilerCircuitStream(compiler);
        this.addRustTestCase("unionTest", ccs);
        CircuitVisitor visitor = new CircuitVisitor(new StderrErrorReporter()) {
            int count = 0;

            @Override
            public void postorder(DBSPIntegrateTraceRetainKeysOperator operator) {
                this.count++;
            }

            @Override
            public void endVisit() {
                Assert.assertEquals(1, this.count);
            }
        };
        visitor.apply(ccs.circuit);
    }

    @Test
    public void nullableHoppingTest() {
        String sql = """
                CREATE TABLE series (
                    pickup TIMESTAMP
                );
                CREATE VIEW V AS
                SELECT * FROM TABLE(
                  HOP(
                    TABLE series,
                    DESCRIPTOR(pickup),
                    INTERVAL '2' MINUTE,
                    INTERVAL '5' MINUTE));""";
        DBSPCompiler compiler = this.testCompiler();
        compiler.compileStatements(sql);
        CompilerCircuitStream ccs = new CompilerCircuitStream(compiler);
        this.addRustTestCase("nullableHoppingTest", ccs);
    }

    @Test
    public void longIntervalHoppingTest() {
        String sql = """
                CREATE TABLE series (
                    pickup TIMESTAMP
                );
                CREATE VIEW V AS
                SELECT * FROM TABLE(
                  HOP(
                    TABLE series,
                    DESCRIPTOR(pickup),
                    INTERVAL 1 MONTH,
                    INTERVAL 2 MONTH));""";
        this.statementsFailingInCompilation(sql, "Hopping window intervals must be 'short'");
        sql = """
                CREATE TABLE series (
                    pickup TIMESTAMP
                );
                CREATE VIEW V AS
                SELECT * FROM TABLE(
                  HOP(
                    TABLE series,
                    DESCRIPTOR(pickup),
                    NULL,
                    NULL));""";
        this.statementsFailingInCompilation(sql, "Cannot apply 'HOP'");
        sql = """
                CREATE TABLE series (
                    pickup TIMESTAMP
                );
                CREATE VIEW V AS
                SELECT * FROM TABLE(
                  HOP(
                    TABLE series,
                    DESCRIPTOR(pickup),
                    6,
                    DATE '2020-12-20'));""";
        this.statementsFailingInCompilation(sql, "Cannot apply 'HOP'");
        sql = """
                CREATE TABLE series (
                    pickup TIMESTAMP
                );
                CREATE VIEW V AS
                SELECT * FROM TABLE(
                  HOP(
                    TABLE series,
                    DESCRIPTOR(pickup),
                    DESCRIPTOR(column),
                    INTERVAL 1 HOUR));""";
        this.statementsFailingInCompilation(sql, "Cannot apply 'HOP'");
    }

    @Test
    public void tumblingTestLimits() {
        String sql = """
               CREATE TABLE series (
                   pickup TIMESTAMP NOT NULL LATENESS INTERVAL '1:00' HOURS TO MINUTES
               );
               CREATE VIEW V AS
               SELECT TUMBLE_START(pickup, INTERVAL 30 MINUTES, TIME '00:12:00'),
                      TUMBLE_END(pickup, INTERVAL 30 MINUTES, TIME '00:12:00')
               FROM series
               GROUP BY TUMBLE(pickup, INTERVAL 30 MINUTES, TIME '00:12:00');""";

        DBSPCompiler compiler = this.testCompiler();
        compiler.compileStatements(sql);
        CompilerCircuitStream ccs = new CompilerCircuitStream(compiler);

        ccs.step("INSERT INTO series VALUES('2024-02-08 10:00:00')",
                """
                 start               | end                 | weight
                ----------------------------------------------------
                 2024-02-08 09:42:00 | 2024-02-08 10:12:00 | 1""");
        ccs.step("INSERT INTO series VALUES('2024-02-08 10:10:00')",
                """
                start              | end                 | weight
                ---------------------------------------------------"""); // same group
        ccs.step( "INSERT INTO series VALUES('2024-02-08 10:12:00')",
                """
                 start               | end                 | weight
                ----------------------------------------------------
                 2024-02-08 10:12:00 | 2024-02-08 10:42:00 | 1""");
        ccs.step("INSERT INTO series VALUES('2024-02-08 10:30:00')",
                """
                start              | end                 | weight
                ---------------------------------------------------"""); // same group as before

        this.addRustTestCase("tumblingTestLimits", ccs);
    }

    @Test
    public void tumblingTest() {
        String sql = """
                CREATE TABLE series (
                        distance DOUBLE,
                        pickup TIMESTAMP NOT NULL LATENESS INTERVAL '1:00' HOURS TO MINUTES
                );
                CREATE VIEW V AS
                SELECT AVG(distance), TUMBLE_START(pickup, INTERVAL '1' DAY) FROM series
                GROUP BY TUMBLE(pickup, INTERVAL '1' DAY)""";
        DBSPCompiler compiler = testCompiler();
        compiler.compileStatements(sql);
        CompilerCircuitStream ccs = new CompilerCircuitStream(compiler);
        ccs.step(
                "INSERT INTO series VALUES(10.0, '2023-12-30 10:00:00');",
                """
                 avg  | start | weight
                ----------------------
                 10.0 | 2023-12-30 00:00:00 | 1""");
        // Insert tuple before waterline, should be dropped
        ccs.step("INSERT INTO series VALUES(10.0, '2023-12-29 10:00:00');",
                """
                avg  | start | weight
                ----------------------""");
        // Insert tuple after waterline, should change average.
        // Waterline is advanced
        ccs.step("INSERT INTO series VALUES(20.0, '2023-12-30 10:10:00');",
                """
                 avg  | start | weight
                ----------------------
                 15.0 | 2023-12-30 00:00:00 | 1
                 10.0 | 2023-12-30 00:00:00 | -1""");
        // Insert tuple before last waterline, should be dropped
        ccs.step("INSERT INTO series VALUES(10.0, '2023-12-29 09:10:00');",
                """
                avg  | start | weight
                ----------------------""");
        // Insert tuple in the past, but before the last waterline
        ccs.step("INSERT INTO series VALUES(10.0, '2023-12-30 10:00:00');",
                """
                avg  | start | weight
                ----------------------
                13.333333333333334 | 2023-12-30 00:00:00 | 1
                15.0               | 2023-12-30 00:00:00 | -1""");
        // Insert tuple in the next tumbling window
        ccs.step("INSERT INTO series VALUES(10.0, '2023-12-31 10:00:00');",
                """
                avg  | start | weight
                ----------------------
                10.0 | 2023-12-31 00:00:00 | 1""");
        this.addRustTestCase("tumblingTest", ccs);
    }

    @Test
    public void blogTest() {
        String statements = """
                CREATE TABLE CUSTOMER(name VARCHAR NOT NULL, zipcode INT NOT NULL);
                CREATE VIEW DENSITY AS
                SELECT zipcode, COUNT(name)
                FROM CUSTOMER
                GROUP BY zipcode
                """;
        DBSPCompiler compiler = this.testCompiler();
        compiler.compileStatements(statements);
        Assert.assertFalse(compiler.hasErrors());
        CompilerCircuitStream ccs = new CompilerCircuitStream(compiler);
        ccs.step("",
                """
                 zipcode | count | weight
                --------------------------""");
        ccs.step("""
                 INSERT INTO customer VALUES('Bob', 1000);
                 INSERT INTO customer VALUES('Pam', 2000);
                 INSERT INTO customer VALUES('Sue', 3000);
                 INSERT INTO customer VALUES('Mike', 1000);""",
                """
                 zipcode | count | weight
                --------------------------
                 1000    | 2     | 1
                 2000    | 1     | 1
                 3000    | 1     | 1""");
        ccs.step("""
                REMOVE FROM customer VALUES('Bob', 1000);
                INSERT INTO customer VALUES('Bob', 2000);""",
                """
                 zipcode | count | weight
                --------------------------
                 1000    | 2     | -1
                 2000    | 1     | -1
                 2000    | 2     | 1
                 1000    | 1     | 1""");
        this.addRustTestCase("ivm blog post", ccs);
    }

    @Test
    public void nullableLatenessTest() {
        // LATENESS used on a nullable column
        String ddl = """
                CREATE TABLE series (
                        distance DOUBLE,
                        pickup TIMESTAMP LATENESS INTERVAL '1:00' HOURS TO MINUTES
                );
                CREATE VIEW V AS
                SELECT AVG(distance), CAST(pickup AS DATE) FROM series GROUP BY CAST(pickup AS DATE);""";
        DBSPCompiler compiler = testCompiler();
        compiler.compileStatements(ddl);
        Assert.assertFalse(compiler.hasErrors());
        CompilerCircuitStream ccs = new CompilerCircuitStream(compiler);
        this.addRustTestCase("nullableLatenessTest", ccs);
    }

    @Test
    public void watermarkTest() {
        String ddl = """
                CREATE TABLE series (
                        distance DOUBLE,
                        pickup TIMESTAMP NOT NULL WATERMARK INTERVAL '1:00' HOURS TO MINUTES
                );
                CREATE VIEW V AS
                SELECT AVG(distance), CAST(pickup AS DATE) FROM series GROUP BY CAST(pickup AS DATE)""";
        DBSPCompiler compiler = testCompiler();
        compiler.compileStatements(ddl);
        CompilerCircuitStream ccs = new CompilerCircuitStream(compiler);
        ccs.step("INSERT INTO series VALUES(10, '2023-12-30 10:00:00');",
                """
                         avg  | date       | weight
                        ---------------------------""");
        // Insert tuple before watermark, should be processed
        ccs.step("INSERT INTO series VALUES(10, '2023-12-29 10:00:00');",
                """
                         avg  | date       | weight
                        ---------------------------
                         10   | 2023-12-29 | 1""");
        // Insert tuple after waterline, but not after watermark
        // Waterline is advanced, no new outputs
        ccs.step("INSERT INTO series VALUES(20, '2023-12-30 10:10:00');",
                """
                         avg  | date        | weight
                        ---------------------------""");
        // Insert tuple before last waterline, should be processed
        // average does not change for 2023-12-19
        ccs.step("INSERT INTO series VALUES(10, '2023-12-29 09:10:00');",
                """
                 avg  | date       | weight
                ---------------------------""");
        // Insert tuple in the past, but before the last waterline
        // no new output
        ccs.step("INSERT INTO series VALUES(10, '2023-12-30 10:00:00');",
                """
                         avg  | date        | weight
                        ---------------------------""");
        // Insert one more tuple that accepts all buffered 3 tuples
        ccs.step("INSERT INTO series VALUES(10, '2023-12-31 10:00:00');",
                """
                         avg  | date        | weight
                        ---------------------------
                         13.333333333333334 | 2023-12-30 | 1""");
        this.addRustTestCase("latenessTest", ccs);
    }

    @Test
    public void latenessTest() {
        String ddl = """
                CREATE TABLE series (
                        distance DOUBLE,
                        pickup TIMESTAMP NOT NULL LATENESS INTERVAL '1:00' HOURS TO MINUTES
                );
                CREATE VIEW V AS
                SELECT AVG(distance), CAST(pickup AS DATE) FROM series GROUP BY CAST(pickup AS DATE);""";
        DBSPCompiler compiler = testCompiler();
        compiler.compileStatements(ddl);
        CompilerCircuitStream ccs = new CompilerCircuitStream(compiler);
        ccs.step("INSERT INTO series VALUES(10, '2023-12-30 10:00:00');",
                """
                         avg  | date       | weight
                        ---------------------------
                         10.0 | 2023-12-30 | 1""");
        // Insert tuple before waterline, should be dropped
        ccs.step("INSERT INTO series VALUES(10, '2023-12-29 10:00:00');",
                """
                         avg  | date       | weight
                        ---------------------------""");
        // Insert tuple after waterline, should change average.
        // Waterline is advanced
        ccs.step("INSERT INTO series VALUES(20, '2023-12-30 10:10:00');",
                """
                         avg  | date        | weight
                        ---------------------------
                         15.0 | 2023-12-30 | 1
                         10.0 | 2023-12-30 | -1""");
        // Insert tuple before last waterline, should be dropped
        ccs.step("INSERT INTO series VALUES(10, '2023-12-29 09:10:00');",
                        """
                         avg  | date       | weight
                        ---------------------------""");
        // Insert tuple in the past, but before the last waterline
        ccs.step("INSERT INTO series VALUES(10, '2023-12-30 10:00:00');",
                """
                         avg  | date        | weight
                        ---------------------------
                         15.0 | 2023-12-30 | -1
                         13.333333333333334 | 2023-12-30 | 1""");
        this.addRustTestCase("latenessTest", ccs);
    }

    Long[] measure(String program, String main) throws IOException, InterruptedException, SQLException {
        File script = createInputScript(program);
        CompilerMessages messages = CompilerMain.execute(
                "-o", BaseSQLTests.testFilePath, "--handles", "-i",
                script.getPath());
        System.out.println(messages);
        Assert.assertEquals(0, messages.errorCount());

        String mainFilePath = rustDirectory + "/main.rs";
        File file = new File(mainFilePath);
        try (PrintWriter mainFile = new PrintWriter(file, StandardCharsets.UTF_8)) {
            mainFile.print(main);
        }
        file.deleteOnExit();
        Utilities.compileAndTestRust(rustDirectory, true, "--release");
        File mainFile = new File(mainFilePath);
        boolean deleted = mainFile.delete();
        Assert.assertTrue(deleted);

        // After executing this Rust program the output is in file "mem.txt"
        // It contains three numbers: time taken (ms), memory used (bytes), and late records.
        String outFile = "mem.txt";
        Path outFilePath = Paths.get(rustDirectory, "..", outFile);
        List<String> strings = Files.readAllLines(outFilePath);
        // System.out.println(strings);
        Assert.assertEquals(1, strings.size());
        String[] split = strings.get(0).split(",");
        Assert.assertEquals(3, split.length);
        deleted = outFilePath.toFile().delete();
        Assert.assertTrue(deleted);
        return Linq.map(split, Long::parseLong, Long.class);
    }

    String createMain(String rustDataGenerator) {
        String preamble = """
                #[allow(unused_imports)]
                use dbsp::{
                    algebra::F64,
                    circuit::{
                        CircuitConfig,
                        metrics::TOTAL_LATE_RECORDS,
                    },
                    utils::{Tup2, Tup3},
                    zset,
                };

                use sqllib::{
                    append_to_collection_handle,
                    read_output_handle,
                    casts::cast_to_Timestamp_s,
                };

                use std::{
                    collections::HashMap,
                    io::Write,
                    ops::Add,
                    fs::File,
                    time::SystemTime,
                };
                
                use metrics::{Key, SharedString, Unit};
                use metrics_util::{
                    debugging::{DebugValue, DebuggingRecorder},
                    CompositeKey, MetricKind,
                };

                use temp::circuit;
                use dbsp::circuit::Layout;
                use uuid::Uuid;

                type MetricsSnapshot = HashMap<CompositeKey, (Option<Unit>, Option<SharedString>, DebugValue)>;

                fn parse_counter(metrics: &MetricsSnapshot, name: &'static str) -> u64 {
                    if let Some((_, _, DebugValue::Counter(value))) = metrics.get(&CompositeKey::new(
                        MetricKind::Counter,
                        Key::from_static_name(name),
                    )) {
                        *value
                    } else {
                        0
                    }
                }

                #[test]
                // Run the circuit generated by 'circuit' for a while then measure the
                // memory consumption.  Write the time taken and the memory used into
                // a file called "mem.txt".
                pub fn test() {
                    let recorder = DebuggingRecorder::new();
                    let snapshotter = recorder.snapshotter();
                    recorder.install().unwrap();
                
                    let (mut circuit, streams) = circuit(
                         CircuitConfig {
                             layout: Layout::new_solo(2),
                             storage: None,
                             min_storage_bytes: usize::MAX,
                             init_checkpoint: Uuid::nil(),
                         }).expect("could not build circuit");
                    let start = SystemTime::now();
                """;
        String postamble = """
                    let metrics = snapshotter.snapshot();
                    let decoded_metrics: MetricsSnapshot = metrics.into_hashmap();
                    let late = parse_counter(&decoded_metrics, TOTAL_LATE_RECORDS);
                
                    let profile = circuit.retrieve_profile().expect("could not get profile");
                    let end = SystemTime::now();
                    let duration = end.duration_since(start).expect("could not get time");

                    let mut data = String::new();
                    data.push_str(&format!("{},{},{}\\n",
                                           duration.as_millis(),
                                           profile.total_used_bytes().unwrap().bytes,
                                           late));
                    let mut file = File::create("mem.txt").expect("Could not create file");
                    file.write_all(data.as_bytes()).expect("Could not write data");
                    // println!("{:?},{:?},{:?}", duration, profile.total_used_bytes().unwrap(), late);
                }""";
        return preamble + rustDataGenerator + postamble;
    }

    static String stripLateness(String query) {
        String[] lines = query.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lateness = line.indexOf("LATENESS");
            if (lateness > 0) {
                boolean comma = line.endsWith(",");
                line = line.substring(0, lateness);
                if (comma) line += ",";
                lines[i] = line;
            }
        }
        return String.join("\n", lines);
    }

    @Test
    public void profileLateness() throws IOException, InterruptedException, SQLException {
        String sql = """
                CREATE TABLE series (
                        distance DOUBLE,
                        pickup TIMESTAMP NOT NULL LATENESS INTERVAL '1:00' HOURS TO MINUTES
                );
                CREATE VIEW V AS
                SELECT AVG(distance), CAST(pickup AS DATE)
                FROM series GROUP BY CAST(pickup AS DATE);
                """;
        // Rust program which profiles the circuit.
        String main = this.createMain("""
                    // Initial data value for timestamp
                    let mut timestamp = cast_to_Timestamp_s("2024-01-10 10:10:10".to_string());
                    for i in 0..1000000 {
                        let value = Some(F64::new(i.into()));
                        timestamp = timestamp.add(20000);
                        let input = zset!(Tup2::new(value, timestamp) => 1);
                        append_to_collection_handle(&input, &streams.0);
                        if i % 1000 == 0 {
                            let _ = circuit.step().expect("could not run circuit");
                            let _ = &read_output_handle(&streams.1);
                            /*
                            let end = SystemTime::now();
                            let profile = circuit.retrieve_profile().expect("could not get profile");
                            let duration = end.duration_since(start).expect("could not get time");
                            println!("{:?},{:?}", duration.as_millis(), profile.total_used_bytes().unwrap().bytes);
                            */
                        }
                    }""");
        this.profile(sql, main);
    }

    void profile(String sql, String main) throws SQLException, IOException, InterruptedException {
        Long[] p0 = this.measure(stripLateness(sql), main);
        Long[] p1 = this.measure(sql, main);
        // Memory consumption of program with lateness is expected to be higher
        if (p0[1] < 1.5 * p1[1]) {
            System.err.println("Profile statistics without and with lateness:");
            System.err.println(Arrays.toString(p0));
            System.err.println(Arrays.toString(p1));
            assert false;
        }
        // No late records
        assert p0[2] == 0 && p1[2] == 0;
    }

    @Test
    public void testJoin() {
        String ddl = """
            CREATE TABLE series (
                    metadata VARCHAR,
                    event_time TIMESTAMP NOT NULL LATENESS INTERVAL '1:00' HOURS TO MINUTES
            );
            
            CREATE TABLE shift(
                    person VARCHAR,
                    on_call DATE
            );
            CREATE VIEW V AS SELECT metadata, person FROM series
            JOIN shift ON CAST(series.event_time AS DATE) = shift.on_call;""";
        DBSPCompiler compiler = testCompiler();
        compiler.compileStatements(ddl);
        CompilerCircuitStream ccs = new CompilerCircuitStream(compiler);
        this.addRustTestCase("testJoin", ccs);
        CircuitVisitor visitor = new CircuitVisitor(new StderrErrorReporter()) {
            int count = 0;

            @Override
            public void postorder(DBSPIntegrateTraceRetainKeysOperator operator) {
                this.count++;
            }

            @Override
            public void endVisit() {
                Assert.assertEquals(1, this.count);
            }
        };
        visitor.apply(ccs.circuit);
    }

    // Test for https://github.com/feldera/feldera/issues/1462
    @Test
    public void testJoinNonMonotoneColumn() {
        String script = """
            CREATE TABLE series (
                    metadata VARCHAR NOT NULL,
                    event_time TIMESTAMP NOT NULL LATENESS INTERVAL '1:00' HOURS TO MINUTES
            );
            
            CREATE TABLE shift(
                    person VARCHAR NOT NULL,
                    on_call DATE
            );
        
            CREATE VIEW V AS
            (SELECT * FROM series JOIN shift ON series.metadata = shift.person);
            """;
        DBSPCompiler compiler = testCompiler();
        compiler.compileStatements(script);
        CompilerCircuitStream ccs = new CompilerCircuitStream(compiler);
        this.addRustTestCase("testJoinNonMonotoneColumn", ccs);
        CircuitVisitor visitor = new CircuitVisitor(new StderrErrorReporter()) {
            int count = 0;

            @Override
            public void postorder(DBSPIntegrateTraceRetainKeysOperator operator) {
                this.count++;
            }

            @Override
            public void endVisit() {
                Assert.assertEquals(0, this.count);
            }
        };
        visitor.apply(ccs.circuit);
    }

    @Test
    public void testJoinTwoColumns() {
        // One joined column is monotone, the other one isn't.
        String script = """
            CREATE TABLE series (
                    metadata VARCHAR NOT NULL,
                    event_time TIMESTAMP NOT NULL LATENESS INTERVAL '1:00' HOURS TO MINUTES
            );
            
            CREATE TABLE shift(
                    person VARCHAR NOT NULL,
                    on_call DATE
            );
        
            CREATE VIEW V AS
            (SELECT * FROM series JOIN shift
             ON series.metadata = shift.person AND CAST(series.event_time AS DATE) = shift.on_call);
            """;
        DBSPCompiler compiler = testCompiler();
        compiler.compileStatements(script);
        CompilerCircuitStream ccs = new CompilerCircuitStream(compiler);
        this.addRustTestCase("testJoinTwoColumns", ccs);
        CircuitVisitor visitor = new CircuitVisitor(new StderrErrorReporter()) {
            int count = 0;

            @Override
            public void postorder(DBSPIntegrateTraceRetainKeysOperator operator) {
                this.count++;
            }

            @Override
            public void endVisit() {
                Assert.assertEquals(1, this.count);
            }
        };
        visitor.apply(ccs.circuit);
    }

    @Test
    public void testJoinFilter() {
        // Join two streams with lateness, and filter based on lateness column
        String script = """
            CREATE TABLE series (
                    metadata VARCHAR NOT NULL,
                    event_date DATE NOT NULL LATENESS INTERVAL 1 DAYS
            );
            
            CREATE TABLE shift(
                    person VARCHAR NOT NULL,
                    on_call DATE NOT NULL LATENESS INTERVAL 1 DAYS
            );
        
            CREATE VIEW V AS
            (SELECT metadata, event_date FROM series JOIN shift
             ON series.metadata = shift.person AND event_date > on_call);
            """;
        DBSPCompiler compiler = testCompiler();
        compiler.compileStatements(script);
        CompilerCircuitStream ccs = new CompilerCircuitStream(compiler);
        this.addRustTestCase("testJoinFilter", ccs);
        CircuitVisitor visitor = new CircuitVisitor(new StderrErrorReporter()) {
            int count = 0;

            @Override
            public void postorder(DBSPIntegrateTraceRetainKeysOperator operator) {
                this.count++;
            }

            @Override
            // TODO: should be 1
            public void endVisit() {
                Assert.assertEquals(0, this.count);
            }
        };
        visitor.apply(ccs.circuit);
    }

    @Test
    public void testAggregate() {
        String sql = """
                CREATE TABLE event_t (
                    event_type_id BIGINT NOT NULL
                );

                -- running total of event types
                CREATE VIEW event_type_count_v AS
                SELECT count(DISTINCT event_type_id) as event_type_count
                from   event_t
                ;""";
        DBSPCompiler compiler = testCompiler();
        compiler.compileStatements(sql);
        CompilerCircuitStream ccs = new CompilerCircuitStream(compiler);
        ccs.step("",
                """
                 event_type_count | weight
                ---------------------------
                 0                | 1""");
        ccs.step("",
                """
                 event_type_count | weight
                ---------------------------""");
        ccs.step("INSERT INTO event_t VALUES(1);",
                 """
                 event_type_count | weight
                ---------------------------
                 0                | -1
                 1                | 1""");
        ccs.step("",
                """
                 event_type_count | weight
                ---------------------------""");
        ccs.step("INSERT INTO event_t VALUES(2);",
                """
                 event_type_count | weight
                ---------------------------
                 1                | -1
                 2                | 1""");
        this.addRustTestCase("testAggregate", ccs);
    }

    @Test
    public void testHopWindows() {
        String sql = """
                CREATE TABLE DATA(
                    moment TIMESTAMP NOT NULL LATENESS INTERVAL 1 DAYS,
                    amount DECIMAL(10, 2),
                    cc_num VARCHAR
                );
                
                CREATE LOCAL VIEW hop AS
                SELECT * FROM TABLE(HOP(TABLE DATA, DESCRIPTOR(moment), INTERVAL 4 HOURS, INTERVAL 1 HOURS));
                
                CREATE LOCAL VIEW agg AS
                SELECT
                  AVG(amount) AS avg_amt,
                  STDDEV(amount) as stddev_amt,
                  COUNT(cc_num) AS trans,
                  ARRAY_AGG(moment) AS moments
                FROM hop
                GROUP BY cc_num, window_start;
                
                CREATE VIEW results AS
                SELECT
                  avg_amt,
                  COALESCE(stddev_amt, 0) AS stddev_amt,
                  trans,
                  moment
                FROM agg CROSS JOIN UNNEST(moments) as moment;
                """;
        DBSPCompiler compiler = testCompiler();
        compiler.compileStatements(sql);
        CompilerCircuitStream ccs = new CompilerCircuitStream(compiler);
        this.addRustTestCase("testHopWindows", ccs);
    }
}
