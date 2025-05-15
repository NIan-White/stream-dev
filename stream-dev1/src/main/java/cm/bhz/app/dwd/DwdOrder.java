package cm.bhz.app.dwd;

import cm.bhz.*;
import cm.bhz.app.dim.DimBaseCategory;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.bhz.util.*;


import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;

import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.time.Time;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;

/**
 * @Package com.lzr.dmp.dwd.DwdOrder
 * @Author lv.zirao
 * @Date 2025/5/14 19:21
 * @description: Flink订单处理，包含时间戳转换
 */
public class DwdOrder {
    private static final List<DimBaseCategory> dim_base_categories;
    private static final Connection connection;

    private static final double time_rate_weight_coefficient = 0.1;    // 时间权重系数
    private static final double amount_rate_weight_coefficient = 0.15;    // 价格权重系数
    private static final double brand_rate_weight_coefficient = 0.2;    // 品牌权重系数
    private static final double category_rate_weight_coefficient = 0.3; // 类目权重系数

    static {
        try {
            connection = JdbcUtil.getMySQLConnection();
            String sql = "select b3.id,                          \n" +
                    "            b3.name3 as b3name,              \n" +
                    "            b2.name2 as b2name,              \n" +
                    "            b1.name1 as b1name               \n" +
                    "     from realtime_dmp.base_category3 as b3  \n" +
                    "     join realtime_dmp.base_category2 as b2  \n" +
                    "     on b3.category2_id = b2.id             \n" +
                    "     join realtime_dmp.base_category1 as b1  \n" +
                    "     on b2.category1_id = b1.id";
            dim_base_categories = JdbcUtil.queryList(connection, sql, DimBaseCategory.class, false);

            for (DimBaseCategory dimBaseCategory : dim_base_categories) {
                System.err.println(dimBaseCategory);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }
    public static void main(String[] args) throws Exception {
        // 1. 环境初始化与数据源配置
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        KafkaSource<String> kafkaSource = FlinkSourceUtil.getKafkaSource("topic_dmp_db", "dwd_app");
        DataStreamSource<String> kafkaSource1 = env.fromSource(kafkaSource, WatermarkStrategy.noWatermarks(), "kafka_source");

        SingleOutputStreamOperator<JSONObject> streamOperatorlog = kafkaSource1.map(JSON::parseObject)
                .assignTimestampsAndWatermarks(WatermarkStrategy.<JSONObject>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                        .withTimestampAssigner(new SerializableTimestampAssigner<JSONObject>() {
                            @Override
                            public long extractTimestamp(JSONObject element, long recordTimestamp) {
                                return element.getLong("ts_ms");
                            }
                        }));

        // 过滤订单主表数据
        SingleOutputStreamOperator<JSONObject> orderDs = streamOperatorlog.filter(data ->
                data.getJSONObject("source").getString("table").equals("order_info"));

        // 过滤订单明细表数据
        SingleOutputStreamOperator<JSONObject> detailDs = streamOperatorlog.filter(data ->
                data.getJSONObject("source").getString("table").equals("order_detail"));

//        订单主表数据处理
        SingleOutputStreamOperator<JSONObject> mapOrderInfo = orderDs.map(new MapOrderInfoDataFunc());
//        mapOrderInfo.print();
//        订单明细表数据处理
        SingleOutputStreamOperator<JSONObject> mapOrderDetailDs = detailDs.map(new MapOrderDetailFunc());
//        数据过滤与键控
//        对订单主表按订单 ID 进行键控，对订单明细表按关联的订单 ID 进行键控
        SingleOutputStreamOperator<JSONObject> filterOrderInfoDs = mapOrderInfo.filter(data -> data.getString("id") != null && !data.getString("id").isEmpty());
        SingleOutputStreamOperator<JSONObject> filterOrderDetailDs = mapOrderDetailDs.filter(data -> data.getString("order_id") != null && !data.getString("order_id").isEmpty());

        KeyedStream<JSONObject, String> keyedStreamOrderInfoDs = filterOrderInfoDs.keyBy(data -> data.getString("id"));
        KeyedStream<JSONObject, String> keyedStreamOrderDetailDs = filterOrderDetailDs.keyBy(data -> data.getString("order_id"));

//        时间窗口内的关联操作
        SingleOutputStreamOperator<JSONObject> pro = keyedStreamOrderInfoDs.intervalJoin(keyedStreamOrderDetailDs)
                .between(Time.minutes(-2), Time.minutes(2))
                .process(new IntervalDbOrderInfoJoinOrderDetailProcessFunc());
//        pro.print();

        SingleOutputStreamOperator<JSONObject> prc = pro.keyBy(data -> data.getString("detail_id"))
                .process(new processOrderInfoAndDetailFunc());
//        prc.print();

        SingleOutputStreamOperator<JSONObject> mapOrderInfoAndDetailModelDs = prc.map(new MapOrderAndDetailRateModelFunc(dim_base_categories, time_rate_weight_coefficient, amount_rate_weight_coefficient, brand_rate_weight_coefficient, category_rate_weight_coefficient));
        mapOrderInfoAndDetailModelDs.print();

        mapOrderInfoAndDetailModelDs.map(JSONObject::toString).sinkTo(FlinkSinkUtil.getKafkaSink("kafka_label_base4_topic"));

        env.execute();
    }
}