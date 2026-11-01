package com.tianmu.agent.tool;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Deliberate small schema subset; unsupported declaration types fail during catalog loading. */
public final class ToolArguments {
    private ToolArguments() {}
    public static String text(ToolCall call, String name) {
        Object value = call.arguments().get(name);
        if (!(value instanceof String text) || text.isBlank()) throw invalid();
        return text;
    }
    public static long integer(ToolCall call, String name) {
        Object value = call.arguments().get(name);
        if (!(value instanceof Number)) throw invalid();
        return new BigDecimal(value.toString()).longValueExact();
    }
    public enum Problem {
        UNEXPECTED_FIELD("Remove arguments not declared by this tool."),
        REQUIRED("Supply this required field."), STRING("Use a nonblank string."),
        INTEGER("Use an integer representable as a signed 64-bit number."),
        RANGE("Use a value within the declared minimum and maximum."),
        LENGTH("Use a string within the declared length bounds."),
        DATE("Use YYYY-MM-DD with a valid calendar date."),
        ENUM("Use a value from the declared enum."), ARRAY("Use a native JSON array."),
        ARRAY_SIZE("Use an array within the declared item-count bounds."),
        OBJECT("Use a JSON object with the declared fields.");
        private final String hint;
        Problem(String hint) { this.hint=hint; }
        public String hint() { return hint; }
    }
    public record Issue(String path, Problem code) {
        public Issue {
            if(path==null || path.length()>128 || !path.matches("[A-Za-z0-9_.$\\[\\]]+") || code==null)
                throw new IllegalArgumentException("Invalid validation issue");
        }
    }
    public record Validation(List<Issue> issues, boolean truncated) {
        public Validation {
            issues=List.copyOf(issues);
            if(issues.size()>8 || truncated && issues.isEmpty())throw new IllegalArgumentException("Invalid validation issues");
        }
        public boolean valid() { return issues.isEmpty(); }
    }
    public static Validation inspect(ToolCall call, ToolDefinition definition) {
        var collector=new Collector();collector.object(call.arguments(),definition.inputSchema(),"");
        return new Validation(collector.issues,collector.truncated);
    }
    public static void validate(ToolCall call, ToolDefinition definition) {
        if(!inspect(call,definition).valid())throw invalid();
    }
    private static final class Collector {
        final List<Issue> issues=new ArrayList<>();boolean truncated;
        void add(String path,Problem problem) {
            if(issues.size()==8){truncated=true;return;}
            // Only trusted identifier paths and generated array indexes reach feedback; never echo unknown keys.
            String safe=path.isEmpty()?"$":path;
            if(safe.length()>128 || !safe.matches("[A-Za-z0-9_.$\\[\\]]+"))safe="$";
            issues.add(new Issue(safe,problem));
        }
        void object(Map<?,?> values,Map<?,?> schema,String parent) {
            if(truncated)return;
            Map<?,?> properties=(Map<?,?>)schema.get("properties");List<?> required=(List<?>)schema.get("required");
            if(!properties.keySet().containsAll(values.keySet()))add(parent,Problem.UNEXPECTED_FIELD);
            for(String key:properties.keySet().stream().map(Object::toString).sorted().toList()) {
                if(truncated)return;
                String path=parent.isEmpty()?key:parent+"."+key;
                if(!values.containsKey(key)){if(required.contains(key))add(path,Problem.REQUIRED);continue;}
                field(values.get(key),(Map<?,?>)properties.get(key),path);
            }
        }
        void field(Object raw,Map<?,?> schema,String path) {
            if("array".equals(schema.get("type"))) {
                if(!(raw instanceof List<?> items)){add(path,Problem.ARRAY);return;}
                if(items.size()<((Number)schema.get("minItems")).intValue() || items.size()>((Number)schema.get("maxItems")).intValue()){
                    add(path,Problem.ARRAY_SIZE);return;
                }
                for(int i=0;i<items.size() && !truncated;i++) {
                    String itemPath=path+"["+i+"]";
                    if(!(items.get(i) instanceof Map<?,?> values))add(itemPath,Problem.OBJECT);
                    else object(values,(Map<?,?>)schema.get("items"),itemPath);
                }
                return;
            }
            if("integer".equals(schema.get("type"))) {
                if(!(raw instanceof Number)){add(path,Problem.INTEGER);return;}
                final long value;
                try {value=new BigDecimal(raw.toString()).longValueExact();}
                catch(NumberFormatException | ArithmeticException invalid){add(path,Problem.INTEGER);return;}
                if(schema.containsKey("minimum") && BigDecimal.valueOf(value).compareTo(new BigDecimal(schema.get("minimum").toString()))<0
                        || schema.containsKey("maximum") && BigDecimal.valueOf(value).compareTo(new BigDecimal(schema.get("maximum").toString()))>0){
                    add(path,Problem.RANGE);return;
                }
            } else {
                if(!(raw instanceof String value) || value.isBlank()){add(path,Problem.STRING);return;}
                if(schema.containsKey("minLength") && value.length()<((Number)schema.get("minLength")).intValue()
                        || schema.containsKey("maxLength") && value.length()>((Number)schema.get("maxLength")).intValue()){
                    add(path,Problem.LENGTH);return;
                }
                if("date".equals(schema.get("format"))) {
                    try {LocalDate.parse(value);}catch(java.time.format.DateTimeParseException invalid){add(path,Problem.DATE);return;}
                }
            }
            if(schema.containsKey("enum") && !((List<?>)schema.get("enum")).contains(raw))add(path,Problem.ENUM);
        }
    }
    private static AgentException invalid() { return new AgentException("INVALID_ARGUMENTS", "Tool arguments do not match the declared schema"); }
}
