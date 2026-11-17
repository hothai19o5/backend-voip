package com.x.backendvoip.sip;

import com.x.backendvoip.common.ApiException;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class FusionPbxException extends ApiException {
    // Only explicit pre-write rejections can safely be marked FAILED.
    private final boolean definitiveRejection;

    public FusionPbxException(HttpStatus status, String code, String detail, boolean definitiveRejection) {
        super(status, code, detail);
        this.definitiveRejection = definitiveRejection;
    }
}
