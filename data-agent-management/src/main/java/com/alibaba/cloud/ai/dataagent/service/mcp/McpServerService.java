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
package com.alibaba.cloud.ai.dataagent.service.mcp;

import com.alibaba.cloud.ai.dataagent.entity.Agent;
import com.alibaba.cloud.ai.dataagent.entity.User;
import com.alibaba.cloud.ai.dataagent.entity.bizStatistics.WeekParamsVO;
import com.alibaba.cloud.ai.dataagent.mapper.AgentMapper;
import com.alibaba.cloud.ai.dataagent.service.UserService;
import com.alibaba.cloud.ai.dataagent.service.bizStatistics.StatisticsService;
import com.alibaba.cloud.ai.dataagent.service.graph.GraphService;
import com.alibaba.cloud.ai.dataagent.util.ProvinceUtil;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

// 封装Mcp 服务
@Service
@AllArgsConstructor
public class McpServerService {

	private final AgentMapper agentMapper;

	private GraphService graphService;

	private StatisticsService statisticsService;

	@Autowired
	private UserService userService;

	public record AgentListRequest(
			@JsonPropertyDescription("按状态过滤，例如 '状态：draft-待发布，published-已发布，offline-已下线") String status,

			@JsonPropertyDescription("按关键词搜索智能体名称或描述") String keyword) {
	}

	//@Tool(description = "查询智能体列表，支持按状态和关键词过滤。可以根据智能体的状态（如已发布PUBLISHED、草稿DRAFT等）进行过滤，也可以通过关键词搜索智能体的名称、描述或标签。返回按创建时间降序排列的智能体列表。")
	public List<Agent> listAgentsToolCallback(AgentListRequest agentListRequest) {
		return agentMapper.findByConditions(agentListRequest.status(), agentListRequest.keyword());
	}

	// NL2SQL 请求参数
	public record Nl2SqlRequest(@JsonPropertyDescription("自然语言查询描述，例如：'查询销售额最高的10个产品'") String naturalQuery,
			@JsonPropertyDescription("智能体ID，用于指定使用哪个智能体进行NL2SQL转换") String agentId) {
	}

	//@Tool(description = "将自然语言查询转换为SQL语句。使用指定的智能体将用户的自然语言查询描述转换为可执行的SQL语句，支持复杂的数据查询需求。")
	public String nl2SqlToolCallback(Nl2SqlRequest nl2SqlRequest) throws GraphRunnerException {
		Assert.hasText(nl2SqlRequest.agentId(), "AgentId cannot be empty");
		Assert.hasText(nl2SqlRequest.naturalQuery(), "Natural query cannot be empty");
		return graphService.nl2sql(nl2SqlRequest.naturalQuery(), nl2SqlRequest.agentId());
	}


	@Tool(description = "获取当前登录用户已开通的省份列表。调用方需把请求 Authorization 头中 Bearer 后面的登录令牌作为 token 参数传入，用户身份由服务端从 token 自动解析，无需传用户ID。当标讯查询（商机/采购/招标/中标/通报/简报等）未指定省份时，应先调用本工具获取用户开通省份，作为默认查询范围")
	public Map getUserProvince(
			@ToolParam(description = "当前登录用户的登录令牌（JWT token，即请求头 Authorization: Bearer 后面的部分）", required = false) String token) {
		return executeWithAuth(token, user -> {
			// 优先查库拿最新授权的省份
			Map openprovince =  userService.getUserProvince(user.getId());
			// 用 token 里携带的省份
			if (openprovince == null) {
				return Map.of("province", user.getProvince());
			}else {
				return openprovince;
			}
		});
	}

	@Tool(description = "生成某省份在指定日期区间内的标讯统计简报（纯汇总文本：项目条数、总金额等统计摘要，不包含任何具体项目信息）。\n" +
			"\n" +
			"【调用条件 - 必须全部满足】\n" +
			"1. 用户明确要求\"简报\"类的整体分析；\n" +
			"2. 用户明确指定了省份（或全国）；\n" +
			"3. 用户明确指定了时间或时间区间。\n" +
			"\n" +
			"【以下情况禁止调用】\n" +
			"- 用户索要项目列表、明细、具体商机、某公司/某行业的招标或中标记录；\n" +
			"- 用户的措辞含糊（如\"看看吉林的标讯\"\"吉林最近招标多吗\"）——此时不要调用任何工具，先向用户澄清：要\"整体统计简报\"还是\"具体项目明细\"。\n" +
			"- 注意：像\"XX的数据\"\"XX的情况\"这类说法通常指明细需求，默认不属于本工具。\n" +
			"\n" +
			"【何时必须调用】\n" +
			"- 仅当用户明确表达\"给我一份XX省X月的标讯简报\"之类意图时。\n")
	public Map getDayRangProvinceText(
			@ToolParam(description = "当前登录用户的登录令牌（JWT token，即请求头 Authorization: Bearer 后面的部分）", required = false) String token,
			@ToolParam(description = "省份，例如：北京", required = true) String province,
			@ToolParam(description = "查询日期数组，格式例如：[\"2026-08-04\", \"2026-08-20\"]", required = true) List<String> publishTime
			) {
		return executeWithAuth(token, user -> {
			// 1. 对传入的 province 进行清洗，去掉省、市、自治区等后缀
			String cleanProvince = ProvinceUtil.normalizeList(province);
			// 2. 校验查询省份是否在用户授权范围内，或用户是否有任何省份授权
			Map<String, Object> error = checkProvinceAuthorized(user, cleanProvince);
			if (error != null) {
				return error;
			}
			// 手动组装 VO 传给业务层
			WeekParamsVO bean = new WeekParamsVO();
			bean.setProvince(cleanProvince);
			bean.setPublishTime(publishTime);
			return statisticsService.getDayProvinceText(bean);
		});
	}

