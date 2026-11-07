package com.tianmu.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.harness.trace.*;
import com.tianmu.agent.model.*;
import com.tianmu.agent.model.deepseek.*;
import com.tianmu.agent.runtime.control.*;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.*;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in only. Uses fabricated conversations and the normal adapter, projection, budget and Trace. */
@EnabledIfEnvironmentVariable(named="AGENT_COMPACTION_REAL",matches="true")
class CompactionRealModelTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @Test void preservesRejectedVideoEmbeddingAndCurrentLayout()throws Exception{var result=runCase("layout",List.of("我否决把视频播放器嵌入Agent的方案。播放器留在原视频页，助手由浮标按钮打开右侧弹窗，点击页面空白处收起。","收到，保留原播放器，先设计布局。","也不要把历史记录藏起来，直接放在助手左边。","已明确设计方向，界面尚未修改。"));String text=json.writeValueAsString(result);assertTrue(text.contains("播放器"));assertTrue(text.contains("右侧"));assertTrue(text.contains("否决") || text.contains("不嵌入") || text.contains("不应") || text.contains("rejected_option"));}
    @Test void doesNotPromoteDraftPreparationIntoPublishingPermission()throws Exception{var result=runCase("draft",List.of("视频1001计划在2026-11-08 20:00发布，现在只准备评论草稿，不允许发布，必须等我确认。","已准备草稿，尚未发布。","保留这条限制，后续先检查草稿内容，不能自动发送。","下一步等待用户审核和确认，不执行发布。"));String text=json.writeValueAsString(result);assertTrue(text.contains("草稿"));assertTrue(text.matches("(?s).*(未发布|不发布|禁止发布|不能发布|不允许发布|不得发布|等待.{0,12}确认|确认后.{0,8}发布).*"));}
    Map<String,Object> runCase(String name,List<String> texts)throws Exception{
        var spans=new ArrayList<SpanData>();SpanExporter exporter=new SpanExporter(){public CompletableResultCode export(Collection<SpanData> values){spans.addAll(values);return CompletableResultCode.ofSuccess();}public CompletableResultCode flush(){return CompletableResultCode.ofSuccess();}public CompletableResultCode shutdown(){return CompletableResultCode.ofSuccess();}};
        try(var provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()){
            var tracer=provider.get("compaction-real-test");var span=tracer.spanBuilder("agent.context.compact").startSpan();TraceNode.begin(span,"agent.context.compact");
            var config=new DeepSeekProperties(URI.create(System.getenv("AGENT_COMPACTION_REAL_URL")),System.getenv().getOrDefault("AGENT_COMPACTION_REAL_KEY","local-test-proxy"),System.getenv("AGENT_COMPACTION_REAL_MODEL"),System.getenv("AGENT_COMPACTION_REAL_MODEL"),Duration.ofSeconds(3),384,3072);
            var model=new DeepSeekMemoryModel(config,json,ContextPolicy.defaults(),tracer);var planner=new CompactionPlanner(json);
            var messages=new ArrayList<MemoryTypes.Message>();UUID run=null;for(int i=0;i<texts.size();i++){if(i%2==0)run=UUID.randomUUID();messages.add(new MemoryTypes.Message(UUID.randomUUID(),run,i+1,i%2==0?"user":"assistant",texts.get(i)+(i%2==0?"补充背景：这是一项界面和内容运营的设计讨论，最终操作需要按照用户的明确要求推进。".repeat(14):""),Instant.EPOCH));}
            var plan=planner.plan(messages,512,32768);assertNotNull(plan);Map<String,Object> summary;
            try(var scope=span.makeCurrent()){
                var response=model.summarize(CompactionService.PROMPT,Map.of("messages",plan.messages()),2048,new CallControl(Instant.now().plusSeconds(45),new CancellationToken(),new ModelBudget(65536,8192),()->{}));summary=planner.validate(response,plan,3500);
                TraceAttributes.apply(span,Map.of("span.type","context_compact","compaction.covered_from",plan.from(),"compaction.covered_through",plan.through(),"compaction.source_messages",plan.messages().size(),"compaction.input_bytes",plan.inputBytes(),"compaction.output_bytes",planner.bytes(summary),"compaction.saved_bytes",plan.inputBytes()-planner.bytes(summary)));TraceNode.success(span);
            }finally{span.end();}
            assertTrue(spans.stream().anyMatch(s->s.getName().equals("model.request") && Boolean.TRUE.equals(s.getAttributes().get(AttributeKey.booleanKey("model.dispatched")))));
            assertTrue(spans.stream().anyMatch(s->Optional.ofNullable(s.getAttributes().get(AttributeKey.longKey("model.input_tokens"))).orElse(0L)>0));
            Path directory=Path.of("target/test-traces/compaction-real");Files.createDirectories(directory);
            var lines=new ArrayList<String>();for(var data:spans)lines.add(json.writeValueAsString(TraceEnvelope.from(data,"compaction-real-test","1.0")));Files.write(directory.resolve(name+".jsonl"),lines);
            Files.writeString(directory.resolve(name+"-summary.json"),json.writeValueAsString(summary));return summary;
        }
    }
}
