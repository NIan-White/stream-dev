package cm.bhz.app.dwd;

import cm.bhz.function.IntervalJoinUserInfoLabelProcessFunc;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.bhz.util.ConfigUtils;
import com.bhz.util.FlinkSinkUtil;
import com.bhz.util.FlinkSourceUtil;
import com.bhz.util.KafkaUtils;
import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.util.Collector;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * @Package com.lzr.dmp.dwd.DwdApp
 * @Author lv.zirao
 * @Date 2025/5/12 22:38
 * @description:
 */
public class DwdApp {
    public static void main(String[] args) throws Exception {
//        1. 环境初始化与数据源配置
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        KafkaSource<String> kafkaSource = FlinkSourceUtil.getKafkaSource("topic_dmp_db", "dwd_app");
        // 添加水位线策略，允许3秒的乱序
        WatermarkStrategy<String> watermarkStrategy = WatermarkStrategy
                .<String>forBoundedOutOfOrderness(Duration.ofSeconds(3))
                .withTimestampAssigner(new SerializableTimestampAssigner<String>() {
                    @Override
                    public long extractTimestamp(String element, long recordTimestamp) {
//                        时间戳提取：从 JSON 数据中提取 ts_ms 字段作为事件时间戳；若提取失败，则使用当前系统时间。
                        try {
                            JSONObject json = JSON.parseObject(element);
                            // 从JSON中提取事件时间戳，根据实际数据结构调整
                            if (json.containsKey("ts_ms")) {
                                return json.getLongValue("ts_ms");
                            }
                            // 如果没有ts_ms字段，使用处理时间
                            return System.currentTimeMillis();
                        } catch (Exception e) {
                            // 异常处理：返回当前时间戳作为备选
                            return System.currentTimeMillis();
                        }
                    }
                });

        DataStreamSource<String> kafkaSource1 = env.fromSource(kafkaSource, watermarkStrategy, "kafka_source");
//        kafkaSource1.print();
//        过滤 Kafka 消息，仅保留来自 user_info_sup_msg 表的数据（如用户身高、体重等补充信息）
        SingleOutputStreamOperator<JSONObject> SupMsgDs = kafkaSource1.map(JSON::parseObject)
                .filter(json->json.getJSONObject("source").getString("table").equals("user_info_sup_msg"));
//        SupMsgDs.print();

        // 从 user_info_sup_msg 表的变更数据（after 字段）中提取用户 ID、性别、身高、体重等信息
        SingleOutputStreamOperator<JSONObject> process = SupMsgDs.process(new ProcessFunction<JSONObject, JSONObject>() {
            @Override
            public void processElement(JSONObject value, ProcessFunction<JSONObject, JSONObject>.Context ctx, Collector<JSONObject> out) throws Exception {
                JSONObject object = new JSONObject();
                object.put("uid", value.getJSONObject("after").getString("uid"));
                object.put("gender", value.getJSONObject("after").getString("gender"));
                object.put("height", value.getJSONObject("after").getString("height"));
                object.put("weight", value.getJSONObject("after").getString("weight"));
                object.put("unit_height", value.getJSONObject("after").getString("unit_height"));
                object.put("unit_weight", value.getJSONObject("after").getString("unit_weight"));
                object.put("ts_ms", value.getLong("ts_ms"));
                out.collect(object);
            }
        });
//        process.print();
        // 处理 user_info 表的数据
        SingleOutputStreamOperator<JSONObject> UserInfo = kafkaSource1
                .map(JSON::parseObject)
                .filter(json -> {
                    JSONObject source = json.getJSONObject("source");
                    return source != null && "user_info".equals(source.getString("table"));
                })
                .map(json -> {
                    JSONObject after = json.getJSONObject("after");
                    if (after != null) {
                        // 1. 重命名 "id" -> "uid"（如果存在）
                        if (after.containsKey("id")) {
                            after.put("uid", after.get("id"));
                            after.remove("id");
                        }
                        // 2. 处理 birthday 并计算 age, decade, constellation
                        if (after.containsKey("birthday")) {
                            try {
                                Integer epocDay = after.getInteger("birthday");
                                if (epocDay == null && after.getString("birthday") != null) {
                                    epocDay = Integer.parseInt(after.getString("birthday"));
                                }
                                if (epocDay != null) {
                                    LocalDate date = LocalDate.ofEpochDay(epocDay);
                                    after.put("birthday", date.format(DateTimeFormatter.ISO_DATE));
                                    // 计算年龄
                                    LocalDate now = LocalDate.now();
                                    int age = now.getYear() - date.getYear();
                                    if (now.getDayOfYear() < date.getDayOfYear()) {
                                        age--;
                                    }
                                    after.put("age", age);
                                    // 计算年代和星座
                                    after.put("decade", (date.getYear() / 10) * 10);
                                    after.put("constellation", calculateConstellation(date.getMonthValue(), date.getDayOfMonth()));
                                }
                            } catch (Exception e) {
                                System.err.println("解析 birthday 失败: " + e.getMessage());
                            }
                        }
                        // 3. 处理 gender 字段（如果为空则设置为 "home"）
                        if (!after.containsKey("gender") || after.getString("gender") == null || after.getString("gender").isEmpty()) {
                            after.put("gender", "home");
                        }
                        // 4. 移除不需要的字段：nick_name
                        after.remove("nick_name");
                        // 5. 返回处理后的 after 数据
                        return after;
                    } else {
                        return new JSONObject();
                    }
                })
                .filter(after -> !after.isEmpty());
//        UserInfo.print();
//        将用户主信息（UserInfo）和补充信息（SupMsgDs）按 uid 进行关联。
        SingleOutputStreamOperator<JSONObject> finalUserinfoDs = UserInfo.filter(data -> data.containsKey("uid") && !data.getString("uid").isEmpty());
//        finalUserinfoDs.print();
        SingleOutputStreamOperator<JSONObject> finalUserinfoSupDs = process.filter(data -> data.containsKey("uid") && !data.getString("uid").isEmpty());
//        finalUserinfoSupDs.print();
        KeyedStream<JSONObject, String> keyedStreamUserInfoDs = finalUserinfoDs.keyBy(data -> data.getString("uid"));
        KeyedStream<JSONObject, String> keyedStreamUserInfoSupDs = finalUserinfoSupDs.keyBy(data -> data.getString("uid"));
        SingleOutputStreamOperator<JSONObject> processIntervalJoinUserInfo6BaseMessageDs = keyedStreamUserInfoDs.intervalJoin(keyedStreamUserInfoSupDs)
//        允许两条流的事件时间差在 ±5 分钟 内完成关联。
                .between(Time.minutes(-5), Time.minutes(5))
//        IntervalJoinUserInfoLabelProcessFunc 是自定义处理函数，负责合并两条流的数据（如将身高、体重信息合并到用户主信息中）
                .process(new IntervalJoinUserInfoLabelProcessFunc());
//        processIntervalJoinUserInfo6BaseMessageDs.print();

//        存入kafka
        processIntervalJoinUserInfo6BaseMessageDs.map(JSONObject::toString).sinkTo(FlinkSinkUtil.getKafkaSink("kafka_label_base6_topic"));

        env.execute("User Info Processing Job");
    }
    // 将星座计算方法移到main方法外部
//    星座计算逻辑
//    根据生日计算星座
    private static String calculateConstellation(int month, int day) {
        String[] constellations = {
                "摩羯座", "水瓶座", "双鱼座", "白羊座", "金牛座", "双子座",
                "巨蟹座", "狮子座", "处女座", "天秤座", "天蝎座", "射手座", "魔蝎座"
        };
        int[] dates = {20, 19, 21, 20, 21, 22, 23, 23, 23, 24, 23, 22};

        return day < dates[month - 1] ? constellations[month - 1] : constellations[month];
    }
}