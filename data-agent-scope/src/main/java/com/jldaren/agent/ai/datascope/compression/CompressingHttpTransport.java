package com.jldaren.agent.ai.datascope.compression;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 支持压缩的 HTTP 传输层
 */
@Slf4j
public class CompressingHttpTransport implements HttpTransport {

    private final HttpTransport delegate;
    private final CompressionConfig compressionConfig;

    public CompressingHttpTransport(HttpTransport delegate, CompressionConfig compressionConfig) {
        this.delegate = delegate;
        this.compressionConfig = compressionConfig;
    }

    @Override
    public HttpResponse execute(HttpRequest request) {
        HttpRequest compressedRequest = compressRequest(request);
        HttpResponse response = delegate.execute(compressedRequest);
        return decompressResponse(response);
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        HttpRequest compressedRequest = compressRequest(request);
        return delegate.stream(compressedRequest);
    }

    /**
     * 压缩请求
     */
    private HttpRequest compressRequest(HttpRequest request) {
        if (compressionConfig.getRequestCompression() == null) {
            return request;
        }

        try {
            String bodyStr = request.getBody();
            byte[] originalBody = bodyStr != null ? bodyStr.getBytes() : null;
            if (originalBody == null || originalBody.length == 0) {
                return request;
            }

            byte[] compressedBody = CompressionUtils.gzipCompress(originalBody);
            
            Map<String, String> headers = new HashMap<>(request.getHeaders());
            headers.put("Content-Encoding", compressionConfig.getRequestCompression().getValue());
            headers.put("Accept-Encoding", compressionConfig.getAcceptEncoding().getValue());

            return HttpRequest.builder()
                    .method(request.getMethod())
                    .url(request.getUrl())
                    .headers(headers)
                    .body(new String(compressedBody))
                    .build();
        } catch (IOException e) {
            log.warn("Failed to compress request, sending uncompressed", e);
            return request;
        }
    }

    /**
     * 解压响应
     */
    private HttpResponse decompressResponse(HttpResponse response) {
        String contentEncoding = response.getHeaders().get("Content-Encoding");
        if (contentEncoding == null || !contentEncoding.equalsIgnoreCase("gzip")) {
            return response;
        }

        try {
            byte[] decompressedBody = CompressionUtils.gzipDecompress(response.getBody().getBytes());
            
            Map<String, String> headers = new HashMap<>(response.getHeaders());
            headers.remove("Content-Encoding");

            return HttpResponse.builder()
                    .statusCode(response.getStatusCode())
                    .headers(headers)
                    .body(new String(decompressedBody))
                    .build();
        } catch (IOException e) {
            log.warn("Failed to decompress response", e);
            return response;
        }
    }

    @Override
    public void close() {
        delegate.close();
    }
}
