package cm.bhz.app.ods;
/**
 * @Package cm.bhz.app.ods.MysqlToKafka
 * @Author huizhong.bai
 * @Date 2025/5/12 09:57
 * @description:
 */

import cm.bhz.util.FlinkSinkUtil;
import cm.bhz.util.FlinkSourceUtil;
import lombok.SneakyThrows;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import com.ververica.cdc.connectors.mysql.source.MySqlSource;
/**
 * @Package cm.bhz.app.ods.MysqlToKafka
 * @Author huizhong.bai
 * @Date 2025/5/12 09:57
 * @description:
 */
public class MysqlToKafka {
    @SneakyThrows
    public static void main(String[] args) {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        MySqlSource<String> realtimeV1 = FlinkSourceUtil.getMySqlSource("realtime_v1", "*");

        DataStreamSource<String> mySQLSource = env.fromSource(realtimeV1, WatermarkStrategy.noWatermarks(), "MySQL Source");

        mySQLSource.print();

        KafkaSink<String> topic_db = FlinkSinkUtil.getKafkaSink("Damopan_topic_db");

        mySQLSource.sinkTo(topic_db);

        env.execute("Print MySQL Snapshot + Binlog");
    }
}
