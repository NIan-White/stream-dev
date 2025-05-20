package org.bhz.app.dwd;

import com.alibaba.fastjson.JSONObject;

import com.bhz.constant.Constant;
import com.bhz.util.FlinkSinkUtil;
import com.bhz.util.FlinkSourceUtil;
import lombok.SneakyThrows;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.co.ProcessJoinFunction;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.util.Collector;
import org.bhz.bean.DimBaseCategory;
import org.bhz.func.MapCategoryAndTrademarkAndPriceAndTimeFunc;
import org.bhz.utils.JdbcUtils;

import java.math.BigDecimal;
import java.sql.Connection;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.List;

/**
 * @Package com.xwz.retail.v2.reaktime.app.dwd.UserOderInfoLabel
 * @Author Wenzhen.Xie
 * @Date 2025/5/14 18:10
 * @description:
 */

public class UserOderInfoLabel {
    private static final List<DimBaseCategory> dim_base_categories;
    private static final Connection connection;

    //private static final double device_rate_weight_coefficient = 0.1; // 设备权重系数
    //private static final double search_rate_weight_coefficient = 0.15; // 搜索权重系数
    private static final double category_rate_weight_coefficient = 0.3; // 类目权重系数
    private static final double trademark_rate_weight_coefficient = 0.2; // 品牌权重系数
    private static final double price_rate_weight_coefficient = 0.15; // 价格权重系数
    private static final double time_rate_weight_coefficient = 0.1; // 时间权重系数

