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
package com.alibaba.cloud.ai.dataagent.util;

public class MarkdownParserUtil {

	public static String extractText(String markdownCode) {
		String code = extractRawText(markdownCode);
		// Correctly handle various newline character types: \r\n, \n, \r, but maintain
		// compatibility with NewLineParser.format()
		return code.replaceAll("\r\n", " ").replaceAll("\n", " ").replaceAll("\r", " ");
	}

	public static String extractRawText(String markdownCode) {
		// Find the start of a code block (3 or more backticks)
		int startIndex = -1;
		int delimiterLength = 0;

		for (int i = 0; i <= markdownCode.length() - 3; i++) {
			if (markdownCode.substring(i, i + 3).equals("```")) {
				startIndex = i;
				delimiterLength = 3;
				// Count additional backticks
				while (i + delimiterLength < markdownCode.length() && markdownCode.charAt(i + delimiterLength) == '`') {
					delimiterLength++;
				}
				break;
			}
		}

		if (startIndex == -1) {
			return markdownCode; // No code block found
		}

		// Skip the opening delimiter and optional language specification
		int contentStart = startIndex + delimiterLength;
		while (contentStart < markdownCode.length() && markdownCode.charAt(contentStart) != '\n') {
			contentStart++;
		}
		if (contentStart < markdownCode.length() && markdownCode.charAt(contentStart) == '\n') {
			contentStart++; // Skip the newline after language spec
		}

		// Find the closing delimiter
		String closingDelimiter = "`".repeat(delimiterLength);
		int endIndex = markdownCode.indexOf(closingDelimiter, contentStart);

		if (endIndex == -1) {
			// No closing delimiter found, return from content start to end
			return markdownCode.substring(contentStart);
		}

		// Extract just the content between delimiters
		String code = markdownCode.substring(contentStart, endIndex);

		// Normalize indentation: convert tabs to 4 spaces and remove common leading
		// indentation
		return normalizeIndentation(code);
	}

	/**
	 * Normalize code indentation by converting tabs to spaces and removing common
	 * leading indentation.
	 * @param code the code with potentially mixed indentation
	 * @return the code with normalized indentation (only spaces, no tabs)
	 */
	private static String normalizeIndentation(String code) {
		// Convert tabs to 4 spaces
		code = code.replace("\t", "    ");

		// Split into lines
		String[] lines = code.split("\n", -1);

		// Find the minimum indentation (ignoring empty lines)
		int minIndent = Integer.MAX_VALUE;
		for (String line : lines) {
			if (line.trim().isEmpty()) {
				continue; // Skip empty lines
			}
			// Count leading spaces
			int leadingSpaces = 0;
			for (char c : line.toCharArray()) {
				if (c == ' ') {
					leadingSpaces++;
				}
				else {
					break;
				}
			}
			if (leadingSpaces < minIndent) {
				minIndent = leadingSpaces;
			}
		}

		// If no indentation found, return as is
		if (minIndent == Integer.MAX_VALUE || minIndent == 0) {
			return code;
		}

		// Remove common indentation from all lines
		StringBuilder result = new StringBuilder();
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			if (line.trim().isEmpty()) {
				result.append("");
			}
			else if (line.length() >= minIndent) {
				result.append(line.substring(minIndent));
			}
			else {
				result.append(line);
			}
			if (i < lines.length - 1) {
				result.append("\n");
			}
		}

		return result.toString();
	}

}
