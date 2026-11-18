package com.contentgrid.platform.runtime.helm;

import com.adobe.testing.s3mock.testcontainers.S3MockContainer;
import java.io.IOException;
import java.util.Properties;
import lombok.experimental.UtilityClass;

/**
 * Keeps the S3Mock image tag aligned with the resolved Testcontainers dependency.
 */
@UtilityClass
public class S3MockUtils {

    private static final String S3_MOCK_VERSION;

    static {
        // Dependency updates automatically select the matching container image.
        var properties = new Properties();
        try (var metadata = S3MockContainer.class.getResourceAsStream(
                "/META-INF/maven/com.adobe.testing/s3mock-testcontainers/pom.properties")) {
            properties.load(metadata);
            S3_MOCK_VERSION = properties.getProperty("version");
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read the S3Mock dependency version", e);
        }
    }

    public static S3MockContainer s3MockContainer() {
        return new S3MockContainer(S3_MOCK_VERSION);
    }
}
