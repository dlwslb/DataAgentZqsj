package com.jldaren.agent.ai.datascope.compression;

import lombok.Builder;
import lombok.Data;

/**
 * 压缩配置
 */
@Data
@Builder
public class CompressionConfig {

    /**
     * 请求压缩编码（null 表示不压缩请求）
     */
    @Builder.Default
    private CompressionEncoding requestCompression = null;

    /**
     * 响应接受的压缩编码列表
     */
    @Builder.Default
    private CompressionEncoding acceptEncoding = CompressionEncoding.GZIP;

    /**
     * 启用 GZIP 压缩的便捷方法
     */
    public static CompressionConfig enableGzip() {
        return CompressionConfig.builder()
                .requestCompression(CompressionEncoding.GZIP)
                .acceptEncoding(CompressionEncoding.GZIP)
                .build();
    }
}
