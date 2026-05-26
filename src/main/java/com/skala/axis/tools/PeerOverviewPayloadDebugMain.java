package com.skala.axis.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skala.axis.AxisApplication;
import com.skala.axis.service.PeerOverviewTableService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

public final class PeerOverviewPayloadDebugMain {
    private PeerOverviewPayloadDebugMain() {
    }

    public static void main(String[] args) throws Exception {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(AxisApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("local")
                .properties(
                        "axis.auth.enforce=false",
                        "spring.main.banner-mode=off",
                        "spring.flyway.enabled=false"
                )
                .run(args)) {
            PeerOverviewTableService service = context.getBean(PeerOverviewTableService.class);
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            Object payload = service.getPeerOverviewTable();

            System.out.println("== PeerOverview payload ==");
            System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
        }
    }
}
