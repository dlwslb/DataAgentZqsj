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
package com.jldaren.agent.ai.datascope.exception;

import com.jldaren.agent.ai.datascope.vo.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.concurrent.TimeoutException;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(InvalidInputException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Object> handleInvalidInputException(InvalidInputException e) {
		log.warn("Invalid input: {}", e.getMessage());
		return ApiResponse.error(e.getMessage(), e.getData());
	}

	@ExceptionHandler(InternalServerException.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public ApiResponse<Object> handleInternalServerException(InternalServerException e) {
		log.error("Internal server error: {}", e.getMessage(), e);
		return ApiResponse.error(e.getMessage());
	}

	@ExceptionHandler(TimeoutException.class)
	@ResponseStatus(HttpStatus.GATEWAY_TIMEOUT)
	public ApiResponse<Object> handleTimeout(TimeoutException e) {
		log.warn("请求超时: {}", e.getMessage());
		return ApiResponse.error("请求超时，请稍后重试");
	}

	@ExceptionHandler(Exception.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public ApiResponse<Object> handleGenericException(Exception e) {
		log.error("Unexpected error: {}", e.getMessage(), e);
		return ApiResponse.error("服务器内部错误");
	}

}
