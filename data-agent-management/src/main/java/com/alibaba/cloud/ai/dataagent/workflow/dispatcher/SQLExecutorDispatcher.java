/*
 * Copyright 2024-2026 the original author or authors.
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
package com.alibaba.cloud.ai.dataagent.workflow.dispatcher;

import com.alibaba.cloud.ai.dataagent.dto.datasource.SqlRetryDto;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import lombok.extern.slf4j.Slf4j;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.*;
import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * @author zhangshenghang
 */
@Slf4j
public class SQLExecutorDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		SqlRetryDto retryDto = StateUtil.getObjectValue(state, SQL_REGENERATE_REASON, SqlRetryDto.class);
		if (retryDto.sqlExecuteFail()) {
			log.warn("SQL运行失败，需要重新生成！");
			return SQL_GENERATE_NODE;
		}
		// 🔑 skipReport=true 时：SQL 已成功跑完并拿到数据库返回数据，
		// 直接结束，不再回到 PlanExecutorNode 跑 Python/报告等后续步骤。
		if (Boolean.TRUE.equals(state.value(SKIP_REPORT, false))) {
			log.info("SQL运行成功，skipReport=true，拿到结果后直接结束。");
			return END;
		}
		log.info("SQL运行成功，返回PlanExecutorNode。");
		return PLAN_EXECUTOR_NODE;
	}

}