    static {
        try {
            connection = JdbcUtils.getMySQLConnection(
                    Constant.MYSQL_URL,
                    Constant.MYSQL_USER_NAME,
                    Constant.MYSQL_PASSWORD);
            String sql = "select b3.id,                          \n" +
                    "            b3.name as b3name,              \n" +
                    "            b2.name as b2name,              \n" +
                    "            b1.name as b1name               \n" +
                    "     from realtime_v1.base_category3 as b3  \n" +
                    "     join realtime_v1.base_category2 as b2  \n" +
                    "     on b3.category2_id = b2.id             \n" +
                    "     join realtime_v1.base_category1 as b1  \n" +
                    "     on b2.category1_id = b1.id";
            dim_base_categories = JdbcUtils.queryList2(connection, sql, DimBaseCategory.class, false);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SneakyThrows
    public static void main(String[] args) {
        System.getProperty("HADOOP_USER_NAME","root");
        //todo 获取环境
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        //todo 设置并行度
        env.setParallelism(1);
        //todo 设置 Checkpoint 模式为精确一次 (默认)
        env.enableCheckpointing(5000L, CheckpointingMode.EXACTLY_ONCE);

        //todo 获取kafka主题数据
        //todo 从 Kafka 读取 CDC 变更数据，创建一个字符串类型的数据流
        SingleOutputStreamOperator<String> kafkaSourceDs = env.fromSource(
                FlinkSourceUtil.getKafkaSource("Damopan_topic_db", "kafka_source_order_info"),
                WatermarkStrategy.<String>forBoundedOutOfOrderness(Duration.ofSeconds(3))
                        .withTimestampAssigner((event, timestamp) -> {
                                    JSONObject jsonObject = JSONObject.parseObject(event);
                                    if (event != null && jsonObject.containsKey("ts_ms")){
                                        try {
                                            return JSONObject.parseObject(event).getLong("ts_ms");
                                        }catch (Exception e){
                                            e.printStackTrace();
                                            System.err.println("Failed to parse event as JSON or get ts_ms: " + event);
                                            return 0L;
                                        }
                                    }
                                    return 0L;
                                }
                        ),
                "kafka_source_order_info"
        ).uid("kafka source order info").name("kafka source order info");
        //kafkaSourceDs.print("kafkaSourceDs ->");

        SingleOutputStreamOperator<JSONObject> mapKafkaSourceDs = kafkaSourceDs.map(JSONObject::parseObject);
        //mapKafkaSourceDs.print("mapKafkaSourceDs ->");

        SingleOutputStreamOperator<JSONObject> orderInfoFilterDs = mapKafkaSourceDs
                .filter(data -> data.getJSONObject("source").getString("table").equals("order_info"))
                .uid("filter user_info data")
                .name("filter user_info data");
        //orderInfoFilterDs.print("orderInfoFilterDs ->");

        SingleOutputStreamOperator<JSONObject> orderDetailFilterDs = mapKafkaSourceDs
                .filter(data -> data.getJSONObject("source").getString("table").equals("order_detail"))
                .uid("filter order_detail data")
                .name("filter order_detail data");
        //orderDetailFilterDs.print("orderDetailFilterDs ->");

        SingleOutputStreamOperator<JSONObject> skuInfoFilterDs = mapKafkaSourceDs
                .filter(data -> data.getJSONObject("source").getString("table").equals("sku_info"))
                .uid("filter sku_info data")
                .name("filter sku_info data");
        //skuInfoFilterDs.print("skuInfoFilterDs ->");

        SingleOutputStreamOperator<JSONObject> baseTrademarkFilterDs = mapKafkaSourceDs
                .filter(data -> data.getJSONObject("source").getString("table").equals("base_trademark"))
                .uid("filter base_trademark data")
                .name("filter base_trademark data");
        //skuInfoFilterDs.print("skuInfoFilterDs ->");

        SingleOutputStreamOperator<JSONObject> mapOrderInfoDs = orderInfoFilterDs.map(new RichMapFunction<JSONObject, JSONObject>() {
            @Override
            public JSONObject map(JSONObject jsonObject) {
                JSONObject result = new JSONObject();
                if (jsonObject.containsKey("after")) {
                    JSONObject after = jsonObject.getJSONObject("after");
                    if (after != null) {
                        result.put("id", after.getString("id"));
                        result.put("total_amount", after.getBigDecimal("total_amount"));
                        result.put("user_id", after.getString("user_id"));
                        result.put("create_time", after.getLong("create_time"));
                        result.put("original_total_amount", after.getBigDecimal("original_total_amount"));
                        String priceRange = obtainThePriceRange(after.getBigDecimal("original_total_amount"));
                        result.put("price_range", priceRange);
                        Date date = new Date(after.getLong("create_time"));
                        // 2. 创建 SimpleDateFormat 并设置目标时区
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                        // 3. 格式化日期
                        String formattedDate = sdf.format(date);
                        result.put("create_date", formattedDate);
                        String timePeriod = obtainTheTimePeriod(date.getHours());
                        result.put("time_period", timePeriod);
                        result.put("ts_ms", jsonObject.getLong("ts_ms"));
                    }
                }
                return result;
            }
        }).uid("filter map order_info data").name("filter map order_info data");
        //mapOrderInfoDs.print("mapOrderInfoDs ->");

        SingleOutputStreamOperator<JSONObject> mapOrderDetailDs = orderDetailFilterDs.map(new RichMapFunction<JSONObject, JSONObject>() {
            @Override
            public JSONObject map(JSONObject jsonObject) {
                JSONObject result = new JSONObject();
                if (jsonObject.containsKey("after")) {
                    JSONObject after = jsonObject.getJSONObject("after");
                    if (after != null) {
                        result.put("id", after.getString("id"));
                        result.put("order_id", after.getString("order_id"));
                        result.put("sku_id", after.getString("sku_id"));
                        result.put("sku_name", after.getString("sku_name"));
                        result.put("ts_ms", jsonObject.getLong("ts_ms"));
                    }
                }
                return result;
            }
        });
        //mapOrderDetailDs.print("mapOrderDetailDs ->");

        SingleOutputStreamOperator<JSONObject> mapBaseTrademarkDs = baseTrademarkFilterDs.map(new RichMapFunction<JSONObject, JSONObject>() {
            @Override
            public JSONObject map(JSONObject jsonObject) {
                JSONObject result = new JSONObject();
                if (jsonObject.containsKey("after")) {
                    JSONObject after = jsonObject.getJSONObject("after");
                    if (after != null) {
                        result.put("id", after.getString("id"));
                        result.put("tm_name", after.getString("tm_name"));
                        result.put("ts_ms", jsonObject.getLong("ts_ms"));
                    }
                }
                return result;
            }
        });
        //mapBaseTrademarkDs.print("mapBaseTrademarkDs ->");

        SingleOutputStreamOperator<JSONObject> mapSkuInfoDs = skuInfoFilterDs.map(new RichMapFunction<JSONObject, JSONObject>() {
            @Override
            public JSONObject map(JSONObject jsonObject) {
                JSONObject result = new JSONObject();
                if (jsonObject.containsKey("after")) {
                    JSONObject after = jsonObject.getJSONObject("after");
                    if (after != null) {
                        result.put("id", after.getString("id"));
                        result.put("tm_id", after.getString("tm_id"));
                        result.put("category3_id", after.getString("category3_id"));
                        result.put("ts_ms", jsonObject.getLong("ts_ms"));
                    }
                }
                return result;
            }
        });
        //mapSkuInfoDs.print("mapSkuInfoDs ->");

        SingleOutputStreamOperator<JSONObject> filterNotNullCdcOrderInfoDs = mapOrderInfoDs.filter(data -> data.getString("id") != null && !data.getString("id").isEmpty());
        SingleOutputStreamOperator<JSONObject> filterNotNullCdcOrderDetailDs = mapOrderDetailDs.filter(data -> data.getString("order_id") != null && !data.getString("order_id").isEmpty());
        SingleOutputStreamOperator<JSONObject> filterNotNullCdcSkuInfoDs = mapSkuInfoDs.filter(data -> data.getString("id") != null && !data.getString("id").isEmpty());
        SingleOutputStreamOperator<JSONObject> filterNotNullCdcBaseTrademarkDs = mapBaseTrademarkDs.filter(data -> data.getString("id") != null && !data.getString("id").isEmpty());

        KeyedStream<JSONObject, String> keyedOrderInfoDs = filterNotNullCdcOrderInfoDs.keyBy(data -> data.getString("id"));
        KeyedStream<JSONObject, String> keyedOrderDetailDs = filterNotNullCdcOrderDetailDs.keyBy(data -> data.getString("order_id"));
        KeyedStream<JSONObject, String> keyedSkuInfoDs = filterNotNullCdcSkuInfoDs.keyBy(data -> data.getString("id"));
        KeyedStream<JSONObject, String> keyedBaseTrademark = filterNotNullCdcBaseTrademarkDs.keyBy(data -> data.getString("id"));

        SingleOutputStreamOperator<JSONObject> processOrderInfoJoinOrderDetail = keyedOrderInfoDs.intervalJoin(keyedOrderDetailDs)
                .between(Time.minutes(-2), Time.minutes(2))
                .process(new ProcessJoinFunction<JSONObject, JSONObject, JSONObject>() {

                    @Override
                    public void processElement(JSONObject left, JSONObject right, ProcessJoinFunction<JSONObject, JSONObject, JSONObject>.Context ctx, Collector<JSONObject> out) {
                        JSONObject result = new JSONObject();
                        if (left.getString("id").equals(right.getString("order_id"))) {
                            result.putAll(left);
                            result.put("order_detail_id", right.getString("id"));
                            result.put("sku_id", right.getString("sku_id"));
                            result.put("sku_name", right.getString("sku_name"));
                        }
                        out.collect(result);
                    }
                });
        //processOrderInfoJoinOrderDetail.print("processOrderInfoJoinOrderDetail ->");

        SingleOutputStreamOperator<JSONObject> processOrderJoinSkuInfo = processOrderInfoJoinOrderDetail.keyBy(data -> data.getString("sku_id")).intervalJoin(keyedSkuInfoDs)
                .between(Time.minutes(-2), Time.minutes(2))
                .process(new ProcessJoinFunction<JSONObject, JSONObject, JSONObject>() {
                    @Override
                    public void processElement(JSONObject left, JSONObject right, ProcessJoinFunction<JSONObject, JSONObject, JSONObject>.Context ctx, Collector<JSONObject> out) {
                        JSONObject result = new JSONObject();
                        if (left.getString("sku_id").equals(right.getString("id"))) {
                            result.putAll(left);
                            result.put("tm_id", right.getString("tm_id"));
                            result.put("category3_id", right.getString("category3_id"));
                        }
                        out.collect(result);
                    }
                });
        //processOrderJoinSkuInfo.print("processOrderJoinSkuInfo ->");

        SingleOutputStreamOperator<JSONObject> processSkuInfoJoinBaseTrademark = processOrderJoinSkuInfo.keyBy(data -> data.getString("tm_id")).intervalJoin(keyedBaseTrademark)
                .between(Time.minutes(-2), Time.minutes(2))
                .process(new ProcessJoinFunction<JSONObject, JSONObject, JSONObject>() {
                    @Override
                    public void processElement(JSONObject left, JSONObject right, ProcessJoinFunction<JSONObject, JSONObject, JSONObject>.Context ctx, Collector<JSONObject> out) {
                        JSONObject result = new JSONObject();
                        if (left.getString("tm_id").equals(right.getString("id"))) {
                            result.putAll(left);
                            result.put("tm_name", right.getString("tm_name"));
                        }
                        out.collect(result);
                    }
                });
        //processSkuInfoJoinBaseTrademark.print("processSkuInfoJoinCategory ->");

        SingleOutputStreamOperator<JSONObject> mapOrderInfoAndDetailModelDs = processSkuInfoJoinBaseTrademark.map(new MapCategoryAndTrademarkAndPriceAndTimeFunc(dim_base_categories, category_rate_weight_coefficient, trademark_rate_weight_coefficient,price_rate_weight_coefficient,time_rate_weight_coefficient));
        mapOrderInfoAndDetailModelDs.print("mapOrderInfoAndDetailModelDs ->");

        /*SingleOutputStreamOperator<JSONObject> mapOrderInfoSummary = processSkuInfoJoinBaseTrademark.map(new RichMapFunction<JSONObject, JSONObject>() {
            @Override
            public JSONObject map(JSONObject jsonObject) {
                String category3Id = jsonObject.getString("category3_id");
                if (category3Id != null && !category3Id.isEmpty()) {
                    for (DimBaseCategory dimBaseCategory : dim_base_categories) {
                        if (dimBaseCategory.getId().equals(category3Id)) {
                            jsonObject.put("b3name", dimBaseCategory.getB3name());
                            jsonObject.put("b2name", dimBaseCategory.getB2name());
                            jsonObject.put("b1name", dimBaseCategory.getB1name());
                        }
                    }
                }
                return jsonObject;
            }
        });*/

        mapOrderInfoAndDetailModelDs.map(JSONObject::toString).sinkTo(FlinkSinkUtil.getKafkaSink("dwd_order_info_base_label"));

        env.disableOperatorChaining();
        env.execute("DbCdcOrderInfoBaseLabel");
    }

    private static String obtainThePriceRange(BigDecimal originalTotalAmount) {
        double priceRange = originalTotalAmount.doubleValue();
        if (priceRange < 1000) return "低价商品";
        else if (priceRange <4000) return "中价商品";
        else return "高价商品";
    }


    private static String obtainTheTimePeriod(int hour) {
        if(hour < 6)        return "凌晨";
        else if(hour < 9)   return "早晨";
        else if(hour < 12)  return "上午";
        else if(hour < 14)  return "中午";
        else if(hour < 18)  return "下午";
        else if(hour < 22)  return "晚上";
        else return "夜间";
    }
}
