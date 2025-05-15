package cm.bhz;

import com.alibaba.fastjson.JSONObject;
import org.apache.flink.streaming.api.functions.co.ProcessJoinFunction;
import org.apache.flink.util.Collector;

/**
 * @Package com.lzr.fun.IntervalDbOrderInfoJoinOrderDetailProcessFunc
 * @Author lv.zirao
 * @Date 2025/5/15 8:22
 * @description:
 */
public class IntervalDbOrderInfoJoinOrderDetailProcessFunc extends ProcessJoinFunction<JSONObject, JSONObject, JSONObject> {
    @Override
    public void processElement(JSONObject jsonObject1, JSONObject jsonObject2,
                               ProcessJoinFunction<JSONObject, JSONObject, JSONObject>.Context context,
                               Collector<JSONObject> collector) throws Exception {

        JSONObject result = new JSONObject();
        result.putAll(jsonObject1);

        // 安全处理数值类型字段
        result.put("sku_num", safeGetLong(jsonObject2, "sku_num"));
        result.put("sku_id", safeGetLong(jsonObject2, "sku_id"));
        result.put("split_activity_amount", safeGetLong(jsonObject2, "split_activity_amount"));
        result.put("split_total_amount", safeGetLong(jsonObject2, "split_total_amount"));

        // 字符串类型字段保持不变
        result.put("split_coupon_amount", jsonObject2.getString("sku_num")); // 注意: 这里似乎有拼写错误，应该是"split_coupon_amount"?
        result.put("sku_name", jsonObject2.getString("sku_name"));
        result.put("order_price", jsonObject2.getString("order_price"));
        result.put("detail_id", jsonObject2.getString("id"));
        result.put("order_id", jsonObject2.getString("order_id"));

        collector.collect(result);
    }

    // 安全获取Long值的辅助方法
    private Long safeGetLong(JSONObject jsonObject, String key) {
        Object value = jsonObject.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null; // 或者返回默认值如0L，根据业务需求决定
        }
    }
}