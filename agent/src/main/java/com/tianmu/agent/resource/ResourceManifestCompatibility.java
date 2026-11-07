package com.tianmu.agent.resource;

import java.util.Map;
import java.util.Objects;
import org.springframework.core.io.ClassPathResource;

/** Exact legacy alias from a released artifact; CRLF/LF equivalence never blesses changed content. */
public final class ResourceManifestCompatibility {
    private ResourceManifestCompatibility() {}
    private record Alias(String sourceAgentRevision,Map<String,String> resourceVersions,Map<String,String> canonicalResourceVersions) {}
    private static final Alias ALIAS=ResourceFiles.json(new ClassPathResource("compat/legacy-resource-manifest-59a1746.json"),Alias.class);
    public static boolean legacy(Map<String,String> stored,Map<String,String> raw,Map<String,String> canonical) {
        return raw.equals(stored) || ALIAS.resourceVersions().equals(stored) && ALIAS.canonicalResourceVersions().equals(canonical);
    }
    public static boolean skill(String name,String stored,String format,String raw,String canonical) {
        if("2".equals(format))return Objects.equals(stored,canonical);
        if(format!=null)return false;
        String key="skill-content:"+name;
        return Objects.equals(stored,raw) || Objects.equals(stored,ALIAS.resourceVersions().get(key)) && Objects.equals(canonical,ALIAS.canonicalResourceVersions().get(key));
    }
}
