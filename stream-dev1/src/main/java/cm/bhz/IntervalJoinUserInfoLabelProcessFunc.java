package cm.bhz;

import com.alibaba.fastjson.JSONObject;
import org.apache.flink.streaming.api.functions.co.ProcessJoinFunction;
import org.apache.flink.util.Collector;

/**
 * @Package com.lzr.fun.IntervalJoinUserInfoLabelProcessFunc
 * @Author lv.zirao
 * @Date 2025/5/13 10:20
 * @description:
 */
public class IntervalJoinUserInfoLabelProcessFunc extends ProcessJoinFunction<JSONObject,JSONObject,JSONObject> {
    @Override
    public void processElement(JSONObject jsonObject1, JSONObject jsonObject2, ProcessJoinFunction<JSONObject, JSONObject, JSONObject>.Context context, Collector<JSONObject> collector) throws Exception {

        JSONObject result = new JSONObject();
        if (jsonObject1.getString("uid").equals(jsonObject2.getString("uid"))) {
            // 1. 合并 jsonObject1 的所有字段（来自 user_info）
            result.putAll(jsonObject1);

            // 2. 合并 jsonObject2 的补充信息（身高、体重等）
            result.put("height", jsonObject2.getString("height"));
            result.put("unit_height", jsonObject2.getString("unit_height"));
            result.put("weight", jsonObject2.getString("weight"));
            result.put("unit_weight", jsonObject2.getString("unit_weight"));

            // 3. 显式合并 ts_ms（覆盖 create_time 或单独保留）
            if (jsonObject2.containsKey("ts_ms")) {
                result.put("ts_ms", jsonObject2.getLong("ts_ms"));  // 添加 ts_ms
                result.remove("create_time");  // 如果不需要 create_time，可以移除
            }
        }
        collector.collect(result);
    }
}
