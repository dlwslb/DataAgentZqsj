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
import com.alibaba.cloud.ai.dataagent.service.bizopportunity.BizOpportunityService;
import com.alibaba.cloud.ai.dataagent.service.corecustomer.CoreCustomerService;
import com.alibaba.cloud.ai.dataagent.service.graph.GraphService;
import com.alibaba.cloud.ai.dataagent.service.lostbid.OperatorLostBidService;
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

	private final CoreCustomerService coreCustomerService;

	private final BizOpportunityService bizOpportunityService;

	private final OperatorLostBidService operatorLostBidService;

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


	@Tool(description = "获取当前登录用户已开通的省份列表。token 参数由平台 user_auth 拦截器自动注入，禁止手动填写或向用户索要；用户身份由服务端从 token 自动解析，无需传用户ID。当标讯查询（商机/采购/招标/中标/通报/简报等）未指定省份时，应先调用本工具获取用户开通省份，作为默认查询范围")
	public Map getUserProvince(
			@ToolParam(description = "登录令牌，由平台自动注入，无需手动传入）", required = false) String token) {
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

	@Tool(description = "查询核心客户信息（bid_customer）。适用于用户明确要查询『某省/市的重点客户、名单制客户、核心要客、战略/价值客户』"
			+ "或其客户经理（A角/B角/挂帅领导）归属等客户档案信息时调用。\n"
			+ "【调用条件】\n"
			+ "1. 必须指定省份（或全国），且省份需在用户授权范围内；\n"
			+ "2. 可按 客户名称、行业、客户经理、是否核心/名单制/战略/价值 等条件过滤。\n"
			+ "【禁止调用】用户询问标讯/招标/中标等公开市场数据时不要调用本工具。")
	public Map queryCoreCustomer(
			@ToolParam(description = "登录令牌，由平台自动注入，无需手动传入", required = false) String token,
			@ToolParam(description = "省份名称，例如：北京", required = true) String province,
			@ToolParam(description = "地市名称，可选，例如：长春", required = false) String city,
			@ToolParam(description = "客户名称关键词，可选，模糊匹配名单客户名称或自然客户名称", required = false) String customerName,
			@ToolParam(description = "行业，可选，精确匹配行业划分", required = false) String industry,
			@ToolParam(description = "客户经理姓名或OA工号，可选，匹配A/B角或挂帅领导", required = false) String manager,
			@ToolParam(description = "是否仅核心要客，可选，true仅返回is_core=1的客户；不传则不限制", required = false) Boolean onlyCore,
			@ToolParam(description = "返回条数上限，可选，默认20，最大100", required = false) Integer limit) {
		return executeWithAuth(token, user -> {
			String cleanProvince = ProvinceUtil.normalizeList(province);
			Map<String, Object> error = checkProvinceAuthorized(user, cleanProvince);
			if (error != null) {
				return error;
			}
			Long tenantId = userService.getTenantId(cleanProvince);
			if (tenantId == null) {
				return Map.of("error", cleanProvince+" 未入住平台");
			}
			return coreCustomerService.queryCoreCustomer(tenantId, cleanProvince, city, customerName,
					industry, manager, onlyCore, limit);
		});
	}

	@Tool(description = "查询【运营商内部填报/跟进的商机】（bid_business 表，本省租户自己录入的业务机会台账）。"
			+ "⚠️ 本工具不是公开市场商机：用户只说『商机/商机数据/商机明细』而未明确说『自己填报的/跟进的/内部商机』时，"
			+ "默认指公开市场标讯/拟在建商机，禁止调用本工具，应走 query-prepose / query-bidding / query-bid-winner 等 skill 或 tender-search。\n"
			+ "【调用条件】\n"
			+ "1. 用户明确表达了『自己填报或跟进的商机』意图（如：查我填的商机、跟进中的商机、内部商机台账、商机进度/环节/客户经理归属/签约情况）；\n"
			+ "2. 必须指定省份（或全国），且省份需在用户授权范围内；\n"
			+ "3. 可按 商机/客户名称、商机编号、当前环节/阶段、客户经理、填报时间区间 等条件过滤；"
			+ "注意本工具按『填报时间』过滤而非公告发布时间，『今日/昨日的商机』类公开市场问题不适用本工具。")
	public Map queryBizOpportunity(
			@ToolParam(description = "登录令牌，由平台自动注入，无需手动传入", required = false) String token,
			@ToolParam(description = "省份名称（按填报人所属省份过滤），例如：北京", required = true) String province,
			@ToolParam(description = "地市名称，可选，例如：长春", required = false) String city,
			@ToolParam(description = "关键词，可选，模糊匹配商机名称或客户名称", required = false) String keyword,
			@ToolParam(description = "商机编号，可选，精确匹配", required = false) String businessNo,
			@ToolParam(description = "商机环节或阶段，可选，匹配当前环节/商机阶段", required = false) String stage,
			@ToolParam(description = "客户经理姓名或工号，可选", required = false) String manager,
			@ToolParam(description = "填报时间起，可选，格式：2026-08-01", required = false) String beginDate,
			@ToolParam(description = "填报时间止，可选，格式：2026-08-31", required = false) String endDate,
			@ToolParam(description = "返回条数上限，可选，默认20，最大100", required = false) Integer limit) {
		return executeWithAuth(token, user -> {
			String cleanProvince = ProvinceUtil.normalizeList(province);
			Map<String, Object> error = checkProvinceAuthorized(user, cleanProvince);
			if (error != null) {
				return error;
			}
			Long tenantId = userService.getTenantId(cleanProvince);
			if (tenantId == null) {
				return Map.of("error", cleanProvince+" 未入住平台");
			}
			return bizOpportunityService.queryBizOpportunity(tenantId, cleanProvince, city, keyword,
					businessNo, stage, manager, beginDate, endDate, limit);
		});
	}

	@Tool(description = "运营商丢标分析（bid_biz_win_bid 中标表）。一次调用即返回丢标原因报告所需的全部统计，"
			+ "无需逐个运营商分别调用再自行汇总。\n"
			+ "\n"
			+ "【丢标口径】本省租户填报了项目参与情况（join_status）且不等于『已中标』的记录；"
			+ "参与情况为空的记录是未跟进的公开标讯，不计入丢标。"
			+ "注意：operator 是『项目归属的运营商市场』维度（招标方是哪家运营商），不是\"谁丢的标\"——"
			+ "本省丢标大多发生在移动/电信/其他等运营商市场的项目上。\n"
			+ "\n"
			+ "【何时调用】用户询问本省丢标/未中标/丢单情况、丢标原因、丢标复盘、输给了谁、"
			+ "哪些行业/哪类原因丢标最多时调用。例如\"分析黑龙江联通最近一个月丢标原因\""
			+ "只调用一次本工具（不传 operator）即可，不要换运营商反复调用；返回的统计字段已覆盖报告所需的全部维度，"
			+ "一次调用后直接写报告，不要反复调用或用脚本重算。\n"
			+ "【调用条件】\n"
			+ "1. 必须指定单个省份（丢标数据按租户/省份隔离，不支持全国汇总），且省份需在用户授权范围内；\n"
			+ "2. operator 可选：不传或传『全部』= 统计本省全部运营商市场的丢标（丢标原因报告用这个）；"
			+ "传 联通/电信/移动/广电/铁塔/其他 之一 = 只看该运营商市场下的丢标分布；"
			+ "禁止为了拼全量而逐个运营商反复调用；\n"
			+ "3. 可按 地市、行业、原因分类、是否复盘、项目名称/招标单位/中标单位关键词、中标时间区间 过滤。\n"
			+ "\n"
			+ "【返回内容】一次调用返回丢标报告所需的全部统计维度：overview（总量/总金额/均价）、reasonStats（原因分类）、"
			+ "joinStatusStats（项目参与情况，用于区分『投标前主动放弃』与『投标后落败』与『结构性不可得』）、"
			+ "industryStats（行业）、cityStats（地市）、weeklyStats（按周走势）、reviewStats（是否复盘）、"
			+ "operatorStats（运营商市场）、winnerFamilyStats（中标方类型）、mechanismStats（丢标机制归因：授权/指定供应商/分包/纯硬件等），"
			+ "以及按中标金额倒序的丢标明细 records（含中标单位、原因分类 lost_reason、是否复盘 review_status、具体情况说明 situation_desc）。\n"
			+ "【输出要求】所有 *Stats 字段已是全量命中数据的统计（不受 limit 影响），报告所需的全部数字直接引用响应字段，"
			+ "禁止再用 bash/python 做任何聚合、分类、核验或时间换算；"
			+ "典型项目直接引用明细里的『原因分类』和『具体情况说明』，按『原因分类 → 典型项目 → 影响金额』组织，再给可执行改进建议；"
			+ "未复盘占比高时单独提示补齐丢标复盘；严禁编造数据中没有的原因。\n"
			+ "【禁止调用】用户要的是公开市场中标/招标项目明细或标讯统计时，走标讯查询工具，不要调用本工具；"
			+ "用户问自己填报跟进的商机进展时走商机查询工具，也不要用本工具。")
	public Map analyzeOperatorLostBid(
			@ToolParam(description = "登录令牌，由平台自动注入，无需手动传入", required = false) String token,
			@ToolParam(description = "省份名称，例如：北京（丢标数据按省份隔离，必须指定单个省份，不支持全国）", required = true) String province,
			@ToolParam(description = "运营商市场维度，可选。不传或传『全部』= 本省全部运营商市场的丢标（丢标原因报告推荐）；传 联通/电信/移动/广电/铁塔/其他 = 只看该市场的丢标", required = false) String operator,
			@ToolParam(description = "地市名称，可选，例如：长春", required = false) String city,
			@ToolParam(description = "行业大类，可选，精确匹配", required = false) String industry,
			@ToolParam(description = "弃标/丢标/漏单原因分类，可选，模糊匹配", required = false) String lostReason,
			@ToolParam(description = "丢标是否复盘，可选，模糊匹配库中实际填报值（如：是/否/已复盘/未复盘）", required = false) String reviewStatus,
			@ToolParam(description = "关键词，可选，模糊匹配项目名称/公告标题/招标单位/中标单位", required = false) String keyword,
			@ToolParam(description = "中标发布时间起，可选，格式：2026-08-01", required = false) String beginDate,
			@ToolParam(description = "中标发布时间止，可选，格式：2026-08-31", required = false) String endDate,
			@ToolParam(description = "丢标明细返回条数上限，可选，默认20，最大500", required = false) Integer limit) {
		return executeWithAuth(token, user -> {
			String cleanProvince = ProvinceUtil.normalizeList(province);
			if (cleanProvince.isEmpty()) {
				return Map.of("error", "省份不能为空，丢标数据按省份隔离，请指定具体省份");
			}
			Map<String, Object> error = checkProvinceAuthorized(user, cleanProvince);
			if (error != null) {
				return error;
			}
			Long tenantId = userService.getTenantId(cleanProvince);
			if (tenantId == null) {
				return Map.of("warning", cleanProvince + " 未入住平台");
			}
			if (tenantId != 130L) {
				return Map.of("warning", cleanProvince + " 敬请期待");
			}
			return operatorLostBidService.analyzeLostBid(tenantId, cleanProvince, operator, city, industry,
					lostReason, reviewStatus, keyword, beginDate, endDate, limit);
		});
	}

	@Tool(description = "公开市场标讯简报。\n" +
			"\n" +
			"【调用条件 - 必须全部满足】\n" +
			"1. 用户明确要求\"简报\"；\n" +
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
	public Map getDayProvinceText(
			@ToolParam(description = "登录令牌，由平台自动注入，无需手动传入", required = false) String token,
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

	@Tool(description = "日标讯统计通报。\n" +
			"\n" +
			"调用条件（必须同时满足）：\n" +
			"- 用户明确使用了\"通报 \"字眼；\n" +
			"- 用户明确指定了省份和日期。\n" +
			"\n" +
			"不调用的情况：\n" +
			"- 用户索要的是项目列表、明细、具体商机、某类招标/中标信息（即使措辞是\"数据\"\"情况\"也算明细需求）；\n" +
			"- 用户意图模糊（如只说\"看看吉林的招标\"），此时先向用户澄清要\"汇总通报\"还是\"项目明细\"，不要直接调用。\n")
	public Map getWeekProvinceText(
			@ToolParam(description = "登录令牌，由平台自动注入，无需手动传入", required = false) String token,
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
		// AI 每日调用限制：工具调用前检查并扣减当日次数，用完拒绝（跨天自动重置）
		if (!userService.tryConsumeAiQuota(user.getId())) {
			return Map.of("error", "今日 AI 调用次数已用完，次日将自动恢复。");
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
