package com.tianmu.agent.model.deepseek;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("agent.models")
public record DeepSeekProperties(@DefaultValue("https://api.deepseek.com") URI baseUrl,
        String apiKey, @DefaultValue("deepseek-flash") String preparationModel,
        @DefaultValue("deepseek-flash") String harnessModel,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("384") int preparationMaxTokens, @DefaultValue("3072") int harnessMaxTokens,
        @DefaultValue("deepseek-flash") String analysisModel,@DefaultValue("true") boolean thinkingEnabled,
        @DefaultValue("high") String thinkingEffort,@DefaultValue("low") String analysisThinkingEffort,
        @DefaultValue("16384") int analysisMaxTokens) {
    public DeepSeekProperties(URI baseUrl,String apiKey,String preparationModel,String harnessModel,Duration connectTimeout,int preparationMaxTokens,int harnessMaxTokens,String analysisModel,boolean thinkingEnabled){this(baseUrl,apiKey,preparationModel,harnessModel,connectTimeout,preparationMaxTokens,harnessMaxTokens,analysisModel,thinkingEnabled,"high","low",16384);}
    public DeepSeekProperties(URI baseUrl,String apiKey,String preparationModel,String harnessModel,Duration connectTimeout,int preparationMaxTokens,int harnessMaxTokens,String analysisModel){this(baseUrl,apiKey,preparationModel,harnessModel,connectTimeout,preparationMaxTokens,harnessMaxTokens,analysisModel,true);}
    public DeepSeekProperties(URI baseUrl, String apiKey, String preparationModel, String harnessModel,
            Duration connectTimeout, int preparationMaxTokens, int harnessMaxTokens) {
        this(baseUrl, apiKey, preparationModel, harnessModel, connectTimeout, preparationMaxTokens, harnessMaxTokens, "deepseek-flash");
    }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public DeepSeekProperties {
        if(thinkingEffort==null || analysisThinkingEffort==null || !java.util.Set.of("low","high","max").contains(thinkingEffort) || !java.util.Set.of("low","high","max").contains(analysisThinkingEffort)
                || analysisMaxTokens<8192 || analysisMaxTokens>32768)throw new IllegalArgumentException("Invalid thinking policy");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("DEEPSEEK_API_KEY is required");
        boolean local = SetHolder.LOCAL.contains(baseUrl.getHost());
        if ((!"https".equals(baseUrl.getScheme()) && !(local && "http".equals(baseUrl.getScheme())))
                || baseUrl.getRawUserInfo() != null || baseUrl.getRawQuery() != null || baseUrl.getFragment() != null)
            throw new IllegalArgumentException("A secure model base URL is required");
        for (String model : new String[]{preparationModel, harnessModel, analysisModel})
            if (model == null || !model.matches("[a-zA-Z0-9_.:/-]{1,100}")) throw new IllegalArgumentException("Invalid model ID");
        if (connectTimeout == null || connectTimeout.toMillis() < 1 || connectTimeout.compareTo(Duration.ofSeconds(30)) > 0
                || preparationMaxTokens < 64 || preparationMaxTokens > 2048 || harnessMaxTokens < 128 || harnessMaxTokens > 8192)
            throw new IllegalArgumentException("Invalid model call limits");
    }
    private static class SetHolder { static final java.util.Set<String> LOCAL = java.util.Set.of("localhost", "127.0.0.1", "[::1]"); }
    public String analysisProtocolRevision(){return "thinking-v2\n"+baseUrl+"\n"+analysisModel+"\n"+thinkingEnabled+"\n"+analysisThinkingEffort+"\n"+analysisMaxTokens;}
    @Override public String toString() { return "DeepSeekProperties[preparationModel=" + preparationModel + ", harnessModel=" + harnessModel + ", apiKey=REDACTED]"; }
}
