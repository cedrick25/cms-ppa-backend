package ppa.cms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import ppa.cms.command.CliCommands;
import ppa.cms.config.CmsProperties;

@SpringBootApplication
@EnableConfigurationProperties(CmsProperties.class)
public class CmsApplication {

    public static void main(String[] args) {
        DotEnv.load();
        SpringApplication application = new SpringApplication(CmsApplication.class);
        if (args.length > 0 && CliCommands.isCommand(args[0])) {
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setAdditionalProfiles("cli");
            System.exit(SpringApplication.exit(application.run(args)));
            return;
        }
        application.run(args);
    }
}
