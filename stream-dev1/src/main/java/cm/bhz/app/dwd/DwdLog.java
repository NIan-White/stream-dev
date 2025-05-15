package cm.bhz.app.dwd;

import cm.bhz.ProcessFilterRepeatTsData;
import cm.bhz.function.AggregateUserDataProcessFunction;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.bhz.util.FlinkSinkUtil;
import com.bhz.util.FlinkSourceUtil;
import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import java.time.Duration;

/**
 * @Package com.lzr.dmp.dwd.DwdLog
 * @Author lv.zirao
 * @Date 2025/5/14 9:58
 * @description:
 *  日志
 */
public class DwdLog {
    public static void main(String[] args) throws Exception {
        // 环境设置和 Kafka 数据源获取：
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        // 设置并行度为 1。
        env.setParallelism(1);

        KafkaSource<String> kafkaSourceLog = FlinkSourceUtil.getKafkaSource("topic_log", "dwd_app");
        // 从 Kafka 读取数据并处理时间戳：
        SingleOutputStreamOperator<String> kafka_source_log = env.fromSource(kafkaSourceLog, WatermarkStrategy.noWatermarks(), "Kafka Source");

        SingleOutputStreamOperator<JSONObject> streamOperatorlog = kafka_source_log.map(JSON::parseObject)
                .assignTimestampsAndWatermarks(WatermarkStrategy.<JSONObject>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                        .withTimestampAssigner(new SerializableTimestampAssigner<JSONObject>() {
                            @Override
                            public long extractTimestamp(JSONObject element, long recordTimestamp) {
                                // 添加空值检查
                                if (element == null || element.get("ts") == null) {
                                    System.err.println("警告: 发现缺失时间戳的记录: " + element);
                                    return System.currentTimeMillis();
                                }
                                return element.getLongValue("ts");
                            }
                        })
                );

        // 数据映射和处理 - 改进空值处理
        SingleOutputStreamOperator<JSONObject> resultStream = streamOperatorlog.map(new MapFunction<JSONObject, JSONObject>() {
            @Override
            public JSONObject map(JSONObject jsonObject) {
                JSONObject result = new JSONObject();
                // 初始化默认值
                result.put("uid", "-1");
                result.put("ts", 0L);
                result.put("search_item", "");
                JSONObject deviceInfo = new JSONObject();
                deviceInfo.put("os", "Unknown");
                deviceInfo.put("ch", "Unknown");
                deviceInfo.put("md", "Unknown");
                deviceInfo.put("ba", "Unknown");
                deviceInfo.put("pv", 0);
                result.put("deviceInfo", deviceInfo);
                // 处理 common 字段
                if (jsonObject.containsKey("common")) {
                    JSONObject common = jsonObject.getJSONObject("common");
                    // 安全获取 uid
                    String uid = common.getString("uid");
                    result.put("uid", uid != null ? uid : "-1");
                    // 获取时间戳
                    result.put("ts", jsonObject.getLongValue("ts"));
                    // 处理设备信息
                    if (common != null) {
                        // 安全获取并处理 os 字段
                        String os = common.getString("os");
                        if (os != null && os.contains(" ")) {
                            deviceInfo.put("os", os.split(" ")[0]);
                        } else if (os != null) {
                            deviceInfo.put("os", os);
                        }
                        // 安全获取其他设备信息字段
                        deviceInfo.put("ch", common.getString("ch"));
                        deviceInfo.put("md", common.getString("md"));
                        deviceInfo.put("ba", common.getString("ba"));
                        // 移除不需要的字段
                        common.remove("sid");
                        common.remove("mid");
                        common.remove("is_new");
                    }
                }

                // 处理 page 信息
                if (jsonObject.containsKey("page") && !jsonObject.getJSONObject("page").isEmpty()) {
                    JSONObject pageInfo = jsonObject.getJSONObject("page");
                    if (pageInfo != null && pageInfo.containsKey("item_type")
                            && "keyword".equals(pageInfo.getString("item_type"))) {
                        String item = pageInfo.getString("item");
                        result.put("search_item", item != null ? item : "");
                    }
                }

                return result;
            }
        });

        // 增强过滤逻辑 - 过滤掉 uid 为 null 或 "-1" 的记录
        SingleOutputStreamOperator<JSONObject> filterNotNullUidLogPageMsg = resultStream.filter(data ->
                data != null &&
                        data.getString("uid") != null &&
                        !data.getString("uid").isEmpty() &&
                        !"-1".equals(data.getString("uid"))
        );

        KeyedStream<JSONObject, String> keyedStreamLogPageMsg = filterNotNullUidLogPageMsg.keyBy(data -> data.getString("uid"));

        // 使用自定义的 ProcessFilterRepeatTsData 函数对分组后的数据进行去重处理
        SingleOutputStreamOperator<JSONObject> processStagePageLogDs = keyedStreamLogPageMsg.process(new ProcessFilterRepeatTsData());

        SingleOutputStreamOperator<JSONObject> win2MinutesPageLogsDs = processStagePageLogDs.keyBy(data -> data.getString("uid"))
                .process(new AggregateUserDataProcessFunction())
                .keyBy(data -> data.getString("uid"))
                .window(TumblingProcessingTimeWindows.of(Time.minutes(2)))
                .reduce((value1, value2) -> value2);

        win2MinutesPageLogsDs.print();

        // 在写入 Kafka 前添加 map 转换步骤：
        SingleOutputStreamOperator<String> stringDataStream = win2MinutesPageLogsDs.map(JSONObject::toString);
        KafkaSink<String> kafkaSink = FlinkSinkUtil.getKafkaSink("minutes_page_log");
        stringDataStream.sinkTo(kafkaSink);

        env.execute();
    }
}