package com.llmstudy.rag.config;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketPolicyArgs;
import io.minio.errors.BucketPolicyTooLargeException;
import io.minio.errors.ErrorResponseException;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.Proxy;

@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfig {

    private static final Logger log = LoggerFactory.getLogger(MinioConfig.class);

    /**
     * 公共读策略
     */
    private static final String PUBLIC_READ_POLICY = """
            {
              "Version": "2012-10-17",
              "Statement": [
                {
                  "Effect": "Allow",
                  "Principal": {"AWS": ["*"]},
                  "Action": ["s3:GetObject"],
                  "Resource": ["arn:aws:s3:::%s/*"]
                }
              ]
            }
            """;

    /**
     * 创建 MinioClient Bean，显式禁用代理避免 JVM 系统代理干扰。
     */
    @Bean
    public MinioClient minioClient(MinioProperties properties) {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .build();

        MinioClient client = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .httpClient(httpClient)
                .build();
        return client;
    }

    @Bean
    public CommandLineRunner initBucket(MinioClient minioClient, MinioProperties properties) {
        return args -> {
            String bucketName = properties.getBucketName();
            try {
                // 直接创建 bucket，已存在则跳过，避免 bucketExists 的重定向问题
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("Bucket [{}] created successfully", bucketName);
            } catch (ErrorResponseException e) {
                if ("BucketAlreadyOwnedByYou".equals(e.errorResponse().code())) {
                    log.info("Bucket [{}] already exists", bucketName);
                } else {
                    log.warn("MinIO makeBucket failed: {}", e.errorResponse().message());
                }
            } catch (Exception e) {
                log.warn("MinIO init failed: {}. Application will continue without MinIO.", e.getMessage());
                return;
            }

            try {
                String policy = String.format(PUBLIC_READ_POLICY, bucketName);
                minioClient.setBucketPolicy(
                        SetBucketPolicyArgs.builder()
                                .bucket(bucketName)
                                .config(policy)
                                .build()
                );
                log.info("Bucket [{}] public read policy set successfully", bucketName);
            } catch (Exception e) {
                log.warn("MinIO setBucketPolicy failed: {}.", e.getMessage());
            }
        };
    }
}
