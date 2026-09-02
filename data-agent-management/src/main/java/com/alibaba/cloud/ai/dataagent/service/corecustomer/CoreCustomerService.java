package com.alibaba.cloud.ai.dataagent.service.corecustomer;

import java.util.Map;

public interface CoreCustomerService {

    /**
     * 条件查询核心客户信息（chatbi.bid_customer）。
     *
     * @param tenantId 当前登录用户租户编号，用于多租户隔离
     */
    Map<String, Object> queryCoreCustomer(Long tenantId, String province, String city, String customerName,
                                          String industry, String manager,
                                          Boolean onlyCore, Integer limit);
}