package io.github.sanskar121543.mcp;

import java.util.Arrays;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The MCP server for the Transaction Audit & Matching Service. Two transports:
 * <ul>
 * <li>Streamable HTTP (default) on {@code /mcp}, for clients that connect to a URL;</li>
 * <li>stdio ({@code --stdio}), for clients that start the server themselves. stdout then carries only MCP
 * messages: no banner, no web server, console logging off.</li>
 * </ul>
 * Either way it is read-only by construction: see {@link ServiceClient} and {@link KeyCheck}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(McpServerApplication.class);
        if (Arrays.asList(args).contains("--stdio")) {
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setBannerMode(Banner.Mode.OFF);
            application.setAdditionalProfiles("stdio");
        }
        application.run(args);
    }
}
