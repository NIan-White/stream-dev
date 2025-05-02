package com.bhz.app.dim;
/**
 * @Package com.bhz.app.dim.BaseApp
 * @Author huizhong.bai
 * @Date 2025/5/2 16:06
 * @description:
 */

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.bhz.constant.Constant;
import com.bhz.util.FlinkSourceUtil;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.Collector;

/**
 * @Package com.bhz.app.dim.BaseApp
 * @Author huizhong.bai
 * @Date 2025/5/2 16:06
 * @description:
 */
public class BaseApp {
    public static void main(String[] args) {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        env.setParallelism(4);

        env.enableCheckpointing(5000, CheckpointingMode.EXACTLY_ONCE);

        KafkaSource<String> kafkaSource = FlinkSourceUtil.getKafkaSource(Constant.TOPIC_DB, "dim_app");

        DataStreamSource<String> kafkaStrDS = env.fromSource(kafkaSource, WatermarkStrategy.noWatermarks(), "kafka_source");

        kafkaStrDS.print();

        SingleOutputStreamOperator<JSONObject> jsonObjDs = kafkaStrDS.process(new ProcessFunction<String, JSONObject>() {
                                                                                @Override
                                                                                public void processElement(String jsonStr, ProcessFunction<String, JSONObject>.Context ctx, Collector<JSONObject> out) throws Exception {

                                                                                    JSONObject jsonObj = JSON.parseObject(jsonStr);
                                                                                    String db = jsonObj.getJSONObject("source").getString("db");
                                                                                    String type = jsonObj.getString("op");
                                                                                    String data = jsonObj.getString("after");

                                                                                    if ("realtime_v1".equals(db)
                                                                                            && ("c".equals(type)
                                                                                            || "u".equals(type)
                                                                                            || "d".equals(type)
                                                                                            || "r".equals(type))
                                                                                            && data != null
                                                                                            && data.length() > 2
                                                                                    ) {
                                                                                        out.collect(jsonObj);
                                                                                    }
                                                                                }
                                                                            }
        );

        jsonObjDs.print();


    }
}
