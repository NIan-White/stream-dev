package cm.bhz;

import cm.bhz.app.dim.DimBaseCategory;
import cm.bhz.app.dim.DimSkuInfoMsg;
import com.alibaba.fastjson.JSONObject;
import com.bhz.util.JdbcUtil;


import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.configuration.Configuration;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.util.List;

public class MapOrderAndDetailRateModelFunc extends RichMapFunction<JSONObject, JSONObject> {

    private Connection connection;
    private List<DimSkuInfoMsg> dimSkuInfoMsgs;
    private final List<DimBaseCategory> dimBaseCategories;
    private final double timeRate;
    private final double amountRate;
    private final double brandRate;
    private final double categoryRate;

    public MapOrderAndDetailRateModelFunc(List<DimBaseCategory> dimBaseCategories,
                                          double timeRate,
                                          double amountRate,
                                          double brandRate,
                                          double categoryRate) {
        this.dimBaseCategories = dimBaseCategories;
        this.timeRate = timeRate;
        this.amountRate = amountRate;
        this.brandRate = brandRate;
        this.categoryRate = categoryRate;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        try {
            connection = JdbcUtil.getMySQLConnection();
            String querySkuSql = "select sku_info.id as skuid,      \n" +
                    "       spu_info.id as spuid,           \n" +
                    "       spu_info.category3_id as c3id,  \n" +
                    "       base_trademark.tm_name as tname \n" +
                    "from realtime_v1.sku_info              \n" +
                    "join realtime_v1.spu_info              \n" +
                    "on sku_info.spu_id = spu_info.id       \n" +
                    "join realtime_v1.base_trademark        \n" +
                    "on realtime_v1.spu_info.tm_id = realtime_v1.base_trademark.id";
            dimSkuInfoMsgs = JdbcUtil.queryList(connection, querySkuSql, DimSkuInfoMsg.class);
            // 确保列表不为 null
            if (dimSkuInfoMsgs == null) {
                dimSkuInfoMsgs = java.util.Collections.emptyList();
                System.err.println("警告: 数据库查询返回空结果，dimSkuInfoMsgs 已初始化为空列表");
            }
        } catch (Exception e) {
            System.err.println("数据库连接或查询出错: " + e.getMessage());
            // 抛出异常会导致任务失败，根据需求决定是否继续执行
            throw new RuntimeException("初始化失败: " + e.getMessage(), e);
        }
    }

    @Override
    public JSONObject map(JSONObject jsonObject) throws Exception {
        // 检查输入是否为 null
        if (jsonObject == null) {
            System.err.println("警告: 接收到 null 输入，跳过处理");
            return null;
        }

        // SKU信息处理
        String skuId = jsonObject.getString("sku_id");
        if (skuId != null && !skuId.isEmpty() && !dimSkuInfoMsgs.isEmpty()) {
            for (DimSkuInfoMsg dimSkuInfoMsg : dimSkuInfoMsgs) {
                // 检查对象属性是否为 null
                if (dimSkuInfoMsg != null && dimSkuInfoMsg.getSkuid() != null && dimSkuInfoMsg.getSkuid().equals(skuId)) {
                    if (dimSkuInfoMsg.getC3id() != null) {
                        jsonObject.put("c3id", dimSkuInfoMsg.getC3id());
                    }
                    if (dimSkuInfoMsg.getTname() != null) {
                        jsonObject.put("tname", dimSkuInfoMsg.getTname());
                    }
                    break;
                }
            }
        }

        // 类目信息处理
        String c3id = jsonObject.getString("c3id");
        if (c3id != null && !c3id.isEmpty() && dimBaseCategories != null && !dimBaseCategories.isEmpty()) {
            for (DimBaseCategory dimBaseCategory : dimBaseCategories) {
                // 检查对象属性是否为 null
                if (dimBaseCategory != null && dimBaseCategory.getId() != null && dimBaseCategory.getId().equals(c3id)) {
                    if (dimBaseCategory.getName1() != null) {
                        jsonObject.put("b1_name", dimBaseCategory.getName1());
                    }
                    break;
                }
            }
        }

        // 时间打分
        String payTimeSlot = jsonObject.getString("pay_time_slot");
        if (payTimeSlot != null && !payTimeSlot.isEmpty()) {
            setTimeScore(jsonObject, payTimeSlot);
        }

        // 价格打分 - 优化判断逻辑
        if (jsonObject.containsKey("total_amount")) {
            double totalAmount = jsonObject.getDoubleValue("total_amount");
            setAmountScore(jsonObject, totalAmount);
        }

        // 品牌打分
        String tname = jsonObject.getString("tname");
        if (tname != null && !tname.isEmpty()) {
            setBrandScore(jsonObject, tname);
        }

        // 类目打分
        String b1Name = jsonObject.getString("b1_name");
        if (b1Name != null && !b1Name.isEmpty()) {
            setCategoryScore(jsonObject, b1Name);
        }

        return jsonObject;
    }

