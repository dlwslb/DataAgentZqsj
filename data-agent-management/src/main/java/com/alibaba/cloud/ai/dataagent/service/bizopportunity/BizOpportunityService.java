package com.alibaba.cloud.ai.dataagent.service.bizopportunity;

import java.util.Map;

public interface BizOpportunityService {

    /**
     * 条件查询商机信息（chatbi.bid_business）。
     *
     * @param tenantId 当前登录用户租户编号，用于多租户隔离
     */
    Map<String, Object> queryBizOpportunity(Long tenantId, String province, String city, String keyword,
                                            String businessNo, String stage, String manager,
                                            String beginDate, String endDate, Integer limit);
}