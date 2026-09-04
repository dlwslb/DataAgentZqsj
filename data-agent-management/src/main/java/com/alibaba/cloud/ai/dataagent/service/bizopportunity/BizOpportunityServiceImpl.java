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
package com.alibaba.cloud.ai.dataagent.service.bizopportunity;

import com.alibaba.cloud.ai.dataagent.mapper.bizopportunity.BizOpportunityMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class BizOpportunityServiceImpl implements BizOpportunityService {

    private static final int MAX_LIMIT = 100;
    private static final int DEFAULT_LIMIT = 20;

    @Resource
    private BizOpportunityMapper bizOpportunityMapper;

    @Override
    public Map<String, Object> queryBizOpportunity(Long tenantId, String province, String city, String keyword,
                                                   String businessNo, String stage, String manager,
                                                   String beginDate, String endDate, Integer limit) {
        int safeLimit = (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        Map<String, Object> param = new HashMap<>();
        param.put("tenantId", tenantId == null ? 0L : tenantId);
        param.put("province", province);
        param.put("city", StringUtils.hasText(city) ? city : null);
        param.put("keyword", StringUtils.hasText(keyword) ? keyword : null);
        param.put("businessNo", StringUtils.hasText(businessNo) ? businessNo : null);
        param.put("stage", StringUtils.hasText(stage) ? stage : null);
        param.put("manager", StringUtils.hasText(manager) ? manager : null);
        param.put("beginDate", StringUtils.hasText(beginDate) ? beginDate : null);
        param.put("endDate", StringUtils.hasText(endDate) ? endDate : null);
        param.put("limit", safeLimit);

        List<Map<String, Object>> list = bizOpportunityMapper.selectBizOpportunityList(param);
        long total = bizOpportunityMapper.countBizOpportunity(param);

        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("returned", list.size());
        result.put("list", list);
        return result;
    }
}
