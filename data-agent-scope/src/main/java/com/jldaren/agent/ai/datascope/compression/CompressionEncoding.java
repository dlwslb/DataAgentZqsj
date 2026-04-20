package com.jldaren.agent.ai.datascope.compression;

/**
 * 压缩编码枚举
 */
public enum CompressionEncoding {
    GZIP("gzip"),
    BROTLI("br"),
    ZSTD("zstd");

    private final String value;

    CompressionEncoding(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
