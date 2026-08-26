package com.alibaba.cloud.ai.dataagent.util;

import java.util.Map;
import java.util.Set;

public class ProvinceUtil {

    private ProvinceUtil() {
        // 工具类，禁止实例化
    }

    /**
     * 自治区/特别行政区全称到短名的白名单（先精确匹配）
     * <p>key = 完整名称，value = 数据库存的短名
     */
    private static final Map<String, String> FULL_PROVINCE_SHORT = Map.of(
            "新疆维吾尔自治区", "新疆",
            "广西壮族自治区", "广西",
            "宁夏回族自治区", "宁夏",
            "内蒙古自治区", "内蒙古",
            "西藏自治区", "西藏",
            "香港特别行政区", "香港",
            "澳门特别行政区", "澳门"
    );

    /**
     * 省份名归一化
     *
     * @param p 任意写法的省份名（null / 空 / "黑龙江" / "黑龙江省" / "内蒙古自治区" 等）
     * @return 归一化后的短名（如 "黑龙江" / "北京" / "内蒙古"），null/空 返回 ""
     */
    public static String normalize(String p) {
        if (p == null) return "";
        String t = p.trim();
        if (t.isEmpty()) return "";

        // 1. 完整匹配自治区/特别行政区（必须先于通用替换）
        if (FULL_PROVINCE_SHORT.containsKey(t)) {
            return FULL_PROVINCE_SHORT.get(t);
        }
        // 2. 通用替换：按“先长后短”顺序，避免把“特别行政区/自治区/市/省”误删
        if (t.contains("特别行政区")) t = t.replace("特别行政区", "");
        if (t.contains("自治区")) t = t.replace("自治区", "");
        if (t.contains("省")) t = t.replace("省", "");
        if (t.contains("市")) t = t.replace("市", "");
        // 3. 已经是短名，原样返回
        return t;
    }

    /**
     * 把逗号分隔的多值省份字符串归一化拼接
     * <pre>
     *   normalizeList("黑龙江省,吉林省");  // → "黑龙江,吉林"
     *   normalizeList("北京,上海,  内蒙古  "); // → "北京,上海,内蒙古"
     *   normalizeList(null);  // → ""
     * </pre>
     */
    public static String normalizeList(String provinces) {
        if (provinces == null || provinces.isBlank()) return "";
        String[] parts = provinces.split(",");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String n = normalize(p);
            if (n.isEmpty()) continue;
            if (sb.length() > 0) sb.append(",");
            sb.append(n);
        }
        return sb.toString();
    }

    /**
     * 把逗号分隔的多值省份字符串归一化成 Set（用于 set 包含判断）
     * <pre>
     *   normalizeSet("黑龙江省,吉林省");  // → {"黑龙江", "吉林"}
     * </pre>
     */
    public static Set<String> normalizeSet(String provinces) {
        Set<String> set = new java.util.HashSet<>();
        if (provinces == null || provinces.isBlank()) return set;
        for (String p : provinces.split(",")) {
            String n = normalize(p);
            if (!n.isEmpty()) set.add(n);
        }
        return set;
    }

    public static String normalizeProvince(String p) {
        if (p == null) return "";
        String t = p.trim();
        if (t.isEmpty()) return "";

        // 1. 完整匹配自治区/特别行政区
        if (FULL_PROVINCE_SHORT.containsKey(t)) {
            return FULL_PROVINCE_SHORT.get(t);
        }
        // 2. 通用替换：按“先长后短”顺序
        if (t.contains("省")) t = t.replace("省", "");
        if (t.contains("市")) t = t.replace("市", "");
        if (t.contains("自治区")) t = t.replace("自治区", "");
        if (t.contains("特别行政区")) t = t.replace("特别行政区", "");
        // 3. 已经是短名，原样返回
        return t;
    }
}