	@Tool(description = "生成某省份某日的标讯统计通报（汇总文本，如\"今日全省共XX个项目，成交额XX\"）。\n" +
			"\n" +
			"调用条件（必须同时满足）：\n" +
			"- 用户明确使用了\"通报 \"字眼；\n" +
			"- 用户明确指定了省份和日期。\n" +
			"\n" +
			"不调用的情况：\n" +
			"- 用户索要的是项目列表、明细、具体商机、某类招标/中标信息（即使措辞是\"数据\"\"情况\"也算明细需求）；\n" +
			"- 用户意图模糊（如只说\"看看吉林的招标\"），此时先向用户澄清要\"汇总通报\"还是\"项目明细\"，不要直接调用。\n")
	public Map getDayProvinceText(
			@ToolParam(description = "当前登录用户的登录令牌（JWT token，即请求头 Authorization: Bearer 后面的部分）", required = false) String token,
			@ToolParam(description = "省份名称，例如：北京", required = true) String province,
			@ToolParam(description = "具体的日期时间，格式例如：\"2026-08-04\"", required = true) String dateTime) {
		return executeWithAuth(token, user -> {
			// 1. 对传入的 province 进行清洗，去掉省、市、自治区等后缀
			String cleanProvince = ProvinceUtil.normalizeList(province);
			// 2. 校验查询省份是否在用户授权范围内，或用户是否有任何省份授权
			Map<String, Object> error = checkProvinceAuthorized(user, cleanProvince);
			if (error != null) {
				return error;
			}
			WeekParamsVO bean = new WeekParamsVO();
			bean.setProvince(cleanProvince);
			bean.setDateTime(dateTime);
			return statisticsService.getWeekProvinceText(bean);
		});
	}

	/**
	 * 公共 token 校验 + 业务执行模板。
	 *
	 * <p>token 为空或解析失败时返回统一的 error 结构，否则执行具体业务逻辑。
	 * @param token 登录令牌（JWT）
	 * @param action 具体的业务逻辑，入参为解析出的当前登录用户
	 * @return 业务结果，或校验失败的 error 结构
	 */
	private Map<String, Object> executeWithAuth(String token, Function<User, Map<String, Object>> action) {
		if (StringUtils.isEmpty(token)) {
			return Map.of("error", "无效token");
		}
		// 从 token 解析当前登录用户（无需远程调用，直接解 JWT claims）
		User user = userService.getUserInfoFromToken(token);
		if (user == null || user.getId() == null) {
			return Map.of("error", "无法解析 token，token 可能无效或已过期");
		}
		return action.apply(user);
	}

	/**
	 * 校验用户省份授权：用户无任何授权省份、或查询省份不在授权范围内时返回 error 结构。
	 *
	 * <p>授权范围取自用户的 province（逗号分隔，如 "北京,上海"），"全国" 表示不限制。
	 * @param user 当前登录用户（token 解析所得）
	 * @param queryProvince 归一化后的查询省份；为空时只校验用户是否有任何授权
	 * @return 校验失败的 error 结构；通过返回 null
	 */
	private Map<String, Object> checkProvinceAuthorized(User user, String queryProvince) {
		String authorizedProvince = ProvinceUtil.normalizeList(user.getProvince());
		if (authorizedProvince.isBlank()) {
			return Map.of("error", "用户无任何省份授权，请联系客户经理开通。");
		}
		if (!StringUtils.isEmpty(queryProvince) && !"全国".equals(authorizedProvince)
				&& !ProvinceUtil.normalizeSet(authorizedProvince).contains(queryProvince)) {
			return Map.of("error", "此省份未授权，请联系客户经理开通。");
		}
		return null;
	}

}
