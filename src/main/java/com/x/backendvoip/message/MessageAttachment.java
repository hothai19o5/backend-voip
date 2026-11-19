package com.x.backendvoip.message;

import com.x.backendvoip.common.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MessageAttachment {
    static final long MAX_BYTES = 10 * 1024 * 1024;
    private static final Pattern UNSAFE_FILENAME = Pattern.compile("[\\p{Cc}\\p{Cf}]");

    @Column(name = "attachment_key", length = 36)
    private String key;
    @Column(name = "attachment_name", length = 240)
    private String filename;
    @Column(name = "attachment_type", length = 32)
    private String contentType;
    @Column(name = "attachment_size")
    private Long size;
    @Column(name = "attachment_digest", length = 64)
    private String digest;

    static MessageAttachment inspect(MultipartFile file) {
        if (file.isEmpty())
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_ATTACHMENT", "The selected file is empty.");
        if (file.getSize() > MAX_BYTES)
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "ATTACHMENT_TOO_LARGE", "Files may be at most 10 MiB.");
        var attachment = new MessageAttachment();
        attachment.key = UUID.randomUUID().toString();
        attachment.filename = safeFilename(file.getOriginalFilename());
        attachment.size = file.getSize();
        try (var stream = new DigestInputStream(file.getInputStream(), MessageDigest.getInstance("SHA-256"))) {
            attachment.contentType = mediaType(HexFormat.of().formatHex(stream.readNBytes(12)));
            stream.transferTo(OutputStream.nullOutputStream());
            attachment.digest = HexFormat.of().formatHex(stream.getMessageDigest().digest());
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT", "The selected file could not be read.");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        return attachment;
    }

    private static String safeFilename(String original) {
        if (original == null) return "attachment";
        String path = original.replace('\\', '/');
        String name = UNSAFE_FILENAME.matcher(path.substring(path.lastIndexOf('/') + 1)).replaceAll("").strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) return "attachment";
        int end = name.offsetByCodePoints(0, Math.min(120, name.codePointCount(0, name.length())));
        return name.substring(0, end);
    }

    private static String mediaType(String signature) {
        if (signature.startsWith("89504e470d0a1a0a")) return "image/png";
        if (signature.startsWith("ffd8ff")) return "image/jpeg";
        if (signature.startsWith("474946383761") || signature.startsWith("474946383961")) return "image/gif";
        if (signature.startsWith("52494646") && signature.endsWith("57454250") && signature.length() == 24)
            return "image/webp";
        return "application/octet-stream";
    }

    boolean matches(MessageAttachment other) {
        return other != null && filename.equals(other.filename) && digest.equals(other.digest);
    }

    public record Metadata(String filename, String contentType, long size) {}

    Metadata metadata() {
        return new Metadata(filename, contentType, size);
    }
}
