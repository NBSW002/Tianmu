package com.orangecode.tianmu.utils;

import jakarta.annotation.Resource;

import java.io.InputStream;
import java.util.UUID;






import cn.hutool.core.util.StrUtil;
import io.minio.*;


import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@Slf4j
public class MinioUtil {

    @Resource
    private MinioClient minioClient;

    @Value("${minio.bucket-name}")
    private String bucketName;

    @Value("${minio.url}")
    private String url;


    /**
     * @MethodName ensureBucketExists
     * @Description 确保桶存在
     * @param: bucketName
     * @return: boolean
     * @Date 2025/4/10 15:30
     */
    public boolean ensureBucketExists() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            }
            return true; // 无论是否新建，只要最终桶存在即返回true
        } catch (Exception e) {
            throw new RuntimeException("Bucket操作失败", e);
        }
    }


    /**
     * @MethodName downloadUrl
     * @Description 获取下载链接
     * @param: fileName
     * @return: String
     * @Date 2025/4/10 15:31
     */
    public String downloadUrl(String fileName) {
        return url + StrUtil.SLASH + bucketName + StrUtil.SLASH + fileName;
    }


    /**
     * @MethodName updateCover
     * @Description 上传封面
     * @param: file
     * @return: String
     * @Date 2025/4/10 15:32
     */
    public String updateCover(MultipartFile file) throws Exception {
        ensureBucketExists();
        String fileSuffix = file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf(".") + 1);
        String id = UUID.randomUUID().toString();
        String fileName = id + "." + fileSuffix;
        InputStream inputStream = file.getInputStream();
        String contentType = new ContentTypeUtil().getType(fileSuffix);
        try {
            minioClient.putObject(PutObjectArgs.builder().bucket(bucketName).object(fileName).stream(inputStream, file.getSize(), -1).contentType(contentType).build());
        } catch (Exception e) {
            throw new RuntimeException("文件上传失败", e);
        }
        return downloadUrl(fileName);
    }


}
