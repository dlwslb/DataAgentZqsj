/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jldaren.agent.ai.datascope2.tool;

import java.util.Map;
import java.util.Set;

/**
 * 省份名归一化工具类
 *
 * <p>目的：让用户输入的省份任意写法（"黑龙江" / "黑龙江省" / "北京市" / "北京"）和
 * 授权的省份（"黑龙江省,吉林省"）归一化成数据库里实际存的短名形式（"黑龙江" / "吉林"），
 * 然后做匹配。
 *
 * <h3>支持的省份</h3>
 * <table>
 *   <caption>省份归一化映射</caption>
 *   <tr><th>用户输入</th><th>归一化后</th></tr>
 *   <tr><td>"黑龙江" / "黑龙江省"</td><td>"黑龙江"</td></tr>
 *   <tr><td>"吉林" / "吉林省"</td><td>"吉林"</td></tr>
 *   <tr><td>"辽宁" / "辽宁省"</td><td>"辽宁"</td></tr>
 *   <tr><td>"北京" / "北京市"</td><td>"北京"</td></tr>
 *   <tr><td>"上海" / "上海市"</td><td>"上海"</td></tr>
 *   <tr><td>"天津" / "天津市"</td><td>"天津"</td></tr>
 *   <tr><td>"重庆" / "重庆市"</td><td>"重庆"</td></tr>
 *   <tr><td>"内蒙古" / "内蒙古自治区"</td><td>"内蒙古"</td></tr>
 *   <tr><td>"新疆" / "新疆维吾尔自治区"</td><td>"新疆"</td></tr>
 *   <tr><td>"广西" / "广西壮族自治区"</td><td>"广西"</td></tr>
 *   <tr><td>"宁夏" / "宁夏回族自治区"</td><td>"宁夏"</td></tr>
 *   <tr><td>"西藏" / "西藏自治区"</td><td>"西藏"</td></tr>
 *   <tr><td>"香港" / "香港特别行政区"</td><td>"香港"</td></tr>
 *   <tr><td>"澳门" / "澳门特别行政区"</td><td>"澳门"</td></tr>
 * </table>
 *
 * <h3>用法</h3>
 * <pre>
 *   ProvinceUtil.normalize("黑龙江省");   // → "黑龙江"
 *   ProvinceUtil.normalize("北京");      // → "北京"
 *   ProvinceUtil.normalize("  吉林  ");  // → "吉林"
 *   ProvinceUtil.normalize(null);         // → ""
 * </pre>
 *
 * <p>线程安全：所有方法都是纯函数，无状态。
 */
public final class ProvinceUtil {

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
