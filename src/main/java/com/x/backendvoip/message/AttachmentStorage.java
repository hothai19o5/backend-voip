package com.x.backendvoip.message;

import com.x.backendvoip.common.ApiException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

@Component
class AttachmentStorage {
    private static final Logger log = LoggerFactory.getLogger(AttachmentStorage.class);
    private final MinioClient client;
    private final String bucket;
    private boolean initialized;

    AttachmentStorage(@Value("${app.attachments.endpoint}") String endpoint,
                      @Value("${app.attachments.bucket}") String bucket,
                      @Value("${app.attachments.access-key}") String accessKey,
                      @Value("${app.attachments.secret-key}") String secretKey) {
        this.bucket = bucket;
        client = accessKey.isBlank() || secretKey.isBlank() ? null :
                MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        if (client != null) client.setTimeout(3000, 15000, 15000);
    }

    void upload(MessageAttachment attachment, MultipartFile file) {
        requireClient();
        try (var stream = file.getInputStream()) {
            ensureBucket();
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(attachment.getKey())
                    .stream(stream, attachment.getSize(), -1)
                    .contentType(attachment.getContentType()).build());
        } catch (Exception ex) {
            throw storageFailure(ex);
        }
    }

    InputStream download(MessageAttachment attachment) {
        requireClient();
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(attachment.getKey()).build());
        } catch (ErrorResponseException ex) {
            if ("NoSuchKey".equals(ex.errorResponse().code()))
                throw new ApiException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "Attachment not found.");
            throw storageFailure(ex);
        } catch (Exception ex) {
            throw storageFailure(ex);
        }
    }

    void discard(MessageAttachment attachment) {
        if (client == null) return;
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(attachment.getKey()).build());
        } catch (Exception ex) {
            log.warn("Could not remove uncommitted attachment {}", attachment.getKey());
        }
    }

    private synchronized void ensureBucket() throws Exception {
        if (initialized) return;
        var exists = BucketExistsArgs.builder().bucket(bucket).build();
        if (!client.bucketExists(exists)) {
            try {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            } catch (ErrorResponseException ex) {
                if (!"BucketAlreadyOwnedByYou".equals(ex.errorResponse().code())) throw ex;
            }
        }
        initialized = true;
    }

    private void requireClient() {
        if (client == null)
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ATTACHMENT_UNAVAILABLE", "File storage is not configured.");
    }

    private static ApiException storageFailure(Exception ex) {
        log.warn("Attachment storage operation failed ({})", ex.getClass().getSimpleName());
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ATTACHMENT_UNAVAILABLE", "File storage is temporarily unavailable.");
    }
}
