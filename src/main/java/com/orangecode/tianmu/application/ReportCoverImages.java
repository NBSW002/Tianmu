package com.orangecode.tianmu.application;

import io.minio.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.util.*;

/** Only call after checking video access; stored object keys never trigger external URL requests. */
@Service
public final class ReportCoverImages {
    private static final int MAX_BYTES=262144;
    private final MinioClient client;
    private final String bucket;
    private final MediaThumbnails thumbnails;
    public ReportCoverImages(MinioClient client,@Value("${minio.bucket-name}") String bucket,MediaThumbnails thumbnails){this.client=client;this.bucket=bucket;this.thumbnails=thumbnails;}
    public String read(String source){
        if(source==null || source.isBlank())return "";
        try{
            String path=URI.create(source).getPath(),prefix="/"+bucket+"/";
            if(path==null || !path.startsWith(prefix))return "";
            String key=path.substring(prefix.length());
            if(!key.matches("(?i)[a-z0-9_-]{8,128}\\.(png|jpe?g|gif|webp|avif)"))return "";
            var stat=client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            String ext=key.substring(key.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
            String mime=switch(ext){case "jpg","jpeg"->"image/jpeg";case "png"->"image/png";case "gif"->"image/gif";case "webp"->"image/webp";default->"image/avif";};
            byte[] image=null;
            if(!Set.of("webp","avif").contains(ext)){
                image=thumbnails.get(bucket+"/"+key+":"+stat.etag()+":"+stat.size(),640,stat.size(),
                        ()->client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build()));
                if(image!=null)mime="image/webp";
            }
            if(image==null && stat.size()>0 && stat.size()<=MAX_BYTES){
                try(var input=client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())){image=input.readNBytes(MAX_BYTES+1);}
            }
            if(image==null || image.length==0 || image.length>MAX_BYTES)return "";
            return "data:"+mime+";base64,"+Base64.getEncoder().encodeToString(image);
        }catch(Exception unavailable){return "";}
    }
}
