package com.jldaren.agent.ai.datascope;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.net.InetAddress;

@SpringBootApplication
@EnableAspectJAutoProxy
@Slf4j
public class AgentScopeApiApplication {

    @Value("${server.port:8080}")
    private int port;

    public static void main(String[] args) {
        SpringApplication.run(AgentScopeApiApplication.class, args);
    }

    @Bean
    public ApplicationRunner printStartupInfo() {
        return args -> {
            String hostAddress;
            try {
                hostAddress = InetAddress.getLocalHost().getHostAddress();
            } catch (Exception e) {
                hostAddress = "localhost";
            }
            
            log.info("\n" +
                "╔═══════════════════════════════════════════════════════════════╗\n" +
                "║              AgentScope API 服务已启动                          ║\n" +
                "╠═══════════════════════════════════════════════════════════════╣\n" +
                "║  本地访问:  http://localhost:{}{}                          ║\n" +
                "║  网络访问:  http://{}:{}{}                          ║\n" +
                "║  Swagger:  http://localhost:{}/swagger-ui.html              ║\n" +
                "╚═══════════════════════════════════════════════════════════════╝",
                port, "/api/v1/chat/quick?message=你好",
                hostAddress, port, "/api/v1/chat/quick?message=你好",
                port);
        };
    }
}