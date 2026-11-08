package com.tianmu.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.memory.PersonalMemory.*;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.harness.RuntimeHarness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.*;

@Service @ConditionalOnProperty(name="agent.memory.enabled",havingValue="true")
public final class MemoryToolService {
    public static final Set<String> NAMES=Set.of("search_memory","read_memory","remember_memory","forget_memory");
    @org.springframework.beans.factory.annotation.Autowired(required=false) private MemoryConsolidation learning;
    private final PersonalMemoryStore store;
    private final MemoryLedger ledger;
    private final MemoryRecallService recall;
    private final RuntimeHarness harness;
    private final ObjectProvider<MemoryModelPort> models;
    private final ObjectMapper json;
    public MemoryToolService(PersonalMemoryStore store,MemoryLedger ledger,MemoryRecallService recall,RuntimeHarness harness,ObjectProvider<MemoryModelPort> models,ObjectMapper json){this.store=store;this.ledger=ledger;this.recall=recall;this.harness=harness;this.models=models;this.json=json;}
    public boolean available(String name,RunState run){
        var s=store.settings(run.owner);if(s.mode()==Mode.OFF)return false;
        return !Set.of("remember_memory","forget_memory").contains(name) || s.mode()==Mode.ON && (management(run.message) || MemoryIntent.save(run.message,run.memory().recent()));
    }
    public List<MemoryTypes.Message> inherit(RunState run,List<MemoryTypes.Message> messages){return ledger.inherit(run,messages);}
    public static boolean management(String text){return text!=null && text.matches("(?s).*(记住|记一下|记下|记忆|忘记|忘掉|存一下|存起来).*");}
    private Scope scope(RunState run,Map<String,Object> args){
        String type=Objects.toString(args.get("scope"),"USER");
        if(type.equals("VIDEO")){if(run.pageContext()==null)throw PersonalMemory.invalid();return new Scope(ScopeType.VIDEO,run.pageContext().videoId());}
        if(!type.equals("USER"))throw PersonalMemory.invalid();return Scope.user();
    }
    private void validateAction(RunState run,String action,String text){
        if(!management(run.message) && !MemoryIntent.save(run.message,run.memory().recent()))throw new AgentException("MEMORY_ACTION_DENIED","当前消息没有明确授权记忆操作。");
        var model=models.getIfAvailable();if(model==null)throw new AgentException("MEMORY_UNAVAILABLE","Memory action validation is unavailable");
        boolean allowed=harness.stage(run,"agent.memory.action_check",null,null,Map.of(),()->harness.toolCall(run,c->{
            run.preparationCallStarted();
            var result=model.evaluate("你是个人记忆操作准入检查器。结合近期可信用户消息检查当前用户原话是否明确要求执行 action，以及拟保存内容是否由用户本人明确提供。引用、反问、否定、假设、视频或他人的话均不能授权。内容需完全由用户原话支持，允许当前“存”“是的”承接前文，不能以助手建议作为事实；当前情绪、敏感画像、凭据不允许保存。删除必须明确指定这一个 memory_id；用户只说忘掉某类内容不授权任意选择。只返回 JSON {\"allowed\":true或false}。",Map.of("user_message",run.message,"recent_messages",run.memory().recent(),"action",action,"proposal",text),64,c);
            return result.isObject() && result.size()==1 && result.path("allowed").isBoolean() && result.path("allowed").booleanValue();
        }));
        if(!allowed)throw new AgentException("MEMORY_ACTION_DENIED","请明确要记住或忘记的具体内容，或在记忆管理中确认。");
    }
    @SuppressWarnings("unchecked") private Map<String,Object> data(Object value){return json.convertValue(value,Map.class);}
    public ToolResult execute(RunState run,ToolCall call){
        if(!available(call.name(),run))throw new AgentException("MEMORY_DISABLED","Memory tools are disabled for this request");
        recall.verify(run);var args=call.arguments();var b=ledger.boundary(run.owner,run.id);if(b==null)throw MemoryLedger.invalidated();
        return switch(call.name()) {
            case "search_memory" -> {
                String query=Objects.toString(args.get("query"),"");int limit=((Number)args.getOrDefault("limit",6)).intValue();
                var entries=ledger.candidates(run.owner,scope(run,args),b.sequence(),query,limit);ledger.consume(run,entries);
                yield new ToolResult(call.name(),Map.of("items",entries.stream().map(e->Map.of("id",e.id().toString(),"version",e.version(),"kind",e.content().kind().name(),"text",e.content().text().codePoints().limit(80).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString())).toList(),"scope",scope(run,args).type().name(),"complete",entries.size()<limit),null);
            }
            case "read_memory" -> {
                UUID id=UUID.fromString(args.get("memory_id").toString());long version=((Number)args.get("version")).longValue();var entry=ledger.read(run.owner,id,version,b.sequence());ledger.consume(run,List.of(entry));yield new ToolResult(call.name(),data(entry),null);
            }
            case "remember_memory" -> {
                if(learning!=null && !args.containsKey("memory_id") && !args.containsKey("version")){
                    var receipt=learning.requestSave(run);
                    yield new ToolResult(call.name(),receipt,null,new Answer("已加入后台记忆整理，完成后可在记忆管理中查看。",List.of()),null,null);
                }
                String text=args.get("text").toString();
                var content=new Content(scope(run,args),PersonalMemory.Kind.valueOf(Objects.toString(args.get("kind"),"PREFERENCE")),text,null);
                Receipt receipt;
                if(args.containsKey("memory_id") || args.containsKey("version")){
                    if(!args.containsKey("memory_id") || !args.containsKey("version"))throw PersonalMemory.invalid();
                    UUID id=UUID.fromString(args.get("memory_id").toString());long version=((Number)args.get("version")).longValue();
                    if(!run.message.contains(id.toString()))yield new ToolResult(call.name(),Map.of("confirmation_required",true,"memory_id",id.toString()),null,null,null,"请在记忆管理中选择要修改的条目，或明确回复该记忆编号及新内容："+id);
                    validateAction(run,"correct",id+":"+text);
                    receipt=ledger.correct(run,id,version,content,"memory-correct:"+run.id+":"+id);
                }else{
                    if(run.message.matches("(?s).*(修改|改成|改为|更正|更新|不再|替换).*(记忆|偏好|目标).*"))yield new ToolResult(call.name(),Map.of("confirmation_required",true),null,null,null,"请在记忆管理中选择要修改的条目，或提供记忆编号及新内容。");
                    validateAction(run,"remember",text);
                    receipt=ledger.declare(run,content,"memory-save:"+run.id+":"+Integer.toUnsignedString(text.hashCode()),true);
                }
                yield new ToolResult(call.name(),data(receipt),null);
            }
            case "forget_memory" -> {
                UUID id=UUID.fromString(args.get("memory_id").toString());long version=((Number)args.get("version")).longValue();
                // Exact target selection must originate with the user, not a model-selected search hit.
                if(!run.message.contains(id.toString()))yield new ToolResult(call.name(),Map.of("confirmation_required",true,"memory_id",id.toString()),null,null,null,"请在记忆管理中选择并确认要忘记的条目，或明确回复要忘记的记忆编号："+id);
                validateAction(run,"forget",id.toString());var receipt=ledger.forget(run,id,version,"memory-forget:"+run.id+":"+id);
                yield new ToolResult(call.name(),data(receipt),null,new Answer("已忘记你指定的记忆。",List.of()),null,null);
            }
            default -> throw new AgentException("TOOL_NOT_FOUND","Unknown memory tool");
        };
    }
}
