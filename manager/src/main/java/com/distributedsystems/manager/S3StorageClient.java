package com.distributedsystems.manager;

import com.distributedsystems.aws.S3Service;
import com.distributedsystems.manager.CloudInterfaces.StorageClient;

import java.util.Arrays;
import java.util.List;

/**
 * AWS S3 implementation of {@link StorageClient} backed by the shared {@link S3Service}.
 */
public class S3StorageClient implements StorageClient {

    private final S3Service s3Service;

    public S3StorageClient(S3Service s3Service) {
        if (s3Service == null) {
            throw new IllegalArgumentException("s3Service must not be null");
        }
        this.s3Service = s3Service;
    }

    @Override
    public List<String> downloadLines(String s3Key) {
        String content = s3Service.downloadString(s3Key);
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(content.split("\\R"));
    }

    @Override
    public void uploadText(String s3Key, String content) {
        s3Service.uploadString(s3Key, content);
    }
}