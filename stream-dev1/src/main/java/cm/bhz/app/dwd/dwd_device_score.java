package cm.bhz.app.dwd;

import cm.bhz.bean.DimBaseCategory;
import cm.bhz.function.MapDeviceAndSearchMarkModelFunc;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.bhz.util.*;
import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;

/**
 * @Package com.lzr.dmp.dwd.dwd_device_score
 * @Author lv.zirao
 * @Date 2025/5/14 14:32
 * @description:
 */
public class dwd_device_score {
    private static final List<DimBaseCategory> dim_base_categories;
    private static final Connection connection;
    private static final double device_rate_weight_coefficient = 0.1; // 设备权重系数
    private static final double search_rate_weight_coefficient = 0.15; // 搜索权重系数


    static {
        try {
            connection = JdbcUtil.getMySQLConnection();
            String sql = "select b3.id,                          \n" +
                    "            b3.name as b3name,              \n" +
                    "            b2.name as b2name,              \n" +
                    "            b1.name as b1name               \n" +
                    "     from realtime_v1.base_category3 as b3  \n" +
                    "     join realtime_v1.base_category2 as b2  \n" +
                    "     on b3.category2_id = b2.id             \n" +
                    "     join realtime_v1.base_category1 as b1  \n" +
                    "     on b2.category1_id = b1.id";
            dim_base_categories = JdbcUtil.queryList(connection, sql, DimBaseCategory.class, false);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }
    public static void main(String[] args) throws Exception {
        //        环境设置和 Kafka 数据源获取：
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        // 设置并行度为 1。
        env.setParallelism(1);
        KafkaSource<String> kafkaSourceLog = FlinkSourceUtil.getKafkaSource("minutes_page_log", "dwd_app");
//        从 Kafka 读取数据并处理时间戳：
        SingleOutputStreamOperator<String> kafka_source_log = env.fromSource(kafkaSourceLog, WatermarkStrategy.noWatermarks(), "Kafka Source");
        SingleOutputStreamOperator<JSONObject> streamOperatorlog = kafka_source_log.map(JSON::parseObject)
                .assignTimestampsAndWatermarks(WatermarkStrategy.<JSONObject>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                        .withTimestampAssigner(new SerializableTimestampAssigner<JSONObject>() {
                                                   @Override
                                                   public long extractTimestamp(JSONObject element, long recordTimestamp) {
                                                       // 添加空值检查
                                                       if (element == null || element.get("ts") == null) {
                                                           // 处理缺失时间戳的情况
                                                           return System.currentTimeMillis();
                                                       }
                                                       return element.getLongValue("ts");
                                                   }
                                               }
                        )
                );
//        streamOperatorlog.print();

//      设备打分模型
        SingleOutputStreamOperator<JSONObject> deviceScoredStream = streamOperatorlog.map(new MapDeviceAndSearchMarkModelFunc(dim_base_categories, device_rate_weight_coefficient, search_rate_weight_coefficient));
        deviceScoredStream.print();
        deviceScoredStream.map(JSONObject::toString).sinkTo(FlinkSinkUtil.getKafkaSink("kafka_label_base2_topic"));




        env.execute();
    }
}
