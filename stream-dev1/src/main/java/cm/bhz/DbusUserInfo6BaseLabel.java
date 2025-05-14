package cm.bhz;
/**
 * @Package cm.bhz.DbusUserInfo6BaseLabel
 * @Author huizhong.bai
 * @Date 2025/5/13 09:20
 * @description:
 */

import cm.bhz.util.FlinkSourceUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import com.bhz.util.ConfigUtils;
import com.bhz.util.EnvironmentSettingUtils;
import com.bhz.util.KafkaUtils;
import com.bhz.util.CdcSourceUtils;
import com.ververica.cdc.connectors.mysql.source.MySqlSource;
import com.ververica.cdc.connectors.mysql.table.StartupOptions;
import lombok.SneakyThrows;
import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.co.ProcessJoinFunction;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.util.Collector;

import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;




/**
 * @Package cm.bhz.DbusUserInfo6BaseLabel
 * @Author huizhong.bai
 * @Date 2025/5/13 09:20
 * @description:
 */
public class DbusUserInfo6BaseLabel {

//    private static final String kafka_botstrap_servers = ConfigUtils.getString("kafka.bootstrap.servers");
//    private static final String kafka_cdc_db_topic = ConfigUtils.getString("kafka.cdc.db.topic");
    public static void main(String[] args) throws Exception {
        System.getProperty("HADOOP_USER_NAME","root");
        //todo 获取环境
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        //todo 设置并行度
        env.setParallelism(1);
        //todo 设置检查点
        env.enableCheckpointing(5000L, CheckpointingMode.EXACTLY_ONCE);
        //todo 获取kafka主题数据
        KafkaSource<String> kafkaSource = FlinkSourceUtil.getKafkaSource("ods_user_profile", "kafka_source_user_profile");

        //todo 从 Kafka 读取 CDC 变更数据，创建一个字符串类型的数据流
        SingleOutputStreamOperator<String> kafkaSourceDs = env.fromSource(kafkaSource,
                WatermarkStrategy.<String>forBoundedOutOfOrderness(Duration.ofSeconds(10))
                        .withTimestampAssigner((event, timestamp) ->
                                JSONObject.parseObject(event).getLong("ts_ms")),
                "kafka_cdc_db_source"
        ).uid("kafka_cdc_db_source").name("kafka_cdc_db_source");
        //kafkaSourceDs.print("kafkaSourceDs -> ");

        //todo 将string的流转换为JSONObject的流
        SingleOutputStreamOperator<JSONObject> mapKafkaToJSONObject = kafkaSourceDs.map(JSONObject::parseObject);

        //todo 过滤出user_info表
        SingleOutputStreamOperator<JSONObject> userInfoDs = mapKafkaToJSONObject
                .filter(data -> data.getJSONObject("source").getString("table").equals("user_info"))
                .uid("filter user_info data")
                .name("filter user_info data");
        //userInfoDs.print("userInfoDs -> ");

        //todo 对获取到的生日进行转换
        SingleOutputStreamOperator<JSONObject> finalUserInfoDs = userInfoDs.map(new RichMapFunction<JSONObject, JSONObject>() {
            @Override
            public JSONObject map(JSONObject jsonObject){
                JSONObject after = jsonObject.getJSONObject("after");
                if (after != null && after.containsKey("birthday")) {
                    Integer epochDay = after.getInteger("birthday");
                    if (epochDay != null) {
                        LocalDate date = LocalDate.ofEpochDay(epochDay);
                        after.put("birthday", date.format(DateTimeFormatter.ISO_DATE));
                    }
                }
                return jsonObject;
            }
        });
        //finalUserInfoDs.print("finalUserInfoDs -> ");

        //todo 过滤user_info_sup_msg表
        SingleOutputStreamOperator<JSONObject> userInfoSupMsgDs = mapKafkaToJSONObject
                .filter(data -> data.getJSONObject("source").getString("table").equals("user_info_sup_msg"))
                .uid("filter user_info_sup_msg data")
                .name("filter user_info_sup_msg data");
        //userInfoSupMsgDs.print("userInfoSupMsgDs ->");

        //todo 获取user_info_sup_msg表中字段信息
        SingleOutputStreamOperator<JSONObject> mapUserInfoSupDs = userInfoSupMsgDs.map(new RichMapFunction<JSONObject, JSONObject>() {
            @Override
            public JSONObject map(JSONObject jsonObject){
                JSONObject result = new JSONObject();
                if (jsonObject.containsKey("after") && jsonObject.getJSONObject("after") != null) {
                    JSONObject after = jsonObject.getJSONObject("after");
                    result.put("uid", after.getString("uid"));
                    result.put("unit_height", after.getString("unit_height"));
                    result.put("create_ts", after.getLong("create_ts"));
                    result.put("weight", after.getString("weight"));
                    result.put("unit_weight", after.getString("unit_weight"));
                    result.put("height", after.getString("height"));
                    result.put("ts_ms", jsonObject.getLong("ts_ms"));
                }
                return result;
            }
        });
        //mapUserInfoSupDs.print("mapUserInfoSupDs ->");

        //todo 此处数据会存在重复 使用状态进行去重
        SingleOutputStreamOperator<JSONObject> mapUserInfoDs = finalUserInfoDs.map(new RichMapFunction<JSONObject, JSONObject>() {
                    @Override
                    public JSONObject map(JSONObject jsonObject){
                        JSONObject result = new JSONObject();
                        if (jsonObject.containsKey("after")) {
                            JSONObject after = jsonObject.getJSONObject("after");
                            if (after != null) {
                                result.put("uid", after.getString("id"));
                                result.put("uname", after.getString("name"));
                                result.put("user_level", after.getString("user_level"));
                                result.put("login_name", after.getString("login_name"));
                                result.put("phone_num", after.getString("phone_num"));
                                result.put("email", after.getString("email"));
                                result.put("gender", after.getString("gender") != null ? after.getString("gender") : "home");
                                result.put("birthday", after.getString("birthday"));
                                result.put("ts_ms", jsonObject.getLongValue("ts_ms"));
                                String birthdayStr = after.getString("birthday");
                                if (birthdayStr != null && !birthdayStr.isEmpty()) {
                                    try {
                                        LocalDate birthday = LocalDate.parse(birthdayStr, DateTimeFormatter.ISO_DATE);
                                        LocalDate currentDate = LocalDate.now(ZoneId.of("Asia/Shanghai"));
                                        int age = calculateAge(birthday, currentDate);
                                        int decade = birthday.getYear() / 10 * 10;
                                        result.put("decade", decade);
                                        result.put("age", age);
                                        String zodiac = getZodiacSign(birthday);
                                        result.put("zodiac_sign", zodiac);
                                    }catch (Exception e){
                                        result.put("age", -1);
                                        e.printStackTrace();
                                        System.err.println("日期解析失败: " + birthdayStr);
                                    }
                                }
                            }
                        }
                        return result;
                    }
                })
                .filter(data -> !data.isEmpty())
                .uid("parse json")
                .name("parse json");
        //mapUserInfoDs.print("mapUserInfoDs ->");

        //todo 根据用户id进行分组
        KeyedStream<JSONObject, String> userInfoKeyed = mapUserInfoDs.keyBy(data -> data.getString("uid"));
        KeyedStream<JSONObject, String> userInfoSupKeyed = mapUserInfoSupDs.keyBy(data -> data.getString("uid"));

        //userInfoKeyed.print("userInfoKeyed ->");
        //userInfoSupKeyed.print("userInfoSupKeyed ->");

        //todo 关联两条流
        SingleOutputStreamOperator<JSONObject> processIntervalJoin = userInfoKeyed.intervalJoin(userInfoSupKeyed)
                .between(Time.seconds(-5), Time.seconds(5))
                .process(new ProcessJoinFunction<JSONObject, JSONObject, JSONObject>() {
                    @Override
                    public void processElement(JSONObject left, JSONObject right, ProcessJoinFunction<JSONObject, JSONObject, JSONObject>.Context ctx, Collector<JSONObject> out) throws Exception {
                        JSONObject result = new JSONObject();
                        if (left.getString("uid").equals(right.getString("uid"))) {
                            result.putAll(left);
                            result.put("height", right.getString("height"));
                            result.put("unit_height", right.getString("unit_height"));
                            result.put("weight", right.getString("weight"));
                            result.put("unit_weight", right.getString("unit_weight"));
                        }
                        out.collect(result);
                    }
                });
        //processIntervalJoin.print("processIntervalJoin ->");

        SingleOutputStreamOperator<String> processIntervalJoinToString = processIntervalJoin.map(new RichMapFunction<JSONObject, String>() {
            @Override
            public String map(JSONObject jsonObject) {
                String jsonString = jsonObject.toJSONString();
                return jsonString;
            }
        });

        processIntervalJoinToString.print("stringSingleOutputStreamOperator -> ");

        //todo 将用户信息标签sink到kafka
        //processIntervalJoinToString.sinkTo(FlinkSinkUtil.getKafkaSink("dwd_user_info_label"));



        env.execute("DbMysqlCdcToHbase");
    }


    private static int calculateAge(LocalDate birthDate, LocalDate currentDate) {
        return Period.between(birthDate, currentDate).getYears();
    }

    private static String getZodiacSign(LocalDate birthDate) {
        int month = birthDate.getMonthValue();
        int day = birthDate.getDayOfMonth();

        // 星座日期范围定义
        if ((month == 12 && day >= 22) || (month == 1 && day <= 19)) return "摩羯座";
        else if (month == 1 || month == 2 && day <= 18) return "水瓶座";
        else if (month == 2 || month == 3 && day <= 20) return "双鱼座";
        else if (month == 3 || month == 4 && day <= 19) return "白羊座";
        else if (month == 4 || month == 5 && day <= 20) return "金牛座";
        else if (month == 5 || month == 6 && day <= 21) return "双子座";
        else if (month == 6 || month == 7 && day <= 22) return "巨蟹座";
        else if (month == 7 || month == 8 && day <= 22) return "狮子座";
        else if (month == 8 || month == 9 && day <= 22) return "处女座";
        else if (month == 9 || month == 10 && day <= 23) return "天秤座";
        else if (month == 10 || month == 11 && day <= 22) return "天蝎座";
        else return "射手座";
    }
}
