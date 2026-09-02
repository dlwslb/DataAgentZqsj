package com.alibaba.cloud.ai.dataagent.mapper.corecustomer;

import org.apache.ibatis.annotations.Mapper;
import java.util.List;
import java.util.Map;

@Mapper
public interface CoreCustomerMapper {

    List<Map<String, Object>> selectCoreCustomerList(Map<String, Object> param);

    long countCoreCustomer(Map<String, Object> param);
}