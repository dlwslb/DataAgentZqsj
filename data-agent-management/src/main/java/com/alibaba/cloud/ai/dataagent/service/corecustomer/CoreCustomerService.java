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