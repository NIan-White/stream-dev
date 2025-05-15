package cm.bhz;

import com.alibaba.fastjson.JSONObject;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;

/**
 * @Package com.lzr.fun.ProcessFilterRepeatTsData
 * @Author lv.zirao
 * @Date 2025/5/14 10:49
 * @description: 对完整数据进行去重
 */
public class ProcessFilterRepeatTsData extends KeyedProcessFunction<String, JSONObject, JSONObject> {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessFilterRepeatTsData.class);
    private ValueState<HashSet<String>> processedDataState;

    @Override
    public void open(Configuration parameters) {
//        使用open方法获取 Flink 的键控状态。
        ValueStateDescriptor<HashSet<String>> descriptor = new ValueStateDescriptor<>(
                "processedDataState",
                TypeInformation.of(new org.apache.flink.api.common.typeinfo.TypeHint<HashSet<String>>() {})
        );
        processedDataState = getRuntimeContext().getState(descriptor);
    }

    @Override
    public void processElement(JSONObject value, Context ctx, Collector<JSONObject> out) throws Exception {
        // 1. 获取或初始化已处理数据集合
        HashSet<String> processedData = processedDataState.value();
        if (processedData == null) {
            processedData = new HashSet<>();
        }
//         2. 将 JSON 对象转换为字符串
        String dataStr = value.toJSONString();
        LOG.info("Processing data: {}", dataStr);
//         3. 检查重复并输出
        if (!processedData.contains(dataStr)) {
            LOG.info("Adding new data to set: {}", dataStr);
            processedData.add(dataStr);
            processedDataState.update(processedData);
            out.collect(value);
        } else {
            LOG.info("Duplicate data found: {}", dataStr);
        }
    }
}
