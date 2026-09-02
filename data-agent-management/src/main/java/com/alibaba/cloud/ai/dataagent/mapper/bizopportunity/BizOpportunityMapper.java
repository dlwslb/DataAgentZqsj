package com.alibaba.cloud.ai.dataagent.mapper.bizopportunity;

import org.apache.ibatis.annotations.Mapper;
import java.util.List;
import java.util.Map;

@Mapper
public interface BizOpportunityMapper {

    List<Map<String, Object>> selectBizOpportunityList(Map<String, Object> param);

    long countBizOpportunity(Map<String, Object> param);
}