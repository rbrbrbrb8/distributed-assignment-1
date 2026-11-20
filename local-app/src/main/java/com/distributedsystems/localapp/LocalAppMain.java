package com.distributedsystems.localapp;

import java.nio.file.Path;
import java.time.Duration;

import com.distributedsystems.aws.EC2Service;
import com.distributedsystems.aws.S3Service;
import com.distributedsystems.aws.SQSService;
import com.distributedsystems.localapp.manager.Ec2ManagerLocator;
import com.distributedsystems.localapp.summary.Base64SummaryParser;

/**
 * Entry point of {@code local-app.jar}:
 * <pre>
 * java -jar local-app.jar inputFileName outputFileName manager-clients-n worker-files-n [terminate]
 * </pre>
 * Settings come from {@code local-app.properties} in the working directory; AWS credentials from
 * {@code ~/.aws/credentials}. Exit code: 0 = done, 1 = the run failed, 2 = bad arguments or settings.
 */
public final class LocalAppMain {

    /** How often to check, while waiting, that the manager is still running. */
    private static final Duration LIVENESS_INTERVAL = Duration.ofSeconds(60);

    private LocalAppMain() {
    }

    public static void main(String[] argv) {
        LocalAppArgs args;
        LocalAppConfig config;
        try {
            args = LocalAppArgs.parse(argv);
            config = LocalAppConfig.load(Path.of(LocalAppConfig.DEFAULT_FILE));
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.err.println(LocalAppArgs.USAGE);
            System.exit(2);
            return;
        }

        try (SQSService sqs = new SQSService(config.region());
             S3Service s3 = new S3Service(config.region(), config.bucket());
             EC2Service ec2 = new EC2Service(config.region())) {

            LocalApp app = new LocalApp(sqs, s3,
                    new Ec2ManagerLocator(ec2, config.amiId(), config.bucket(), config.managerJarKey()),
                    new Base64SummaryParser(), config.waitTimeout(), LIVENESS_INTERVAL);
            app.run(args);
        } catch (Exception e) {
            System.err.println("[local-app] Failed: " + e.getMessage());
            System.exit(1);
        }
    }
}
