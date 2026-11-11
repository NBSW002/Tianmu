package com.orangecode.tianmu.application;

import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.exception.BusinessException;
import java.util.Set;

/** Limits are checked before storage access, allocations or signing. */
public final class UploadPolicy {
    private UploadPolicy() {}
    public static final long MAX_BYTES = 3L * 1024 * 1024 * 1024;
    public static final int CHUNK_BYTES = 5 * 1024 * 1024;
    public static final int WINDOW = 32;
    public record Request(String fileHash, long sizeBytes, String fileType) {
        public Request {
            if (fileHash == null || !fileHash.matches("[a-f0-9]{32}") || sizeBytes < 1 || sizeBytes > MAX_BYTES
                    || !Set.of("mp4", "mov", "avi", "webm").contains(fileType == null ? "" : fileType))
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "无效的视频格式或大小（最大3GB）");
        }
        public int chunks() { return (int)((sizeBytes + CHUNK_BYTES - 1) / CHUNK_BYTES); }
        public long chunkSize(int index) {
            if (index < 0 || index >= chunks()) throw new BusinessException(ErrorCode.PARAMS_ERROR);
            return Math.min(CHUNK_BYTES, sizeBytes - (long) index * CHUNK_BYTES);
        }
    }
}
