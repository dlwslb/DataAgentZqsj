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
package com.alibaba.cloud.ai.dataagent.util.date;

import cn.hutool.core.util.ArrayUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 时间间隔的枚举
 *
 * @author dhb52
 */
@Getter
@AllArgsConstructor
public enum DateIntervalEnum implements IntArrayValuable {

    DAY(1, "天"),
    WEEK(2, "周"),
    MONTH(3, "月"),
    QUARTER(4, "季度"),
    YEAR(5, "年")
    ;

    public static final int[] ARRAYS = Arrays.stream(values()).mapToInt(DateIntervalEnum::getInterval).toArray();

    /**
     * 类型
     */
    private final Integer interval;
    /**
     * 名称
     */
    private final String name;

    @Override
    public int[] array() {
        return ARRAYS;
    }

    public static DateIntervalEnum valueOf(Integer interval) {
        return ArrayUtil.firstMatch(item -> item.getInterval().equals(interval), DateIntervalEnum.values());
    }

}