    private void setTimeScore(JSONObject jsonObject, String payTimeSlot) {
        switch (payTimeSlot) {
            case "凌晨":
                jsonObject.put("pay_time_18-24", round(0.2 * timeRate));
                jsonObject.put("pay_time_25-29", round(0.1 * timeRate));
                jsonObject.put("pay_time_30-34", round(0.1 * timeRate));
                jsonObject.put("pay_time_35-39", round(0.1 * timeRate));
                jsonObject.put("pay_time_40-49", round(0.1 * timeRate));
                jsonObject.put("pay_time_50", round(0.1 * timeRate));
                break;
            // 其他case...
            case "早晨":
                jsonObject.put("pay_time_18-24", round(0.1 * timeRate));
                jsonObject.put("pay_time_25-29", round(0.1 * timeRate));
                jsonObject.put("pay_time_30-34", round(0.1 * timeRate));
                jsonObject.put("pay_time_35-39", round(0.1 * timeRate));
                jsonObject.put("pay_time_40-49", round(0.2 * timeRate));
                jsonObject.put("pay_time_50", round(0.3 * timeRate));
                break;
            // 省略其他时间case
        }
    }

    private void setAmountScore(JSONObject jsonObject, double totalAmount) {
        if (totalAmount < 1000) {
            jsonObject.put("amount_18-24", round(0.8 * amountRate));
            jsonObject.put("amount_25-29", round(0.6 * amountRate));
            jsonObject.put("amount_30-34", round(0.4 * amountRate));
            jsonObject.put("amount_35-39", round(0.3 * amountRate));
            jsonObject.put("amount_40-49", round(0.2 * amountRate));
            jsonObject.put("amount_50", round(0.1 * amountRate));
        } else if (totalAmount > 1000 && totalAmount < 4000) {
            jsonObject.put("amount_18-24", round(0.2 * amountRate));
            jsonObject.put("amount_25-29", round(0.4 * amountRate));
            jsonObject.put("amount_30-34", round(0.6 * amountRate));
            jsonObject.put("amount_35-39", round(0.7 * amountRate));
            jsonObject.put("amount_40-49", round(0.8 * amountRate));
            jsonObject.put("amount_50", round(0.7 * amountRate));
        } else {
            jsonObject.put("amount_18-24", round(0.1 * amountRate));
            jsonObject.put("amount_25-29", round(0.2 * amountRate));
            jsonObject.put("amount_30-34", round(0.3 * amountRate));
            jsonObject.put("amount_35-39", round(0.4 * amountRate));
            jsonObject.put("amount_40-49", round(0.5 * amountRate));
            jsonObject.put("amount_50", round(0.6 * amountRate));
        }
    }

    private void setBrandScore(JSONObject jsonObject, String tname) {
        switch (tname) {
            case "TCL":
                jsonObject.put("tname_18-24", round(0.2 * brandRate));
                jsonObject.put("tname_25-29", round(0.3 * brandRate));
                jsonObject.put("tname_30-34", round(0.4 * brandRate));
                jsonObject.put("tname_35-39", round(0.5 * brandRate));
                jsonObject.put("tname_40-49", round(0.6 * brandRate));
                jsonObject.put("tname_50", round(0.7 * brandRate));
                break;
            // 其他品牌case...
            case "苹果":
            case "联想":
            case "小米":
                jsonObject.put("tname_18-24", round(0.9 * brandRate));
                jsonObject.put("tname_25-29", round(0.8 * brandRate));
                jsonObject.put("tname_30-34", round(0.7 * brandRate));
                jsonObject.put("tname_35-39", round(0.7 * brandRate));
                jsonObject.put("tname_40-49", round(0.7 * brandRate));
                jsonObject.put("tname_50", round(0.5 * brandRate));
                break;
            // 省略其他品牌case
        }
    }

    private void setCategoryScore(JSONObject jsonObject, String b1Name) {
        switch (b1Name) {
            case "数码":
            case "手机":
            case "电脑办公":
            case "个护化妆":
            case "服饰内衣":
                jsonObject.put("b1name_18-24", round(0.9 * categoryRate));
                jsonObject.put("b1name_25-29", round(0.8 * categoryRate));
                jsonObject.put("b1name_30-34", round(0.6 * categoryRate));
                jsonObject.put("b1name_35-39", round(0.4 * categoryRate));
                jsonObject.put("b1name_40-49", round(0.2 * categoryRate));
                jsonObject.put("b1name_50", round(0.1 * categoryRate));
                break;
            // 其他类目case...
            case "家居家装":
            case "图书、音像、电子书刊":
            case "厨具":
            case "鞋靴":
            case "母婴":
            case "汽车用品":
            case "珠宝":
            case "家用电器":
                jsonObject.put("b1name_18-24", round(0.2 * categoryRate));
                jsonObject.put("b1name_25-29", round(0.4 * categoryRate));
                jsonObject.put("b1name_30-34", round(0.6 * categoryRate));
                jsonObject.put("b1name_35-39", round(0.8 * categoryRate));
                jsonObject.put("b1name_40-49", round(0.9 * categoryRate));
                jsonObject.put("b1name_50", round(0.7 * categoryRate));
                break;
            // 省略其他类目case
        }
    }

    @Override
    public void close() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value)
                .setScale(3, RoundingMode.HALF_UP)
                .doubleValue();
    }
}