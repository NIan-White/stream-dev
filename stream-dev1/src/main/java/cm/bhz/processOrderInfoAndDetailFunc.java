package cm.bhz;

import com.alibaba.fastjson.JSONObject;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

/**
 * @Package com.lzr.fun.processOrderInfoAndDetailFunc
 * @Author lv.zirao
 * @Date 2025/5/15 8:22
 * @description: 处理订单信息和详情的函数，基于时间戳去重
 */
public class processOrderInfoAndDetailFunc extends KeyedProcessFunction<String, JSONObject, JSONObject> {

    private ValueState<Long> latestTsState;

    @Override
    public void open(Configuration parameters) {
        ValueStateDescriptor<Long> descriptor =
                new ValueStateDescriptor<>("latestTs", Long.class);
        descriptor.enableTimeToLive(StateTtlConfig.newBuilder(Time.hours(1)).build());
        latestTsState = getRuntimeContext().getState(descriptor);
    }

    @Override
    public void processElement(JSONObject value, Context ctx, Collector<JSONObject> out) throws Exception {
        // 获取存储的最新时间戳
        Long storedTs = latestTsState.value();

        // 安全获取当前记录的时间戳
        Long currentTs = value.getLong("ts_ms");

        // 检查时间戳字段是否存在
        if (currentTs == null) {
            // 处理时间戳缺失的情况，例如记录日志或使用其他默认值
            System.err.println("Missing 'ts_ms' field in record: " + value);
            return; // 或者设置默认值: currentTs = ctx.timestamp();
        }

        // 比较时间戳并更新状态
        if (storedTs == null || currentTs > storedTs) {
            latestTsState.update(currentTs);
            out.collect(value);
        } else {
            // 可选：记录被过滤掉的旧记录
            System.out.println("Filtered older record with ts: " + currentTs +
                    ", latest is: " + storedTs);
        }
    }
}